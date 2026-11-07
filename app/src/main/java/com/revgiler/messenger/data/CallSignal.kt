package com.revgiler.messenger.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** Состояние служебного документа звонка calls/{id}. */
data class CallDoc(
    val id: String,
    val from: String,
    val to: String,
    val chatId: String,
    val video: Boolean,
    val status: String, // ringing | accepted | declined | ended | missed
    val offerSdp: String?,
    val answerSdp: String?,
    val createdAt: Long,
)

data class Candidate(val sdpMid: String?, val sdpMLineIndex: Int, val sdp: String)

/**
 * Установка соединения для звонка (сигнализация WebRTC) через Firestore:
 * звонящий кладёт «предложение» (offer), отвечающий — «ответ» (answer),
 * а сетевые адреса (ICE-кандидаты) каждый пишет в свою подколлекцию.
 */
object CallSignal {
    private val db get() = FirebaseFirestore.getInstance()
    private fun ref(id: String) = db.collection("calls").document(id)

    const val CALLER = "callerCandidates"
    const val CALLEE = "calleeCandidates"

    fun newCallId(): String = db.collection("calls").document().id

    suspend fun create(id: String, to: String, chatId: String, video: Boolean, offerSdp: String) {
        val me = Repo.uid ?: error("Не выполнен вход")
        ref(id).set(
            mapOf(
                "from" to me, "to" to to, "chatId" to chatId, "video" to video,
                "status" to "ringing",
                "offer" to mapOf("type" to "offer", "sdp" to offerSdp),
                "createdAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
    }

    suspend fun get(id: String): CallDoc? = parse(ref(id).get().await())

    suspend fun answer(id: String, answerSdp: String) {
        ref(id).update(mapOf("answer" to mapOf("type" to "answer", "sdp" to answerSdp), "status" to "accepted")).await()
    }

    fun setStatus(id: String, status: String) {
        ref(id).update("status", status)
    }

    fun watch(id: String): Flow<CallDoc> = callbackFlow {
        val reg = ref(id).addSnapshotListener { snap, err ->
            if (err != null) { close(err); return@addSnapshotListener }
            parse(snap)?.let { trySend(it) }
        }
        awaitClose { reg.remove() }
    }

    fun addCandidate(id: String, side: String, c: Candidate) {
        ref(id).collection(side).add(
            mapOf("sdpMid" to c.sdpMid, "sdpMLineIndex" to c.sdpMLineIndex, "candidate" to c.sdp),
        )
    }

    /** Новые кандидаты собеседника по мере появления. */
    fun candidates(id: String, side: String, onEach: (Candidate) -> Unit): ListenerRegistration =
        ref(id).collection(side).addSnapshotListener { snap, _ ->
            snap?.documentChanges?.forEach { ch ->
                if (ch.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                    val d = ch.document
                    val sdp = d.getString("candidate") ?: return@forEach
                    onEach(Candidate(d.getString("sdpMid"), (d.getLong("sdpMLineIndex") ?: 0L).toInt(), sdp))
                }
            }
        }

    /** Входящие звонки, пока приложение открыто (работает и без настроенного сервера уведомлений). */
    fun incoming(): Flow<CallDoc> = callbackFlow {
        val me = Repo.uid ?: run { close(); return@callbackFlow }
        val reg = db.collection("calls").whereEqualTo("to", me).whereEqualTo("status", "ringing")
            .addSnapshotListener { snap, _ ->
                snap?.documentChanges?.forEach { ch ->
                    if (ch.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        parse(ch.document)?.let { trySend(it) }
                    }
                }
            }
        awaitClose { reg.remove() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(d: com.google.firebase.firestore.DocumentSnapshot?): CallDoc? {
        if (d == null || !d.exists()) return null
        val offer = d.get("offer") as? Map<String, Any?>
        val answer = d.get("answer") as? Map<String, Any?>
        return CallDoc(
            id = d.id,
            from = d.getString("from") ?: return null,
            to = d.getString("to") ?: return null,
            chatId = d.getString("chatId").orEmpty(),
            video = d.getBoolean("video") == true,
            status = d.getString("status") ?: "ringing",
            offerSdp = offer?.get("sdp") as? String,
            answerSdp = answer?.get("sdp") as? String,
            createdAt = d.getTimestamp("createdAt")?.toDate()?.time ?: System.currentTimeMillis(),
        )
    }
}
