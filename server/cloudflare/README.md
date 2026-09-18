# SMS-L 云端中继（Cloudflare Worker + Durable Object）

把 iPhone 快捷指令的 HTTPS POST 实时推送给 Android 上的 SMS-L。
Android 主动建立 WSS 长连接，**不需要公网 IP、不需要开放端口、不需要 VPS、不依赖第三方推送服务**。

```
iPhone 快捷指令 ──HTTPS POST /sms──▶ Cloudflare Worker
                                          │
                                          ▼
                                   Durable Object（AndroidHub）
                                          │  WebSocket 推送
                                          ▼
                                     Android SMS-L ──▶ 系统通知
```

## 目录结构

```
server/cloudflare/
├── src/
│   ├── index.js      Worker 入口：路由、Token 校验、内容哈希兜底
│   └── hub.js        Durable Object：WebSocket 管理、待确认队列、重放与 ack
├── test/
│   ├── worker.test.mjs  Worker 逻辑测试（36 项）
│   └── hub.test.mjs     Durable Object 逻辑测试（31 项）
├── wrangler.toml     Durable Object 绑定与迁移声明
├── package.json      部署依赖与脚本
└── README.md         本文件
```

## 通信协议

### iPhone → Worker

```http
POST /sms
Authorization: Bearer <IPHONE_TOKEN>
Content-Type: application/json
```

```json
{
  "messageId": "唯一ID（可选，缺失时服务端用内容哈希兜底）",
  "sender": "95588",
  "text": "您的验证码是123456",
  "timestamp": 1726680000
}
```

响应：

| 状态码 | 含义 |
| --- | --- |
| `202` | 已投递，返回 `{"ok":true,"messageId":"...","delivered":1}` |
| `400` | JSON / Content-Type / 字段非法（含非 UTF-8 正文） |
| `401` | IPHONE_TOKEN 错误 |
| `405` | 方法不是 POST |
| `502` | Durable Object 暂时不可用 |

限制：请求体最大 16 KiB，`text` 最多 8000 字符，`sender`/`messageId` 最多 200/256 字符。

### Worker → Android（WebSocket 下行）

```json
{ "type": "sms", "messageId": "唯一ID", "sender": "95588", "text": "您的验证码是123456", "timestamp": "1726680000" }
```

### Android → Worker（WebSocket 上行）

```json
{ "type": "ack", "messageId": "唯一ID" }
```

### Android 建连

```http
GET /ws
Authorization: Bearer <ANDROID_TOKEN>
Upgrade: websocket
```

## 可靠性设计

**不丢消息**：Worker 把消息写入 Durable Object 的待确认队列（上限 50 条，保留 24 小时）。
Android 断线期间到达的消息留在队列里，重连后立即重放。

**不重复通知**：Android 每成功写入一条消息都会回发 `ack`，DO 收到即出队。
即使 Android 在 ack 之前崩溃，重放的消息也会被 Android 侧按 `messageId` 幂等去重（Room 唯一索引）。
两层配合才能同时满足"不丢"和"不重"。

**messageId 兜底**：快捷指令若给不出唯一值，服务端用 `SHA-256(sender + NUL + text + NUL + timestamp秒)`
生成确定性 ID（前缀 `h1-`）。Android 端实现了完全相同的算法，两端结果一致。

## 免费额度说明

- Workers 免费计划每天 10 万次请求，短信量级下远超需求。
- **Durable Objects 在免费计划下必须使用 SQLite 存储后端**，因此 `wrangler.toml` 里用的是
  `new_sqlite_classes` 而不是 `new_classes`。写成后者会在部署时失败。
- DO 使用 Hibernation API（`state.acceptWebSocket`），空闲休眠期间不产生计费时长。

## 部署步骤

### 1. 安装依赖

```bash
cd server/cloudflare
npm install
```

### 2. 登录 Cloudflare

```bash
npx wrangler login
```

浏览器会打开授权页面，确认即可。

