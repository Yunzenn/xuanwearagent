# Protocol Contract

## P0 transport

- WebSocket Protocol v1 only。
- Headers：`Authorization: Bearer <token>`、`Protocol-Version: 1`、`Device-Id`、`Client-Id`。
- Client hello：`version=1`、`transport=websocket`、Opus、16000 Hz、mono、60 ms。
- 不声明尚未实现的 `mcp` 或 `aec`。
- Server hello 的 audio parameters 是唯一播放配置来源；禁止写死 16000 或 24000。

## Events

客户端内部使用 `ProtocolEvent` sealed hierarchy：Hello、Stt、Tts、Llm、Mcp、BinaryAudio、Error。UI 和业务层不直接解析 JSONObject。

## State machines

Transport：Disconnected → Connecting → WaitingHello → Ready → Disconnected。

Conversation：Idle → Listening → Thinking → Speaking → Idle；打断路径为 Speaking → Interrupting → Listening。

## Audio rules

- uplink：PCM16、16 kHz、mono、960 samples/frame；用 accumulator 处理任意 `AudioRecord.read()` 分块。
- downlink：Server Hello → PlaybackAudioConfig → OpusDecoder → AudioTrack。
- abort 先本地停止/flush/清空播放队列，再发送 abort。
- stale audio gate 同时要求 generationId、acceptDownlinkAudio 和 tts:start 生命周期。

