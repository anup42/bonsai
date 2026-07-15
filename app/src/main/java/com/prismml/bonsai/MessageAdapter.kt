package com.prismml.bonsai

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class MessageAdapter : ListAdapter<ChatMessage, MessageAdapter.MessageViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val author: TextView = itemView.findViewById(R.id.author)
        private val reasoningPanel: View = itemView.findViewById(R.id.reasoningPanel)
        private val reasoningContent: TextView = itemView.findViewById(R.id.reasoningContent)
        private val content: TextView = itemView.findViewById(R.id.content)
        private val meta: TextView = itemView.findViewById(R.id.meta)
        private val card: MaterialCardView = itemView.findViewById(R.id.messageCard)

        fun bind(message: ChatMessage) {
            val isUser = message.role == "You"
            author.text = message.role
            reasoningContent.text = message.reasoning.orEmpty()
            reasoningPanel.isVisible = !isUser && !message.reasoning.isNullOrBlank()
            content.text = message.text
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
