package com.samsung.ibit

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import io.noties.markwon.Markwon

class MessageAdapter(
    private val markwon: Markwon,
) : ListAdapter<ChatMessage, MessageAdapter.MessageViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view, markwon)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class MessageViewHolder(
        itemView: View,
        private val markwon: Markwon,
    ) : RecyclerView.ViewHolder(itemView) {
        private val author: TextView = itemView.findViewById(R.id.author)
        private val reasoningPanel: View = itemView.findViewById(R.id.reasoningPanel)
        private val reasoningContent: TextView = itemView.findViewById(R.id.reasoningContent)
        private val content: TextView = itemView.findViewById(R.id.content)
        private val meta: TextView = itemView.findViewById(R.id.meta)
        private val card: MaterialCardView = itemView.findViewById(R.id.messageCard)

        fun bind(message: ChatMessage) {
            val isUser = message.role == "You"
            author.text = message.role
            setMessageText(reasoningContent, message.reasoning.orEmpty(), message, isUser)
            reasoningPanel.isVisible = !isUser && !message.reasoning.isNullOrBlank()
            setMessageText(content, message.text, message, isUser)
            meta.text = message.meta.orEmpty()
            meta.isVisible = !message.meta.isNullOrBlank()
            card.setCardBackgroundColor(
                itemView.context.getColor(
                    if (isUser) R.color.user_message_surface else R.color.assistant_message_surface,
                ),
            )
            author.setTextColor(
                itemView.context.getColor(
                    if (isUser) R.color.on_user_message else R.color.accent,
                ),
            )
        }

        private fun setMessageText(
            view: TextView,
            text: String,
            message: ChatMessage,
            isUser: Boolean,
        ) {
            if (isUser || message.isStreaming) {
                view.text = text
            } else {
                markwon.setMarkdown(view, text)
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem == newItem
        }
    }
}
