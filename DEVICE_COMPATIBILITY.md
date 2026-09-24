# Device Compatibility — Phase 0C/C1

目标设备：CD12Max，预期 Full Android 9 / API 28、410×502；这些仍是计划值，不是真机证据。

## Gate status

**C1 Device Probe: TARGET VALIDATION PENDING**

2026-09-25 执行 `adb devices -l`，ADB daemon 正常启动，但设备列表为空。没有使用商品页、模拟器或预期参数填充报告。

待设备连接后采集：Android release/API、model、CPU ABI、hardware、screen size/density、RAM、OpenGL ES、Audio HAL、AudioRecord、AudioTrack、麦克风、扬声器、SAF、APK side-load、后台行为。

真机执行前不生成带虚构数值的 `DeviceCapabilityReport.md/json`。Probe APK 已具备 Device / Audio / Graphics / Storage 页面和 app-private report 导出骨架。
