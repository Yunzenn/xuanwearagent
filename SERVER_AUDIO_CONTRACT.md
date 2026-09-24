# P0 Server Audio Contract — Static Verification

审计日期：2026-09-25  
冻结对象：`xinnan-tech/xiaozhi-esp32-server@788f5301fdd60cc3a8ef74025bfeece9b82b94ce`  
冻结来源：`main` 分支提交（Merge PR #3378，2026-09-21）  
补丁基线：上述完整 commit archive  
状态：**C4 STATIC PASS — Runtime verification pending**

## 补丁前复现

在独立 checkout 上先加入契约测试，再运行未修改的 upstream：

```text
5 failed, 3 passed
```

失败行为包括：

- Client Hello 的 16 kHz 上行参数把 Server Hello 的 24 kHz 下行参数覆盖。
- `conn.welcome_msg["audio_params"]` 被客户端输入突变。
- 非对象、非法格式及类型错误的 `audio_params` 会异常或污染服务端默认值。

## 最小补丁

- `core/handle/helloHandle.py::handleHelloMessage()`：只接受 P0 上行契约 `Opus/16000/mono/60ms`，写入独立的 `client_*` 字段；不再修改 `welcome_msg.audio_params`。
- `core/connection.py::ConnectionHandler.__init__()`：明确区分 `client_sample_rate`（上行）和 `sample_rate`（下行/TTS）。
- `core/connection.py::_init_connection_state()` 与 `_decode_opus_packet()`：Opus 上行 decoder 和 frame size 分别来自 `client_sample_rate/client_channels/client_frame_duration`，不会误用 24 kHz 下行值。
- `core/providers/tts/base.py::TTSProviderBase.open_audio_channels()`：常规 TTS Opus encoder 由 `conn.sample_rate` 创建；provider 预建 encoder 时强制校验其采样率等于 `conn.sample_rate`，不一致则安全失败。

## Server Hello 与实际编码参数的同源证明

1. `ConnectionHandler.handle_connection()` 从服务端 `config["xiaozhi"]["audio_params"]` 建立 `welcome_msg`。
2. 同一函数把 `welcome_msg["audio_params"]["sample_rate"]` 写入 `conn.sample_rate`。
3. `handleHelloMessage()` 不再允许 Client Hello 修改 `welcome_msg.audio_params`。
4. `TTSProviderBase.open_audio_channels()` 使用 `conn.sample_rate` 创建 `OpusEncoderUtils`。
5. `OpusEncoderUtils.__init__()` 将该值直接传给 Opus `Encoder(sample_rate, channels, ...)`。
6. IndexTTS 固定创建 24 kHz encoder；新增守卫要求它与 `conn.sample_rate` 相同，否则在音频线程启动前失败。因此当前默认 Server Hello=24 kHz 时一致，未来配置漂移不会静默产生错误音频。

结论：静态路径满足 `ServerHello.playback.sampleRate == actual TTS Opus encoder sampleRate`，且客户端 16 kHz 上行仍由独立上行字段驱动。

## 补丁后验证

```text
10 passed in 0.12s
py_compile: PASS
```

测试覆盖：

- A. client 16k + server 24k，Server Hello 仍为 24k。
- B. client 16k 上行被接受并独立保存。
- C. Client Hello 不突变 server playback config。
- D. Server Hello 保留 `session_id`。
- E. 缺少 client `audio_params` 时保留已定义默认值。
- F. malformed/unsupported 参数安全保留默认值。
- 上行 Opus decoder 使用 16k/mono/960 samples，而非 24k 下行值。
- TTS encoder 使用 Server Hello 的 24k；预建 encoder 不一致时明确失败。

最终补丁：`evidence/patches/xiaozhi-server-audio-contract.patch`。  
最终测试：`evidence/tests/test_hello_audio_contract.py`。

## Runtime verification pending

`STATIC PASS` 不等于最终 `PASS`。待真实服务端/TTS provider 可运行后仍需：

`Client Hello uplink=16k` → `Server Hello playback=24k` → 抓取真实下行 Opus → 使用 24k decoder 正确解码，并核对时长、音调和连续播放。

通过上述动态验证后，C4 才能标记为 `PASS`。
