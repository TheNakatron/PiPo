package com.example.msg

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

class WebSocketManager(private val context: Context) {

    private val listeners = mutableListOf<(String) -> Unit>()
    private lateinit var webSocket: WebSocket
    private val client = OkHttpClient()

    var isConnected = false
        private set

    var token: String? = null
        set(value) {
            field = value
            if (value != null) {
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                prefs.edit().putString("token", value)
                    .putLong("token_time", System.currentTimeMillis())
                    .apply()
            }
        }
    var userId: String? = null
        private set

    fun connect() {
        if (isConnected) {
            Log.d("WebSocket", "Уже подключено, повторное подключение не требуется")
            return
        }
        val request = Request.Builder()
            .url("ws://192.168.1.194:12345")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                isConnected = true
                Log.d("WebSocket", "Подключено к серверу")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("WebSocket", "Получено сообщение: $text")
                // Обработка успешного логина
                if (text.startsWith("service:login:successful:")) {
                    val parts = text.split(":")
                    if (parts.size >= 5) {
                        userId = parts[3]
                        token = parts[4]
                        Log.d("WebSocket", "Авторизация успешна: userId=$userId, token=$token")
                    }
                }

                // Если получено сообщение от друга в формате receive, отправляем ACK только если чат открыт
                if (text.startsWith("message:") && text.contains(":receive:")) {
                    val parts = text.split(":")
                    if (parts.size >= 5) {
                        val fid = parts[1]
                        // Получаем активный чат из глобального состояния
                        val app = context.applicationContext as MyApplication
                        if (app.activeChatFriendId == fid) {
                            // Извлекаем msg_id из последней части, если она содержит идентификатор в квадратных скобках
                            val lastPart = parts.last().trim()
                            val msgId = extractMsgId(lastPart)
                            if (msgId != null) {
                                Log.d("WebSocket", "Отправка ACK для msgId: $msgId")
                                // Отправляем ACK без добавления токена
                                webSocket.send("service:ack:$msgId")
                            }
                        }
                    }
                }

                // Передаём исходное сообщение всем слушателям
                listeners.forEach { it.invoke(text) }
            }

            // Вспомогательная функция для извлечения msg_id из строки вида "18/04[5438]"
            private fun extractMsgId(text: String): String? {
                val start = text.indexOf('[')
                val end = text.indexOf(']')
                return if (start != -1 && end != -1 && end > start) {
                    text.substring(start + 1, end)
                } else null
            }

            private fun processIncomingMessage(message: String): String {
                // Ищем шаблон [<msg_id>] в сообщении
                val regex = "\\[(\\d+)\\]".toRegex()
                val matchResult = regex.find(message)
                if (matchResult != null) {
                    val msgId = matchResult.groupValues[1]
                    // Отправляем подтверждение для этого сообщения (ACK) – команда отправляется без токена
                    sendMessage("service:ack:$msgId")
                    // Удаляем часть с идентификатором из сообщения
                    return message.replace(regex, "").trim()
                }
                return message
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d("WebSocket", "Получено бинарное сообщение: $bytes")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("WebSocket", "Подключение закрывается: $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                Log.d("WebSocket", "Подключение закрыто: $reason")
                token = null
                userId = null
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                isConnected = false
                Log.e("WebSocket", "Ошибка подключения", t)
                token = null
                userId = null
            }
        })
    }

    fun sendMessage(message: String) {
        // Если сообщение начинается с "service:pong:" или "service:ack:", отправляем без токена
        if (message.startsWith("service:pong:") || message.startsWith("service:ack:")) {
            Log.d("WebSocket", "Отправка без токена: $message")
            webSocket.send(message)
        } else if (!message.startsWith("service:login:") && token != null) {
            val newMessage = "$token:$message"
            Log.d("WebSocket", "Отправка сообщения с токеном: $newMessage")
            webSocket.send(newMessage)
        } else {
            Log.d("WebSocket", "Отправка сообщения: $message")
            webSocket.send(message)
        }
    }

    fun disconnect() {
        if (isConnected) {
            webSocket.close(1000, "Закрыто пользователем")
            isConnected = false
        }
        token = null
        userId = null
    }

    fun addListener(listener: (String) -> Unit) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }
}
