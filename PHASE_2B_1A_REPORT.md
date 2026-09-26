# PHASE 2B-1A REPORT — DEV-ONLY Home Live2D Integration

**状态：NOT COMPLETE。完成标准 5 项中 2 项 PASS、3 项 NOT MET。**

**当前阻塞点：`P2B-1A-O1c — Host-Fidelity Oracle`。**
O1a 排除了配置三件套与 clear color；O1b 得到 **A FAIL / B FAIL**（官方语义与我们的语义在同一宿主下**都空白**），
据此排除 `RuntimeModel` 语义，把问题收敛到**两侧共有、且与已验证 harness 宿主不同的宿主层**。
详见下方 O1b / O1c 专节。

Home 目前稳定显示的是**静态 Avatar**；Live2D 运行时能初始化、能创建 renderer、能无错调用 `drawModel()`，
但**画不出任何可见内容**，因此第 1 项未达成，依赖它的第 2、4 项也无法判定为通过。
本报告不含任何把"编译/安装成功"当作"渲染成功"的表述。

## 完成标准（用户冻结的 5 项）

| # | 标准 | 结果 | 依据 |
|---|---|---|---|
| 1 | Home 显示合法 Live2D 模型 | **NOT MET** | 运行时报告 ready（`Live2D ready: Haru/Haru.model3.json textures=2`），renderer 已建、`glError=0`，但 surface 像素为单一颜色。系统截图见下 |
| 2 | 切回静态 Avatar 正常 | **NOT MET（未能验证）** | 依赖 1；Live2D 从未真正显示，故"切回"无从验证 |
| 3 | Live2D 初始化失败自动 fallback | **PASS** | 用真实失败路径验证（把模型指向不存在的资产），Home 保持可用 |
| 4 | background/resume + surface recreation 不崩 | **NOT MET（未能验证）** | 不崩这一半未观察到崩溃，但"重建后仍能渲染"无法验证，因 1 未达成 |
| 5 | APK 内 shader 资源断言通过 | **PASS** | 三层断言全部通过，见下 |

## 已交付并已验证的部分

### 结构（与用户冻结的结构一致）

```
Home (HomeActivity)
  ↓  avatarContainer: FrameLayout（静态 Avatar 在下，Live2D 视图在上）
Live2DAvatarView : GLSurfaceView
  ↓  拥有并释放
CubismRuntimeOwner（per-instance，显式生命周期，无静态实例、无 Activity 引用）
  ↓
:core-live2d（Framework adapter module）
  ↓
official Framework（未修改的 SDK 源码树）
  ↓
local Core AAR
```

新增文件：

- `core-live2d/build.gradle.kts` — adapter module，含 build-time shader 断言任务
- `core-live2d/src/main/kotlin/com/aiwatch/live2d/CubismAssets.kt`
- `core-live2d/src/main/kotlin/com/aiwatch/live2d/CubismFrameworkBootstrap.kt`
- `core-live2d/src/main/kotlin/com/aiwatch/live2d/CubismRuntimeOwner.kt`
- `core-live2d/src/main/kotlin/com/aiwatch/live2d/Live2DAvatarView.kt`
- `app/src/androidTest/kotlin/com/aiwatch/probe/Phase2B1AAvatarTest.kt`
- `settings.gradle.kts` 增加 `include(":core-live2d")`；`app/build.gradle.kts` 增加依赖
- `HomeActivity.kt`：`avatarContainer` + `staticAvatar` + Live2D 视图 + fallback + `onPause/onResume/onDestroy`

### 三条硬约束的落实

1. **未搬用 Sample 的 Activity 单例。** `CubismRuntimeOwner` 是 per-instance，由 `Live2DAvatarView` 创建、
   在 detach/`releaseRuntime()` 中释放；`CubismFrameworkBootstrap` 只持有 **Application context**（框架
   `startUp` 本身要求进程级一次性注入 load 函数，这属于框架设计而非 Sample 的 Activity 单例）。
2. **shader 三层断言** — 全部通过：
   - build-time（Gradle 任务 `verifyCubismShaderAssets`，挂在 `preBuild`）：`Cubism shaders verified: 36 files {frag=30, vert=6}`
   - packaged：APK zip 内实测 `packaged shaders = 36`（`.frag` 30 + `.vert` 6）
   - runtime readable：`shaderResourcesArePackagedAndReadable` **PASS**，逐个 `assets.open()` 读取非空
   - 额外佐证：运行期框架经我的 load 钩子实际读取了这些 shader（日志逐个列出
     `loadFile: com/live2d/sdk/cubism/framework/shaders/standardES/VertShaderSrc.vert` 等）
3. **任何 Live2D 初始化失败自动回落静态 Avatar** — 已用**真实失败路径**验证（非打桩）：
   `Cubism asset missing: 'does-not-exist/missing.model3.json'` → `fallbackToStaticAvatar()`，
   静态 Avatar 可见、Home 其他控件仍在且未被误启用。

### 构建与测试结果

- `:app:assembleDebug` / `:app:assembleDebugAndroidTest`：**BUILD SUCCESSFUL**
- `:core-live2d:preBuild` 触发 shader 断言并通过
- `am instrument -e class com.aiwatch.probe.Phase2B1AAvatarTest`：**Tests run: 3, Failures: 1**
  - `shaderResourcesArePackagedAndReadable` — PASS
  - `live2DFailureFallsBackToStaticAvatar` — PASS
  - `homeShowsLive2DModelSurvivesRecreationAndReleasesCleanly` — FAIL：
    `AssertionError: Live2D surface is blank: only 1 distinct colours`

### 制品

- `app-debug.apk` 27,985,682 B，SHA256 `F99BA68684E87865B8B007A9EE3BDDCDACF45EA5E4AD90E7A890EA4BC4AABC0A`
  （最终构建：白底诊断已还原为透明清屏后的产物）
- `app-debug-androidTest.apk` 1,631,153 B，SHA256 `9D757895FBA01CE302219E254C7DE30BE0D7FE86519D84FA4DA4F95C9723210D`
- 包内 native（**已复核 provenance**）：`libLive2DCubismCoreJNI.so` 只在 **arm64-v8a / x86 / x86_64 三个 ABI**
  出现，与 Core AAR 的 `jni/` 完全一致，三个 SO 的 SHA256 均与 AAR 逐字节相同。
  **AAR 内不含 `armeabi-v7a`**，APK 里的 `lib/armeabi-v7a/` 只包含 `libdatastore_shared_counter.so`
  （来自 androidx.datastore），与 Cubism 无关。
  本报告早先版本写的"四个 ABI 含 armeabi-v7a"是**读交错 `lib/*` 列表时的误记**，不是制品差异；
  AAR SHA256 `3F05DA57AB855E803000E6353888DD561C47758598C6C0200DCD0109312705F8` 与
  `LIVE2D_BINARY_AUDIT.md` 记录一致，本地 SDK 未被替换。
