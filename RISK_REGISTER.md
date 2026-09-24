# Risk Register

| 风险 | 等级 | 处置 |
|---|---|---|
| CD12Max 实际 ABI/GL/音频能力未知 | P0C TARGET VALIDATION PENDING | 2026-09-25 ADB 无设备；先完成 Device Probe，禁止提前锁定方案 |
| Cubism Core 或模型授权不适合分发 | P0 | Gate B 前完成 SDK/资产条款审查 |
| Server hello 与实际 TTS Opus 采样率不一致 | P0C STATIC PASS / Runtime pending | 冻结 commit 的 bug 已由先红后绿测试复现并修复；Server Hello 与 TTS encoder 均由 `conn.sample_rate` 驱动，预建 encoder 有一致性守卫；仍需真实 TTS Opus 24k 解码验证后关闭 |
| OTA/activation 字段与参考客户端漂移 | P0 | 从 rokid-xiaozhi 抽取并做契约测试 |
| WebSocket v1 二进制无 turn id 导致旧音频回放 | MITIGATED / Audio pending | SessionCoordinator 中央连接/epoch 过滤；abort 先 flush、再发送、再更换连接隔离迟到轮次。真实编码/PCM 队列与 AudioTrack flush 留到 1C；TTS stop 不等于播放完毕 |
| 激活模式与真实服务端不匹配 | Runtime pending | 支持验证码展示/有界 Bootstrap 轮询至 credentials；challenge-only 明确失败，不伪造 ESP32 HMAC；暂无服务器地址 |
| 身份存储损坏导致重新绑定 | MITIGATED | 默认报错不换号；只有明确确认后重置，保存原始文件。API 28 两次独立进程持久化通过 |
| Coordinator 尚未接入产品会话入口 | OPEN | 本轮单元/回环验证不等于完整 App 或真实语音 E2E；音频 sink 当前为契约回调 |
| 本地无 Android 工程基线 | CLOSED | Phase 0B 三模块 skeleton、clean build、单元测试及 lint 已通过，审查已通过 |
| GitHub 仓库动态变化 | P1 | 记录审计日期、commit/ref；依赖版本固定 |
| 本机构建环境缺失 | CLOSED | 已安装 Platform 35、Build Tools 35.0.0、ADB 37.0.1；Gradle 8.9 clean build 已通过 |
| targetSdk 35 在 Android 9/新系统上的行为差异 | P0C | minSdk 已固定 28；真机 Gate 同时验证 Android 9 行为，产品 targetSdk 在后续发布策略中复核 |
| IndexTTS 固定 24k 与可配置 server playback rate 再次分叉 | MITIGATED / Runtime pending | `open_audio_channels()` 已在启动音频线程前校验预建 encoder rate 与 `conn.sample_rate`；不一致明确失败，不再静默错配；长期可增加 provider 显式输出契约 |
