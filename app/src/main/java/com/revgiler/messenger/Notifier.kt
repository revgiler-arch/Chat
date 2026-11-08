package com.revgiler.messenger

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.revgiler.messenger.call.CallActivity

/** Уведомления: новые сообщения и входящие звонки. */
object Notifier {
    private const val CH_MESSAGES = "messages"
    private const val CH_CALLS = "calls"
    const val EXTRA_CHAT_ID = "chatId"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_MESSAGES, "Сообщения", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Новые сообщения в чатах"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CALLS, "Звонки", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Входящие звонки"
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 600, 800, 600, 800)
            },
        )
    }

    private fun messageId(chatId: String) = chatId.hashCode()
    private fun callNotifId(callId: String) = 100_000 + (callId.hashCode() and 0xFFFF)

    fun showMessage(ctx: Context, chatId: String, title: String, body: String) {
        if (App.visibleChatId == chatId) return // этот чат и так открыт
        ensureChannels(ctx)
        val open = PendingIntent.getActivity(
            ctx, messageId(chatId),
            Intent(ctx, MainActivity::class.java)
                .putExtra(EXTRA_CHAT_ID, chatId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        notify(ctx, messageId(chatId), n)
    }

    fun clearChat(ctx: Context, chatId: String) {
        NotificationManagerCompat.from(ctx).cancel(messageId(chatId))
    }

    fun showIncomingCall(ctx: Context, callId: String, title: String, video: Boolean) {
        ensureChannels(ctx)
        fun intent(action: String?) = Intent(ctx, CallActivity::class.java)
            .putExtra(CallActivity.EXTRA_CALL_ID, callId)
            .putExtra(CallActivity.EXTRA_INCOMING, true)
            .putExtra(CallActivity.EXTRA_VIDEO, video)
            .putExtra(CallActivity.EXTRA_TITLE, title)
            .apply { if (action != null) putExtra(CallActivity.EXTRA_ACTION, action) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val id = callNotifId(callId)
        val full = PendingIntent.getActivity(ctx, id, intent(null), flags)
        val accept = PendingIntent.getActivity(ctx, id + 1, intent(CallActivity.ACTION_ACCEPT), flags)
        val decline = PendingIntent.getActivity(ctx, id + 2, intent(CallActivity.ACTION_DECLINE), flags)
        val n = NotificationCompat.Builder(ctx, CH_CALLS)
            .setSmallIcon(R.drawable.ic_stat_chat)
            .setContentTitle(title)
            .setContentText(if (video) "Входящий видеозвонок" else "Входящий звонок")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(0, "Отклонить", decline)
            .addAction(0, "Ответить", accept)
            .build()
        notify(ctx, id, n)
        wakeScreen(ctx)
    }

    /** Включает экран, даже если система не разрешила полноэкранный показ уведомления. */
    @Suppress("DEPRECATION")
    private fun wakeScreen(ctx: Context) {
        runCatching {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) {
                pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "chat:incoming",
                ).acquire(15_000)
            }
        }
    }

    fun clearCall(ctx: Context, callId: String) {
        NotificationManagerCompat.from(ctx).cancel(callNotifId(callId))
    }

    @Suppress("MissingPermission")
    private fun notify(ctx: Context, id: Int, n: android.app.Notification) {
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }
}