- 包内模型资产：`assets/Haru/Haru.model3.json` 等（来自被忽略的 SDK 目录，未进入版本控制）

### 模拟器截图证据

设备：工作区 AVD `aiwatch-api28`（`emulator-5556`，x86_64 / API 28 / 4 KB 页 / swiftshader）

| 文件 | 内容 |
|---|---|
| `.tools/p2b1a-shots/home-live2d.png` | 修复前：Avatar 区域纯黑，Home 其余 UI 正常 |
| `.tools/p2b1a-shots/home-live2d2.png` | renderer 尺寸修复后：仍纯黑 |
| `.tools/p2b1a-shots/home-white.png` | 诊断性白底清屏：Avatar 区域确实变成白色 → **证明 GL surface 内容在合成，模型确实没被画出** |

这些截图**证明的是失败**，不是成功，故不作为完成证据。

## 未解决的技术问题（精确诊断）

### 现象

模型与纹理加载成功、renderer 按真实尺寸创建、`drawModel()` 无 GL 错误，但输出为空。

### 实测诊断（一次性日志）

```
Live2D ready: Haru/Haru.model3.json textures=2 generation=1
renderer created 280x248 textures=2
diag canvas=1.0x1.875 parts=19 renderTextures=1 maskBuffer=256.0x256.0 view=280x248
      modelOpacity=1.0 mvp=0.94476193,0,0,0, 0,1.0666667,0,0, 0,0,1,0, 0,0,0,1
diag firstDrawModel glError=0x0
```

MVP 为纯缩放 `(0.945, 1.067)`、无平移，与 Haru 画布 1.0×1.875、视口 280×248 的预期一致，
几何上模型应占据视口纵向约 ±1.0 NDC，**不应不可见**。

### 已排除的假设（每条都做了实测）

| 假设 | 结论 |
|---|---|
| APK 缺 shader → 静默不绘制 | **排除**。36 个 shader 已打包；运行期经 load 钩子逐个成功读取 |
| renderer 用 1×1 创建导致 clipping mask buffer 为 0 | **已修**（改为按真实视口尺寸创建并支持 resize 重建）；修复后仍为空 |
| `glGetError` 显示 GL 失败 | **排除**。`glError=0x0` |
| 模型不透明度为 0 | **排除**。`modelOpacity=1.0` |
| 矩阵退化/模型被移出视野 | **基本排除**。MVP 为合理缩放，无平移 |
| 未调用每帧 `beginFrameProcess/endFrameProcess` | **已补**（与 sample 一致）；补齐后仍为空 |
| SurfaceView 未合成 / PixelCopy 取不到 | **排除**。白底诊断证明 surface 内容会显示；活动为 resumed |
| GLSurfaceView 需要特殊 EGL/格式配置 | **排除**。sample 的 MainActivity 只用 `setEGLContextClientVersion(2)` + `setRenderer` + `RENDERMODE_CONTINUOUSLY`，与本实现一致 |
| 需要 `setDrawableClippingMaskBufferSize` / `setupParentOffscreens` | **排除**。sample 从不调用它们 |
| sample 的 viewMatrix 是必需前置 | **排除**。该调用是死代码（`model.draw(projection)` 用的是 `projection`） |

### 尚未排除（下一步最该看的方向）

> **SUPERSEDED BY FIRST-FRAME DIAGNOSTICS BELOW**
>
> Earlier hypothesis: **mask / offscreen**.
>
> Current ruling: **do not investigate mask until rule H conditions justify it.** 首帧诊断已实测
> `masks=0`（采样 drawable 不走遮罩）且纹理全部绑定，因此"遮罩把 drawable 剔除"这一假设已被排除，
> 下面第 1、2 条仅作历史记录保留，**不得据此重新钻 FBO/mask**。

1. ~~Cubism 5 的 **offscreen/mask 路径**~~ — **已排除（见上）**。原假设：模型 `renderTextures=1`，
   掩码渲染目标分配或绑定不当会让遮罩内 drawable 被整片剔除且不产生 GL 错误。
   实测：采样到的可见 drawable `masks=0`，该机制不适用。
2. ~~`initialize(model)` 重载与 `maskBufferCount` 对齐~~ — **已排除（见上）**，同上理由。
3. **比对法**：验证工程（`third_party/live2d/sdk-r5/runtime-smoke`）用 sample 的 `LAppModel` 能画出同一
   个 Haru 模型。R1 已完成且仍空白，**该 oracle 现已由用户正式授权为 `P2B-1A-O1`**，见下方专节。

## 未解决的工程风险

1. **开发机构建依赖本地 SDK。** `:core-live2d` 直接引用被 gitignore 的
   `third_party/live2d/sdk-r5/`（框架源码 + shader 资产 + 样例模型）。这是 Live2D 只提供本地归档、
   无 Maven/JitPack 制品的直接后果，但意味着**新克隆无法构建**。需要专门的依赖获取脚本或文档化步骤。
2. **样例模型资产被引入产品包。** 当前把 SDK 的 `Sample/src/main/assets` 作为 assets 源加入，
   APK 体积因此增至 27.9 MB。仅限 DEV；正式分发前必须替换为自有或已授权的模型，并复核许可。
3. **未验证 `onDetachedFromWindow` 的释放时序**：`releaseRuntime()` 依赖 GL 线程在 3 秒内消费释放请求，
   否则回退为非 GL 线程释放（删除纹理可能失败）。功能未验证（因渲染本身未通过）。
4. **API35 x86_64 / 16 KB：native load PASS，行为级 ENVIRONMENT PENDING**（真实 `PAGE_SIZE=16384`
   设备上 APK 安装成功、`libLive2DCubismCoreJNI.so` 装载成功、EGL/GLES 初始化成功，无本 app native 崩溃；
   完整渲染/生命周期行为被该模拟器极慢的 EGL 环境阻塞）。**ARM64：NOT RUN / NO HARDWARE。**

## git diff summary

本轮新增/修改（**均未提交**）：

```
 M settings.gradle.kts                                   (+ include :core-live2d)
 M app/build.gradle.kts                                  (+ implementation :core-live2d)
 M app/src/main/kotlin/com/aiwatch/probe/product/HomeActivity.kt
?? core-live2d/                                          (5 个新文件)
?? app/src/androidTest/kotlin/com/aiwatch/probe/Phase2B1AAvatarTest.kt
```

