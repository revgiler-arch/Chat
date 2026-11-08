package com.revgiler.messenger

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        com.revgiler.messenger.ui.C.load(this)
        instance = this
        Notifier.ensureChannels(this)
    }

    fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull() ?: "?"

    companion object {
        lateinit var instance: App
            private set

        /** Открытый сейчас чат: для него не показываем уведомления. */
        @Volatile
        var visibleChatId: String? = null

        /** Идущий звонок (чтобы не показывать о нём уведомление повторно). */
        @Volatile
        var activeCallId: String? = null
    }
}
