# Live2D Compatibility — Phase 0C/C3

## Gate status

**C3 Cubism Gate B: TARGET VALIDATION PENDING**

原因：CD12Max 未连接。不得以桌面、模拟器或其他 Android 设备替代 ABI、OpenGL ES、性能、温度与 10 分钟稳定性结论。

冻结基线：`Live2D/CubismJavaSamples@8ce6803de7030a4816bccd8a3efcad69ea1d1186`。测试必须使用官方 Cubism Android sample/Core 和许可合规的测试模型。

待验证：模型加载、Core ABI、OpenGL ES、纹理、motion、expression、physics、启动时间、Speaking 约 30 FPS、Idle 15–20 FPS、CPU、内存、可获得的 GPU/温度数据、crash 和 10 分钟持续运行。

目标设备 Gate 与软件开发并行，不再把 CD12Max 未到手解释为禁止 Avatar 软件开发。进入 Live2D 集成前先完成 P2B-0 的 SDK/模型许可、配套 Core 制品与软件运行检查；见 PHASE_2B_REUSE_GATE.md。当前尚未取得 Core AAR，运行 Gate 未通过。
