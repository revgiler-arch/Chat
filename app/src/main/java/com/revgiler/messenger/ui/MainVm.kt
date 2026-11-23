package com.revgiler.messenger.ui

import android.app.Activity
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.revgiler.messenger.App
import com.revgiler.messenger.Notifier
import com.revgiler.messenger.R
import com.revgiler.messenger.call.CallActivity
import com.revgiler.messenger.data.CallSignal
import com.revgiler.messenger.data.Chat
import com.revgiler.messenger.data.Invite
import com.revgiler.messenger.data.Message
import com.revgiler.messenger.data.Profile
import com.revgiler.messenger.data.Repo
import com.revgiler.messenger.data.UpdateInfo
import com.revgiler.messenger.data.Updater
import com.revgiler.messenger.data.UsernameTaken
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data object NeedUsername : AuthState
    data class Ready(val me: Profile) : AuthState
}

sealed interface Screen {
    data object Chats : Screen
    data object NewChat : Screen
    data object Settings : Screen
    data class Conversation(val chatId: String, val other: Profile) : Screen
    data object NewGroup : Screen
    data class GroupInfo(val chatId: String) : Screen
    /** Обои: chatId == null — общие для всех чатов. */
    data class Wallpaper(val chatId: String?) : Screen
}

private const val TYPING_SEND_MS = 4_000L
private const val TYPING_SHOW_MS = 6_000L

class MainVm(app: Application) : AndroidViewModel(app) {

    var auth by mutableStateOf<AuthState>(AuthState.Loading)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    /** Ход загрузки файла, 0..1; null — ничего не загружается. */
    var uploadProgress by mutableStateOf<Float?>(null)

    val stack = mutableStateListOf<Screen>(Screen.Chats)
    val screen get() = stack.last()

    var chats by mutableStateOf<List<Chat>>(emptyList())
        private set
    /** Профили собеседников: подгружаются по мере появления чатов. */
    var people by mutableStateOf<Map<String, Profile>>(emptyMap())
        private set
    var messages by mutableStateOf<List<Message>>(emptyList())
        private set
    private var chatsJob: Job? = null
    private var messagesJob: Job? = null
    private var callsJob: Job? = null
    private var invitesJob: Job? = null

    /** Группа, открытая по ссылке: показываем «Вступить?». */
    var joinPreview by mutableStateOf<Repo.GroupPreview?>(null)
        private set
    private var pendingJoinId: String? = null

    /** Приглашения в группы, на которые я ещё не ответил. */
    var invites by mutableStateOf<List<Invite>>(emptyList())
        private set
    /** Чат из уведомления, нажатого до того, как загрузился профиль. */
    private var pendingChatId: String? = null

    var found by mutableStateOf<Profile?>(null)
        private set
    var searchStatus by mutableStateOf<String?>(null)
        private set

    /** Чаты, где собеседник сейчас печатает. */
    var typingChats by mutableStateOf<Set<String>>(emptySet())
        private set
    private val seenTyping = HashMap<String, Long>()
    private val typingJobs = HashMap<String, Job>()
    private var lastTypingSent = 0L

    /** Приложение на экране: только тогда сообщения считаются прочитанными. */
    private var resumed = false
    private val readMarked = HashMap<String, String>()

    var update by mutableStateOf<UpdateInfo?>(null)
        private set
    var updateStatus by mutableStateOf<String?>(null)
        private set

    init {
        refreshAuth()
        checkUpdate(silent = true)
    }

    // ---------------------------------------------------------------- вход

    private fun refreshAuth() {
        if (Repo.uid == null) {
            auth = AuthState.SignedOut
            return
        }
        auth = AuthState.Loading
        viewModelScope.launch {
            runCatching { Repo.myProfile() }
                .onSuccess { p ->
                    auth = if (p == null) AuthState.NeedUsername else AuthState.Ready(p)
                    if (p != null) {
                        startChats()
                        startIncomingCalls()
                        launch { runCatching { Repo.registerPush() } }
                        pendingChatId?.let { pendingChatId = null; openChatById(it) }
                        pendingJoinId?.let { pendingJoinId = null; openJoinLink(it) }
                    }
                }
                .onFailure {
                    error = "Не удалось загрузить профиль: ${it.message}"
                    auth = AuthState.NeedUsername
                }
        }
    }

