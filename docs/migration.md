# 源码整合记录

日期：2026-10-10。主目录为哆啦 <device-local-path>，Windows 副本为 <windows-workspace>。

- TrafficMonitor 来源：<windows-workspace>，旧 Git HEAD 9ae2bf417ef5e013daf7153eef3a6509c25e7909。导入 77 文件，包含当前工作树修改及四张未跟踪预览图。原仓库 Git 历史、状态和工作树均保留。
- Android 来源：<device-local-path>，导入 91 文件。排除 build、.gradle、.kotlin、local.properties 等设备配置和构建产物，原源码保留。
- 旧安卓 README、AGENTS、接口／部署文档与 HTML 设计稿保存在 docs/reference/legacy-mobile；它们是固定导入参考，不能作为新架构的当前状态。
- Token Monitor 固定版本接口说明与许可证进入 docs/reference/token-monitor，完整旧参考克隆仍在旧项目目录。
- 新 backend 尚未实现；旧 Node Hub／Agent 不迁移、不卸载、不重启。没有复制旧登录态、运行数据、secret 或 APK。

导入哈希及来源清单在主库 .local/import-audit（不纳入 Git），Windows 审计在 docs/maintenance/2026-10-10-ai-quota-consolidation。复制时前后复核原件哈希，目标逐文件匹配；原件不删除。

旧 Windows 插件仓库保留完整 Git 历史、未提交来源和旧构建引用；<windows-workspace> 为稳定软件运行目录。
旧安卓目录保留现有会话、交付产物、完整上游参考和旧部署文档。以后开发使用统一主库，旧目录不再作为新架构开发入口。

此次只是源码整合与建立同步入口，不代表旧修改通过新的构建验收，也不代表 Go 系统已经上线。
