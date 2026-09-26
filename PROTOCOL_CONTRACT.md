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



## Bootstrap / OTA

客户端不直接配置 WebSocket 地址。`ProductStore` 只接受 HTTPS（已核实：`validEndpoint()` 要求
`uri.scheme == "https"` 且 host 非空），由 `BootstrapRepository` 向该地址 POST，服务端返回真正的
WebSocket URL 与 token。

```text
POST https://<host>/xiaozhi/ota/          # 服务端单体模式内部对应 http_port 8003 /xiaozhi/ota/
  -> ws:// 或 wss://<host>/xiaozhi/v1/    + token
```

生产部署按反向代理暴露：

```text
https://xiaozhi.example.com/xiaozhi/ota/
wss://xiaozhi.example.com/xiaozhi/v1/
```

**不要把 `http://IP:8003` 填进产品设置** —— 当前客户端会拒绝非 HTTPS。

冻结服务端 commit：`788f5301fdd60cc3a8ef74025bfeece9b82b94ce`（16k 上行 / 24k 下行补丁）。

## Server 状态（三项必须分别声明，不可合并成一句"已就绪"）

```text
SERVER ENDPOINT   : MISSING        <- 仓库与历史记录中都没有真实部署地址或 token
PATCH SOURCE      : VERIFIED       <- 静态测试 10 passed / py_compile PASS
PATCH DEPLOYMENT  : NOT VERIFIED   <- 必须在真实运行的 server 上验一次
```

拿到 endpoint 后，需在**运行中的**服务端确认：Client Hello = 16k、Server Hello = 24k、
真实 TTS Opus = 24k、客户端按 24k 解码且音高与时长正常。
**在验完之前不得声称补丁已部署。**