    fun signIn(activity: Activity) {
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                Repo.signInWithGoogle(googleIdToken(activity))
                refreshAuth()
            } catch (e: GetCredentialCancellationException) {
                // пользователь закрыл окно — ничего не делаем
            } catch (e: Exception) {
                error = "Не удалось войти: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    /** Окно выбора Google-аккаунта; возвращает токен для Firebase. */
    private suspend fun googleIdToken(activity: Activity): String {
        val option = GetSignInWithGoogleOption.Builder(activity.getString(R.string.default_web_client_id)).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val cred = CredentialManager.create(activity).getCredential(activity, request).credential
        if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return GoogleIdTokenCredential.createFrom(cred.data).idToken
        }
        throw IllegalStateException("Google вернул неожиданный ответ")
    }

    fun claimUsername(username: String, name: String) {
        val clean = Repo.normalize(username)
        if (!Repo.isValidUsername(clean)) {
            error = "Имя: 3–20 символов, латинские буквы, цифры и _"
            return
        }
        busy = true
        error = null
        viewModelScope.launch {
            runCatching { Repo.claimUsername(clean, name) }
                .onSuccess {
                    refreshAuth()
                    if (stack.size > 1) back()
                }
                .onFailure { e ->
                    error = if (e is UsernameTaken || e.cause is UsernameTaken) "Имя @$clean уже занято" else "Ошибка: ${e.message}"
                }
            busy = false
        }
    }

    fun signOut() {
        stopListeners()
        viewModelScope.launch { Repo.signOut() }
        resetToSignedOut()
    }

