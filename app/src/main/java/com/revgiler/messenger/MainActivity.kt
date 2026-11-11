package com.revgiler.messenger

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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

    private var callSetupDialog: AlertDialog? = null

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
        askCallSetup()
    }

    override fun onPause() {
        super.onPause()
        vm.onPause()
    }

    /** Разрешения, без которых звонок не разбудит заблокированный телефон. Каждое спрашиваем один раз. */
    private fun askCallSetup() {
        if (callSetupDialog?.isShowing == true) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val prefs = getSharedPreferences("call_setup", Context.MODE_PRIVATE)
        val pkg = Uri.parse("package:$packageName")
        val nm = getSystemService(NotificationManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        val (key, text, action) = when {
            Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent() && !prefs.getBoolean("fullscreen", false) ->
                Triple("fullscreen", "Разрешите полноэкранные уведомления, чтобы входящий звонок открывался на заблокированном экране.", Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
            // версия для Play не просит снять ограничение батареи: Play пускает такое только по особому согласованию,
            // а звонки и так будят телефон через высокоприоритетный push
            !BuildConfig.PLAY && !pm.isIgnoringBatteryOptimizations(packageName) && !prefs.getBoolean("battery", false) ->
                Triple("battery", "Снимите ограничение батареи, чтобы телефон в режиме сна не пропускал звонки.", Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            else -> return
        }
        prefs.edit().putBoolean(key, true).apply()
        callSetupDialog = AlertDialog.Builder(this)
            .setTitle("Настройка звонков")
            .setMessage(text)
            .setPositiveButton("Настроить") { _, _ ->
                runCatching { startActivity(Intent(action, pkg)) }
            }
            .setNegativeButton("Позже", null)
            .show()
    }
}
