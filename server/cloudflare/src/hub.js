/**
 * SMS-L 云端中继 — Durable Object（Android 连接中枢）。
 *
 * 职责：
 *   1. 持有 Android 的 WebSocket 长连接（Hibernation API，空闲不计费）。
 *   2. 维护一个小型待确认队列，保证短暂断网后消息能补发。
 *   3. 收到 Android 的 ack 后从队列移除，避免无意义重放。
 *
 * 全局只有一个实例（index.js 里固定用 idFromName("hub")），
 * 因为设计目标就是"一个 Android 设备"。
 *
 * 安全约定：本文件不得输出包含短信正文、发件人或 Token 的日志。
 */

const STORAGE_KEY = "pending";
const MAX_PENDING = 50;
const PENDING_TTL_MS = 24 * 60 * 60 * 1000;

const CLOSE_NORMAL = 1000;
const CLOSE_ERROR = 1011;

export class AndroidHub {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    /** @type {Array<{id: string, ts: number, envelope: object}> | null} */
    this.queue = null;
    this.loading = null;
  }

  async fetch(request) {
    const upgrade = (request.headers.get("Upgrade") ?? "").trim().toLowerCase();
    if (upgrade === "websocket") return this.handleUpgrade();

    const url = new URL(request.url);
    if (url.pathname === "/deliver") return this.handleDeliver(request);

    return new Response("not_found", { status: 404 });
  }

  /** Android 建连：接受连接并把队列里所有未确认的消息立刻重放。 */
  async handleUpgrade() {
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];

    // Hibernation API：连接可以跨越 Durable Object 的休眠，且休眠期间不产生计费时长。
    this.state.acceptWebSocket(server);

    const queue = await this.loadQueue();
    for (const entry of queue) {
      try {
        server.send(JSON.stringify(entry.envelope));
      } catch {
        // 连接刚建立就失效时直接放弃，重连后会再次重放。
        break;
      }
    }

    return new Response(null, { status: 101, webSocket: client });
  }

  /** Worker 投递消息：入队（去重）并推给所有在线连接。 */
  async handleDeliver(request) {
    let body;
    try {
      body = await request.json();
    } catch {
      return jsonResponse(400, { ok: false, error: "invalid_json" });
    }

    const envelope = body?.envelope;
    if (!envelope || typeof envelope !== "object" || typeof envelope.messageId !== "string") {
      return jsonResponse(400, { ok: false, error: "invalid_envelope" });
    }

    const queue = await this.loadQueue();
    if (!queue.some((entry) => entry.id === envelope.messageId)) {
      queue.push({ id: envelope.messageId, ts: Date.now(), envelope });
      while (queue.length > MAX_PENDING) queue.shift();
      await this.persist();
    }

    let delivered = 0;
    const frame = JSON.stringify(envelope);
    for (const socket of this.state.getWebSockets()) {
      try {
        socket.send(frame);
        delivered += 1;
      } catch {
        // 连接正在关闭，跳过即可；消息仍留在队列里等待重放。
      }
    }

    return jsonResponse(200, { ok: true, delivered });
  }

  /** Android 写入 Room 成功后回发 ack，收到即出队。 */
  async webSocketMessage(socket, message) {
    if (typeof message !== "string") return;

    let parsed;
    try {
      parsed = JSON.parse(message);
    } catch {
      return;
    }
    if (!parsed || parsed.type !== "ack" || typeof parsed.messageId !== "string") return;

    const queue = await this.loadQueue();
    const remaining = queue.filter((entry) => entry.id !== parsed.messageId);
    if (remaining.length !== queue.length) {
      this.queue = remaining;
      await this.persist();
    }
  }

  async webSocketClose(socket, code, reason) {
    try {
      socket.close(code === CLOSE_NORMAL ? CLOSE_NORMAL : CLOSE_ERROR, reason);
    } catch {
      // 连接可能已经被对端关闭。
    }
  }

  async webSocketError(socket) {
    try {
      socket.close(CLOSE_ERROR, "error");
    } catch {
      // 同上。
    }
  }

  /** 首次访问时从 storage 载入，并顺手清理过期条目。 */
  async loadQueue() {
    if (this.queue) return this.queue;
    if (!this.loading) {
      this.loading = (async () => {
        let stored = null;
        try {
          stored = await this.state.storage.get(STORAGE_KEY);
        } catch {
          stored = null;
        }
        const list = Array.isArray(stored) ? stored : [];
        const now = Date.now();
        const alive = list.filter(
          (entry) =>
            entry &&
            typeof entry.id === "string" &&
            typeof entry.ts === "number" &&
            now - entry.ts <= PENDING_TTL_MS
        );
        this.queue = alive;
        if (alive.length !== list.length) await this.persist();
        return this.queue;
      })();
    }
    return this.loading;
  }

  async persist() {
    try {
      await this.state.storage.put(STORAGE_KEY, this.queue ?? []);
    } catch {
      // 写失败时保留内存队列，下次 ack 或投递会再次尝试写入。
    }
  }
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
