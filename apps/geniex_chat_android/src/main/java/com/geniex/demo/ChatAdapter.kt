// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo

import android.content.Context
import android.graphics.BitmapFactory
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import java.io.File

data class Message(
    val content: String,
    val type: MessageType,
    val images: List<File> = emptyList(),
)

enum class MessageType(
    val value: Int,
) {
    USER(0),
    ASSISTANT(1),
    PROFILE(2),
    IMAGES(3),
    ASSISTANT_IMAGES(4),
    LOADING(5),
    ;

    companion object {
        fun from(value: Int): MessageType = entries.firstOrNull { it.value == value } ?: PROFILE
    }
}

class ChatAdapter(
    private val messages: List<Message>,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    // Markwon (especially tables/LaTeX) is relatively expensive to construct.
    // Keep one full renderer and one lighter streaming renderer per adapter.
    // The streaming renderer intentionally omits LaTeX so growing output can
    // be reformatted several times per second without repeatedly building math
    // render trees. Completion gets one full render with LaTeX enabled.
    private var markdownRenderer: Markwon? = null
    private var streamingMarkdownRenderer: Markwon? = null

    private fun markwon(context: Context): Markwon =
        markdownRenderer ?:
            Markwon
                .builder(context)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(context))
                .usePlugin(LinkifyPlugin.create())
                .usePlugin(MarkwonInlineParserPlugin.create())
                .usePlugin(
                    JLatexMathPlugin.create(context.resources.displayMetrics.scaledDensity * 16f) { builder ->
                        builder.inlinesEnabled(true)
                        builder.blocksEnabled(true)
                    },
                ).build()
                .also { markdownRenderer = it }

    private fun streamingMarkwon(context: Context): Markwon =
        streamingMarkdownRenderer ?:
            Markwon
                .builder(context)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(context))
                .usePlugin(LinkifyPlugin.create())
                .usePlugin(MarkwonInlineParserPlugin.create())
                .build()
                .also { streamingMarkdownRenderer = it }
    override fun getItemViewType(position: Int): Int {
        val message = messages[position]
        return message.type.value
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val type = MessageType.from(viewType)
        return when (type) {
            MessageType.USER -> {
                UserViewHolder(inflater.inflate(R.layout.item_user_message, parent, false))
            }

            MessageType.ASSISTANT -> {
                AiViewHolder(
                    inflater.inflate(R.layout.item_ai_message, parent, false),
                    markwon(parent.context),
                    streamingMarkwon(parent.context),
                )
            }

            MessageType.IMAGES -> {
                ImagesViewHolder(inflater.inflate(R.layout.item_image_message, parent, false))
            }

            MessageType.ASSISTANT_IMAGES -> {
                ImagesViewHolder(
                    inflater.inflate(
                        R.layout.item_assistant_image_message,
                        parent,
                        false,
                    ),
                )
            }

            MessageType.LOADING -> {
                LoadingViewHolder(
                    inflater.inflate(R.layout.item_loading_message, parent, false),
                )
            }

            else -> {
                ProfileViewHolder(inflater.inflate(R.layout.item_profile_message, parent, false))
            }
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        val message = messages[position]
        if (holder is UserViewHolder) holder.bind(message)
        if (holder is AiViewHolder) holder.bind(message)
        if (holder is ImagesViewHolder) holder.bind(message)
        if (holder is ProfileViewHolder) holder.bind(message)
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        if (payloads.contains(PAYLOAD_STREAM_TEXT) && holder is AiViewHolder) {
            holder.bindStreaming(messages[position])
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun getItemCount() = messages.size

    class UserViewHolder(
        itemView: View,
    ) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tv_message)

        fun bind(message: Message) {
            tvMessage.text = message.content
        }
    }

    class AiViewHolder(
        itemView: View,
        private val markwon: Markwon,
        private val streamingMarkwon: Markwon,
    ) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tv_message)

        fun bind(message: Message) {
            val markdown = MarkdownNormalizer.normalize(message.content.trim())
            markwon.setMarkdown(tvMessage, markdown)
            tvMessage.setTextIsSelectable(true)
            tvMessage.movementMethod = LinkMovementMethod.getInstance()
        }

        fun bindStreaming(message: Message) {
            // Apply Markdown progressively, but with the lighter renderer. MainActivity
            // throttles these binds so we do not parse the entire growing response on
            // every native output piece. Links/selection are enabled at completion to
            // avoid touch/focus churn while the RecyclerView item is changing height.
            val markdown = MarkdownNormalizer.normalize(message.content)
            streamingMarkwon.setMarkdown(tvMessage, markdown)
            tvMessage.setTextIsSelectable(false)
            tvMessage.movementMethod = null
        }
    }

    class ProfileViewHolder(
        itemView: View,
    ) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tv_message)

        fun bind(message: Message) {
            tvMessage.text = message.content
        }

    }

    class LoadingViewHolder(
        itemView: View,
    ) : RecyclerView.ViewHolder(itemView)

    class ImagesViewHolder(
        itemView: View,
    ) : RecyclerView.ViewHolder(itemView) {
        private val imageContainer: LinearLayout = itemView.findViewById(R.id.image_container)

        fun bind(message: Message) {
            val savedImageFiles = message.images
            imageContainer.removeAllViews()
            val context = itemView.context

            for (file in savedImageFiles) {
                val itemView =
                    LayoutInflater
                        .from(context)
                        .inflate(R.layout.item_image_item_message, imageContainer, false)
                val ivImage = itemView.findViewById<ImageView>(R.id.iv_image)
                val bitmap = decodeThumbnail(file, 320)
                if (bitmap != null) ivImage.setImageBitmap(bitmap)
                imageContainer.addView(itemView)
            }
        }

        fun clear() {
            // Recycled holders otherwise keep ImageViews (and their decoded
            // thumbnail bitmaps) alive until the holder is rebound.
            imageContainer.removeAllViews()
        }

        private fun decodeThumbnail(file: File, maxDimension: Int): android.graphics.Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth / (sample * 2), bounds.outHeight / (sample * 2)) >= maxDimension) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }
            return BitmapFactory.decodeFile(file.absolutePath, options)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is ImagesViewHolder) holder.clear()
        super.onViewRecycled(holder)
    }

    companion object {
        const val PAYLOAD_STREAM_TEXT = "stream_markdown"
    }
}
