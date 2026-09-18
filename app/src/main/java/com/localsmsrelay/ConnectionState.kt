package com.localsmsrelay

/** 中继服务的连接状态，供前台服务与界面共享。 */
enum class RelayConnectionState {
    /** 服务未启动。 */
    STOPPED,

    /** 正在建立 WebSocket 连接。 */
    CONNECTING,

    /** 已连接，可以接收短信。 */
    CONNECTED,

    /** 连接断开，正在按退避策略重试。 */
    RECONNECTING,

    /** 服务器返回 401，Token 或地址配置有误。 */
    UNAUTHORIZED,

    /** 局域网模式下的 HTTP 服务正在监听。 */
    LAN_LISTENING
}
