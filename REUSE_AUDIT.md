# Reuse Audit — Evidence Freeze

审计日期：2026-09-25。所有证据均固定到 commit SHA；后续升级必须重新审计差异。

| 能力 | 冻结证据 | Class / function | Decision | 许可与采用方式 |
|---|---|---|---|---|
| Protocol | `78/xiaozhi-esp32@64b57d0ba5c2f11a30974a0216dc28221c5b9d5b`：`docs/websocket.md`、`main/protocols/websocket_protocol.cc` | `WebsocketProtocol::OpenAudioChannel`、`ParseServerHello` | ADAPT | MIT；把协议行为重写为 Kotlin，不复制 ESP-IDF transport。若复制实质代码，保留原 MIT copyright/permission notice。 |
| Backend | `xinnan-tech/xiaozhi-esp32-server@788f5301fdd60cc3a8ef74025bfeece9b82b94ce`：`main/xiaozhi-server/core/handle/helloHandle.py`、`core/connection.py` | hello handler 写入 `conn.welcome_msg["audio_params"]`；connection 从 welcome 读取 `sample_rate` | REFERENCE | MIT；只做 P0-SERVER-AUDIO-CONTRACT 验证，失败时才允许最小后端 patch。 |
| OTA / Activation | `mdloverm/rokid-xiaozhi@8e3c920808b113e6f26726db80cdf33f6370dee2`：`app/src/main/java/com/rokid/xiaozhi/network/XiaozhiWebSocketClient.kt` | `XiaozhiWebSocketClient`、`setDeviceInfo`、`fetchConfig`、`ActivationInfo`、`OtaResult` | ADAPT | MIT；抽取为独立 `BootstrapRepository`，不复制 conversation/WebSocket 巨类。复制片段时保留 `Copyright (c) 2026 DLOVER` 和 MIT notice。 |
| Android audio | `DayanJ/xiaozhi-android-native@5fdc51e376fd89b4db0491066635b27663f66e09`：`service/AudioUtil.kt`、`service/XiaozhiWebSocketManager.kt` | AudioRecord/AudioTrack 生命周期实现 | REFERENCE | MIT；只对照权限、线程、初始化和故障处理，不直接复制。 |
| Opus | `lostromb/concentus@3885c4e46513ef0fc81fca100189e54f1714c6ca`：`Java/Concentus/src/main/java/org/concentus/OpusEncoder.java`、`OpusDecoder.java` | `OpusEncoder`、`OpusDecoder` encode/decode API | DIRECT DEPENDENCY candidate | BSD 风格许可；优先依赖发布制品/固定源码版本，不复制 codec。分发物必须复现 LICENSE 中版权、条件与免责声明。 |
| Live2D | `Live2D/CubismJavaSamples@8ce6803de7030a4816bccd8a3efcad69ea1d1186`：`Sample/src/full/.../LAppDelegate.java`、`LAppLive2DManager.java`、`LAppModel.java` | lifecycle、model loading、render loop、motion | SDK / REFERENCE | Sample 受 Live2D Open Software License；Core 受 Proprietary Software License；商业发布可能需要 Release License；样例模型另受 Free Material/各模型条款。不得按 MIT/Apache 处理。 |
| Native Live2D integration | `Voine/ChatWaifu_Mobile@14092ac66c2afd51de06bb126fd102cec869eb8e` | Android/Native Live2D 集成 | REFERENCE ONLY | 根目录未发现 LICENSE，禁止复制代码，只可参考思想。 |
| Product behavior | `TOM88812/xiaozhi-android-client@30a0c80446a3a88772945244739c0e69b79c647c` | Flutter Android/iOS 行为 | REFERENCE | Apache-2.0；不引入 Flutter runtime。 |
| MCP | `stixez/droid-mcp@aeaa5b9e8e96f56ef64a7ca23d0726585f7b1103` | `DroidMcp` builder、`ToolRegistry`、device/settings/vibration/alarms/apps modules | P1 ADAPT | Apache-2.0；Phase 5 才按白名单选择模块，禁止 `addAll()`。 |
| Avatar import | Android SAF + `ZipInputStream` 或成熟 ZIP library | select → validate → copy → private storage | DIRECT PLATFORM REUSE | 默认复制进 app private storage，导入完成后不依赖原 URI。只自研 canonical path、数量、压缩/解压体积、嵌套深度等安全策略，不自研压缩引擎。 |

## Freeze rules

Phase 1C 落地：上述 Concentus candidate 已转为固定源码依赖，冻结 SHA 不变。`third_party/concentus` 保存 `git archive` 导出的未修改 Java 源码及 LICENSE；构建哈希校验、编译并依赖生成 JAR，未重写 codec。细节见该目录 README 与 LICENSE_MATRIX.md。

- 依赖或参考升级到不同 commit/tag 前必须重新审计。
- 没有明确 LICENSE 的仓库一律 REFERENCE ONLY。
- 任何复制的 MIT/Apache/BSD 源码都保留文件版权头，并在发行包中附带相应许可证/NOTICE 要求。
