package com.revgiler.messenger.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.revgiler.messenger.call.CallActivity
import com.revgiler.messenger.voice.VoicePlayer
import com.revgiler.messenger.voice.VoiceRecorder
import kotlinx.coroutines.delay
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.revgiler.messenger.App
import com.revgiler.messenger.data.Chat
import com.revgiler.messenger.data.Message
import com.revgiler.messenger.data.Profile
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun MessengerApp(vm: MainVm, activity: Activity) {
    AppTheme {
        BackHandler(enabled = vm.stack.size > 1) { vm.back() }
        Box(Modifier.fillMaxSize().background(C.Bg).safeDrawingPadding()) {
            when (val a = vm.auth) {
                AuthState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = C.Accent)
                }
                AuthState.SignedOut -> LoginScreen(vm, activity)
                AuthState.NeedUsername -> UsernameScreen(vm, initialName = "", firstTime = true)
                is AuthState.Ready -> when (val s = vm.screen) {
                    Screen.Chats -> ChatsScreen(vm, a.me)
                    Screen.NewChat -> NewChatScreen(vm)
                    Screen.Settings -> SettingsScreen(vm, a.me)
                    is Screen.Conversation -> ConversationScreen(vm, s.chatId, s.other)
                    is Screen.Wallpaper -> WallpaperScreen(s.chatId) { vm.back() }
                }
            }
            vm.error?.let { ErrorBar(it) { vm.error = null } }
        }
    }
}

@Composable
private fun ErrorBar(text: String, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(12.dp), Alignment.BottomCenter) {
        Row(
            Modifier.fillMaxWidth().background(C.Bad, RoundedCornerShape(12.dp)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, color = Color.White, modifier = Modifier.weight(1f))
            Text("OK", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onClose).padding(8.dp))
        }
    }
}

// ---------------------------------------------------------------- вход

@Composable
private fun LoginScreen(vm: MainVm, activity: Activity) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(84.dp).background(C.Accent, CircleShape), Alignment.Center) {
            Text("💬", fontSize = 38.sp)
        }
        Spacer(Modifier.height(20.dp))
        Text("Чат", color = C.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Только сообщения и звонки. Ничего лишнего.", color = C.TextDim, fontSize = 15.sp)
        Spacer(Modifier.height(36.dp))
        Button(
            onClick = { vm.signIn(activity) },
            enabled = !vm.busy,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1F1F1F)),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (vm.busy) {
                CircularProgressIndicator(Modifier.size(22.dp), color = C.Accent, strokeWidth = 2.dp)
            } else {
                Text("G  ", fontWeight = FontWeight.Black, color = Color(0xFF4285F4), fontSize = 18.sp)
                Text("Войти через Google", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun UsernameScreen(vm: MainVm, initialName: String, firstTime: Boolean, current: Profile? = null) {
    var username by rememberSaveable { mutableStateOf(current?.username ?: "") }
    var name by rememberSaveable { mutableStateOf(current?.name ?: initialName) }
    Column(Modifier.fillMaxSize().padding(24.dp).imePadding()) {
        if (!firstTime) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = C.Text) }
        } else {
            Spacer(Modifier.height(40.dp))
        }
        Text(if (firstTime) "Как вас называть?" else "Профиль", color = C.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "По имени пользователя вас найдут друзья. Почту никто не увидит.",
            color = C.TextDim, fontSize = 15.sp,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(40) },
            label = { Text("Имя") }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }.take(20) },
            label = { Text("Имя пользователя") },
            prefix = { Text("@", color = C.TextDim) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.claimUsername(username, name) }),
            supportingText = { Text("3–20 символов: латинские буквы, цифры и _", color = C.TextDim) },
            colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { vm.claimUsername(username, name) },
            enabled = !vm.busy && username.length >= 3,
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            if (vm.busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
            else Text(if (firstTime) "Продолжить" else "Сохранить", fontSize = 16.sp)
        }
    }
}

// ---------------------------------------------------------------- список чатов

@Composable
private fun ChatsScreen(vm: MainVm, me: Profile) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Чаты", color = C.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.open(Screen.Settings) }) { Icon(Icons.Filled.Settings, "Настройки", tint = C.TextDim) }
            }
            vm.update?.let {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
                        .background(C.Surface, RoundedCornerShape(12.dp)).clickable { vm.installUpdate() }.padding(12.dp),
                ) {
                    Text(vm.updateStatus ?: "Доступна новая версия — нажмите, чтобы обновить", color = C.Text)
                }
            }
            if (vm.chats.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Пока нет чатов", color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Нажмите «+» и найдите друга по имени пользователя.\nВаше имя: @${me.username}",
                        color = C.TextDim, fontSize = 15.sp,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
                    items(vm.chats, key = { it.id }) { chat -> ChatRow(chat, vm.people[chat.otherUid]) { vm.openChat(chat) } }
                }
            }
        }
        FloatingActionButton(
            onClick = { vm.open(Screen.NewChat) },
            containerColor = C.Accent,
            contentColor = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) { Icon(Icons.Filled.Add, "Новый чат") }
    }
}

