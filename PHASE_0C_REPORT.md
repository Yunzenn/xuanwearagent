# Phase 0C Report

日期：2026-09-25  
总体状态：**NOT COMPLETE — 不得进入 Phase 1**

| Gate | 状态 | 证据 | 下一解除条件 |
|---|---|---|---|
| C1 Device Probe | BLOCKED | `adb devices -l` 返回空设备列表 | CD12Max 通过 ADB 授权连接，执行 Probe 并导出真实 md/json |
| C2 Audio Gate C | BLOCKED | 无目标真机，无法验证 HAL、chunk 分布、AudioTrack 与 10 分钟稳定性 | C1 完成后在 CD12Max 运行 16k 原生 capture 探测和完整 loopback |
| C3 Cubism Gate B | BLOCKED | 无目标真机，不能验证 ABI/GL/FPS/温度 | C1 确认 ABI/GL 后运行冻结官方 sample + 合法模型 10 分钟 |
| C4 Server Audio Contract | STATIC PASS | 精确冻结 checkout；补丁前 5 failed/3 passed，补丁后 10 passed；上行/下行字段拆分，TTS encoder 与 Server Hello rate 同源并有不一致守卫 | 在真实 server/TTS provider 抓取下行 Opus，以 Server Hello 宣告值正确解码后升级为 PASS |

## C1

没有生成伪造的 `DeviceCapabilityReport.md/json`。现有 Probe APK 保持可用，等待真机。

## C2

没有提前实现 resampler。原生 16 kHz capture 是否可用、实际 read chunk 分布、underrun、CPU/内存和 10 分钟稳定性均待真机。

## C3

没有实现 AvatarController、导入器或 Avatar UI。冻结版本继续为 `Live2D/CubismJavaSamples@8ce6803de7030a4816bccd8a3efcad69ea1d1186`。

## C4

冻结 checkout：`xinnan-tech/xiaozhi-esp32-server@788f5301fdd60cc3a8ef74025bfeece9b82b94ce`。回归测试先在未修改 upstream 上复现 5 个失败，再应用最小补丁得到 10/10 通过；相关 Python 文件也通过语法编译。静态路径、具体函数和参数来源记录于 `SERVER_AUDIO_CONTRACT.md`，最终补丁保存在 `evidence/patches/xiaozhi-server-audio-contract.patch`。

保存的 patch 已在重新解压的同一 commit 干净基线上通过 `git apply --check` 和实际 `git apply`；应用后的 3 个生产文件与测试通过的工作副本逐文件一致。Phase 0B APK SHA-256 仍为 `5CC66C0541C73A572E73715BB9E515096B76AFF4C9682E3BBDE0135706F84258`，未被修改。

C4 当前仅为 `STATIC PASS`：没有真实 TTS provider/部署环境，因此没有伪造下行 Opus 动态证据。Runtime verification 仍待执行。

## 边界确认

本轮未实现 DeviceIdentity、BootstrapRepository、业务 WebSocket、Conversation FSM、Live2D Avatar、Character、MCP 或完整 UI。
