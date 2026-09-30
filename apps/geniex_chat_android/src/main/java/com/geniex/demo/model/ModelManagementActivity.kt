package com.geniex.demo.model

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.geniex.demo.StartupActivity
import com.geniex.demo.bean.ModelData
import com.geniex.demo.databinding.ActivityModelsBinding
import com.geniex.demo.diagnostics.DiagnosticsLogger
import com.geniex.demo.server.InferenceBridge
import com.geniex.demo.storage.WorkingDirectoryManager
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.ModelManagerWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

class ModelManagementActivity : FragmentActivity() {
    private lateinit var binding: ActivityModelsBinding
    private lateinit var models: List<ModelData>
    private lateinit var adapter: ModelManagementAdapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var states: List<ModelUiState> = emptyList()
    @Volatile private var sdkReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!WorkingDirectoryManager.applyConfigured(this)) {
            startActivity(Intent(this, StartupActivity::class.java))
            finish()
            return
        }
        binding = ActivityModelsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        models = ModelCatalog.load(this)

        adapter = ModelManagementAdapter(::downloadModel, ::selectModel, ::confirmDelete)
        binding.rvModels.layoutManager = LinearLayoutManager(this)
        // RecyclerView's default change animation cross-fades a whole card on
        // every progress update, which looks like flashing/pulsing during large
        // downloads. Progress should update in place instead.
        binding.rvModels.itemAnimator = null
        binding.rvModels.adapter = adapter
        binding.btnModelsBack.setOnClickListener { finish() }
        observeDownloadState()
        GenieXSdk.getInstance().init(
            this,
            object : GenieXSdk.InitCallback {
                override fun onSuccess() {
                    sdkReady = true
                    refreshStates()
                }

                override fun onFailure(reason: String) {
                    DiagnosticsLogger.log("ERROR", "Models", "GenieX SDK init failed: $reason")
                    runOnUiThread {
                        Toast.makeText(this@ModelManagementActivity, "GenieX initialization failed: $reason", Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
    }

    override fun onResume() {
        super.onResume()
        if (sdkReady) refreshStates()
    }

    override fun onDestroy() {
        // Downloads are owned by ModelDownloadService and deliberately survive
        // this Activity being destroyed/backgrounded.
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshStates() {
        scope.launch {
            val activeId = InferenceBridge.activeModelId
            val download = ModelDownloadService.currentState()
            val refreshed = models.map { model ->
                val old = states.firstOrNull { it.model.id == model.id }
                val available = ModelPathResolver.isAvailable(this@ModelManagementActivity, model)
                val persistentFilesPresent =
                    ModelDownloadCoordinator.hasPersistentDownloadFiles(this@ModelManagementActivity, model)
                val thisDownload = download.modelId == model.id
                ModelUiState(
                    model = model,
                    available = available,
                    persistentFilesPresent = persistentFilesPresent,
                    loaded = model.id == activeId,
                    switchesActiveModel = activeId != null && activeId != model.id,
                    downloading = thisDownload && download.isRunning,
                    progress = if (thisDownload && download.isRunning) download.percent else old?.progress,
                    error = when {
                        thisDownload && download.status == ModelDownloadService.Status.FAILED -> download.message
                        thisDownload -> null
                        else -> old?.error
                    },
                )
            }
            updateStates(refreshed)
        }
    }

    private fun downloadModel(model: ModelData) {
        val active = ModelDownloadService.currentState()
        if (active.isRunning) {
            Toast.makeText(
                this,
                "${active.displayName ?: "Another model"} is already downloading in the background.",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        ModelDownloadCoordinator.compatibilityError(model)?.let { message ->
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }
        if (ModelDownloadCoordinator.isAiHub(model) && model.chipset.isNullOrBlank()) {
            Toast.makeText(this, "This AI Hub model has no chipset configured.", Toast.LENGTH_LONG).show()
            return
        }
        setState(model.id) { it.copy(downloading = true, progress = 0, error = null) }
        ModelDownloadService.start(this, model)
    }

    private fun observeDownloadState() {
        scope.launch {
            ModelDownloadService.state.collect { download ->
                val modelId = download.modelId ?: return@collect
                if (download.isRunning) {
                    setState(modelId) {
                        it.copy(downloading = true, progress = download.percent, error = null)
                    }
                } else {
                    refreshStates()
                }
            }
        }
    }

    private fun selectModel(model: ModelData) {
        val activeId = InferenceBridge.activeModelId
        val action = if (activeId == model.id) ACTION_UNLOAD else ACTION_LOAD
        AppPreferences.setSelectedModelId(this, model.id)
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(EXTRA_SELECTED_MODEL_ID, model.id)
                .putExtra(EXTRA_MODEL_ACTION, action),
        )
        finish()
    }

    private fun confirmDelete(model: ModelData) {
        if (InferenceBridge.activeModelId == model.id) {
            Toast.makeText(this, "Unload this model before deleting it.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete model?")
            .setMessage("Delete ${model.displayName} from this device? It can be downloaded again later.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> deleteModel(model) }
            .show()
    }

    private fun deleteModel(model: ModelData) {
        scope.launch {
            val result = runCatching {
                if (ModelDownloadCoordinator.usesPersistentDirectDownload(model)) {
                    val sdkCopyExists = runCatching { ModelManagerWrapper.getPaths(model.modelName) }.getOrNull() != null
                    val localDeleted = ModelDownloadCoordinator.deletePersistentDownload(this@ModelManagementActivity, model)
                    val sdkCode = if (sdkCopyExists) ModelManagerWrapper.remove(model.modelName) else 0
                    if (localDeleted && sdkCode == 0) 0 else if (sdkCode != 0) sdkCode else -1
                } else {
                    ModelManagerWrapper.remove(model.modelName)
                }
            }
            result.onSuccess { code ->
                DiagnosticsLogger.checkpoint("MODEL_DELETE", "${model.modelName} rc=$code")
                runOnUiThread {
                    if (code == 0) {
                        AppPreferences.clearLastLoadedModelIfMatches(this@ModelManagementActivity, model.id)
                        Toast.makeText(this@ModelManagementActivity, "Model deleted.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@ModelManagementActivity, "Delete failed (code $code).", Toast.LENGTH_LONG).show()
                    }
                }
            }.onFailure {
                DiagnosticsLogger.log("ERROR", "ModelDelete", model.modelName, it)
                runOnUiThread { Toast.makeText(this@ModelManagementActivity, "Delete failed: ${it.message}", Toast.LENGTH_LONG).show() }
            }
            refreshStates()
        }
    }

    private fun setState(modelId: String, transform: (ModelUiState) -> ModelUiState) {
        val base = if (states.isEmpty()) models.map { ModelUiState(it) } else states
        updateStates(base.map { if (it.model.id == modelId) transform(it) else it })
    }

    private fun updateStates(newStates: List<ModelUiState>) {
        states = newStates
        runOnUiThread {
            adapter.submitList(newStates)
            val available = newStates.count { it.available }
            val workspace = WorkingDirectoryManager.workspace(this@ModelManagementActivity)?.root?.absolutePath
            val modelStorage = WorkingDirectoryManager.modelStoragePath(this@ModelManagementActivity)
            binding.tvModelStorageSummary.text = buildString {
                append("$available of ${newStates.size} models available")
                if (!modelStorage.isNullOrBlank()) append("\nModels: $modelStorage")
                if (!workspace.isNullOrBlank()) append("\nWorkspace: $workspace")
            }
        }
    }

    companion object {
        const val EXTRA_SELECTED_MODEL_ID = "selected_model_id"
        const val EXTRA_MODEL_ACTION = "model_action"
        const val ACTION_LOAD = "load"
        const val ACTION_UNLOAD = "unload"
    }
}
