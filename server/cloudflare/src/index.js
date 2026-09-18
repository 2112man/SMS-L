/**
 * SMS-L 云端中继 — Cloudflare Worker 入口。
 *
 * 路由：
 *   POST /sms    iPhone 快捷指令 → Worker（Bearer IPHONE_TOKEN）→ Durable Object
 *   GET  /ws     Android        → Worker（Bearer ANDROID_TOKEN）→ WebSocket 升级
 *   GET  /health 部署自检
 *
 * 安全约定（务必保持）：
 *   - 两个 Token 只从 Worker Secrets 读取，互不通用。
 *   - 任何日志都不记录短信正文、发件人和 Token。
 *   - Token 只通过 Authorization 请求头传递，绝不放进 URL。
 */

import { AndroidHub } from "./hub.js";

export { AndroidHub };

export const HUB_NAME = "hub";

const MAX_BODY_BYTES = 16 * 1024;
const MAX_TEXT_CHARS = 8000;
const MAX_METADATA_CHARS = 200;
const MAX_MESSAGE_ID_CHARS = 256;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/sms") return handleSms(request, env);
    if (url.pathname === "/ws") return handleWebSocket(request, env);
    if (url.pathname === "/health") {
      return jsonResponse(200, { ok: true, service: "sms-l-relay" });
    }
    return jsonResponse(404, { ok: false, error: "not_found" });
  },
};

/** iPhone → Worker：校验 IPHONE_TOKEN，生成规范化 envelope，转投 Durable Object。 */
async function handleSms(request, env) {
  if (request.method !== "POST") {
    return jsonResponse(405, { ok: false, error: "method_not_allowed" });
  }
  if (!verifyBearer(request, env.IPHONE_TOKEN)) {
    return jsonResponse(401, { ok: false, error: "unauthorized" });
  }

  const contentType = (request.headers.get("Content-Type") ?? "").split(";")[0].trim().toLowerCase();
  if (contentType !== "application/json") {
    return jsonResponse(400, { ok: false, error: "invalid_content_type" });
  }

  const declared = request.headers.get("Content-Length");
  if (declared !== null && declared !== "") {
    const length = Number(declared);
    if (!Number.isFinite(length) || length < 0) {
      return jsonResponse(400, { ok: false, error: "invalid_body" });
    }
    if (length > MAX_BODY_BYTES) {
      return jsonResponse(400, { ok: false, error: "body_too_large" });
    }
  }

  const body = await readLimitedBody(request, MAX_BODY_BYTES);
  if (body.error) return jsonResponse(400, { ok: false, error: body.error });

  let payload;
  try {
    payload = JSON.parse(body.text);
  } catch {
    return jsonResponse(400, { ok: false, error: "invalid_json" });
  }
  if (payload === null || typeof payload !== "object" || Array.isArray(payload)) {
    return jsonResponse(400, { ok: false, error: "invalid_json" });
  }

  const text = typeof payload.text === "string" ? payload.text.trim() : "";
  if (text.length === 0) return jsonResponse(400, { ok: false, error: "text_required" });
  if (text.length > MAX_TEXT_CHARS) return jsonResponse(400, { ok: false, error: "text_too_long" });

  const sender = normalizeMetadata(payload.sender);
  if (sender === undefined) return jsonResponse(400, { ok: false, error: "sender_too_long" });

  const messageId = await resolveMessageId(payload.messageId, sender, text, payload.timestamp);
  if (messageId === null) {
    return jsonResponse(400, { ok: false, error: "message_id_too_long" });
  }

  const envelope = {
    type: "sms",
    messageId,
    sender: sender ?? null,
    text,
    timestamp: normalizeTimestamp(payload.timestamp),
  };

  const stub = env.HUB.get(env.HUB.idFromName(HUB_NAME));
  let result;
  try {
    const response = await stub.fetch("https://hub.internal/deliver", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ envelope }),
    });
    result = response.ok ? await response.json() : null;
  } catch {
    result = null;
  }
  if (!result) {
    return jsonResponse(502, { ok: false, error: "relay_unavailable" });
  }

  // 只回传投递计数与消息标识，不含短信内容。
  return jsonResponse(202, { ok: true, messageId, delivered: result.delivered });
}

