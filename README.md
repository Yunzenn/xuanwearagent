# xuanwearagent — 小智 · 腕上二次元陪伴

为 **CD12Max 4+32G**（Full Android 手表，Android 9 / API 28）定制的二次元 AI 陪伴软件。

> **这不是手表版 ChatGPT。** 目标是一个**长期记住这个用户、会自然说话、能被打断、有角色感**的腕上陪伴体。
> 需求正本见 **[`PRODUCT_REQUIREMENTS.md`](PRODUCT_REQUIREMENTS.md)**（接手必读），排期与 Gates 见 **[`ROADMAP.md`](ROADMAP.md)**。

---

## 当前状态（诚实版）

| 部分 | 状态 |
|---|---|
| 可安装 APK | ✅ 可构建、可安装、可启动；启动页是 `CompanionActivity` |
| **P0-1 Companion Home** | ✅ 角色舞台 + 聊天气泡 + 大号 PTT + 四态，尺寸已按真实密度 320dpi / 205×251dp 修正 |
| 角色立绘 | ⚠️ **占位**。客户资产有版权限制且随附的静图带水印，未打包。放一张有授权的立绘进 `app/src/main/assets/character/` 即生效 |
| **P0-2 真实语音闭环** | ⚠️ **客户端已接通，真实 E2E 未验证**。首页 PTT 现在驱动真实的 `XiaozhiVoiceSession`（采集 / Opus / 协议 / 回放），四态来自协议状态机；缺的是一个可达的 HTTPS bootstrap endpoint，所以 `t_release → first_audio` 还没有实测数字 |
| 长期记忆（P0-3~P0-7） | ❌ 未开始 |
| Live2D 形象（P1） | ❌ **0 像素**。原因已收窄到 Cubism program/path；另有两道设备门槛未读 |

**Gates**：`G1`（真实语音闭环）= **未达成**（等 endpoint）；`G2`（记忆生效）= 未开始；`G3`（Watch Operator）= 未开始。

**G1 / G2 / G3 三者都属于 V1**，Live2D 是独立的 Visual Enhancement Gate，不占 G 编号、不阻塞任何一个。

---

## 架构

**手表是薄客户端，服务端做重活。** W527 不跑本地 LLM / 大 embedding / VITS / reranker。

```text
CD12Max
├── Companion UI      角色舞台 · 最近对话 · PTT · IDLE/LISTENING/THINKING/SPEAKING
├── core-audio        PCM · Opus(Concentus) · AudioTrack
├── core-live2d       可选增强（P1），当前不影响任何其余功能
└── core-protocol ──WebSocket / HTTPS──┐
                                       │
                           Companion Backend
                      ASR → Context/Memory → LLM → TTS
                                ↑
                      Memory Service（候选检索 → 条件重排）
```

| 模块 | 职责 |
|---|---|
| `:app` | `home/` 首页 · `conversation/` 对话 · `character/` 角色 · `voice/` 语音 · `theme/` token。**对 Live2D 零编译依赖**（`HomeActivity` 已删除，manifest 无 Live2D Activity） |
| `:core-audio` | PCM 组帧、Opus 编解码、播放队列、音频设备枚举（含单测） |
| `:core-protocol` | Xiaozhi Protocol v1、bootstrap、WebSocket、会话状态机、interrupt（含单测） |
| `:core-live2d` | Cubism 适配模块 + 产品 runtime + 设备能力/Live2D 无关的光栅化探针（**P1 可选**） |

---

## 构建

当前开发机的可用配置（**离线**构建，依赖本地缓存与本地 SDK）：

```powershell
$env:JAVA_HOME          = 'D:\JDK'                       # JDK 21
$env:GRADLE_USER_HOME   = 'D:\AIwatch\.tools\gradle-home'
$env:GRADLE_RO_DEP_CACHE= 'C:\Users\<你>\.gradle\caches'

& 'D:\AIwatch\.tools\gradle-8.9\bin\gradle.bat' -p 'D:\AIwatch' --offline --console=plain `
    :app:assembleDebug :app:assembleDebugAndroidTest
