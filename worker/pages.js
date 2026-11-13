// Публичные страницы для Google Play: политика конфиденциальности и удаление аккаунта.
// Адреса: /privacy и /delete-account на сервере уведомлений.

const APP = "U chat";
const UPDATED = "13.11.2026";

function page(title, body, script = "") {
  return `<!DOCTYPE html>
<html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title} — ${APP}</title>
<style>
  :root { color-scheme: light dark; --acc: #F27A1A; }
  body { font: 16px/1.6 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; margin: 0;
         background: Canvas; color: CanvasText; }
  main { max-width: 760px; margin: 0 auto; padding: 28px 20px 60px; }
  header { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
  .logo { width: 44px; height: 44px; border-radius: 50%; background: linear-gradient(135deg,#FFA034,#F26A0F);
          display: grid; place-items: center; color: #fff; font-weight: 800; font-size: 22px; }
  h1 { font-size: 26px; margin: 0; }
  h2 { font-size: 19px; margin-top: 28px; }
  .muted { opacity: .65; font-size: 14px; }
  hr { border: 0; border-top: 1px solid color-mix(in srgb, CanvasText 15%, transparent); margin: 36px 0; }
  button { font: inherit; padding: 12px 20px; border-radius: 12px; border: 0; cursor: pointer;
           background: var(--acc); color: #fff; font-weight: 600; }
  button.danger { background: #D93838; }
  button:disabled { opacity: .5; cursor: default; }
  .box { padding: 16px; border-radius: 14px; background: color-mix(in srgb, CanvasText 6%, transparent); margin: 16px 0; }
  a { color: var(--acc); }
</style></head>
<body><main>
<header><div class="logo">U</div><h1>${title}</h1></header>
${body}
</main>${script}</body></html>`;
}

function contactRu(env) {
  return env.CONTACT_EMAIL
    ? `Вопросы и запросы: <a href="mailto:${env.CONTACT_EMAIL}">${env.CONTACT_EMAIL}</a>.`
    : `Вопросы и запросы: через кнопку «Пожаловаться» в приложении или страницу <a href="/delete-account">удаления аккаунта</a>.`;
}
function contactEn(env) {
  return env.CONTACT_EMAIL
    ? `Contact: <a href="mailto:${env.CONTACT_EMAIL}">${env.CONTACT_EMAIL}</a>.`
    : `Contact: via the in-app “Report” button or the <a href="/delete-account">account deletion page</a>.`;
}

export function privacyPage(env) {
  return html(page("Политика конфиденциальности", `
<p class="muted">Обновлено ${UPDATED} · <a href="#en">English version below</a></p>

<p>${APP} — лёгкий мессенджер: сообщения и звонки, без рекламы и без отслеживания.
Здесь описано, какие данные приложение хранит и зачем.</p>

<h2>Какие данные мы храним</h2>
<ul>
  <li><b>Аккаунт.</b> Вход через Google: мы получаем идентификатор аккаунта, имя и фото профиля Google.
      Адрес почты другим пользователям не показывается.</li>
  <li><b>Профиль.</b> Имя, @username, статус и фото, которые вы указали, — их видят другие пользователи, чтобы вас найти.</li>
  <li><b>Переписка.</b> Текст сообщений, голосовые, фото и файлы, а также отметки «доставлено/прочитано».
      Хранятся, чтобы собеседник получил их на любом устройстве. Файлы удаляются автоматически через 90 дней.</li>
  <li><b>Звонки.</b> Служебные данные для соединения (кто, кому, когда, длительность). Сам голос и видео
      идут напрямую между телефонами в зашифрованном виде (WebRTC) и нигде не записываются.</li>
  <li><b>Уведомления.</b> Технический токен устройства, чтобы присылать push-уведомления.</li>
  <li><b>Жалобы.</b> Если вы пожаловались, мы храним причину, комментарий и до 5 последних сообщений нарушителя.</li>
</ul>
<p>Мы <b>не</b> собираем контакты телефона, местоположение, рекламные идентификаторы и не используем аналитику.
Мы <b>не продаём</b> и не передаём данные третьим лицам для рекламы.</p>

<h2>Где хранятся данные</h2>
<p>У сервисов, на которых работает приложение: Google Firebase (вход, база данных, уведомления) и
Cloudflare (сервер уведомлений, хранение файлов, ретрансляция звонков). Данные передаются по защищённому
соединению (HTTPS/TLS).</p>

<h2>Безопасность и модерация</h2>
<p>Любого пользователя можно заблокировать или на него пожаловаться прямо из чата. Жалобы проверяются вручную;
за нарушения аккаунт может быть удалён. Материалы сексуального насилия над детьми запрещены, такие аккаунты
удаляются, а сведения передаются в уполномоченные организации.</p>
<p>Приложение не предназначено для детей младше 13 лет.</p>

<h2>Удаление данных</h2>
<p>Аккаунт и все ваши данные (профиль, чаты, сообщения, файлы) можно удалить в приложении:
<b>Настройки → Удалить аккаунт</b>, или на странице <a href="/delete-account">удаления аккаунта</a>.
Удаление необратимо и происходит сразу.</p>

<h2>Контакты</h2>
<p>${contactRu(env)}</p>

<hr id="en">

<h2>Privacy Policy (English)</h2>
<p class="muted">Updated ${UPDATED}</p>
<p>${APP} is a lightweight messenger for messages and calls, with no ads and no tracking.</p>
<ul>
  <li><b>Account:</b> Google Sign-In — account ID, name and profile photo. Your email is not shown to other users.</li>
  <li><b>Profile:</b> name, @username, status and photo you set; visible to other users so they can find you.</li>
  <li><b>Messages:</b> text, voice messages, photos and files, plus delivery/read receipts, stored so the recipient
      can get them. Files are deleted automatically after 90 days.</li>
  <li><b>Calls:</b> signalling metadata only (who, to whom, when, duration). Audio and video go peer-to-peer,
      encrypted (WebRTC), and are never recorded.</li>
  <li><b>Notifications:</b> a device push token.</li>
  <li><b>Reports:</b> reason, comment and up to 5 recent messages of the reported user.</li>
</ul>
<p>We do <b>not</b> collect contacts, location, advertising IDs or analytics, and we do <b>not</b> sell or share data
for advertising. Data is processed by Google Firebase and Cloudflare and encrypted in transit.</p>
<p>Users can block and report others from any chat. Reports are reviewed manually. Child sexual abuse material
is prohibited; offending accounts are removed and reported to the relevant authorities. Not intended for children under 13.</p>
<p>You can delete your account and all data in the app (<b>Settings → Delete account</b>) or at
<a href="/delete-account">/delete-account</a>. Deletion is immediate and permanent.</p>
<p>${contactEn(env)}</p>
`));
}

