package com.geniex.demo.model

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

class ModelManagementActivity : FragmentActivity() {
    private lateinit var binding: ActivityModelsBinding
    private lateinit var models: List<ModelData>
    private lateinit var adapter: ModelManagementAdapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var states: List<ModelUiState> = emptyList()
    private var downloadJob: Job? = null
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
        downloadJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshStates() {
        scope.launch {
            val activeId = InferenceBridge.activeModelId
            val refreshed = models.map { model ->
                val old = states.firstOrNull { it.model.id == model.id }
                val available = ModelManagerWrapper.getPaths(model.modelName) != null
                ModelUiState(
                    model = model,
                    available = available,
                    loaded = model.id == activeId,
                    blockedByActiveModel = activeId != null && activeId != model.id,
                    downloading = old?.downloading == true,
                    progress = old?.progress,
                    error = old?.error,
                )
            }
            updateStates(refreshed)
        }
    }

    private fun downloadModel(model: ModelData) {
        if (downloadJob?.isActive == true) {
            Toast.makeText(this, "Another model is already downloading.", Toast.LENGTH_SHORT).show()
            return
        }
        if (ModelDownloadCoordinator.isAiHub(model) && model.chipset.isNullOrBlank()) {
            Toast.makeText(this, "This AI Hub model has no chipset configured.", Toast.LENGTH_LONG).show()
            return
        }
        setState(model.id) { it.copy(downloading = true, progress = 0, error = null) }
        DiagnosticsLogger.checkpoint("MODEL_DOWNLOAD_BEGIN", "${model.modelName}:${model.quant.orEmpty()}")

        val wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "geniex:model_management_download")
        wakeLock.acquire(DOWNLOAD_WAKELOCK_TIMEOUT_MS)
        val newDownloadJob = scope.launch(start = CoroutineStart.LAZY) {
            val thisJob = coroutineContext[Job]
            try {
                ModelDownloadCoordinator.downloadFlow(this@ModelManagementActivity, model).collect { event ->
                    when (event) {
                        is ModelDownloadCoordinator.Event.Progress -> {
                            if (states.firstOrNull { it.model.id == model.id }?.progress != event.percent) {
                                setState(model.id) {
                                    it.copy(downloading = true, progress = event.percent, error = null)
                                }
                            }
                        }
                        is ModelDownloadCoordinator.Event.Completed -> {
                            val paths = ModelManagerWrapper.getPaths(model.modelName)
                            val persistent = WorkingDirectoryManager.isPersistentModelPath(
                                this@ModelManagementActivity,
                                paths?.model_path,
                            )
                            DiagnosticsLogger.checkpoint(
                                "MODEL_DOWNLOAD_COMPLETE",
                                "${model.modelName} path=${paths?.model_path.orEmpty()} persistent=$persistent",
                            )
                            setState(model.id) {
                                it.copy(
                                    available = paths != null,
                                    downloading = false,
                                    progress = 100,
                                    error = if (paths != null && !persistent) {
                                        "Model downloaded outside Genie/models; export diagnostics before reinstalling."
                                    } else {
                                        null
                                    },
                                )
                            }
                            runOnUiThread {
                                val message = if (persistent) {
                                    "${model.displayName} is ready in Genie/models."
                                } else {
                                    "${model.displayName} downloaded, but storage verification failed."
                                }
                                Toast.makeText(
                                    this@ModelManagementActivity,
                                    message,
                                    if (persistent) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                        is ModelDownloadCoordinator.Event.Error -> {
                            DiagnosticsLogger.log(
                                "ERROR",
                                "ModelDownload",
                                "${model.modelName}: ${event.code ?: "http"} ${event.message}",
                            )
                            setState(model.id) { it.copy(downloading = false, error = event.message) }
                            runOnUiThread {
                                Toast.makeText(
                                    this@ModelManagementActivity,
                                    "Download failed: ${event.message}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                DiagnosticsLogger.log("ERROR", "ModelDownload", model.modelName, e)
                setState(model.id) { it.copy(downloading = false, error = e.message ?: "download failed") }
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                if (downloadJob === thisJob) downloadJob = null
            }
        }
        downloadJob = newDownloadJob
        newDownloadJob.start()
    }

    private fun selectModel(model: ModelData) {
        val activeId = InferenceBridge.activeModelId
        val action = if (activeId == model.id) ACTION_UNLOAD else ACTION_LOAD
        if (activeId != null && activeId != model.id) {
            Toast.makeText(this, "Unload the active model before loading another one.", Toast.LENGTH_SHORT).show()
            return
        }
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
            val result = runCatching { ModelManagerWrapper.remove(model.modelName) }
            result.onSuccess { code ->
                DiagnosticsLogger.checkpoint("MODEL_DELETE", "${model.modelName} rc=$code")
                if (code == 0) ModelDownloadCoordinator.discardStaging(this@ModelManagementActivity, model)
                runOnUiThread {
                    if (code == 0) Toast.makeText(this@ModelManagementActivity, "Model deleted.", Toast.LENGTH_SHORT).show()
                    else Toast.makeText(this@ModelManagementActivity, "Delete failed (code $code).", Toast.LENGTH_LONG).show()
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
        private const val DOWNLOAD_WAKELOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L
    }
}
