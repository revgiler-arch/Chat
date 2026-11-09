// Сервер push-уведомлений для «Чата» (Cloudflare Worker).
//
// Телефон отправителя после отправки сообщения вызывает POST /notify {chatId}
// со своим токеном входа Firebase. Сервер проверяет, что отправитель — участник чата,
// находит FCM-токены получателя в Firestore и будит его телефон через Firebase Cloud Messaging.
//
// Секрет SERVICE_ACCOUNT — JSON сервисного аккаунта Firebase (Настройки проекта → Сервисные аккаунты).

const JWKS_URL =
  "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/") {
      return json({ ok: true, service: "chat-push" });
    }
    if (request.method === "GET" && url.pathname === "/turn") {
      try {
        const idToken = (request.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "");
        await verifyIdToken(idToken, env.PROJECT_ID);
        return json({ iceServers: await turnServers(env) });
      } catch (e) {
        return json({ error: String(e.message || e) }, e.status || 500);
      }
    }
    if (request.method === "POST" && url.pathname === "/account/delete") {
      try {
        return await handleDeleteAccount(request, env);
      } catch (e) {
        return json({ error: String(e.message || e) }, e.status || 500);
      }
    }
    const fileRoute = url.pathname.match(/^\/files\/([A-Za-z0-9_-]{10,200})\/([A-Za-z0-9_-]{10,64})$/);
    if (fileRoute && (request.method === "GET" || request.method === "PUT")) {
      try {
        return await handleFile(request, env, fileRoute[1], fileRoute[2]);
      } catch (e) {
        return json({ error: String(e.message || e) }, e.status || 500);
      }
    }
    if (request.method !== "POST" || url.pathname !== "/notify") {
      return json({ error: "not found" }, 404);
    }
    try {
      const idToken = (request.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "");
      const sender = await verifyIdToken(idToken, env.PROJECT_ID);
      const { chatId, kind = "message", callId = "", video = false } = await request.json();
      if (!["message", "call", "cancel"].includes(kind)) return json({ error: "bad kind" }, 400);
      if (typeof chatId !== "string" || !/^[A-Za-z0-9_-]{10,200}$/.test(chatId)) {
        return json({ error: "bad chatId" }, 400);
      }
      const sa = JSON.parse(env.SERVICE_ACCOUNT);
      const token = await accessToken(sa);
      const fs = firestore(env.PROJECT_ID, token);

      const chat = await fs.get(`chats/${chatId}`);
      const members = arr(chat, "members");
      if (!members.includes(sender)) return json({ error: "not a member" }, 403);
      const recipient = members.find((m) => m !== sender);
      if (!recipient) return json({ ok: true, sent: 0 });

      const [me, them] = await Promise.all([fs.get(`users/${sender}`), fs.get(`users/${recipient}`)]);
      const tokens = arr(them, "fcmTokens");
      const title = str(me, "name") || str(me, "username") || "Новое сообщение";
      const body =
        kind === "call" ? (video ? "Входящий видеозвонок" : "Входящий звонок") :
        kind === "cancel" ? "" :
        str(chat, "lastText") || "Новое сообщение";

      let sent = 0;
      const dead = [];
      for (const t of tokens) {
        const r = await fetch(`https://fcm.googleapis.com/v1/projects/${env.PROJECT_ID}/messages:send`, {
          method: "POST",
          headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
          body: JSON.stringify({
            message: {
              token: t,
              // только данные: уведомление рисует само приложение (своё оформление и открытие чата)
              data: {
                kind, chatId, senderUid: sender, title, body: body.slice(0, 300),
                callId: String(callId).slice(0, 100), video: video ? "1" : "0",
              },
              // звонок без ответа через минуту уже не нужен
              android: { priority: "high", ttl: kind === "message" ? "86400s" : "60s" },
            },
          }),
        });
        if (r.ok) sent++;
        else if (r.status === 404 || r.status === 400) dead.push(t);
      }
      if (dead.length) await fs.removeFromArray(`users/${recipient}`, "fcmTokens", dead).catch(() => {});
      return json({ ok: true, sent });
    } catch (e) {
      const status = e.status || 500;
      return json({ error: String(e.message || e) }, status);
    }
  },
};