（`app/build.gradle.kts` 等文件的改动中，有一部分是 Phase 2A 遗留的工作区改动，非本轮产生。）

## 下一步：P2B-1A-R1 — Reuse Known-Good Android Cubism Host（用户 2026-09-25 指令）

**不再继续手搓 renderer。** 已确认官方渲染器有**两条静默跳过路径**，它们都能产生"纹理已绑、drawable 可见、glError=0、却全空白"的现象：

```java
// CubismRendererAndroid.drawMeshAndroid()
if (textures.get(model.getDrawableTextureIndex(index)) == null) return;   // 纹理未绑定 → 静默跳过
...
glGetIntegerv(GL_CURRENT_PROGRAM, currentProgram, 0);
if (currentProgram[0] != 0) { ... glDrawElements ... }                    // program 为 0 → 同样静默跳过
```

### 最新首帧诊断（实测）

```
diag drawables=84 visible=73 boundTextures={0=1, 1=2} modelOpacity=1.0
     sample=[0 tex=1 bound=true vtx=54 idx=243 masks=0 op=1.00]
            [1 tex=1 bound=true vtx=51 idx=228 masks=0 op=1.00]
            [2 tex=0 bound=true vtx=37 idx=159 masks=0 op=1.00]
diag afterDraw currentProgram=0 glError=0x0
```

判读：

- **纹理分支已排除**：两个模型纹理都在 `boundTextures` 中（0→GL 1，1→GL 2），采样到的可见 drawable 全部 `bound=true`。
- **遮罩分支已排除**：采样 drawable `masks=0`，Haru 的这些 drawable 不走遮罩。
- **program 分支成为唯一剩下的静默跳过点**：`currentProgram=0`。**但此读数不能单独定案**——`drawModel()` = `saveProfile(); doDrawModel(); restoreProfile()`，`restoreProfile()` 本就可能把 program 复位。需要要么在 draw 循环内取证（需框架侧钩子），要么直接用已知可工作的实现替换当前 host。

### 冻结的复用来源与采用方式

| 来源 | 版本 | 许可 | 决策 |
|---|---|---|---|
| `Live2D/CubismJavaSamples` | `8ce6803de7030a4816bccd8a3efcad69ea1d1186` | Live2D Open Software License | DIRECT / 权威 |
| `llz121517/mea-pet-mobile` | `dc460e5d41cfe0592d841b19360b476af65d0538` | MIT | **ADAPT（优先级最高）**：`Live2dTextureManager`、texture upload、`renderer.bindTexture`、GL context 重建后重绑 |
| `marce1994/OpenClaw-Companion` | `ee581192c9efb46210864652addc315eebe85028` | MIT | **ADAPT**：透明 GLSurfaceView 配置、EGLConfig/PixelFormat、GL 线程内纹理上传。**不复制其过期的 Cubism API 调用**（本项目冻结在 R5） |
| `ZenkyR/foxgirlsupremacy` | `e7fa0b9e22aa1a6416566b35bb02d3b26577d02f` | MIT | SECONDARY：`TextureView + EGL14 + RenderThread` 仅在 GLSurfaceView 路线确实失败时启用 |
| `FatPanda8885/NekoWeather` | `4ef06fd9f9ddeeddb6798d0a07a72d6b628a44c2` | **GPLv3** | **REFERENCE ONLY，禁止复制源码** |
| `catkiss62/Sen-Live2D-Companion-Android` | `9fad90804711254b9736063266907075ba8dd041` | 未找到 LICENSE | **REFERENCE ONLY** |

**明确不搬**：`mea-pet-mobile` 的 `Live2dDelegate` / Activity 全局引用 / 全局 `textureManager`；`Maimchat` 整体（其 `ImprovedLive2DRenderer` 又把 `LAppDelegate`/`LAppLive2DManager` 搬了回来，等于回到 Sample App 架构）。

**实施规则（用户冻结）**：

- A. `CubismRuntimeOwner` 保持 per-instance，无 Activity/全局 renderer 单例。
- B. 用 ADAPT 自 MIT 的 `Live2dTextureManager` 风格实现替换当前自研纹理路径，GL 线程内严格按序：
  `decode → glGenTextures → glBindTexture → texImage2D → 纹理参数 → renderer.bindTexture(modelTextureIndex, glId)`。
- C. 绑定后断言：`renderer.getBoundTextures()` 含该模型纹理索引，且 `glIsTexture(glId) == true`。
- D. 确认 `model.update()` 在 `drawModel()` 之前执行。
- E. 只做首帧诊断：`drawableCount` / `visibleDrawableCount` / `boundTextureCount`，以及可见 drawable 的
  `textureIndex` / `textureBound` / `vertexCount` / `indexCount` / `maskCount`。**不新建常驻诊断框架。**
- F. 先用现有 GLSurfaceView 产品宿主，**暂不引入自研 EGL RenderThread**。
- G. 只有在 known-good GLSurfaceView + known-good 纹理路径**仍然空白**时，才做官方 `LAppModel` A/B oracle。
- H. 在以下条件全部满足前**不得进入遮罩调试**：存在可见 drawable、所有被引用纹理已绑定、`glIsTexture` 为真、
  `model.update()` 已执行、且已知一个无遮罩 drawable 的结果。
- I. **不进入 P2B-1B。**

### 边界冻结（R1 前，用户 2026-09-25 明确）

1. **`CubismAssets` 保持纯 I/O，不得长成 TextureManager。** 它现在只做
   `AssetManager → BitmapFactory.decodeStream → Bitmap`（`readRequired()` / `readBitmap()` / `shaderFiles()`），
   这是干净的；`readRequired()` 对打包资产的失败语义也正确（缺失即抛，交由上层 fallback，而不是静默返回空数组）。
   R1 要复用/适配的成熟纹理链路属于 GL 状态，必须放进**新增的独立类**：

   ```
   CubismTextureManager
   ├ decode
   ├ glGenTextures / glBindTexture / GLUtils.texImage2D
   ├ parameters / mipmap
   ├ renderer.bindTexture
   └ ownership / release
   ```

   **不得继续往 `CubismAssets` 塞 GL 状态。**

2. **未来用户导入 Avatar 不得复用这个 asset loader。** 当前它面对的是**可信 APK assets**；
   SAF 导入文件属于**不可信外部输入**，需要另一条来源链：

   ```
   PackagedAssetSource     ← 可信 APK assets（当前）
   ImportedAvatarSource    ← 不可信 SAF 输入（未来）
   ```

   否则 ZIP/路径校验、尺寸上限、私有存储边界会混在一起。

### R1-preflight 结果（本轮已完成）

