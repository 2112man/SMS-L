/**
 * Durable Object 逻辑测试（无第三方依赖，直接用 node 运行）。
 *
 *   cd server/cloudflare && node test/hub.test.mjs
 *
 * Cloudflare 的 WebSocketPair / 101 响应在 Node 中不可用，测试用轻量替身模拟，
 * 因此验证的是队列、重放、ack、上限与过期清理这些业务逻辑。
 */
import { pathToFileURL } from "node:url";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const HUB = process.argv[2] ?? resolve(here, "../src/hub.js");

// Node 的 Response 不接受 101 状态码，而 Cloudflare 接受。
// 用一个工厂函数替换，101 时返回轻量对象，其余走真实 Response。
const RealResponse = globalThis.Response;
globalThis.Response = function Response(body, init) {
  if (init && init.status === 101) {
    return { __upgrade101: true, status: 101, webSocket: init.webSocket };
  }
  return new RealResponse(body, init);
};

class MockSocket {
  constructor(name) {
    this.name = name;
    this.sent = [];
    this.closed = null;
  }
  send(frame) {
    this.sent.push(frame);
  }
  close(code, reason) {
    this.closed = { code, reason };
  }
}

// Cloudflare 的 WebSocketPair 是 {0: client, 1: server}
globalThis.WebSocketPair = function WebSocketPair() {
  const client = new MockSocket("client");
  const server = new MockSocket("server");
  const pair = [client, server];
  pair.client = client;
  pair.server = server;
  return pair;
};

const { AndroidHub } = await import(pathToFileURL(HUB).href);

function makeState(seed) {
  const store = new Map(seed ? [["pending", seed]] : []);
  const sockets = [];
  return {
    store,
    sockets,
    acceptWebSocket: (ws) => sockets.push(ws),
    getWebSockets: () => sockets,
    storage: {
      get: async (key) => store.get(key),
      put: async (key, value) => {
        store.set(key, JSON.parse(JSON.stringify(value)));
      },
    },
  };
}

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

const deliver = (hub, envelope) =>
  hub.fetch(
    new Request("https://hub.internal/deliver", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ envelope }),
    })
  );

const upgrade = (hub) =>
  hub.fetch(new Request("https://hub.internal/ws", { headers: { Upgrade: "websocket" } }));

const envelope = (id) => ({
  type: "sms",
  messageId: id,
  sender: "95588",
  text: "验证码123456",
  timestamp: "1",
});

console.log("\n--- 建连与重放 ---");
{
  const state = makeState();
  const hub = new AndroidHub(state, {});
  const res = await upgrade(hub);
  check("upgrade returns 101", res.status === 101);
  check("101 carries client socket", !!res.webSocket);
  check("server socket was accepted", state.sockets.length === 1);
  check("no replay when queue empty", state.sockets[0].sent.length === 0);
}

console.log("\n--- 投递 ---");
{
  const state = makeState();
  const hub = new AndroidHub(state, {});

  const offline = await deliver(hub, envelope("m1"));
  check("deliver with no socket -> delivered 0", (await offline.json()).delivered === 0);
  check("deliver with no socket still queued", (await state.storage.get("pending")).length === 1);

  await upgrade(hub);
  const socket = state.sockets[0];
  check("queued message replayed on connect", socket.sent.length === 1);
  const replayed = JSON.parse(socket.sent[0]);
  check("replayed frame keeps messageId", replayed.messageId === "m1");
  check("replayed frame keeps text", replayed.text === "验证码123456");

  const online = await deliver(hub, envelope("m2"));
  check("deliver with socket -> delivered 1", (await online.json()).delivered === 1);
  check("socket received live frame", socket.sent.length === 2);
}

