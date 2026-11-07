package com.revgiler.messenger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.revgiler.messenger.ui.MainVm
import com.revgiler.messenger.ui.MessengerApp

class MainActivity : ComponentActivity() {
    private val vm: MainVm by viewModels()

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MessengerApp(vm, this) }
        handle(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Нажали на уведомление о сообщении — открываем этот чат. */
    private fun handle(intent: Intent?) {
        val chatId = intent?.getStringExtra(Notifier.EXTRA_CHAT_ID) ?: return
        intent.removeExtra(Notifier.EXTRA_CHAT_ID)
        vm.openChatById(chatId)
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }
}