| 项 | 结果 |
|---|---|
| 1. 修掉报告内 mask 的过期结论 | **完成** — 原"下一步最该看 mask/offscreen"一节已标注 `SUPERSEDED BY FIRST-FRAME DIAGNOSTICS`，并冻结"规则 H 不满足前不得进入 mask 调试" |
| 2. 更新 API35/16KB 状态 | **完成** — 改为 `native load PASS / 行为级 ENVIRONMENT PENDING`，不再写成"未运行过" |
| 3. 查清 `armeabi-v7a` provenance | **完成** — **无矛盾，是我报告里的误记**：Core AAR 只有 arm64-v8a/x86/x86_64；APK 中三者 SHA256 与 AAR 逐字节一致；`armeabi-v7a` 仅属 `libdatastore_shared_counter.so`。AAR SHA256 与 `LIVE2D_BINARY_AUDIT.md` 一致，制品未被替换。**无需清理依赖，也无需改 Binary Audit** |

### 复核后的验收标准（用户更新）

两级模型：

```
Level 1  Haru        → known-good SDK reference → 定位 renderer（已降级为 diagnosis oracle）
Level 2  Mahiro_V1   → CUSTOMER ACCEPTANCE MODEL → 客户实际需求
```

1. known-good Haru 在 Home 中渲染出真实像素
2. 客户 `Mahiro_V1` 在 Home 中渲染
3. Mahiro 的 physics 在其资产声明 physics 时可见更新
4. 静态 Avatar ↔ Mahiro 切换正常
5. Live2D 失败自动回落静态 Avatar（**当前已 PASS**）
6. background/resume/surface recreate 后 Mahiro 仍能渲染
7. 测量：纹理尺寸、纹理显存、模型加载时间、帧率、RSS

`motion / expression / pose` 若 Mahiro 资产本身没有，明确记 `N/A BY CUSTOMER ASSET`，**不得为让表格变绿而给模型硬加**。

## P2B-1A-R1 实施结果（本轮，授权范围内）

### 已实施

1. **新增 `core-live2d/src/main/kotlin/com/aiwatch/live2d/CubismTextureManager.kt`** —— GL-thread 纹理所有权：
   `decode → glActiveTexture(0) → glGenTextures → glBindTexture → texImage2D → min/mag filter + wrap →
   renderer.bindTexture → release / rebind on context recreation`，并带 `inJustDecodeBounds` 度量能力
   （记录尺寸与 `decodedBytes`，为 Mahiro 8192 测量做准备）。
   `CubismAssets` **保持纯 I/O**，未塞入任何 GL 状态。
2. **收敛 `Live2DAvatarView` host**：加入透明 surface 三件套
   `setEGLConfigChooser(8,8,8,8,16,0)` + `holder.setFormat(PixelFormat.TRANSLUCENT)` + `setZOrderMediaOverlay(true)`。
3. **`CubismRuntimeOwner` 改用纹理层**，删除自研 `uploadTexture`，首帧诊断加入
   `renderer.getBoundTextures()` 与 `glIsTexture` 断言。

### 实测结果：规则 H 前置条件**全部满足**，Haru 仍空白

```
texture[0] Haru/Haru.2048/texture_00.png 2048x2048 glId=1 decodedBytes=16777216
texture[1] Haru/Haru.2048/texture_01.png 2048x2048 glId=2 decodedBytes=16777216
renderer created 280x248 textures=2 uploaded=2 glIds=[1,2] bound={0=1,1=2}
                missingIndices=[] invalidGlIds=[] allValid=true
diag drawables=84 visible=73 modelOpacity=1.0 uploaded=2 glIds=[1,2] bound={0=1,1=2}
     missingIndices=[] invalidGlIds=[] allValid=true
     sample=[0 tex=1 bound=true vtx=54 idx=243 masks=0 op=1.00]
            [2 tex=0 bound=true vtx=37 idx=159 masks=0 op=1.00]
diag afterDraw currentProgram=0 glError=0x0
```

逐条对照授权规则 3 要求的首帧验证：

| 要求 | 实测 | 结论 |
|---|---|---|
| `visibleDrawableCount > 0` | 73 / 84 | **满足** |
| referenced textures bound | `bound={0=1,1=2}`，`missingIndices=[]` | **满足** |
| `glIsTexture == true` | `invalidGlIds=[]`，`allValid=true` | **满足** |
| `model.update()` executed | 每帧先 `update()` 后 `draw()` | **满足** |
| real non-background pixels produced | **仅 1 种颜色（空白）** | **不满足** |

**因此：纹理、遮罩、可见性、不透明度、矩阵、shader 加载、EGL 合成全部已被实测排除，问题不在这些层。**

### 下一步（授权规则 4，硬性）

**立即停止继续猜 GL 状态**，进入冻结的 A/B oracle：

```
official LAppModel   vs   our RuntimeModel
同一 APK · 同一模型(Haru) · 同一 Surface/EGL 环境
```

仅在两端路径共用一个 APK、一个 Surface、一个 EGL 环境时，才能把差异收敛到"我们的调用链缺了哪一步"，
而不是继续在单侧盲调。**在此之前不新增任何自研 GL renderer 逻辑。** 两条 stop condition 继续有效：
mask 调试在规则 H 之外仍 FORBIDDEN；P2B-1B NOT AUTHORIZED。

### 上游文件采纳与许可归属

| 上游 | 采纳内容 | 方式 | 许可 |
|---|---|---|---|
| `Live2D/CubismJavaSamples` @ `8ce6803de7030a4816bccd8a3efcad69ea1d1186` | model/update/draw/renderer 生命周期语义；`drawModel` 调用顺序 | **DIRECT（R5 冻结 Framework）** | Live2D Open Software License |
| `llz121517/mea-pet-mobile` @ `dc460e5d41cfe0592d841b19360b476af65d0538` | 纹理上传/绑定序列与"必须用 Application-scoped AssetManager，否则纹理静默失败"这一结论 | **ADAPT（仅模式，未复制任何上游源码文本）** | MIT |
| `marce1994/OpenClaw-Companion` @ `ee581192c9efb46210864652addc315eebe85028` | 透明 GLSurfaceView 三件套（alpha EGLConfig + TRANSLUCENT + media overlay） | **ADAPT（仅模式，未复制源码）** | MIT |

**说明（准确表述）**：实现工作区**未 vendor、未复制任何上游 MIT 源码文件**。上游实现是通过 GitHub 复审
**作为设计与 provenance 对照被阅读过**的；R1 的代码是依据冻结的行为契约**独立编写**的，**没有逐字复制上游源码文本**。
因此当前无需附带上游版权头；若后续要逐字采用其实现，必须先拉取原文、保留 MIT 版权与许可声明，并把上表更新为具体文件路径 + 行号。`NekoWeather`（GPLv3）与
`Sen-Live2D-Companion-Android`（无许可证）**未被采用**。

