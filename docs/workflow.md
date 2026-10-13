<!-- 2026-10-13: 本轮用户要求优先，禁止 GitHub Actions；C++ 改在哆啦 MinGW 交叉构建，Windows 仅部署运行。 -->
# 双设备开发与同步

## 已确认约束

GitHub `B-22/ai-quota-monitor` 是同步中心；哆啦保留源码主工作目录，Windows 保留开发副本。通过 Git 提交同步，不做文件级覆盖或自动部署。

**Windows 本机仅编辑、审阅和操作 Git，禁止 C++／Go 编译、测试、CMake 配置及工具链安装。** 不通过本机 Docker、WSL 或虚拟机绕过此约束。

| 内容 | 验证位置 |
|---|---|
| Go 采集／Hub、旧 Go 模块 | 哆啦测试、构建，按需交叉编译 Windows 产物 |
| Android App／小组件 | 哆啦测试、构建；SDK 和缓存遵循该设备缓存目录约定 |
| C++ TrafficMonitor 插件 | 哆啦 MinGW-w64 x64 交叉编译；有 Wine 时 CTest，否则报告限制并宿主实测 |

C++ 工作流已经位于统一仓库根目录，但用户于 2026-10-13 禁止触发 Actions，改用哆啦 MinGW-w64 x64 工具链。交叉构建全部默认目标；有 Wine 时运行 CTest，无 Wine 时标明测试只完成编译，并由 Windows 宿主机实测插件加载与 Hub 数据。

## 日常流程

1. 开工检查 git status 和分支，执行 `git fetch origin`；保留已有未提交改动。
2. 从更新后的 main 创建 `agent/<任务名>`。一项任务一个分支，同一分支同时只在一台设备编辑。
3. 换设备前提交指定文件，执行 `git push -u origin <分支名>`；另一端 fetch，首次 `git switch --track origin/<分支名>`，已有分支则切换后 `git pull --ff-only`。
4. 同时开发使用不同分支。fast-forward 失败先审阅分歧、保留双方提交；禁止强推和 reset --hard 清除工作。
5. 开发分支允许先推送待验证代码；Go／Android 在哆啦验证，C++ 在哆啦交叉验证。PR 记录验证位置、结果及对应提交；必要验证通过才合并 main／发布。
6. 合并后两端切换 main，确认工作树干净，再 `git pull --ff-only`。

Git 和文本检查不替代编译测试。凭据、真实 .env、运行配置、SDK 路径、日志、缓存和生成产物不进入 Git；验证后的安装包通过 Releases 分发。pull 不自动重启服务或覆盖运行文件。

## 迁移状态（2026-10-10）

迁移分支 agent/consolidate-sources 从 GitHub 原 main 派生，保留旧提交和标签；整合目录以经过脱敏的快照导入，本地私有导入历史不上传。两端通过 GitHub origin 同步，哆啦保留主工作目录。整合版本合并到 main；原插件版本保留在 legacy/trafficmonitor-original。根目录工作流执行云端 Windows x64 验证，不自动创建标签或发布。

## 手动构建与通知

默认不在 push 或 PR 时构建。Windows x64 验证工作流仅保留 workflow_dispatch，本轮禁止触发 GitHub Actions，改在哆啦交叉构建验证；仍禁止 Windows 本机 C++／Go 编译测试。GitHub Actions 邮件通知由账户通知设置控制，与工作流触发开关分开管理。
