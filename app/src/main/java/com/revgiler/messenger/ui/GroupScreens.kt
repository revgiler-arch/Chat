package com.revgiler.messenger.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.revgiler.messenger.data.Chat
import com.revgiler.messenger.data.Invite
import com.revgiler.messenger.data.Profile

// ---------------------------------------------------------------- общие кусочки

/** Круглая «аватарка» группы: значок людей на цветном фоне. */
@Composable
internal fun GroupAvatar(name: String, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(authorColor(name).copy(alpha = 0.85f)), Alignment.Center) {
        Icon(Icons.Filled.Groups, null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
    }
}

internal fun membersLabel(n: Int): String {
    val word = when {
        n % 10 == 1 && n % 100 != 11 -> "участник"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "участника"
        else -> "участников"
    }
    return "$n $word"
}

private val AUTHOR_COLORS = listOf(
    Color(0xFFFF8A3D), Color(0xFF4FC3F7), Color(0xFF81C784), Color(0xFFBA68C8),
    Color(0xFFF06292), Color(0xFFFFD54F), Color(0xFF4DB6AC), Color(0xFF9FA8DA),
)

/** У каждого участника свой постоянный цвет имени. */
internal fun authorColor(key: String): Color = AUTHOR_COLORS[(key.hashCode() and 0x7fffffff) % AUTHOR_COLORS.size]

// ---------------------------------------------------------------- список чатов

@Composable
internal fun InviteCard(inv: Invite, from: Profile?, vm: MainVm) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
            .clip(RoundedCornerShape(14.dp)).background(C.Surface).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GroupAvatar(inv.name, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(inv.name, color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${from?.name ?: "Вас"} приглашает вас · ${membersLabel(inv.membersCount)}",
                    color = C.TextDim, fontSize = 13.sp, maxLines = 2,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { vm.declineInvite(inv) }, modifier = Modifier.weight(1f)) { Text("Отклонить", color = C.Text) }
            Button(onClick = { vm.acceptInvite(inv) }, modifier = Modifier.weight(1f)) { Text("Вступить") }
        }
    }
}

@Composable
internal fun GroupRow(chat: Chat, people: Map<String, Profile>, typing: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupAvatar(chat.name, 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chat.name, color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (chat.lastAt > 0) Text(shortTime(chat.lastAt), color = if (chat.unread) C.Accent else C.TextDim, fontSize = 12.sp)
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val author = when {
                    chat.lastFromMe -> "Вы: "
                    chat.lastFromUid != null -> people[chat.lastFromUid]?.name?.let { "$it: " }.orEmpty()
                    else -> ""
                }
                Text(
                    if (typing) "${people[chat.typingUid ?: ""]?.name ?: "Кто-то"} печатает…" else author + chat.lastText.ifBlank { "Нет сообщений" },
                    color = if (typing) C.Accent else C.TextDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (chat.unread) Box(Modifier.padding(start = 8.dp).size(10.dp).background(C.Accent, CircleShape))
            }
        }
    }
}

// ---------------------------------------------------------------- поиск человека по @username

/** Поле «@username» + кнопка: найденного человека отдаёт в [onFound]. */
@Composable
private fun PersonPicker(vm: MainVm, exclude: Set<String>, onFound: (Profile) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    fun find() {
        status = "Ищу…"
        vm.lookup(query) { p, err ->
            when {
                p == null -> status = err
                p.uid in exclude -> status = "${p.name} уже в списке"
                else -> { status = null; query = ""; onFound(p) }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' || c == '@' }.take(21) },
            placeholder = { Text("@имя_пользователя") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { find() }),
            colors = fieldColors(),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = { find() },
            enabled = query.length >= 3,
            modifier = Modifier.size(52.dp).background(C.Accent, CircleShape),
        ) { Icon(Icons.Filled.PersonAdd, "Добавить", tint = Color.White) }
    }
    status?.let { Text(it, color = C.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
}

@Composable
private fun PersonRow(p: Profile?, note: String?, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(p, 42.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p?.name ?: "…", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(p?.username?.takeIf { it.isNotBlank() }?.let { "@$it" }, note).joinToString(" · "),
                color = C.TextDim, fontSize = 13.sp, maxLines = 1,
            )
        }
        trailing()
    }
}

// ---------------------------------------------------------------- новая группа

@Composable
internal fun NewGroupScreen(vm: MainVm) {
    var name by rememberSaveable { mutableStateOf("") }
    val picked = remember { mutableStateListOf<Profile>() }
    Column(Modifier.fillMaxSize().padding(16.dp).imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = C.Text) }
            Text("Новая группа", color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(50) },
            label = { Text("Название группы") }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(18.dp))
        Text("Кого пригласить", color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Каждый получит приглашение и сам решит, вступать ли в группу.",
            color = C.TextDim, fontSize = 13.sp,
        )
        Spacer(Modifier.height(8.dp))
        PersonPicker(vm, picked.map { it.uid }.toSet()) { if (picked.size < 49) picked.add(it) }
        LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
            items(picked, key = { it.uid }) { p ->
                PersonRow(p, "получит приглашение") {
                    IconButton(onClick = { picked.remove(p) }) { Icon(Icons.Filled.Close, "Убрать", tint = C.TextDim) }
                }
            }
        }
        Button(
            onClick = { vm.createGroup(name, picked.toList()) },
            enabled = name.isNotBlank() && !vm.busy,
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            if (vm.busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
            else Text(if (picked.isEmpty()) "Создать группу" else "Создать и пригласить (${picked.size})", fontSize = 16.sp)
        }
    }
}

