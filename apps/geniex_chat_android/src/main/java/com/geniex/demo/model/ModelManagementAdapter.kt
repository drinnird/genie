package com.geniex.demo.model

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.geniex.demo.bean.ModelData
import com.geniex.demo.databinding.ItemModelManagementBinding

data class ModelUiState(
    val model: ModelData,
    val available: Boolean = false,
    val persistentFilesPresent: Boolean = false,
    val loaded: Boolean = false,
    val blockedByActiveModel: Boolean = false,
    val downloading: Boolean = false,
    val progress: Int? = null,
    val error: String? = null,
)

class ModelManagementAdapter(
    private val onDownload: (ModelData) -> Unit,
    private val onUse: (ModelData) -> Unit,
    private val onDelete: (ModelData) -> Unit,
) : ListAdapter<ModelUiState, ModelManagementAdapter.ViewHolder>(DiffCallback) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).model.id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemModelManagementBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(private val binding: ItemModelManagementBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(state: ModelUiState) {
            val model = state.model
            binding.tvModelName.text = model.displayName
            binding.tvModelMeta.text =
                listOfNotNull(
                    model.quant,
                    model.computeSummary,
                    model.minAvailableMemoryGiB?.let { String.format("%.1f GiB free recommended", it) },
                ).joinToString("  •  ")
            binding.tvModelRepo.text = model.modelName
            binding.pbModelDownload.visibility = if (state.downloading) android.view.View.VISIBLE else android.view.View.GONE
            binding.pbModelDownload.progress = state.progress ?: 0

            binding.tvModelStatus.text = when {
                state.error != null -> "Download failed"
                state.downloading -> state.progress?.let { "Downloading • $it%" } ?: "Downloading"
                state.loaded -> "Active"
                state.available && state.blockedByActiveModel -> "Available • unload active model to switch"
                state.available -> "Available • ready to load"
                state.persistentFilesPresent -> "Stored files • incomplete or incompatible"
                else -> "Not downloaded"
            }

            // Keep the action surface unambiguous: a model that is already on
            // disk never shows Download, while a missing model never shows Load.
            binding.btnModelDownload.visibility =
                if (!state.available || state.downloading) android.view.View.VISIBLE else android.view.View.GONE
            binding.btnModelDownload.isEnabled = !state.available && !state.downloading
            binding.btnModelDownload.text = if (state.downloading) "Downloading" else "Download"
            binding.btnModelDownload.setOnClickListener { onDownload(model) }

            binding.btnModelUse.visibility =
                if (state.available && !state.downloading) android.view.View.VISIBLE else android.view.View.GONE
            binding.btnModelUse.isEnabled = state.available && !state.downloading && !state.blockedByActiveModel
            binding.btnModelUse.text = if (state.loaded) "Unload" else "Load"
            binding.btnModelUse.setOnClickListener { onUse(model) }

            // Do not show destructive controls for models that do not exist
            // locally. Once downloaded the action appears; active models keep
            // it visible but disabled until unloaded.
            binding.btnModelDelete.visibility =
                if ((state.available || state.persistentFilesPresent) && !state.downloading) android.view.View.VISIBLE
                else android.view.View.GONE
            binding.btnModelDelete.isEnabled =
                (state.available || state.persistentFilesPresent) && !state.loaded && !state.downloading
            binding.btnModelDelete.setOnClickListener { onDelete(model) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ModelUiState>() {
        override fun areItemsTheSame(oldItem: ModelUiState, newItem: ModelUiState): Boolean =
            oldItem.model.id == newItem.model.id

        override fun areContentsTheSame(oldItem: ModelUiState, newItem: ModelUiState): Boolean =
            oldItem == newItem
    }
}