@Composable
private fun ChatRow(chat: Chat, other: Profile?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = other != null, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(other, 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    other?.name ?: "…", color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (chat.lastAt > 0) Text(shortTime(chat.lastAt), color = if (chat.unread) C.Accent else C.TextDim, fontSize = 12.sp)
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (chat.lastFromMe) "Вы: " else "") + chat.lastText.ifBlank { "Нет сообщений" },
                    color = C.TextDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (chat.unread) Box(Modifier.padding(start = 8.dp).size(10.dp).background(C.Accent, CircleShape))
            }
        }
    }
}

@Composable
private fun Avatar(p: Profile?, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(C.SurfaceHi), Alignment.Center) {
        Text(
            (p?.name ?: "?").take(1).uppercase(),
            color = C.Text, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.4f).sp,
        )
        if (p?.photo != null) {
            AsyncImage(p.photo, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

// ---------------------------------------------------------------- новый чат

@Composable
private fun NewChatScreen(vm: MainVm) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = C.Text) }
            Text("Новый чат", color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' || c == '@' }.take(21) },
            placeholder = { Text("@имя_пользователя") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = C.TextDim) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
            colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = { vm.search(query) }, modifier = Modifier.fillMaxWidth()) { Text("Найти") }
        Spacer(Modifier.height(16.dp))
        vm.searchStatus?.let { Text(it, color = C.TextDim) }
        vm.found?.let { p ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Surface)
                    .clickable { vm.startChat(p) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(p, 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("@${p.username}", color = C.TextDim, fontSize = 14.sp)
                }
                Text("Написать", color = C.Accent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ---------------------------------------------------------------- переписка

@Composable
private fun ConversationScreen(vm: MainVm, chatId: String, other: Profile) {
    val ctx = LocalContext.current
    var text by rememberSaveable(chatId) { mutableStateOf("") }
    val listState = rememberLazyListState()
    val msgs = vm.messages
    // обои перечитываем при каждом возвращении на экран (могли поменять в настройках)
    val wallpaper = remember(chatId, vm.stack.size) { Wallpapers.get(ctx, chatId) }
    var menu by remember { mutableStateOf(false) }
    val recorder = remember { VoiceRecorder(ctx) }
    var recording by remember { mutableStateOf(false) }
    var recordMs by remember { mutableLongStateOf(0L) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) recording = recorder.start()
    }
    fun startRecording() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            VoicePlayer.stop()
            recording = recorder.start()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    fun finishRecording(send: Boolean) {
        if (send) recorder.stop()?.let { (bytes, dur) -> vm.sendVoice(chatId, bytes, dur) } else recorder.cancel()
        recording = false
        recordMs = 0
    }
    LaunchedEffect(recording) {
        while (recording) {
            recordMs = recorder.elapsedMs
            if (recordMs >= VoiceRecorder.MAX_MS) { finishRecording(true); break }
            delay(200)
        }
    }
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }
    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.lastIndex)
    }
    fun call(video: Boolean) {
        ctx.startActivity(CallActivity.outgoing(ctx, other.uid, chatId, other.name, video))
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().background(C.Surface).padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = C.Text) }
            Avatar(other, 40.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(other.name, color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("@${other.username}", color = C.TextDim, fontSize = 13.sp, maxLines = 1)
            }
            IconButton(onClick = { call(false) }) { Icon(Icons.Filled.Call, "Звонок", tint = C.Text) }
            IconButton(onClick = { call(true) }) { Icon(Icons.Filled.Videocam, "Видеозвонок", tint = C.Text) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Ещё", tint = C.Text) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Обои чата") }, onClick = {
                        menu = false
                        vm.open(Screen.Wallpaper(chatId))
                    })
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            WallpaperBackground(wallpaper, Modifier.fillMaxSize())
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(msgs.size, key = { msgs[it].id }) { i ->
                    val m = msgs[i]
                    val prev = msgs.getOrNull(i - 1)
                    Column(Modifier.fillMaxWidth()) {
                        if (prev == null || !sameDay(prev.at, m.at)) DayLabel(m.at)
                        Bubble(m, chatId)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(C.Surface).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (recording) {
                IconButton(onClick = { finishRecording(false) }) { Icon(Icons.Filled.Delete, "Отменить", tint = C.Bad) }
                Box(Modifier.size(10.dp).background(C.Bad, CircleShape))
                Text(
                    "  Запись  ${duration(recordMs)}", color = C.Text, fontSize = 16.sp,
                    modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                )
                IconButton(
                    onClick = { finishRecording(true) },
                    modifier = Modifier.size(48.dp).background(C.Accent, CircleShape),
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Отправить", tint = Color.White) }
            } else {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(4000) },
                    placeholder = { Text("Сообщение") },
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    shape = RoundedCornerShape(22.dp),
                    colors = fieldColors(),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                if (text.isBlank()) {
                    IconButton(
                        onClick = { startRecording() },
                        modifier = Modifier.size(48.dp).background(C.Accent, CircleShape),
                    ) { Icon(Icons.Filled.Mic, "Голосовое сообщение", tint = Color.White) }
                } else {
                    IconButton(
                        onClick = {
                            vm.send(chatId, text)
                            text = ""
                        },
                        modifier = Modifier.size(48.dp).background(C.Accent, CircleShape),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "Отправить", tint = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: Message, chatId: String) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (m.fromMe) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier.widthIn(max = 300.dp)
                .background(
                    if (m.fromMe) C.Mine else C.Theirs,
                    RoundedCornerShape(
                        topStart = 18.dp, topEnd = 18.dp,
                        bottomStart = if (m.fromMe) 18.dp else 4.dp,
                        bottomEnd = if (m.fromMe) 4.dp else 18.dp,
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            when (m.type) {
                "voice" -> {
                    val id = m.mediaId
                    val isCurrent = VoicePlayer.currentId == id
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(220.dp)) {
                        IconButton(
                            onClick = { if (id != null) VoicePlayer.toggle(ctx, chatId, id) },
                            modifier = Modifier.size(40.dp).background(Color.White.copy(alpha = 0.15f), CircleShape),
                        ) {
                            if (VoicePlayer.loadingId == id) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            else Icon(if (isCurrent && VoicePlayer.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            LinearProgressIndicator(
                                progress = { if (isCurrent) VoicePlayer.progress else 0f },
                                color = Color.White, trackColor = Color.White.copy(alpha = 0.25f),
                                modifier = Modifier.fillMaxWidth().height(3.dp),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(duration(m.durationMs), color = C.Text.copy(alpha = 0.75f), fontSize = 12.sp)
                        }
                    }
                }
                "call" -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Call, null, tint = C.Text.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        m.text + if (m.durationMs > 0) ", ${duration(m.durationMs)}" else "",
                        color = C.Text, fontSize = 15.sp,
                    )
                }
                else -> Text(m.text, color = C.Text, fontSize = 16.sp)
            }
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Text(clock(m.at), color = C.Text.copy(alpha = 0.6f), fontSize = 11.sp)
                if (m.pending) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.Schedule, "Отправляется", tint = C.Text.copy(alpha = 0.6f), modifier = Modifier.size(12.dp))
                }
            }
        }
    }
}

