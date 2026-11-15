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
import androidx.core.app.Person
import androidx.core.app.RemoteInput
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

    // Последние сообщения каждого чата — уведомление показывает переписку, а не только одну строку
    private class Line(val from: String?, val text: String, val at: Long)
    private val history = mutableMapOf<String, ArrayDeque<Line>>()
    private val titles = mutableMapOf<String, String>()

    fun showMessage(ctx: Context, chatId: String, title: String, body: String) {
        if (App.visibleChatId == chatId) return // этот чат и так открыт
        synchronized(history) {
            titles[chatId] = title
            history.getOrPut(chatId) { ArrayDeque() }.apply {
                addLast(Line(title, body, System.currentTimeMillis()))
                while (size > 8) removeFirst()
            }
        }
        post(ctx, chatId, silent = false)
    }

    /** Ответ ушёл из шторки — дописываем его в уведомление (иначе Android крутит «отправка…»). */
    fun addMyReply(ctx: Context, chatId: String, text: String, title: String?) {
        synchronized(history) {
            if (title != null) titles.putIfAbsent(chatId, title)
            history.getOrPut(chatId) { ArrayDeque() }.apply {
                addLast(Line(null, text, System.currentTimeMillis()))
                while (size > 8) removeFirst()
            }
        }
        post(ctx, chatId, silent = true)
    }

    private fun post(ctx: Context, chatId: String, silent: Boolean) {
        ensureChannels(ctx)
        val id = messageId(chatId)
        val lines = synchronized(history) { history[chatId]?.toList().orEmpty() }
        val title = synchronized(history) { titles[chatId] } ?: "U chat"
        val open = PendingIntent.getActivity(
            ctx, id,
            Intent(ctx, MainActivity::class.java)
                .putExtra(EXTRA_CHAT_ID, chatId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val me = Person.Builder().setName("Вы").build()
        val other = Person.Builder().setName(title).setKey(chatId).build()
        val style = NotificationCompat.MessagingStyle(me)
        lines.forEach { style.addMessage(it.text, it.at, if (it.from == null) null else other) }

        // «Ответить» — поле ввода прямо в шторке
        val replyIntent = PendingIntent.getBroadcast(
            ctx, id + 1,
            Intent(ctx, ReplyReceiver::class.java).setAction(ReplyReceiver.ACTION_REPLY)
                .putExtra(EXTRA_CHAT_ID, chatId).putExtra(ReplyReceiver.EXTRA_TITLE, title),
            // RemoteInput дописывает текст в intent, поэтому он должен быть изменяемым
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
        )
        val reply = NotificationCompat.Action.Builder(R.drawable.ic_stat_chat, "Ответить", replyIntent)
            .addRemoteInput(RemoteInput.Builder(ReplyReceiver.KEY_TEXT).setLabel("Сообщение").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAllowGeneratedReplies(true)
            .build()
        val readIntent = PendingIntent.getBroadcast(
            ctx, id + 2,
            Intent(ctx, ReplyReceiver::class.java).setAction(ReplyReceiver.ACTION_READ).putExtra(EXTRA_CHAT_ID, chatId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val read = NotificationCompat.Action.Builder(R.drawable.ic_stat_chat, "Прочитано", readIntent)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_chat)
            .setContentTitle(title)
            .setContentText(lines.lastOrNull()?.text.orEmpty())
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(silent)
            .setSilent(silent)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(reply)
            .addAction(read)
            .build()
        notify(ctx, id, n)
    }

    fun clearChat(ctx: Context, chatId: String) {
        synchronized(history) { history.remove(chatId) }
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
