# Phase 2B-0 — Runtime / ABI / Reuse Gate

审计日期：2026-09-25。Phase 2A 保持用户审查 PASS。本轮仅源码与文档审计，没有引入 SDK、模型、Android 代码或新依赖，没有接受第三方许可。

## 结论与证据边界

后续 Binary Audit 已执行，当前结果以 LIVE2D_BINARY_AUDIT.md 为准：制品/API配套确认，ARM64/x86 的16KB RELRO静态检查失败，Runtime未执行，overall仍NOT PASS。下表保留首次审计时点状态，不作为最新制品状态。

| 检查 | 状态 | 证据/剩余工作 |
|---|---|---|
| 复用路径与源码职责 | STATIC REVIEW COMPLETE | 下表固定 commit、路径、符号及采用方式 |
| 官方声明 ABI | VERIFIED FROM SOURCE | Core/README.md 列 ARM64、x86、x86_64；Samples CHANGELOG.md 的 5-r.2（2024-11-07）移除 armeabi-v7a |
| 实际 Core AAR ABI / 哈希 / 版本配套 | PENDING | 尚未取得经用户接受许可的官方 SDK；不能以 README 代替二进制审计 |
| SDK 与测试模型许可 | USER REVIEW PENDING | Core、Framework/Samples、模型资产分别审查；没有代为下载或同意 |
| 官方 sample / 合法模型运行 | NOT RUN | 取得制品后在模拟器/参考手机验证加载、GL 生命周期、motion/expression/physics/pose |
| CD12Max | TARGET VALIDATION PENDING | 到手先采集 ro.product.cpu.abilist；不阻止软件侧准备工作 |
| Manager 认证 | STATIC REVIEW COMPLETE / RUNTIME PENDING | 见 MANAGER_API_AUTH_AUDIT.md；暂无真实部署端点 |

**P2B-0 overall: NOT PASS。** 用户独立审查确认 Reuse design、Manager auth static audit、Live2D source/API audit 为 PASS；Licensing awareness 为 PASS WITH RELEASE GATE。Core binary audit 与 Runtime smoke test 均 PENDING。不以源码审计替代 Core 运行证据。

## 冻结来源

