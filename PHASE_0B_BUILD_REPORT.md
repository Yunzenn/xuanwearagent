# Phase 0B Build Report

日期：2026-09-25  
范围：Evidence Freeze + Minimal Android Baseline。未进入 Phase 1。

## 结论

Phase 0B clean build 通过。三模块工程可配置、可测试、可 lint、可生成有效 debug APK；没有 CD12Max 真机，因此没有填写或伪造 Device Probe 结果。

## 锁定版本

| 项目 | 版本 |
|---|---|
| Gradle Wrapper | 8.9 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin Gradle Plugin | 2.1.0 |
| JDK | Oracle 21.0.8 |
| JVM bytecode target | 17 |
| compileSdk | 35 |
| Build Tools | 35.0.0 |
| minSdk | 28 |
| targetSdk | 35（Probe baseline，后续复核） |
| Platform Tools / ADB | 37.0.1 |

## 执行命令

```powershell
sdkmanager.bat --sdk_root=D:\AIwatch\.android-sdk "platforms;android-35" "build-tools;35.0.0" "platform-tools"
.\gradlew.bat clean test lint assembleDebug --no-daemon
apksigner.bat verify --verbose --print-certs app\build\outputs\apk\debug\app-debug.apk
zipalign.exe -c -v 4 app\build\outputs\apk\debug\app-debug.apk
aapt.exe dump badging app\build\outputs\apk\debug\app-debug.apk
```

## 测试

`PcmFrameAccumulatorTest` 有 4 个独立测试，在 debug/release unit-test variant 中各执行一次，共 8 次执行：

1. 任意 chunk 跨界累积，输出精确 960 samples frame，余数保留。
2. 959 samples 不输出短帧；补入第 960 个 sample 后才输出。
3. 单个大 chunk 输出所有完整帧，只保留尾部余数。
4. `reset()` 只在显式调用时丢弃 pending samples。

结果：0 failures、0 errors、0 skipped。没有删除、跳过或弱化测试。

## Lint 与构建

- 最终命令：`BUILD SUCCESSFUL`，138 actionable tasks。
- `app` lint：No issues found。
- `core-audio` lint：No issues found。
- APK 签名：debug certificate，APK Signature Scheme v2 验证通过。
- ZIP alignment：验证通过。
- APK manifest：`minSdkVersion=28`、`targetSdkVersion=35`、`compileSdkVersion=35`。

## 产物

- APK：`app/build/outputs/apk/debug/app-debug.apk`
- 大小：2,382,819 bytes
- SHA-256：`5CC66C0541C73A572E73715BB9E515096B76AFF4C9682E3BBDE0135706F84258`
- Unit test reports：`core-audio/build/reports/tests/`
- Lint reports：`app/build/reports/lint-results-debug.html`、`core-audio/build/reports/lint-results-debug.html`

## 尚未完成的 Gate

- Phase 0C Device Probe：需要 CD12Max 连接 ADB。
- Cubism Gate B：需要官方 sample、合法模型及 CD12Max 10 分钟数据。
- Audio Gate C：需要真机 AudioRecord/AudioTrack + Concentus loopback。
- P0-SERVER-AUDIO-CONTRACT：需要服务端和真实下行 Opus 数据验证宣告采样率等于实际采样率。

Phase 1 的 DeviceIdentity、Bootstrap、业务 WebSocket、conversation、Live2D Avatar、Character 和 MCP 均未实现。
