# Dependency Decisions

1. Android：尚未选定 Gradle/Kotlin 版本，等工程基线与 API 28 编译验证后锁定。
2. WebSocket：优先 Android/Kotlin 成熟客户端；不自研 WebSocket 栈。
3. Opus：Concentus `3885c4e46513ef0fc81fca100189e54f1714c6ca` 为首选；许可已核为 BSD 风格条款，仍须做 16 kHz/60 ms round-trip 与目标机性能验证。
4. Storage：使用 Storage Access Framework 选择文件，校验后复制到 App Private Storage，默认不长期依赖原 URI。
5. Live2D：使用官方 Cubism Core/sample；不自研 moc parser、mesh、physics、motion、expression。
6. DataStore：Phase 1 的 DeviceIdentity 使用 DataStore；不读取真实 MAC。
7. MCP：Phase 1 不依赖；P1 只按白名单选模块。
8. ZIP：使用平台 `ZipInputStream` 或成熟库；自研仅限 Zip Slip、文件数、压缩/解压体积、路径和嵌套限制策略。

## Phase 0B build baseline（2026-09-25）

- Gradle Wrapper：8.9
- Android Gradle Plugin：8.7.3
- Kotlin Gradle Plugin：2.1.0
- compileSdk：35
- Build Tools：35.0.0（显式锁定）
- minSdk：28（保持 CD12Max Android 9 安装下限）
- targetSdk：35（仅当前 Probe baseline，产品阶段仍需复核行为变化）
- JDK：Oracle JDK 21.0.8；编译字节码目标 Java 17
- Platform Tools / ADB：37.0.1