// ------------------------------------------------------------ файлы (Cloudflare R2)

const MAX_FILE_BYTES = 50 * 1024 * 1024;

// Читать и загружать файлы чата могут только его участники.
async function handleFile(request, env, chatId, fileId) {
  const idToken = (request.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "");
  const me = await verifyIdToken(idToken, env.PROJECT_ID);
  const token = await accessToken(JSON.parse(env.SERVICE_ACCOUNT));
  const chat = await firestore(env.PROJECT_ID, token).get(`chats/${chatId}`);
  if (!arr(chat, "members").includes(me)) return json({ error: "not a member" }, 403);
  const key = `${chatId}/${fileId}`;

  if (request.method === "GET") {
    const obj = await env.FILES.get(key);
    if (!obj) return json({ error: "not found" }, 404);
    return new Response(obj.body, {
      headers: {
        "Content-Type": "application/octet-stream",
        "Content-Length": String(obj.size),
        "X-Content-Type-Options": "nosniff",
      },
    });
  }

  const length = Number(request.headers.get("Content-Length") || 0);
  if (!length) return json({ error: "length required" }, 411);
  if (length > MAX_FILE_BYTES) return json({ error: "too large" }, 413);
  if (await env.FILES.head(key)) return json({ error: "exists" }, 409);
  await env.FILES.put(key, request.body, { customMetadata: { from: me } });
  return json({ ok: true });
}

// ------------------------------------------------------------ удаление аккаунта

// На бесплатном плане Cloudflare — 50 внешних запросов на вызов, поэтому работаем частями:
// отвечаем {done:false}, и приложение вызывает снова. Каждый шаг можно безопасно повторить.
// Сам вход Firebase удаляет приложение последним действием.
const DELETE_BUDGET = 40;
const RECENT_LOGIN_SECONDS = 15 * 60;

async function handleDeleteAccount(request, env) {
  const idToken = (request.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "");
  const claims = await verifyClaims(idToken, env.PROJECT_ID);
  if (Math.floor(Date.now() / 1000) - Number(claims.auth_time || 0) > RECENT_LOGIN_SECONDS) {
    throw httpError(403, "нужно заново подтвердить вход");
  }
  const uid = claims.sub;
  const fs = firestore(env.PROJECT_ID, await accessToken(JSON.parse(env.SERVICE_ACCOUNT)));
  const budget = { left: DELETE_BUDGET };
  const done = await deleteUserData(fs, env, uid, budget);
  return json({ ok: true, done });
}

async function deleteUserData(fs, env, uid, budget) {
  // Чаты и всё внутри них (сообщения, голосовые, файлы в R2)
  while (true) {
    if (budget.left < 1) return false;
    budget.left--;
    const chats = await fs.queryNames("chats", "members", "ARRAY_CONTAINS", uid, 20);
    if (!chats.length) break;
    for (const path of chats) {
      if (!(await deleteChat(fs, env, path, budget))) return false;
    }
  }
  // Служебные документы звонков, где человек был участником
  for (const field of ["from", "to"]) {
    while (true) {
      if (budget.left < 1) return false;
      budget.left--;
      const calls = await fs.queryNames("calls", field, "EQUAL", uid, 10);
      if (!calls.length) break;
      for (const path of calls) {
        for (const side of ["callerCandidates", "calleeCandidates"]) {
          if (!(await deleteCollection(fs, `${path}/${side}`, budget))) return false;
        }
        if (budget.left < 1) return false;
        budget.left--;
        await fs.deleteDocs([fs.fullName(path)]);
      }
    }
  }
  // Профиль и имя @username — в конце, чтобы при сбое можно было продолжить
  if (budget.left < 3) return false;
  budget.left -= 3;
  const user = await fs.get(`users/${uid}`);
  const username = str(user, "username");
  const writes = [fs.fullName(`users/${uid}`)];
  if (username) {
    const owner = await fs.get(`usernames/${username}`);
    if (str(owner, "uid") === uid) writes.push(fs.fullName(`usernames/${username}`));
  }
  await fs.deleteDocs(writes);
  return true;
}

