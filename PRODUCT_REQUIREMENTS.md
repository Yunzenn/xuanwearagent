# PRODUCT_REQUIREMENTS — 项目记忆 / 需求正本

> **这份文件是项目记忆。** 任何人（或 AI 会话）接手这个仓库，先读它，再读 `ROADMAP.md`。
> 它记录的是"要做什么、边界在哪、哪些决定已经冻结"，不记录进度流水。进度看 `ROADMAP.md`。

---

## 一句话定位

> 要做的是**能长期记住这个用户、会自然说话、能被打断、有二次元角色感、真正陪伴她的腕上 AI**，
> 不是 Live2D 展示器，也不是普通聊天机器人。

---

## 1. 目标设备

为 **CD12Max 4+32G** 做定制软件。

| | |
|---|---|
| SoC | 紫光展锐 W527，12nm，1×Cortex-A75 + 3×Cortex-A55 |
| 系统 | 新版糯米OS（Android 9 / **API 28**），**Full Android，不是 Wear OS** |
| 内存 / 存储 | 4 GB RAM + 32 GB ROM |
| 屏幕 | 2.06" AMOLED，**410 × 502 px**，60 Hz |
| 电池 | 1400 mAh，磁吸快充 |

**密度（已实测确认，曾据此返工一次）**：410×502 对角 648px ÷ 2.06" ≈ **315 dpi → Android 取 320 档
（density 2.0）**，可用画布只有 **205 × 251 dp**，不是 410 × 502 dp。所有 UI 尺寸以 205×251dp 为基准。
验证方式：`adb shell wm size 410x502; adb shell wm density 320`，看 `dumpsys window displays` 的
`base=410x502 320dpi`。

---

## 2. 产品定位

不是"手表版 ChatGPT"，而是**长期陪伴型二次元 AI 角色**。

打开手表的第一感觉必须是「**这个角色住在手表里**」，而不是工具页、设置页或工程 Demo。

---

## 3. 角色形象

- 用户指定想要 **泳装绪山真寻** 这一类具体角色形象。
- 开发期**可以使用用户提供的 Mahiro 资产**做私有定制和测试。
- **但产品架构不能把任何 IP 写死**，必须通过 `CharacterProfile / AvatarSource` 表达，以后能换角色。
- **公共发行时要把"版权/授权"与"用户自备资产"区分清楚**：IP 角色不得成为可再分发 APK 的默认资源。
- 客户资产目录（`app/src/main/assets/character/`）**已在 gitignore 中**，只提交 `.gitignore` 本身。
  放一张干净、有授权的立绘进去即可自动生效，不需要改代码。

---

## 4. 声音

- 用户想要的听感是 **《奇迹暖暖》苏暖暖 / 陈奕雯配音版本那种**：甜软、元气、少女感、亲近。
- 技术抽象为 `VoiceProvider / VoiceProfile`。
- **合规边界（不可越）**：没有相应授权时，**不得把"陈奕雯/苏暖暖声纹"作为默认官方克隆音色**。
  若客户自己持有合法授权，再接授权语音。
- 无授权时产品层只提供**描述性风格**（甜软 / 元气 / 温柔 / 平静），不克隆特定真人声纹。
- **TTS 放服务端**，手表只接收流式 PCM/Opus。

---

## 5. 核心交互

- **以语音为主**。首页 = 二次元角色舞台 + 最近对话 + 大号「按住说话」。
- 状态必须**真正**存在并被驱动：

```text
IDLE → LISTENING → THINKING → SPEAKING → IDLE
```

- **Speaking 时用户再次按下必须能立刻打断**（barge-in），且**本地要先 flush**，不能有残留音频漏出。

---

## 6. 语音链路

```text
手表端：录音 → Opus → 播放
服务端：ASR → Context/Memory → LLM → TTS
```

- **现有 `core-audio` 与 `core-protocol` 尽量直接复用，不重写协议和音频轮子。**
- 当前优先做**真实端到端**：`PTT → ASR → LLM → TTS → AudioTrack`。
- 服务端走现成的 `xinnan-tech/xiaozhi-esp32-server` 路线；**不新造 ASR 服务**，只做 provider 选型与压测。
- 关键指标：`t_release → first_audio`，目标 **< ~1.2 s**。

---

## 7. 陪伴感的核心：长期记忆

比 Live2D 重要得多。用户记忆力不太好，所以 AI 要替她记住：

- 喜好 / 厌恶
- 重要人物
- 过去发生的事
- 约定、计划、未来事件
- 情绪上下文
- 角色与用户之间的长期关系状态

**长期记忆不能只是向量库。**

---

## 8. 记忆模型

唯一正本是**类型化 schema**：

```text
CanonicalMemory
  type      = PROFILE / EVENT / EPISODE / RELATION
  content
  importance
  timestamp
  source
  characterScope
  provenance
```

举例：

```text
"我下周三下午三点要去医院" → EVENT，带 time + importance=high
"我其实不太喜欢香菜"       → PROFILE，food.dislike = 香菜
"昨天和室友吵架了很难受"   → EPISODE + 情绪上下文 + RELATION:室友
```

目标效果是能自然说出「你上次不是说和室友闹矛盾了吗，现在好一点了吗」，
而不是随机命中一句旧聊天。

**每轮上下文固定由五部分组成**：

```text
最近对话 + 用户画像 + 相关长期记忆 + 时间/事件/承诺 + 角色关系与情绪状态
```

### Mem0 / Jev 的角色分工（不能含糊）

```text
CanonicalMemory (类型化唯一正本)
        ↓ 持久化 / 候选检索
Mem0 / 向量库 / BM25            ← 只是实现底座
        ↓ 条件式重排
Jev Recall                      ← 只做二阶段 rerank
```

