// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.SimpleAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.geniex.demo.bean.ModelData
import com.geniex.demo.bean.getSupportPluginIds
import com.geniex.demo.bean.isNpuModel
import com.geniex.demo.databinding.ActivityMainBinding
import com.geniex.demo.databinding.DialogSelectPluginIdBinding
import com.geniex.demo.listeners.CustomDialogInterface
import com.geniex.demo.diagnostics.DiagnosticsActivity
import com.geniex.demo.diagnostics.DiagnosticsLogger
import com.geniex.demo.model.AppPreferences
import com.geniex.demo.model.ModelManagementActivity
import com.geniex.demo.server.InferenceBridge
import com.geniex.demo.server.LocalApiServer
import com.geniex.demo.server.ServerActivity
import com.geniex.demo.utils.ExecShell
import com.geniex.demo.utils.GgufVisionConfig
import com.geniex.demo.utils.GgufVisionReader
import com.geniex.demo.utils.ImgUtil
import com.geniex.demo.utils.inflate
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.VlmWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ModelPullInput
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import com.geniex.sdk.bean.VlmCreateInput
import com.gyf.immersionbar.ktx.immersionBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : FragmentActivity() {
    private val binding: ActivityMainBinding by inflate()
    private var downloadJob: Job? = null
    private var downloadingModelData: ModelData? = null
    private lateinit var llDownloading: LinearLayout
    private lateinit var tvDownloadProgress: TextView
    private lateinit var pbDownloading: ProgressBar
    private lateinit var spModelList: Spinner
    private lateinit var btnDownload: Button
    private lateinit var btnLoadModel: Button
    private lateinit var btnUnloadModel: Button
    private lateinit var btnStop: Button
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button
    private lateinit var btnClearHistory: Button
    private lateinit var btnAddImage: Button
    private lateinit var tvSelectedModel: TextView
    private lateinit var tvSelectedModelStatus: TextView
    private lateinit var tvServerStatusCompact: TextView
    private lateinit var btnModels: Button
    private lateinit var btnServer: Button
    private lateinit var btnDiagnostics: Button

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ChatAdapter

    private lateinit var scrollImages: HorizontalScrollView
    private lateinit var topScrollContainer: LinearLayout
    private lateinit var llLoading: LinearLayout
    private lateinit var vTip: View

    private lateinit var llmWrapper: LlmWrapper
    private lateinit var vlmWrapper: VlmWrapper
    private val modelScope = CoroutineScope(Dispatchers.IO)

    private val chatList = arrayListOf<ChatMessage>()
    private val vlmChatList = arrayListOf<VlmChatMessage>()
    private lateinit var modelList: List<ModelData>
    private var selectModelId = ""

    private var isLoadLlmModel = false
    private var isLoadVlmModel = false

    /**
     * Vision geometry of the currently loaded VLM, read from its mmproj GGUF.
     * Null for LLM-only models, and when the mmproj declares nothing usable —
     * image preprocessing then falls back to [FALLBACK_VLM_IMAGE_SIZE].
     */
    private var vlmVisionConfig: GgufVisionConfig? = null

    private var enableThinking = false
    private var isGenerating = false

    private val savedImageFiles = mutableListOf<File>()
    private val messages = arrayListOf<Message>()
    private var loadingMessageIndex: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        immersionBar {
            statusBarColorInt(Color.WHITE)
            statusBarDarkFont(true)
        }
        initData()
        initView()
        setListeners()
        showInterruptedLoadWarning()
    }

    override fun onResume() {
        super.onResume()
        if (::modelList.isInitialized && ::spModelList.isInitialized) {
            syncSelectedModelFromPreferences()
            refreshSelectedModelUi()
            refreshServerStatusUi()
        }
    }

    private fun resetLoadState() {
        isLoadLlmModel = false
        isLoadVlmModel = false
        // Stale geometry would size preprocessing for the previous model.
        vlmVisionConfig = null
    }

    private fun initView() {
        adapter = ChatAdapter(messages)
        binding.rvChat.adapter = adapter

        llDownloading = findViewById(R.id.ll_downloading)
        tvDownloadProgress = findViewById(R.id.tv_download_progress)
        pbDownloading = findViewById(R.id.pb_downloading)
        spModelList = findViewById(R.id.sp_model_list)
        spModelList.adapter =
            object : SimpleAdapter(
                this,
                modelList.map {
                    val map = mutableMapOf<String, String>()
                    map["displayName"] = it.displayName
                    map
                },
                R.layout.item_model,
                arrayOf("displayName"),
                intArrayOf(R.id.tv_model_id),
            ) {
            }
        spModelList.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    selectModelId = modelList[position].id

                    messages.clear()
                    adapter.notifyDataSetChanged()
                    binding.rvChat.scrollTo(0, 0)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    selectModelId = ""
                }
            }
        btnDownload = findViewById(R.id.btn_download)
        btnLoadModel = findViewById(R.id.btn_load_model)
        btnUnloadModel = findViewById(R.id.btn_unload_model)
        btnStop = findViewById(R.id.btn_stop)
        etInput = findViewById(R.id.et_input)
        btnAddImage = findViewById(R.id.btn_add_image)
        tvSelectedModel = findViewById(R.id.tv_selected_model)
        tvSelectedModelStatus = findViewById(R.id.tv_selected_model_status)
        tvServerStatusCompact = findViewById(R.id.tv_server_status_compact)
        btnModels = findViewById(R.id.btn_models)
        btnServer = findViewById(R.id.btn_server)
        btnDiagnostics = findViewById(R.id.btn_diagnostics)

        btnSend = findViewById(R.id.btn_send)
        btnSend.isEnabled = false
        etInput.doAfterTextChanged { refreshSendButtonState() }
        btnClearHistory = findViewById(R.id.btn_clear_history)
        scrollImages = findViewById(R.id.scroll_images)
        topScrollContainer = findViewById(R.id.ll_images_container)
        llLoading = findViewById(R.id.ll_loading)
        vTip = findViewById<View>(R.id.v_tip)
        syncSelectedModelFromPreferences()
        refreshSelectedModelUi()
        refreshServerStatusUi()

        findViewById<Button>(R.id.btn_test).setOnClickListener {
            Thread {
                val exeFile = File(filesDir, "geniex_test_llm")
                val chmodProcess = Runtime.getRuntime().exec("chmod 755 " + exeFile.absolutePath)
                chmodProcess.waitFor()
                Log.d(TAG, "exeFile exe? ${exeFile.canExecute()}")
                Log.d(TAG, "Exe Thread:${Thread.currentThread().name}")
                ExecShell()
                    .executeCommand(
                        arrayOf(
                            "cat",
                            "/sys/devices/soc0/sku",
                        ),
                    ).forEach {
                        Log.d(TAG, "cmd:$it")
                    }
            }.start()
        }

        findViewById<View>(R.id.v_tip).setOnClickListener {
            Toast.makeText(this, "please unload model first", Toast.LENGTH_SHORT).show()
        }
    }

    private fun syncSelectedModelFromPreferences() {
        if (modelList.isEmpty()) return
        val preferred = AppPreferences.getSelectedModelId(this)
        val index = modelList.indexOfFirst { it.id == preferred }.let { if (it >= 0) it else 0 }
        if (preferred == null) AppPreferences.setSelectedModelId(this, modelList[index].id)
        if (spModelList.selectedItemPosition != index) spModelList.setSelection(index)
        selectModelId = modelList[index].id
    }

    private fun refreshSelectedModelUi() {
        val model = modelList.firstOrNull { it.id == selectModelId }
        if (model == null) {
            tvSelectedModel.text = "No model selected"
            tvSelectedModelStatus.text = "Open Models to choose a model"
            return
        }
        tvSelectedModel.text = model.displayName
        modelScope.launch {
            val available = runCatching { isModelDownloaded(model) }.getOrDefault(false)
            runOnUiThread {
                val active = InferenceBridge.activeModelId == model.id
                tvSelectedModelStatus.text = when {
                    active -> "Active • ${model.quant ?: model.runtime.orEmpty()} • ${InferenceBridge.requestedComputeUnit?.uppercase() ?: model.computeSummary}"
                    available -> "Available • ${model.quant ?: model.runtime.orEmpty()} • ${model.computeSummary}"
                    else -> "Not downloaded • ${model.quant ?: model.runtime.orEmpty()} • ${model.computeSummary}"
                }
            }
        }
    }

    private fun refreshServerStatusUi() {
        tvServerStatusCompact.text = if (LocalApiServer.isRunning()) {
            "API • ${LocalApiServer.lanUrl() ?: LocalApiServer.localhostUrl()}"
        } else {
            "API server off"
        }
    }

    private fun showInterruptedLoadWarning() {
        if (!DiagnosticsLogger.wasModelLoadInterrupted()) return
        val details = DiagnosticsLogger.interruptedModelDetails().ifBlank { "Unknown model" }
        AlertDialog.Builder(this)
            .setTitle("Previous model load did not complete")
            .setMessage("The previous app process ended while loading a model.\n\n$details\n\nDiagnostics were preserved for troubleshooting.")
            .setNegativeButton("Dismiss") { _, _ ->
                DiagnosticsLogger.acknowledgeInterruptedModelLoad()
            }
            .setPositiveButton("Diagnostics") { _, _ ->
                DiagnosticsLogger.acknowledgeInterruptedModelLoad()
                startActivity(Intent(this, DiagnosticsActivity::class.java))
            }
            .setOnCancelListener { DiagnosticsLogger.acknowledgeInterruptedModelLoad() }
            .show()
    }

    private fun parseModelList() {
        try {
            val baseJson = assets.open("model_list.json").bufferedReader().use { it.readText() }
            val json = Json { ignoreUnknownKeys = true }
            modelList = json.decodeFromString<List<ModelData>>(baseJson)
        } catch (e: Exception) {
            Log.e(TAG, "parseModelList: $e")
        }
    }

    /**
     * Step 0. Parse the model list and initialise the SDK. Model presence
     * is queried from the Rust model manager, not tracked client-side.
     */
    private fun initData() {
        parseModelList()
        initGenieXSdk()
    }

    /**
     * Step 1. initGenieXSdk environment
     */
    private fun initGenieXSdk() {
        GenieXSdk.getInstance().init(
            this,
            object : GenieXSdk.InitCallback {
                override fun onSuccess() {
                    DiagnosticsLogger.log("INFO", TAG, "GenieX SDK initialized")
                }

                override fun onFailure(reason: String) {
                    Log.e(TAG, "GenieXSdk init failed: $reason")
                    DiagnosticsLogger.log("ERROR", TAG, "GenieX SDK init failed: $reason")
                }
            },
        )
    }

    private fun onLoadModelSuccess(tip: String) {
        runOnUiThread {
            Toast
                .makeText(
                    this@MainActivity,
                    tip,
                    Toast.LENGTH_SHORT,
                ).show()
            // change UI
            btnAddImage.visibility = View.INVISIBLE
            if (isLoadVlmModel) {
                btnAddImage.visibility = View.VISIBLE
            }
            btnUnloadModel.visibility = View.VISIBLE
            llLoading.visibility = View.INVISIBLE
            btnStop.visibility = View.VISIBLE
            refreshSendButtonState()
            refreshSelectedModelUi()
            refreshServerStatusUi()
        }
    }

    private fun onLoadModelFailed(tip: String) {
        runOnUiThread {
            vTip.visibility = View.GONE
            Toast.makeText(this@MainActivity, tip, Toast.LENGTH_SHORT).show()
            // change UI
            btnAddImage.visibility = View.INVISIBLE
            btnUnloadModel.visibility = View.GONE
            llLoading.visibility = View.INVISIBLE
            refreshSelectedModelUi()
        }
        DiagnosticsLogger.markModelLoadFailed(tip)
    }

    private fun hasLoadedModel(): Boolean = isLoadLlmModel || isLoadVlmModel

    /**
     * Send is enabled only when (a) a model is loaded, (b) no inference
     * is in flight, and (c) there is something to send — text or an
     * attached image (VLM only).
     */
    private fun refreshSendButtonState() {
        runOnUiThread {
            val hasText = etInput.text?.isNotBlank() == true
            val hasAttachment = savedImageFiles.isNotEmpty()
            btnSend.isEnabled = hasLoadedModel() && !isGenerating && (hasText || hasAttachment)
        }
    }

    /**
     * Checks the Rust model manager's cache for [modelData]. Uses
     * `getPaths`, which canonicalises the name (so `ai-hub-models/<repo>`
     * and `qualcomm/<repo>` map to the same on-disk entry) and returns
     * null while the pull is still in `.inflight/`.
     */
    private suspend fun isModelDownloaded(modelData: ModelData): Boolean = ModelManagerWrapper.getPaths(modelData.modelName) != null

    private fun loadModel(
        selectModelData: ModelData,
        modelDataPluginId: String,
        nGpuLayers: Int,
        deviceId: String? = null,
    ) {
        val requestedCompute = deviceId ?: ComputeUnitValue.NPU.value
        DiagnosticsLogger.markModelLoadStart(
            "model=${selectModelData.modelName} quant=${selectModelData.quant.orEmpty()} runtime=${selectModelData.runtime.orEmpty()} compute=$requestedCompute",
        )
        modelScope.launch {
            resetLoadState()
            val paths = ModelManagerWrapper.getPaths(selectModelData.modelName)
            if (paths == null) {
                onLoadModelFailed("model paths unavailable — pull it first")
                return@launch
            }
            // Manifest-written runtime_id wins when present; fall back to
            // the user's UI selection for GGUF models that skip the manifest.
            val pluginId = paths.runtime_id.ifEmpty { modelDataPluginId }
            val resolvedDeviceId = deviceId
            when (selectModelData.type) {
                "chat", "llm" -> {
                    // QAIRT rejects non-zero n_ctx / n_gpu_layers (both fixed at compile
                    // time in the AI Hub bundle) — and the Kotlin ModelConfig defaults
                    // are non-zero, so zero them explicitly for the qairt path.
                    val isQairt = pluginId == "qairt"
                    val conf =
                        if (isQairt) {
                            ModelConfig(nCtx = 0, nGpuLayers = 0, enable_thinking = enableThinking)
                        } else {
                            ModelConfig(
                                nCtx = 1024,
                                nGpuLayers = nGpuLayers,
                                enable_thinking = enableThinking,
                            )
                        }
                    LlmWrapper
                        .builder()
                        .llmCreateInput(
                            LlmCreateInput(
                                model_name = paths.model_name,
                                model_path = paths.model_path,
                                tokenizer_path = paths.tokenizer_path,
                                config = conf,
                                runtime_id = pluginId,
                                compute_unit = resolvedDeviceId ?: ComputeUnitValue.NPU.value,
                            ),
                        ).build()
                        .onSuccess { wrapper ->
                            isLoadLlmModel = true
                            llmWrapper = wrapper
                            InferenceBridge.setLlm(wrapper, selectModelData.id, selectModelData.displayName, requestedCompute)
                            DiagnosticsLogger.markModelLoadComplete("${selectModelData.modelName} compute=$requestedCompute")
                            onLoadModelSuccess("LLM model loaded")
                        }.onFailure { error ->
                            onLoadModelFailed(error.message.toString())
                        }
                }

                "multimodal", "vlm" -> {
                    val isNpuVlm = pluginId == "qairt"
                    // Size image preprocessing from the tower this model actually
                    // ships, not from a constant: Qwen3.5-VL is 768/16 (576
                    // tokens) but Qwen2.5-VL is 560/14 (1600), so one hardcoded
                    // number mis-sizes every other model in the catalog.
                    vlmVisionConfig =
                        paths.mmproj_path?.takeIf { it.isNotEmpty() }?.let { GgufVisionReader.read(File(it)) }
                    vlmVisionConfig?.let {
                        Log.d(
                            TAG,
                            "vision tower: ${it.imageSize}px, patch ${it.patchSize}, " +
                                "merge ${it.spatialMergeSize} -> ${it.tokenCount} image tokens",
                        )
                    } ?: Log.w(TAG, "no vision config from mmproj; preprocessing at $FALLBACK_VLM_IMAGE_SIZE")
                    val config =
                        if (isNpuVlm) {
                            // QAIRT rejects non-zero n_ctx / n_gpu_layers for VLM too.
                            ModelConfig(nCtx = 0, nGpuLayers = 0, nThreads = 8, enable_thinking = enableThinking)
                        } else {
                            ModelConfig(
                                // One image costs tokenCount tokens (576 on
                                // Qwen3.5-VL, 1600 on Qwen2.5-VL). nCtx = 1024
                                // left too little room for the prompt plus a
                                // reply, and a second image turn died inside
                                // mtmd_tokenize with "failed to initialize
                                // batch". Leave room for an image, its answer and
                                // a follow-up turn.
                                nCtx = vlmContextSize(vlmVisionConfig),
                                nThreads = 4,
                                nBatch = 1,
                                nUBatch = 1,
                                nGpuLayers = nGpuLayers,
                                enable_thinking = enableThinking,
                            )
                        }
                    VlmWrapper
                        .builder()
                        .vlmCreateInput(
                            VlmCreateInput(
                                model_name = paths.model_name,
                                model_path = paths.model_path,
                                mmproj_path = paths.mmproj_path,
                                config = config,
                                runtime_id = pluginId,
                                compute_unit = resolvedDeviceId ?: ComputeUnitValue.NPU.value,
                            ),
                        ).build()
                        .onSuccess {
                            isLoadVlmModel = true
                            vlmWrapper = it
                            InferenceBridge.setVlm(it, selectModelData.id, selectModelData.displayName, requestedCompute)
                            DiagnosticsLogger.markModelLoadComplete("${selectModelData.modelName} compute=$requestedCompute")
                            onLoadModelSuccess("VLM model loaded")
                        }.onFailure { error ->
                            onLoadModelFailed(error.message.toString())
                        }
                }

                else -> {
                    onLoadModelFailed("model type error")
                }
            }
        }
    }

    private fun downloadModel(selectModelData: ModelData) {
        if (hasLoadedModel()) {
            Toast.makeText(this@MainActivity, "unload the current model first", Toast.LENGTH_SHORT).show()
            return
        }
        if (downloadJob?.isActive == true) {
            Toast
                .makeText(
                    this@MainActivity,
                    "${downloadingModelData?.displayName ?: "a model"} is already downloading",
                    Toast.LENGTH_SHORT,
                ).show()
            return
        }

        downloadingModelData = selectModelData
        llDownloading.visibility = View.VISIBLE
        tvDownloadProgress.text = "0%"

        val hub =
            runCatching { HubSource.valueOf(selectModelData.hub ?: "AUTO") }
                .getOrDefault(HubSource.AUTO)
        // AI Hub pulls route through chipset-matched assets. The Rust side
        // can auto-detect the host only on Windows-on-Snapdragon, so on
        // Android we must pass an explicit chipset for anything that ends
        // up on the AI Hub path — whether hub is AIHUB or AUTO + ai-hub-models/*
        // (or its canonical alias qualcomm/*).
        val name = selectModelData.modelName
        val isAiHubName =
            name.startsWith("ai-hub-models/", ignoreCase = true) ||
                name.startsWith("qualcomm/", ignoreCase = true)
        val willUseAiHub =
            hub == HubSource.AIHUB ||
                (hub == HubSource.AUTO && isAiHubName)
        if (willUseAiHub && selectModelData.chipset.isNullOrBlank()) {
            llDownloading.visibility = View.GONE
            Toast.makeText(this@MainActivity, "AI Hub models require a chipset. Update model_list.json.", Toast.LENGTH_SHORT).show()
            return
        }
        val input =
            ModelPullInput(
                model_name = selectModelData.modelName,
                precision = selectModelData.quant,
                hub = hub,
                chipset = selectModelData.chipset,
                display_name = selectModelData.aiHubDisplayName,
            )

        val wakeLock =
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "geniex:model_download")
        wakeLock.acquire()
        downloadJob =
            modelScope.launch {
                try {
                    // Short-circuit if already cached — the manager filters .inflight/
                    // models out of list(), so this only matches a complete pull.
                    if (isModelDownloaded(selectModelData)) {
                        runOnUiThread {
                            llDownloading.visibility = View.GONE
                            Toast.makeText(this@MainActivity, "model already downloaded", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }

                    ModelManagerWrapper.pullFlow(input).collect { event ->
                        when (event) {
                            is ModelManagerWrapper.PullEvent.Progress -> {
                                val total = event.files.sumOf { if (it.total_bytes > 0) it.total_bytes else 0L }
                                val done = event.files.sumOf { it.downloaded_bytes }
                                val percent = if (total > 0) ((done * 100) / total).toInt() else 0
                                runOnUiThread { tvDownloadProgress.text = "$percent%" }
                            }

                            is ModelManagerWrapper.PullEvent.Completed -> {
                                runOnUiThread {
                                    llDownloading.visibility = View.GONE
                                    Toast
                                        .makeText(
                                            this@MainActivity,
                                            "${selectModelData.displayName} downloaded",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                }
                            }

                            is ModelManagerWrapper.PullEvent.Error -> {
                                Log.e(TAG, "pull failed rc=${event.code}: ${event.message}")
                                runOnUiThread {
                                    llDownloading.visibility = View.GONE
                                    Toast
                                        .makeText(
                                            this@MainActivity,
                                            "Download failed. Please check your network connection and try again.",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                }
                            }
                        }
                    }
                } finally {
                    if (wakeLock.isHeld) wakeLock.release()
                }
            }
    }

    private fun setListeners() {
        btnModels.setOnClickListener {
            startActivity(Intent(this, ModelManagementActivity::class.java))
        }
        btnServer.setOnClickListener {
            startActivity(Intent(this, ServerActivity::class.java))
        }
        btnDiagnostics.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        btnAddImage.setOnClickListener {
            openGallery()
        }

        btnClearHistory.setOnClickListener {
            clearHistory()
        }
        /*
         * Step 3. download model. Cancelling the coroutine triggers the
         * flow's awaitClose which flips the Rust progress callback to
         * return false — partial files stay on disk for a resumed pull.
         * Use the Retry button to kick off a fresh pull that resumes.
         */
        binding.btnCancelDownload.setOnClickListener {
            downloadJob?.cancel()
            downloadJob = null
            tvDownloadProgress.text = "0%"
            binding.llDownloading.visibility = View.GONE
        }
        binding.btnRetryDownload.setOnClickListener {
            downloadJob?.cancel()
            downloadJob = null
            downloadingModelData?.let { downloadModel(it) }
        }
        btnDownload.setOnClickListener {
            if (downloadJob?.isActive == true) {
                if (downloadingModelData?.id == selectModelId) {
                    binding.llDownloading.visibility = View.VISIBLE
                } else {
                    Toast
                        .makeText(
                            this@MainActivity,
                            "${downloadingModelData?.displayName} is currently downloading.",
                            Toast.LENGTH_SHORT,
                        ).show()
                }
                return@setOnClickListener
            }
            val selectModelData = modelList.first { it.id == selectModelId }
            downloadModel(selectModelData)
        }
        /*
         * Step 4. load model
         */
        btnLoadModel.setOnClickListener {
            val selectModelData = modelList.first { it.id == selectModelId }
            Log.d(TAG, "current select model data:$selectModelData")
            if (hasLoadedModel()) {
                Toast.makeText(this@MainActivity, "please unload first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Availability is checked against the manager's cache — a pull
            // that was cancelled mid-flight is not listed until it completes.
            modelScope.launch {
                if (!isModelDownloaded(selectModelData)) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Model not downloaded — open Models and download it first.", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                runOnUiThread { startLoadModel(selectModelData) }
            }
        }

        /*
         * Step 5. send message
         */
        btnSend.setOnClickListener {
            if (!hasLoadedModel()) {
                Toast
                    .makeText(this@MainActivity, "please load model first", Toast.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }
            // Guard against re-entry: a second click while a previous
            // generate() is still running would race on the native handle
            // and crash the app.
            if (isGenerating) return@setOnClickListener
            if (!InferenceBridge.mutex.tryLock()) {
                Toast.makeText(this, "The model is busy serving another request.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            isGenerating = true
            DiagnosticsLogger.checkpoint("INFERENCE_BEGIN", "model=${InferenceBridge.activeModelName.orEmpty()}")
            refreshSendButtonState()

            if (savedImageFiles.isNotEmpty()) {
                messages.add(Message("", MessageType.IMAGES, savedImageFiles.map { it }))
                reloadRecycleView()
            }

            val inputString = etInput.text.trim().toString()
            etInput.setText("")
            etInput.clearFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etInput.windowToken, 0)

            if (inputString.isNotEmpty()) {
                messages.add(Message(inputString, MessageType.USER))
                reloadRecycleView()
            }

            showLoadingIndicator()

            val supportFunctionCall = false
            var tools: String? = null
            if (supportFunctionCall) {
                // if this model support 'function call'
                tools =
                    "[{\"type\":\"function\",\"function\":{\"name\": \"campaign_investigation\",\"description\": \"Check campaign limits and determine appropriate action. If customer has reached limit, return a message (hardcoded or generated by model). If limit not reached, contact support.\",\"parameters\": {\"type\": \"object\", \"properties\":{\"campaign_name\":{\"type\": \"string\",\"description\": \"The name of the campaign to investigate\"}}, \"required\":[\"campaign_name\"]}}}]"
            }

            if (!hasLoadedModel()) {
                Toast.makeText(this@MainActivity, "model not loaded", Toast.LENGTH_SHORT).show()
                isGenerating = false
                InferenceBridge.mutex.unlock()
                refreshSendButtonState()
                return@setOnClickListener
            }

            modelScope.launch {
                try {
                    val selectModelData = modelList.first { it.id == selectModelId }
                    val isNpu = ModelManagerWrapper.getPaths(selectModelData.modelName)?.runtime_id == "qairt"
                    Log.d(TAG, "isNpu: $isNpu")

                    val sb = StringBuilder()
                    if (isLoadVlmModel) {
                        val contents =
                            savedImageFiles
                                .map {
                                    VlmContent("image", it.absolutePath)
                                }.toMutableList()
                        contents.add(VlmContent("text", inputString))
                        clearImages()
                        val sendMsg = VlmChatMessage(role = "user", contents = contents)
                        vlmChatList.add(sendMsg)

                        Log.d(TAG, "applying VLM chat template; turns=${vlmChatList.size}")
                        vlmWrapper
                            .applyChatTemplate(vlmChatList.toTypedArray(), tools, enableThinking)
                            .onSuccess { result ->
                                Log.d(TAG, "VLM chat template prepared; chars=${result.formattedText.length}")
                                val baseConfig =
                                    GenerationConfigSample().toGenerationConfig()
                                // Only inject the current turn's media: SDK tokenizes
                                // incrementally, so re-passing history bitmaps breaks
                                // mtmd_tokenize (markers/bitmaps mismatch).
                                val configWithMedia =
                                    vlmWrapper.injectMediaPathsToConfig(
                                        arrayOf(sendMsg),
                                        baseConfig,
                                    )

                                Log.d(TAG, "Config has ${configWithMedia.imageCount} images")

                                vlmWrapper
                                    .generateStreamFlow(
                                        result.formattedText,
                                        configWithMedia,
                                    ).collect { handleResult(sb, it) }
                            }.onFailure {
                                runOnUiThread {
                                    Toast
                                        .makeText(
                                            this@MainActivity,
                                            it.message,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                }
                            }
                    } else {
                        chatList.add(ChatMessage(role = "user", inputString))
                        // Apply chat template and generate
                        llmWrapper
                            .applyChatTemplate(
                                chatList.toTypedArray(),
                                tools,
                                enableThinking,
                            ).onSuccess { templateOutput ->
                                Log.d(TAG, "LLM chat template prepared; chars=${templateOutput.formattedText.length}")
                                llmWrapper
                                    .generateStreamFlow(
                                        templateOutput.formattedText,
                                        GenerationConfigSample().toGenerationConfig(),
                                    ).collect { streamResult ->
                                        handleResult(sb, streamResult)
                                    }
                            }.onFailure { error ->
                                runOnUiThread {
                                    Toast
                                        .makeText(
                                            this@MainActivity,
                                            error.message,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                }
                            }
                    }

                    clearImages()
                } finally {
                    removeLoadingIndicator()
                    isGenerating = false
                    if (InferenceBridge.mutex.isLocked) InferenceBridge.mutex.unlock()
                    DiagnosticsLogger.checkpoint("INFERENCE_COMPLETE", "model=${InferenceBridge.activeModelName.orEmpty()}")
                    refreshSendButtonState()
                }
            }
        }

        /*
         * Step 6. others
         */
        btnUnloadModel.setOnClickListener {
            if (!hasLoadedModel()) {
                Toast.makeText(this@MainActivity, "model not loaded", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // The native model handle is shared with the HTTP server. Never
            // destroy it while either UI or API inference owns the mutex.
            if (!InferenceBridge.mutex.tryLock()) {
                Toast.makeText(this@MainActivity, "Model is busy. Try again when inference finishes.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Unload model and cleanup
            val handleUnloadResult = fun(result: Int) {
                resetLoadState()
                InferenceBridge.clear()
                DiagnosticsLogger.checkpoint("MODEL_UNLOAD")
                chatList.clear()
                vlmChatList.clear()
                runOnUiThread {
                    vTip.visibility = View.GONE
                    btnUnloadModel.visibility = View.GONE
                    btnStop.visibility = View.GONE
                    btnAddImage.visibility = View.INVISIBLE
                    messages.clear()
                    clearImages()
                    reloadRecycleView()
                    Toast
                        .makeText(
                            this@MainActivity,
                            if (result == 0) {
                                "unload success"
                            } else {
                                "unload failed and error code: $result"
                            },
                            Toast.LENGTH_SHORT,
                        ).show()
                    refreshSendButtonState()
                    refreshSelectedModelUi()
                    refreshServerStatusUi()
                }
            }
            modelScope.launch {
                try {
                    if (isLoadVlmModel) {
                        vlmWrapper.stopStream()
                        vlmWrapper.destroy()
                        vlmChatList.clear()
                        handleUnloadResult(0)
                    } else if (isLoadLlmModel) {
                        llmWrapper.stopStream()
                        llmWrapper.destroy()
                        chatList.clear()
                        handleUnloadResult(0)
                    } else {
                        handleUnloadResult(0)
                    }
                } finally {
                    if (InferenceBridge.mutex.isLocked) InferenceBridge.mutex.unlock()
                }
            }
        }
        btnStop.setOnClickListener {
            if (!hasLoadedModel()) {
                Toast
                    .makeText(
                        this@MainActivity,
                        "model not loaded",
                        Toast.LENGTH_SHORT,
                    ).show()
                return@setOnClickListener
            }
            // Stop streaming
            modelScope.launch {
                if (isLoadVlmModel) {
                    vlmWrapper.stopStream()
                } else if (isLoadLlmModel) {
                    llmWrapper.stopStream()
                }
            }
        }
    }

    private fun startLoadModel(selectModelData: ModelData) {
        vTip.visibility = View.VISIBLE
        llLoading.visibility = View.VISIBLE

        val supported = selectModelData.getSupportPluginIds()
        Log.d(TAG, "supported compute units: $supported")
        DiagnosticsLogger.log("INFO", TAG, "model=${selectModelData.modelName} supportedCompute=$supported")

        // QAIRT bundles are NPU-only and do not need a picker.
        if (supported.size == 1 && supported[0] == "npu") {
            loadModel(
                selectModelData = selectModelData,
                modelDataPluginId = selectModelData.runtime ?: "qairt",
                nGpuLayers = 0,
                deviceId = ComputeUnitValue.NPU.value,
            )
            return
        }

        val dialogBinding = DialogSelectPluginIdBinding.inflate(layoutInflater)
        dialogBinding.rbCpu.visibility = if ("cpu" in supported) View.VISIBLE else View.GONE
        dialogBinding.rbGpu.visibility = if ("gpu" in supported) View.VISIBLE else View.GONE
        dialogBinding.rbNpu.visibility = if ("npu" in supported) View.VISIBLE else View.GONE
        dialogBinding.llGpuLayers.visibility = View.GONE

        when {
            "npu" in supported -> dialogBinding.rbNpu.isChecked = true
            "gpu" in supported -> dialogBinding.rbGpu.isChecked = true
            else -> dialogBinding.rbCpu.isChecked = true
        }

        val dialogOnClickListener =
            object : CustomDialogInterface.OnClickListener() {
                override fun onClick(dialog: DialogInterface?, which: Int) {
                    when (which) {
                        DialogInterface.BUTTON_POSITIVE -> {
                            val checkedId = dialogBinding.rgSelectPluginId.checkedRadioButtonId
                            val computeUnit = when (checkedId) {
                                R.id.rb_gpu -> ComputeUnitValue.GPU.value
                                R.id.rb_cpu -> ComputeUnitValue.CPU.value
                                else -> ComputeUnitValue.NPU.value
                            }
                            // GenieX rewrites the layer count for the selected compute
                            // unit. -1 means all eligible layers for GPU/NPU; CPU is 0.
                            val nGpuLayers = if (computeUnit == ComputeUnitValue.CPU.value) 0 else -1
                            dialog?.dismiss()
                            loadModel(
                                selectModelData = selectModelData,
                                modelDataPluginId = selectModelData.runtime ?: "llama_cpp",
                                nGpuLayers = nGpuLayers,
                                deviceId = computeUnit,
                            )
                        }

                        DialogInterface.BUTTON_NEGATIVE -> {
                            llLoading.visibility = View.INVISIBLE
                            vTip.visibility = View.GONE
                        }
                    }
                }
            }

        val alertDialog =
            AlertDialog.Builder(this)
                .setTitle("Compute unit")
                .setMessage("Choose where this model should run. Diagnostics record the requested backend and SDK logs for troubleshooting.")
                .setView(dialogBinding.root)
                .setNegativeButton("Cancel", dialogOnClickListener)
                .setPositiveButton("Load", dialogOnClickListener)
                .setCancelable(false)
                .create()
        alertDialog.show()
        dialogOnClickListener.resetPositiveButton(alertDialog)
    }

    fun handleResult(
        sb: StringBuilder,
        streamResult: LlmStreamResult,
    ) {
        when (streamResult) {
            is LlmStreamResult.Token -> {
                removeLoadingIndicator()
                runOnUiThread {
                    sb.append(streamResult.text)
                    Message(sb.toString(), MessageType.ASSISTANT).let { lastMsg ->
                        val size = messages.size
                        messages[size - 1].let { msg ->
                            if (msg.type != MessageType.ASSISTANT) {
                                messages.add(lastMsg)
                            } else {
                                messages[size - 1] = lastMsg
                            }
                        }
                    }
                    adapter.notifyDataSetChanged()
                }
                // Do not write generated text to logcat; exported diagnostics must not leak chats.
            }

            is LlmStreamResult.Completed -> {
                removeLoadingIndicator()
                if (isLoadVlmModel) {
                    vlmChatList.add(
                        VlmChatMessage(
                            "assistant",
                            listOf(VlmContent("text", sb.toString())),
                        ),
                    )
                } else {
                    chatList.add(ChatMessage("assistant", sb.toString()))
                }

                runOnUiThread {
                    val content = sb.toString()
                    val size = messages.size
                    messages[size - 1] = Message(content, MessageType.ASSISTANT)

                    val ttft = String.format(Locale.US, "%.2f", streamResult.profile.ttftMs)
                    val promptTokens = streamResult.profile.promptTokens
                    val prefillSpeed =
                        String.format(Locale.US, "%.2f", streamResult.profile.prefillSpeed)

                    val generatedTokens = streamResult.profile.generatedTokens
                    val decodingSpeed =
                        String.format(Locale.US, "%.2f", streamResult.profile.decodingSpeed)

                    val profileData =
                        "TTFT: $ttft ms; Prompt Tokens: $promptTokens; \nPrefilling Speed: $prefillSpeed tok/s\nGenerated Tokens: $generatedTokens; Decoding Speed: $decodingSpeed tok/s"
                    messages.add(
                        Message(
                            profileData,
                            MessageType.PROFILE,
                        ),
                    )
                    reloadRecycleView()
                }
                Log.d(TAG, "Completed: ${streamResult.profile}")
            }

            is LlmStreamResult.Error -> {
                removeLoadingIndicator()
                runOnUiThread {
                    val reason = streamResult.throwable.message ?: streamResult.throwable.toString()
                    messages.add(Message("Error: $reason", MessageType.PROFILE))
                    reloadRecycleView()
                }
                Log.d(TAG, "Error: $streamResult")
            }
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, null)
        intent.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
        startActivityForResult(intent, 1)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 0) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openGallery()
            } else {
                Toast.makeText(this, "Not allow", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == 2001) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera()
            } else {
                Toast.makeText(this, "Camera not allow", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        var bitmap: Bitmap? = null
        if (requestCode == 1) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val inputStream = contentResolver.openInputStream(data.data!!)
                bitmap = BitmapFactory.decodeStream(inputStream)
            }
        } else if (requestCode == 1001 && resultCode == Activity.RESULT_OK) {
            photoFile?.let {
                bitmap = BitmapFactory.decodeFile(it.absolutePath)
            }
        }

        bitmap?.let {
            try {
                val file = File(filesDir, "chat_${System.currentTimeMillis()}.jpg")
                val success = saveBitmapToFile(it, file)
                if (success) {
                    Log.d(TAG, "Save success: ${file.absolutePath}")
                    savedImageFiles.add(file)
                    refreshTopScrollContainer()
                } else {
                    Toast.makeText(this, "Save Image failed", Toast.LENGTH_SHORT).show()
                }
            } catch (e: FileNotFoundException) {
                Log.e(TAG, "save image failed", e)
            }
        }
    }

    private fun saveBitmapToFile(
        bitmap: Bitmap,
        file: File,
    ): Boolean =
        try {
            val tempDir = File(this.filesDir, "tmp").apply { if (!exists()) mkdirs() }

            val tempFile =
                File(
                    tempDir,
                    "tmp_${System.currentTimeMillis()}.jpg",
                )
            FileOutputStream(tempFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }

            // Crop straight from the full-size temp file. Pre-downscaling on the
            // *longest* edge first would leave the shorter edge under the target
            // (e.g. 448x355), forcing squareCrop to upscale it back — two lossy
            // resamples for a softer result. squareCrop samples down internally.
            ImgUtil.squareCrop(
                imageFile = tempFile,
                outFile = file,
                size = vlmVisionConfig?.imageSize ?: FALLBACK_VLM_IMAGE_SIZE,
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveBitmapToFile failed", e)
            false
        }

    private fun clearHistory() {
        if (isLoadLlmModel) {
            chatList.clear()
            modelScope.launch {
                llmWrapper.reset()
            }
        }
        if (isLoadVlmModel) {
            vlmChatList.clear()
            modelScope.launch {
                vlmWrapper.reset()
            }
        }
        messages.clear()
        clearImages()
        reloadRecycleView()
    }

    private var popupWindow: PopupWindow? = null

    private fun showPopupMenu(anchorView: View) {
        if (popupWindow?.isShowing == true) {
            popupWindow?.dismiss()
            return
        }

        val popupView = LayoutInflater.from(this).inflate(R.layout.menu_layout, null)

        popupWindow =
            PopupWindow(
                popupView,
                anchorView.width * 2,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                true,
            )

        popupWindow?.isOutsideTouchable = true
        popupWindow?.elevation = 10f

        val btnCamera = popupView.findViewById<Button>(R.id.btn_camera)
        val btnPhoto = popupView.findViewById<Button>(R.id.btn_photo)

        btnCamera.setOnClickListener {
            popupWindow?.dismiss()
            checkAndOpenCamera()
        }
        btnPhoto.setOnClickListener {
            popupWindow?.dismiss()
            openGallery()
        }

        popupView.measure(
            View.MeasureSpec.UNSPECIFIED,
            View.MeasureSpec.UNSPECIFIED,
        )
        val popupHeight = popupView.measuredHeight
        popupWindow?.showAsDropDown(anchorView, 0, -anchorView.height - popupHeight)
    }

    private var photoUri: Uri? = null
    private var photoFile: File? = null

    private fun checkAndOpenCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                2001,
            )
        } else {
            openCamera()
        }
    }

    private fun openCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        photoFile =
            File(
                getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "photo_${System.currentTimeMillis()}.jpg",
            )
        photoUri =
            FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                photoFile!!,
            )

        intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        startActivityForResult(intent, 1001)
    }

    private fun clearImages() {
        savedImageFiles.clear()
        refreshTopScrollContainer()
    }

    private fun refreshTopScrollContainer() {
        refreshSendButtonState()
        runOnUiThread {
            topScrollContainer.removeAllViews()
            if (savedImageFiles.isEmpty()) {
                scrollImages.visibility = View.GONE
                return@runOnUiThread
            }

            scrollImages.visibility = View.VISIBLE

            for (file in savedImageFiles) {
                val itemView =
                    LayoutInflater
                        .from(this)
                        .inflate(R.layout.item_image_scroll, topScrollContainer, false)
                val ivImage = itemView.findViewById<ImageView>(R.id.iv_image)
                val btnRemove = itemView.findViewById<ImageButton>(R.id.btn_remove)

                ivImage.setImageURI(Uri.fromFile(file))

                btnRemove.setOnClickListener {
                    savedImageFiles.remove(file)
                    refreshTopScrollContainer()
                }
                topScrollContainer.addView(itemView)
            }
        }
    }

    private fun reloadRecycleView() {
        adapter.notifyDataSetChanged()
        binding.rvChat.scrollToPosition(messages.size - 1)
    }

    private fun showLoadingIndicator() {
        runOnUiThread {
            if (loadingMessageIndex >= 0) return@runOnUiThread
            messages.add(Message("", MessageType.LOADING))
            loadingMessageIndex = messages.size - 1
            reloadRecycleView()
        }
    }

    private fun removeLoadingIndicator() {
        runOnUiThread {
            val idx = loadingMessageIndex
            if (idx < 0 || idx >= messages.size) {
                loadingMessageIndex = -1
                return@runOnUiThread
            }
            if (messages[idx].type == MessageType.LOADING) {
                messages.removeAt(idx)
                adapter.notifyItemRemoved(idx)
            }
            loadingMessageIndex = -1
        }
    }

    companion object {
        private const val TAG = "GenieXDemo"

        /**
         * Square edge length used for image preprocessing when the mmproj GGUF
         * does not declare one. Only a fallback — the real value is read per
         * model by [GgufVisionReader], since feeding a tower a smaller square
         * than it was trained on silently discards detail.
         */
        private const val FALLBACK_VLM_IMAGE_SIZE = 448

        /** Room for an image, its answer, and a follow-up turn, over the image cost. */
        private const val VLM_CTX_HEADROOM = 2048

        /** nCtx must be at least this regardless of image cost. */
        private const val VLM_MIN_CTX = 4096

        /**
         * Context size that fits one image of [vision]'s token cost plus room to
         * answer and ask again. Rounded up to a power of two, which is what
         * llama.cpp KV-cache allocation prefers.
         */
        private fun vlmContextSize(vision: GgufVisionConfig?): Int {
            val needed = (vision?.tokenCount ?: 0) + VLM_CTX_HEADROOM
            var ctx = VLM_MIN_CTX
            while (ctx < needed) ctx *= 2
            return ctx
        }
    }
}
