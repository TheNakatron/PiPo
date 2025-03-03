package com.example.msg

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log

class MyApplication : Application() {
    lateinit var webSocketManager: WebSocketManager
    // Глобальное хранилище непрочитанных сообщений: ключ – friendId, значение – число новых сообщений
    val unreadCounts: MutableMap<String, Int> = mutableMapOf()
    // Глобальная переменная для текущего открытого чата (friendId)
    var activeChatFriendId: String? = null

    private var activityReferences = 0
    private var isActivityChangingConfigurations = false

    override fun onCreate() {
        super.onCreate()
        webSocketManager = WebSocketManager(applicationContext)
        webSocketManager.connect()
        Log.d("MyApplication", "WebSocketManager initialized and connected")

        NotificationHelper.createNotificationChannel(this)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                // Если это первая активность, значит приложение вышло на передний план
                if (++activityReferences == 1 && !isActivityChangingConfigurations) {
                    Log.d("MyApplication", "Приложение на переднем плане, восстанавливаем соединение")
                    webSocketManager.connect()
                }
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                isActivityChangingConfigurations = activity.isChangingConfigurations
                if (--activityReferences == 0 && !isActivityChangingConfigurations) {
                    Log.d("MyApplication", "Приложение свернуто, отключаем соединение")
                    webSocketManager.disconnect()
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == TRIM_MEMORY_UI_HIDDEN) {
            Log.d("MyApplication", "Приложение ушло в фон, отключаем WebSocket")
            webSocketManager.disconnect()
        }
    }
}