1. [Cubism Samples](https://github.com/Live2D/CubismJavaSamples/tree/8ce6803de7030a4816bccd8a3efcad69ea1d1186)：保留原冻结 SHA `8ce6803de7030a4816bccd8a3efcad69ea1d1186`。`5-r.5` 指向 merge commit `3a379181fd2be590a2afaaa382f9592091c79b8f`。根据用户独立 Git object 复核，两者 tree SHA 均为 `b48b56c447adc7738b84a36047ae635a9f384f59`，Framework gitlink 也相同：**commit identity differs / source tree identical / Framework gitlink identical / Core binary still requires independent verification**。这不是 Samples 源码不匹配，不需要为 commit ID 不同重新迁移 Samples。本次修订记录用户提供的对象证据，未重新拉取 GitHub。
2. [Cubism Framework](https://github.com/Live2D/CubismJavaFramework/tree/ed15cb21a466893381d1dbddce0da943c7fe9a0f)：Samples 的 Framework gitlink 为 `ed15cb21a466893381d1dbddce0da943c7fe9a0f`。Core 仍需从官方 SDK 独立锁定版本、SHA256 和 ABI。
3. [Wanyu](https://github.com/JieRobot/wanyu-ai-android/tree/f873e137e224192fbac72021536054ed4a5ad044)：`f873e137e224192fbac72021536054ed4a5ad044`，审计 main 快照，不跟随分支构建。
4. [AIRI](https://github.com/moeru-ai/airi/tree/a142a053fdc304666ba7caf8462678b79187f8ea)：`a142a053fdc304666ba7caf8462678b79187f8ea`，审计 main 快照，仅参考所列模块。
5. Xiaozhi Manager 沿用 `788f5301fdd60cc3a8ef74025bfeece9b82b94ce`，不升级服务端。

源码 checkout 位于忽略的 `.upstream/`，未复制到应用。Samples 5-r.1 `b0a5ab2fca965299a311e4efd4fbf9f2311a5cf2` 仅列为 ARMv7 历史版本审计候选，**不是已验证兼容方案**；如目标仅有 armeabi-v7a，须重新核对其配套 Core、Framework、模型版本和许可，禁止自研 renderer 兜底。

## 文件 / 符号 / 采用方式

Framework 路径前缀 `framework/src/main/java/com/live2d/sdk/cubism/framework/`；Wanyu 前缀 `wanyu-android/app/src/main/java/com/wanyu/ai/`。

| 来源与精确路径 | 符号 / 证据 | 决策 |
|---|---|---|
| Framework: ICubismModelSetting.java、CubismModelSettingJson.java | getModelFileName、getTextureFileName、getExpressionName/FileName、getMotionGroupName/Count/FileName、getPhysicsFileName、getPoseFileName、getEyeBlinkParameterId、getLipSyncParameterId | DIRECT；不另写 model3 parser |
| Framework: model/CubismModel.java | getParameterCount、getParameterId、getParameterMinimumValue、getParameterMaximumValue | DIRECT；AvatarRuntimeInfo 是适配结果，不是第二套参数解析器 |
| Samples: Sample/src/full/java/com/live2d/demo/full/LAppModel.java | setupModel、loadModel、loadExpression、loadPhysics、loadPose；注册 Expression/Physics/Pose/EyeBlink/Breath/Look Updater | SDK / REFERENCE；官方更新器单一所有权，不重复 blink/breath |
| Wanyu: data/repository/Live2DModelRepository.kt | importModel、extractModelZip、findModelLayout、extractZip、resolveSafe；验证完成前删除同名目录、copyTo 无完整预算 | ADAPT 流程，不原样复制；保留旧 Avatar |
| Wanyu: live2d/model/EmotionMapper.kt | resolve，精确匹配→关键词评分→fallback | ADAPT 为导入时建议；用户保存的映射优先，运行时不反复模糊决策 |
| Wanyu: live2d/lipsync/LipSyncController.kt | shapeAmplitude：threshold/gain/power/attack/decay；另有 Visualizer 与模拟 pulse 路径 | ADAPT 纯 shaping；拒绝 MediaPlayer/Visualizer/pulse |
| Wanyu: live2d/driver/plugin/LipSyncPlugin.kt | onFinal 写固定 ParamMouthOpenY/ParamMouthForm | 不原样采用；使用模型 LipSync group 默认和用户 override |
| Wanyu: live2d/driver/DrivePlugin.kt | DriveContext、onPre/onPost/onFinal，注释注明 AIRI 来源 | REFERENCE；仅规划 AvatarStateDriver / EmotionDriver / LipSyncDriver |
| AIRI: packages/stage-ui-live2d/src/composables/live2d/motion-manager.ts | MotionManagerPluginContext、useLive2DMotionManagerUpdate、disableLive2DSdkBreath | REFERENCE；不移植 Vue/Pixi 插件栈；避免与 SDK 重复执行 |

本轮无实质源码复制。未来改编 Wanyu 需保留 `/LICENSE` MIT 文本及 `Copyright (c) 2026 陈兵`；实质采用 AIRI 需保留其 `/LICENSE` MIT 文本及 `Copyright (c) 2024-PRESENT Neko Ayaka`。这些是第三方版权，不因本项目作者匿名要求而删除。本项目提交作者仍为 yunzenn。

## 实施前冻结的边界

- 导入：SAF → 有界复制 → 独立 staging → ZipInputStream + 安全策略 → 官方 metadata → 全部引用/纹理检查 → Core load test → promote → 切换 active。失败只清理本次 staging。
- 安全策略必须限制压缩输入、总解压/单文件大小、文件数、目录深度、重复规范化路径及纹理像素；路径和模型内引用均须位于导入目录。拒绝歧义多模型包、非法绝对路径、路径逃逸；不递归展开嵌套压缩包。官方 JSON parser 不替代这些策略。
- 导入使用 `avatars/<avatar-id>/versions/<uuid>/` 独立新版本。验证成功后用 Android `AtomicFile` 更新 `active-avatar.json` 内的 active version ID；不是 filesystem symlink，不开发事务文件系统。失败只删本次新版本，不得先删旧目录。新模型成功激活前保留旧版本；后续测试覆盖失败、取消和进程中断恢复。具体限额在实现前量化并测试。
- PCM 为唯一声音事实源。shaping 使用 PCM16 RMS；固定窗口或时间相关系数，不能照搬不同回调频率的常量。包络须与播放进度对齐，不能解码排队时提前张嘴；interrupt/flush/generation 切换清空包络。不增加第二播放链。
- 嘴型：模型 LipSync group 提供默认，用户保存映射覆盖默认，传统参数名只作建议；验证 ID 实际存在，并按官方参数范围约束。表情/动作建议也是可编辑、可持久化配置。
- 保留 Views、三模块、minSdk28 和构建基线。禁止为复用引入整套 Compose/Hilt/Room/NDK，禁止自写 moc/motion/physics/pose/expression 引擎。

## 下一步可验收输入

### Core 官方制品检查清单（全部待验）

Core 只允许来自用户接受许可后下载的官方 Java SDK，禁止从 Wanyu、ChatWaifu 或其他第三方仓库复制 AAR / .so。公开可下载不代表可再分发。

- SDK release/version、原始 ZIP SHA-256、Live2DCubismCore.aar SHA-256。
- AAR AndroidManifest/minSdk、classes.jar API 与 Framework 调用兼容。
- JNI ABI 清单及每个 .so 的 ELF architecture，不能只看目录名。
- 16 KB page compatibility：逐库 ELF LOAD segment alignment；后续生成 APK 的打包对齐另验。静态对齐不替代 16 KB 环境运行证据，未执行须注明。
- Core CHANGELOG、Framework gitlink、Samples/Core/Framework release compatibility。
- RedistributableFiles 与 LICENSE 实际文件、允许分发范围及模型独立授权。

### Runtime 验收清单（全部待验）

使用两个结构不同、授权明确的标准 Cubism 模型，不改 Kotlin 业务代码完成切换。逐模型记录 moc3、textures、Idle motion、显式 motion、expression、physics、pose、eye blink、后台/恢复、GL surface recreation、clean release。缺某资源不能冒记 PASS；选取模型组合覆盖全部能力，对缺失项明确 N/A 及替代覆盖模型。

记录 SDK/设备/API/ABI、模型版本与许可、操作步骤、预期/实际、日志及资源释放结果；反复 surface recreation 后不得残留失效 GL 资源或崩溃。此为软件 Runtime Gate，不替代 CD12Max 性能/温度/十分钟 Gate。仅显示模型不算通过。

### EXPANDABLE_APPLICATION / RELEASE BLOCKER

允许导入不确定数量模型的产品高度可能落入 Expandable Application；最终分类与许可由 Live2D 确认。[官方规则](https://www.live2d.com/en/sdk/license/expandable/)要求该类应用发布前接受审查并签署特殊 Publication License，通常适用于个人/小规模主体的豁免不能直接套用。核查日期：2026-09-25。

独立 Release Gate：取得适用分类与发布授权记录、核清 SDK 可再分发文件及模型授权后才可关闭；不能因为 Runtime PASS、免费发布或开源就自动关闭。此 Gate 不阻止在合规 SDK/模型许可下开发和本地验证，不等于已经取得发布许可。

Manager 登录、验证码/SM2、token 存储及实际授权 UX 完全移出 Phase 2B；本阶段只定义 XiaozhiAgentRepository 接口契约。Phase 2C 再决定手机配对、一次性授权或受限 device-scoped adapter。

由用户自行阅读并接受[官方 SDK 下载页](https://www.live2d.com/sdk/download/java/)的条款，提供下载后的 SDK 本地路径；测试模型还须确认对应资产使用条款。随后审计 AAR 的 SHA256、jni ABI、SDK/Core/Framework 配套关系，运行官方合法模型，再进入 P2B-1。未经许可确认不下载 Core、不分发模型。

本轮未重新运行 Android 测试，因为应用代码与依赖未改；现有 APK SHA256 复核仍为 `6BE764ACB506C38632057A188B85F5E5C800081D7512026B6D4691FB7FE1EDF2`。此哈希复核不是新增测试 PASS。
