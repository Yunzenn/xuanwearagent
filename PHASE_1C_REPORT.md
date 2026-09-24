# Phase 1C — Audio vertical slice 开发记录

状态：IN PROGRESS。本地 Mock 音频闭环和模拟器 API 验证通过；参考手机、真实 Xiaozhi 和持续运行验收尚未完成。用户已通过 Phase 1A/1B 审查。

## 首批增量（历史记录）

1. RetryPolicy 默认稳定窗口 60 秒，可配置。合法 Hello 进入 Ready 后启动独立计时器；只有同一 epoch/connectionId 持续 Ready 到期才将 retries 清零。退避预算不再累积整个 start 生命周期，短暂 Ready 仍不清零。retire/stop/close 取消稳定计时器；认证刷新预算不随网络稳定自动重置。
2. 增加长期恢复测试：maxRetries=1，连续经历 8 次 fail/recover/stable/fail，均获得新预算；另测短暂 Ready 耗尽预算、旧计时器失效与显式停止。
3. HardInterruptPolicy 独立实现 invalidate/flush → abort → reconnect，Coordinator 通过窄接口委托策略。只提供这一生产实现，未偷偷启用软打断。保留 v1 不携带 turn ID、重连可能丢服务端上下文及 abort best-effort 的限制。
4. ActivationPolicy 收敛默认 3000 ms / 60 次轮询参数，不根据未验证的 expiry/timeout 推断激活成功。
5. Concentus 冻结 commit 的未修改 Java 源码归档成为离线依赖；构建校验 SHA256、保留源码版权头、JAR LICENSE 及 APK LICENSE asset，不增加第四个模块，不重写 codec。
6. OpusCodec / ConcentusOpusCodec：上行严格 PCM16 / 16000 / mono / 60ms / 960 samples；不足/超额帧拒绝，不补零。下行必须显式传入 PlaybackAudioConfig，无默认 24k，后续接线只允许来自已验证 Server Hello。每个 codec 由单一串行音频 worker 拥有，当前未接真实 worker。
7. 新增 5 项音频单测：真实 Opus round-trip（五种受支持采样率 × 单/双声道）、拒绝非960帧、任意 chunk 与 accumulator 联动、reset 恢复 codec 状态、无效输入/播放配置拒绝。

## 首批验证边界（历史记录）

已执行 `test lint assembleDebug --offline --no-daemon --console=plain`，BUILD SUCCESSFUL。策略修改后 core-protocol 42 个测试通过；codec 新增后 core-audio debug/release 每个 variant 9 个测试（原 accumulator 4 + codec 5）通过，0 failure/error/skipped；app/core-audio lint 0。首轮 codec 构建发现 Gradle Kotlin DSL 的 java 名称遮蔽，改为显式导入 MessageDigest 后构建通过，未跳过或弱化测试。

真实 codec 测试检查样本数、非静音输出、帧完整性和 reset 确定性；Opus 有损，不要求 PCM 与输入逐样本相等。此为同实现 loopback，不是独立 reference decoder 对照，不代表真实 TTS 音调、时长或服务端采样率已验收，也不是 Mock WebSocket 音频 E2E。

编译冻结 Java 8 源码时 JDK 提示 source/target 8 未来弃用，未抑制；现有 DataStore native strip 提示仍存在。三模块、minSdk 28、compileSdk 35 保持不变。

## 第二批：采集、播放与 Debug Session

