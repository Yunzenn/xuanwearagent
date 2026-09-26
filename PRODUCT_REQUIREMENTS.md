# PRODUCT_REQUIREMENTS — 项目记忆 / 需求正本

> **这份文件是项目记忆。** 任何人（或 AI 会话）接手这个仓库，先读它，再读 `ROADMAP.md`。
> 它记录的是"要做什么、边界在哪、哪些决定已经冻结"，不记录进度流水。进度看 `ROADMAP.md`。

---

## 一句话定位

> 一个以二次元角色呈现、拥有长期个人记忆，并能**像 Codex 操作电脑一样通过自然语言观察、理解和操作
> 整块 Android 手表**的个人 AI Agent。

内部称之为 **Companion Agent Runtime**／**腕上陪伴智能体**。

不是"一个会聊天的二次元手表 App，后面顺便加一点设备控制"。三者缺一不可：

```text
陪伴 = 人格层
记忆 = 持续性
接管手表 = 行动能力
```

产品核心是**三个并列能力面**，由 Agent Planner 统一决定这一轮该不该调工具：

```text
                 小智 Agent
                    │
      ┌─────────────┼─────────────┐
      ▼             ▼             ▼
 Companion       Memory        Operator
 陪伴人格         长期记忆        手表控制
```

它更接近：

```text
Codex = 模型 + 上下文 + 工具 + 电脑执行器
小智  = 角色人格 + 长期记忆 + 模型 + 工具 + 手表执行器
```


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

### G3 Watch Operator —— V1 核心能力之一（不是可选增强）

`G1 / G2 / G3` **三者都属于最终 V1**。只有**工程顺序**是 G1 → G2 → G3，因为 Agent 连"听懂并正常对话"都还没稳定时，
先让它乱点系统没有意义。**Live2D 才是 optional。**

```text
G1 真语音闭环  →  G2 长期记忆生效  →  G3 Watch Operator
```

> G3 Watch Operator is gated behind G1 and G2. No MCP capability advertisement, Accessibility
> integration, or device-control dependency may enter the production path before the real P0-2 voice
> E2E is measured.

第一批工具只做 **5–8 个**最高频、native API 最稳的：

```text
get_battery / set_volume / launch_app / create_alarm·timer
read_calendar / media play·pause / vibrate / （可选 brightness）
```

**Contacts / SMS / Location / Notification / Camera 第一批全部不进。** 这一条同时解决两个问题：隐私姿态，
以及 tool schema 膨胀 —— 工具 schema 每轮都进 prompt，工具越多选错率越高、首字延迟越长，而
`t_release → first_audio < ~1.2 s` 是 P0-2 的判定指标。因此**不允许全量 `tools/list` 暴露**。

能力分档（硬规则）：

```text
自动执行     调音量 / 打开 App / 读本地电量
首次授权     读取日历
需要确认     创建日历事件 / 回复通知 / 修改设置
强制确认     发短信 / 拨号 / 任何"离开设备"的动作
```

敏感能力（通知 / 通讯录 / 短信 / 位置 / 相机）必须：用户主动开启 + 明确说明哪些数据离开手表 + 高风险动作逐项确认
+ 调用日志用户可查 + 随时撤权。

**协议现状（不要误解为"已经支持 MCP"）**：`core-protocol` 目前只有 `type="mcp"` 的**入站信封解析**
（`ProtocolModels.kt:15`、`XiaozhiProtocol.kt:42`），**没有** device-MCP 会话实现 ——
`initialize / tools/list / tools/call / response / list_changed / 出站构造` 全都仍是实际工作量。
且打开 `features.mcp` 会改动 client hello，而那条 hello 正是**尚未在真实服务端验证过**的语音链路所依赖的。


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


---

## 18. 三面能力架构与 Watch Operator（2026-09-26 产品定义更新）

本节取代此前把 G3 描述为"未来边界"的措辞。**G1/G2/G3 都是 V1 核心**，Live2D 才是增强项。

### 18.1 产品完成度