### 3. 生成两个互不通用的 Token

本地生成，不要用弱口令：

```bash
# Linux / macOS
openssl rand -base64 32

# Windows PowerShell
[Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 }))
```

生成两份，一份给 iPhone，一份给 Android，**两者绝不能相同**。

### 4. 写入 Worker Secrets

```bash
npx wrangler secret put IPHONE_TOKEN     # 粘贴第一份
npx wrangler secret put ANDROID_TOKEN    # 粘贴第二份
```

Secrets 只存在于 Cloudflare 侧，不会进入仓库，也不会出现在 `wrangler.toml` 里。

### 5. 部署

```bash
npx wrangler deploy
```

部署成功后会输出形如 `https://sms-l-relay.<你的子域>.workers.dev` 的地址。

### 6. 验证部署

```bash
# 健康检查，应返回 {"ok":true,"service":"sms-l-relay"}
curl https://sms-l-relay.<你的子域>.workers.dev/health

# 未带 Token 的请求应返回 401
curl -i -X POST https://sms-l-relay.<你的子域>.workers.dev/sms \
  -H "Content-Type: application/json" -d '{"text":"test"}'
```

### 7. 自定义域名（可选）

`workers.dev` 子域已经够用。若要用自己的域名，在 Cloudflare 控制台的
**Workers & Pages → 你的 Worker → Settings → Domains & Routes** 里添加 Custom Domain 即可，
不需要改动本目录任何文件。

## 本地开发

```bash
npx wrangler dev
```

此时 Android 端服务器地址填 `ws://127.0.0.1:8787/ws`。
`wrangler dev` 默认使用本地模拟的 Durable Object，不会影响线上数据。

> 生产环境必须使用 `wss://`。`ws://` 明文只建议在本机调试时使用。

## 测试

不需要安装任何依赖（只用 Node 内置能力），直接运行：

```bash
npm test
```

会依次跑 Worker 与 Durable Object 两组逻辑测试，覆盖：

- 双 Token 鉴权、Token 不可互换、缺失 Bearer 的处理
- Content-Type / 超长正文 / 非法 UTF-8 / 非法 JSON 的拒绝
- 内容哈希兜底的确定性与字段敏感性、NUL 分隔符防碰撞
- 队列重放、ack 出队、重复 messageId 只入队一次
- 队列上限 50 条、24 小时过期清理
- 多连接广播与 WebSocket 关闭码映射

改动 `src/` 后请先跑通这些测试再部署。

> 说明：测试用轻量替身模拟 Cloudflare 特有的 `WebSocketPair` 与 101 响应，
> 因此验证的是业务逻辑本身，不能替代在真实 Cloudflare 环境下的联调。

## 日志与隐私

- 本目录代码**不输出任何包含短信正文、发件人或 Token 的日志**，改动时请保持这条约束。
- `wrangler.toml` 中已关闭 Workers 日志推送（`[observability] enabled = false`）。
- `wrangler tail` 用于排障时，只会看到状态码层面的信息。

## 故障排查

| 现象 | 排查方向 |
| --- | --- |
| 部署报 Durable Object 迁移错误 | 确认用的是 `new_sqlite_classes`；若之前用 `new_classes` 部署过，需要更换 `tag` 或删除旧 Worker |
| POST /sms 返回 401 | `Authorization` 头格式必须是 `Bearer <token>`，且与 `IPHONE_TOKEN` 完全一致（注意首尾空格） |
| POST /sms 返回 502 | Durable Object 不可用；查看 `npx wrangler tail` 的日志 |
| Android 连不上 /ws | 同样先确认 Token；再确认地址是 `wss://.../ws` |
| 消息收到但重复 | 检查 Android 是否成功回发 ack；即使回发失败也应被 Room 唯一索引拦下，若仍重复请查看 Android 侧日志 |
| 长时间离线后重连 | 最多补发最近 50 条、24 小时内的消息；更早的消息不会补发 |
