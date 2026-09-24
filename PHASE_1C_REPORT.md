# Phase 1C — 首批增量：策略修正与真实 Opus codec

状态：IN PROGRESS，未完成 Audio vertical slice。用户已通过 Phase 1A/1B 审查。

## 本轮完成

1. RetryPolicy 默认稳定窗口 60 秒，可配置。合法 Hello 进入 Ready 后启动独立计时器；只有同一 epoch/connectionId 持续 Ready 到期才将 retries 清零。退避预算不再累积整个 start 生命周期，短暂 Ready 仍不清零。retire/stop/close 取消稳定计时器；认证刷新预算不随网络稳定自动重置。
2. 增加长期恢复测试：maxRetries=1，连续经历 8 次 fail/recover/stable/fail，均获得新预算；另测短暂 Ready 耗尽预算、旧计时器失效与显式停止。
3. HardInterruptPolicy 独立实现 invalidate/flush → abort → reconnect，Coordinator 通过窄接口委托策略。只提供这一生产实现，未偷偷启用软打断。保留 v1 不携带 turn ID、重连可能丢服务端上下文及 abort best-effort 的限制。
4. ActivationPolicy 收敛默认 3000 ms / 60 次轮询参数，不根据未验证的 expiry/timeout 推断激活成功。
5. Concentus 冻结 commit 的未修改 Java 源码归档成为离线依赖；构建校验 SHA256、保留源码版权头、JAR LICENSE 及 APK LICENSE asset，不增加第四个模块，不重写 codec。
6. OpusCodec / ConcentusOpusCodec：上行严格 PCM16 / 16000 / mono / 60ms / 960 samples；不足/超额帧拒绝，不补零。下行必须显式传入 PlaybackAudioConfig，无默认 24k，后续接线只允许来自已验证 Server Hello。每个 codec 由单一串行音频 worker 拥有，当前未接真实 worker。
7. 新增 5 项音频单测：真实 Opus round-trip（五种受支持采样率 × 单/双声道）、拒绝非960帧、任意 chunk 与 accumulator 联动、reset 恢复 codec 状态、无效输入/播放配置拒绝。

## 验证边界

已执行 `test lint assembleDebug --offline --no-daemon --console=plain`，BUILD SUCCESSFUL。策略修改后 core-protocol 42 个测试通过；codec 新增后 core-audio debug/release 每个 variant 9 个测试（原 accumulator 4 + codec 5）通过，0 failure/error/skipped；app/core-audio lint 0。首轮 codec 构建发现 Gradle Kotlin DSL 的 java 名称遮蔽，改为显式导入 MessageDigest 后构建通过，未跳过或弱化测试。

真实 codec 测试检查样本数、非静音输出、帧完整性和 reset 确定性；Opus 有损，不要求 PCM 与输入逐样本相等。此为同实现 loopback，不是独立 reference decoder 对照，不代表真实 TTS 音调、时长或服务端采样率已验收，也不是 Mock WebSocket 音频 E2E。

编译冻结 Java 8 源码时 JDK 提示 source/target 8 未来弃用，未抑制；现有 DataStore native strip 提示仍存在。三模块、minSdk 28、compileSdk 35 保持不变。

## 尚未完成（不得标 PASS）

- AudioRecord / AudioCaptureSource、权限及采集线程。
- Coordinator 上行音频发送、编码/PCM 有界队列与 AudioTrack。
- 真正清队列、pause/flush 及并发 interrupt 测试；目前仍为策略/codec 测试。
- Mock WebSocket 音频 E2E 与 Debug Session 页面。
- 普通 Android 手机音频闭环、真实 Xiaozhi 服务端联调。

C1/C2/C3 TARGET VALIDATION PENDING；C4 STATIC PASS / Runtime pending。未重跑 Android 进程重启测试，本轮不追加模拟器或真机音频结论。
