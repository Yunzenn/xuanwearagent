# Live2D Compatibility — Phase 0C/C3

## Gate status

**C3 Cubism Gate B: BLOCKED**

原因：CD12Max 未连接。不得以桌面、模拟器或其他 Android 设备替代 ABI、OpenGL ES、性能、温度与 10 分钟稳定性结论。

冻结基线：`Live2D/CubismJavaSamples@8ce6803de7030a4816bccd8a3efcad69ea1d1186`。测试必须使用官方 Cubism Android sample/Core 和许可合规的测试模型。

待验证：模型加载、Core ABI、OpenGL ES、纹理、motion、expression、physics、启动时间、Speaking 约 30 FPS、Idle 15–20 FPS、CPU、内存、可获得的 GPU/温度数据、crash 和 10 分钟持续运行。

在 Gate PASS 前不实现 AvatarController、自定义导入器或 Avatar 编辑器。
