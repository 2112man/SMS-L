package com.localsmsrelay

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.localsmsrelay.data.SmsMessageEntity

class MessageHistoryAdapter(
    private val onCopyOtp: (String) -> Unit
) : RecyclerView.Adapter<MessageHistoryAdapter.MessageViewHolder>() {
    private var messages: List<SmsMessageEntity> = emptyList()
    private val expandedIds = mutableSetOf<Long>()

    fun submitList(newMessages: List<SmsMessageEntity>) {
        val oldMessages = messages
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldMessages.size
            override fun getNewListSize(): Int = newMessages.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldMessages[oldItemPosition].id == newMessages[newItemPosition].id

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old = oldMessages[oldItemPosition]
                val new = newMessages[newItemPosition]
                return old.id == new.id && old.sender == new.sender && old.text == new.text &&
                    old.otp == new.otp && old.receivedAt == new.receivedAt &&
                    old.messageId == new.messageId
            }
        })
        messages = newMessages
        expandedIds.retainAll(newMessages.mapTo(mutableSetOf()) { it.id })
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemCount(): Int = messages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val context = parent.context
        val density = context.resources.displayMetrics.density
        fun px(value: Int): Int = (value * density).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(14), px(16), px(14))
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = px(10).toFloat()
                setColor(Color.WHITE)
                setStroke(px(1), Color.rgb(224, 224, 224))
            }
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(px(12), px(6), px(12), px(6))
            }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val sender = TextView(context).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(32, 32, 32))
        }
        val time = TextView(context).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.rgb(100, 100, 100))
        }
        header.addView(sender, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(time)

        val body = TextView(context).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.rgb(48, 48, 48))
            setLineSpacing(0f, 1.12f)
        }

        val otpRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val otp = TextView(context).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(14, 77, 100))
        }
        val copy = Button(context).apply {
            text = "复制"
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
        }
        otpRow.addView(otp, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        otpRow.addView(copy, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(42)))

        root.addView(header)
        root.addView(body, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = px(8) })
        root.addView(otpRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = px(8) })

        return MessageViewHolder(root, sender, time, body, otpRow, otp, copy)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        holder.sender.text = message.sender?.takeIf { it.isNotBlank() } ?: "iPhone 短信"
        holder.time.text = MessageTimeFormatter.format(message.receivedAt)
        holder.body.text = message.text
        val isExpanded = message.id in expandedIds
        holder.body.maxLines = if (isExpanded) Int.MAX_VALUE else COLLAPSED_LINES
        holder.body.ellipsize = if (isExpanded) null else TextUtils.TruncateAt.END

        val otpValue = message.otp
        holder.otpRow.visibility = if (otpValue.isNullOrBlank()) View.GONE else View.VISIBLE
        holder.otp.text = otpValue?.let { "验证码：$it" }.orEmpty()
        holder.copy.setOnClickListener {
            if (!otpValue.isNullOrBlank()) onCopyOtp(otpValue)
        }
        holder.itemView.setOnClickListener {
            if (message.id in expandedIds) expandedIds.remove(message.id) else expandedIds.add(message.id)
            val adapterPosition = holder.bindingAdapterPosition
            if (adapterPosition != RecyclerView.NO_POSITION) notifyItemChanged(adapterPosition)
        }
    }

    class MessageViewHolder(
        itemView: View,
        val sender: TextView,
        val time: TextView,
        val body: TextView,
        val otpRow: View,
        val otp: TextView,
        val copy: Button
    ) : RecyclerView.ViewHolder(itemView)

    companion object {
        private const val COLLAPSED_LINES = 5
    }
}