// ---------------------------------------------------------------- участники и настройки группы

@Composable
internal fun GroupInfoScreen(vm: MainVm, chatId: String, me: Profile) {
    val ctx = LocalContext.current
    val chat = vm.chats.firstOrNull { it.id == chatId }
    if (chat == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = C.Text) }
            Text("Группа недоступна", color = C.TextDim, modifier = Modifier.padding(16.dp))
        }
        return
    }
    val owner = chat.owner == me.uid
    var renaming by remember { mutableStateOf(false) }
    var inviting by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var reportWho by remember { mutableStateOf<Profile?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = C.Text) }
                Text("Группа", color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                GroupAvatar(chat.name, 88.dp)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(chat.name, color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    if (owner) IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, "Переименовать", tint = C.TextDim) }
                }
                Text(membersLabel(chat.members.size), color = C.TextDim, fontSize = 14.sp)
            }
            if (owner) {
                OutlinedButton(onClick = { inviting = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.PersonAdd, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Пригласить участников")
                }
                Spacer(Modifier.height(12.dp))
            }
            Text("Участники", color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 6.dp))
        }
        // владелец первым, остальные по имени
        val members = chat.members.sortedWith(compareBy({ it != chat.owner }, { vm.people[it]?.name ?: "" }))
        items(members, key = { "m/$it" }) { uid ->
            val p = vm.people[uid]
            val isMe = uid == me.uid
            PersonRow(
                if (isMe) me else p,
                listOfNotNull(if (uid == chat.owner) "владелец" else null, if (isMe) "вы" else null).joinToString(", ").ifEmpty { null },
            ) {
                if (!isMe && p != null) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Ещё", tint = C.TextDim) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Написать лично") }, onClick = {
                                menu = false
                                vm.startChat(p)
                            })
                            if (owner) DropdownMenuItem(text = { Text("Удалить из группы", color = C.Bad) }, onClick = {
                                menu = false
                                vm.removeMember(chatId, uid)
                            })
                            DropdownMenuItem(text = { Text("Пожаловаться", color = C.Bad) }, onClick = {
                                menu = false
                                reportWho = p
                            })
                        }
                    }
                }
            }
        }
        if (chat.invited.isNotEmpty()) {
            item {
                Text(
                    "Ждут ответа на приглашение",
                    color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
                )
            }
            items(chat.invited, key = { "i/$it" }) { uid ->
                PersonRow(vm.people[uid], "приглашён") {
                    if (owner) TextButton(onClick = { vm.cancelInvite(chatId, uid) }) { Text("Отменить", color = C.TextDim) }
                }
            }
        }
        item {
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = { confirmLeave = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, null, Modifier.size(18.dp), tint = C.Bad)
                Spacer(Modifier.width(8.dp))
                Text("Выйти из группы", color = C.Bad)
            }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(chat.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            containerColor = C.Surface,
            title = { Text("Название группы", color = C.Text) },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it.take(50) }, singleLine = true, colors = fieldColors())
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    renaming = false
                    vm.renameGroup(chatId, name)
                }) { Text("Сохранить", color = C.Accent) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Отмена", color = C.Text) } },
        )
    }
    if (inviting) {
        AlertDialog(
            onDismissRequest = { inviting = false },
            containerColor = C.Surface,
            title = { Text("Пригласить в «${chat.name}»", color = C.Text) },
            text = {
                Column {
                    Text("Человек получит приглашение и сам решит, вступать ли.", color = C.TextDim, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    PersonPicker(vm, (chat.members + chat.invited).toSet()) { p ->
                        vm.inviteToGroup(chatId, p)
                        Toast.makeText(ctx, "Приглашение отправлено: ${p.name}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { inviting = false }) { Text("Готово", color = C.Accent) } },
        )
    }
    if (confirmLeave) {
        val alone = chat.members.size <= 1
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            containerColor = C.Surface,
            title = { Text("Выйти из «${chat.name}»?", color = C.Text) },
            text = {
                Text(
                    when {
                        alone -> "Вы последний участник — группа будет удалена."
                        owner -> "Вы владелец: группа перейдёт к другому участнику."
                        else -> "Вернуться можно будет только по новому приглашению."
                    },
                    color = C.TextDim, fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    vm.leaveGroup(chat)
                }) { Text("Выйти", color = C.Bad) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Отмена", color = C.Text) } },
        )
    }
    reportWho?.let { p ->
        ReportDialog(
            name = p.name,
            onDismiss = { reportWho = null },
            onSend = { reason, comment ->
                reportWho = null
                vm.report(chatId, p, reason, comment) {
                    Toast.makeText(ctx, "Жалоба отправлена", Toast.LENGTH_LONG).show()
                }
            },
        )
    }
}
