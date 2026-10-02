# iOS 功能对照

参考基线：AxonHub iOS `db811721692275273387c5999d790a7d50c3820c`。Android 使用由 `tools/import_ios_contracts.py` 确定性导入的 126 个管理操作和 schema revision `939b2bc07cc05bdf7750d7ec872290d67784d13d`。

| iOS 区域 | Android 状态 | Android 实现 |
|---|---|---|
| 实例与认证 | 已实现 | 多实例、管理员登录、API Key、编辑/切换/删除/重新登录、HTTPS/显式 HTTP、保留 base subpath |
| Dashboard | 已实现 | 真实请求/失败/延迟/token/cost、日趋势、渠道成功率；缺失值保持为空 |
| 渠道 | 已实现 | 列表/搜索、详情、创建/编辑、状态、删除、测试、模型获取/同步、显式秘密读取、OAuth 固定路由 |
| 模型 | 已实现 | 列表/搜索、详情、创建/编辑、状态、删除、模型卡、价格/限制、递归路由关联和条件 |
| API Keys | 已实现 | 列表、详情、创建/编辑/状态/rotate/archive、显式 reveal、profiles/quota/权限/templates/usage 与批量 schema 操作 |
| Projects/Users/Members/Roles | 已实现 | 项目作用域、`X-Project-ID`、分页、搜索、详情、成员专用读回、项目邀请与短时秘密链接、schema 操作 |
| Prompts/Protection/Storage | 已实现 | 原生列表/详情/递归编辑器、破坏性确认和可用的读回验证 |
| System/Account | 已实现 | 系统策略、缓存诊断/清理、catalog、账号操作，以及所有非秘密导入操作的可搜索原生表单 |
| Backup/Restore | 已实现 | JSON 备份导出；50 MiB 上限、结构校验、官方 GraphQL multipart map、二次确认、同实例版本读回 |
| Observability | 已实现 | Requests/Traces/Threads/Usage 分页、详情、关联、archive/retain、正文/headers/会话检查和递归脱敏 |
| Playground | 已实现 | Admin、OpenAI Chat/Responses、Anthropic、Gemini SSE；图片输入（最多 8 张/每张 10 MiB）、reasoning/tool/usage/terminal/error/cancel；加密的实例+项目+凭据范围历史 |
| UI/设置 | 已实现，设备体验未验证 | Foundation 自绘苹果风格控件、四标签悬浮 dock、欢迎/连接 sheet、主题/强调色/locale、adaptive icon；Material 仅用于文字、图标与内部 token，不绘制默认控件 |

## 明确差异

- Android 已提供简体中文、繁体中文、英文、日文和韩文的应用级 locale 选择，但目前完整翻译覆盖主要导航与应用资源；部分管理 schema 字段和运行时说明仍显示英文。字段结构和 API 行为不受影响。
- Android 的“全部 schema 操作”以递归原生表单呈现，而不是逐个复制 iOS 页面布局。它使用相同文档、类型、可选/必填语义、破坏性标记和读回规则。
- 广域 restore 在服务端没有统一事务 revision。与 iOS 一致，Android 只确认服务端 success 并读取同一实例的 version，不声称每个跨实体对象都已逐条验证。
- Provider 图标和许可已随包保留；当前紧凑列表以文字 provider/type 为主，并非所有位置都展示品牌图标。
- 渠道/模型已提供状态筛选、多选与批量启用/禁用，串行写入、逐项读回并显示部分失败。其他高级批量工具、健康诊断和专用渠道密钥管理仍未全部复制 iOS 专用页面；不能视为完整功能对等。API Key、Prompt、Protection 和 Role 的部分批量操作可从“全部 schema 操作”运行。
- Android 已有 Analytics 入口与服务，但分析展示、高级渠道工具和技术表单的交互布局仍与 iOS 有差异。部分旧技术文案及 Gateway 文案仍未完整本地化。
- 本轮按要求仅通过 GitHub Actions 编译/单元测试/lint/签名检查，不执行模拟器或真机测试；真实服务登录、权限与所有写操作的端到端行为未在设备验收。苹果风格 UX 是 Compose 实现，不包含 Apple 专有字体或实时 Liquid Glass 模糊。

不包含假数据、GraphQL 文本控制台、WebView 页面或静态 TODO 占位功能。