@Composable
private fun DayLabel(at: Long) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), Alignment.Center) {
        Text(
            dayTitle(at), color = C.TextDim, fontSize = 12.sp,
            modifier = Modifier.background(C.Surface, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

// ---------------------------------------------------------------- настройки

@Composable
private fun SettingsScreen(vm: MainVm, me: Profile) {
    var editing by remember { mutableStateOf(false) }
    if (editing) {
        BackHandler { editing = false }
        Box(Modifier.fillMaxSize()) {
            UsernameScreen(vm, initialName = me.name, firstTime = true, current = me)
            IconButton(onClick = { editing = false }, modifier = Modifier.padding(8.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = C.Text)
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = C.Text) }
            Text("Настройки", color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(me, 64.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(me.name, color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("@${me.username}", color = C.TextDim, fontSize = 15.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) { Text("Изменить имя или @username") }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = { vm.open(Screen.Wallpaper(null)) }, modifier = Modifier.fillMaxWidth()) { Text("Обои чатов") }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { if (vm.update != null) vm.installUpdate() else vm.checkUpdate(silent = false) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (vm.update != null) "Установить обновление" else "Проверить обновления") }
        vm.updateStatus?.let { Text(it, color = C.TextDim, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = { vm.signOut() }, modifier = Modifier.fillMaxWidth()) { Text("Выйти", color = C.Bad) }
        Spacer(Modifier.weight(1f))
        Text("Версия ${App.instance.versionName()}", color = C.TextDim, fontSize = 13.sp)
    }
}

// ---------------------------------------------------------------- мелочи

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = C.Accent,
    unfocusedBorderColor = C.SurfaceHi,
    focusedTextColor = C.Text,
    unfocusedTextColor = C.Text,
    cursorColor = C.Accent,
    focusedLabelColor = C.Accent,
    unfocusedLabelColor = C.TextDim,
    focusedPlaceholderColor = C.TextDim,
    unfocusedPlaceholderColor = C.TextDim,
    focusedContainerColor = C.Bg,
    unfocusedContainerColor = C.Bg,
)

private fun duration(ms: Long): String {
    val sec = ms / 1000
    return if (sec >= 3600) String.format(Locale.US, "%d:%02d:%02d", sec / 3600, sec % 3600 / 60, sec % 60)
    else String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
}

private fun clock(t: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

private fun sameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

private fun shortTime(t: Long): String {
    val now = System.currentTimeMillis()
    return when {
        sameDay(t, now) -> clock(t)
        now - t < 6L * 24 * 3_600_000 -> SimpleDateFormat("EE", Locale("ru")).format(Date(t))
        else -> SimpleDateFormat("dd.MM.yy", Locale.getDefault()).format(Date(t))
    }
}

private fun dayTitle(t: Long): String {
    val now = System.currentTimeMillis()
    return when {
        sameDay(t, now) -> "Сегодня"
        sameDay(t, now - 24L * 3_600_000) -> "Вчера"
        else -> SimpleDateFormat("d MMMM", Locale("ru")).format(Date(t))
    }
}