async function deleteChat(fs, env, path, budget) {
  for (const sub of ["messages", "media"]) {
    if (!(await deleteCollection(fs, `${path}/${sub}`, budget))) return false;
  }
  const chatId = path.split("/").pop();
  while (true) {
    if (budget.left < 2) return false;
    budget.left -= 2;
    const list = await env.FILES.list({ prefix: `${chatId}/`, limit: 1000 });
    if (!list.objects.length) break;
    await env.FILES.delete(list.objects.map((o) => o.key));
    if (!list.truncated) break;
  }
  if (budget.left < 1) return false;
  budget.left--;
  await fs.deleteDocs([fs.fullName(path)]);
  return true;
}

async function deleteCollection(fs, path, budget) {
  while (true) {
    if (budget.left < 2) return false;
    budget.left--;
    const names = await fs.listNames(path, 300);
    if (!names.length) return true;
    budget.left--;
    await fs.deleteDocs(names);
    if (names.length < 300) return true;
  }
}

// ------------------------------------------------------------ TURN (ретранслятор для звонков)

// Если настроены секреты TURN_KEY_ID и TURN_KEY_API_TOKEN (Cloudflare → Realtime → TURN),
// выдаём временные логины к TURN-серверам Cloudflare. Иначе — только бесплатный STUN Google.
async function turnServers(env) {
  const stun = [{ urls: ["stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"] }];
  if (!env.TURN_KEY_ID || !env.TURN_KEY_API_TOKEN) return stun;
  const headers = { Authorization: `Bearer ${env.TURN_KEY_API_TOKEN}`, "Content-Type": "application/json" };
  const base = `https://rtc.live.cloudflare.com/v1/turn/keys/${env.TURN_KEY_ID}/credentials`;
  for (const path of ["generate-ice-servers", "generate"]) {
    const r = await fetch(`${base}/${path}`, { method: "POST", headers, body: JSON.stringify({ ttl: 86400 }) });
    if (!r.ok) continue;
    const d = await r.json();
    const list = Array.isArray(d.iceServers) ? d.iceServers : d.iceServers ? [d.iceServers] : [];
    if (list.length) return stun.concat(list);
  }
  return stun;
}

// ------------------------------------------------------------ проверка токена входа Firebase

let jwksCache = { keys: null, until: 0 };

async function verifyIdToken(jwt, projectId) {
  return (await verifyClaims(jwt, projectId)).sub;
}

async function verifyClaims(jwt, projectId) {
  const parts = (jwt || "").split(".");
  if (parts.length !== 3) throw httpError(401, "no token");
  const header = JSON.parse(b64urlDecodeText(parts[0]));
  const payload = JSON.parse(b64urlDecodeText(parts[1]));
  const now = Math.floor(Date.now() / 1000);
  if (header.alg !== "RS256") throw httpError(401, "bad alg");
  if (payload.aud !== projectId || payload.iss !== `https://securetoken.google.com/${projectId}`) {
    throw httpError(401, "wrong project");
  }
  if (!payload.sub || payload.exp < now || payload.iat > now + 300) throw httpError(401, "expired");

  if (!jwksCache.keys || Date.now() > jwksCache.until) {
    const r = await fetch(JWKS_URL);
    const maxAge = Number((r.headers.get("Cache-Control") || "").match(/max-age=(\d+)/)?.[1] || 3600);
    jwksCache = { keys: (await r.json()).keys, until: Date.now() + maxAge * 1000 };
  }
  const jwk = jwksCache.keys.find((k) => k.kid === header.kid);
  if (!jwk) throw httpError(401, "unknown key");
  const key = await crypto.subtle.importKey(
    "jwk", jwk, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"],
  );
  const ok = await crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5", key, b64urlDecode(parts[2]), new TextEncoder().encode(`${parts[0]}.${parts[1]}`),
  );
  if (!ok) throw httpError(401, "bad signature");
  return payload;
}

