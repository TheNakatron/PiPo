package com.example.msg

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.security.MessageDigest

class LoginActivity : AppCompatActivity() {

    private lateinit var webSocketManager: WebSocketManager
    private var currentHashedPassword: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Проверяем, есть ли сохранённый хеш пароля (автологин)
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val savedPasswordHash = prefs.getString("saved_password", null)
        if (savedPasswordHash != null) {
            // Если данные уже сохранены, сразу переходим в MenuActivity
            startActivity(Intent(this, MenuActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_login)

        // Передаем applicationContext, чтобы использовать его в WebSocketManager
        webSocketManager = WebSocketManager(applicationContext)
        webSocketManager.connect()
        // Регистрируем слушатель, чтобы получать ответы от сервера
        webSocketManager.addListener { message ->
            runOnUiThread { handleServerResponse(message) }
        }

        val passwordEditText = findViewById<EditText>(R.id.passwordEditText)
        val loginButton = findViewById<Button>(R.id.loginButton)

        // Получаем уникальный идентификатор устройства
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        Log.d("LoginActivity", "Android ID: $androidId")

        loginButton.setOnClickListener {
            val password = passwordEditText.text.toString()
            if (password.isEmpty()) {
                Toast.makeText(this, "Введите пароль", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            currentHashedPassword = hashPassword(password)
            // Формируем команду логина
            val loginCommand = "service:login:$androidId:$currentHashedPassword"
            webSocketManager.sendMessage(loginCommand)
        }
    }

    private fun handleServerResponse(message: String) {
        Log.d("LoginActivity", "Ответ сервера: $message")
        when {
            message.startsWith("service:login:successful:") -> {
                // Ожидаемый формат: service:login:successful:<user_id>:<token>
                val parts = message.split(":")
                if (parts.size >= 5) {
                    val userId = parts[3]
                    val token = parts[4]
                    // Сохраняем хешированный пароль, user_id и token для автологина
                    val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                    currentHashedPassword?.let { hashed ->
                        prefs.edit().putString("saved_password", hashed).apply()
                    }
                    prefs.edit().putString("user_id", userId).apply()
                    // Если нужно, можно сохранить token тоже (либо WebSocketManager уже его установил)
                    // Переход в MenuActivity
                    val intent = Intent(this, MenuActivity::class.java)
                    startActivity(intent)
                    finish()
                } else {
                    Toast.makeText(this, "Некорректный формат ответа от сервера", Toast.LENGTH_SHORT).show()
                }
            }
            message.startsWith("service:login:failed") -> {
                Toast.makeText(this, "Неверный пароль", Toast.LENGTH_SHORT).show()
            }
            else -> {
                Log.d("LoginActivity", "Неизвестное сообщение от сервера")
            }
        }
    }

    private fun hashPassword(password: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(password.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        // При необходимости можно удалить слушатель из WebSocketManager
    }
}
