package com.revgiler.messenger

import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.revgiler.messenger.data.Repo
import java.util.concurrent.TimeUnit

/** Принимает push от нашего сервера и показывает уведомление. */
class PushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Repo.savePushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        val chatId = d["chatId"] ?: return
        val title = d["title"].orEmpty().ifBlank { "U chat" }
        when (d["kind"] ?: "message") {
            "call" -> {
                val callId = d["callId"] ?: return
                if (App.activeCallId == callId) return
                Notifier.showIncomingCall(this, callId, title, d["video"] == "1")
            }
            "cancel", "gcancel" -> d["callId"]?.let { Notifier.clearCall(this, it) }
            "invite" -> Notifier.showInvite(this, chatId, title, d["body"].orEmpty())
            "gcall" -> {
                val callId = d["callId"] ?: return
                if (App.activeCallId == callId) return
                val who = d["senderName"].orEmpty()
                Notifier.showIncomingCall(this, callId, if (who.isBlank()) title else "$title · $who", d["video"] == "1", groupChatId = chatId)
            }
            else -> {
                val group = d["group"] == "1"
                Notifier.showMessage(
                    this, chatId, title,
                    // в группе сервер присылает текст без имени — имя автора идёт отдельно
                    d["body"].orEmpty(),
                    sender = if (group) d["senderName"].orEmpty().ifBlank { "Участник" } else null,
                )
                // приложение может быть закрыто: ждём, пока отметка «доставлено» уйдёт на сервер
                runCatching { Repo.markDeliveredNow(chatId)?.let { Tasks.await(it, 8, TimeUnit.SECONDS) } }
            }
        }
    }
}
