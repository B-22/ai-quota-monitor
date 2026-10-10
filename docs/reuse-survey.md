# GitHub 能力复用调研

核查日期：2026-10-10。状态：已核查公开源码、模块依赖与许可；尚未导入外部实现、构建候选程序或测量候选内存。

目标：一个 Go 可执行文件的 collector/hub 两种模式；设备本地读取 Claude Desktop / Codex 登录态，只上传额度结果；哆啦汇聚；沿用 TrafficMonitor 与安卓显示端。

## 当前建议

优先复用已有项目中的 Go 查询／缓存代码和 C++ Desktop 凭据格式，再对照已核查的开源实现补齐。不要重写已有显示端，不引入新的桌面运行时、浏览器、完整日志统计或观测平台。

现成候选里，InfoHub 最接近 collector → hub 架构；rotcst/AiTokenMonitor 最明确覆盖 Windows Claude Desktop。它们仍未共同提供本项目全部要求，因此选择必要模块组合，而非宣称发现了可直接替换的完整系统。

| 能力 | 优先来源 | 本项目需要的适配 |
|---|---|---|
| Claude Desktop 路径发现、缓存解析 | 现有 `clients/trafficmonitor/src/ClaudeCredentialReader.cpp` 与 `DpapiHelper.cpp`；rotcst/AiTokenMonitor 交叉验证 | 移植为 Windows Go 模块，验证虚拟机实际缓存格式 |
| DPAPI | Go 官方 `golang.org/x/sys/windows.CryptUnprotectData` | 管理返回缓冲区与错误；不自封装 Windows 系统调用 |
| AES-GCM / JSON / HTTP | Go 标准库 `crypto/aes`、`crypto/cipher`、`encoding/json`、`net/http` | 仅做 Desktop 数据格式及接口装配 |
| Codex 在线额度 | 现有 `clients/trafficmonitor/server/`；InfoHub 在线 collector；token-burn provider 对照 | 留在 collector，归一化字段，不让 Hub 持有 Provider token |
| collector 上传 / hub 收取 | InfoHub agent client、ingest 和 quota 模型 | 只保留额度；合并两种运行入口；满足 Android 接口 |
| 读取端 REST / SSE / 快照语义 | 固定版本 Token Monitor 接口与现有 Android 契约测试 | 提供客户端确实读取的字段，不重建全部统计接口 |
| 显示 | 当前 TrafficMonitor / Android 工程 | 增加 Hub 读取入口，保留已有绘制和小组件缓存 |

上述是选型建议；20–30 MiB 仍为目标，不是任何候选或新实现的实测结果。

## 核查候选与源码入口

### 1. iwascy/InfoHub — 优先复用 Go 采集／上报结构

