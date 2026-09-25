# Xiaozhi Manager API — Authentication / Reuse Audit

2026-09-25；STATIC REVIEW COMPLETE，部署联调 PENDING。冻结 [xinnan-tech/xiaozhi-esp32-server](https://github.com/xinnan-tech/xiaozhi-esp32-server/tree/788f5301fdd60cc3a8ef74025bfeece9b82b94ce)；不新增后端、不实现登录、不使用实际凭据。

以下 Java 路径相对于 `main/manager-api/src/main/java/xiaozhi/`。

| 能力 | 文件 / 符号 | 结论 |
|---|---|---|
| 认证路由 | modules/security/config/ShiroConfig.java / shirFilter | /user/login、captcha、pub-config 等匿名；agent 和 models 相关路由落入 oauth2，不能因 OTA 匿名而推导 Manager 匿名 |
| Bearer 校验 | modules/security/oauth2/Oauth2Filter.java / getRequestToken；Oauth2Realm.java / doGetAuthenticationInfo | 读取 Authorization Bearer；查用户 token 数据、过期时间与用户状态，不是设备 WS token 验证器 |
| 登录签发 | modules/security/controller/LoginController.java；modules/security/service/impl/SysUserTokenServiceImpl.java / createToken | 登录成功签发/续期用户数据库 token；有效期 12 小时；注销/密码变更有失效逻辑。不要假设 OAuth refresh-token 接口存在 |
| 手机端流程 | main/manager-mobile/src/pages/login/index.vue；src/api/auth.ts | 公共配置获取 SM2 公钥，验证码+密码加密后提交 /user/login，保存返回 token；不能只做 username/password 明文 JSON 适配 |
| Agent API | modules/agent/controller/AgentController.java | GET /agent/list、GET /agent/{id}、PUT /agent/{id}；使用登录用户上下文及 normal 权限 |
| 资源所有权 | modules/agent/service/impl/AgentServiceImpl.java / getAgentById、requireAgentPermission、updateAgentById | Controller 传 userId；服务校验资源权限。设备 ID 或绑定关系本身不是用户更新授权 |
| 更新字段 | modules/agent/dto/AgentUpdateDTO.java；AgentServiceImpl.updateAgentById | agentName、ttsModelId/ttsVoiceId/ttsLanguage、ttsVolume/Rate/Pitch、systemPrompt、summaryMemory；只更新非 null 字段。PUT 非整对象替换；null 不表示清除 |
| 音色目录 | modules/model/controller/ModelController.java / getVoiceList | GET /models/{modelId}/voices，normal 权限，可选 voiceName；调用 timbreService.getVoiceNames |
| 设备 token 对照 | main/xiaozhi-server/core/utils/auth.py / AuthToken.generate_token、verify_token | 设备 ID/过期信息的加密签名 token 路径，与 Manager 用户数据库 token 不同；不能互换 |

## 适配时必须保留的语义

1. 独立配置 Manager base URL、用户认证与所选 agentId；不能从 OTA 地址猜 Manager 地址，不将 WS token 填入 Manager Bearer。
2. 同时检查 HTTP 与 `Result` JSON 错误码。Oauth2Filter 认证失败写错误 envelope，并未显式设置 HTTP 401；HTTP 200 不能单独视为认证或更新成功。
3. 仅提交明确修改字段；null/缺字段表示不改，清空文本需单独验证。不要回传整个服务端对象，更不要覆盖 functions、memory 等不在当前编辑范围的字段。修改后重新读取确认实际结果。
4. Token 过期后停止更新、提示重新认证；禁止无限重试。Android 安全持久化方案需另审，不能照搬 mobile 的普通本地存储。
5. 目前不能认定用户登录流程适合手表。先明确参考手机/管理端登录与手表授权 UX；如确需 device-scoped adapter，必须限定 agent、操作权限与撤销机制，另行批准，不开放通用用户管理权限。
6. `CharacterPresentation` 的 avatar/background/layout/display overrides 属本地；agentName、voice、systemPrompt、memory 以服务端为准。本地名字保留为显示覆盖，不宣称已修改服务器人格。

后续仅计划 `XiaozhiAgentRepository` 适配既有接口，取消新建 `/companion/profile`、`/companion/voices` 后端。冻结服务端根 LICENSE 为 MIT，但部分安全文件含原始第三方版权头；本轮只引用 API，不复制后端认证代码。

## 尚未执行的验收

以下真实认证与授权实现/验收移至 **Phase 2C**。Phase 2B 仅定义 `XiaozhiAgentRepository` 接口契约，不新增 Manager 登录页、验证码/SM2 客户端、用户 token 持久化或 device-scoped 后端。Phase 2C 先决定手机配对、一次性授权或受限 adapter 的 UX 与权限范围，再实现；静态审计已获用户审查 PASS，不表示真实认证通过。

- 真实部署 base URL、版本、登录/验证码流程、token 过期与注销。
- 无 token、设备 token、其他用户 agent 越权请求均不得成功。
- HTTP 成功但业务错误、受限音色、部分更新保留未改字段、更新后读回。
- 客户端 TLS、凭据脱敏、取消/超时与重认证 UX。

暂无服务器地址，因此以上没有 runtime PASS，也没有声称设备凭据已在真实 Manager 上做过拒绝测试。
