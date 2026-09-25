# P2B-0 Binary Audit — R5

## 结论

**Binary audit: PASS WITH CONDITIONS；16 KB static compatibility: FAIL（ARM64/x86 RELRO）；Runtime: NOT RUN；P2B-0 overall: NOT PASS。**

未修改 App、未执行 SDK/native library、未迁移源码、未提交 Core。使用 dependency-management 的版本固定与制品核验流程；不作完整漏洞扫描或法律授权结论。

## 制品与版本

用户提供原始官方包，位置和大小见 LIVE2D_ARTIFACT_RECEIPT.md。哈希用于固定本地制品，并非独立厂商签名认证。

- ZIP SHA256 `2BCCF7B3A6CC6FDAEADE2CF1D7A6E544CB4AD71912D0131565648BAA6D515BD3`
- AAR SHA256 `3F05DA57AB855E803000E6353888DD561C47758598C6C0200DCD0109312705F8`
- cubism-info.yml：version `5-r.5`，created `20260529T153028+0900`；core `544bd1cb00d17bf135521a76de2615c70c8b203f`，framework `85abaf7c4c6398974e8a14666415886fb8ba0f30`，samples `de06e787cd6bb521270352f06a7471cb41a93364`。这些是发行包元数据，不能自动当作本项目冻结 Git commit。
- 实际比较 SDK Framework/framework/src 的 142 个文件与冻结 Framework ed15cb…：统一 CRLF/LF 后无内容差异、无缺失/额外文件。Sample/src/full 12 个、minimum 11 个文件与冻结 Samples 8ce680…同样一致；未声称整个 SDK 与 Git 仓库逐字节一致。
- Core CHANGELOG 最新版本升级条目为 2026-01-08 `06.00.0001`；2025-07-17 声明支持 Android 16KB。运行时 getVersion 尚未调用，不能将文档版本冒充实际运行返回值。

## AAR / Java API

AndroidManifest package `com.live2d.sdk.cubism.core`，minSdk **21**，不要求提高项目 minSdk28。aar metadata：minCompileSdk=1、minAndroidGradlePluginVersion=1.0.0、coreLibraryDesugaringEnabled=false。

classes.jar 含 23 个 class，class major version 51。`javap -public` 检查公开签名（未反编译方法体）：Live2DCubismCore.getVersion/getLatestMocVersion/getMocVersion/hasMocConsistency；CubismMoc.instantiate/instantiateModel/close；CubismModel.update/getParameterViews/getDrawableViews/getOffscreenRenderingViews/close 等。

额外静态 API 验证：JDK javac 将冻结 Framework 的 105 个 main Java 文件，以 classes.jar + Android35 android.jar 为 classpath、-proc:none、UTF-8、source/target8 编译，exit=0。出现4条旧source/target及bootstrap classpath警告、unchecked提示；未隐藏警告。输出仅在忽略目录 sdk-r5/binary-audit/framework-classes。此为 API 链接编译证据，不是 Android28运行或 JNI ABI 调用证明，不是 App Gradle build。

## 实际 native ABI 与对齐

每个 JNI 目录仅有 libLive2DCubismCoreJNI.so；无 armeabi-v7a。

| ABI | ELF class / machine | bytes | LOAD | RELRO vaddr + memsz | 16KB RELRO |
|---|---|---:|---|---|---|
| arm64-v8a | ELF64 / 183 (AArch64) | 110360 | 3段均16384，offset/vaddr同余 | 120704 + 2176 = 122880 | FAIL，余8192 |
| x86 | ELF32 / 3 (386) | 112856 | 3段均16384，offset/vaddr同余 | 125552 + 1424 = 126976 | FAIL，余12288 |
| x86_64 | ELF64 / 62 (AMD64) | 116992 | 3段均16384，offset/vaddr同余 | 127536 + 3536 = 131072 | PASS |

SO SHA256：

- ARM64 `6b1a457b619427e8387133a01c84f867ca8f1cecd3c74886cec7c2fba7dc262b`
- x86 `039c9e8bd8442149dd95e3bc9854b9408b0781a6511a69f5a80c3896e28e393d`
- x86_64 `d88f85dd6ddc031cca93d289cc02312983f89e0c38019a20f7f3d6e8fe2fd53c`

[Android官方检查说明](https://developer.android.com/guide/practices/page-sizes)同时要求 ELF LOAD 与 RELRO 末端检查，之后仍须 APK zipalign 和16KB环境测试。因此本轮只能确认 LOAD 对齐，不能由 SDK CHANGELOG 推导完整16KB PASS。未运行实际崩溃复现；RELRO静态异常是需要官方解释/兼容制品或运行验证的风险，不冒称已观察到 crash。不修补闭源SO、不自动升级SDK。4KB/API28目标兼容仍须Runtime，不被本结论自动否定。

## 许可及剩余项

Core/LICENSE.md 指向 Proprietary Software License；Core/RedistributableFiles.txt 明确列 android/Live2DCubismCore.aar，且再分发以协议为条件。不是无条件可上传Git；模型许可和 EXPANDABLE_APPLICATION RELEASE BLOCKER 均不关闭。

待处理：ARM64 16KB RELRO风险、最终APK打包对齐、native运行getVersion、两个合法模型的完整生命周期、真实设备/16KB系统验证。P2B-0不放行，未开始P2B-1产品集成。

## 可复核命令与保全

`python evidence/tests/audit_cubism_binary.py` 只读取 ZIP/ELF header/class metadata，输出哈希/段/源码比较结果；无反汇编、无native加载。公开API可用 `javap -public -classpath <本地classes.jar> <class>` 复查。

App APK SHA256 仍为 `6BE764ACB506C38632057A188B85F5E5C800081D7512026B6D4691FB7FE1EDF2`。`git ls-files third_party/live2d` 无输出。Core及编译输出留在忽略目录；提交候选仅审计脚本和报告，没有新增应用依赖。