- **绝不允许** Wanyu memory + Mem0 memory + Jev memory 三套各自自称"记忆"。
- **Jev Recall 是条件式的，不是每轮都调**（会吃掉语音首字延迟预算）：

```text
普通寒暄 / 当前轮上下文已足够        → 不重排
PROFILE / EVENT 查询                 → 重排
候选 top scores 接近、歧义高          → 重排
需要跨事件关联                        → 重排
```

- `Jev-Mem` 只做后期 A/B（用我们自己的真实陪伴对话数据），**不做 P0 blocker，不上手表**。
- Wanyu 的**记忆分类、时间衰减、去重、暂存确认、用户画像、情绪连续性**重点借鉴，
  但**不搬整个 Wanyu 工程**。

---

## 9. 记忆必须可见、可控

后续需要「**我的记忆**」页面，让用户看到 AI 记住了什么，并能**修改、删除、确认或拒绝**。

这是陪伴产品的**信任机制**，不是可选功能。

涉及个人生活与健康相邻内容（"腰疼""去医院"）时：服务端加密存储、可删除、原始音频不做长期保留。

---

## 10. UI 设计原则

- 用户偏二次元，**不能是默认 Android Button / TextView 风格**。
- 目标：**OLED 深色 + 柔和少女系**，角色为视觉中心，简洁但精致。
- 参考 GitHub 成熟项目的**布局、气泡、PTT、状态反馈**，不重复造轮子。
- 已冻结的 token 与尺寸见 `app/src/main/kotlin/com/aiwatch/probe/theme/`。

---

## 11. 技术栈约束（硬）

继续使用当前 **Native Android Views**。**不为了 UI 引入**：

```text
Compose / Wear Compose / Wear OS runtime
新渲染栈 / 大型动画库 / 新的网络库 / 新的数据库
```

CD12Max 是 Full Android，**兼容优先**。

---

## 12. Live2D 的定位

- 从**主线 blocker 降级为 P1 可选增强**。**P0 用静态立绘也必须能完整交付陪伴体验。**
- 真机一接上，**提前**读取这两个值（不等 P1）：

```text
ro.product.cpu.abilist     # 是否存在 arm64-v8a（W527 是 64 位硅片，但 ROM 可能是 32 位 userspace）
GL_MAX_TEXTURE_SIZE        # Mahiro 原始 atlas 是 8192×8192；若上限 < 8192，原图不可能直接上传
```

- 判定规则：
  - 没有 `arm64-v8a` → Live2D Core 直接判不适配。
  - `GL_MAX_TEXTURE_SIZE < 8192` → 从产品设计上固定走**静态立绘 / 降采样 atlas / 序列帧**，
    不再幻想原始 8192 直接跑。
- 读法：debug build 内已有的 GLES capability probe（`PlainShaderProbe`），
  比 `dumpsys` 可靠。**只读值，不做任何 Live2D 调试。**

---

## 13. 整体架构：薄客户端

```text
CD12Max 手表
├── Companion UI（角色舞台 / 最近对话 / PTT / 四态）
├── core-audio（PCM / Opus / AudioTrack）
├── core-memory 本地缓存（Room：最近会话、画像缓存、事件缓存）
└── core-protocol ──WebSocket/HTTPS──┐
                                     │
                         Companion Backend
                    ASR → Context Builder → LLM → TTS
                              ↑
                    Memory Service（候选检索 → 条件重排）
```

**W527 不跑本地 LLM、大 embedding、VITS、Jev reranker。**
电池和热预算优先留给屏幕、麦克风、网络和播放。

---

## 14. 复用原则

凡是 GitHub 已有成熟方案：**先审 LICENSE，再决定 `DIRECT / ADAPT / REFERENCE ONLY`**。

重点参考：`WristAssist`、`ECSDevs/Messenger`、`Wanyu AI Companion`、`OpenClaw Companion`、
`Mem0`、`Jev Recall` 等。**不自己重造聊天气泡、PTT、记忆 schema、ASR provider、语音状态机。**

许可证状态与用法见 `REUSE_AUDIT.md`（已核实的标注为 VERIFIED）。

---

## 15. 当前优先级

```text
P0-1  二次元 Companion Home          ← 已基本完成，并已按真实密度 320dpi 修正
P0-2  真实语音闭环                    ← 下一步
P0-3  Memory Gateway + Room cache
P0-4  记忆后端（类型化 schema + 候选检索）
P0-5  用户画像 + 事件 + 长期记忆
P0-6  Jev Recall 条件式重排
P0-7  主动关心 / 提醒
P1    Live2D / Mahiro
P1+   Jev-Mem A/B
```

**长期记忆在 P0，Live2D 在 P1。**

---

## 16. 范围围栏（明确不做）

```text
不继续死磕 Cubism、不改官方 Live2D Framework   ← 直到两道设备门槛有结论
不引 Compose / Wear OS runtime / 新 UI 栈
不做端侧 ASR/LLM/TTS
不做多角色、角色商城、importer
不为"好看"引入大型动画库
不把某个 IP 角色作为公共发行版默认资源
不克隆未授权的特定真人声纹
```

---

## 17. 治理规则

> **任何一项工作，如果连续两轮没有缩小 G1/G2 的交付缺口，就停下来重新评估，而不是继续深挖。**

（设立原因：曾有一整个 session 花在 Live2D 上，而它当时是最不产品关键的一环。）

### Gates

- **G1**（不依赖 Live2D）：真机上打开 App → 看到角色 → 按住说话 → 听到回答 → 状态正确 → 重进仍在。
- **G2**：记忆生效——隔天它能提起她之前说过的事。
- **G3**：Live2D / Mahiro 形象。**永不阻塞 G1/G2。**