### R1 后的 instrumentation 与制品

- `am instrument -e class com.aiwatch.probe.Phase2B1AAvatarTest`：**Tests run: 3, Failures: 1**
  - `shaderResourcesArePackagedAndReadable` — PASS
  - `live2DFailureFallsBackToStaticAvatar` — PASS
  - `homeShowsLive2DModelSurvivesRecreationAndReleasesCleanly` — FAIL（`Live2D surface is blank: only 1 distinct colours`）
- `app-debug.apk` 27,992,402 B，SHA256 `C28D77B6824893CF2010E7BC549E7515EC8015E214549D0630E3BDA6D31C9136`
- Haru 截图（R1 后）：`.tools/p2b1a-shots/r1-haru.png`

### Mahiro_V1 状态

**未开始。** 按授权顺序，Mahiro 验证在 Haru 出像素之后；Haru 未通过，故不进入第 5、6 项，
也不做 8192 的 decode/upload/latency/RSS/FPS 测量。

## 客户验收模型 `Mahiro_GG`（本轮已解出并实测）

来源：随消息附带的 `Mahiro_GG_by_yuzuru233_03ea2de1ed4909ec92355de6e3d10f5e.rar`
（12,941,987 B，magic `52 61 72 21 1A 07 01 00` = **RAR5**）。

**解压方式（实测可行）**：本机无 7z / WinRAR / unrar，但 **Windows 自带 `tar.exe`（bsdtar/libarchive）可读 RAR5**：

```powershell
tar -xf "<rar>" -C D:\AIwatch\third_party\live2d\downloads\mahiro-gg
```

已解出到 `third_party/live2d/downloads/mahiro-gg/`，经 `git check-ignore` 确认被 `.gitignore:18 /third_party/live2d/downloads/` 覆盖，**不会进入版本控制**。

内容（实测）：

| 文件 | 大小 |
|---|---|
| `Mahiro_GG/Mahiro_V1.moc3` | 531,072 B |
| `Mahiro_GG/Mahiro_V1.model3.json` | 406 B |
| `Mahiro_GG/Mahiro_V1.physics3.json` | 14,804 B |
| `Mahiro_GG/Mahiro_V1.cdi3.json` | 6,144 B |
| `Mahiro_GG/Mahiro_V1.8192/texture_00.png` | 12,544,727 B |
| `Mahiro_GG/真寻.png` | 690,446 B（1000×1000，参考图，非运行时资产） |

`model3.json` 声明（原文）：

```json
"FileReferences": { "Moc": "Mahiro_V1.moc3",
  "Textures": ["Mahiro_V1.8192/texture_00.png"],
  "Physics": "Mahiro_V1.physics3.json",
  "DisplayInfo": "Mahiro_V1.cdi3.json" },
"Groups": [ { "Name": "EyeBlink", "Ids": ["ParamEyeLOpen","ParamEyeROpen"] },
            { "Name": "LipSync", "Ids": [] } ]
```

结论：

- **标准 Cubism 模型**，Java R5 路线无需更改。
- **无 Motions / Expressions / Pose** → 这三项 `N/A BY CUSTOMER ASSET`。
- **EyeBlink 有声明**（`ParamEyeLOpen`/`ParamEyeROpen`）→ 眨眼对 Mahiro **适用**，不是 N/A。
- `LipSync` 的 `Ids` 为**空数组** → 口型对 Mahiro 是 `N/A BY CUSTOMER ASSET`。
- **纹理实测 `8192×8192`，Format32bppArgb** → 单张未压缩 GPU 纹理理论约 **256 MiB**。这对 CD12Max 是
  **重点风险**（此前只是按目录名推测，现在是实测）。后续应在目标设备上做 8192/4096/2048 的真实 benchmark
  （VRAM/RSS、加载时间、FPS、画质、温度、崩溃）再定设备 profile；**不要现在就无脑降到 1024**，会严重损失画质。

**App 不需要支持 RAR。** RAR 只是客户交付模型的包装格式，P2B importer 仍保持 `SAF ZIP / folder → 安全校验 → Cubism 模型`。
除非客户明确要求"在手表里直接选 `.rar` 导入"，否则不引入 RAR 解压库。

**许可未关闭**：文件名含第三方作者 `yuzuru233`，包内**未见 LICENSE/README**。当前只能定性为
`technical customer reference model`，**不能**当作 `redistributable model`。开发与本地验证可继续，但正式 APK 分发前
必须由客户确认：是否拥有将该模型嵌入最终 APK、用于商业项目并分发给终端用户的授权。

## O1d — Draw-Loop Ground Truth：**已取得事实，但未定案**

对冻结 R5 Framework 施加 **debug-only diagnostic patch**（仅 `CubismRendererAndroid.drawMeshAndroid()` 一处，
备份在同目录 `*.o1d-backup`，可用 `.tools/o1d_patch.ps1 -revert` 撤销）。

### 实测事实（OFFICIAL 侧，`-s O1D`）

```
DRAWABLE index=81 program=4 isProgram=true linkStatus=1 textureIndex=0
         vertexCount=162 indexCount=849 masks=0 fbo=1 viewport=[0,0,256,256] glError=0x0
DRAW_ELEMENTS_REACHED=true index=81 indexCount=849 glError=0x0
GLREADPIXELS size=280x248 distinct=1 nonBackground=0 glError=0x0   ← 读的是默认 framebuffer(0)
```

由此**确定排除**：

- **shader/program 无罪**：`isProgram=true`、`linkStatus=1`（GL_TRUE）、program=4 有效。
- **未跳过绘制**：`DRAW_ELEMENTS_REACHED=true`，`indexCount=849`、`vertexCount=162`，`masks=0`。
- **无 GL 错误**；`glReadPixels` 对 FBO 0 读取结果均匀 → 模型**没有进入窗口 framebuffer**。

### 观察到但尚未定案的一点

在（被记录的）绘制中，**绑定的是 `fbo=1`（离屏目标）且 viewport 为 256×256**，而 256×256 正是此前的
`maskBuffer=256.0x256.0`。在 `runtime.draw()` 之前显式 `glBindFramebuffer(FRAMEBUFFER, 0)` **并未改变**
观测值 → FBO 是在 `drawModel()` 内部被绑定的。

