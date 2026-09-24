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

Phase 0B 状态：**PASS / CLOSED，审查已通过。不得自动进入 Phase 1。**

## Phase 0C（需要真机/服务端）

- [ ] C1 `BLOCKED`：ADB 无设备；连接 CD12Max 后完成 DeviceCapabilityReport.md/json
- [ ] C2 `BLOCKED`：需要 CD12Max；完成 AudioRecord → accumulator → Concentus → AudioTrack 10 分钟 Gate
- [ ] C3 `BLOCKED`：需要 CD12Max；运行冻结 Cubism sample + 合法模型 10 分钟
- [x] C4 `STATIC PASS`：冻结 checkout 上先以测试复现 bug，再完成最小 patch；10 个契约测试及语法编译通过

C4 最终补丁保存在 `evidence/patches/xiaozhi-server-audio-contract.patch`，回归测试保存在 `evidence/tests/test_hello_audio_contract.py`。真实 server/TTS provider 的下行 Opus 抓取与 24k 解码尚未执行，因此状态只能是 `STATIC PASS`，不得标记最终 `PASS`。

P0-SERVER-AUDIO-CONTRACT 失败时只允许修改服务端最小必要代码，Android 端不得猜测采样率。

Phase 0C 状态：**未完成。C1/C2/C3 BLOCKED，C4 STATIC PASS（Runtime pending）。不得进入 Phase 1。**

## Phase 1 vertical slice

状态：**NOT STARTED**。须 C1、C2、C3、C4 全部最终 PASS 后启动。

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
