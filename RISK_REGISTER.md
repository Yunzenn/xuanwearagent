# Risk Register

| 风险 | 等级 | 处置 |
|---|---|---|
| CD12Max 实际 ABI/GL/音频能力未知 | P0C BLOCKED | 2026-09-25 ADB 无设备；先完成 Device Probe，禁止提前锁定方案 |
| Cubism Core 或模型授权不适合分发 | P0 | Gate B 前完成 SDK/资产条款审查 |
| Server hello 与实际 TTS Opus 采样率不一致 | P0C STATIC PASS / Runtime pending | 冻结 commit 的 bug 已由先红后绿测试复现并修复；Server Hello 与 TTS encoder 均由 `conn.sample_rate` 驱动，预建 encoder 有一致性守卫；仍需真实 TTS Opus 24k 解码验证后关闭 |
| OTA/activation 字段与参考客户端漂移 | P0 | 从 rokid-xiaozhi 抽取并做契约测试 |
| WebSocket v1 二进制无 turn id 导致旧音频回放 | P0 | generation + lifecycle gate + 本地优先 abort |
| 本地无 Android 工程基线 | CLOSED | Phase 0B 三模块 skeleton、clean build、单元测试及 lint 已通过，审查已通过 |
| GitHub 仓库动态变化 | P1 | 记录审计日期、commit/ref；依赖版本固定 |
| 本机构建环境缺失 | CLOSED | 已安装 Platform 35、Build Tools 35.0.0、ADB 37.0.1；Gradle 8.9 clean build 已通过 |
| targetSdk 35 在 Android 9/新系统上的行为差异 | P0C | minSdk 已固定 28；真机 Gate 同时验证 Android 9 行为，产品 targetSdk 在后续发布策略中复核 |
| IndexTTS 固定 24k 与可配置 server playback rate 再次分叉 | MITIGATED / Runtime pending | `open_audio_channels()` 已在启动音频线程前校验预建 encoder rate 与 `conn.sample_rate`；不一致明确失败，不再静默错配；长期可增加 provider 显式输出契约 |
