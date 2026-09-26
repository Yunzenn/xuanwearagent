# ROADMAP — xuanwearagent / 小智腕上陪伴

Plan of record. Kept short on purpose: it exists so the Gate is not forgotten and regressions are caught,
not as a development phase of its own.

## Target hardware (supplied by the customer, 2026-09-26)

| | |
|---|---|
| SoC | Unisoc W527, 12 nm, 1x Cortex-A75 + 3x Cortex-A55 (ARMv8, 64-bit silicon) |
| OS | 糯米OS, Android 9 / API 28, full Android (not Wear OS) |
| RAM / ROM | 4 GB + 32 GB |
| Panel | 2.06" AMOLED, **410 x 502 px**, 60 Hz |
| Battery | 1400 mAh, magnetic fast charge |

**Density derivation (this corrected an earlier wrong assumption).** 410x502 is 648 px on the diagonal;
648 / 2.06" = ~315 dpi, which Android buckets to **320 dpi / density 2.0**. Usable canvas is therefore
**205 x 251 dp**, not 410 x 502 dp. The P0-1 layout was built against the wrong basis and overflowed the
panel (transcript and push-to-talk pushed off-screen) until `CompanionDimensions` was re-derived.
`evidence/screenshots/p0-1-at-real-density-320.png` is the overflow; the re-scaled capture supersedes it.

## Gates

* **G1 — shippable, no Live2D required.** On the device: open app -> see the character -> hold to talk ->
  hear a reply -> correct state -> relaunch still works.
* **G2 — memory works.** A day later it raises something she said before.
* **G3 — optional.** Live2D / Mahiro avatar. Never blocks G1 or G2.

## Architecture: thin client

The watch does character UI, capture, playback and a small cache. LLM, long-term memory and TTS live on the
server. W527 should not run a local LLM, large embeddings, VITS or a reranker — that is battery and heat
spent to make the product worse. 1400 mAh belongs to the panel, the microphone and the radio.

```
watch:  Companion UI (IDLE/LISTENING/THINKING/SPEAKING)
      + core-audio (PCM / Opus / AudioTrack)
      + core-memory local cache (Room: recent turns, profile cache, event cache)
      + core-protocol  <-- WebSocket / HTTPS -->  backend
backend: ASR -> context builder -> LLM -> TTS (streamed PCM/Opus back to the watch)
                              ^
                    memory service (candidate retrieval -> rerank)
```

**ASR is a first-class backend component** and was missing from the first draft of this plan; it must be
chosen deliberately (streaming vs utterance) because it drives the latency budget.

## Roadmap

```
P0-1  Companion Home (density fix done; avatar art still pending)
P0-2  real-time voice loop: PTT -> capture -> ASR -> LLM -> streamed TTS -> playback
P0-3  core-memory: MemoryGateway + Room cache
P0-4  memory backend (typed store; candidate retrieval)
P0-5  user profile + events + long-term memory
P0-6  second-stage rerank (Jev Recall)
P0-7  proactive care / reminders
P1    Live2D / Mahiro
P1+   Jev-Mem A/B against Mem0 and the Wanyu-style schema, using our own real conversation data
```

Long-term memory is **P0**; Live2D is **P1**. For this user "does it remember what I said yesterday" matters
far more than "does the hair move".

## Memory model

Five fixed components of every turn's context:

```
recent turns + user profile + relevant long-term memory + time/events/promises + character relationship & mood
```

Memories are **typed**, not just embeddings:

```
EVENT      "下周三下午三点去医院" -> time, importance=high, source=conversation
PROFILE    "不喜欢香菜"           -> food.dislike = 香菜
EPISODE    "昨天和室友吵架了"      -> episode + emotional context + relation:室友
```

The point is to be able to say "你上次不是说和室友闹矛盾了吗" rather than surfacing a random old line.

Retrieval is two-stage, and the reranker is never the first layer:

```
query -> structured filters -> BM25 / embedding / entity / recent events
      -> 20-50 candidates -> Jev Recall rerank -> 3-8 memories -> LLM
```

Reranking is **conditional**, not unconditional: it adds a network hop, and the voice path has a hard
latency budget (target < ~1.2 s to first audio). Rerank only when the candidate set is ambiguous or when the
query is about events/preferences.

## Reuse decisions

Reuse-first. **Licences below are as reported by the user and still need to be verified against each
repository's LICENSE file before any code is adapted** — only then do they enter `REUSE_AUDIT.md` as
verified.

| Project | Licence (reported) | Use |
|---|---|---|
| `JieRobot/wanyu-ai-android` | MIT | **Primary schema/design reference**: `MemoryRepository`, `UserProfileEntity`, `MemoryEntity`, `MemoryLinkEntity`, `EmotionEngine`; importance, time decay, staged confirmation, dedup, character scoping |
| `mem0ai/mem0` | Apache-2.0 | Backend memory store, self-hosted |
| `samdotmak/jev-recall` | MIT | Second-stage reranker (P0-6) |
| `libingzheren/Jev-Mem` | MIT | P1+ A/B only; Python 3.11 research backend, never on-device, never a P0 blocker |
| `marce1994/OpenClaw-Companion` | MIT | PTT, streamed TTS, client state machine |
| `Voine/ChatWaifu_Mobile` | unverified | Product structure reference only; **no code, no models** |
| `moeru-ai/airi` | MIT | Character/persona/mood visual language |

