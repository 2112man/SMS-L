/**
 * Worker 逻辑测试（无第三方依赖，直接用 node 运行）。
 *
 *   cd server/cloudflare && node test/worker.test.mjs
 *
 * 用真实的 Request/Response 跑通路由与鉴权，只把 Durable Object 替换成桩，
 * 因此验证的是 Worker 自身的校验逻辑，而不是 Cloudflare 运行时。
 */
import { pathToFileURL } from "node:url";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const WORKER = process.argv[2] ?? resolve(here, "../src/index.js");
const { default: worker } = await import(pathToFileURL(WORKER).href);

const IPHONE_TOKEN = "iphone-token-aaaaaaaaaaaaaaaaaaaaaaaa";
const ANDROID_TOKEN = "android-token-bbbbbbbbbbbbbbbbbbbbbb";

let captured = null;
const env = {
  IPHONE_TOKEN,
  ANDROID_TOKEN,
  HUB: {
    idFromName: (name) => `id:${name}`,
    get: () => ({
      fetch: async (url, init) => {
        captured = { url, body: JSON.parse(init.body) };
        return new Response(JSON.stringify({ ok: true, delivered: 1 }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      },
    }),
  },
};

const BASE = "https://relay.example.com";
let pass = 0;
let fail = 0;

function check(name, condition, extra = "") {
  if (condition) {
    pass += 1;
    console.log(`  PASS  ${name}`);
  } else {
    fail += 1;
    console.log(`  FAIL  ${name} ${extra}`);
  }
}

function post(body, { token = IPHONE_TOKEN, contentType = "application/json", raw = false } = {}) {
  const headers = {};
  if (contentType !== null) headers["Content-Type"] = contentType;
  if (token !== null) headers["Authorization"] = `Bearer ${token}`;
  return new Request(`${BASE}/sms`, {
    method: "POST",
    headers,
    body: raw ? body : JSON.stringify(body),
  });
}

console.log("\n--- /health ---");
{
  const res = await worker.fetch(new Request(`${BASE}/health`), env);
  const json = await res.json();
  check("returns 200", res.status === 200);
  check("does not leak token", !JSON.stringify(json).includes("token"));
}

console.log("\n--- 鉴权 ---");
{
  const noAuth = await worker.fetch(post({ text: "hi" }, { token: null }), env);
  check("missing Authorization -> 401", noAuth.status === 401);

  const wrong = await worker.fetch(post({ text: "hi" }, { token: "wrong-token" }), env);
  check("wrong token -> 401", wrong.status === 401);

  const androidOnSms = await worker.fetch(post({ text: "hi" }, { token: ANDROID_TOKEN }), env);
  check("ANDROID_TOKEN rejected on /sms (tokens not interchangeable)", androidOnSms.status === 401);
}

console.log("\n--- 请求校验 ---");
{
  const wrongType = await worker.fetch(post({ text: "hi" }, { contentType: "text/plain" }), env);
  check("non-json content-type -> 400", wrongType.status === 400);

  const noText = await worker.fetch(post({ sender: "95588" }), env);
  check("missing text -> 400", noText.status === 400);

  const blankText = await worker.fetch(post({ text: "   " }), env);
  check("blank text -> 400", blankText.status === 400);

  const longText = await worker.fetch(post({ text: "a".repeat(8001) }), env);
  check("text > 8000 chars -> 400", longText.status === 400);

  const longSender = await worker.fetch(post({ text: "hi", sender: "s".repeat(201) }), env);
  check("sender > 200 chars -> 400", longSender.status === 400);

  const badJson = await worker.fetch(post("{not json", { raw: true }), env);
  check("malformed json -> 400", badJson.status === 400);

  const emptyBody = await worker.fetch(
    new Request(`${BASE}/sms`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${IPHONE_TOKEN}` },
    }),
    env
  );
  check("empty body -> 400", emptyBody.status === 400);

  const badUtf8 = await worker.fetch(
    post(new Uint8Array([0x7b, 0x22, 0xc3, 0x28, 0x22, 0x7d]), { raw: true }),
    env
  );
  check("invalid utf-8 -> 400 (not silently replaced)", badUtf8.status === 400);

  const getSms = await worker.fetch(
    new Request(`${BASE}/sms`, { headers: { Authorization: `Bearer ${IPHONE_TOKEN}` } }),
    env
  );
  check("GET /sms -> 405", getSms.status === 405);

  const notFound = await worker.fetch(new Request(`${BASE}/nope`), env);
  check("unknown path -> 404", notFound.status === 404);
}

console.log("\n--- 正常投递 ---");
{
  captured = null;
  const res = await worker.fetch(
    post({ messageId: "abc-123", sender: "95588", text: "您的验证码是123456", timestamp: 1726680000 }),
    env
  );
  const json = await res.json();
  check("valid request -> 202", res.status === 202, `got ${res.status}`);
  check("response echoes messageId", json.messageId === "abc-123");
  check("response carries delivered count", json.delivered === 1);
  check("response does not echo sms text", !JSON.stringify(json).includes("验证码"));
  check("DO received /deliver", captured && captured.url.endsWith("/deliver"));

  const envelope = captured?.body?.envelope;
  check("envelope type is sms", envelope?.type === "sms");
  check("envelope keeps original messageId", envelope?.messageId === "abc-123");
  check("envelope keeps text", envelope?.text === "您的验证码是123456");
  check("envelope keeps sender", envelope?.sender === "95588");
  check("timestamp normalized to string seconds", envelope?.timestamp === "1726680000");
}

console.log("\n--- messageId 缺失时的内容哈希兜底 ---");
{
  const first = await worker.fetch(post({ sender: "95588", text: "验证码123456", timestamp: 1726680000 }), env);
  const firstJson = await first.json();
  check("fallback id prefixed with h1-", String(firstJson.messageId).startsWith("h1-"), firstJson.messageId);
  check("fallback id is 64 hex + prefix", firstJson.messageId.length === 3 + 64);

  const second = await worker.fetch(post({ sender: "95588", text: "验证码123456", timestamp: 1726680000 }), env);
  check("same content -> same fallback id (dedup works)", (await second.json()).messageId === firstJson.messageId);

  const different = await worker.fetch(post({ sender: "95588", text: "验证码999999", timestamp: 1726680000 }), env);
  check("different text -> different id", (await different.json()).messageId !== firstJson.messageId);

  const otherSender = await worker.fetch(post({ sender: "10086", text: "验证码123456", timestamp: 1726680000 }), env);
  check("different sender -> different id", (await otherSender.json()).messageId !== firstJson.messageId);

  const otherTime = await worker.fetch(post({ sender: "95588", text: "验证码123456", timestamp: 1726680001 }), env);
  check("different timestamp -> different id", (await otherTime.json()).messageId !== firstJson.messageId);

  // 分隔符必须生效：否则 ("ab","c") 与 ("a","bc") 会碰撞。
  const collide1 = await worker.fetch(post({ sender: "ab", text: "c", timestamp: 1 }), env);
  const collide2 = await worker.fetch(post({ sender: "a", text: "bc", timestamp: 1 }), env);
  check(
    "NUL separator prevents field-boundary collision",
    (await collide1.json()).messageId !== (await collide2.json()).messageId
  );

  const longId = await worker.fetch(post({ messageId: "x".repeat(257), text: "hi" }), env);
  check("over-long messageId -> 400", longId.status === 400);
}

console.log("\n--- /ws 鉴权 ---");
{
  const noAuth = await worker.fetch(new Request(`${BASE}/ws`, { headers: { Upgrade: "websocket" } }), env);
  check("no token -> 401", noAuth.status === 401);

  const iphoneOnWs = await worker.fetch(
    new Request(`${BASE}/ws`, { headers: { Upgrade: "websocket", Authorization: `Bearer ${IPHONE_TOKEN}` } }),
    env
  );
  check("IPHONE_TOKEN rejected on /ws (tokens not interchangeable)", iphoneOnWs.status === 401);

  const noUpgrade = await worker.fetch(
    new Request(`${BASE}/ws`, { headers: { Authorization: `Bearer ${ANDROID_TOKEN}` } }),
    env
  );
  check("missing Upgrade header -> 426", noUpgrade.status === 426);
}

console.log(`\n===== ${pass} passed, ${fail} failed =====\n`);
process.exit(fail === 0 ? 0 : 1);
