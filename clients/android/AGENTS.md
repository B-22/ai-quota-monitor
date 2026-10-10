# 安卓客户端维护指南

本目录来自旧 Token Monitor Mobile 的 android-client，源码、测试和资源保持字节一致。父项目 AGENTS.md 规定新 Go 架构；docs/reference/legacy-mobile 中旧规范的“只允许上游 Hub”属于旧架构，不能覆盖用户本轮已确认的 Go 重写。

- 沿用 Kotlin / Jetpack Compose / Material 3、StatsParser 和现有快照缓存实现。
- 当前 App 仍使用旧 Hub 原生 REST/SSE 接口。Go Hub 实现前，先核对这些接口与契约测试，避免同时重写客户端。
- App 不读取 Provider 凭据；Hub secret 沿用 Keystore 加密存储，不出现在日志、URL、源码和测试数据中。
- 额度未知、为零、失效、解析失败分别显示；单个 Provider 异常不破坏整页。
- 哆啦构建：GRADLE_USER_HOME=/cache/gradle ANDROID_HOME=/cache/android-sdk ./gradlew :app:testDebugUnitTest；需要交付 APK 时再 assembleDebug。
- Windows local.properties 按本机 SDK 创建，不复制 Linux 配置，不跟踪 Git。
- 解析和数据流变化运行 JVM 测试；UI 变化构建 APK并做受影响界面验收。此次导入不升 App 版本、不安装手机、不改变已有功能。
- docs/reference/legacy-mobile 保留旧构建与接口背景；新架构事实写在项目现行文档。