**但不能据此断言"可见 pass 被误导进离屏目标"**：本次只记录**前 24 次** draw，而 Haru 有遮罩
（`renderTextures=1`）→ **掩码 setup pass 同样会调用 `glDrawElements`**，这 24 条很可能全部属于掩码 pass，
真正的可见 pass 在其后、未被记录。**两种解释当前无法区分**，不做结论。

### 下一次增量（同一 patch，成本很低）

1. 取消 24 次上限（或按"仅记录 `isGeneratingMask()==false`"过滤），并同时记录
   `isGeneratingMask()`、`lastFBO` 保存值、可见 pass 的 `fbo`。
2. 对 **FBO 1** 也做一次 `glReadPixels` 对比：若模型在 FBO 1 里 → 确认是离屏误导；
   若 FBO 1 也没有 → 回到 vertex/rasterization。
3. `glReadPixels` 需在 `eglSwapBuffers` **之前**读取才代表本帧前缓冲。

冻结项不变：C2 暂缓、mask 调试仍需规则 H 之外不得进行、Mahiro WAIT UNTIL HARU PIXELS、P2B-1B NOT AUTHORIZED。

## O1c 进行中（一行式记录，不再为措辞耗轮次）

- **C1 显式 `glViewport(0,0,w,h)`：已应用，未解决** —— A/B 两侧仍 `distinct=1`。
- 新线索：`CubismRendererAndroid.preDraw()` 会 `glEnable(GL_BLEND)` 但**从不设置 `glBlendFunc`**；
  官方 Sample 在 `LAppView.preModelDraw()` 里每帧显式设 `glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)`。
  这是我们两侧都缺、官方必做的一步 → 下一次先试 **C1b**。
- **C1b-PMA Fidelity：已应用，未解决** —— 整套官方 alpha 契约已对齐（decode `inPremultiplied=true`、
  `renderer.isPremultipliedAlpha(true)`、pre-draw `glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)`），
  A/B 两侧仍 `distinct=1` → **PMA / blend 排除**。日志中 `PMA_CONTRACT ... rendererPremultiplied=false` 是
  **读取时机假象**（renderer 懒创建，view 打日志时尚不存在）；runtime 自身 diag 实测为 `true`。
- 据此**降低 C2（top-level GLSurfaceView）预期**：嵌套 Surface 已能稳定 clear 与 PixelCopy。
  下一步优先级改为 **draw-loop 内直接取证**：shader program id、`glLinkStatus`、first `glDrawElements` reached。
- 其余待试：**C2** 让 `GLSurfaceView` 成为顶层 content view（官方是 `setContentView`，我们是嵌套子视图）。
- 若 C1b/C2 仍无效，则必须在 draw loop 内直接取证 `GL_CURRENT_PROGRAM`（`afterDraw currentProgram=0`
  因 `restoreProfile()` 复位而不可判），之后才有资格收敛到 shader program。

## O1b 结果：Official-vs-Ours Oracle — **A FAIL / B FAIL**（已结案）

按用户定义实施：同一 APK、同一 `Live2DAvatarView`、同一 Surface/EGL、**host 固定 `HARNESS_LIKE`**、
同一 Haru 资产、同一 viewport、同一 `CubismTextureManager`；**唯一变量 = 模型 setup/update/draw 语义**
（debug-only `EXTRA_RUNTIME_BACKEND_OFFICIAL`）。

| 侧 | 语义 | 结果 |
|---|---|---|
| **A `OFFICIAL`** | `OfficialOracleRuntime`：framework 的 `CubismUserModel.loadModel()` + `setupRenderer()` + 官方 update 顺序（含 `updateScheduler.onLateUpdate`）+ 官方 draw/MVP | **blank**（`distinct=1`） |
| **B `OURS`** | 当前 `CubismRuntimeOwner` | **blank**（`distinct=1`） |

```
test=o1bOfficialSemanticsProducesPixels → FAIL  AssertionError: OFFICIAL side is blank: distinct=1
test=o1bOurSemanticsProducesPixels      → FAIL  AssertionError: OURS side is blank: distinct=1
```

**A 侧自身初始化完全成功**（日志实测），所以 A 是一份能跑的官方语义实现，而不是坏掉的对照：

```
oracleA framework started=true initialized=true
oracleA ready: Haru/Haru.model3.json textures=2
oracleA renderer 280x248 uploaded=2 glIds=[1,2] bound={0=1,1=2} missingIndices=[] invalidGlIds=[] allValid=true
oracleA diag premultipliedAlpha=false frameworkStarted=true frameworkInitialized=true
```

### 裁定（触发用户预冻结的 "A FAIL / B FAIL" 分支）

```
Oracle A 不足以复现 prior positive control
→ 不能据此判 RuntimeModel
→ 问题位于 A 与 B 在本 APK / 本 View 宿主下【共有】的层：Framework integration / 宿主层
```

**这是一个有价值的收窄**：官方语义与我们的语义在同一宿主下**表现完全相同**（都空白），说明差异不在于
`RuntimeModel` 的 setup/update/draw 调用链，而在于**两侧共有、且与已通过验证的 harness 宿主不同的那部分**。
另外两条排除也已成立：`premultipliedAlpha=false` 在 A 侧被显式设置并记录，仍未出像素；
`CubismFramework.isStarted()/isInitialized()` 在 A 侧均为 `true`，bootstrap 时序也在两侧一致。

### 尚未被独立控制过的宿主差异（下一步应针对这些，而不是再动语义）

O1a 已排除的是**配置三件套**（EGL config / holder 格式 / z-order）与 clear color。O1b 又把**语义**排除了。
两者叠加后，剩下从未被单独切换过的宿主差异主要是：

1. **未显式调用 `glViewport(0,0,w,h)`。** 官方 Sample 在 `LAppDelegate.onSurfaceChanged` 里明确调用它；
   我们的 View 依赖 EGL surface 创建时的默认 viewport。`CubismRendererAndroid.doDrawModel()` 会
   `glGetIntegerv(GL_VIEWPORT)` 并在 mask 路径中使用它——viewport 异常会**不产生 GL 错误地**导致整片不可见。
2. **视图层级不同**：harness 把 sample 的 `GLSurfaceView` 作为 Activity 的**顶层 content view**
   （`setContentView`），我们的是 `FrameLayout` 里的嵌套子视图（且带静态 Avatar 同层）。
3. 首帧前是否已发生一次 surface 尺寸变更（我们的容器高度由布局决定）。

### 下一步（建议 `P2B-1A-O1c`）

把 oracle 从"语义忠实"推进到"**宿主忠实**"：在**同一 APK** 内让官方 A 路径补齐 harness 的宿主行为，
**一次只加一项**，首个从 blank 变 PASS 的项即当前最小 culprit boundary：

