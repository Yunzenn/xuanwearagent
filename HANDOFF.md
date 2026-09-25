# Handoff — Live2D Runtime Gate

当前不是 Phase 2B-1 起点：P2B-RUNTIME 尚未运行。Phase2A 用户审查PASS；音频本地链已通过，参考手机/真实Xiaozhi/CD12Max仍待验。

## 不重复研究

- 复用边界：PHASE_2B_REUSE_GATE.md；Manager认证：MANAGER_API_AUTH_AUDIT.md。Manager登录实现放Phase2C。
- 官方包路径/哈希：LIVE2D_ARTIFACT_RECEIPT.md。Core只在 third_party/live2d/downloads 与 sdk-r5，均忽略；禁止提交专有制品或模型。
- 二进制审计：LIVE2D_BINARY_AUDIT.md；可重跑 evidence/tests/audit_cubism_binary.py。
- Framework API静态编译通过；ARM64/x86 LOAD对齐通过但RELRO16KB失败，x86_64静态通过。Core文档版本06.00.0001，实际native getVersion未调用。
- LIVE2D-ARM64-16K单独Release Gate，不成为4KB开发永久阻断。Expandable Application许可仍为独立Release Gate。

## 下一次直接做

1. 用户本人确认Haru/Hiyori素材及模型条款（见LIVE2D_RUNTIME_VALIDATION.md）。不代同意。
2. 获取官方NDK llvm-readelf，复核ARM64 RELRO；不改闭源SO。
3. 保留原始SDK，独立验证工程运行官方sample；解决其API36/Gradle8.11.1环境，不升级产品三模块基线。
4. 实测模拟器ABI/页大小，执行双模型、动作/表情/physics/pose/blink、后台恢复、GL重建、释放并保存逐项证据。
5. 单列x86_64 16KB、ARM64 4KB reference phone证据；模拟器不能替代手机。无设备时诚实PENDING。
6. 更新LIVE2D_RUNTIME_VALIDATION.md。未达到门槛不标PASS；完成后停止，下一次审查才进DEV-ONLY P2B-1 Home集成。

## Git边界

本次只获授权提交文档、报告、审计脚本。现有Phase2A App代码和测试工作区改动保留，不顺手打包进审计提交。作者/提交者使用yunzenn及GitHub noreply；禁止用户真名/本机用户目录进入提交内容。无强制推送、无Core上传、无新产品功能。
