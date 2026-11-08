package com.revgiler.messenger.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

data class Profile(
    val uid: String,
    val username: String,
    val name: String,
    val photo: String?,
    val status: String = "",
)

data class Chat(
    val id: String,
    val otherUid: String,
    val lastText: String,
    val lastAt: Long,
    val lastFromMe: Boolean,
    val unread: Boolean,
)

data class Message(
    val id: String,
    val fromMe: Boolean,
    val text: String,
    val at: Long,
    /** Ещё не дошло до сервера (нет сети). */
    val pending: Boolean,
    /** "text", "voice" или "call" (запись о звонке). */
    val type: String = "text",
    val mediaId: String? = null,
    val durationMs: Long = 0,
    val fileId: String? = null,
    val fileSize: Long = 0,
    val mime: String? = null,
)

class UsernameTaken : Exception("Это имя уже занято")

/** Всё общение с Firebase: вход, профили, чаты, сообщения. */
object Repo {
    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()

    val uid: String? get() = auth.currentUser?.uid

    private val profiles = ConcurrentHashMap<String, Profile>()

    // ---------------------------------------------------------------- вход

    suspend fun signInWithGoogle(idToken: String) {
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
    }

    suspend fun signOut() {
        runCatching { unregisterPush() }
        profiles.clear()
        auth.signOut()
    }

    // ---------------------------------------------------------------- push-токены

    /** Сохранить токен этого телефона, чтобы сервер мог присылать сюда уведомления. */
    suspend fun registerPush() {
        val token = FirebaseMessaging.getInstance().token.await()
        savePushToken(token)
    }

    fun savePushToken(token: String) {
        val me = uid ?: return
        db.collection("users").document(me)
            .update("fcmTokens", FieldValue.arrayUnion(token))
    }

    private suspend fun unregisterPush() {
        val me = uid ?: return
        val token = FirebaseMessaging.getInstance().token.await()
        db.collection("users").document(me).update("fcmTokens", FieldValue.arrayRemove(token)).await()
        FirebaseMessaging.getInstance().deleteToken().await()
    }

    // ---------------------------------------------------------------- профили

    /** Мой профиль или null, если имя ещё не выбрано. */
    suspend fun myProfile(): Profile? {
        val me = uid ?: return null
        val doc = db.collection("users").document(me).get().await()
        return doc.toProfile()?.also { profiles[me] = it }
    }

    suspend fun profile(uid: String): Profile? {
        profiles[uid]?.let { return it }
        val doc = db.collection("users").document(uid).get().await()
        return doc.toProfile()?.also { profiles[uid] = it }
    }