```
C1 + 显式 glViewport(0,0,w,h)          ← 首要嫌疑，改动最小
C2 + 让 GLSurfaceView 成为顶层 content view（临时 Activity，仅 debug）
C3 + 首帧前不经历 surface 尺寸变更
```

`C1` 尤其值得先做：它是官方 Sample 有、而我们两侧都没有的**唯一一条显式 GL 状态设置**。

## O1a 结果：Host Configuration Differential — **B1 仍然空白**（已结案）

按用户定义实施：**只切 host 配置**，`CubismRuntimeOwner` / `CubismTextureManager` / 模型 / projection /
update / draw **一律未动**。同一个 debug APK，通过 debug-only `EXTRA_HOST_MODE_HARNESS` 选择
`Live2DAvatarView.HostMode.HARNESS_LIKE`。

| 运行 | host 配置 | 结果 |
|---|---|---|
| **B0 `PRODUCT`** | RGBA EGL chooser + `TRANSLUCENT` + `setZOrderMediaOverlay(true)` + 透明 clear | **blank**（`distinct=1`） |
| **B1 `HARNESS_LIKE`** | 平台默认 EGL/holder/z-order + **不透明白 clear** | **blank**（`distinct=1`） |

```
test=o1aHarnessLikeHostProducesPixels  →  FAIL
AssertionError: HARNESS_LIKE host is still blank: distinct=1
Tests run: 4, Failures: 2
```

**裁定（触发用户预冻结的 "B1 仍 blank" 分支）**：

```
our RuntimeModel  fails under PRODUCT host
              and fails under known-good-style opaque host
```

- **已测试的 host 配置差异被排除（tested host-configuration delta excluded）**：R1 引入的透明 host 三件套
  与 clear color **不是当前根因**。此处只排除这组被实际切换并测量过的配置差异，**不等于**"所有
  Surface/EGL/composition 问题都绝对不可能"。
- clear color 层被排除（B1 用不透明白，仍无模型）。
- 由此得到强结论：**问题在我们这条 RuntimeModel 调用链或其 Framework integration，而不是 Surface/EGL/合成。**

### 术语更正（用户要求）

验证工程里官方 `LAppModel` 在本机同型号模拟器上出像素，记为：

```
Official path on same emulator/model:
PRIOR POSITIVE CONTROL
```

**不是** `O1 A-side PASS`。只有 official A 真正在**同 APK、同 View/EGL 条件**下通过，才可记作 O1 的 A PASS。
报告全文已按此口径统一。

### O1a 留下的可用资产

`Live2DAvatarView.HostMode` 这个 debug-only 开关**保留**，正是 O1b 需要的：按用户要求，
**O1b 应使用 `HARNESS_LIKE`（最简单的不透明/默认 host）**，避免把透明合成当成额外变量。

## 下一步：`P2B-1A-O1b — Official-vs-Ours Oracle`（O1a 已把入口条件判给了它）

```
同一 APK · 同一 GLSurfaceView · 同一 EGL context/Surface
同一 Haru · 同一 viewport(280×248) · 同一纹理资产
host 固定为 HARNESS_LIKE（默认/不透明）

      ┌─ A: official Sample LAppModel path
frame ┤
      └─ B: our RuntimeModel path
```

四分支裁定沿用（见上节）；若落到 `A PASS / B FAIL`，只比较会改变 renderer 行为的少数 checkpoint，
并**必须在 A 路径拿到"确实进入 `glDrawElements`"的正样本**后，才有资格把问题收敛到 shader program
creation/use（`afterDraw currentProgram=0` 仍不足以定案，因为 `restoreProfile()` 会恢复 GL state）。

冻结不变：**mask 调试仍 FORBIDDEN**；**Mahiro WAIT UNTIL HARU PIXELS**；**P2B-1B NOT AUTHORIZED**。

## 下一步：`P2B-1A-O1 — Official A/B Oracle`（用户 2026-09-25 授权）

同环境差分实验，**不是**再修我们的 renderer：

```
同一个 APK · 同一个 GLSurfaceView · 同一个 EGL context/Surface
同一个 Haru 资产 · 同一个 Framework/Core · 同一个 viewport(280×248)
      ┌─ A: official Sample LAppModel path
frame ┤
      └─ B: our RuntimeModel path
```

**debug/test-only**，允许 DIRECT 复用官方 Sample 的 model/update/draw/texture 代码，但**不得**带入
`LAppDelegate` singleton / `LAppLive2DManager` / Activity global / scene manager / Sample UI。
建议落位：`core-live2d/src/debug/`（或 `app/src/androidTest/`）下的 `oracle/` 包。

### 已经可以回答的一半（既有证据）

**A 路径在本机同型号模拟器上已经证明能出像素**：验证工程 `runtime-smoke` 用的正是官方 `LAppModel` 链路，
在 `emulator-5556`（API 28 AVD / 320×640 / swiftshader）上多轮 `OK (2 tests)`，
截图可见 Haru 成像、前景像素 73 万+。因此按冻结的四分支裁定，已可初步落到：

```
A PASS / B FAIL  →  环境与 SDK 无罪  →  对 model setup/update/draw 调用链做最小 diff
```

**但必须带一个 caveat**：验证工程用的是 sample 自己的 `GLSurfaceView`（仅 `setEGLContextClientVersion(2)`
+ `setRenderer` + `RENDERMODE_CONTINUOUSLY`），**不是同一个 APK、也不是同一个 View 配置**。所以 O1 仍必须做，
只是搜索空间已被压缩。

### O1 的**首要 checkpoint（本轮新识别的嫌疑）**

R1 按用户指令采用的透明 host 三件套，与**已证明能出像素的 harness 配置不同**：

| | 已验证能出像素（harness） | 当前产品 host（R1） |
|---|---|---|
| EGL config | 默认 | `setEGLConfigChooser(8,8,8,8,16,0)`（带 alpha） |
| holder 格式 | 默认（不透明） | `PixelFormat.TRANSLUCENT` |
| z-order | 默认（在 window 之后） | `setZOrderMediaOverlay(true)`（在 window 之前） |
| clear color | **不透明白** `(1,1,1,1)` | 透明黑 `(0,0,0,0)` |

因此 **O1 的最小差分应当先只动这一组变量**（用同一份我们的 runtime，在 harness 式配置与产品式配置之间切换），
再决定是否需要引入官方 A 路径。这一组差异比 `RuntimeModel` 的调用链差异更可疑，因为它恰好是"唯一被引入过、
却从未单独验证过"的变量。若这一步就能让 B 出像素，则无需再做 oracle；若不能，再按上表进 A/B。