```

`compileSdk 35` · `minSdk 28` · `targetSdk 35` · `versionName 0.2.0-product-preview`

> ✅ **fresh clone 可以直接构建。** 官方 Cubism SDK 不可再分发，所以 `settings.gradle.kts` **只在 SDK 根目录
> 真实存在时才 include `:core-live2d`**。干净检出默认构建 `:app` / `:core-protocol` / `:core-audio`。
> 要构建 Live2D 模块时，自备 `CubismSdkForJava-5-r.5` 放到 `third_party/live2d/sdk-r5/` 即可。

---

## 在模拟器上按目标几何验证

真机面板是 **410×502 @ 320dpi**，任何模拟器都要先覆盖成同样几何，否则视觉判断无效：

```powershell
adb shell wm size 410x502
adb shell wm density 320
adb shell dumpsys window displays | Select-String 'base=410x502'   # 确认生效
```

### 跑测试

本环境的 UTP runner 起不来，**绕开 Gradle 的 connected test，直接用 `adb install` + `am instrument`**，
并且**一次只跑一个测试方法**（同进程整类跑会因 Live2D 静态状态互相污染）：

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class 'com.aiwatch.probe.CompanionHomeTest#pushToTalkDrivesTheFourStatesAndAppendsBothSides' `
    com.aiwatch.probe.test/androidx.test.runner.AndroidJUnitRunner
```

---

## 文档地图

| 文件 | 作用 |
|---|---|
| **`PRODUCT_REQUIREMENTS.md`** | **项目记忆 / 需求正本** — 接手先读 |
| **`ROADMAP.md`** | 排期、Gates、架构决定、复用表、范围围栏、治理规则 |
| `HANDOFF.md` | 会话交接与技术上下文 |
| `REUSE_AUDIT.md` | 每个参考项目的 LICENSE 与用法（`DIRECT / ADAPT / REFERENCE ONLY`） |
| `RISK_REGISTER.md` | 风险登记 |
| `DEVICE_COMPATIBILITY.md` | 目标设备与 `C1 Device Probe`（**至今未连真机**） |
| `SERVER_AUDIO_CONTRACT.md` | 与 xiaozhi-esp32-server 的音频契约（16k 上行 / 24k 下行） |
| `PROTOCOL_CONTRACT.md` | 协议契约 |
| `LICENSE_MATRIX.md` / `DEPENDENCY_DECISIONS.md` | 依赖与许可证 |
| `PHASE_*.md` | 各阶段报告（历史记录） |
| `任务工程书.md` | 最初的任务工程书 |

---

## 已知限制与风险

1. **真机从未连上过**（`C1 Device Probe: TARGET VALIDATION PENDING`）。ABI、GPU 纹理上限、
   麦克风/扬声器、真实耗电与后台存活**全部未验证**。这是当前最大的风险。
2. **Live2D 未出像素**，且 `GL_MAX_TEXTURE_SIZE` 未读——若 < 8192，Mahiro 原始 atlas 在这台设备上
   不可能直接上传。详见 `ROADMAP.md` 的两道设备门槛。
3. **debug APK 约 6.3 MB**，已不含 Cubism 官方示例资源（移除 `:app → :core-live2d` 依赖的直接结果）。
4. **客户角色资产与音色授权**均有边界：IP 角色不得作为可再分发 APK 的默认资源；
   未获授权不得克隆特定真人声纹。
5. 诊断补丁已全部回滚，官方 Framework 与发行包逐字节一致（hash 已校验）。

---

## 治理规则

> **任何一项工作，如果连续两轮没有缩小 G1/G2 的交付缺口，就停下来重新评估，而不是继续深挖。**

（设立原因：曾有一整个 session 花在 Live2D 上，而它当时是最不产品关键的一环。）
