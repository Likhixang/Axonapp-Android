# Axonapp Android

[English](README.en.md)

Axonapp Android 是独立的第三方 AxonHub 管理客户端，以 [Axonapp-iOS](https://github.com/Likhixang/Axonapp-iOS) 为功能和 UX 参考。项目不隶属于 AxonHub，也未获 AxonHub 官方背书。应用使用 Kotlin、Jetpack Compose Foundation 自绘苹果风格控件和原生网络/安全存储实现，不包含 WebView 包装层。

## 功能

- 多实例管理：管理员邮箱/密码登录与 API Key 模式、编辑、切换、重新登录和移除实例。
- 管理控制台：真实仪表盘、渠道与模型、API Key、项目、成员、角色、提示词、保护规则、存储、系统策略及全部导入的 schema 操作。
- 网关管理：渠道/模型创建、编辑、状态、删除、测试、上游模型获取与同步；递归表单支持高级路由和条件。
- 可观测性：请求、Trace、Thread、Usage 的分页列表、详情、关系和脱敏正文/会话检查器。
- Playground：管理员通道以及 OpenAI Chat Completions、OpenAI Responses、Anthropic Messages、Gemini 流式协议。
- 备份：原生 JSON 导出和 GraphQL multipart 恢复，恢复前验证文件结构并要求二次确认。
- 外观：苹果风格大标题、分组卡片、悬浮四标签底栏、自绘输入/开关/分段控件/弹层，浅色/深色/跟随系统、自定义强调色和应用语言选择。
- 渠道/模型多选：状态筛选与批量启用/禁用、归档、删除/渠道恢复、导入、排序；串行写入并逐项读回，明确反馈部分失败，不宣称原子操作。
- 专用工具：渠道密钥、价格、诊断/配额、覆盖模板、复制与模型路由预览；API Key 使用原生简化编辑、Profile/额度/策略模板和用量页面。
- 图标：白底真实 iOS AH 图标；应用显示名 Axonapp，保留 `cc.khixang.axonhub` 以兼容原安装和本地数据。

详细映射和已知差异见 [docs/PARITY.md](docs/PARITY.md)。安全模型见 [SECURITY.md](SECURITY.md)。

## 构建

本项目的交付构建统一由 [GitHub Actions](https://github.com/Likhixang/Axonapp-Android/actions) 执行：单元测试、lint、Debug APK 打包及签名/对齐检查。不运行模拟器；成功后下载 `axonapp-debug-apk` artifact。Debug 签名通过 `ANDROID_DEBUG_KEYSTORE_BASE64` GitHub Secret 复用，源码不包含签名文件。

不进行本地编译，也不启动模拟器。以下命令说明 Actions 验证所需环境：

- JDK 17
- Android SDK Platform 35
- Android SDK Build Tools 35.x

仓库包含 Gradle 8.11.1 Wrapper，常用命令：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

本项目在 ARM64 Linux 上构建时，Android SDK 的 AAPT2 可能是 x86_64 可执行文件，需要宿主提供 binfmt/QEMU 或兼容的 AAPT2。此兼容层不属于应用源码。

## 安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次启动后添加实例。HTTPS 为默认要求；只有明确开启单实例的“允许 HTTP”选项时才接受 HTTP 地址。

## 发布签名

源码没有内置生产签名。Release 构建仅在以下环境变量全部存在时使用外部 keystore：

- `AXONHUB_SIGNING_STORE_FILE`
- `AXONHUB_SIGNING_STORE_PASSWORD`
- `AXONHUB_SIGNING_KEY_ALIAS`
- `AXONHUB_SIGNING_KEY_PASSWORD`

GitHub tag 工作流对应使用 `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` secrets。缺少 secrets 时生成的 release APK 明确标记为 unsigned。

## 上游与资源

本移植基于 iOS 参考版本 `42e262e3448aa63ca7c18aec12ad65c27d62c11f`。GraphQL 文档、schema、测试夹具和资源来源记录在 `app/src/main/assets/UPSTREAM_PROVENANCE.json`，品牌图标许可位于 `app/src/main/assets/licenses/`。本仓库不额外声明上游未提供的通用许可证。
