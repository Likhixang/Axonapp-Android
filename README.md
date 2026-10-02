# AxonHub Android

[English](README.en.md)

AxonHub Android 是 AxonHub 管理客户端的原生 Android 移植版。应用使用 Kotlin、Jetpack Compose Material 3 和原生网络/安全存储实现，不包含 WebView 包装层。

## 功能

- 多实例管理：管理员邮箱/密码登录与 API Key 模式、编辑、切换、重新登录和移除实例。
- 管理控制台：真实仪表盘、渠道与模型、API Key、项目、成员、角色、提示词、保护规则、存储、系统策略及全部导入的 schema 操作。
- 网关管理：渠道/模型创建、编辑、状态、删除、测试、上游模型获取与同步；递归表单支持高级路由和条件。
- 可观测性：请求、Trace、Thread、Usage 的分页列表、详情、关系和脱敏正文/会话检查器。
- Playground：管理员通道以及 OpenAI Chat Completions、OpenAI Responses、Anthropic Messages、Gemini 流式协议。
- 备份：原生 JSON 导出和 GraphQL multipart 恢复，恢复前验证文件结构并要求二次确认。
- 外观：手机底部导航、平板导航栏、浅色/深色/跟随系统、自定义强调色和应用语言选择。

详细映射和已知差异见 [docs/PARITY.md](docs/PARITY.md)。安全模型见 [SECURITY.md](SECURITY.md)。

## 构建

要求：

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

本移植基于 iOS 参考版本 `db811721692275273387c5999d790a7d50c3820c`。GraphQL 文档、schema、测试夹具和资源来源记录在 `app/src/main/assets/UPSTREAM_PROVENANCE.json`，品牌图标许可位于 `app/src/main/assets/licenses/`。本仓库不额外声明上游未提供的通用许可证。
