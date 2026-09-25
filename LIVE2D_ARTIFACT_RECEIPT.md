# Official Java SDK artifact receipt

用户提供已下载的 `CubismSdkForJava-5-r.5.zip`；本轮没有代用户接受许可或重新下载。仅复制、校验与解压，未运行 SDK、未接入 App、未进行 Binary Audit。

- 原始文件名：`CubismSdkForJava-5-r.5.zip`（未改名）
- 保存路径：`D:\AIwatch\third_party\live2d\downloads\CubismSdkForJava-5-r.5.zip`
- 大小：20,930,369 bytes
- ZIP SHA-256：`2BCCF7B3A6CC6FDAEADE2CF1D7A6E544CB4AD71912D0131565648BAA6D515BD3`
- 解压目录：`D:\AIwatch\third_party\live2d\sdk-r5\`
- SDK 根目录：`D:\AIwatch\third_party\live2d\sdk-r5\CubismSdkForJava-5-r.5\`
- AAR 路径：`D:\AIwatch\third_party\live2d\sdk-r5\CubismSdkForJava-5-r.5\Core\android\Live2DCubismCore.aar`
- AAR 大小：167,813 bytes
- AAR SHA-256：`3F05DA57AB855E803000E6353888DD561C47758598C6C0200DCD0109312705F8`

存在 `Core/README.md`、`Core/CHANGELOG.md`、`Core/LICENSE.md`、`Core/RedistributableFiles.txt`、`Framework/`、`Sample/`、根 `cubism-info.yml`。此处仅确认存在，不表示版本、API、ABI 或许可审计通过。

复制前确认目标不存在；原包与保存副本 SHA-256 一致；解压前逐项检查目标路径不越界。未覆盖已有目录。

Git 检查：`git ls-files third_party/live2d` 无输出；ZIP、AAR、SDK LICENSE 均命中忽略规则。限定目录的 `git status --short -- .gitignore third_party/live2d` 仅显示 `.gitignore` 修改。SDK 内 Markdown 同样忽略；本项目审计文档保留在仓库根目录。本轮没有提交或推送。

P2B-0 overall 仍 NOT PASS。下一任务为 Binary Audit only；Runtime 和 Release Gate 均未关闭。
