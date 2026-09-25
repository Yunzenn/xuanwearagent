# P2B-RUNTIME validation

状态：PENDING / NOT RUN。未安装或启动 Cubism sample，未把模型显示或静态编译冒充 Runtime PASS。

## 本轮环境预检

- ADB 连接 `127.0.0.1:11509`，LENOVO_L79031 模拟器。
- 系统声明 ABI：x86_64,arm64-v8a,x86,armeabi-v7a,armeabi。模拟器宣告 ARM ABI 不构成 ARM64 原生参考手机证据。
- `getconf PAGE_SIZE` 返回 command not found；页大小尚未实测，不假定4KB。
- 本项目 SDK 安装目录仅 Platform35；官方 sample 要求 compile/target36、AGP8.9.1、Gradle8.11.1。未升级产品工程或修改官方原包。
- 本地 `.android-sdk/ndk` 不存在；尚未用官方 llvm-readelf 复核。Python ELF 结果仍为已获得静态证据，不宣称完成独立工具交叉验证。

## 样例模型许可确认待完成

官方包根 LICENSE.md 将 Haru/Hiyori/Mao/Mark/Natori/Ren/Rice/Wanko 列为 Free Material，并明确要求使用各模型时同意独立 Sample Model Terms。SDK下载确认不自动替代这一项；请求用户本人确认 Haru 与 Hiyori 的适用条款后运行。条款入口：

- https://www.live2d.com/eula/live2d-free-material-license-agreement_en.html
- https://www.live2d.com/eula/live2d-sample-model-terms_en.html

未因待确认而删除或修改任何模型；资产仍只在忽略目录。

## 后续执行矩阵

| 检查 | 当前 |
|---|---|
| 官方 llvm-readelf ARM64 GNU_RELRO 交叉复核 | PENDING |
| x86_64 4KB native版本、加载/纹理、渲染 | NOT RUN |
| x86_64 16KB emulator | NOT RUN |
| ARM64 4KB reference phone | 无参考手机，NOT RUN |
| Haru / Hiyori 双模型切换，不改业务代码 | NOT RUN |
| Idle、显式motion、expression、physics、pose、blink | NOT RUN；缺资源项标N/A并补另一合法模型覆盖 |
| 后台恢复、GL surface recreation、clean release | NOT RUN |

P2B-RUNTIME 与 LIVE2D-ARM64-16K 分离。后者为 BLOCKED BY UPSTREAM / RELEASE BLOCKER（ARM64静态FAIL），不无限阻止4KB开发测试；x8632异常单独记录，不与64位发布Gate等权。完整模型生命周期和必要参考设备证据通过后才可请求 DEV-ONLY P2B-1，不因模拟器单项通过宣布整体验收。

本轮没有向Live2D提交问题，没有上传Core或模型，没有开始Home集成。