We do **not** import the Wanyu project. We adopt its memory taxonomy and policies into a new `core-memory`
module while keeping the canonical store server-side, because Wanyu is a client-side app and our topology is
client + backend. Two systems must not both claim to be "the memory": one canonical typed schema, with the
vector store as substrate underneath it.

## Character and voice

* `CharacterProfile -> userSuppliedAvatarAsset`. The private custom build may use assets the customer has
  rights to; **the IP character is never a default resource of a redistributable APK**.
* `VoiceProvider { CloudTtsVoice, AuthorizedCustomVoice, GenericStyleVoice }`. If the corresponding licence
  is held, the licensed voice may be wired in; otherwise the product only offers descriptive styles
  (sweet/soft, bright, gentle, calm) and **does not clone a named voice actor's voiceprint**.
* TTS is server-side; the watch receives streamed PCM/Opus.

## User-visible trust surface

A "我的记忆" screen listing what the companion believes it knows — preferences, recent events, upcoming
commitments — **and letting her edit or delete them**, including confirming or rejecting staged memories.
For a companion product this is not a nice-to-have; it is the trust mechanism.

Personal-life and health-adjacent content ("腰疼", "去医院") lives on a server, so: encrypted at rest,
deletable, and raw audio is not retained longer than transcription needs it.

## Scope fences

```
do not keep chasing Cubism / editing the official Framework   <- until the two device gates below are read
no Compose, no Wear OS runtime, no new UI stack
no on-device ASR/LLM/TTS
no multi-character, no character store, no importer
no large animation library for polish
```

## Device gates that are still unread

`C1 Device Probe` has never been run — no CD12Max has ever been connected. Two readings decide Live2D's fate
and should be taken the moment a device is available, even though Live2D is P1:

```
getprop ro.product.cpu.abilist     # arm64-v8a present? W527 is 64-bit silicon, but the ROM may be 32-bit
GL_MAX_TEXTURE_SIZE                # Mahiro's atlas is 8192x8192; a 4096 cap makes Live2D impossible here
```

## Governance rule

Adopted after a full session was spent on Live2D while it was the least product-critical component:

> If two consecutive rounds fail to shrink the G1/G2 delivery gap, stop and re-evaluate rather than dig
> deeper.


## P0-2A — 真实语音闭环（当前工作流）

**性质：产品化接通 + 真实端到端验证**，不是从零实现语音链路。

`DebugAudioSession`（185 行）已经跑通
`AudioRecord → PcmFrameAccumulator(960) → ConcentusOpusCodec → SessionCoordinator → ProtocolEvent →
PlaybackQueue + AndroidPcmPlaybackSink`，并带 tx/rx/readChunks/discardedTailSamples/paddedFinalFrames
等指标。**抽取，不重写。**

冻结范围（不扩）：

1. 把 `DebugAudioSession` 抽成产品可用的 `VoiceSessionAdapter`，复用现有 audio/protocol 代码。
   **`DebugAudioSession` 保留为薄包装**——它背后是 `DebugSessionActivity`、10 分钟模拟器 soak 证据和
   `core-audio` 的 codec 单测，删掉就等于扔掉唯一的 runtime 证据。
2. `CompanionActivity` 的 PTT 绑定真实 `beginCapture / endCapture`。
3. 协议 Conversation 状态映射到 UI 四态。协议侧已定义
   `Idle / Listening / Thinking / Speaking`，打断路径 `Speaking → Interrupting → Listening`。
4. STT 文本进用户气泡，TTS 文本进角色气泡。
5. binary audio 继续走现有 `PlaybackQueue + AudioTrack`。
6. Speaking 时再次按下走已有 `abort()`。"abort 先本地停止/flush/清空播放队列，再发送 abort"
   **已是协议契约**（见 `PROTOCOL_CONTRACT.md`），所以这条是**验证**，不是新设计。
7. 服务端沿用现有 Xiaozhi stack：**不引入 memory、不引入 Jev、不碰 Live2D**。
8. 做一次真实 E2E，记录 `t_release → first_audio`。

**唯一外部 blocker**：一个真实可访问的 `https://.../xiaozhi/ota/`（见 `PROTOCOL_CONTRACT.md`）。

### ASR：只做选型与压测，不自己造

服务端已有多 provider（`selected_module.ASR`，并已区分 `InterfaceType.STREAM`）。
**第一轮只测 4 个，不做全量 sweep** —— 目标是实时陪伴，不是做 ASR 论文：

```text
A. FunASR              本地 baseline：零外部网络下的 final 延迟与准确率
B. DoubaoStreamASRV2   明确流式：重点看首个 partial 与 final 延迟
C. AliyunBLStreamASR   paraformer-realtime-v2，max_sentence_silence 可到 200ms，适合低延迟交互
D. XunfeiStreamASR     另一个成熟中文流式基线
```

无云 API key 时退到 `FunASR + FunASRServer`，**不因选型阻塞产品**。

20 句基准固定记录字段：

```text
utterance_id / duration_ms / t_audio_end / t_first_partial / t_final
partial_latency_ms / final_latency_ms / expected_text / actual_text / CER / success / error
```

**真正决定 P0-2 的是端到端，不是单独的 ASR 分数**：

```text
PTT release → ASR final → LLM → TTS first packet → AudioTrack first audible sample
重点看：t_release → t_first_audio
```