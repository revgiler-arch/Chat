package com.revgiler.messenger.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

/** Документ группового звонка groupCalls/{id}. */
data class GroupCallDoc(
    val id: String,
    val chatId: String,
    val from: String,
    val video: Boolean,
    val participants: List<String>,
    val ended: Boolean,
)

/** Связь двух участников: offer — от того, чей uid меньше; gen — «поколение», чтобы отличать переподключения. */
data class LinkDoc(val id: String, val from: String, val to: String, val gen: String, val offer: String?, val answer: String?)

/**
 * Сигнализация группового звонка через Firestore. Каждый участник соединён с каждым (mesh):
 *  groupCalls/{callId}                     — кто сейчас в звонке
 *  groupCalls/{callId}/links/{a}_{b}       — предложение/ответ между a и b (a < b)
 *  groupCalls/{callId}/links/{a}_{b}/ice   — сетевые кандидаты обеих сторон
 */
object GroupCallSignal {
    private val db get() = FirebaseFirestore.getInstance()
    private fun call(id: String) = db.collection("groupCalls").document(id)
    private fun link(callId: String, linkId: String) = call(callId).collection("links").document(linkId)

    const val MAX_PARTICIPANTS = 8

    fun linkId(a: String, b: String) = if (a < b) "${a}_$b" else "${b}_$a"

    /** Начать звонок в группе: я — первый участник; в чате появляется отметка «идёт звонок». */
    suspend fun start(chatId: String, video: Boolean): String {
        val me = Repo.uid ?: error("Не выполнен вход")
        val ref = db.collection("groupCalls").document()
        ref.set(
            mapOf(
                "chatId" to chatId, "from" to me, "video" to video,
                "participants" to listOf(me), "ended" to false,
                "createdAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
        db.collection("chats").document(chatId).update(
            "call", mapOf("id" to ref.id, "video" to video, "by" to me, "at" to FieldValue.serverTimestamp()),
        ).await()
        return ref.id
    }

    suspend fun get(callId: String): GroupCallDoc? = parse(call(callId).get().await())

    /** Звонок, который сейчас идёт в группе (если есть): чтобы не начинать второй, а присоединиться. */
    suspend fun active(chatId: String): GroupCallDoc? {
        val id = runCatching { db.collection("chats").document(chatId).get().await().getString("call.id") }.getOrNull() ?: return null
        val doc = runCatching { get(id) }.getOrNull() ?: return null
        return doc.takeIf { !it.ended && it.participants.isNotEmpty() }
    }

    suspend fun join(callId: String) {
        val me = Repo.uid ?: return
        call(callId).update("participants", FieldValue.arrayUnion(me)).await()
    }

    /** Выйти; последний вышедший закрывает звонок и убирает отметку в чате. */
    suspend fun leave(callId: String, chatId: String) {
        val me = Repo.uid ?: return
        val ref = call(callId)
        ref.update("participants", FieldValue.arrayRemove(me)).await()
        val left = runCatching { parse(ref.get().await()) }.getOrNull()
        if (left == null || left.participants.isEmpty()) end(callId, chatId)
    }

    suspend fun end(callId: String, chatId: String) {
        runCatching { call(callId).update("ended", true).await() }
        runCatching {
            val chat = db.collection("chats").document(chatId).get().await()
            if (chat.getString("call.id") == callId) {
                db.collection("chats").document(chatId).update("call", FieldValue.delete()).await()
            }
        }
    }

    fun watch(callId: String, onChange: (GroupCallDoc?) -> Unit): ListenerRegistration =
        call(callId).addSnapshotListener { snap, _ -> onChange(snap?.let { parse(it) }) }

    // ---------------------------------------------------------------- связи пар

    suspend fun offer(callId: String, to: String, gen: String, sdp: String) {
        val me = Repo.uid ?: return
        link(callId, linkId(me, to)).set(
            mapOf("from" to me, "to" to to, "gen" to gen, "offer" to sdp, "answer" to null, "at" to FieldValue.serverTimestamp()),
        ).await()
    }

    suspend fun answer(callId: String, linkId: String, gen: String, sdp: String) {
        link(callId, linkId).set(mapOf("answer" to sdp, "answerGen" to gen), SetOptions.merge()).await()
    }

    /** Все связи, где я участвую (и как предлагающий, и как отвечающий). */
    fun links(callId: String, onLink: (LinkDoc) -> Unit): List<ListenerRegistration> {
        val me = Repo.uid ?: return emptyList()
        fun listen(field: String) = call(callId).collection("links").whereEqualTo(field, me)
            .addSnapshotListener { snap, _ ->
                snap?.documentChanges.orEmpty().forEach { ch ->
                    val d = ch.document
                    val gen = d.getString("gen") ?: return@forEach
                    // ответ относится к текущему поколению, только если answerGen совпадает
                    val answer = d.getString("answer")?.takeIf { d.getString("answerGen") == gen }
                    onLink(LinkDoc(d.id, d.getString("from").orEmpty(), d.getString("to").orEmpty(), gen, d.getString("offer"), answer))
                }
            }
        return listOf(listen("to"), listen("from"))
    }

    fun addCandidate(callId: String, linkId: String, gen: String, c: Candidate) {
        val me = Repo.uid ?: return
        link(callId, linkId).collection("ice").add(
            mapOf("from" to me, "gen" to gen, "sdpMid" to c.sdpMid, "sdpMLineIndex" to c.sdpMLineIndex, "candidate" to c.sdp),
        )
    }

    fun candidates(callId: String, linkId: String, from: String, gen: String, onCandidate: (Candidate) -> Unit): ListenerRegistration =
        link(callId, linkId).collection("ice").whereEqualTo("from", from).whereEqualTo("gen", gen)
            .addSnapshotListener { snap, _ ->
                snap?.documentChanges.orEmpty().forEach { ch ->
                    if (ch.type != com.google.firebase.firestore.DocumentChange.Type.ADDED) return@forEach
                    val d = ch.document
                    val sdp = d.getString("candidate") ?: return@forEach
                    onCandidate(Candidate(d.getString("sdpMid"), (d.getLong("sdpMLineIndex") ?: 0L).toInt(), sdp))
                }
            }

    private fun parse(d: com.google.firebase.firestore.DocumentSnapshot): GroupCallDoc? {
        if (!d.exists()) return null
        return GroupCallDoc(
            id = d.id,
            chatId = d.getString("chatId").orEmpty(),
            from = d.getString("from").orEmpty(),
            video = d.getBoolean("video") == true,
            participants = (d.get("participants") as? List<*>)?.filterIsInstance<String>().orEmpty(),
            ended = d.getBoolean("ended") == true,
        )
    }
}