    /** Изменить поля своего профиля (фото, статус). */
    suspend fun updateMyProfile(fields: Map<String, Any?>) {
        val me = uid ?: error("Не выполнен вход")
        db.collection("users").document(me)
            .set(fields + ("updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge())
            .await()
        profiles.remove(me)
    }

    /** Свежие профили собеседников: могли сменить фото или статус. */
    suspend fun refreshProfiles(uids: List<String>): List<Profile> = uids.mapNotNull { id ->
        runCatching { db.collection("users").document(id).get().await().toProfile() }.getOrNull()
            ?.also { profiles[id] = it }
    }

    fun cachedProfile(uid: String): Profile? = profiles[uid]

    /**
     * Занять имя @username. Делается в транзакции: два человека не смогут взять одно имя.
     * Старое имя (если было) освобождается.
     */
    suspend fun claimUsername(raw: String, displayName: String) {
        val me = uid ?: error("Не выполнен вход")
        val name = normalize(raw)
        val user = auth.currentUser
        db.runTransaction { tr ->
            val nameRef = db.collection("usernames").document(name)
            val taken = tr.get(nameRef)
            if (taken.exists() && taken.getString("uid") != me) throw UsernameTaken()
            val userRef = db.collection("users").document(me)
            val current = tr.get(userRef)
            val old = current.getString("username")
            if (old != null && old != name) tr.delete(db.collection("usernames").document(old))
            if (!taken.exists()) tr.set(nameRef, mapOf("uid" to me))
            tr.set(
                userRef,
                mapOf(
                    "username" to name,
                    "name" to displayName.trim().ifBlank { name },
                    // своё фото и его удаление не сбрасываем при смене имени
                    "photo" to if (current.contains("photo")) current.getString("photo") else user?.photoUrl?.toString(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
                SetOptions.merge(),
            )
            true
        }.await()
        profiles.remove(me)
    }

    suspend fun findByUsername(raw: String): Profile? {
        val name = normalize(raw)
        if (!isValidUsername(name)) return null
        val doc = db.collection("usernames").document(name).get().await()
        val owner = doc.getString("uid") ?: return null
        return profile(owner)
    }

    fun normalize(raw: String) = raw.trim().removePrefix("@").lowercase()

    fun isValidUsername(name: String) = Regex("^[a-z0-9_]{3,20}$").matches(name)

    private fun DocumentSnapshot.toProfile(): Profile? {
        val username = getString("username") ?: return null
        return Profile(id, username, getString("name") ?: username, getString("photo"), getString("status").orEmpty())
    }

    // ---------------------------------------------------------------- чаты

    fun chatId(a: String, b: String) = listOf(a, b).sorted().joinToString("_")

    /** Создать чат, если его ещё нет (повторный вызов ничего не ломает). */
    suspend fun openChat(other: String): String {
        val me = uid ?: error("Не выполнен вход")
        val id = chatId(me, other)
        db.collection("chats").document(id)
            .set(mapOf("members" to listOf(me, other).sorted()), SetOptions.merge())
            .await()
        return id
    }

    /** Мои чаты, новые сверху. Сортируем на телефоне — так не нужен составной индекс в Firestore. */
    fun chats(): Flow<List<Chat>> = callbackFlow {
        val me = uid ?: run { close(); return@callbackFlow }
        val reg = db.collection("chats").whereArrayContains("members", me)
            .addSnapshotListener { snap, err ->
                if (err != null) { close(err); return@addSnapshotListener }
                val list = snap?.documents.orEmpty().mapNotNull { d ->
                    @Suppress("UNCHECKED_CAST")
                    val members = d.get("members") as? List<String> ?: return@mapNotNull null
                    val other = members.firstOrNull { it != me } ?: return@mapNotNull null
                    val lastAt = d.getTimestamp("lastAt")?.toDate()?.time ?: 0L
                    val readAt = d.getTimestamp("read.$me")?.toDate()?.time ?: 0L
                    val lastFrom = d.getString("lastFrom")
                    Chat(
                        id = d.id,
                        otherUid = other,
                        lastText = d.getString("lastText").orEmpty(),
                        lastAt = lastAt,
                        lastFromMe = lastFrom == me,
                        unread = lastFrom != null && lastFrom != me && readAt < lastAt,
                    )
                }.sortedByDescending { it.lastAt }
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    // ---------------------------------------------------------------- сообщения

    fun messages(chatId: String): Flow<List<Message>> = callbackFlow {
        val me = uid ?: run { close(); return@callbackFlow }
        val reg = db.collection("chats").document(chatId).collection("messages")
            .orderBy("at", Query.Direction.ASCENDING)
            .limitToLast(300)
            .addSnapshotListener { snap, err ->
                if (err != null) { close(err); return@addSnapshotListener }
                val list = snap?.documents.orEmpty().map { d ->
                    Message(
                        id = d.id,
                        fromMe = d.getString("from") == me,
                        text = d.getString("text").orEmpty(),
                        type = d.getString("type") ?: "text",
                        mediaId = d.getString("mediaId"),
                        durationMs = d.getLong("duration") ?: 0L,
                        fileId = d.getString("fileId"),
                        fileSize = d.getLong("size") ?: 0L,
                        mime = d.getString("mime"),
                        // пока сервер не поставил время — берём оценку, чтобы сообщение сразу встало на место
                        at = d.getTimestamp("at", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toDate()?.time
                            ?: System.currentTimeMillis(),
                        pending = d.metadata.hasPendingWrites(),
                    )
                }
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    /** Отправка. Работает и без сети: Firestore сохранит и дошлёт сам. */
    fun send(chatId: String, text: String) {
        val me = uid ?: return
        val clean = text.trim().take(4000)
        if (clean.isEmpty()) return
        val chatRef = db.collection("chats").document(chatId)
        db.batch()
            .set(
                chatRef.collection("messages").document(),
                mapOf("from" to me, "text" to clean, "at" to FieldValue.serverTimestamp()),
            )
            .set(
                chatRef,
                mapOf(
                    "lastText" to clean.take(200),
                    "lastFrom" to me,
                    "lastAt" to FieldValue.serverTimestamp(),
                    "read" to mapOf(me to FieldValue.serverTimestamp()),
                ),
                SetOptions.merge(),
            )
            .commit()
            .addOnSuccessListener { Push.notify(chatId) }
    }

    /**
     * Голосовое сообщение. Звук (AAC, ~24 кбит/с) хранится прямо в Firestore отдельным документом —
     * так не нужно платное хранилище файлов. Предел документа 1 МБ ≈ 5 минут записи.
     */
    fun sendVoice(chatId: String, audio: ByteArray, durationMs: Long) {
        val me = uid ?: return
        val chatRef = db.collection("chats").document(chatId)
        val media = chatRef.collection("media").document()
        db.batch()
            .set(media, mapOf("from" to me, "data" to Blob.fromBytes(audio), "mime" to "audio/mp4", "at" to FieldValue.serverTimestamp()))
            .set(
                chatRef.collection("messages").document(),
                mapOf(
                    "from" to me, "text" to "", "type" to "voice", "mediaId" to media.id,
                    "duration" to durationMs, "at" to FieldValue.serverTimestamp(),
                ),
            )
            .set(
                chatRef,
                mapOf(
                    "lastText" to "🎤 Голосовое сообщение",
                    "lastFrom" to me,
                    "lastAt" to FieldValue.serverTimestamp(),
                    "read" to mapOf(me to FieldValue.serverTimestamp()),
                ),
                SetOptions.merge(),
            )
            .commit()
            .addOnSuccessListener { Push.notify(chatId) }
    }

    /** Запись о файле, который уже загружен в хранилище. В text — имя файла. */
    fun sendFile(chatId: String, fileId: String, name: String, mime: String, size: Long, image: Boolean) {
        val me = uid ?: return
        val chatRef = db.collection("chats").document(chatId)
        db.batch()
            .set(
                chatRef.collection("messages").document(),
                mapOf(
                    "from" to me, "text" to name.take(300), "type" to if (image) "image" else "file",
                    "fileId" to fileId, "mime" to mime, "size" to size, "at" to FieldValue.serverTimestamp(),
                ),
            )
            .set(
                chatRef,
                mapOf(
                    "lastText" to if (image) "📷 Фото" else "📎 ${name.take(150)}",
                    "lastFrom" to me,
                    "lastAt" to FieldValue.serverTimestamp(),
                    "read" to mapOf(me to FieldValue.serverTimestamp()),
                ),
                SetOptions.merge(),
            )
            .commit()
            .addOnSuccessListener { Push.notify(chatId) }
    }

    suspend fun loadMedia(chatId: String, mediaId: String): ByteArray {
        val doc = db.collection("chats").document(chatId).collection("media").document(mediaId).get().await()
        return doc.getBlob("data")?.toBytes() ?: error("Файл не найден")
    }

    /** Запись о звонке в переписке («Исходящий звонок, 3 мин»). */
    fun logCall(chatId: String, video: Boolean, durationMs: Long, missed: Boolean) {
        val me = uid ?: return
        val text = when {
            missed -> if (video) "Видеозвонок без ответа" else "Звонок без ответа"
            else -> if (video) "Видеозвонок" else "Звонок"
        }
        val chatRef = db.collection("chats").document(chatId)
        db.batch()
            .set(
                chatRef.collection("messages").document(),
                mapOf("from" to me, "text" to text, "type" to "call", "duration" to durationMs, "at" to FieldValue.serverTimestamp()),
            )
            .set(
                chatRef,
                mapOf("lastText" to "📞 $text", "lastFrom" to me, "lastAt" to FieldValue.serverTimestamp()),
                SetOptions.merge(),
            )
            .commit()
    }

    fun markRead(chatId: String) {
        val me = uid ?: return
        db.collection("chats").document(chatId)
            .set(mapOf("read" to mapOf(me to FieldValue.serverTimestamp())), SetOptions.merge())
    }
}
