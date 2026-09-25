# 联想模拟器补充验证

结论：PASS（模拟器软件循环稳定性）；不替代参考手机、真实 Xiaozhi 或 CD12Max 验收。没有修改音频代码、队列容量或测试断言。

## 环境与输入

- 用户指定联想模拟器，MEmu 可执行文件版本9.2.8.1；用户以管理员权限启动。
- 实测Android 9 / API28，1600×900，density240。ABI列表为x86_64,arm64-v8a,x86,armeabi-v7a,armeabi；这是模拟器声明，不能当作CD12Max ABI证据。
- APK SHA256：`E4EC4195021E5FDCE10AEAEF458CED163396CD0E69B73C557A851A3C180B352D`，与上一轮基线一致，未重新构建。
- 现有DebugAudioSession、AudioRecord、Concentus、Coordinator、AudioTrack及同进程HTTPS/WSS Mock；不连接外部服务器，不保存录音。
- 用户人工确认“能听到”短音。未确认无爆音/拖尾，也未量化可听延迟；未独立确认电脑麦克风输入是否真实透传。

## 执行

```powershell
adb -s 127.0.0.1:11509 install -r -g app/build/outputs/apk/debug/app-debug.apk
adb -s 127.0.0.1:11509 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s 127.0.0.1:11509 shell am instrument -w -r -e soakMs 600000 com.aiwatch.probe.test/androidx.test.runner.AndroidJUnitRunner
```

完整instrumentation：`OK (5 tests)`，601.739秒。录音读取/释放、AudioTrack pause/flush、十分钟长测、Debug页面启动关闭、身份读取均通过；未重新执行跨进程身份重启测试或JVM单测。

## 长测结果

原始数据：`evidence/reports/lenovo-emulator-audio-soak.json`，独立保存，不覆盖官方Android模拟器结果。

| 指标 | 本轮实测 |
|---|---|
| 长测时长 | 600328ms |
| 完整收发轮次 | 751 |
| 硬打断重连 / 连接数 | 75 / 76 |
| TX / RX | 4428 / 3455 |
| 服务端上行解码样本 | 4250880（4428×960） |
| 正常结束补齐帧 / padding samples | 751 / 491232 |
| 丢弃尾样本 / listen stop后迟到上行 | 0 / 0 |
| encoded / PCM记录峰值 | 1 / 1 |
| overload / partial-write计数 | 0 / 65 |
| 各连接最后保存的underrun累计 | 1831 |
| RSS采样最大值 | 127824KiB |
| 进程CPU累计 | 93570ms |
| 松开至server收到stop最大值 | 45ms |
| 已报告audioError | 无 |

read chunk分布（samples:次数）：1024:3271、832:404、160:148、480:81、512:19、864:2。

partial-write计数包括未一次写完和零长度写入尝试；本轮出现该路径仍完成测试，但不代表经过声学逐样本验证。CPU/RSS含同进程Mock与测试框架，不可用作纯App或手表功耗结论。

## 保留边界

- underrun尚未区分播放前、active TTS、TTS stop后；1831不是零卡顿，也不能据此直接判定全部发生在有效播放中。后续参考手机需要阶段性观测及听感配合，当前不调大队列或修播放器。
- 多轮短PTT持续十分钟，不是十分钟不间断录音；45ms是控制链延迟，不是首音或打断后的可听静音延迟。
- 合成下行仍为16k编码的Opus、24k解码输出；不能证明实际24k TTS provider契约，不关闭C4 runtime。
- 测试正常结束、未报告音频异常；没有独立系统级crash/ANR日志审计，不能声称全系统零ANR。
- C-C参考手机PENDING，C-D真实XiaozhiPENDING，C-E CD12Max TARGET VALIDATION PENDING；Phase 0C C4 STATIC PASS / Runtime pending；Phase 1C整体IN PROGRESS。

测试结束后不关闭用户启动的模拟器，不更改其音频或ADB配置。本轮仅增加本地验证报告和证据。
