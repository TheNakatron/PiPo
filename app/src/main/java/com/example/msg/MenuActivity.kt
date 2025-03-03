package com.example.msg

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.messaging.FirebaseMessaging

class MenuActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_NOTIFICATION = 1001
    }

    private lateinit var friendsRecyclerView: RecyclerView
    private lateinit var friendAdapter: FriendAdapter
    private lateinit var webSocketManager: WebSocketManager
    private lateinit var headerTextView: TextView
    private lateinit var addFriendButton: FloatingActionButton
    private lateinit var userId: String

    // Список друзей (единственный источник данных, переданный в адаптер)
    private val friendList = mutableListOf<String>()

    // Локальный listener для входящих сообщений от WebSocket
    private val menuListener: (String) -> Unit = { message ->
        runOnUiThread { handleServerMessage(message) }
    }

    private val unreadResetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val fid = intent?.getStringExtra("friend_id")
            if (fid != null) {
                (application as MyApplication).unreadCounts.remove(fid)
                updateAllFriendNotifications()
                Log.d("MenuActivity", "Счетчик для $fid сброшен через broadcast")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)

        handleIncomingIntent(intent)

        headerTextView = findViewById(R.id.myIdTextView)
        addFriendButton = findViewById(R.id.addFriendButton)
        addFriendButton.imageTintList = null

        friendsRecyclerView = findViewById(R.id.friendsRecyclerView)
        friendsRecyclerView.layoutManager = LinearLayoutManager(this)
        // Создаём адаптер с единым friendList – все изменения будут через него
        friendAdapter = FriendAdapter(friendList)
        friendsRecyclerView.adapter = friendAdapter

        // Загружаем userId из SharedPreferences
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        userId = prefs.getString("user_id", "Unknown") ?: "Unknown"
        headerTextView.text = "User: $userId"

        // Загружаем список друзей, сохранённый в SharedPreferences
        val savedFriends = prefs.getStringSet("friends_set", emptySet())
        // Избегаем дублирования: если savedFriends уже содержится, вызываем метод адаптера,
        // который добавит их в friendList.
        savedFriends?.forEach { friendAdapter.addFriend(it) }

        webSocketManager = (application as MyApplication).webSocketManager
        webSocketManager.addListener(menuListener)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_CODE_NOTIFICATION
                )
            } else {
                sendFcmTokenToServer()
            }
        } else {
            sendFcmTokenToServer()
        }

        LocalBroadcastManager.getInstance(this)
            .registerReceiver(unreadResetReceiver, IntentFilter("unread_reset"))

        addFriendButton.setOnClickListener { showAddFriendDialog() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        intent?.getStringExtra("friend_id")?.let { friendId ->
            Log.d("MenuActivity", "handleIncomingIntent: received friend_id = $friendId")
            val chatIntent = Intent(this, ChatActivity::class.java).apply {
                putExtra("friend_id", friendId)
            }
            startActivity(chatIntent)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_NOTIFICATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                sendFcmTokenToServer()
            } else {
                Log.d("MenuActivity", "Разрешение на уведомления отклонено")
            }
        }
    }

    private fun sendFcmTokenToServer() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w("MenuActivity", "Ошибка получения FCM токена", task.exception)
                return@addOnCompleteListener
            }
            val fcmToken = task.result ?: ""
            webSocketManager.sendMessage("service:fcm:[$fcmToken]")
        }
    }

    override fun onResume() {
        super.onResume()
        webSocketManager.connect()
        Log.d("MenuActivity", "onResume вызван, listener будет добавлен")
        webSocketManager.addListener(menuListener)
        (application as MyApplication).activeChatFriendId = null
        if (webSocketManager.token != null) {
            webSocketManager.sendMessage("service:pong:ready")
        } else {
            val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
            prefs.getString("saved_password", null)?.let { savedPasswordHash ->
                val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                val loginCommand = "service:login:$androidId:$savedPasswordHash"
                webSocketManager.sendMessage(loginCommand)
            }
        }
        updateAllFriendNotifications()
    }

    override fun onPause() {
        webSocketManager.removeListener(menuListener)
        super.onPause()
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(unreadResetReceiver)
        super.onDestroy()
    }

    private fun handleServerMessage(message: String) {
        Log.d("MenuActivity", "Сообщение от сервера: $message")

        // Если сервер сообщает, что команда не распознана – повторно авторизуемся
        if (message.contains("service:not_authenticated")) {
            webSocketManager.token = null
            val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
            prefs.getString("saved_password", null)?.let { savedPasswordHash ->
                val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                val loginCommand = "service:login:$androidId:$savedPasswordHash"
                webSocketManager.sendMessage(loginCommand)
            }
            return
        }

        if (message.startsWith("service:friend:") && message.contains(":delete_you")) {
            // Ожидаемый формат: service:friend:<friendId>:delete_you[<msg_id>]
            val pattern = "service:friend:(.*?):delete_you\\[(\\d+)\\]".toRegex()
            val matchResult = pattern.find(message)
            if (matchResult != null) {
                val fid = matchResult.groupValues[1].trim()  // friendId
                val msgId = matchResult.groupValues[2].trim()  // msg_id
                // Отправляем подтверждение получения
                webSocketManager.sendMessage("service:ack:$msgId")
                // Удаляем друга и очищаем историю чата
                removeFriend(fid)
                AlertDialog.Builder(this)
                    .setTitle("Удаление из друзей")
                    .setMessage("Пользователь $fid удалил вас из друзей. История чата очищена.")
                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                    .show()
            }
            return
        }

        // Обработка входящих сообщений от друзей (уведомления)
        if (message.startsWith("message:") && message.contains(":receive:")) {
            val parts = message.split(":")
            if (parts.size >= 5) {
                val fid = parts[1]
                val app = application as MyApplication
                // Если открыт чат для этого друга, не обновляем уведомления
                if (app.activeChatFriendId == fid) {
                    Log.d("MenuActivity", "Чат с $fid открыт – уведомления не обновляются")
                    return
                }
                val count = app.unreadCounts.getOrDefault(fid, 0) + 1
                app.unreadCounts[fid] = count
                updateFriendNotification(fid, true)
            }
            return
        }
        // Остальная логика обработки команд (например, запросы дружбы, удаления и т.д.)
        when {
            message.startsWith("service:friend:accepted:") -> {
                // Ожидаемый формат: service:friend:accepted:<friendId>[<msg_id>]
                val pattern = "service:friend:accepted:(.*)\\[(\\d+)\\]".toRegex()
                val matchResult = pattern.find(message)
                if (matchResult != null) {
                    val fid = matchResult.groupValues[1].trim()  // Например, "3112"
                    val msgId = matchResult.groupValues[2].trim()  // Например, "6408"
                    webSocketManager.sendMessage("service:ack:$msgId")
                    if (fid.isNotEmpty()) {
                        addFriend(fid)
                        Toast.makeText(this, "Запрос принят. Добавлен друг: $fid", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val fid = message.substringAfter("service:friend:accepted:").substringBefore("[").trim()
                    if (fid.isNotEmpty()) {
                        addFriend(fid)
                        Toast.makeText(this, "Запрос принят. Добавлен друг: $fid", Toast.LENGTH_SHORT).show()
                    }
                }
                return
            }
            message.startsWith("service:friend:delete_complete:") -> {
                val fid = message.substringAfter("service:friend:delete_complete:").trim()
                if (fid.isNotEmpty()) {
                    removeFriend(fid)
                    Toast.makeText(this, "Друг $fid удалён", Toast.LENGTH_SHORT).show()
                }
            }
            message.startsWith("service:friend:") && message.endsWith(":delete_you") -> {
                val fid = message.substringAfter("service:friend:").substringBefore(":delete_you").trim()
                if (fid.isNotEmpty()) {
                    removeFriend(fid)
                    AlertDialog.Builder(this)
                        .setTitle("Удаление из друзей")
                        .setMessage("Пользователь $fid удалил вас из друзей.")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
            }
            message.startsWith("service:friend:rejected:") -> {
                Toast.makeText(this, "Запрос дружбы отклонён", Toast.LENGTH_SHORT).show()
            }
            message.startsWith("service:friend:add_user:") -> {
                // Ожидаемый формат: service:friend:add_user:<friendId>[<msg_id>]
                val pattern = "service:friend:add_user:(.*)\\[(\\d+)\\]".toRegex()
                val matchResult = pattern.find(message)
                if (matchResult != null) {
                    val senderId = matchResult.groupValues[1].trim() // friendId
                    val msgId = matchResult.groupValues[2].trim()     // msg_id
                    // Отправляем подтверждение
                    webSocketManager.sendMessage("service:ack:$msgId")
                    // Вызываем диалог запроса дружбы с отправителем (без квадратных скобок)
                    showFriendRequestDialog(senderId)
                } else {
                    // Если формат не соответствует, обрабатываем как раньше:
                    val senderId = message.substringAfter("service:friend:add_user:").trim()
                    showFriendRequestDialog(senderId)
                }
                return
            }
            else -> {
                Log.d("MenuActivity", "Неизвестное сообщение: $message")
            }
        }
    }

    // Добавление друга через адаптер
    private fun addFriend(fid: String) {
        if (friendList.contains(fid)) return
        friendAdapter.addFriend(fid)
        // SharedPreferences обновляются на основе friendList (который обновляется внутри адаптера, если он инициализирован ссылкой на него)
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        prefs.edit().putStringSet("friends_set", friendList.toSet()).apply()
    }

    private fun showFriendRequestDialog(senderId: String) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Запрос дружбы")
        builder.setMessage("Принять запрос дружбы от пользователя $senderId?")
        builder.setPositiveButton("Принять") { dialog, _ ->
            addFriend(senderId)
            // Отправляем серверу подтверждение принятия заявки
            webSocketManager.sendMessage("service:friend:accept:$senderId")
            // При необходимости сбрасываем уведомления
            (application as MyApplication).unreadCounts.remove(senderId)
            updateFriendNotification(senderId, false)
            Toast.makeText(this, "Друг добавлен: $senderId", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        builder.setNegativeButton("Отклонить") { dialog, _ ->
            // Отправляем серверу команду отклонения заявки
            webSocketManager.sendMessage("service:friend:reject:$senderId")
            Toast.makeText(this, "Запрос дружбы отклонён", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        builder.show()
    }

    private fun updateAllFriendNotifications() {
        val app = application as MyApplication
        friendList.forEach { fid ->
            val count = app.unreadCounts.getOrDefault(fid, 0)
            if (count > 0) {
                updateFriendNotification(fid, true)
            } else {
                updateFriendNotification(fid, false)
            }
        }
    }

    // Обновление уведомлений – реализуйте по необходимости
    private fun updateFriendNotification(fid: String, isUnread: Boolean) {
        val index = friendList.indexOf(fid)
        if (index != -1) {
            // Уведомляем адаптер, что элемент изменился.
            friendAdapter.notifyItemChanged(index)
        }
    }

    // Диалог добавления нового друга
    private fun showAddFriendDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Добавить друга")
        val input = EditText(this)
        input.hint = "Введите ID пользователя"
        builder.setView(input)
        builder.setPositiveButton("Отправить") { dialog, _ ->
            val fid = input.text.toString().trim()
            if (fid.isNotEmpty()) {
                // Отправляем запрос дружбы, но не добавляем друга локально
                webSocketManager.sendMessage("service:friend:$fid")
            } else {
                Toast.makeText(this, "Введите ID", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
        builder.setNegativeButton("Отмена") { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    // Функция для показа диалога опций для друга (при долгом нажатии)
    fun showFriendOptionsDialog(friendId: String) {
        val options = arrayOf("Удалить друга", "Очистить историю", "Выключить уведомления")
        AlertDialog.Builder(this)
            .setTitle("Опции для друга $friendId")
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> { // Удалить друга
                        removeFriend(friendId)
                        Toast.makeText(this, "Друг $friendId удалён", Toast.LENGTH_SHORT).show()
                    }
                    1 -> { // Очистить историю
                        clearChatHistory(friendId)
                        Toast.makeText(this, "История с $friendId очищена", Toast.LENGTH_SHORT).show()
                    }
                    2 -> { // Выключить уведомления
                        disableNotificationsForFriend(friendId)
                        Toast.makeText(this, "Уведомления для $friendId выключены", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun removeFriend(fid: String) {
        friendAdapter.removeFriend(fid)
        // Обновляем SharedPreferences
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        prefs.edit().putStringSet("friends_set", friendList.toSet()).apply()
        // Удаляем уведомления для этого друга
        (application as MyApplication).unreadCounts.remove(fid)
    }


    private fun clearChatHistory(friendId: String) {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        prefs.edit().remove("chat_history_$friendId").apply()
    }

    private fun disableNotificationsForFriend(friendId: String) {
        (application as MyApplication).unreadCounts.remove(friendId)
        updateFriendNotification(friendId, false)
    }
}
