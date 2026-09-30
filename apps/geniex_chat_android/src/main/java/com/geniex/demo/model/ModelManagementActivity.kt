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
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.ModelPullInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ModelManagementActivity : FragmentActivity() {
    private lateinit var binding: ActivityModelsBinding
    private lateinit var models: List<ModelData>
    private lateinit var adapter: ModelManagementAdapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var states: List<ModelUiState> = emptyList()
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
            val selectedId = AppPreferences.getSelectedModelId(this@ModelManagementActivity)
            val activeId = InferenceBridge.activeModelId
            val refreshed = models.map { model ->
                val old = states.firstOrNull { it.model.id == model.id }
                val available = ModelManagerWrapper.getPaths(model.modelName) != null
                ModelUiState(
                    model = model,
                    available = available,
                    selected = model.id == selectedId,
                    loaded = model.id == activeId,
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
        val hub = runCatching { HubSource.valueOf(model.hub ?: "AUTO") }.getOrDefault(HubSource.AUTO)
        val isAiHub = hub == HubSource.AIHUB ||
            (hub == HubSource.AUTO && (model.modelName.startsWith("ai-hub-models/") || model.modelName.startsWith("qualcomm/")))
        if (isAiHub && model.chipset.isNullOrBlank()) {
            Toast.makeText(this, "This AI Hub model has no chipset configured.", Toast.LENGTH_LONG).show()
            return
        }
        setState(model.id) { it.copy(downloading = true, progress = 0, error = null) }
        DiagnosticsLogger.checkpoint("MODEL_DOWNLOAD_BEGIN", "${model.modelName}:${model.quant.orEmpty()}")

        val input = ModelPullInput(
            model_name = model.modelName,
            precision = model.quant,
            hub = hub,
            chipset = model.chipset,
            display_name = model.aiHubDisplayName,
        )
        val wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "geniex:model_management_download")
        wakeLock.acquire(60 * 60 * 1000L)
        downloadJob = scope.launch {
            try {
                ModelManagerWrapper.pullFlow(input).collect { event ->
                    when (event) {
                        is ModelManagerWrapper.PullEvent.Progress -> {
                            val total = event.files.sumOf { it.total_bytes }
                            val done = event.files.sumOf { it.downloaded_bytes }
                            val progress = if (total > 0L) ((done * 100L) / total).toInt().coerceIn(0, 100) else 0
                            setState(model.id) { it.copy(downloading = true, progress = progress, error = null) }
                        }
                        is ModelManagerWrapper.PullEvent.Completed -> {
                            DiagnosticsLogger.checkpoint("MODEL_DOWNLOAD_COMPLETE", model.modelName)
                            setState(model.id) { it.copy(available = true, downloading = false, progress = 100, error = null) }
                            runOnUiThread { Toast.makeText(this@ModelManagementActivity, "${model.displayName} is ready.", Toast.LENGTH_SHORT).show() }
                        }
                        is ModelManagerWrapper.PullEvent.Error -> {
                            DiagnosticsLogger.log("ERROR", "ModelDownload", "${model.modelName}: ${event.code} ${event.message}")
                            setState(model.id) { it.copy(downloading = false, error = event.message) }
                        }
                    }
                }
            } catch (e: Exception) {
                DiagnosticsLogger.log("ERROR", "ModelDownload", model.modelName, e)
                setState(model.id) { it.copy(downloading = false, error = e.message ?: "download failed") }
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                downloadJob = null
            }
        }
    }

    private fun selectModel(model: ModelData) {
        AppPreferences.setSelectedModelId(this, model.id)
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SELECTED_MODEL_ID, model.id))
        Toast.makeText(this, "Selected ${model.displayName}", Toast.LENGTH_SHORT).show()
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
            binding.tvModelStorageSummary.text = buildString {
                append("$available of ${newStates.size} models available")
                if (!workspace.isNullOrBlank()) append("\nWorkspace: $workspace")
            }
        }
    }

    companion object {
        const val EXTRA_SELECTED_MODEL_ID = "selected_model_id"
    }
}
