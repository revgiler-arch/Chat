package com.revgiler.messenger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.google.android.gms.tasks.Tasks
import com.revgiler.messenger.data.Push
import com.revgiler.messenger.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Кнопки в уведомлении о сообщении: «Ответить» (поле ввода в шторке) и «Прочитано».
 * Работает и когда приложение закрыто: ждём, пока сообщение и push уйдут, и только потом отпускаем процесс.
 */
class ReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val chatId = intent.getStringExtra(Notifier.EXTRA_CHAT_ID) ?: return
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)
                            ?.getCharSequence(KEY_TEXT)?.toString()?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            Notifier.addMyReply(ctx, chatId, text, intent.getStringExtra(EXTRA_TITLE))
                            runCatching { Repo.send(chatId, text, push = false)?.let { Tasks.await(it, 7, TimeUnit.SECONDS) } }
                            runCatching { Repo.markRead(chatId) }
                            Push.notifyNow(chatId)
                        }
                    }
                    ACTION_READ -> {
                        Notifier.clearChat(ctx, chatId)
                        runCatching { Repo.markRead(chatId)?.let { Tasks.await(it, 7, TimeUnit.SECONDS) } }
                    }
                }
            } finally {
                done.finish()
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "com.revgiler.messenger.REPLY"
        const val ACTION_READ = "com.revgiler.messenger.MARK_READ"
        const val KEY_TEXT = "text"
        const val EXTRA_TITLE = "title"
    }
}