### O1 的四分支裁定（用户预先冻结）

```
A PASS / B FAIL  → 环境与 SDK 无罪 → 最小 diff model setup/update/draw 调用链
A PASS / B PASS  → oracle 改变了环境/时序 → 回查 Home integration 差异
A FAIL / B FAIL  → 问题在共同环境/Framework integration 层 → 不再查 RuntimeModel
A FAIL / B PASS  → oracle 不忠实 → oracle INVALID，不据此修改产品
```

若落到第一分支，checkpoint 只比较会改变 renderer 行为的少数几项，不做全字段 dump：
`model/modelMatrix created`、layout matrix、`renderer width/height`、`renderer initialized against same model`、
premultiplied-alpha、`bound texture map`、`model.update()` 完成、visible drawable count、render order、
`MVP before drawModel`、**first actual shader program used**、**first glDrawElements reached**。
尤其要在 official A 里拿到"确实进入 `glDrawElements`"的**正样本**，再与 B 对比——只有到那时，
才有资格把问题收敛到 shader program creation/use（当前 `afterDraw currentProgram=0` 不能定案，
因为 `restoreProfile()` 会恢复 GL state）。

冻结不变：**mask 调试仍 FORBIDDEN**（规则 H 之外）；**P2B-1B NOT AUTHORIZED**；
**Mahiro 仍不提前跑**（顺序：Haru official A/B → 定位修复 → Haru Home pixels PASS → Mahiro 8192 → physics/EyeBlink/lifecycle/perf）。

## 仍存的风险（更新）

1. **渲染未打通**（第 1/2 项未达成）：**Current blocker = `P2B-1A-O1b — Official-vs-Ours Oracle`**。
   R1 的替换已完成（纹理层 + host 收敛），O1a 已结案（host 配置差异排除），下一步是 O1b。
2. **开发机构建依赖本地 SDK**（`:core-live2d` 引用被 gitignore 的 `third_party/live2d/sdk-r5/`）；新克隆无法构建，
   需要配套获取脚本。
3. **样例模型资产进入产品包**（APK 27.9 MB），仅限 DEV；正式分发前必须替换。
4. **客户模型 8192×8192 纹理**：约 256 MiB 显存量级，手表端未验证。
5. **客户模型授权未确认**。
6. **平台覆盖**：`API35 x86_64 / 16KB` = native load PASS / 行为级 ENVIRONMENT PENDING；
   `ARM64` = NOT RUN / NO HARDWARE。

## 建议的下一步（不擅自进入 P2B-1B）

```
Current blocker:
P2B-1A-O1b — Official-vs-Ours Oracle
```

1. **执行 `P2B-1A-O1b`（已授权）**：host 固定 `HARNESS_LIKE`，A = official Sample 的
   `setupModel` / layout / update 顺序 / `model.update` / renderer setup / draw-MVP 语义（第一轮与 B
   **共用已证明有效的 `CubismTextureManager`**），B = 当前 RuntimeModel。第一轮只回答"A 出像素吗 / B 出像素吗"。
2. Haru 出像素后立刻用 **Mahiro_V1** 做客户模型验收，并按验收标准第 7 项做纹理/性能测量
   （`inJustDecodeBounds` 先记录 8192×8192 与内存前后、加载延迟；**不引入自动 downsample**）。
3. 修复完成后再回到第 1/2/4 项验收；在那之前不把 Home 的 Live2D 视为可用。


---

## CORRECTION (2026-09-26, after O1d-2 and the plain-shader calibration)

O1d-2 refuted every Live2D-side explanation for the black frame, **including one recorded earlier in this
report**:

- **The render-target hypothesis is REFUTED.** Measured: `blendModeEnabled=false modelRenderTargets=0`.
  `beforeDrawModelRenderTarget()` returns early, so `afterDrawModelRenderTarget()`'s full-screen composite
  never executes. The model draws straight into framebuffer 0 at the correct viewport.
- Eliminated by direct measurement rather than inference: geometry/MVP (`u_matrix` read back correct, NDC
  in range on the same array GL actually receives), culling, fragment-discard state (depth/scissor/stencil
  off, colour mask full), blend factors, program link, texture binding and content (GL readback matches the
  CPU bitmap), client-side vertex arrays, VBOs, and the index path.
- **The instrumentation is now calibrated.** `PlainShaderProbe` draws a Live2D-independent magenta triangle
  into the same GLSurfaceView / GL thread / framebuffer 0 and is read back twice: `glReadPixels` and
  PixelCopy both report **8680** magenta pixels. Verdict: `HOST_AND_READBACKS_OK`.
- Consequently every earlier "blank" reading in this report is a true blank, and the fault is confined to
  the **Cubism program/path**.
- `twoModelSmoke` must NOT be used as evidence that the Cubism model rendered: the Sample's
  `LAppView.render()` draws background/gear/power sprites through a different shader, so its
  `foreground > 5000` assertion is satisfiable with zero model pixels. It stays `PRIOR POSITIVE CONTROL`,
  never `O1 A-side PASS`.

Status: `P2B-1A = NOT COMPLETE (2/5)`. All O1d / O1d-2 diagnostic patches were rolled back; the official
Framework files were restored from the distribution archive and hash-verified
(`CubismRendererAndroid.java 7A5277552D5EE832292487E492661F643A94EE5522475532D392F69278212834`,
`CubismShaderAndroid.java 61BEAA68635CF3ABDC420F4403EE450697883AD991C741BBD41CC96A7090DCE2`).

---

## RE-GATE (2026-09-26): product-first ordering

"Haru renders" is removed from the critical path. It gated the whole product behind its least
product-critical component, and Live2D may not even be viable on the target device: the Core AAR ships
`arm64-v8a/x86/x86_64` but **no `armeabi-v7a`**, and the customer model's 8192x8192 atlas is ~256 MiB per
texture. Neither is a rendering bug.

The deliverable is a wrist-worn companion APK for **CD12Max** (expected Full Android 9 / API 28, 410x502).
`C1 Device Probe = TARGET VALIDATION PENDING` — the APK has never run on target hardware. New ordering:

```
P0-1  WeChat-like small-screen chat UI
P0-2  push-to-talk -> server -> TTS reply
P0-3  Listening / Thinking / Speaking
P0-4  barge-in
P0-5  name / voice / personality
P0-6  CD12Max real device
P1    Live2D / animated avatar
```

Live2D becomes an optional `AvatarProvider` plugin. `core-live2d` stays in the tree but blocks nothing.
The Phase 2A static avatar remains the shipped face and its fallback behaviour (acceptance criterion 3) is
unchanged.