- AndroidAudioCaptureSource：权限显式检查；仅原生 16k/mono/PCM16，不支持时明确失败，不预置 resampler。1024-sample 读缓冲经 accumulator 形成 960-sample 帧；上行 codec 与下行 codec 各自由独立串行 worker 持有。
- Coordinator 新增 beginCapture/sendAudio/endCapture；上行要求 READY + LISTENING + 当前 generation。音频发送逐包等待 actor 返回，旧 generation 的音频和结束录音命令不能影响新会话。SessionSnapshot 暴露 ConversationState。
- PlaybackQueue 限制 8 个 encoded packet + 2 个 decoded PCM frame，另有最多 1 个在途 decode；过载明确失败关闭会话，不静默丢包。sink 使用 AudioTrack 非阻塞写，保留 partial-write offset；没有完整写出的 PCM 会在下次 pump 继续写。
- flush 在同一锁内关 gate、递增内部 epoch、清两个队列并调用 AudioTrack.pause/flush。decode 在锁外执行，返回时再核对 epoch，不能在 flush 后重新填入旧 PCM；sink write 与 flush 共用锁，因此 flush 返回后不会出现旧 write。
- TTS stop 只关闭入站接收，已接受数据继续 drain；新一轮 listen 会清上一轮设备缓冲。播放 worker 空闲时等待 channel 通知，不做持续轮询；有待写 PCM 时每 5ms 尝试非阻塞写。
- DebugAudioSession 接入 HTTPS Bootstrap → Coordinator → Opus → AudioTrack，源自 Hello 的播放参数直传 decoder/AudioTrack。发生非 Ready 状态会停止采集，离开页面关闭会话、录音、播放与重连。Connect 本身不启动麦克风。
- Probe 首页增加 Debug Session 页面：HTTPS 地址、Connect、Hold to Talk、Interrupt、Disconnect；显示连接/会话状态、输入/输出参数、TX/RX、队列深度、generation、connectionId、read 次数、结束时的未满帧 tail 数和诊断。端点/凭据不持久化；页面不支持明文网络，JVM Mock 测试才显式开启 WS。
- 手动松开/打断是采集结束边界：不补零发送不足 960 的 tail，当前 tail 计数显示在 Debug Panel；录音期间跨 chunk 余数保持不丢。不能把用户结束一轮时主动丢弃 tail 描述成无损结束，若需要尾音完整保留仍须进一步设计。

### 第二批验证

```powershell
.\.tools\gradle-8.9\bin\gradle.bat clean test lint assembleDebug :app:assembleDebugAndroidTest --offline --no-daemon --console=plain
.\.android-sdk\platform-tools\adb.exe -s emulator-5556 install -r -g app/build/outputs/apk/debug/app-debug.apk
.\.android-sdk\platform-tools\adb.exe -s emulator-5556 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
.\.android-sdk\platform-tools\adb.exe -s emulator-5556 shell am instrument -w -r com.aiwatch.probe.test/androidx.test.runner.AndroidJUnitRunner
```

- clean build SUCCESS：170 tasks，165 executed / 5 up-to-date。随后加入播放 worker 的事件唤醒并再次 `test lint assembleDebug :app:assembleDebugAndroidTest`，SUCCESS：167 tasks，19 executed / 148 up-to-date。
- core-protocol：43 tests；core-audio：每 variant 14 tests（codec 5、accumulator 4、queue 4、Mock E2E 1），debug/release 合计 28 次。全部 0 failure/error/skipped；lint 0。
- Mock E2E 使用真实回环 WebSocket，不是直接调用 parser：16k 上行 Opus 在 server 端得到 960 samples；server 使用真正 24k encoder 生成 3 个 60ms 包，经 Hello/tts:start/binary/tts:stop、Coordinator、queue 后得到 4320 个非全零 PCM samples。
- queue 测试覆盖 partial write、不支持写入时有界背压、encoded/PCM 同时 flush、阻塞在途 decode 与 flush 的竞态、旧 generation 拒绝，以及 TTS stop 后 drain。
- API 28 x86_64 模拟器，最终 instrumentation `OK (4 tests)`：AudioRecord 读取/释放；真实 AudioTrack play → pause/flush → 再 pump 仍暂停；Debug 页面启动/关闭；身份读取。没有主机麦克风输入或扬声器听感测试。身份测试本轮只读取，未重新执行 force-stop 跨进程比较。
- 开发中修复音频库权限声明和跨模块公共 OkHttp/coroutines API 可见性。没有抑制 MissingPermission、降低 lint 或跳过原测试。

最终 APK：`app/build/outputs/apk/debug/app-debug.apk`；SHA256 `87925F05885FABF5391D89C5BFDD727427263661A0B0FB7A8144A8745EA5B3A8`。Debug Session 可从 Probe 首页按钮进入。

### 仍待验收

- Debug 页面到真实服务器的完整会话、真实 Activation、网络中断后的音频恢复。
- 参考手机 mic → mock/server → speaker；10 分钟稳定运行、read chunk 分布、CPU/内存、underrun、延迟与可听尾音/打断体验。
- 当前对极慢设备/网络采用有限队列过载失败，不保证慢连接无掉话；尚无播放 stall watchdog、音频焦点/来电处理或后台录音支持。页面离开即关闭是本轮明确边界。
- Mock E2E 末端是测试 PCM sink；模拟器 AudioTrack 测试独立进行，不将两项拼称“真机端到端对话通过”。

C1/C2/C3 TARGET VALIDATION PENDING；C4 STATIC PASS / Runtime pending，不变。不得据此标记整个 Phase 1C PASS。