```text
核心 V1
──────
G1  Voice / Conversation     会自然交流
G2  Long-term Memory         会长期记住
G3  Watch Operator           会真正替用户操作手表

增强
────
Live2D / 视觉 GUI fallback / 主动陪伴 / 更复杂自动化
```

工程顺序仍是 G1 → G2 → G3（`Phase A → B → C`），但 A+B+C 才是第一版产品，不是"A+B 做完再说"。

### 18.2 Watch Operator Runtime 三层（优先级即顺序，不可颠倒）

```text
1. Native Tools        直接 Android API —— 永远优先
2. Accessibility       通用 UI 操作（观察 → 决策 → 操作 → 再观察）
3. Visual fallback     截图 → VLM → 坐标点击（后期，不带进第一版）
```

**有 API 就绝不模拟点击。** 用户说"声音太大了"→ `watch.set_volume(30)`，不是"打开设置→声音→拖滑块"。
第一层最可靠、最省电、也最不容易坏，是第一版最重要的控制能力。

第二层才是 Codex 式循环：`launch_app → inspect_ui → click → inspect_ui → set_text → …`

### 18.3 工具必须分层披露，但核心集必须常驻

不能每轮给模型 145 个 tool。默认只暴露**能力域**，模型判断属于某域后再展开：

```text
device / apps / calendar / media / communication
```

**但要注意一个延迟陷阱**：渐进披露会多一次往返（模型先要域、再拿工具、再调用）。语音路径的预算是
`t_release → first_audio < ~1.2 s`，多一次 LLM 往返可能就吃掉了。

因此：

```text
核心 6–10 个工具     每轮常驻，不付额外往返
长尾工具             才走渐进披露
```

第一版核心集：

```text
set_volume / set_brightness / launch_app / create_timer
create_alarm / read_calendar / media_play_pause / get_battery
```

### 18.4 记忆必须参与"操作"，这是与普通 Computer Use 最大的区别

普通 Agent 只执行；小智要先**用记忆消解指代**再决定工具参数：

```text
"我明天下午别让我忘了那个事"
   ↓ Memory Resolution
EVENT: 交课程材料
   ↓
create_reminder(...)
```

**由此产生一条新的安全约束（本节新增）**：当工具参数是**由记忆消解指代**得出、且动作**不可撤销**时，
必须在执行前把消解结果给用户确认。

例如"那个事"被解析成"交课程材料"→ 创建提醒前先显示：

```text
你是说：交课程材料，明天
对吗？
```

理由：错误的记忆消解在聊天里只是一句错话，落到工具调用上就是**一个真实且不可逆的世界动作**。
这是 G2 与 G3 叠加后才出现的失败模式，单独的 G2 或 G3 都不需要这条。

### 18.5 UI：极简 Agent Action Card

首页不能只是聊天页，还要有"**Agent 正在替你做事情**"的感觉：

```text
小智
"好呀，明天别睡过头。"

✓ 已设置闹钟
  明天 07:00
```

多步任务：

```text
正在帮你处理…
✓ 打开设置
✓ 找到声音
● 调整媒体音量
```

**但只有 205 × 251 dp，不能做桌面 Codex 那种大块 terminal/log UI。**
而且首页的固定预算（顶栏 38 + 舞台 84 + PTT 44 + 间距/边距 ≈ 188dp）只剩约 63dp 给消息区——
action card 与对话区**抢同一块空间**。

因此决定：**action card 不做独立面板，而是作为消息流里的附着元素**（紧跟触发它的那条回复），
随消息一起滚动。这样不需要额外的垂直预算，也不会在只有一条消息时把页面撑空。

### 18.6 架构定位

```text
手表端 = Thin Agent Client + Tool Runtime + Companion UI + Local Cache
服务端 = ASR + LLM + Memory + Planning + TTS
```

**手表端不持有 LLM。** 这对 W527 + 4GB 是唯一合理的选择。

### 18.7 命名

文档与内部讨论改称 **Companion Agent Runtime**／**腕上陪伴智能体**。
不改 `applicationId` 与包名——那是产品化步骤，不应在开发中途动。