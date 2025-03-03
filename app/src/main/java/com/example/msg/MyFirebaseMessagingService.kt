package com.example.msg

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import android.util.Log

class MyFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        // Логируем весь data-пейлоад для отладки
        Log.d("MyFirebaseMessagingService", "onMessageReceived data: ${remoteMessage.data}")

        // Извлекаем необходимые поля из data
        val data = remoteMessage.data
        val friendId = data["friend_id"] ?: "Unknown"
        val messageText = data["message"] ?: "Нет сообщения"

        Log.d("MyFirebaseMessagingService", "Extracted friend_id: $friendId, message: $messageText")

        // Вызываем собственный метод для показа уведомления, который сформирует Intent с нужными данными
        NotificationHelper.showNotification(applicationContext, friendId, messageText)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("MyFirebaseMessagingService", "New FCM token: $token")
        // При необходимости отправьте новый токен на сервер
    }
}

