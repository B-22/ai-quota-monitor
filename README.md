# AI Quota Monitor

状态：2026-10-10 已完成源码收拢；现有客户端保持原行为，新的 Go 采集端／Hub 尚未实现。

统一源码主库在哆啦 `<device-local-path>`；Windows 开发副本在
`<windows-workspace>`。两处使用同一 Git 历史，通过显式提交、fetch、合并同步；不做文件级双向覆盖。

| 目录 | 内容 |
|---|---|
| `backend/` | 新 Go 单一可执行文件的设计入口，未来提供 collector / hub 两种模式 |
| `clients/trafficmonitor/` | 当前 Windows C++ 插件完整工作树，包括此次导入的未提交改动 |
| `clients/android/` | 当前 Kotlin Android App、小组件、Gradle wrapper 与单元测试 |
| `deploy/` | 部署约定入口；真实配置与凭据继续留在运行设备 |
| `docs/architecture.md` | 已确认架构、数据边界和待实现事项 |
| `docs/reuse-survey.md` | 开始实现前的 GitHub 能力核查与复用矩阵 |
| `docs/workflow.md` | 两台设备的开发、构建、同步与回退规则 |
| `docs/migration.md` | 导入来源、排除项及原位置保留说明 |
| `docs/reference/` | 旧安卓文档及固定版本 Token Monitor 接口参考，不代表新系统实现 |

现有插件中的 `server/` 是旧 Go 转发实现，仅作后续采集逻辑参考，不等于新 backend。
插件旧 `.github/` 工作流位于子目录，不会自动成为统一项目的 CI。
本次不带入 .git、构建缓存、APK、运行配置、SDK 路径、登录凭据或日志。

开始开发先读 `AGENTS.md` 和所在客户端的维护指南。

## 双设备协作

GitHub B-22/ai-quota-monitor 作为同步中心。Windows 仅编辑和操作 Git，禁止本机 C++／Go 编译与测试；Go 和 Android 在哆啦验证，C++ 使用 GitHub Actions Windows runner。

换设备前提交、推送，另一端拉取；同一分支避免两端同时编辑。验证通过才合并 main／发布，源码同步不自动部署。本次迁移分支保留 GitHub 原插件历史；本地私有导入历史不上传，详见 [双设备开发与同步](docs/workflow.md)。

迁移通过保留原 main 祖先的任务分支完成；Go backend 尚未实现。根目录工作流验证 Windows x64 插件，迁移期间不自动发版；子目录旧工作流仅供参考。