    /** Удалить аккаунт и все его данные: сначала подтверждение через Google. */
    fun deleteAccount(activity: Activity) {
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                Repo.reauthenticate(googleIdToken(activity))
                stopListeners()
                Repo.deleteAccount()
                val ctx = getApplication<Application>()
                runCatching {
                    ctx.cacheDir.deleteRecursively()
                    java.io.File(ctx.filesDir, "voice").deleteRecursively()
                }
                resetToSignedOut()
            } catch (e: GetCredentialCancellationException) {
                // передумал на окне выбора аккаунта
            } catch (e: FirebaseAuthException) {
                error = if (e.errorCode == "ERROR_USER_MISMATCH") "Выберите тот же Google-аккаунт, с которым вошли" else "Не удалось подтвердить вход: ${e.message}"
                restartListeners()
            } catch (e: Exception) {
                error = "Аккаунт не удалён: ${e.message}. Попробуйте ещё раз"
                restartListeners()
            } finally {
                busy = false
            }
        }
    }

    private fun stopListeners() {
        invitesJob?.cancel()
        chatsJob?.cancel()
        messagesJob?.cancel()
        callsJob?.cancel()
    }

    private fun restartListeners() {
        if (Repo.uid == null || auth !is AuthState.Ready) return
        startChats()
        startIncomingCalls()
    }

    private fun resetToSignedOut() {
        typingJobs.values.forEach { it.cancel() }
        typingJobs.clear()
        seenTyping.clear()
        typingChats = emptySet()
        readMarked.clear()
        chats = emptyList()
        people = emptyMap()
        stack.clear()
        stack.add(Screen.Chats)
        auth = AuthState.SignedOut
    }

    // ---------------------------------------------------------------- навигация

    fun open(s: Screen) {
        if (s is Screen.NewChat) {
            found = null
            searchStatus = null
        }
        stack.add(s)
        if (s is Screen.Conversation) {
            startMessages(s.chatId)
            App.visibleChatId = s.chatId
            Notifier.clearChat(getApplication(), s.chatId)
        }
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        val left = stack.removeAt(stack.lastIndex)
        if (left is Screen.Conversation) {
            messagesJob?.cancel()
            messages = emptyList()
            Repo.markRead(left.chatId)
            App.visibleChatId = null
            com.revgiler.messenger.voice.VoicePlayer.stop()
        }
        (screen as? Screen.Conversation)?.let { App.visibleChatId = it.chatId }
        return true
    }

    // ---------------------------------------------------------------- чаты

    private fun startInvites() {
        invitesJob?.cancel()
        invitesJob = viewModelScope.launch {
            Repo.invites().catch { }.collect { list ->
                invites = list
                loadPeople(list.mapNotNull { it.fromUid })
            }
        }
    }

    private fun startChats() {
        startInvites()
        chatsJob?.cancel()
        chatsJob = viewModelScope.launch {
            Repo.chats()
                .catch { error = "Чаты не загрузились: ${it.message}" }
                .collect { list ->
                    chats = list
                    loadPeople(list.flatMap { if (it.isGroup) it.members + it.invited else listOf(it.otherUid) })
                    updateTyping(list)
                    ringGroupCalls(list)
                    // чат обновился на этом телефоне — значит, сообщение дошло
                    list.filter { !it.lastFromMe && it.lastAt > it.myDeliveredAt }
                        .forEach { Repo.markDelivered(it.id, it.lastAt) }
                }
        }
    }

    private val seenGroupCalls = HashSet<String>()
    private var groupCallsPrimed = false

    /** В группе начали звонок, пока приложение открыто, — показываем входящий (push может прийти позже). */
    private fun ringGroupCalls(list: List<Chat>) {
        val me = Repo.uid ?: return
        val now = System.currentTimeMillis()
        list.filter { it.isGroup && it.callId != null }.forEach { c ->
            val id = c.callId!!
            if (!seenGroupCalls.add(id) || !groupCallsPrimed) return@forEach
            if (c.callBy == me || App.activeCallId == id || App.activeCallId != null) return@forEach
            if (kotlin.math.abs(now - c.callAt) > 50_000) return@forEach
            val ctx = getApplication<Application>()
            runCatching {
                ctx.startActivity(com.revgiler.messenger.call.GroupCallActivity.incoming(ctx, c.id, id, c.name, c.callVideo))
            }.onFailure { Notifier.showIncomingCall(ctx, id, c.name, c.callVideo, groupChatId = c.id) }
        }
        // первый снимок после запуска — только запоминаем, не звоним
        groupCallsPrimed = true
    }

    fun joinGroupCall(chat: Chat) {
        val id = chat.callId ?: return
        val ctx = getApplication<Application>()
        ctx.startActivity(
            com.revgiler.messenger.call.GroupCallActivity.join(ctx, chat.id, id, chat.name, chat.callVideo)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun updateTyping(list: List<Chat>) {
        val now = System.currentTimeMillis()
        list.forEach { c ->
            val previous = seenTyping.put(c.id, c.otherTypingAt)
            when {
                // сообщение пришло — значит, уже не печатает
                !c.lastFromMe && c.lastAt >= c.otherTypingAt -> stopTyping(c.id)
                // первый снимок после запуска содержит старые отметки — их не показываем
                previous != null && c.otherTypingAt > previous && kotlin.math.abs(now - c.otherTypingAt) < 60_000 -> startTyping(c.id)
            }
        }
    }

    private fun startTyping(chatId: String) {
        typingChats = typingChats + chatId
        typingJobs.remove(chatId)?.cancel()
        typingJobs[chatId] = viewModelScope.launch {
            delay(TYPING_SHOW_MS)
            typingChats = typingChats - chatId
        }
    }

    private fun stopTyping(chatId: String) {
        typingJobs.remove(chatId)?.cancel()
        if (chatId in typingChats) typingChats = typingChats - chatId
    }

    /** Вызывается при вводе текста. В базу пишем не чаще раза в 4 секунды и только при наличии сети. */
    fun onTyping(chatId: String) {
        val now = System.currentTimeMillis()
        if (now - lastTypingSent < TYPING_SEND_MS || !online()) return
        lastTypingSent = now
        Repo.setTyping(chatId)
    }

    private fun online(): Boolean =
        getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java)?.activeNetwork != null

    private fun loadPeople(uids: List<String>) {
        val missing = uids.filter { it.isNotEmpty() && it !in people }.distinct()
        if (missing.isEmpty()) return
        viewModelScope.launch {
            val loaded = missing.mapNotNull { runCatching { Repo.profile(it) }.getOrNull() }
            people = people + loaded.associateBy { it.uid }
        }
    }

    fun search(username: String) {
        val clean = Repo.normalize(username)
        if (!Repo.isValidUsername(clean)) {
            searchStatus = "Введите имя, например @ivan_99"
            return
        }
        searchStatus = "Ищу…"
        found = null
        viewModelScope.launch {
            runCatching { Repo.findByUsername(clean) }
                .onSuccess { p ->
                    found = p?.takeIf { it.uid != Repo.uid }
                    searchStatus = when {
                        p == null -> "Пользователь @$clean не найден"
                        p.uid == Repo.uid -> "Это вы :)"
                        else -> null
                    }
                }
                .onFailure { searchStatus = "Ошибка: ${it.message}" }
        }
    }

    fun startChat(other: Profile) {
        viewModelScope.launch {
            runCatching { Repo.openChat(other.uid) }
                .onSuccess { id ->
                    people = people + (other.uid to other)
                    if (screen is Screen.NewChat) stack.removeAt(stack.lastIndex)
                    open(Screen.Conversation(id, other))
                }
                .onFailure { error = "Не удалось открыть чат: ${it.message}" }
        }
    }

    /** Открыть чат по id (из уведомления). */
    fun openChatById(chatId: String) {
        val me = Repo.uid ?: return
        if (auth !is AuthState.Ready) { pendingChatId = chatId; return }
        if ((screen as? Screen.Conversation)?.chatId == chatId) return
        if (chatId.startsWith("g_")) {
            viewModelScope.launch {
                val name = chats.firstOrNull { it.id == chatId }?.name ?: Repo.groupName(chatId) ?: return@launch
                while (stack.size > 1) back()
                open(Screen.Conversation(chatId, groupProfile(chatId, name)))
            }
            return
        }
        val other = chatId.split("_").firstOrNull { it != me } ?: return
        viewModelScope.launch {
            val p = people[other] ?: runCatching { Repo.profile(other) }.getOrNull() ?: return@launch
            people = people + (other to p)
            while (stack.size > 1) back()
            open(Screen.Conversation(chatId, p))
        }
    }

    private var lastUpdateCheck = 0L
    private var lastPeopleRefresh = 0L

    private fun refreshPeople() {
        val now = System.currentTimeMillis()
        if (people.isEmpty() || now - lastPeopleRefresh < 120_000L) return
        lastPeopleRefresh = now
        viewModelScope.launch {
            val fresh = Repo.refreshProfiles(people.keys.toList())
            people = people + fresh.associateBy { it.uid }
        }
    }

    private fun saveProfile(fields: Map<String, Any?>) {
        viewModelScope.launch {
            runCatching {
                withTimeout(15_000) { Repo.updateMyProfile(fields) }
                Repo.myProfile()
            }.onSuccess { p ->
                if (p != null && auth is AuthState.Ready) auth = AuthState.Ready(p)
            }.onFailure { error = "Профиль не сохранён: проверьте интернет" }
        }
    }

    fun setStatus(text: String) = saveProfile(mapOf("status" to text.trim().take(70)))

    fun removeAvatar() = saveProfile(mapOf("photo" to null))

    fun setAvatar(uri: android.net.Uri) {
        viewModelScope.launch {
            runCatching { com.revgiler.messenger.data.ChatFiles.avatar(getApplication<Application>(), uri) }
                .onSuccess { saveProfile(mapOf("photo" to it)) }
                .onFailure { error = "Фото не подошло: ${it.message}" }
        }
    }

    fun onPause() {
        resumed = false
        App.visibleChatId = null
    }

    /** Отметить чат прочитанным, если он реально на экране и есть новое входящее. */
    private fun markReadIfVisible(chatId: String) {
        if (!resumed || (screen as? Screen.Conversation)?.chatId != chatId) return
        val last = messages.lastOrNull { !it.fromMe } ?: return
        if (readMarked.put(chatId, last.id) != last.id) Repo.markRead(chatId)
    }

    fun onResume() {
        resumed = true
        refreshPeople()
        if (System.currentTimeMillis() - lastUpdateCheck > 30 * 60_000L) checkUpdate(silent = true)
        (screen as? Screen.Conversation)?.let {
            App.visibleChatId = it.chatId
            Notifier.clearChat(getApplication(), it.chatId)
            markReadIfVisible(it.chatId)
        }
    }

    /**
     * Входящие звонки, пока приложение открыто: так звонки работают,
     * даже если сервер уведомлений ещё не настроен.
     */
    private fun startIncomingCalls() {
        callsJob?.cancel()
        callsJob = viewModelScope.launch {
            CallSignal.incoming().catch { }.collect { call ->
                if (System.currentTimeMillis() - call.createdAt > 50_000) return@collect // старый звонок
                if (App.activeCallId == call.id) return@collect
                val name = runCatching { Repo.profile(call.from)?.name }.getOrNull() ?: "Звонок"
                val ctx = getApplication<Application>()
                runCatching { ctx.startActivity(CallActivity.incoming(ctx, call.id, name, call.video)) }
                    .onFailure { Notifier.showIncomingCall(ctx, call.id, name, call.video) }
            }
        }
    }

    fun openChat(chat: Chat) {
        if (chat.isGroup) {
            open(Screen.Conversation(chat.id, groupProfile(chat.id, chat.name)))
            return
        }
        val other = people[chat.otherUid] ?: return
        open(Screen.Conversation(chat.id, other))
    }

    // ---------------------------------------------------------------- сообщения

    private fun startMessages(chatId: String) {
        messagesJob?.cancel()
        messages = emptyList()
        messagesJob = viewModelScope.launch {
            Repo.messages(chatId)
                .catch { error = "Сообщения не загрузились: ${it.message}" }
                .collect {
                    messages = it
                    markReadIfVisible(chatId)
                }
        }
    }

    fun send(chatId: String, text: String) {
        lastTypingSent = 0
        Repo.send(chatId, text)
    }

    fun setBlocked(chatId: String, block: Boolean, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching { withTimeout(15_000) { Repo.setBlocked(chatId, block) } }
                .onSuccess { onDone() }
                .onFailure { error = "Не получилось: проверьте интернет" }
        }
    }

    /** Жалоба на собеседника; к ней прикладываем его последние сообщения. */
    fun report(chatId: String, other: Profile, reason: String, comment: String, onDone: () -> Unit) {
        val evidence = messages.filter { it.fromUid == other.uid }
        viewModelScope.launch {
            runCatching { withTimeout(15_000) { Repo.report(chatId, other.uid, reason, comment, evidence) } }
                .onSuccess { onDone() }
                .onFailure { error = "Жалоба не отправлена: проверьте интернет" }
        }
    }

    // ---------------------------------------------------------------- группы

    /** Найти человека по @username (для приглашения в группу). */
    fun lookup(username: String, onResult: (Profile?, String?) -> Unit) {
        val clean = Repo.normalize(username)
        if (!Repo.isValidUsername(clean)) { onResult(null, "Введите имя, например @ivan_99"); return }
        viewModelScope.launch {
            runCatching { Repo.findByUsername(clean) }
                .onSuccess { p ->
                    when {
                        p == null -> onResult(null, "Пользователь @$clean не найден")
                        p.uid == Repo.uid -> onResult(null, "Это вы :)")
                        else -> {
                            people = people + (p.uid to p)
                            onResult(p, null)
                        }
                    }
                }
                .onFailure { onResult(null, "Ошибка: ${it.message}") }
        }
    }

    fun createGroup(name: String, invitees: List<Profile>) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            runCatching { withTimeout(15_000) { Repo.createGroup(name, invitees.map { it.uid }) } }
                .onSuccess { id ->
                    while (screen is Screen.NewGroup || screen is Screen.NewChat) stack.removeAt(stack.lastIndex)
                    open(Screen.Conversation(id, groupProfile(id, name.trim().ifBlank { "Группа" })))
                }
                .onFailure { error = "Группа не создана: ${it.message}" }
            busy = false
        }
    }

    private fun groupAction(what: String, action: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { withTimeout(15_000) { action() } }
                .onFailure { error = "$what: проверьте интернет" }
        }
    }

    fun acceptInvite(inv: Invite) = groupAction("Не удалось вступить") {
        Repo.acceptInvite(inv.chatId)
        Notifier.clearInvite(getApplication(), inv.chatId)
    }

    fun declineInvite(inv: Invite) = groupAction("Не удалось отклонить") {
        Repo.declineInvite(inv.chatId)
        Notifier.clearInvite(getApplication(), inv.chatId)
    }

    /** Открыли ссылку на группу. */
    fun openJoinLink(chatId: String) {
        if (auth !is AuthState.Ready) { pendingJoinId = chatId; return }
        viewModelScope.launch {
            val p = Repo.groupPreview(chatId)
            when {
                p == null -> error = "Группа закрыта или удалена — вступить можно только по приглашению"
                p.member -> openChatById(chatId)
                else -> joinPreview = p
            }
        }
    }

    fun dismissJoin() { joinPreview = null }

    fun confirmJoin() {
        val p = joinPreview ?: return
        joinPreview = null
        groupAction("Не удалось вступить") {
            if (p.invited) Repo.acceptInvite(p.id) else Repo.joinOpenGroup(p.id)
            while (stack.size > 1) back()
            open(Screen.Conversation(p.id, groupProfile(p.id, p.name)))
        }
    }

    fun setGroupOpen(chatId: String, open: Boolean) = groupAction("Настройка не сохранена") { Repo.setGroupSettings(chatId, open = open) }
    fun setMembersInvite(chatId: String, on: Boolean) = groupAction("Настройка не сохранена") { Repo.setGroupSettings(chatId, membersInvite = on) }
    fun setAdmin(chatId: String, uid: String, admin: Boolean) = groupAction("Не получилось") { Repo.setAdmin(chatId, uid, admin) }

    fun inviteToGroup(chatId: String, p: Profile) = groupAction("Приглашение не отправлено") { Repo.inviteToGroup(chatId, listOf(p.uid)) }
    fun cancelInvite(chatId: String, uid: String) = groupAction("Не удалось отменить") { Repo.cancelInvite(chatId, uid) }
    fun removeMember(chatId: String, uid: String) = groupAction("Не удалось удалить") { Repo.removeMember(chatId, uid) }
    fun renameGroup(chatId: String, name: String) = groupAction("Не удалось переименовать") { Repo.renameGroup(chatId, name) }

    fun leaveGroup(chat: Chat) {
        groupAction("Не удалось выйти") {
            Repo.leaveGroup(chat)
            while (stack.size > 1) back()
        }
    }

    fun sendVoice(chatId: String, audio: ByteArray, durationMs: Long) = Repo.sendVoice(chatId, audio, durationMs)

    fun sendFile(chatId: String, uri: android.net.Uri) {
        if (uploadProgress != null) return
        uploadProgress = 0f
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            try {
                val prepared = com.revgiler.messenger.data.ChatFiles.prepare(ctx, uri)
                val fileId = com.revgiler.messenger.data.ChatFiles.newId()
                try {
                    com.revgiler.messenger.data.ChatFiles.upload(chatId, fileId, prepared) { p ->
                        viewModelScope.launch { uploadProgress = p }
                    }
                    val size = prepared.file.length()
                    Repo.sendFile(chatId, fileId, prepared.name, prepared.mime, size, prepared.image)
                    com.revgiler.messenger.data.ChatFiles.keepLocal(ctx, fileId, prepared.name, prepared.file)
                } finally {
                    prepared.file.delete()
                }
            } catch (e: Exception) {
                error = "Файл не отправлен: ${e.message}"
            } finally {
                uploadProgress = null
            }
        }
    }

    // ---------------------------------------------------------------- обновления

    fun checkUpdate(silent: Boolean) {
        if (com.revgiler.messenger.BuildConfig.PLAY) return // в версии для Play обновляет сам Play
        lastUpdateCheck = System.currentTimeMillis()
        viewModelScope.launch {
            if (!silent) updateStatus = "Проверяю…"
            runCatching { Updater.check(getApplication<Application>()) }
                .onSuccess {
                    update = it
                    if (!silent) updateStatus = if (it == null) "Установлена последняя версия" else null
                }
                .onFailure { if (!silent) updateStatus = "Не удалось проверить: ${it.message}" }
        }
    }

    fun installUpdate() {
        if (com.revgiler.messenger.BuildConfig.PLAY) return
        val info = update ?: return
        val ctx = getApplication<Application>()
        if (!Updater.canInstall(ctx)) {
            Updater.openInstallPermission(ctx)
            return
        }
        viewModelScope.launch {
            runCatching { Updater.download(ctx, info) { p -> updateStatus = "Скачиваю… ${(p * 100).toInt()}%" } }
                .onSuccess {
                    updateStatus = null
                    Updater.install(ctx, it)
                }
                .onFailure { updateStatus = "Ошибка загрузки: ${it.message}" }
        }
    }
}

/** Группа в шапке чата показывается как «профиль»: имя группы, без @username. */
fun groupProfile(chatId: String, name: String) = Profile(uid = chatId, username = "", name = name, photo = null)
