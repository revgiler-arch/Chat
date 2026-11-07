# Чат

Лёгкий мессенджер для своего круга: переписка, голосовые, аудио- и видеозвонки. Без статусов, сообществ и рекламы.

- Вход через Google, друг друга находят по **@username** (почту никто не видит).
- Сообщения хранятся в Firebase Firestore, отправка работает и без сети — дойдёт, когда сеть появится.
- Доступ к данным ограничен правилами `firestore.rules`: каждый видит только свои чаты.

## Настройка Firebase (один раз)

1. Authentication → Sign-in method → **Google** — включено.
2. Firestore Database → **Rules** → вставить содержимое `firestore.rules` → Publish.
3. SHA-1 ключа подписи добавлен в настройках Android-приложения.

## Сборка

APK собирает GitHub Actions при каждом пуше в `main` и выкладывает в Releases (тег `latest`);
приложение само проверяет там обновления. Нужен секрет `SIGNING_KEY_PASS`.

## Уведомления и звонки

Сервер уведомлений — Cloudflare Worker из папки `worker/` (бесплатно, без карты). Его выкладывает
workflow «Deploy push server»; нужны секреты репозитория:

| Секрет | Где взять |
|---|---|
| `CLOUDFLARE_API_TOKEN` | Cloudflare → My Profile → API Tokens → Create Token → шаблон «Edit Cloudflare Workers» |
| `CLOUDFLARE_ACCOUNT_ID` | Cloudflare → Workers & Pages → справа «Account ID» |
| `FIREBASE_SERVICE_ACCOUNT` | Firebase → ⚙ Настройки проекта → Сервисные аккаунты → «Создать закрытый ключ» (весь JSON целиком) |
| `TURN_KEY_ID`, `TURN_KEY_API_TOKEN` | по желанию: Cloudflare → Realtime → TURN Server → Create. Без них звонки идут напрямую через STUN — на части мобильных сетей могут не соединяться |

Адрес сервера сборка сама записывает в `push-url.txt`, приложение читает его оттуда.

- Звонки (WebRTC) устанавливаются через документы `calls/` в Firestore. Пока приложение открыто,
  входящий звонок приходит и без сервера уведомлений; в фоне — через push.
- Голосовые сообщения (AAC ~24 кбит/с, до 4 минут) хранятся прямо в Firestore (`chats/*/media`).
