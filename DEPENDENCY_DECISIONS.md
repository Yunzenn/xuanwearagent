# Dependency Decisions

1. Android：构建版本已由 Phase 0B 锁定，详见下表。
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

Phase 1 JSON 边界使用 Gson 2.10.1（Apache-2.0，固定依赖版本）；协议行为参考已冻结 ESP32 文档，未复制上游实现。

Phase 1 身份与 HTTP 增量固定版本：DataStore core 1.1.1、kotlinx-coroutines core/android 1.8.1、OkHttp 4.12.0；MockWebServer 4.12.0 仅测试使用。启用 AndroidX，保持 minSdk 28、原有三模块和构建工具版本。
DataStore 单文件单实例，由 Application 持有，身份文件存入 noBackupFilesDir；不提供自动损坏重置，避免无提示重新绑定。
使用 DataStore core 自定义小型身份序列化，而非 Preferences：避免将可空/缺字段记录暴露给业务；JVM 和 Android 共用相同持久化实现。
Phase 1B 测试依赖固定：okhttp-tls 4.12.0、kotlinx-coroutines-test 1.8.1、AndroidX test runner 1.6.2、JUnit 4.13.2（Android instrumentation）。生产构建版本不变。新增 API 28 Google APIs x86_64 模拟器，WHPX 加速；不替代 CD12Max 验收。

Phase 1C：Concentus 不使用其 `1.0-SNAPSHOT` 版本号解析依赖，直接编译冻结 commit `3885c4e46513ef0fc81fca100189e54f1714c6ca` 导出的完整未修改 Java 源码归档（SHA256 与重建方式见 third_party/concentus/README.md）。core-audio 内部 JavaCompile/Jar task 生成 Java 8 依赖，生产 Kotlin/Java 仍目标 17，模块数仍为三个；构建时检查归档哈希，支持离线构建。APK assets 包含完整 LICENSE。
参考官方约束：https://developer.android.com/topic/libraries/architecture/datastore 。
