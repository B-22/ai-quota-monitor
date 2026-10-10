# 当前架构决策

状态：2026-10-10 用户确认；collector 首期（Desktop 凭据读取 + 只读额度查询）已实现并通过 Linux 单元测试与 Windows 交叉编译，尚未在虚拟机 Desktop 实测；hub 未实现，未切换。

采集设备（虚拟机 Claude Desktop、所在设备的 Codex）→ Go collector → 哆啦 Go hub → TrafficMonitor / 安卓 App 与小组件。

collector / hub 合并为一个 Go 可执行文件，通过启动参数选择模式。Hub 可以被多个设备读取；采集端按配置只采集本设备需要的提供方，不做无关工具扫描。

传输包含提供方、匿名账号键、采集设备别名、额度窗口、已用／剩余百分比、UTC 重置时间、采集时间、状态；Provider 登录凭据留在采集设备。
同一账号多端上报不累加额度，优先指定主采集设备并选最新有效快照。断网保留最后成功数据，读取端明确显示过期或离线。

Android 当前依赖旧 Hub 的 /api/health、/api/stats、/api/devices 与 /api/stats/stream；Go Hub 优先实现客户端实际需要的兼容字段和帧语义，不重建整个 Token Monitor。
TrafficMonitor 后续增加 Hub 数据源；当前仍保留旧本机采集行为。安卓小组件继续显示 App 写入的本地快照。

空闲常驻内存 20–30 MiB 是目标，需分别测 Hub、采集端与查询峰值；不是已达到指标。Go Desktop 凭据读取已在 collector 实现（尚未在虚拟机 Desktop 实测）；令牌刷新协调、账号标识、认证、持久化格式、过期判定、接口子集仍待实现与验证。

## 实现选型顺序

用户要求开始开发前搜索现成能力，避免重复造轮子。已核查的来源与缺口见 [复用调研](reuse-survey.md)。优先当前插件已有 Go／Desktop 采集代码，结合 InfoHub 的额度上报结构、rotcst/AiTokenMonitor 的 Windows Desktop 格式验证及 Go 官方系统库；仅补单程序模式、额度数据连接和客户端接口适配。候选仍未在本机部署或测内存，调研建议不等于已经采用。
