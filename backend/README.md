# Go 后端入口

状态：设计已确认，代码尚未创建。

未来一个 Go 可执行文件，启动时选择 collector（设备本地采集、上传）或 hub（在哆啦接收并保存最新额度、提供读取）。首期仅 Claude Desktop 与 Codex 额度，不扫描会话和 Token 历史。

设计见 ../docs/architecture.md。旧插件 server/ 仅供参考，不能直接视为 Desktop 采集端。

实施前先读 [GitHub 能力复用调研](../docs/reuse-survey.md)，先复用已有实现，再补缺口；本目录尚未引入外部代码或 Go 依赖。
