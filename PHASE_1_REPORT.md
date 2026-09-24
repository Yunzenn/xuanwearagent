# Phase 1 软件开发记录

状态：IN PROGRESS。当前已完成协议基础、身份持久化、Bootstrap HTTP 和 WebSocket 本地集成，尚未完成语音纵向链。

## Phase 1A 增量（历史快照；1B 更新见文末）

- XiaozhiProtocolV1：Hello/STT/LLM/TTS/MCP/未知事件解析，Hello/Listen/Abort 消息生成。
- Server Hello 播放参数必须显式合法；不猜采样率、不宣告 MCP/AEC。
- TransportStateMachine：连接、等待 Hello、Ready、断开；拒绝旧连接回调。
- ConversationStateMachine：手动监听、Thinking、Speaking、Interrupting；generation 和 TTS 生命周期控制接收许可。
- BootstrapResponseParser：解析 websocket 与 activation，activation 优先；诊断输出隐藏凭据；非 TLS WebSocket 需要显式开发选项。
- DeviceIdentityStore：随机本地管理 deviceId + UUID clientId，DataStore 原子持久化；并发获取保持一致，损坏报错不换号。Application 单例放在 noBackupFilesDir；Probe 新增 Identity 检查按钮。
- BootstrapRepository：真实 OkHttp POST，发送 Device-Id/Client-Id/Activation-Version；取消会取消 HTTP 调用，15 秒总超时、64 KiB 响应上限、禁用重定向和自动重试。开发 HTTP 需显式启用。
- 请求只描述 Android 客户端和应用版本，不伪造 ESP32 flash/heap/hardware 参数。实际服务端是否接受该 board 类型仍须联调确认。
- WebSocketTransport：OkHttp v1 adapter，带 token 时发送四个握手 Header；无 token 时不发送空 Authorization。连接开始后 10 秒内须收到合法 Server Hello；Ready 前禁发业务数据，非法/重复 Hello 或提前音频断开。
- 文本/二进制事件带 connectionId，旧 socket 回调被过滤；显式 disconnect/connect 重新握手，不重放音频。单消费者事件队列限制 64 条，满时断开并关闭 transport；调用方必须过滤重连前已排队的旧 connectionId 事件。
- 默认 WSS、禁用重定向；允许测试显式开启 WS。发送队列超过 256 KiB 时断开，音频单帧应用层上限 64 KiB。此限制在 OkHttp 完成帧接收后执行，不代表底层帧内存硬上限。

协议参考：冻结 `78/xiaozhi-esp32@64b57d0ba5c2f11a30974a0216dc28221c5b9d5b/docs/websocket.md`。
Bootstrap 字段参考：冻结 `mdloverm/rokid-xiaozhi@8e3c920808b113e6f26726db80cdf33f6370dee2` 的 `XiaozhiWebSocketClient.fetchConfig`。独立实现解析，未复制参考客户端代码。

## Phase 1A 验证（历史快照）

命令：`.\.tools\gradle-8.9\bin\gradle.bat test lint assembleDebug --offline --no-daemon --console=plain`

- 三模块 BUILD SUCCESSFUL；139 tasks，17 executed、122 up-to-date。追加组合测试后单独运行 `:core-protocol:test --offline --no-daemon --console=plain`，BUILD SUCCESSFUL。
- JVM 测试：ProtocolTest 6 + BootstrapTest 4 + IdentityAndBootstrapTest 8 + WebSocketTransportTest 7，25/25 通过，无失败/错误/跳过。
- WebSocket 测试：四个 Header、16k Hello/24k 下行配置、双向文本/二进制、Hello 超时、非法 Hello/提前音频、显式重连、401、明文拒绝、关闭后不可复用，以及 DataStore→Bootstrap→WebSocket→Hello 组合路径。
- 新增测试使用真实 DataStore 文件与 MockWebServer 回环 HTTP，覆盖并发创建、重新打开存储、损坏不覆盖、独立安装 ID、POST header/body、激活响应、401、超大响应、重定向禁用、取消和明文端点拒绝。
- 原 accumulator 测试未修改，本次复用 up-to-date 结果；不宣称重新执行。
- app/core-audio lint：No issues found。
- 已生成当前增量 APK：`app/build/outputs/apk/debug/app-debug.apk`，它已是新构建，不再声称与 Phase 0B APK 哈希一致。

## Phase 1A 当时的验收边界（1B 已更新部分条目）

用户确认暂无真实服务端地址。已进行 MockWebServer HTTP/WebSocket 回环集成，尚未验证 WSS/TLS 真实部署、真实 Xiaozhi、模拟器或手机。重新打开 DataStore 的 JVM 测试不等于 Android 进程重启验收。
以上为 Phase 1A 历史记录；Phase 1B 最新结果见下方及 PHASE_1B_REPORT.md。网络组件尚未接入完整应用会话入口。
DataStore 1.1.1 Android 制品带有 libdatastore_shared_counter.so；本轮未按目标机猜测或裁剪 ABI。APK 打包有无法剥离此库调试符号的提示，构建成功；目标 ABI 安装测试仍待设备。
Phase 1A 当时尚未实现真实音频线程、播放队列 flush、自动重连、端到端对话；Phase 1B 已补会话协调器及自动重连，真实音频仍未实现。
v1 没有 turn ID：generation 仅能拒绝已带旧标记的回调/队列，不能分辨同一连接上所有迟到音频；网络层接入前须确定 abort 后隔离策略。TTS stop 表示网络音频结束，实际已入队 PCM 的 drain/flush 由后续播放器处理。

C1/C2/C3 为 TARGET VALIDATION PENDING；C4 为 STATIC PASS / Runtime pending。Phase 1 软件验收不代表 CD12Max 验收。

## Phase 1B 增量（2026-09-25）

SessionCoordinator 已统一拥有连接/epoch/generation、串行会话状态、有限重试和认证刷新；旧连接的排队事件集中丢弃。验证码激活采用冻结 Android 参考客户端的轮询方式；challenge-only 硬件 HMAC 模式明确不支持，不伪造完成。

API 28 x86_64 模拟器实际安装和 force-stop/不同进程身份一致性已通过；APK 四种 native ABI 清单已审计。新增用户确认后的身份重置，保留原文件；TLS 不可信证书负向测试通过。

最新 clean build：38 个 core-protocol 测试、accumulator debug/release 各 4 个测试通过，0 跳过；lint 0。具体命令、PID、APK 哈希、设计限制及未完成项见 PHASE_1B_REPORT.md。