export function deletePage(env) {
  const cfg = JSON.stringify({
    apiKey: env.WEB_API_KEY,
    authDomain: `${env.PROJECT_ID}.firebaseapp.com`,
    projectId: env.PROJECT_ID,
  });
  return html(page("Удаление аккаунта", `
<p class="muted">${APP} · <a href="#en">English below</a></p>

<p>Удаляются <b>навсегда</b>: ваш профиль и @username, все ваши чаты с сообщениями, голосовыми и файлами,
история звонков и токены уведомлений. Жалобы, которые вы отправили, сохраняются у модераторов.</p>

<div class="box">
  <b>Способ 1 — в приложении.</b> Откройте ${APP} → нажмите на свой аватар → <b>Удалить аккаунт</b>.
</div>

<div class="box">
  <b>Способ 2 — здесь.</b> Войдите тем же аккаунтом Google, что и в приложении.
  <p id="who" class="muted"></p>
  <p><button id="login">Войти через Google</button></p>
  <p><label hidden id="confirmWrap"><input type="checkbox" id="confirm"> Я понимаю, что восстановить данные нельзя</label></p>
  <p><button id="del" class="danger" hidden disabled>Удалить аккаунт навсегда</button></p>
  <p id="status"></p>
</div>

<p>${contactRu(env)}</p>

<hr id="en">
<h2>Delete your account (English)</h2>
<p>This permanently deletes your profile and @username, all your chats with messages, voice messages and files,
call history and push tokens. Delete in the app (tap your avatar → <b>Delete account</b>) or sign in above with
the same Google account and press the red button.</p>
<p>${contactEn(env)}</p>
`, `
<script type="module">
import { initializeApp } from "https://www.gstatic.com/firebasejs/10.14.1/firebase-app.js";
import { getAuth, GoogleAuthProvider, signInWithPopup, deleteUser } from "https://www.gstatic.com/firebasejs/10.14.1/firebase-auth.js";

const auth = getAuth(initializeApp(${cfg}));
const $ = (id) => document.getElementById(id);
const say = (t) => { $("status").textContent = t; };

$("login").onclick = async () => {
  try {
    const { user } = await signInWithPopup(auth, new GoogleAuthProvider());
    $("who").textContent = "Вы вошли как " + (user.displayName || "") + " · " + (user.email || "");
    $("login").hidden = true;
    $("confirmWrap").hidden = false;
    $("del").hidden = false;
  } catch (e) {
    say("Не удалось войти: " + (e.code || e.message));
  }
};
$("confirm").onchange = () => { $("del").disabled = !$("confirm").checked; };

$("del").onclick = async () => {
  const user = auth.currentUser;
  if (!user) return;
  $("del").disabled = true;
  try {
    const token = await user.getIdToken(true);
    for (let i = 0; i < 1000; i++) {
      say("Удаляю… шаг " + (i + 1));
      const r = await fetch("/account/delete", { method: "POST", headers: { Authorization: "Bearer " + token } });
      const j = await r.json();
      if (!r.ok) throw new Error(j.error || r.status);
      if (j.done) break;
    }
    await deleteUser(user);
    say("Готово: аккаунт и все данные удалены.");
    $("del").hidden = true; $("confirmWrap").hidden = true;
  } catch (e) {
    say("Ошибка: " + (e.message || e) + ". Попробуйте ещё раз или удалите аккаунт в приложении.");
    $("del").disabled = false;
  }
};
</script>`));
}

function html(body) {
  return new Response(body, { headers: { "Content-Type": "text/html; charset=utf-8" } });
}
