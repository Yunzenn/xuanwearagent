# Phase 1B — Session orchestration & resilience

日期：2026-09-25。状态：本地软件验证通过，真实服务端/应用会话集成仍待完成；不宣称 Phase 1 全部 PASS。Phase 1C 未开始。

后续审查：用户已批准 Phase 1B。以下是当时的历史快照；Phase 1C 已开始，并将 retry budget 改为连续稳定 Ready 60 秒后恢复，提取 HardInterruptPolicy 与 ActivationPolicy。最新状态见 PHASE_1C_REPORT.md。

## 实现与约束

- `SessionCoordinator` 单 actor 统一管理 Bootstrap、连接、ConversationStateMachine、epoch/connectionId/generation。生产 Transport 继续单连接，不承担重连策略。
- 网络故障/Hello timeout 使用有界退避：上限 1/2/4/8/15 秒，每次 jitter 为上限的 50%–100%；最多 5 次，当前 start 生命周期内预算不因 Ready 自动清零，避免抖动无限重试。
- 401/403 从 WebSocket 回 Bootstrap 一次，再次认证失败进入 AUTH_REQUIRED；Bootstrap 401/403 直接 AUTH_REQUIRED。TLS、协议错误、队列过载终止，不无限重试。显式退出取消计时器、接收和 Bootstrap。
- 验证码模式：ACTIVATING 展示 code，每 3 秒重新 Bootstrap，最多 60 次；得到 credentials 后连接并等待合法 Hello 才 Ready。此为 Android 参考客户端模式，不把 `timeout_ms` 当成 ESP32 challenge 请求已完成；challenge-only 明确 unsupported。暂无真实服务端，不能宣称真实绑定成功。
- callback/队列统一在 Coordinator 入口过滤旧 epoch 与 connectionId；下游只接当前事件。异步音频 sink 将来仍须给自身队列打 generation 并实现同步 flush，不应自行订阅原始 Transport。
- 消费者抛错会关闭 Transport 并进入诊断错误态，stop/close 不无限等待。
- 身份损坏默认报错；Probe 重置按钮明确提示重新绑定，只有确认后才结束旧 DataStore、保留旧文件并创建新身份。未来接入活动会话时，必须先 stop coordinator，再允许重置；当前 Probe 尚无活动会话入口。

## 打断契约（Phase 1C 前固定）

`generation++ → 禁止下行 → flushAudio（清 encoded/PCM 队列及 AudioTrack pause/flush）→ send abort → retire socket → Bootstrap/new socket/Hello → 新 listen/stop → 新 tts:start 才接音频`。

v1 不携带 turn ID，因此选择更换连接，不能承诺保留服务端同一 session 的上下文。abort 发送是 best effort，随后取消 socket 不保证服务端收到；本地隔离不依赖对端确认。真实 AudioTrack 尚未实现，测试只验证 flush 回调顺序，不能据此宣称听感或音频中断验收通过。正常 TTS stop 只关闭网络接收，后续播放器仍需处理 drain。

## 自动验证

命令：

```powershell
.\.tools\gradle-8.9\bin\gradle.bat clean test lint assembleDebug :app:assembleDebugAndroidTest --offline --no-daemon --console=plain
.\scripts\verify-identity-process.ps1 -Serial emulator-5556
```

- BUILD SUCCESSFUL，167 tasks：162 executed / 5 up-to-date。
- core-protocol：38 tests，0 failures/errors/skipped（Protocol 6、Bootstrap 4、Identity/HTTP 10、WebSocket 8、Coordinator 10）。
- core-audio：4 个 accumulator 测试分别在 debug/release 执行，共 8 次，未删改/弱化测试。
- app/core-audio lint：No issues found。
- TLS 负向测试：真实本地 TLS 握手，不可信自签证书被默认客户端拒绝，未进入 Ready。
- Coordinator：激活轮询成功/耗尽/不支持 challenge、网络重连与旧连接事件、显式停止、认证刷新上限、协议/TLS 不重试、重试预算、abort 顺序/音频 gate、身份错误、消费者异常。
- Android API 28 / x86_64 模拟器实际安装 App 和 instrumentation APK；force-stop 前后 PID 5592 / 5637，设备身份一致。没有 clear-data/reset。已脱敏，不记录身份值。
- instrumentation APK 编译与运行通过；测试运行两次，各 1 个测试。宿主脚本验证不同 PID，而非仅 DataStore reopen。

## APK native ABI 审计

APK：`app/build/outputs/apk/debug/app-debug.apk`

SHA256：`E9B8F85348C2EA28588EDD2823825C68F80B6970BF2437491BFC3634C10B81E7`

| ABI | native library | bytes |
|---|---|---:|
| arm64-v8a | libdatastore_shared_counter.so | 7112 |
| armeabi-v7a | libdatastore_shared_counter.so | 4416 |
| x86 | libdatastore_shared_counter.so | 5148 |
| x86_64 | libdatastore_shared_counter.so | 6224 |

所有库均保留，未根据猜测裁剪 ABI。构建提示此库无法 strip，按原样打包；这不是 lint 错误。仅 x86_64 API 28 完成安装/执行，ARM 和 CD12Max 安装兼容性未验证。

## 来源与边界

Activation 参考冻结 `mdloverm/rokid-xiaozhi@8e3c920808b113e6f26726db80cdf33f6370dee2` 的 MainActivity.showActivationCode / XiaozhiWebSocketClient.fetchConfig：验证码展示后轮询配置。
对照 `78/xiaozhi-esp32@64b57d0ba5c2f11a30974a0216dc28221c5b9d5b/main/ota.cc` 的 Ota::Activate / GetActivationPayload：硬件 challenge/HMAC 与 Android 验证码模式不是同一机制。本轮独立实现，没有复制上游源码。

剩余：真实 OTA/Activation/WSS 服务端联调、完整应用会话入口、参考手机音频、Phase 1C 音频队列及端到端对话。C1/C2/C3 继续 TARGET VALIDATION PENDING；C4 STATIC PASS / Runtime pending。模拟器结果不升级 CD12Max Gate。
