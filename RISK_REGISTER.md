# Risk Register

| 风险 | 等级 | 处置 |
|---|---|---|
| LIVE2D-ARM64-16K | BLOCKED BY UPSTREAM / RELEASE BLOCKER | ARM64 RELRO静态FAIL；与P2B-RUNTIME分离，不无限阻止4KB开发。llvm-readelf复核待做；不改闭源SO、不从其他runtime偷换Core |
| R5 Core ARM64/x86 RELRO末端未16KB对齐 | OPEN / STATIC FAIL | LOAD段均通过但RELRO余数非0；未做运行复现，不修改闭源SO、不自动换版。详见 LIVE2D_BINARY_AUDIT.md；最终APK及16KB环境仍待验 |
| CD12Max 实际 ABI/GL/音频能力未知 | P0C TARGET VALIDATION PENDING | 2026-09-25 ADB 无设备；先完成 Device Probe，禁止提前锁定方案 |
| Cubism Core 或模型授权不适合分发 | P0 | Gate B 前完成 SDK/资产条款审查 |
| 当前 Cubism Java 已移除 ARMv7 | P2B-ABI-GATE OPEN | 官方声明 ARM64/x86/x86_64；实际 AAR 未审计，CD12Max ABI 未知；若仅 armeabi-v7a，审计配套旧官方 release，禁止自研 renderer |
| Cubism Core 制品配套未核验 | P2B-0 OPEN | 用户独立复核 Samples 冻结 commit 与 R5 tag 的 tree 均为 b48b56c447adc7738b84a36047ae635a9f384f59，Framework gitlink 相同，无须迁移源码；Core 仍须独立核验官方来源、ZIP/AAR哈希、API/minSdk、ELF/ABI、16KB及许可 |
| EXPANDABLE_APPLICATION | RELEASE BLOCKER | 用户导入任意模型涉及可扩展应用分类；发布前由 Live2D 确认分类并取得适用特殊 Publication License，不套用个人/小规模一般豁免。独立于开发和 Runtime Gate；见 PHASE_2B_REUSE_GATE.md 官方来源 |
| 模型导入覆盖旧 Avatar / ZIP 资源耗尽 | DESIGN MITIGATED / IMPLEMENTATION PENDING | 不原样复制 Wanyu 先删除旧目录逻辑；staging、有界输入/展开、路径/引用/纹理检查、Core load 后原子激活 |
| SDK 与自定义驱动重复更新 / 嘴型超前播放 | DESIGN MITIGATED / IMPLEMENTATION PENDING | 官方 updater 单一所有权；既有 PCM 唯一音源；嘴型对齐播放并随 flush/generation 清空 |
| Manager 与设备 token 混用 / 本地人格双事实源 | STATIC AUDIT COMPLETE / Runtime pending | Manager 查用户 token 与 Agent 所有权；HTTP200仍可能业务认证失败；本地只展示覆盖，voice/systemPrompt以服务端为准 |
| Server hello 与实际 TTS Opus 采样率不一致 | P0C STATIC PASS / Runtime pending | 冻结 commit 的 bug 已由先红后绿测试复现并修复；Server Hello 与 TTS encoder 均由 `conn.sample_rate` 驱动，预建 encoder 有一致性守卫；仍需真实 TTS Opus 24k 解码验证后关闭 |
| OTA/activation 字段与参考客户端漂移 | P0 | 从 rokid-xiaozhi 抽取并做契约测试 |
| WebSocket v1 二进制无 turn id 导致旧音频回放 | MITIGATED / Phone pending | SessionCoordinator 中央连接/epoch 过滤；abort 先 flush、再发送、再换连接。encoded/PCM 队列竞态与模拟器 AudioTrack pause/flush 已验证；手机听感仍待测，TTS stop 不等于播放完毕 |
| 激活模式与真实服务端不匹配 | Runtime pending | 支持验证码展示/有界 Bootstrap 轮询至 credentials；challenge-only 明确失败，不伪造 ESP32 HMAC；暂无服务器地址 |
| 身份存储损坏导致重新绑定 | MITIGATED | 默认报错不换号；只有明确确认后重置，保存原始文件。API 28 两次独立进程持久化通过 |
| 真实会话入口尚未联调 | Runtime pending | 已接 Debug Session、AudioRecord/AudioTrack 和 Coordinator；本地 Mock 到 PCM 与模拟器设备 API 分别通过，仍无真实服务端/参考手机会话证据 |
| 长期运行累积耗尽重试预算 | MITIGATED | 连续 Ready 60 秒才恢复预算；短暂 Ready 不恢复，旧 epoch/connection 的稳定计时器无效。8 次稳定恢复循环回归测试通过 |
| Codec 自回环不足以证明服务端/设备音频质量 | Phase 1C OPEN | 已补 Mock WebSocket 音频 E2E、queue 竞态和模拟器 AudioTrack flush；仍须参考手机、10 分钟稳定性与 C4 runtime 验证 |
| 采集结束时不足一帧的尾音 | MITIGATED / Phone pending | 录音中不补零、不丢跨 chunk 余数；正常松开只补齐一次最终960帧，并在 listen stop 前发送；取消/打断不补帧。覆盖余数0/1/100/959和重复结束；可听尾音仍须手机验证 |
| 音频焦点/来电/慢设备输出 | OPEN | 当前是前台 Debug Session，离开即关；非阻塞写和有界队列避免无限堆积，但没有 stall watchdog/音频焦点策略，过载终止会话 |
| 模拟器播放underrun | OPEN / Phone pending | 十分钟738轮软件测试通过、队列过载0，但各连接累计underrun1556；未分离段间空闲和有效播放中饥饿，不宣称无卡顿；参考手机须定位并验证听感 |
| Ready早于播放初始化/异步停止采集竞态 | MITIGATED | 首次长测失败证据保留；Hello消费者初始化先于Ready发布，采集失效改为Coordinator同步通知；新增回归及重新运行完整十分钟通过 |
| 本地无 Android 工程基线 | CLOSED | Phase 0B 三模块 skeleton、clean build、单元测试及 lint 已通过，审查已通过 |
| GitHub 仓库动态变化 | P1 | 记录审计日期、commit/ref；依赖版本固定 |
| 本机构建环境缺失 | CLOSED | 已安装 Platform 35、Build Tools 35.0.0、ADB 37.0.1；Gradle 8.9 clean build 已通过 |
| targetSdk 35 在 Android 9/新系统上的行为差异 | P0C | minSdk 已固定 28；真机 Gate 同时验证 Android 9 行为，产品 targetSdk 在后续发布策略中复核 |
| IndexTTS 固定 24k 与可配置 server playback rate 再次分叉 | MITIGATED / Runtime pending | `open_audio_channels()` 已在启动音频线程前校验预建 encoder rate 与 `conn.sample_rate`；不一致明确失败，不再静默错配；长期可增加 provider 显式输出契约 |
