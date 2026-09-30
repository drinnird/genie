package com.geniex.demo.model

import android.content.Context
import com.geniex.demo.bean.ModelData
import com.geniex.sdk.ModelManagerWrapper
import java.io.File

/** Unifies SDK-managed models with persistent direct GGUF and Qualcomm QAIRT bundles. */
object ModelPathResolver {
    data class Paths(
        val model_name: String,
        val runtime_id: String,
        val model_path: String,
        val tokenizer_path: String?,
        val mmproj_path: String?,
        val managedBySdk: Boolean,
    )

    suspend fun resolve(context: Context, model: ModelData): Paths? {
        // Precompiled Qualcomm QAIRT bundles are kept in the persistent shared
        // model store and loaded directly. The plugin reads metadata.json from
        // the bundle directory, so no duplicate SDK cache import is required.
        if (ModelDownloadCoordinator.usesQualcommHfQairtDownload(model)) {
            QairtBundleStore.resolve(context, model)?.let { local ->
                if (ModelDownloadCoordinator.isQualcommBundleCompatible(local.chipsetKey)) {
                    return Paths(
                        model_name = local.modelId,
                        runtime_id = "qairt",
                        model_path = local.modelPath,
                        tokenizer_path = local.tokenizerPath,
                        mmproj_path = null,
                        managedBySdk = false,
                    )
                }
            }
            // Do not fall back to an SDK-managed v21 copy with the same model
            // name. Its chipset provenance is not recorded in this catalog and
            // it may be an SM8750 bundle on an SM8850 device (or vice versa).
            return null
        }

        // Standard HTTPS GGUF downloads do not need a native model-manager
        // lookup. Prefer the completion marker + direct path to avoid repeated
        // JNI/cache work on every UI refresh.
        if (ModelDownloadCoordinator.usesStandardHuggingFaceDownload(model)) {
            ModelLocalStore.resolve(context, model)?.let { local ->
                return Paths(
                    model_name = model.modelName,
                    runtime_id = model.runtime ?: "llama_cpp",
                    model_path = local.modelPath,
                    tokenizer_path = null,
                    mmproj_path = local.mmprojPath,
                    managedBySdk = false,
                )
            }
        }

        val sdkPaths = runCatching { ModelManagerWrapper.getPaths(model.modelName) }.getOrNull()
        if (sdkPaths != null && File(sdkPaths.model_path).exists()) {
            return Paths(
                model_name = sdkPaths.model_name,
                runtime_id = sdkPaths.runtime_id.ifBlank { model.runtime ?: "llama_cpp" },
                model_path = sdkPaths.model_path,
                tokenizer_path = sdkPaths.tokenizer_path,
                mmproj_path = sdkPaths.mmproj_path,
                managedBySdk = true,
            )
        }
        return null
    }

    suspend fun isAvailable(context: Context, model: ModelData): Boolean = resolve(context, model) != null
}