/** Android → Worker：校验 ANDROID_TOKEN，然后把升级请求交给 Durable Object。 */
async function handleWebSocket(request, env) {
  if (request.method !== "GET") {
    return jsonResponse(405, { ok: false, error: "method_not_allowed" });
  }
  if (!verifyBearer(request, env.ANDROID_TOKEN)) {
    return jsonResponse(401, { ok: false, error: "unauthorized" });
  }
  const upgrade = (request.headers.get("Upgrade") ?? "").trim().toLowerCase();
  if (upgrade !== "websocket") {
    return jsonResponse(426, { ok: false, error: "upgrade_required" });
  }

  const stub = env.HUB.get(env.HUB.idFromName(HUB_NAME));
  return stub.fetch(request);
}

/**
 * 有 messageId 就用原始的；没有就用内容哈希兜底。
 * 哈希输入固定为 sender + NUL + text + NUL + timestamp(秒)，两端算法必须一致。
 */
async function resolveMessageId(rawMessageId, sender, text, rawTimestamp) {
  if (typeof rawMessageId === "string") {
    const trimmed = rawMessageId.trim();
    if (trimmed.length > MAX_MESSAGE_ID_CHARS) return null;
    if (trimmed.length > 0) return trimmed;
  }
  const material = `${sender ?? ""}\u0000${text}\u0000${normalizeTimestamp(rawTimestamp) ?? ""}`;
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(material));
  return "h1-" + toHex(new Uint8Array(digest));
}

function toHex(bytes) {
  let out = "";
  for (const byte of bytes) out += byte.toString(16).padStart(2, "0");
  return out;
}

/** 时间戳统一成整数秒的字符串；无法识别时返回 null。 */
function normalizeTimestamp(value) {
  if (typeof value === "number" && Number.isFinite(value)) {
    return String(Math.trunc(value));
  }
  if (typeof value === "string") {
    const trimmed = value.trim();
    if (trimmed.length > 0 && trimmed.length <= 24 && /^-?\d+(\.\d+)?$/.test(trimmed)) {
      return String(Math.trunc(Number(trimmed)));
    }
  }
  return null;
}

/** 返回 null 表示字段缺失；返回 undefined 表示超长需要拒绝。 */
function normalizeMetadata(value) {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  if (trimmed.length === 0) return null;
  if (trimmed.length > MAX_METADATA_CHARS) return undefined;
  return trimmed;
}

/** 流式读取并按上限截断，避免超大请求体撑爆内存。 */
async function readLimitedBody(request, limit) {
  if (!request.body) return { error: "invalid_body" };

  const reader = request.body.getReader();
  const chunks = [];
  let received = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      received += value.byteLength;
      if (received > limit) {
        await reader.cancel();
        return { error: "body_too_large" };
      }
      chunks.push(value);
    }
  } catch {
    return { error: "invalid_body" };
  }

  const merged = new Uint8Array(received);
  let offset = 0;
  for (const chunk of chunks) {
    merged.set(chunk, offset);
    offset += chunk.byteLength;
  }

  try {
    // fatal 模式：非法 UTF-8 直接报错，而不是替换成 U+FFFD 污染短信内容。
    return { text: new TextDecoder("utf-8", { fatal: true }).decode(merged) };
  } catch {
    return { error: "invalid_utf8" };
  }
}

function extractBearerToken(request) {
  const header = request.headers.get("Authorization") ?? "";
  const match = /^Bearer[ \t]+(.+)$/i.exec(header.trim());
  return match ? match[1].trim() : null;
}

function verifyBearer(request, expected) {
  if (typeof expected !== "string" || expected.length === 0) return false;
  const supplied = extractBearerToken(request);
  if (supplied === null) return false;
  return timingSafeEqual(supplied, expected);
}

/** 长度与内容都做比较，避免通过响应时间泄露 Token 前缀。 */
function timingSafeEqual(left, right) {
  const encoder = new TextEncoder();
  const a = encoder.encode(left);
  const b = encoder.encode(right);
  let difference = a.length ^ b.length;
  const length = Math.max(a.length, b.length);
  for (let i = 0; i < length; i += 1) {
    difference |= (a[i] ?? 0) ^ (b[i] ?? 0);
  }
  return difference === 0;
}

function jsonResponse(status, payload) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
    },
  });
}