console.log("\n--- 幂等与 ack ---");
{
  const state = makeState();
  const hub = new AndroidHub(state, {});
  await upgrade(hub);
  const socket = state.sockets[0];

  await deliver(hub, envelope("m1"));
  await deliver(hub, envelope("m1"));
  const queued = await state.storage.get("pending");
  check("same messageId queued once", queued.filter((e) => e.id === "m1").length === 1);
  check("but delivered twice (client dedups)", socket.sent.length === 2);

  await hub.webSocketMessage(socket, JSON.stringify({ type: "ack", messageId: "m1" }));
  check("ack removes from queue", (await state.storage.get("pending")).length === 0);

  const state2 = makeState(state.store.get("pending"));
  const hub2 = new AndroidHub(state2, {});
  await upgrade(hub2);
  check("no replay after ack", state2.sockets[0].sent.length === 0);

  await hub.webSocketMessage(socket, "not json at all");
  await hub.webSocketMessage(socket, JSON.stringify({ type: "unknown" }));
  await hub.webSocketMessage(socket, JSON.stringify({ type: "ack" }));
  check("malformed/unknown frames are ignored without throwing", true);
}

console.log("\n--- 断线重连补发 ---");
{
  const state = makeState();
  const hub = new AndroidHub(state, {});
  await deliver(hub, envelope("offline-1"));
  const res = await upgrade(hub);
  check("reconnect replays offline message", state.sockets[0].sent.length === 1);
  check("reconnect still returns 101", res.status === 101);
}

console.log("\n--- 队列上限与过期 ---");
{
  const state = makeState();
  const hub = new AndroidHub(state, {});
  for (let i = 0; i < 60; i += 1) await deliver(hub, envelope(`cap-${i}`));
  const queued = await state.storage.get("pending");
  check("queue capped at 50", queued.length === 50, `got ${queued.length}`);
  check("oldest dropped, newest kept", queued[queued.length - 1].id === "cap-59");

  const now = Date.now();
  const stale = [
    { id: "fresh", ts: now - 1000, envelope: envelope("fresh") },
    { id: "stale", ts: now - 25 * 60 * 60 * 1000, envelope: envelope("stale") },
  ];
  const state3 = makeState(stale);
  const hub3 = new AndroidHub(state3, {});
  await upgrade(hub3);
  const replayed = state3.sockets[0].sent.map((f) => JSON.parse(f).messageId);
  check("entries older than 24h pruned", !replayed.includes("stale"), JSON.stringify(replayed));
  check("fresh entries survive", replayed.includes("fresh"));
}

console.log("\n--- 非法输入 ---");
{
  const state = makeState();
  const hub = new AndroidHub(state, {});

  const badJson = await hub.fetch(
    new Request("https://hub.internal/deliver", { method: "POST", body: "{oops" })
  );
  check("malformed deliver body -> 400", badJson.status === 400);

  const noId = await deliver(hub, { type: "sms", text: "hi" });
  check("envelope without messageId -> 400", noId.status === 400);

  const unknownPath = await hub.fetch(new Request("https://hub.internal/other"));
  check("unknown DO path -> 404", unknownPath.status === 404);
}

console.log("\n--- 多连接 ---");
{
  const state = makeState();
  const hub = new AndroidHub(state, {});
  await upgrade(hub);
  await upgrade(hub);
  check("two sockets accepted", state.sockets.length === 2);
  const res = await deliver(hub, envelope("multi"));
  check("broadcast to all sockets", (await res.json()).delivered === 2);
  check("socket A got frame", state.sockets[0].sent.some((f) => JSON.parse(f).messageId === "multi"));
  check("socket B got frame", state.sockets[1].sent.some((f) => JSON.parse(f).messageId === "multi"));
}

console.log("\n--- 关闭处理 ---");
{
  const state = makeState();
  const hub = new AndroidHub(state, {});
  await upgrade(hub);
  const socket = state.sockets[0];
  await hub.webSocketClose(socket, 1000, "bye");
  check("normal close keeps 1000", socket.closed.code === 1000);
  await hub.webSocketError(socket);
  check("error close uses 1011", socket.closed.code === 1011);
}

console.log(`\n===== ${pass} passed, ${fail} failed =====\n`);
process.exit(fail === 0 ? 0 : 1);
