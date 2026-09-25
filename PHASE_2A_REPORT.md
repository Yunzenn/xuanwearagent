# Phase 2A — Product Shell + Static Avatar

状态：首批产品预览已实现，本地构建及联想模拟器回归通过，等待用户审查。与Phase 1C真实设备/服务器验收并行，不代表完整Phase 2或Live2D系统通过。

## 已实现

- Launcher改为Home；应用显示“小星陪伴 · 预览”，versionCode2 / versionName0.2.0-product-preview，applicationId不变，原设备身份不迁移、不重置。
- 深色原生Android Views，静态默认形象、角色名、连接状态、字幕、PTT、打断、连接/断开。宽横屏采用左右两栏，窄屏纵向滚动；不增加Compose或新模块/依赖。
- 使用已有DebugAudioSession/SessionCoordinator/AudioRecord/Concentus/AudioTrack，不另造音频链。字幕仅来自经过Coordinator处理的Stt及Tts文本，最长2000字符，flush时清除，不解析JSON或保存聊天历史。无服务器时不生成假回复。
- Ready及IDLE才可开始录音；权限不足只请求权限，需用户再次操作。普通触摸按住/松开，辅助功能click可开始/结束。取消手势不补尾帧。离开Home取消待连接任务、断开当前会话；关闭完成前不重连。
- 设置：本地角色名、静态形象导入/恢复、HTTPS Bootstrap地址保存/清除、Voice/Personality/Live2D说明、Probe工程诊断入口。声音与人格仍由服务器决定，未实现的能力无伪生效开关。
- Character文件、Server文件、Avatar文件相互独立；存入noBackupFilesDir/product，使用AtomicFile写入，设备Identity仍用原DataStore。服务器地址拒绝HTTP、userinfo、query、fragment和非法端口；不保存Bootstrap返回的WS令牌。

## 图片导入策略

SAF ACTION_OPEN_DOCUMENT → 限流复制到临时文件 → ImageDecoder真实头部校验 → 缩放 → 原子写入私有PNG。不申请广泛存储权限，不获取持久URI权限，不依赖原文件名或原文件后续存在。

只接收静态PNG/JPEG/WebP；最多10MiB、单边8192、总3200万像素，解码后最长边1024。拒绝动图/GIF/ZIP，不冒充Live2D importer。成功规范化副本不带原EXIF元数据；验证失败保留旧形象并清理本次临时文件。异常终止进程导致的孤立临时文件清理、后台导入恢复、多角色/AvatarPack及三层映射不在本批范围。

默认静态矢量形象为本项目新增绘制，不含第三方模型资产或Cubism SDK。

## 验证与修复记录

- 首轮编译发现跨模块可空字段smart-cast及View.background同名遮蔽；修为显式局部let/接收者后通过。
- 首轮lint发现矢量尺寸、无用资源和触摸performClick问题；调整矢量尺寸、删除未用资源、保留触摸结束与无障碍click各自语义，未抑制lint。
- 实际横屏截图发现操作按钮落到屏幕下方，改为双栏；禁用按钮改为低亮度底色，避免离线误导。
- 本轮原有单元测试报告仍为44个protocol + audio20×2 = 84次，0failure/error/skipped。无改动的任务由Gradle复用缓存，不宣称84项全部重新执行。
- 第一轮联想模拟器instrumentation 8/8：新增4项覆盖配置独立持久化及输入拒绝、图片私有副本与缩放、坏文件/超限回滚清理、Home离线状态及设置入口；另回归AudioDevice2、DebugPanel1、Identity读取1。
- 没有再次跑十分钟soak；原测试仍保留，本轮通过class选择运行产品与设备API回归，不把未运行的长测计为通过。SAF系统选择器跨不同provider的真实交互、旋转/进程终止期间导入、真实Home到服务器的PTT/字幕联调尚未验收。
- 保留API28兼容调用；compileSdk35对旧系统栏/WindowInsets API发出deprecated编译警告，没有抑制。新系统边到边和CD12Max布局仍待验证。

命令：

```powershell
.\.tools\gradle-8.9\bin\gradle.bat test lint assembleDebug :app:assembleDebugAndroidTest --offline --no-daemon --console=plain
adb -s 127.0.0.1:11509 install -r -g app/build/outputs/apk/debug/app-debug.apk
adb -s 127.0.0.1:11509 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s 127.0.0.1:11509 shell am instrument -w -r -e class com.aiwatch.probe.ProductShellTest,com.aiwatch.probe.AudioDeviceTest,com.aiwatch.probe.DebugPanelTest,com.aiwatch.probe.IdentityProcessTest com.aiwatch.probe.test/androidx.test.runner.AndroidJUnitRunner
```

## 不变的Gate

最终构建 `BUILD SUCCESSFUL in 1m 40s`，167 tasks（18 executed / 149 up-to-date），app/core-audio lint均0。最终APK重新安装后instrumentation `OK (8 tests)`，0.973秒；已在联想模拟器打开Home。APK为 `app/build/outputs/apk/debug/app-debug.apk`，SHA256 `6BE764ACB506C38632057A188B85F5E5C800081D7512026B6D4691FB7FE1EDF2`。原始本轮instrumentation输出保存在 `evidence/reports/phase-2a-instrumentation.txt`。

Phase1C本地软件链PASS；整体IN PROGRESS。参考手机、真实Xiaozhi、CD12Max仍待验收；C4 STATIC PASS / Runtime pending。本版不能证明STT/LLM/TTS真实服务已打通，也不能证明Live2D动态形象、声音切换或人格同步已完成。

参考平台接口：[SAF文档](https://developer.android.com/training/data-storage/shared/documents-files)、[ImageDecoder](https://developer.android.com/reference/android/graphics/ImageDecoder)。
