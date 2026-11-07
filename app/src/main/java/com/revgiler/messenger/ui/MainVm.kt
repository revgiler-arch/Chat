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
import com.revgiler.messenger.data.Message
import com.revgiler.messenger.data.Profile
import com.revgiler.messenger.data.Repo
import com.revgiler.messenger.data.UpdateInfo
import com.revgiler.messenger.data.Updater
import com.revgiler.messenger.data.UsernameTaken
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

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
    /** Обои: chatId == null — общие для всех чатов. */
    data class Wallpaper(val chatId: String?) : Screen
}

class MainVm(app: Application) : AndroidViewModel(app) {

    var auth by mutableStateOf<AuthState>(AuthState.Loading)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

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
    /** Чат из уведомления, нажатого до того, как загрузился профиль. */
    private var pendingChatId: String? = null

    var found by mutableStateOf<Profile?>(null)
        private set
    var searchStatus by mutableStateOf<String?>(null)
        private set

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
                val option = GetSignInWithGoogleOption.Builder(activity.getString(R.string.default_web_client_id)).build()
                val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                val result = CredentialManager.create(activity).getCredential(activity, request)
                val cred = result.credential
                if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                    Repo.signInWithGoogle(token)
                    refreshAuth()
                } else {
                    error = "Google вернул неожиданный ответ"
                }
            } catch (e: GetCredentialCancellationException) {
                // пользователь закрыл окно — ничего не делаем
            } catch (e: Exception) {
                error = "Не удалось войти: ${e.message}"
            } finally {
                busy = false
            }
        }
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
        chatsJob?.cancel()
        messagesJob?.cancel()
        callsJob?.cancel()
        viewModelScope.launch { Repo.signOut() }
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

    private fun startChats() {
        chatsJob?.cancel()
        chatsJob = viewModelScope.launch {
            Repo.chats()
                .catch { error = "Чаты не загрузились: ${it.message}" }
                .collect { list ->
                    chats = list
                    loadPeople(list.map { it.otherUid })
                }
        }
    }

    private fun loadPeople(uids: List<String>) {
        val missing = uids.filter { it !in people }
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
        val other = chatId.split("_").firstOrNull { it != me } ?: return
        viewModelScope.launch {
            val p = people[other] ?: runCatching { Repo.profile(other) }.getOrNull() ?: return@launch
            people = people + (other to p)
            while (stack.size > 1) back()
            open(Screen.Conversation(chatId, p))
        }
    }

    fun onResume() {
        (screen as? Screen.Conversation)?.let {
            App.visibleChatId = it.chatId
            Notifier.clearChat(getApplication(), it.chatId)
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
                    Repo.markRead(chatId)
                }
        }
    }

    fun send(chatId: String, text: String) = Repo.send(chatId, text)

    fun sendVoice(chatId: String, audio: ByteArray, durationMs: Long) = Repo.sendVoice(chatId, audio, durationMs)

    // ---------------------------------------------------------------- обновления

    fun checkUpdate(silent: Boolean) {
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
