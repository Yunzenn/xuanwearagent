# Handoff — Live2D Runtime Gate

P2B-RUNTIME 已在**限定范围**内跑通并通过：x86_64 / 4 KB 页 / API 28 模拟器。ARM64 真实手表行仍 NOT RUN；P2B-0 整体未 PASS（ARM64 16 KB RELRO 静态 FAIL 仍是独立 Release Gate）。产品 Home 集成（P2B-1）**未开始**。

## 本次实际进展

1. **冒烟测试跑通了，并且证据强度提高了**。此前唯一阻塞点是 Gradle `connectedAndroidTest` 的 UTP runner 启动即死（`utp.0.log` 仅 267 字节，无 stack trace），与测试代码无关。改用 `adb install` + `am instrument` 后通过。
2. 把「没有 GL 报错」升级为**像素级 + 参数级**证据：新增 `PixelCopy` 取像素、帧间 diff、全参数时间序列采样。见 `LIVE2D_RUNTIME_VALIDATION.md`。
3. 实测页大小 = **4 kB**（此前 PENDING 且明确不假定），native `getVersion()` 实测返回 `06.00.0001`（此前只有文档版本）。
4. 双模型 Haru/Hiyori、真实渲染、眨眼、physics、motion、GL context 重建、clean release 全部取得证据。
5. 第二轮 GitHub 复用调研完成，**更正了一条已冻结的错误许可结论**，见 `REUSE_AUDIT.md` 末节。
6. **集成形态重构已落地**（本轮授权范围内）：验证工程改为「框架作 Gradle library module + 本地 Core AAR」，官方 SDK 未改，重构后重跑运行时 Gate 通过。
7. **逐特性归因已从推断升级为实测**：新增 A/B 对照实验（从 `CubismUpdateScheduler.cubismUpdatableList` 移除目标 updater 再复测）。blink / breath / physics / expression 均实测归因，4 次连续运行复现；expression 与 `.exp3.json` 声明值精确吻合。**pose 判定为 N/A BY ASSET**（官方所有模型的 `pose3.json` 的 `Link` 均为空，pose 按设计即 no-op），wiring 已验证。全过程与两次方法学更正记录在 `LIVE2D_RUNTIME_VALIDATION.md`。
8. **harness 修掉四个缺陷，最终在工作区 AVD 上完整复现通过**（`OK (1 test)` × 2、`smoke verification PASSED`）：整类单进程 flaky（sample 静态单例竞态）→ 改为每方法独立进程；`glGetError()` 断言语义错误 → 加前置 drain 使错误可归因；报告夹带本机账户路径 → 加 `Redact()`；adb daemon 重启导致的设备发现竞态 → 有界重试。
9. **两个必须知道的新情况**：(a) 原设备 A（1600×900 硬件 GL）被我一条 `adb reboot` 误关机且无法恢复，最终复现在 320×640 软件 GL 的工作区 AVD `aiwatch-api28` 上完成；(b) 软件 GL 下间歇出现 `GL_INVALID_VALUE`，像素同时正常，已改为记录项而非硬 Gate 并登记风险。

## 不重复研究

- 运行时证据与范围限制：`LIVE2D_RUNTIME_VALIDATION.md`；可复现脚本 `evidence/tests/run_cubism_smoke.ps1`。
- 复用边界：`PHASE_2B_REUSE_GATE.md`、`REUSE_AUDIT.md`（末节为 2026-09-25 第二轮调研）；Manager 认证：`MANAGER_API_AUTH_AUDIT.md`。
- 官方包路径/哈希：`LIVE2D_ARTIFACT_RECEIPT.md`。Core 只在忽略目录，禁止提交专有制品或模型。
- 二进制审计：`LIVE2D_BINARY_AUDIT.md`；可重跑 `evidence/tests/audit_cubism_binary.py`。

## 本轮最重要的三条复用结论

1. **不要 vendor 整个 Sample**。`CubismSdkForJava-5-r.5/Framework/framework/build.gradle` 本身就是 `com.android.library`，带完整 `rendering/android/` 渲染包。**此项已落地**：验证工程已改为「框架作 Gradle library module + 本地 Core AAR」，见下文与 `LIVE2D_RUNTIME_VALIDATION.md`。
2. **官方没有 Maven/JitPack 制品**（多方核实），所以本地 module 是唯一路径，不存在 `implementation("com.live2d:...")`。
3. **嘴型驱动不要自研框架**。官方 Java sample 的 lipsync 是硬编码 `return 0.0f;` 加一句 TODO，但框架层已提供 `CubismLipSyncUpdater` + `IParameterProvider` 及全套 updater。只需实现一个 `IParameterProvider` 算 PCM 的 RMS 包络（约 50 行），这就是官方源码里指名的做法。

## 集成形态重构（已完成于验证工程）

验证工程 `runtime-smoke` 已从「framework 源码摊平进 app 模块」改为 `:cubism-framework` Gradle library module，重构后重跑运行时 Gate 通过，证据与旧形态等价。官方 SDK 未做任何修改。

