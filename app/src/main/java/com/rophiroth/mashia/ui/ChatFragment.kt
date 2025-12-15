package com.rophiroth.mashia.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rophiroth.mashia.R

data class Message(val text: String, val isMe: Boolean)

class ChatFragment : Fragment() {
    private val messages = mutableListOf<Message>()
    private lateinit var adapter: MessagesAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_chat, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val list = view.findViewById<RecyclerView>(R.id.messages_list)
        val input = view.findViewById<EditText>(R.id.input_text)
        val send = view.findViewById<ImageButton>(R.id.btn_send)
        val attachFile = view.findViewById<ImageButton>(R.id.btn_attach)
        val camera = view.findViewById<ImageButton>(R.id.btn_camera)
        val mic = view.findViewById<ImageButton>(R.id.btn_mic)

        adapter = MessagesAdapter(messages)
        list.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        list.adapter = adapter

        send.setOnClickListener {
            val txt = input.text.toString().trim()
            if (txt.isNotEmpty()) {
                addMessage(txt, true)
                input.setText("")
                mockReply()
            }
        }

        attachFile.setOnClickListener {
            // Guarded: add file picker later
        }
        camera.setOnClickListener {
            requestPermission(Manifest.permission.CAMERA)
        }
        mic.setOnClickListener {
            requestPermission(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun addMessage(text: String, isMe: Boolean) {
        messages.add(Message(text, isMe))
        adapter.notifyItemInserted(messages.lastIndex)
    }

    private fun mockReply() {
        Handler(Looper.getMainLooper()).postDelayed({
            addMessage("Respuesta por defecto: pronto conectaremos IA.", false)
        }, 700)
    }

    private fun requestPermission(permission: String) {
        if (ContextCompat.checkSelfPermission(requireContext(), permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(requireActivity(), arrayOf(permission), 0)
        }
    }
}

class MessagesAdapter(private val items: List<Message>) : RecyclerView.Adapter<MessageVH>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return MessageVH(v)
    }
    override fun onBindViewHolder(holder: MessageVH, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size
}

class MessageVH(view: View) : RecyclerView.ViewHolder(view) {
    private val bubbleMe: View = view.findViewById(R.id.bubble_me)
    private val bubbleOther: View = view.findViewById(R.id.bubble_other)
    private val textMe: android.widget.TextView = view.findViewById(R.id.text_me)
    private val textOther: android.widget.TextView = view.findViewById(R.id.text_other)
    fun bind(m: Message) {
        if (m.isMe) {
            bubbleMe.visibility = View.VISIBLE
            bubbleOther.visibility = View.GONE
            textMe.text = m.text
        } else {
            bubbleMe.visibility = View.GONE
            bubbleOther.visibility = View.VISIBLE
            textOther.text = m.text
        }
    }
}

