# Implementation Tasks

## Phase 0A（完成）

- [x] 建立任务书、复用矩阵和协议初始契约
- [x] 初审指定 GitHub 仓库、定位与许可证
- [x] 补齐 Concentus、rokid-xiaozhi、Cubism 的文件/class/commit/license 级证据

## Phase 0B（PASS / CLOSED）

- [x] 建立 `app`、`core-protocol`、`core-audio` 最小模块
- [x] 创建 Device / Audio / Graphics / Storage Probe skeleton 与 md/json 导出
- [x] 创建 960-sample `PcmFrameAccumulator` 与无补零/无丢余数测试
- [x] 安装 Android SDK Platform 35、Build Tools 35.0.0、Platform Tools/ADB 37.0.1
- [x] `clean test lint assembleDebug` 通过；lint 0 issues
- [x] 4 个 accumulator 测试在 debug/release variant 共执行 8 次，0 failure/0 skipped
- [x] Probe APK 已通过签名和 zipalign 结构验证，minSdk=28

Phase 0B 状态：**PASS / CLOSED，审查已通过。用户已授权 Phase 1 软件开发与目标设备验收并行。**

## Phase 0C（需要真机/服务端）

- [ ] C1 `TARGET VALIDATION PENDING`：ADB 无设备；连接 CD12Max 后完成 DeviceCapabilityReport.md/json
- [ ] C2 `TARGET VALIDATION PENDING`：需要 CD12Max；完成 AudioRecord → accumulator → Concentus → AudioTrack 10 分钟 Gate
- [ ] C3 `TARGET VALIDATION PENDING`：需要 CD12Max；运行冻结 Cubism sample + 合法模型 10 分钟
- [x] C4 `STATIC PASS`：冻结 checkout 上先以测试复现 bug，再完成最小 patch；10 个契约测试及语法编译通过

C4 最终补丁保存在 `evidence/patches/xiaozhi-server-audio-contract.patch`，回归测试保存在 `evidence/tests/test_hello_audio_contract.py`。真实 server/TTS provider 的下行 Opus 抓取与 24k 解码尚未执行，因此状态只能是 `STATIC PASS`，不得标记最终 `PASS`。

P0-SERVER-AUDIO-CONTRACT 失败时只允许修改服务端最小必要代码，Android 端不得猜测采样率。

Phase 0C 状态：**未完成。C1/C2/C3 TARGET VALIDATION PENDING，C4 STATIC PASS（Runtime pending）。与 Phase 1 软件开发并行。**

## Phase 1 vertical slice

状态：**IN PROGRESS**。JVM 单测、模拟器 API 28、参考手机与 CD12Max 四层证据独立记录；软件通过不代表目标机通过。

Phase 1A：PASS WITH CONDITIONS（用户审查）。Phase 1B：已完成 SessionCoordinator、有限退避/认证刷新、集中旧事件过滤、验证码激活轮询、确认后身份恢复、TLS 负向测试及 APK ABI 审计。38 个 core-protocol 测试通过；API 28 模拟器实际进程重启身份一致性通过。验证码模式仅本地自动测试，真实服务端激活、完整应用会话入口仍待联调；challenge-only 硬件 HMAC 模式不支持。详见 PHASE_1B_REPORT.md。Phase 1C 音频链未开始。

1. DeviceIdentity + DataStore 重启持久化测试
2. BootstrapRepository（OTA/activation/WS token）
3. ProtocolEvent 与 XiaozhiProtocol 接口
4. Transport / Conversation 双状态机
5. PcmFrameAccumulator + Concentus uplink
6. Server hello 动态 downlink decoder + AudioTrack
7. 本地优先 interrupt 与 stale audio gate
8. 集成测试：Mic → server → STT/LLM/TTS → speaker
9. API 28 构建、真机安装、10 分钟稳定性测试

Phase 1 明确不做：完整 UI、Live2D、Avatar importer、Character、MCP、本地 LLM/ASR、wake word、Root/Shizuku。

## Phase 1C — Audio vertical slice（IN PROGRESS）

Phase 1A 已通过；Phase 1B 用户审查通过，minor follow-up 已落地：稳定 Ready 60 秒恢复 retry budget，短暂 Ready 不恢复；HardInterruptPolicy / ActivationPolicy 已提取，未实现 SoftInterruptPolicy。

- [x] 稳定恢复/快速抖动/旧稳定计时器/可注入打断策略回归测试
- [x] 固定 Concentus 原始源码依赖与归档哈希、随包 LICENSE
- [x] OpusCodec / ConcentusOpusCodec；16k mono 960-sample 上行，显式 playback config 下行
- [x] 真实 Opus 编解码、不同采样率/声道、任意 chunk 分帧与 reset 测试
- [ ] AudioCaptureSource / AudioRecord 权限、初始化与线程生命周期
- [ ] Coordinator 上行音频入口与 generation 隔离
- [ ] encoded / PCM 有界队列、AudioTrack 输出与真实 pause/flush
- [ ] Mock WebSocket E2E：Hello → tts:start → 合法 Opus → PCM → tts:stop
- [ ] 最小 Debug Session 页面（状态、按住说话、打断、包数、队列、generation/connectionId）
- [ ] 参考手机 mic → codec → mock/server → codec → speaker

当前不是 Phase 1C PASS；详见 PHASE_1C_REPORT.md。CD12Max 与 C4 runtime Gate 不变。