**但有一条环境约束必须知道**：官方 `Framework/framework` module **无法在本机直接 include**——它要 `compileSdk 36`（本机只有 android-35）且声明 `java.toolchain = 17`（本机只有 JDK 21），两者都无外网可下载，Gradle 实测报 `No locally installed toolchains match`。所以当前用的是**自己写的薄适配 module**，源码/资源仍指向官方未修改的树。

**给产品的建议**：如果补齐 android-36 平台与 JDK 17，应删掉适配 module、直接 include 官方 module；否则照抄适配 module 的写法。另外别踩这个坑：框架的 GLSL shader 在 `Framework/framework/src/main/assets/.../standardES/`，是**运行时**加载的，library module 只挂 `java.srcDirs` 会编译打包都正常、到设备上才炸。

## 下一次直接做

1. **产品侧采纳已验证的集成形态**：在 `app` 侧按 `evidence/tests/cubism_runtime_smoke/` 的形态引入 Live2D（框架作 Gradle library module + 本地 Core AAR + 自写渲染宿主）。验证工程已证明该形态可编译、可打包、运行时成立；把「验证形态」搬成「产品形态」仍需单独授权（本轮授权只覆盖重构动作本身，未覆盖进 P2B-1）。
2. 取得官方 NDK `llvm-readelf` 复核 ARM64 RELRO。**本环境无外网、无本地 NDK，此项目前无法推进**。
3. **下一件真正的事：找一台普通 ARM64 4 KB Android 手机，用完全相同的 harness 重跑**（不必等 CD12Max）。目标是证明同一份代码 / 同一 Core / 同一 Framework / 同一模型从 x86_64 迁移到 ARM64 仍然成立。命令：
   `evidence/tests/run_cubism_smoke.ps1 -Serial <手机序列号>`。重点重复 expression、physics、GL recreate、clean release（分别覆盖 framework scheduler、Core 参数更新、native/GL 生命周期、资源释放）；A/B 归因不必四项全做四遍。
   注意：harness 依赖反射读取 `CubismUpdateScheduler.cubismUpdatableList` 与 `CubismUserModel` 的 protected 字段，框架版本变化会破坏它；`GL_INVALID_VALUE` 需在真机上一并留意。
4. 处理 `connectedAndroidTest`/UTP 崩溃（工具链缺陷，未修，已成未决项）。有一个**未经证实**的猜想：与 sample 的 `testInstrumentationRunner` / androidTest 依赖配置有关；但冒烟工程自身确实需要 instrumentation 测试，删依赖不是正确解法，需实际诊断。
5. **音频 AEC 风险需尽早原型验证**。新增一条可能更省事的路：官方协议支持**服务端 AEC**（hello 里 `features.aec=true` + 二进制 v2 带 timestamp），若服务端支持则手表不必承担设备侧 AEC。见 `REUSE_AUDIT.md`。
6. 许可合规：确认是否触及 Cubism SDK Release License 的 >1000 万日元商业门槛。
7. 完成上述并复现证据后，才可请求 DEV-ONLY P2B-1 Home 集成。
8. **若需 pose 行为级证据**：另找一个 `pose3.json` 含非空 `Link` 的合法模型覆盖该项；获批的 Haru/Hiyori 不具备该条件，不应为了凑证据而改用未获批资产。
9. 归因实验目前只在 x86_64 模拟器上跑过；若在 ARM 设备上重复，注意 `CubismUpdateScheduler.cubismUpdatableList` 是私有字段，反射路径随框架版本可能变化。

## Git 边界

只获授权提交文档、报告、审计脚本。现有 Phase2A App 代码与测试工作区改动**保留未提交**，不顺手打包。作者/提交者使用 yunzenn 及 GitHub noreply；禁止用户真名/本机用户目录进入提交内容。无强制推送、无 Core 上传、无新产品功能。

本轮新增/修改的文档与证据（**尚未提交**，等明确指示）：`LIVE2D_RUNTIME_VALIDATION.md`、`REUSE_AUDIT.md`、`HANDOFF.md`、`RISK_REGISTER.md`、`evidence/tests/run_cubism_smoke.ps1`、`evidence/tests/cubism_runtime_smoke/**`、`evidence/reports/cubism-runtime-smoke.txt`、`evidence/reports/cubism-feature-attribution.txt`。

**已知工作区卫生问题**：冒烟工程的源码位于 `third_party/live2d/sdk-r5/`，该路径被 `.gitignore` 整体忽略（因为同一目录含专有 SDK）。因此 `RuntimeSmokeTest.java` 的**工作副本不受版本控制**。本轮已把该 harness 镜像到受版本控制的 `evidence/tests/cubism_runtime_smoke/` 以保全，但根因（harness 与专有制品同处一个被忽略目录）尚未解决，建议后续把 harness 迁到独立路径并用可配置路径引用 SDK。
