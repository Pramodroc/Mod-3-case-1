package mod3.case1

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(private val messages: MutableList<Message>) :
    RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val container: LinearLayout = view.findViewById(R.id.llContainer)
        val tvMessage: TextView = view.findViewById(R.id.tvMessage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val message = messages[position]

        val fullText = if (!message.isUser && !message.sources.isNullOrEmpty()) {
            val sourcesFormatted = message.sources.joinToString("\n• ")
            "${message.text}\n\n📌 Sources:\n• $sourcesFormatted"
        } else {
            message.text
        }

        holder.tvMessage.text = fullText

        if (message.isUser) {
            holder.container.gravity = Gravity.END
            holder.tvMessage.setBackgroundResource(R.drawable.bg_user_bubble)
        } else {
            holder.container.gravity = Gravity.START
            holder.tvMessage.setBackgroundResource(R.drawable.bg_bot_bubble)
        }
    }

    override fun getItemCount() = messages.size

    fun addMessage(message: Message) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }
}