// ------------------------------------------------------------ токен доступа сервисного аккаунта

let saCache = { token: null, until: 0 };

async function accessToken(sa) {
  if (saCache.token && Date.now() < saCache.until) return saCache.token;
  const now = Math.floor(Date.now() / 1000);
  const claim = {
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/datastore https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  };
  const unsigned = `${b64urlText(JSON.stringify({ alg: "RS256", typ: "JWT" }))}.${b64urlText(JSON.stringify(claim))}`;
  const pem = sa.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey(
    "pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"],
  );
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned));
  const r = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: `grant_type=${encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer")}&assertion=${unsigned}.${b64url(new Uint8Array(sig))}`,
  });
  const data = await r.json();
  if (!data.access_token) throw new Error("service account auth failed: " + JSON.stringify(data).slice(0, 200));
  saCache = { token: data.access_token, until: Date.now() + (data.expires_in - 120) * 1000 };
  return saCache.token;
}

// ------------------------------------------------------------ Firestore REST

function firestore(projectId, token) {
  const base = `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents`;
  return {
    async get(path) {
      const r = await fetch(`${base}/${path}`, { headers: { Authorization: `Bearer ${token}` } });
      if (r.status === 404) return null;
      if (!r.ok) throw new Error(`firestore ${r.status}`);
      return r.json();
    },
    fullName(path) {
      return `projects/${projectId}/databases/(default)/documents/${path}`;
    },
    // Только имена документов: содержимое (например, звук голосовых) не скачиваем
    async listNames(path, pageSize) {
      const r = await fetch(`${base}/${path}?pageSize=${pageSize}&mask.fieldPaths=from`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      if (r.status === 404) return [];
      if (!r.ok) throw new Error(`firestore list ${r.status}`);
      return ((await r.json()).documents || []).map((d) => d.name);
    },
    // Пути документов коллекции по одному условию
    async queryNames(collectionId, field, op, value, limit) {
      const r = await fetch(`${base}:runQuery`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: JSON.stringify({
          structuredQuery: {
            from: [{ collectionId }],
            where: { fieldFilter: { field: { fieldPath: field }, op, value: { stringValue: value } } },
            select: { fields: [{ fieldPath: "__name__" }] },
            limit,
          },
        }),
      });
      if (!r.ok) throw new Error(`firestore query ${r.status}`);
      return (await r.json()).filter((x) => x.document).map((x) => x.document.name.split("/documents/")[1]);
    },
    async deleteDocs(names) {
      const r = await fetch(`${base}:commit`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: JSON.stringify({ writes: names.map((name) => ({ delete: name })) }),
      });
      if (!r.ok) throw new Error(`firestore delete ${r.status}`);
    },
    async removeFromArray(path, field, values) {
      const name = `projects/${projectId}/databases/(default)/documents/${path}`;
      await fetch(`${base}:commit`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: JSON.stringify({
          writes: [{
            transform: {
              document: name,
              fieldTransforms: [{
                fieldPath: field,
                removeAllFromArray: { values: values.map((v) => ({ stringValue: v })) },
              }],
            },
          }],
        }),
      });
    },
  };
}

function str(doc, field) {
  return doc?.fields?.[field]?.stringValue || "";
}

function arr(doc, field) {
  return (doc?.fields?.[field]?.arrayValue?.values || []).map((v) => v.stringValue).filter(Boolean);
}

// ------------------------------------------------------------ мелочи

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), { status, headers: { "Content-Type": "application/json" } });
}

function httpError(status, message) {
  const e = new Error(message);
  e.status = status;
  return e;
}

function b64url(bytes) {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function b64urlText(text) {
  return b64url(new TextEncoder().encode(text));
}

function b64urlDecode(s) {
  s = s.replace(/-/g, "+").replace(/_/g, "/");
  while (s.length % 4) s += "=";
  return Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
}

function b64urlDecodeText(s) {
  return new TextDecoder().decode(b64urlDecode(s));
}

// Выкладка: workflow «Deploy push server» (.github/workflows/deploy-push.yml)
