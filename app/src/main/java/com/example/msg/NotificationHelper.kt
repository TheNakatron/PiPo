package com.example.msg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

object NotificationHelper {
    private const val CHANNEL_ID = "messenger_channel"
    private const val CHANNEL_NAME = "Messenger Notifications"
    private const val CHANNEL_DESCRIPTION = "Notifications for Messenger"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = CHANNEL_DESCRIPTION
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d("NotificationHelper", "Notification channel created: $CHANNEL_ID")
        } else {
            Log.d("NotificationHelper", "No need to create notification channel (API < 26)")
        }
    }

    fun showNotification(context: Context, friendId: String, messageText: String, notificationId: Int = 0) {
        Log.d("NotificationHelper", "Preparing to show notification for friendId: $friendId, message: $messageText")
        // Создаем Intent для открытия MenuActivity, а затем переходим в чат
        val intent = Intent(context, MenuActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("friend_id", friendId)
            Log.d("NotificationHelper", "Intent created for MenuActivity with friend_id extra: $friendId")
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        Log.d("NotificationHelper", "PendingIntent created for MenuActivity. FriendId extra included: ${intent.getStringExtra("friend_id")}")

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_background)
            .setContentTitle("Новое сообщение от $friendId")
            .setContentText(messageText)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, builder.build())
        Log.d("NotificationHelper", "Notification shown with ID: $notificationId. Click will open MenuActivity with friendId: ${intent.getStringExtra("friend_id")}")
    }
}


