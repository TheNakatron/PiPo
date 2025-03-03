package com.example.msg

import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import android.graphics.Color
import android.os.Looper
import android.view.Gravity
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import java.text.SimpleDateFormat
import java.util.*
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.os.Handler




class ChatActivity : AppCompatActivity() {

    private lateinit var webSocketManager: WebSocketManager
    private lateinit var messagesContainer: LinearLayout
    private lateinit var sendButton: ImageButton
    private lateinit var messageEditText: EditText
    private lateinit var messagesScrollView: ScrollView

    private lateinit var friendId: String
    private lateinit var myUserId: String
    private var aesKey: String? = null

    private val chatListener: (String) -> Unit = { message ->
        runOnUiThread { handleServerMessage(message) }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)


        messagesContainer = findViewById(R.id.messagesContainer)
        sendButton = findViewById(R.id.sendButton)
        messageEditText = findViewById(R.id.messageEditText)
        messagesScrollView = findViewById(R.id.messagesScrollView)


        friendId = intent.getStringExtra("friend_id") ?: ""
        myUserId = intent.getStringExtra("my_user_id") ?: ""
        Log.d("ChatActivity", "Открыт чат с friendId = $friendId")

        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        aesKey = prefs.getString("aes_$friendId", null)

        webSocketManager = (application as MyApplication).webSocketManager

        val friendId = intent.getStringExtra("friend_id")
        Log.d("ChatActivity", "ChatActivity opened with friendId: $friendId")

        loadChatHistory()

        (application as MyApplication).unreadCounts.remove(friendId)
        // Устанавливаем, что текущий активный чат – с этим friendId
        (application as MyApplication).activeChatFriendId = friendId

        sendButton.setOnClickListener {
            val text = messageEditText.text.toString().trim()
            if (text.isNotEmpty()) {
                val command = "message:$friendId:send:$text"
                webSocketManager.sendMessage(command)
                val timeStamp = getCurrentTimeStamp()
                val formattedMsg = "[$timeStamp] Я: $text"
                addMessageToUI(formattedMsg, true)
                saveMessageToHistory(formattedMsg)
                messageEditText.text.clear()
            }
        }

        messageEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                // Когда поле получает фокус (и клавиатура открывается)
                // Используем Handler для прокрутки через 1 секунду после открытия клавиатуры
                Handler(Looper.getMainLooper()).postDelayed({
                    messagesScrollView.post {
                        // Прокручиваем ScrollView вниз
                        messagesScrollView.fullScroll(View.FOCUS_DOWN)
                    }
                }, 500) // Задержка 500 мс для того, чтобы клавиатура успела открыться
            }
        }
    }

    override fun onResume() {
        super.onResume()
        webSocketManager.connect()
        webSocketManager.addListener(chatListener)
        val app = application as MyApplication
        app.unreadCounts.remove(friendId)
        // Отправляем broadcast, чтобы MenuActivity обновила уведомления
        val intent = Intent("unread_reset").apply { putExtra("friend_id", friendId) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        // Отправляем service:pong:ready, если token установлен
        if (webSocketManager.token != null) {
            webSocketManager.sendMessage("service:pong:ready")
        } else {
            // Если токена нет, отправляем команду логина (автоматически, если требуется)
            val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
            prefs.getString("saved_password", null)?.let { savedPasswordHash ->
                val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                val loginCommand = "service:login:$androidId:$savedPasswordHash"
                webSocketManager.sendMessage(loginCommand)
            }
        }
    }

    override fun onPause() {
        webSocketManager.removeListener(chatListener)
        // Сбрасываем активный чат
        (application as MyApplication).activeChatFriendId = null
        val app = application as MyApplication
        app.activeChatFriendId = null
        app.unreadCounts.remove(friendId)
        super.onPause()
    }



    private fun handleServerMessage(message: String) {
        Log.d("ChatActivity", "Обработка входящего сообщения для чата с $friendId: $message")
        // Обрабатываем только сообщения, предназначенные для текущего чата
        if (message.startsWith("message:$friendId:receive:")) {
            val parts = message.split(":")
            val formattedMsg: String = if (parts.size >= 5) {
                // Собираем текст сообщения (части с 3-й по предпоследнюю)
                val msgText = parts.subList(3, parts.size - 1).joinToString(":")
                val serverTimeAndId = parts.last().trim()
                // Извлекаем msg_id, если оно присутствует в квадратных скобках
                val msgId = extractMsgId(serverTimeAndId)
                // Отправляем подтверждение (ACK) только в чате
                if (msgId != null) {
                    webSocketManager.sendMessage("service:ack:$msgId")
                }
                // Если есть msg_id, убираем его из отображаемого времени
                val serverTime = if (serverTimeAndId.contains("[")) {
                    serverTimeAndId.substringBefore("[")
                } else {
                    serverTimeAndId
                }
                "[$serverTime] $friendId: $msgText"
            } else {
                // Если формат сообщения не соответствует ожиданиям, используем локальное время
                val msgText = message.substringAfter("message:$friendId:receive:")
                val timeStamp = getCurrentTimeStamp()
                "[$timeStamp] $friendId: $msgText"
            }
            addMessageToUI(formattedMsg, false)
            saveMessageToHistory(formattedMsg)
        } else {
            Log.d("ChatActivity", "Сообщение не для этого чата: $message")
        }
    }

    // Вспомогательная функция для извлечения msg_id из строки вида "18/04[5438]"
    private fun extractMsgId(text: String): String? {
        val start = text.indexOf('[')
        val end = text.indexOf(']')
        return if (start != -1 && end != -1 && end > start) {
            text.substring(start + 1, end)
        } else null
    }



    private fun addMessageToUI(formattedMsg: String, isOutgoing: Boolean) {
        Log.d("ChatUI", "Добавление сообщения: \"$formattedMsg\", isOutgoing = $isOutgoing")

        val view = layoutInflater.inflate(R.layout.item_message, messagesContainer, false)
        val messageWrapper = view.findViewById<LinearLayout>(R.id.messageWrapper) // Здесь остаемся с LinearLayout
        val messageContainer = view.findViewById<LinearLayout>(R.id.messageContainer)
        val messageTextView = view.findViewById<TextView>(R.id.messageText)
        val messageTimeView = view.findViewById<TextView>(R.id.messageTime)

        val timeRegex = "\\[(.*?)\\]".toRegex()
        val timeMatch = timeRegex.find(formattedMsg)
        val timeText = timeMatch?.groupValues?.get(1) ?: getCurrentTimeStamp()
        val messageText = formattedMsg.replace("[$timeText]", "").trim()

        messageTextView.text = messageText
        messageTimeView.text = timeText

        // Применение LinearLayout.LayoutParams для управления выравниванием
        val params = messageWrapper.layoutParams as LinearLayout.LayoutParams
        if (isOutgoing) {
            messageContainer.setBackgroundResource(R.drawable.bubble_outgoing)
            params.gravity = Gravity.END // Исходящее сообщение выравниваем по правому краю
            Log.d("ChatUI", "Устанавливаю gravity = Gravity.END для исходящего сообщения")
        } else {
            messageContainer.setBackgroundResource(R.drawable.bubble_incoming)
            params.gravity = Gravity.START // Входящее сообщение выравниваем по левому краю
            Log.d("ChatUI", "Устанавливаю gravity = Gravity.START для входящего сообщения")
        }
        messageWrapper.layoutParams = params

        messagesContainer.addView(view)

        // Автопрокрутка вниз после добавления нового сообщения
        messagesScrollView.post {
            messagesScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }



    private fun saveMessageToHistory(message: String) {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val key = "chat_history_$friendId"
        val existing = prefs.getString(key, "")
        val updated = if (existing.isNullOrEmpty()) message else "$existing|||$message"
        prefs.edit().putString(key, updated).apply()
    }

    private fun loadChatHistory() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val key = "chat_history_$friendId"
        val history = prefs.getString(key, "")
        messagesScrollView.post {
            messagesScrollView.fullScroll(View.FOCUS_DOWN)
        }
        if (!history.isNullOrEmpty()) {
            val messages = history.split("|||")
            messages.forEach { msg ->
                if (!TextUtils.isEmpty(msg)) {
                    // Определяем, исходящее ли сообщение
                    val isOutgoing = msg.contains("Я:")
                    addMessageToUI(msg, isOutgoing)
                }
            }
            // Прокручиваем вниз после добавления сообщений
            messagesScrollView.post {
                messagesScrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }


    private fun getCurrentTimeStamp(): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date())
    }
}
