// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
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
import android.widget.PopupMenu
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
import com.geniex.demo.documents.DocumentProcessor
import com.geniex.demo.model.AppPreferences
import com.geniex.demo.model.ModelDownloadCoordinator
import com.geniex.demo.model.ModelManagementActivity
import com.geniex.demo.server.InferenceBridge
import com.geniex.demo.server.LocalApiService
import com.geniex.demo.server.LocalApiServer
import com.geniex.demo.server.ServerActivity
import com.geniex.demo.storage.WorkingDirectoryManager
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
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import com.geniex.sdk.bean.VlmCreateInput
import com.gyf.immersionbar.ktx.immersionBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : FragmentActivity() {
    private val binding: ActivityMainBinding by inflate()
    private var downloadJob: Job? = null
    private var modelLoadJob: Job? = null
    private var documentJob: Job? = null
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
    private lateinit var btnAttachText: Button
    private lateinit var btnDocumentMode: Button
    private lateinit var btnClearDocuments: Button
    private lateinit var llDocuments: LinearLayout
    private lateinit var tvAttachedDocuments: TextView
    private lateinit var tvDocumentStatus: TextView
    private lateinit var tvSelectedModel: TextView
    private lateinit var tvSelectedModelStatus: TextView
    private lateinit var tvServerStatusCompact: TextView
    private lateinit var btnModels: Button
    private lateinit var btnServer: Button
    private lateinit var btnDiagnostics: Button
    private lateinit var btnModelPanelToggle: Button
    private lateinit var llModelPanelContent: LinearLayout
    private lateinit var btnSettingsMenu: View

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ChatAdapter

    private lateinit var scrollImages: HorizontalScrollView
    private lateinit var topScrollContainer: LinearLayout
    private lateinit var llLoading: LinearLayout
    private lateinit var vTip: View

    private lateinit var llmWrapper: LlmWrapper
    private lateinit var vlmWrapper: VlmWrapper
    private val modelScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
    private val selectedDocuments = mutableListOf<DocumentProcessor.DocumentRef>()
    private var documentLectureMode = true
    @Volatile private var documentStopRequested = false
    private val messages = arrayListOf<Message>()
    private var loadingMessageIndex: Int = -1
    private var streamingMessageIndex: Int = -1
    private var lastStreamUiUpdateMs: Long = 0L
    private var sdkReady = false
    private var uiReady = false
    private var nativeRuntimeWasUsed = false
    private var pendingResumeAttempted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The native model manager reads GENIEX_DATADIR on first use. Never
        // initialize GenieX until the persistent workspace has been applied.
        if (!WorkingDirectoryManager.applyConfigured(this)) {
            startActivity(Intent(this, StartupActivity::class.java))
            finish()
            return
        }
        immersionBar {
            statusBarColorInt(getColor(R.color.bg_normal))
            statusBarDarkFont(true)
        }
        // Build the UI before initializing GenieX. The SDK init callback may
        // complete synchronously, so any callback that touches views must not
        // run before lateinit view fields are assigned.
        parseModelList()
        initView()
        uiReady = true
        setListeners()
        initGenieXSdk()
        showInterruptedLoadWarning()
        if (sdkReady) {
            maybeResumePendingModelLoad()
        }
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
        btnAttachText = findViewById(R.id.btn_attach_text)
        btnDocumentMode = findViewById(R.id.btn_document_mode)
        btnClearDocuments = findViewById(R.id.btn_clear_documents)
        llDocuments = findViewById(R.id.ll_documents)
        tvAttachedDocuments = findViewById(R.id.tv_attached_documents)
        tvDocumentStatus = findViewById(R.id.tv_document_status)
        tvSelectedModel = findViewById(R.id.tv_selected_model)
        tvSelectedModelStatus = findViewById(R.id.tv_selected_model_status)
        tvServerStatusCompact = findViewById(R.id.tv_server_status_compact)
        btnModels = findViewById(R.id.btn_models)
        btnServer = findViewById(R.id.btn_server)
        btnDiagnostics = findViewById(R.id.btn_diagnostics)
        btnModelPanelToggle = findViewById(R.id.btn_model_panel_toggle)
        llModelPanelContent = findViewById(R.id.ll_model_panel_content)
        btnSettingsMenu = findViewById(R.id.btn_settings_menu)
        // The former model-control card is intentionally gone. Keep the hidden
        // compatibility controls for the existing load/unload implementation,
        // while all navigation is surfaced through the gear menu.
        llModelPanelContent.visibility = View.GONE

        btnSend = findViewById(R.id.btn_send)
        btnSend.isEnabled = false
        etInput.doAfterTextChanged { refreshSendButtonState() }
        btnClearHistory = findViewById(R.id.btn_clear_history)
        scrollImages = findViewById(R.id.scroll_images)
        topScrollContainer = findViewById(R.id.ll_images_container)
        llLoading = findViewById(R.id.ll_loading)
        vTip = findViewById<View>(R.id.v_tip)
        // All views used by state refresh helpers must be bound before any
        // refresh method runs. Some of these helpers call each other.
        refreshDocumentUi()
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

    private fun toggleModelPanel() {
        val collapsed = !AppPreferences.isModelPanelCollapsed(this)
        AppPreferences.setModelPanelCollapsed(this, collapsed)
        applyModelPanelCollapsedState()
    }

    private fun applyModelPanelCollapsedState() {
        if (!::llModelPanelContent.isInitialized || !::btnModelPanelToggle.isInitialized) return
        val collapsed = AppPreferences.isModelPanelCollapsed(this)
        llModelPanelContent.visibility = if (collapsed) View.GONE else View.VISIBLE
        btnModelPanelToggle.text = if (collapsed) "Expand" else "Collapse"
        btnModelPanelToggle.contentDescription = if (collapsed) {
            "Expand model controls"
        } else {
            "Collapse model controls"
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
        // This method is called from asynchronous SDK/model-manager callbacks.
        // Never touch lateinit views until the Activity UI is fully bound.
        if (!uiReady ||
            !::modelList.isInitialized ||
            !::tvSelectedModel.isInitialized ||
            !::tvSelectedModelStatus.isInitialized ||
            !::btnLoadModel.isInitialized ||
            !::btnUnloadModel.isInitialized ||
            !::btnStop.isInitialized
        ) {
            return
        }
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
                val anyModelLoaded = hasLoadedModel()
                // These controls are hidden compatibility actions now; the Models
                // screen is the visible load/unload surface.
                btnLoadModel.visibility = View.GONE
                btnLoadModel.isEnabled = available && !anyModelLoaded
                btnLoadModel.text = if (available) "Load model" else "Download in Models"
                btnUnloadModel.visibility = View.GONE
                btnStop.visibility = if (anyModelLoaded && isGenerating) View.VISIBLE else View.GONE
                tvSelectedModelStatus.text = when {
                    active -> "Active • ${InferenceBridge.requestedComputeUnit?.uppercase() ?: model.computeSummary}"
                    anyModelLoaded -> "Not active • unload the active model to switch"
                    available -> "Ready to load • ${model.computeSummary}"
                    else -> "Not downloaded • ${model.computeSummary}"
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
                    sdkReady = true
                    WorkingDirectoryManager.markMainLaunchHealthy(this@MainActivity)
                    DiagnosticsLogger.log("INFO", TAG, "GenieX SDK initialized")
                    runOnUiThread {
                        // Re-query the configured persistent cache now that the
                        // model manager is initialized; this discovers models
                        // left in the workspace by a previous installation.
                        if (uiReady) {
                            refreshSelectedModelUi()
                            maybeResumePendingModelLoad()
                        }
                    }
                }

                override fun onFailure(reason: String) {
                    Log.e(TAG, "GenieXSdk init failed: $reason")
                    DiagnosticsLogger.log("ERROR", TAG, "GenieX SDK init failed: $reason")
                    WorkingDirectoryManager.recordWorkspaceError(this@MainActivity, "GenieX initialization failed: $reason")
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "GenieX could not initialize this workspace. Returning to workspace setup.",
                            Toast.LENGTH_LONG,
                        ).show()
                        startActivity(Intent(this@MainActivity, StartupActivity::class.java))
                        finish()
                    }
                }
            },
        )
    }

    private fun onLoadModelSuccess(tip: String) {
        nativeRuntimeWasUsed = true
        val resumeServer = AppPreferences.consumeResumeServerAfterRestart(this)
        if (resumeServer && !LocalApiServer.isRunning()) {
            LocalApiService.start(
                applicationContext,
                AppPreferences.getServerPort(this),
                AppPreferences.isLanEnabled(this),
                AppPreferences.getApiKey(this),
            )
            DiagnosticsLogger.log("INFO", "ApiServer", "foreground server resume requested after model switch")
        }
        runOnUiThread {
            Toast
                .makeText(
                    this@MainActivity,
                    tip,
                    Toast.LENGTH_SHORT,
                ).show()
            // change UI
            btnAddImage.visibility = View.GONE
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
            btnAddImage.visibility = View.GONE
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
        // Startup callbacks and document-state refreshes can arrive while the
        // activity is still binding views. Never dereference a lateinit view
        // until the complete composer/control set exists.
        if (!::etInput.isInitialized || !::btnSend.isInitialized || !::btnStop.isInitialized) return
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val hasText = etInput.text?.isNotBlank() == true
            val hasImageAttachment = savedImageFiles.isNotEmpty()
            val hasDocuments = selectedDocuments.isNotEmpty()
            val documentReady = hasDocuments && (documentLectureMode || hasText)
            btnSend.isEnabled =
                hasLoadedModel() && !isGenerating && !DocumentProcessor.isProcessing() &&
                    (hasText || hasImageAttachment || documentReady)
            btnStop.visibility = if (hasLoadedModel() && isGenerating) View.VISIBLE else View.GONE
        }
    }

    /**
     * Checks the Rust model manager's cache for [modelData]. Uses
     * `getPaths`, which canonicalises the name (so `ai-hub-models/<repo>`
     * and `qualcomm/<repo>` map to the same on-disk entry) and returns
     * null while the pull is still in `.inflight/`.
     */
    private suspend fun isModelDownloaded(modelData: ModelData): Boolean = ModelManagerWrapper.getPaths(modelData.modelName) != null

    private fun maybeResumePendingModelLoad() {
        if (!sdkReady || !uiReady || pendingResumeAttempted) return
        if (DiagnosticsLogger.wasModelLoadInterrupted()) {
            AppPreferences.clearPendingModelLoad(this)
            return
        }
        val pending = AppPreferences.getPendingModelLoad(this) ?: return
        pendingResumeAttempted = true
        AppPreferences.clearPendingModelLoad(this)
        val model = modelList.firstOrNull { it.id == pending.modelId } ?: return
        AppPreferences.setSelectedModelId(this, model.id)
        syncSelectedModelFromPreferences()
        DiagnosticsLogger.checkpoint(
            "SAFE_RUNTIME_RESUME",
            "model=${model.modelName} compute=${pending.computeUnit}",
        )
        llLoading.visibility = View.VISIBLE
        vTip.visibility = View.VISIBLE
        val nGpuLayers = if (pending.computeUnit == ComputeUnitValue.CPU.value) 0 else -1
        loadModel(
            selectModelData = model,
            modelDataPluginId = model.runtime ?: "llama_cpp",
            nGpuLayers = nGpuLayers,
            deviceId = pending.computeUnit,
            bypassFreshRuntimeGuard = true,
        )
    }

    private fun restartIntoFreshRuntime(selectModelData: ModelData, computeUnit: String) {
        AppPreferences.setPendingModelLoad(this, selectModelData.id, computeUnit)
        DiagnosticsLogger.checkpoint(
            "SAFE_RUNTIME_RESTART",
            "model=${selectModelData.modelName} compute=$computeUnit",
        )
        val serverWasRunning = LocalApiServer.isRunning()
        AppPreferences.setResumeServerAfterRestart(this, serverWasRunning)
        if (serverWasRunning) LocalApiServer.stop()
        Toast.makeText(this, "Restarting the model engine for a clean switch…", Toast.LENGTH_SHORT).show()
        startActivity(
            Intent(this, RuntimeRestartActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
        )
        finishAffinity()
        Process.killProcess(Process.myPid())
    }

    private fun recursiveSizeBytes(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { recursiveSizeBytes(it) } ?: 0L
    }

    private fun loadModel(
        selectModelData: ModelData,
        modelDataPluginId: String,
        nGpuLayers: Int,
        deviceId: String? = null,
        bypassFreshRuntimeGuard: Boolean = false,
    ) {
        val requestedCompute: String = deviceId ?: ComputeUnitValue.NPU.value ?: "npu"
        if (nativeRuntimeWasUsed && !bypassFreshRuntimeGuard && !hasLoadedModel()) {
            restartIntoFreshRuntime(selectModelData, requestedCompute)
            return
        }
        if (modelLoadJob?.isActive == true) {
            Toast.makeText(this, "A model is already loading.", Toast.LENGTH_SHORT).show()
            return
        }
        DiagnosticsLogger.markModelLoadStart(
            "model=${selectModelData.modelName} quant=${selectModelData.quant.orEmpty()} runtime=${selectModelData.runtime.orEmpty()} compute=$requestedCompute",
        )
        val loadJob = modelScope.launch {
            DiagnosticsLogger.modelLoadStage("RESET_LOAD_STATE_BEGIN")
            resetLoadState()
            DiagnosticsLogger.modelLoadStage("RESET_LOAD_STATE_COMPLETE")

            DiagnosticsLogger.modelLoadStage("PATH_RESOLUTION_BEGIN", "model=${selectModelData.modelName}")
            val paths = ModelManagerWrapper.getPaths(selectModelData.modelName)
            if (paths == null) {
                DiagnosticsLogger.modelLoadStage("PATH_RESOLUTION_FAILED", "model=${selectModelData.modelName}")
                onLoadModelFailed("model paths unavailable — pull it first")
                return@launch
            }
            DiagnosticsLogger.modelLoadStage(
                "PATH_RESOLUTION_COMPLETE",
                "modelName=${paths.model_name} runtimeId=${paths.runtime_id} modelPath=${paths.model_path} " +
                    "tokenizerPath=${paths.tokenizer_path} mmprojPath=${paths.mmproj_path.orEmpty()}",
            )
            DiagnosticsLogger.recordModelFiles(
                modelName = selectModelData.modelName,
                runtimeId = paths.runtime_id.ifEmpty { modelDataPluginId },
                computeUnit = requestedCompute,
                modelPath = paths.model_path,
                tokenizerPath = paths.tokenizer_path,
                mmprojPath = paths.mmproj_path,
            )

            val memoryInfo = ActivityManager.MemoryInfo().also {
                (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
            }
            val availableBytes = memoryInfo.availMem
            val modelBytes = recursiveSizeBytes(File(paths.model_path))
            DiagnosticsLogger.log(
                "INFO",
                "Memory",
                "load preflight model=${selectModelData.displayName} runtime=${paths.runtime_id.ifEmpty { modelDataPluginId }} " +
                    "compute=$requestedCompute available=${formatGiB(availableBytes)} modelFiles=${formatGiB(modelBytes)} " +
                    "lowMemory=${memoryInfo.lowMemory} threshold=${formatGiB(memoryInfo.threshold)}",
            )
            DiagnosticsLogger.modelLoadStage(
                "MEMORY_PREFLIGHT_COMPLETE",
                "availableBytes=$availableBytes modelBytes=$modelBytes",
            )
            if (memoryInfo.lowMemory) {
                DiagnosticsLogger.modelLoadStage(
                    "MEMORY_PREFLIGHT_BLOCKED",
                    "availableBytes=$availableBytes threshold=${memoryInfo.threshold} reason=android_low_memory",
                )
                onLoadModelFailed(
                    "Android is already reporting low-memory pressure. Close other apps or reboot before loading a model.",
                )
                return@launch
            }

            selectModelData.minAvailableMemoryGiB?.let { minGiB ->
                val minimumBytes = (minGiB * GIB_BYTES.toDouble()).toLong()
                if (availableBytes > 0L && availableBytes < minimumBytes) {
                    DiagnosticsLogger.modelLoadStage(
                        "MEMORY_PREFLIGHT_BLOCKED",
                        "availableBytes=$availableBytes minimumBytes=$minimumBytes reason=model_threshold",
                    )
                    onLoadModelFailed(
                        "Load blocked to prevent another low-memory process kill. " +
                            "${selectModelData.displayName} has a ${String.format(Locale.US, "%.1f", minGiB)} GiB " +
                            "recommended free-memory threshold in this build, but Android reports ${formatGiB(availableBytes)}. " +
                            "Use the GGUF Q4_0 version instead, or close apps/reboot and try again.",
                    )
                    return@launch
                }
            }

            // GPU model loading can temporarily require another large chunk of
            // shared system memory in addition to the GGUF itself. Android may
            // kill the entire process instead of delivering an exception when
            // that pressure becomes too high, so preflight the load and prefer
            // a clear error over a low-memory process death.
            if (requestedCompute == ComputeUnitValue.GPU.value) {
                val recommendedBytes =
                    if (modelBytes > 0L) {
                        modelBytes + maxOf(GPU_MIN_EXTRA_HEADROOM_BYTES, modelBytes / 2)
                    } else {
                        GPU_MIN_EXTRA_HEADROOM_BYTES
                    }
                DiagnosticsLogger.log(
                    "INFO",
                    "Memory",
                    "GPU preflight model=${selectModelData.displayName} available=${formatGiB(availableBytes)} " +
                        "model=${formatGiB(modelBytes)} recommended=${formatGiB(recommendedBytes)}",
                )
                if (availableBytes > 0L && availableBytes < recommendedBytes) {
                    DiagnosticsLogger.modelLoadStage(
                        "MEMORY_PREFLIGHT_BLOCKED",
                        "availableBytes=$availableBytes recommendedBytes=$recommendedBytes reason=gpu_headroom",
                    )
                    onLoadModelFailed(
                        "GPU load blocked to prevent a low-memory crash. " +
                            "Available ${formatGiB(availableBytes)}; about ${formatGiB(recommendedBytes)} recommended. " +
                            "Use NPU, close other apps, then try GPU again.",
                    )
                    return@launch
                }
            }

            // From here on, a native runtime may allocate accelerator/driver memory even
            // if builder creation eventually fails. Require a fresh process before the
            // next attempt so partial native teardown cannot poison a model switch.
            nativeRuntimeWasUsed = true

            // Manifest-written runtime_id wins when present; fall back to
            // the user's UI selection for GGUF models that skip the manifest.
            val pluginId = paths.runtime_id.ifEmpty { modelDataPluginId }
            val resolvedDeviceId = deviceId
            DiagnosticsLogger.modelLoadStage(
                "RUNTIME_RESOLVED",
                "pluginId=$pluginId requestedCompute=$requestedCompute deviceId=${resolvedDeviceId.orEmpty()} type=${selectModelData.type}",
            )
            when (selectModelData.type) {
                "chat", "llm" -> {
                    // QAIRT rejects non-zero n_ctx / n_gpu_layers (both fixed at compile
                    // time in the AI Hub bundle) — and the Kotlin ModelConfig defaults
                    // are non-zero, so zero them explicitly for the qairt path.
                    val isQairt = pluginId == "qairt"
                    val llamaTuning = if (isQairt) null else PerformanceTuning.llamaConfig(availableBytes, requestedCompute)
                    val conf =
                        if (isQairt) {
                            ModelConfig(nCtx = 0, nGpuLayers = 0, enable_thinking = enableThinking)
                        } else {
                            ModelConfig(
                                nCtx = llamaTuning!!.nCtx,
                                nThreads = llamaTuning.nThreads,
                                nBatch = llamaTuning.nBatch,
                                nUBatch = llamaTuning.nUBatch,
                                nGpuLayers = nGpuLayers,
                                enable_thinking = enableThinking,
                            )
                        }
                    DiagnosticsLogger.modelLoadStage(
                        "LLM_CONFIG_READY",
                        if (isQairt) {
                            "runtime=$pluginId compute=$requestedCompute nCtx=0 nGpuLayers=0 thinking=$enableThinking"
                        } else {
                            "runtime=$pluginId compute=$requestedCompute nCtx=${llamaTuning!!.nCtx} " +
                                "nThreads=${llamaTuning.nThreads} nBatch=${llamaTuning.nBatch} " +
                                "nUBatch=${llamaTuning.nUBatch} nGpuLayers=$nGpuLayers thinking=$enableThinking"
                        },
                    )
                    DiagnosticsLogger.modelLoadStage("LLM_BUILDER_CREATE_BEGIN")
                    val builder = LlmWrapper.builder()
                    DiagnosticsLogger.modelLoadStage("LLM_BUILDER_CREATE_COMPLETE")
                    val createInput = LlmCreateInput(
                        model_name = paths.model_name,
                        model_path = paths.model_path,
                        tokenizer_path = paths.tokenizer_path,
                        config = conf,
                        runtime_id = pluginId,
                        compute_unit = resolvedDeviceId ?: ComputeUnitValue.NPU.value ?: "npu",
                    )
                    DiagnosticsLogger.modelLoadStage("LLM_CREATE_INPUT_BEGIN")
                    val configuredBuilder = builder.llmCreateInput(createInput)
                    DiagnosticsLogger.modelLoadStage("LLM_CREATE_INPUT_COMPLETE")
                    DiagnosticsLogger.modelLoadStage("LLM_BUILD_ENTER")
                    configuredBuilder.build()
                        .onSuccess { wrapper ->
                            DiagnosticsLogger.modelLoadStage("LLM_BUILD_RETURNED_SUCCESS")
                            isLoadLlmModel = true
                            llmWrapper = wrapper
                            InferenceBridge.setLlm(
                                wrapper,
                                selectModelData.id,
                                selectModelData.displayName,
                                requestedCompute,
                                if (isQairt) PerformanceTuning.QAIRT_CONTEXT_BUDGET_TOKENS else llamaTuning!!.nCtx,
                            )
                            DiagnosticsLogger.markModelLoadComplete("${selectModelData.modelName} compute=$requestedCompute")
                            onLoadModelSuccess("LLM model loaded")
                        }.onFailure { error ->
                            DiagnosticsLogger.modelLoadStage(
                                "LLM_BUILD_RETURNED_FAILURE",
                                "type=${error::class.java.name} message=${error.message.orEmpty()}",
                            )
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
                    DiagnosticsLogger.modelLoadStage(
                        "VLM_CONFIG_READY",
                        "runtime=$pluginId compute=${resolvedDeviceId ?: ComputeUnitValue.NPU.value} " +
                            "nCtx=${if (isNpuVlm) 0 else vlmContextSize(vlmVisionConfig)} " +
                            "nGpuLayers=${if (isNpuVlm) 0 else nGpuLayers} nThreads=${if (isNpuVlm) 8 else 4}",
                    )
                    DiagnosticsLogger.modelLoadStage("VLM_BUILDER_CREATE_BEGIN")
                    val builder = VlmWrapper.builder()
                    DiagnosticsLogger.modelLoadStage("VLM_BUILDER_CREATE_COMPLETE")
                    val createInput = VlmCreateInput(
                        model_name = paths.model_name,
                        model_path = paths.model_path,
                        mmproj_path = paths.mmproj_path,
                        config = config,
                        runtime_id = pluginId,
                        compute_unit = resolvedDeviceId ?: ComputeUnitValue.NPU.value ?: "npu",
                    )
                    DiagnosticsLogger.modelLoadStage("VLM_CREATE_INPUT_BEGIN")
                    val configuredBuilder = builder.vlmCreateInput(createInput)
                    DiagnosticsLogger.modelLoadStage("VLM_CREATE_INPUT_COMPLETE")
                    DiagnosticsLogger.modelLoadStage("VLM_BUILD_ENTER")
                    configuredBuilder.build()
                        .onSuccess {
                            DiagnosticsLogger.modelLoadStage("VLM_BUILD_RETURNED_SUCCESS")
                            isLoadVlmModel = true
                            vlmWrapper = it
                            InferenceBridge.setVlm(
                                it,
                                selectModelData.id,
                                selectModelData.displayName,
                                requestedCompute,
                                if (isNpuVlm) PerformanceTuning.QAIRT_CONTEXT_BUDGET_TOKENS else vlmContextSize(vlmVisionConfig),
                            )
                            DiagnosticsLogger.markModelLoadComplete("${selectModelData.modelName} compute=$requestedCompute")
                            onLoadModelSuccess("VLM model loaded")
                        }.onFailure { error ->
                            DiagnosticsLogger.modelLoadStage(
                                "VLM_BUILD_RETURNED_FAILURE",
                                "type=${error::class.java.name} message=${error.message.orEmpty()}",
                            )
                            onLoadModelFailed(error.message.toString())
                        }
                }

                else -> {
                    DiagnosticsLogger.modelLoadStage("MODEL_TYPE_UNSUPPORTED", "type=${selectModelData.type}")
                    onLoadModelFailed("model type error")
                }
            }
        }
        modelLoadJob = loadJob
        loadJob.invokeOnCompletion {
            if (modelLoadJob === loadJob) modelLoadJob = null
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
        if (ModelDownloadCoordinator.isAiHub(selectModelData) && selectModelData.chipset.isNullOrBlank()) {
            Toast.makeText(
                this@MainActivity,
                "AI Hub models require a chipset. Update model_list.json.",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        downloadingModelData = selectModelData
        llDownloading.visibility = View.VISIBLE
        tvDownloadProgress.text = "0%"
        DiagnosticsLogger.checkpoint(
            "MODEL_DOWNLOAD_BEGIN",
            "${selectModelData.modelName}:${selectModelData.quant.orEmpty()}",
        )

        val wakeLock =
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "geniex:model_download")
        wakeLock.acquire(DOWNLOAD_WAKELOCK_TIMEOUT_MS)
        val newDownloadJob = modelScope.launch(start = CoroutineStart.LAZY) {
            val thisJob = coroutineContext[Job]
            try {
                    if (isModelDownloaded(selectModelData)) {
                        runOnUiThread {
                            llDownloading.visibility = View.GONE
                            Toast.makeText(this@MainActivity, "model already downloaded", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }

                    ModelDownloadCoordinator.downloadFlow(this@MainActivity, selectModelData).collect { event ->
                        when (event) {
                            is ModelDownloadCoordinator.Event.Progress -> {
                                runOnUiThread { tvDownloadProgress.text = "${event.percent}%" }
                            }

                            is ModelDownloadCoordinator.Event.Completed -> {
                                val paths = ModelManagerWrapper.getPaths(selectModelData.modelName)
                                val persistent = WorkingDirectoryManager.isPersistentModelPath(
                                    this@MainActivity,
                                    paths?.model_path,
                                )
                                DiagnosticsLogger.checkpoint(
                                    "MODEL_DOWNLOAD_COMPLETE",
                                    "${selectModelData.modelName} path=${paths?.model_path.orEmpty()} persistent=$persistent",
                                )
                                runOnUiThread {
                                    llDownloading.visibility = View.GONE
                                    tvDownloadProgress.text = "100%"
                                    Toast
                                        .makeText(
                                            this@MainActivity,
                                            if (persistent) {
                                                "${selectModelData.displayName} downloaded to Genie/models"
                                            } else {
                                                "Download finished, but model storage verification failed"
                                            },
                                            if (persistent) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                                        ).show()
                                }
                            }

                            is ModelDownloadCoordinator.Event.Error -> {
                                Log.e(
                                    TAG,
                                    "model download failed code=${event.code ?: "http"}: ${event.message}",
                                )
                                runOnUiThread {
                                    llDownloading.visibility = View.GONE
                                    Toast
                                        .makeText(
                                            this@MainActivity,
                                            "Download failed: ${event.message}",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                }
                            }
                        }
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                if (downloadJob === thisJob) {
                    downloadingModelData = null
                    downloadJob = null
                }
            }
        }
        downloadJob = newDownloadJob
        newDownloadJob.start()
    }

    private fun showAppMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_MANAGE_MODELS, 0, "Manage models")
            menu.add(0, MENU_WEB_SERVER, 1, "Web server")
            menu.add(0, MENU_DIAGNOSTICS, 2, "Diagnostics")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_MANAGE_MODELS -> btnModels.performClick()
                    MENU_WEB_SERVER -> btnServer.performClick()
                    MENU_DIAGNOSTICS -> btnDiagnostics.performClick()
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
            show()
        }
    }

    private fun setListeners() {
        btnModels.setOnClickListener {
            startActivityForResult(
                Intent(this, ModelManagementActivity::class.java),
                REQUEST_MODEL_MANAGEMENT,
            )
        }
        btnServer.setOnClickListener {
            startActivity(Intent(this, ServerActivity::class.java))
        }
        btnDiagnostics.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        btnSettingsMenu.setOnClickListener { showAppMenu(it) }

        btnAddImage.setOnClickListener {
            openGallery()
        }
        btnAttachText.setOnClickListener { openTextTranscriptPicker() }
        btnDocumentMode.setOnClickListener {
            documentLectureMode = !documentLectureMode
            refreshDocumentUi()
        }
        btnClearDocuments.setOnClickListener {
            selectedDocuments.clear()
            refreshDocumentUi()
        }

        btnClearHistory.setOnClickListener {
            clearHistory()
        }
        /*
         * Step 3. download model. Cancelling the coroutine closes the active
         * HTTP/native pull. Standard Hugging Face downloads keep their .part
         * file so Retry can resume with an HTTP Range request.
         */
        binding.btnCancelDownload.setOnClickListener {
            downloadJob?.cancel()
            tvDownloadProgress.text = "0%"
            binding.llDownloading.visibility = View.GONE
        }
        binding.btnRetryDownload.setOnClickListener {
            val retryModel = downloadingModelData ?: return@setOnClickListener
            val previousJob = downloadJob
            if (previousJob == null || !previousJob.isActive) {
                downloadModel(retryModel)
            } else {
                previousJob.invokeOnCompletion {
                    if (!isFinishing && !isDestroyed) {
                        runOnUiThread { downloadModel(retryModel) }
                    }
                }
                previousJob.cancel()
            }
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
            // Transcript attachments use a bounded document pipeline instead
            // of stuffing the entire file into one model context.
            if (selectedDocuments.isNotEmpty()) {
                if (isGenerating) return@setOnClickListener
                sendDocumentRequest(etInput.text.trim().toString())
                return@setOnClickListener
            }

            // Guard against re-entry: a second click while a previous
            // generate() is still running would race on the native handle
            // and crash the app.
            if (isGenerating) return@setOnClickListener
            if (DocumentProcessor.isProcessing()) {
                Toast.makeText(this, "A transcript task is already using the model.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!InferenceBridge.mutex.tryLock()) {
                Toast.makeText(this, "The model is busy serving another request.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            isGenerating = true
            streamingMessageIndex = -1
            lastStreamUiUpdateMs = 0L
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
                        trimVlmHistoryForMemory()

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
                        trimLlmHistoryForMemory()
                        // Apply chat template and generate
                        llmWrapper
                            .applyChatTemplate(
                                chatList.toTypedArray(),
                                tools,
                                enableThinking,
                            ).onSuccess { templateOutput ->
                                Log.d(TAG, "LLM chat template prepared; chars=${templateOutput.formattedText.length}")
                                val safeMaxTokens = InferenceBridge.safeResponseBudget(
                                    templateOutput.formattedText,
                                    PerformanceTuning.DEFAULT_RESPONSE_TOKENS,
                                )
                                if (safeMaxTokens < PerformanceTuning.MIN_RESPONSE_TOKENS) {
                                    runOnUiThread {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Conversation is too long for this model context. Clear older messages and try again.",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                    return@onSuccess
                                }
                                llmWrapper
                                    .generateStreamFlow(
                                        templateOutput.formattedText,
                                        GenerationConfigSample(maxTokens = safeMaxTokens).toGenerationConfig(),
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
            // destroy it while either UI, document processing, or API inference is using it.
            if (DocumentProcessor.isProcessing()) {
                Toast.makeText(this@MainActivity, "Transcript processing is still running.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!InferenceBridge.mutex.tryLock()) {
                Toast.makeText(this@MainActivity, "Model is busy. Try again when inference finishes.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Unload model and cleanup
            val handleUnloadResult = fun(result: Int) {
                resetLoadState()
                InferenceBridge.clear()
                DiagnosticsLogger.checkpoint("MODEL_UNLOAD", "fresh process required before next model load")
                chatList.clear()
                vlmChatList.clear()
                runOnUiThread {
                    vTip.visibility = View.GONE
                    btnUnloadModel.visibility = View.GONE
                    btnStop.visibility = View.GONE
                    btnAddImage.visibility = View.GONE
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
                        settleAfterNativeUnload()
                        handleUnloadResult(0)
                    } else if (isLoadLlmModel) {
                        llmWrapper.stopStream()
                        llmWrapper.destroy()
                        chatList.clear()
                        settleAfterNativeUnload()
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
            documentStopRequested = true
            documentJob?.cancel()
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

    private fun getAvailableMemoryBytes(): Long {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.availMem
    }

    private fun formatGiB(bytes: Long): String =
        String.format(Locale.US, "%.2f GiB", bytes.toDouble() / GIB_BYTES.toDouble())

    private suspend fun settleAfterNativeUnload() {
        // GenieX may release large native / driver allocations asynchronously.
        // Give those allocations a short chance to drain before the user swaps
        // from NPU to GPU (or vice versa), which reduces transient peak memory.
        delay(MODEL_UNLOAD_SETTLE_MS)
        DiagnosticsLogger.log(
            "INFO",
            "Memory",
            "post-unload available=${formatGiB(getAvailableMemoryBytes())}",
        )
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
                sb.append(streamResult.text)
                val now = SystemClock.uptimeMillis()
                if (now - lastStreamUiUpdateMs >= STREAM_UI_UPDATE_MS) {
                    lastStreamUiUpdateMs = now
                    postStreamingAssistantText(sb.toString(), finalRender = false)
                }
            }

            is LlmStreamResult.Completed -> {
                postStreamingAssistantText(sb.toString(), finalRender = true)
                if (isLoadVlmModel) {
                    vlmChatList.add(
                        VlmChatMessage(
                            "assistant",
                            listOf(VlmContent("text", sb.toString())),
                        ),
                    )
                    trimVlmHistoryForMemory()
                } else {
                    chatList.add(ChatMessage("assistant", sb.toString()))
                    trimLlmHistoryForMemory()
                }

                runOnUiThread {
                    val ttft = String.format(Locale.US, "%.2f", streamResult.profile.ttftMs)
                    val promptTokens = streamResult.profile.promptTokens
                    val prefillSpeed = String.format(Locale.US, "%.2f", streamResult.profile.prefillSpeed)
                    val generatedTokens = streamResult.profile.generatedTokens
                    val decodingSpeed = String.format(Locale.US, "%.2f", streamResult.profile.decodingSpeed)
                    val profileData =
                        "TTFT: $ttft ms; Prompt Tokens: $promptTokens; \nPrefilling Speed: $prefillSpeed tok/s\nGenerated Tokens: $generatedTokens; Decoding Speed: $decodingSpeed tok/s"
                    messages.add(Message(profileData, MessageType.PROFILE))
                    adapter.notifyItemInserted(messages.lastIndex)
                    binding.rvChat.scrollToPosition(messages.lastIndex)
                    streamingMessageIndex = -1
                    trimUiTranscriptForMemory()
                    if (messages.isNotEmpty()) binding.rvChat.scrollToPosition(messages.lastIndex)
                }
                Log.d(TAG, "Completed: ${streamResult.profile}")
            }

            is LlmStreamResult.Error -> {
                removeLoadingIndicator()
                runOnUiThread {
                    val reason = streamResult.throwable.message ?: streamResult.throwable.toString()
                    messages.add(Message("Error: $reason", MessageType.PROFILE))
                    adapter.notifyItemInserted(messages.lastIndex)
                    streamingMessageIndex = -1
                    trimUiTranscriptForMemory()
                    if (messages.isNotEmpty()) binding.rvChat.scrollToPosition(messages.lastIndex)
                }
                Log.d(TAG, "Error: $streamResult")
            }
        }
    }

    private fun postStreamingAssistantText(content: String, finalRender: Boolean) {
        runOnUiThread {
            removeLoadingIndicatorOnMainThread()
            val idx = streamingMessageIndex
            if (idx < 0 || idx >= messages.size || messages[idx].type != MessageType.ASSISTANT) {
                messages.add(Message(content, MessageType.ASSISTANT))
                streamingMessageIndex = messages.lastIndex
                adapter.notifyItemInserted(streamingMessageIndex)
            } else {
                messages[idx] = Message(content, MessageType.ASSISTANT)
                if (finalRender) {
                    adapter.notifyItemChanged(idx)
                } else {
                    adapter.notifyItemChanged(idx, ChatAdapter.PAYLOAD_STREAM_TEXT)
                }
            }
            binding.rvChat.scrollToPosition(messages.lastIndex)
        }
    }

    private fun trimLlmHistoryForMemory() {
        val contextCharBudget =
            ((InferenceBridge.contextWindowTokens -
                PerformanceTuning.CONTEXT_SAFETY_TOKENS -
                PerformanceTuning.DEFAULT_RESPONSE_TOKENS)
                .coerceAtLeast(PerformanceTuning.MIN_RESPONSE_TOKENS) * 2)
        while (chatList.size > PerformanceTuning.MAX_NATIVE_HISTORY_MESSAGES ||
            chatList.sumOf { it.content.length } > minOf(PerformanceTuning.MAX_NATIVE_HISTORY_CHARS, contextCharBudget)
        ) {
            // Preserve the latest user turn even when it alone is very long. The
            // context-budget check after chat templating will then show a clear
            // message instead of silently deleting the user's prompt.
            if (chatList.size <= 1) break
            chatList.removeAt(0)
        }
    }

    private fun trimVlmHistoryForMemory() {
        fun chars(): Int = vlmChatList.sumOf { message ->
            message.contents.sumOf { content -> if (content.type == "text") content.text?.length ?: 0 else 0 }
        }
        val contextCharBudget =
            ((InferenceBridge.contextWindowTokens -
                PerformanceTuning.CONTEXT_SAFETY_TOKENS -
                PerformanceTuning.DEFAULT_RESPONSE_TOKENS)
                .coerceAtLeast(PerformanceTuning.MIN_RESPONSE_TOKENS) * 2)
        while (vlmChatList.size > PerformanceTuning.MAX_NATIVE_HISTORY_MESSAGES ||
            chars() > minOf(PerformanceTuning.MAX_NATIVE_HISTORY_CHARS, contextCharBudget)
        ) {
            if (vlmChatList.size <= 1) break
            vlmChatList.removeAt(0)
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
        if (resultCode != Activity.RESULT_OK) return

        if (requestCode == REQUEST_MODEL_MANAGEMENT) {
            val modelId = data?.getStringExtra(ModelManagementActivity.EXTRA_SELECTED_MODEL_ID)
            val action = data?.getStringExtra(ModelManagementActivity.EXTRA_MODEL_ACTION)
            if (!modelId.isNullOrBlank()) {
                AppPreferences.setSelectedModelId(this, modelId)
                syncSelectedModelFromPreferences()
                refreshSelectedModelUi()
                when (action) {
                    ModelManagementActivity.ACTION_LOAD -> btnLoadModel.performClick()
                    ModelManagementActivity.ACTION_UNLOAD -> btnUnloadModel.performClick()
                }
            }
            return
        }

        if (requestCode == REQUEST_TEXT_TRANSCRIPTS) {
            importSelectedTextTranscripts(data)
            return
        }

        modelScope.launch {
            var sourceFile: File? = null
            val deleteSourceAfter = requestCode == 1 || requestCode == 1001
            try {
                sourceFile = when (requestCode) {
                    1 -> {
                        val uri = data?.data ?: return@launch
                        val tempDir = (WorkingDirectoryManager.workspace(this@MainActivity)?.temp
                            ?: File(filesDir, "tmp")).apply { mkdirs() }
                        val temp = File(tempDir, "import_${System.currentTimeMillis()}.img")
                        contentResolver.openInputStream(uri)?.use { input ->
                            temp.outputStream().buffered().use { output -> input.copyTo(output, 128 * 1024) }
                        } ?: return@launch
                        temp
                    }
                    1001 -> photoFile
                    else -> null
                }
                val source = sourceFile ?: return@launch
                if (!source.exists()) return@launch

                val attachmentsDir = WorkingDirectoryManager.workspace(this@MainActivity)?.attachments ?: filesDir
                attachmentsDir.mkdirs()
                val outputFile = File(attachmentsDir, "chat_${System.currentTimeMillis()}.jpg")
                ImgUtil.squareCrop(
                    imageFile = source,
                    outFile = outputFile,
                    size = vlmVisionConfig?.imageSize ?: FALLBACK_VLM_IMAGE_SIZE,
                    quality = 90,
                )
                savedImageFiles.add(outputFile)
                runOnUiThread { refreshTopScrollContainer() }
            } catch (e: Exception) {
                DiagnosticsLogger.log("ERROR", TAG, "image preprocessing failed", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Could not prepare image: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                if (deleteSourceAfter) {
                    runCatching { sourceFile?.delete() }
                }
            }
        }
    }

    private fun openTextTranscriptPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, REQUEST_TEXT_TRANSCRIPTS)
    }

    private fun importSelectedTextTranscripts(data: Intent?) {
        val uris = mutableListOf<Uri>()
        data?.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri
        }
        data?.data?.let { if (it !in uris) uris += it }
        if (uris.isEmpty()) return

        modelScope.launch {
            var imported = 0
            var lastError: String? = null
            uris.forEach { uri ->
                DocumentProcessor.importUri(this@MainActivity, uri)
                    .onSuccess { ref ->
                        if (selectedDocuments.none { it.id == ref.id }) selectedDocuments += ref
                        imported += 1
                    }
                    .onFailure { error -> lastError = error.message }
            }
            runOnUiThread {
                refreshDocumentUi()
                val message = when {
                    imported > 0 && lastError != null -> "Attached $imported transcript(s). One or more files could not be added: $lastError"
                    imported > 0 -> "Attached $imported transcript${if (imported == 1) "" else "s"}."
                    else -> lastError ?: "No transcript files were added."
                }
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshDocumentUi(progress: String? = null) {
        if (!::llDocuments.isInitialized ||
            !::tvAttachedDocuments.isInitialized ||
            !::tvDocumentStatus.isInitialized ||
            !::btnDocumentMode.isInitialized) return
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val count = selectedDocuments.size
            llDocuments.visibility = if (count > 0) View.VISIBLE else View.GONE
            tvAttachedDocuments.text = if (count == 0) "" else buildString {
                append("$count transcript${if (count == 1) "" else "s"} attached")
                selectedDocuments.take(3).forEach { append("\n• ${it.displayName}") }
                if (count > 3) append("\n• +${count - 3} more")
            }
            btnDocumentMode.text = if (documentLectureMode) "Lecture notes" else "Ask files"
            tvDocumentStatus.text = progress ?: if (documentLectureMode) {
                "Whole-lecture mode • all attached files are processed in order. Leave the message blank to use the lecture-notes preset."
            } else {
                "Ask-files mode • type a question and only relevant transcript excerpts are sent to the model."
            }
            refreshSendButtonState()
        }
    }

    private fun sendDocumentRequest(input: String) {
        if (selectedDocuments.isEmpty()) return
        if (!documentLectureMode && input.isBlank()) {
            Toast.makeText(this, "Type a question about the attached transcripts.", Toast.LENGTH_SHORT).show()
            return
        }
        val docsSnapshot = selectedDocuments.toList()
        val userText = if (documentLectureMode) {
            input.ifBlank { DocumentProcessor.DEFAULT_LECTURE_PROMPT }
        } else {
            input
        }
        etInput.setText("")
        etInput.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(etInput.windowToken, 0)

        messages.add(
            Message(
                if (documentLectureMode) {
                    "Summarize ${docsSnapshot.size} attached lecture transcript${if (docsSnapshot.size == 1) "" else "s"}."
                } else {
                    userText
                },
                MessageType.USER,
            ),
        )
        reloadRecycleView()
        showLoadingIndicator()
        isGenerating = true
        refreshSendButtonState()
        DiagnosticsLogger.checkpoint(
            if (documentLectureMode) "DOCUMENT_SUMMARY_BEGIN" else "DOCUMENT_QA_BEGIN",
            "files=${docsSnapshot.size}",
        )

        documentStopRequested = false
        documentJob = modelScope.launch {
            try {
                val result = if (documentLectureMode) {
                    DocumentProcessor.summarizeLecture(
                        context = this@MainActivity,
                        documents = docsSnapshot,
                        customPrompt = userText,
                    onProgress = { p ->
                        refreshDocumentUi(
                            buildString {
                                append(p.phase)
                                if (p.total > 0) append(" • ${p.completed}/${p.total}")
                                if (p.detail.isNotBlank()) append(" • ${p.detail}")
                            },
                        )
                    },
                    shouldCancel = { documentStopRequested },
                    ).map { it.markdown }
                } else {
                    DocumentProcessor.answerFromDocuments(this@MainActivity, docsSnapshot, userText)
                }
                result.onSuccess { answer ->
                    runOnUiThread {
                        removeLoadingIndicatorOnMainThread()
                        messages.add(Message(answer, MessageType.ASSISTANT))
                        trimUiTranscriptForMemory()
                        reloadRecycleView()
                        if (documentLectureMode) {
                            documentLectureMode = false
                            refreshDocumentUi("Summary complete • switched to Ask files for follow-up questions.")
                        } else {
                            refreshDocumentUi()
                        }
                    }
                }.onFailure { error ->
                    DiagnosticsLogger.log("ERROR", "Documents", "document request failed", error)
                    val wasStopped = error is java.util.concurrent.CancellationException || documentStopRequested
                    runOnUiThread {
                        removeLoadingIndicatorOnMainThread()
                        val text = if (wasStopped) {
                            "Document processing stopped."
                        } else {
                            "Document processing failed: ${error.message}"
                        }
                        messages.add(Message(text, MessageType.ASSISTANT))
                        reloadRecycleView()
                        refreshDocumentUi(if (wasStopped) "Stopped" else "Failed: ${error.message}")
                    }
                }
            } finally {
                isGenerating = false
                documentJob = null
                documentStopRequested = false
                refreshSendButtonState()
            }
        }
    }

    private fun clearHistory() {
        if (!hasLoadedModel()) {
            chatList.clear()
            vlmChatList.clear()
            messages.clear()
            clearImages()
            reloadRecycleView()
            return
        }
        if (DocumentProcessor.isProcessing()) {
            Toast.makeText(this, "Transcript processing is still running.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!InferenceBridge.mutex.tryLock()) {
            Toast.makeText(this, "Model is busy. Clear the chat after generation finishes.", Toast.LENGTH_SHORT).show()
            return
        }

        chatList.clear()
        vlmChatList.clear()
        messages.clear()
        clearImages()
        reloadRecycleView()
        modelScope.launch {
            try {
                if (isLoadLlmModel) llmWrapper.reset()
                if (isLoadVlmModel) vlmWrapper.reset()
            } finally {
                if (InferenceBridge.mutex.isLocked) InferenceBridge.mutex.unlock()
            }
        }
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

    /** Keep the visible transcript bounded so long sessions do not retain an
     * ever-growing graph of message strings, spans and image-row views. Native
     * model history is bounded separately in trimLlmHistoryForMemory()/
     * trimVlmHistoryForMemory().
     */
    private fun trimUiTranscriptForMemory() {
        var removed = 0
        fun chars(): Int = messages.sumOf { it.content.length }
        while (messages.size > PerformanceTuning.MAX_UI_MESSAGES ||
            (messages.size > 2 && chars() > PerformanceTuning.MAX_UI_CHARS)
        ) {
            messages.removeAt(0)
            removed += 1
        }
        if (removed <= 0) return

        if (loadingMessageIndex >= 0) loadingMessageIndex = (loadingMessageIndex - removed).coerceAtLeast(-1)
        if (streamingMessageIndex >= 0) streamingMessageIndex = (streamingMessageIndex - removed).coerceAtLeast(-1)
        adapter.notifyItemRangeRemoved(0, removed)
        DiagnosticsLogger.log(
            "INFO",
            "Memory",
            "trimmed visible chat transcript removed=$removed remaining=${messages.size}",
        )
    }

    private fun reloadRecycleView() {
        adapter.notifyDataSetChanged()
        if (messages.isNotEmpty()) binding.rvChat.scrollToPosition(messages.lastIndex)
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
        runOnUiThread { removeLoadingIndicatorOnMainThread() }
    }

    private fun removeLoadingIndicatorOnMainThread() {
        val idx = loadingMessageIndex
        if (idx < 0 || idx >= messages.size) {
            loadingMessageIndex = -1
            return
        }
        if (messages[idx].type == MessageType.LOADING) {
            messages.removeAt(idx)
            adapter.notifyItemRemoved(idx)
            if (streamingMessageIndex > idx) streamingMessageIndex -= 1
        }
        loadingMessageIndex = -1
    }

    override fun onDestroy() {
        popupWindow?.dismiss()
        downloadJob?.cancel()
        modelLoadJob?.cancel()
        modelScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val DOWNLOAD_WAKELOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L
        private const val REQUEST_TEXT_TRANSCRIPTS = 3001
        private const val REQUEST_MODEL_MANAGEMENT = 3002
        private const val MENU_MANAGE_MODELS = 4101
        private const val MENU_WEB_SERVER = 4102
        private const val MENU_DIAGNOSTICS = 4103
        private const val TAG = "GenieXDemo"

        /**
         * Square edge length used for image preprocessing when the mmproj GGUF
         * does not declare one. Only a fallback — the real value is read per
         * model by [GgufVisionReader], since feeding a tower a smaller square
         * than it was trained on silently discards detail.
         */
        private const val GIB_BYTES = 1024L * 1024L * 1024L
        private const val GPU_MIN_EXTRA_HEADROOM_BYTES = GIB_BYTES
        private const val MODEL_UNLOAD_SETTLE_MS = 1500L
        private const val FALLBACK_VLM_IMAGE_SIZE = 448
        private const val STREAM_UI_UPDATE_MS = 80L

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
