# Go 后端入口

状态：collector 首期切片已实现（Windows Claude Desktop 凭据读取 + 只读额度查询），已通过 Linux 单元测试与 Windows 交叉编译；尚未在虚拟机 Desktop 实测。hub 尚未实现。

一个 Go 可执行文件（`cmd/aqm`），用 `-mode` 选择 collector / hub；当前只实现 collector：

- `internal/collector/claudedesktop`：读取 Windows Claude Desktop 的 `Local State`（DPAPI 解出 AES 密钥）与 `config.json`（v10 AES-GCM 解出 OAuth token cache），按主 client / V2 / 最新有效选择 token。仅 Windows 可用，其它平台返回不支持。
- `internal/collector/claudeusage`：用该 token 只读查询 Anthropic OAuth usage，归一化为 `session` / `weekly` / Fable weekly 窗口。
- `internal/quota`：与 Hub 线格式一致的额度快照类型。
- `-mode hub`：当前返回未实现。

尚未实现，不得当成已完成：令牌刷新协调、账号标识（accountKey）、hub 的 ingest / stats / SSE、持久化格式、过期判定、认证与客户端接口子集；20–30 MiB 内存目标仍未实测。

设计见 ../docs/architecture.md，复用边界见 ../docs/reuse-survey.md。旧插件 `server/` 仅作参考。

## 构建与测试

按 docs/workflow.md，Go 只在哆啦编译与测试，Windows 本机不编译。工具链与缓存全部放在哆啦的缓存盘（GOROOT、GOPATH、GOBIN、GOMODCACHE、GOCACHE、GOTMPDIR 由一个 env 脚本导出），不随源码入库。

```sh
# 在哆啦加载 Go 环境后
cd backend
gofmt -l .
go vet ./...
go test ./...
GOOS=windows GOARCH=amd64 go build ./...
```

零第三方依赖，只用标准库，因此 Windows 专用文件也能在 Linux 上交叉编译与测试。