固定提交 `5cb484b04d14a06c0a48541c97438b919f125b86`，[MIT 许可证](https://github.com/iwascy/InfoHub/blob/5cb484b04d14a06c0a48541c97438b919f125b86/LICENSE)。

- [Codex 在线采集](https://github.com/iwascy/InfoHub/blob/5cb484b04d14a06c0a48541c97438b919f125b86/internal/collector/codex_online_quota.go) 直接查询在线额度；[agent runner](https://github.com/iwascy/InfoHub/blob/5cb484b04d14a06c0a48541c97438b919f125b86/internal/agent/runner.go) 已同时注册 Claude / Codex 在线采集。README 对 Codex 的描述落后于源码。
- [agent client](https://github.com/iwascy/InfoHub/blob/5cb484b04d14a06c0a48541c97438b919f125b86/internal/agent/client.go) 上传额度结果；[Hub ingest](https://github.com/iwascy/InfoHub/blob/5cb484b04d14a06c0a48541c97438b919f125b86/internal/api/ingest.go) 接收 quota。Provider OAuth 不在该上传 payload 中，但默认还有使用记录和文件路径，不能整套照搬为“只上传脱敏额度”。
- 当前两个 cmd、SQLite remote store、日志统计超出本项目范围。Claude 是 CLI 凭据，不是 Windows Desktop。仅抽取需要的模块，保持 MIT 版权声明。

### 2. rotcst/AiTokenMonitor — Windows Desktop 格式交叉验证

固定提交 `1a930ecd2d9188c580836b09c55379b648422132`，[MIT 许可证](https://github.com/rotcst/AiTokenMonitor/blob/1a930ecd2d9188c580836b09c55379b648422132/LICENSE)。

- [ClaudeCredentialStore.cs](https://github.com/rotcst/AiTokenMonitor/blob/1a930ecd2d9188c580836b09c55379b648422132/CodexWeeklyMonitor/Services/ClaudeCredentialStore.cs) 读取普通／MSIX Desktop 目录、Local State 和 config.json，支持 tokenCacheV2/tokenCache，DPAPI(CurrentUser) 与 v10/v11 AES-GCM、audience 筛选及候选去重。
- [ClaudeUsageClient.cs](https://github.com/rotcst/AiTokenMonitor/blob/1a930ecd2d9188c580836b09c55379b648422132/CodexWeeklyMonitor/Services/ClaudeUsageClient.cs) 查询额度、尝试候选并遵守 Retry-After；没有 OAuth refresh 或凭据写回。
- 不引入整个 .NET 程序。移植格式处理时保留来源。Desktop 登录缓存由 Desktop 更新；CLI token 刷新逻辑不能直接套到 Desktop。

### 3. durandom/token-burn — Go provider 与刷新边界参考

固定提交 `4b8826a68c9c3b1e95dc61bb4399e8300b9a30b9`，[MIT 许可证](https://github.com/durandom/token-burn/blob/4b8826a68c9c3b1e95dc61bb4399e8300b9a30b9/LICENSE)。

- [Codex provider](https://github.com/durandom/token-burn/blob/4b8826a68c9c3b1e95dc61bb4399e8300b9a30b9/internal/provider/codex/codex.go) 是在线额度查询；[Claude refresh](https://github.com/durandom/token-burn/blob/4b8826a68c9c3b1e95dc61bb4399e8300b9a30b9/internal/provider/claude/refresh.go) 有刷新前重读／变化检测，但并非真正跨进程 compare-and-swap。
- 没有目标形式的自带 Hub；远端观测依赖 OTel/OpenObserve。整套含 TUI、SQLite、OTel 等，不采用整套，也未验证 Windows Desktop。

### 4. onllm-dev/onWatch — 在线查询和容错参考

固定提交 `ea6611342eb1bfb1e8248cbe73b5b2d24151f58b`，[GPL-3.0 许可证](https://github.com/onllm-dev/onwatch/blob/ea6611342eb1bfb1e8248cbe73b5b2d24151f58b/LICENSE)。

- [Codex client](https://github.com/onllm-dev/onwatch/blob/ea6611342eb1bfb1e8248cbe73b5b2d24151f58b/internal/api/codex_client.go) 在线读取额度；[Windows Claude 凭据](https://github.com/onllm-dev/onwatch/blob/ea6611342eb1bfb1e8248cbe73b5b2d24151f58b/internal/api/anthropic_token_windows.go) 实际读取 CLI .credentials.json，不支持 Desktop 解密。
- internal/agent 是 provider 轮询，不是已验证的分布式 collector → hub。作者 README 内存报告不是本机实测，不能与此前 systemd 内存口径直接比较。
- 本次只作行为与数据字段参考；复制其实现前需明确整个派生代码的许可方案。其限流后刷新令牌的策略不直接继承到 Desktop。

### 5–8. 其余核查后未作为首选

| 项目与固定 HEAD | 实际能力／未选原因 |
|---|---|
| [ansonliam/AIUsageMonitor](https://github.com/ansonliam/AIUsageMonitor/tree/85300ade20983d6f6e197e2d8121d98e7cc7da7f) | Windows/WSL CLI 凭据；ClaudeAuthentication 会 refresh 并写回 CLI 文件，没有 Desktop DPAPI 路径 |
| [jens-duttke/usage-monitor-for-claude](https://github.com/jens-duttke/usage-monitor-for-claude/tree/987fc0005291f98df2859d083431370bde313e16) | Python CLI .credentials.json 和只读查询，不能解决 Desktop 登录态 |
| [babs/claude-quota](https://github.com/babs/claude-quota/tree/45b0113653b368c814f41058aa726c33ef740be7) | quota.go / credentials.go 是 Go 在线查询和 CLI 凭据；程序还含托盘与 SQLite。README 标 MIT，但核查未找到根 LICENSE，暂不直接拷贝 |
| [sky1core/quota](https://github.com/sky1core/quota/tree/091aea5359b4abb51cfee15e0050b23878aff330) | internal/claude/claude.go 实际启动 claude -p /usage，依赖 CLI 子进程；不适合 Desktop-only 和最小常驻目标 |

## 系统库与已有代码

- [Go 官方 Windows DPAPI 声明](https://github.com/golang/sys/blob/69a6691c31d742208b9886e4325980048069d272/windows/syscall_windows.go)，[BSD 许可证](https://github.com/golang/sys/blob/69a6691c31d742208b9886e4325980048069d272/LICENSE)。现成 `billgraziano/dpapi` 也封装此能力，但这里优先官方库，减少重复包装依赖。
- 当前项目 `clients/trafficmonitor/server/` 已有 Go HTTP、缓存、Claude/Codex 查询与 CLI 刷新；适合作为第一来源。其旧“上传 Provider 凭据再转发”的模式不进入新 Hub。
- 当前项目 `clients/trafficmonitor/src/ClaudeCredentialReader.cpp` 和 `DpapiHelper.cpp` 已有 Desktop 支持；新 Go 模块以此为基础并交叉验证格式。
- Android 模型解析、SSE 恢复、小组件缓存和现有测试已存在，不重写。

## 实施前复用门槛

1. 每个新能力先检查仓库既有实现，再检索 GitHub 的候选源码；核对是否真的覆盖 Desktop、在线额度或所需 Hub 模式。
2. 在此记录选用文件、固定提交、许可证、依赖与适用边界。internal 包不是稳定公开 SDK，不直接把整个项目 go get 进来。
3. 确定缺口后才补写必要的连接代码；抽取外部实现保留版权、许可和来源，新增依赖先核对是否有更小的标准库方案。
4. 首个验证点是虚拟机 Desktop 凭据格式与只读额度查询；不要先扩展 Hub、UI 或历史统计。实现后再做真实内存基线和峰值测量。

本次只做搜索、源码核验和维护规则更新，没有运行候选安装脚本、访问用户 Provider 凭据、调用真实额度接口或切换现有服务。
