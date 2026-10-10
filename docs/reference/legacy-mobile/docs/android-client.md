# Android 客户端

本项目自己写的原生 Android App，读取官方 Token Monitor Hub 的 API。

导航：项目总览见 [`../README.md`](../README.md) ｜ 部署运维见 [`hub-deployment.md`](hub-deployment.md) ｜ 协作规范见 [`../AGENTS.md`](../AGENTS.md)

App 是**纯展示层**：统计、聚合、Provider 探测全部由上游 Token Monitor 完成，App 只负责读取与渲染。凭据只有一个 Hub shared secret，App 不持有任何 Provider 凭据。

技术栈：Kotlin 2.0.21 + Jetpack Compose + Material 3，AGP 8.7.3 / Gradle 8.11.1，`compileSdk 35` / `minSdk 26`。255 个 JVM 单元测试（2026-10-09 运行，全部通过）。

小组件有两个：单账号 `AI 额度`（按设计稿 `AI 额度小组件.html` 的四档版式）与多账号 `AI 额度 · 多账号`（完整列表）。

最后更新：2026-10-10

---

## 项目结构

```
android-client/                      # Gradle 工程
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml       # version catalog
├── gradlew / gradlew.bat / gradle/wrapper/
├── local.properties                # 只含 sdk.dir，机器相关，不提交
└── app/
```

`android-client/` 目录职责：

| 路径 | 职责 |
|---|---|
| `settings.gradle.kts` | 模块声明 + 仓库声明（`google()` + `mavenCentral()`，保证可移植） |
| `build.gradle.kts` | 根构建脚本，只声明插件不带版本 |
| `gradle.properties` | JVM 参数、AndroidX 开关 |
| `gradle/libs.versions.toml` | version catalog，所有依赖与插件版本集中在这里 |
| `gradlew` / `gradlew.bat` / `gradle/wrapper/` | Gradle Wrapper（Gradle 8.11.1） |
| `local.properties` | 只含 `sdk.dir`，机器相关，**不要提交** |
| `app/` | 唯一的应用模块 |

`app/src/main/java/com/tokenmon/mobile/` 职责：

| 路径 | 职责 |
|---|---|
| `MainActivity.kt` | 宿主 Activity；Dashboard 与 Settings 两页用布尔值切换；绑定 STARTED/STOP 生命周期控制 SSE |
| `TokenMonitorApp.kt` | Application；持有手写 `AppContainer`（6 个单例）与 `NetworkMonitor` |
| `core/Json.kt` | 防御式 JSON 取值器（类型不对就返回默认值，不抛） |
| `core/LenientJson.kt` | 宽松的 `Json` 实例配置（容忍未知字段、容忍非严格输入） |
| `core/TimeFormat.kt` | 时间戳格式化、剩余时间、年龄、临近判断 |
| `data/HubClient.kt` | REST 调用 + `probe()`（"测试连接"的七种结果） |
| `data/HubStream.kt` | SSE 客户端 + 指数退避（`backoffDelayMs`） |
| `data/HubRepository.kt` | 数据通路编排：SSE 为主路，`/api/stats` 轮询为兜底 |
| `data/HubHttp.kt` | 两个 OkHttpClient：普通请求与 SSE（`readTimeout(0)`） |
| `data/SecretStore.kt` | Hub Secret 的加解密，Android Keystore AES-256-GCM |
| `data/SettingsStore.kt` | 非机密配置（Hub URL、是否已存 secret 等），DataStore |
| `data/model/Models.kt` | `HubStats` / `LimitProvider` / `LimitWindow` 等数据类 |
| `data/model/StatsParser.kt` | **唯一的 JSON 解析入口**，Dashboard、SSE、后台刷新、小组件全走它 |
| `data/model/ProviderCatalog.kt` | provider id → 显示名，纯装饰用途 |
| `data/model/WidgetSnapshot.kt` | 小组件快照的编解码（存 Hub 的 wire 格式） |
| `data/model/WidgetAppearance.kt` | 小组件背景外观枚举（跟随系统／浅／深／完全透明）与容错的取值 |
| `alerts/QuotaAlerts.kt` | 通知阈值纯逻辑 + `QuotaAlertSink` 接口 + 空实现 |
| `ui/DashboardViewModel.kt` | Dashboard 状态机（连接状态、数据、保存配置、测试连接） |
| `ui/DashboardScreen.kt` | 主界面：状态条、汇总卡、Provider 卡列表 |
| `ui/SettingsScreen.kt` | 设置页：Hub URL、Secret、测试连接、明文警告、小组件背景外观 |
| `ui/ProviderCard.kt` | 单个 Provider 卡片与状态角标 |
| `ui/Format.kt` | 百分比、金额、紧凑数字格式化 |
| `ui/theme/Theme.kt` | Material 3 主题与额度配色 |
| `work/QuotaRefreshWorker.kt` | WorkManager 周期任务（15 分钟），App 关闭时刷新小组件 |
| `widget/QuotaWidget.kt` | 单账号 Glance 小组件（`QuotaWidget` + `QuotaWidgetReceiver`），四档版式与共用零件 |
| `widget/MultiQuotaWidget.kt` | 多账号 Glance 小组件（`MultiQuotaWidget` + `MultiQuotaWidgetReceiver`），可滚动完整列表 |
| `widget/WidgetDesign.kt` | 设计稿（`AI 额度小组件.html`）的调色板与几何常量 |
| `widget/WidgetPresentation.kt` | 版式分档、缩放系数、窗口文案、重置卡状态等纯函数 |
| `widget/WidgetCache.kt` | 小组件快照存储（DataStore） |
| `widget/WidgetSurface.kt` | 底板与文字色（纯函数 `widgetSurface`）、语气色与重置卡配色（`widgetChrome`） |
| `res/` | 图标、主题、`network_security_config.xml`、`data_extraction_rules.xml`、`quota_widget_info.xml` |

`app/src/test/` 职责：

| 路径 | 职责 |
|---|---|
| `java/com/tokenmon/mobile/**Test.kt` | 纯 JVM 单元测试（不依赖 Android framework），覆盖解析、格式化、退避、告警阈值、小组件快照 |
| `resources/fixtures/hub-stats-real.json` | **真实 Hub 响应原文**，由上游 Node Hub 在 commit `d8a798c` 上实际返回，`HubPayloadContractTest` 用它做契约测试 |

包名 `com.tokenmon.mobile`。技术栈：Kotlin 2.0.21 + Jetpack Compose + Material 3，AGP 8.7.3 / Gradle 8.11.1，`compileSdk 35`、`targetSdk 35`、`minSdk 26`。依赖只有 `core-ktx`、lifecycle、activity-compose、Compose BOM 2024.12.01、`datastore-preferences`、`glance-appwidget` + `glance-material3`、`work-runtime-ktx`、okhttp + okhttp-sse、`kotlinx-serialization-json`、`kotlinx-coroutines-android`，测试用 junit4 + `kotlinx-coroutines-test`。

**刻意没有引入**：React Native、Flutter、WebView 套壳、DI 框架、导航框架、图片加载库、任何自建中转服务。依赖注入是手写 `AppContainer`；两个页面用一个布尔值切换，不引入 nav 库。

---

## 当前 Token Monitor 上游 commit

| 项 | 值 |
|---|---|
| 仓库 | https://github.com/Javis603/token-monitor.git |
| commit | `d8a798ca1171420b4824399cd9c4abd54252ed46` |
| 日期 | 2026-09-30 |
| 标题 | `feat(edge-dock): animate quota refills on rail rings and the card (#893)` |

`upstream-token-monitor/` 是**只读参考**，不修改、不打 patch。检查上游有没有新提交：

```bash
cd <device-local-path>
git fetch
git log --oneline HEAD..origin/main
```

是否 pull / reset 由使用者决定，本项目不会自动执行。App 的行为与上游新提交无关，重新同步克隆后需要自行核对 `StatsParser` 的字段假设是否仍然成立。

---

## 架构

```
  开发机 / 日常电脑                      Hub（通常是 VPS）                      Android 手机
┌───────────────────────┐   POST /api/ingest  ┌────────────────────────┐  HTTPS 或 Tailscale  ┌──────────────────────┐
│ Token Monitor         │ ──────────────────► │ Token Monitor Hub      │ ◄──────────────────  │ 本项目 Android App   │
│ Agent / Widget        │                     │ Node Hub 或 Cloudflare │  GET  /api/stats     │ 只读 + 展示          │
│ （探测 Provider、写数据）│                     │ Worker                 │  GET  /api/stats/    │                      │
└───────────────────────┘                     │ 做聚合，Hub 是唯一事实源│  stream (SSE)        └──────────────────────┘
                                              └────────────────────────┘
                                                       ▲
                                                       └─ 官方桌面客户端、iOS、脚本也只连这一个 Hub
```

**没有中间层。** 具体地：

- 没有自建 API / HTTP 中转
- 没有协议桥或反向代理转换层
- 没有自建数据库
- 没有第二套 Hub
- 没有定时转存脚本
- 没有 Hub 侧为了配合 App 而加的定时任务或 cron

所有统计、聚合、Provider 探测都由官方 Token Monitor 完成。App 只负责**读取 + 展示**。

App 运行时只知道两样东西：**Hub URL** 和 **Hub Secret**。它不保存 Claude token、sessionKey、OpenAI OAuth token、Codex token、Kimi token 或任何 Provider API Key —— 这些东西根本不在 App 的数据流里。

---

## 实际使用了哪些 API

事实来源是上游源码，不是猜的：`upstream-token-monitor/src/hub/server.js`（Node Hub）与 `upstream-token-monitor/worker/src/index.js`（Cloudflare Worker），两者暴露的路径一致。

| 端点 | 用途 | 需要认证 |
|---|---|---|
| `GET /api/health` | 唯一免认证端点。用于"测试连接"判断可达性与 Hub 类型 | 否 |
| `GET /api/stats` | 拉取聚合结果。手动刷新、轮询兜底、WorkManager 后台刷新 | 是 |
| `GET /api/stats/stream` | SSE 实时流。前台运行时的主数据通路 | 是 |
| `GET /api/devices` | 仅在"测试连接"里确认设备列表可读。主界面不用 | 是 |

`/api/ingest` 是电脑端 Agent 写数据的端点，App 不调用。

`GET /api/health` 返回：

```json
{ "ok": true, "role": "hub", "runtime": "node-hub", "version": 1,
  "hubBuild": { ... }, "deviceCount": 2, "secretRequired": true, "now": "..." }
```

`hubBuild` 可能缺失（旧版 Hub），App 按兼容处理，不当作错误。

### 认证

| 方式 | 说明 |
|---|---|
| `Authorization: Bearer <TOKEN_MONITOR_SECRET>` | **首选**，App 用这个，官方客户端也用这个 |
| `X-Token-Monitor-Secret: <secret>` | 上游同样接受 |
| `?secret=` | Cloudflare Worker 为兼容 iOS widget 仍接受，**App 绝不使用** |

URL 里的 secret 会进代理日志、平台日志和浏览/历史记录，所以不走 URL。

### AI Tool Limits 数据结构

来源：`upstream-token-monitor/src/shared/limits/core.js`，经 `GET /api/stats` 出现在 `stats.limits.providers[]`。

`providers[]` 每项：

| 字段 | 含义 |
|---|---|
| `provider` | provider id。当前上游共 29 个：`claude` `codex` `opencode` `cursor` `antigravity` `cline` `factory` `kimi` `grok` `copilot` `zed` `commandcode` `mimo` `zai` `zaiteam` `kiro` `workbuddy` `qoder` `deepseek` `devin` `typesafe` `openrouter` `minimax` `volcengine` `ollama` `trae` `alibaba` `stepfun` `thirdparty` |
| `accountKey` / `accountEmail` / `accountName` / `accountLabel` | 账号身份（`accountKey` 是 sha256） |
| `planLabel` / `workspaceKind` / `adapterId`（仅 `thirdparty`） | 套餐与工作区信息 |
| `status` | 闭集：`ok` `disabled` `notConfigured` `unauthorized` `rateLimited` `sourceRateLimited` `unavailable` `error` |
| `source` | `oauth` / `cli` / `web` / `rpc` / `local` / `api` |
| `updatedAt` | 该 provider 数据的时间戳 |
| `stale` | 布尔。Hub 跨设备聚合时加的标记，表示这条数据已过期 |
| `windows[]` | 额度窗口，见下 |
| `balance` / `balanceUsd` | 余额 |
| `resetCredits` | Codex 重置卡：`availableCount`（可为 null）、`nextExpiresAt`、可选 `expirations[]`；字段缺失不能当作零张 |

`windows[]` 每项：

| 字段 | 含义 |
|---|---|
| `kind` | `session` / `daily` / `weekly` / `billing`；未知值直接丢弃 |
| `label` | 展示用标签，可为空，≤32 字符 |
| `metric` | `credits` 或 `spend`，可缺省 |
| `usedPercent` | 0..100，可为 null |
| `remainingPercent` | `= 100 - usedPercent` |
| `used` / `limit` / `remaining` | 可缺省 |
| `resetsAt` | ISO 时间，可为 null |
| `resetDescription` / `boundaryKind` | 展示文本；`reset` / `expiry` / `mixed` |
| `additional` | true 表示独立计量桶 |
| `limitId` / `currency` / `windowMinutes` | 窗口标识与货币 |
| `showMeter` | 默认 true；false 表示 Hub 声明该窗口没有分母 |
| `detail` | 仅展示用文本 |

**关键语义（App 严格遵守）**

- `metric: "credits"` 的窗口，主角是**金额**，不是百分比。上游明确说明余额类 provider 不发布百分比，任何百分比都应由渲染层自行推导且刻意不上线。所以 App 对这类窗口**显示金额，且不画进度条**。
- `showMeter: false` 表示 Hub 声明没有分母，同样不画进度条。
- `usedPercent` 缺失但 `used` 与 `limit`（且 `limit > 0`）都存在时，App 按 `used / limit * 100` 推导，与上游 `percentFromWindow()` 一致，结果 clamp 到 0..100。`limit = 0` 不推导。

### SSE 帧语义

- 连接建立后 Hub **先发一条完整 `snapshot`**，所以重连永远能恢复完整状态，不依赖之前的事件。
- 之后每 30 秒一条 `: hb` 心跳注释。
- 数据变化时发 `stats`（完整）或 `freshness`（增量）事件。

App 会发 `x-token-monitor-stream: 2`（官方客户端用的头）。带这个头时，"只有时间戳变了"的 ingest 只发一条小的 `freshness` 帧，App 按上游 `applyFreshnessEvent()` 的语义合并：只更新时间和 `stale` 状态，保留 `limits.providers` 与设备明细。这个头是建议性的，Hub 忽略它就退回发完整 `stats` 帧，App 一样处理。

---

## Android App 如何编译

本机环境（已验证）：

| 项 | 值 |
|---|---|
| JDK | 系统自带 OpenJDK 21（`/usr/bin/java`），满足 AGP 8.7 要求 |
| Android SDK | 官方 Google cmdline-tools，装在 `/cache/android-sdk`；已装 `platforms;android-35`、`build-tools;35.0.0`、`platform-tools` |
| `GRADLE_USER_HOME` | `/cache/gradle`（Gradle 8.11.1 已缓存） |

SDK 放 `/cache` 是因为本机存储分层：可重建的运行环境放 WDC 盘（`/cache`），唯一数据放 Samsung 盘。

编译命令：

```bash
cd <device-local-path>
export GRADLE_USER_HOME=/cache/gradle
export ANDROID_HOME=/cache/android-sdk
./gradlew assembleDebug
```

单元测试：

```bash
./gradlew :app:testDebugUnitTest
```

同样需要上面两个环境变量。

**换机器要改什么**

| 项 | 做法 |
|---|---|
| `local.properties` | 改 `sdk.dir` 指向新机器的 SDK 路径（机器相关文件，不要提交） |
| 或 | 不改文件，直接设 `ANDROID_HOME` 环境变量 |
| `GRADLE_USER_HOME` | 换默认位置即可；不设时 Wrapper 会下载 Gradle 8.11.1 |
| JDK | 需要 17 及以上（本机是 21） |

**关于 Maven 仓库**

项目本身用标准的 `google()` + `mavenCentral()`（见 `settings.gradle.kts`），保证在能直连的环境里开箱可构建。

本机在境内网络，JVM 直连 `repo.maven.apache.org` 的 TLS 握手会被中断，因此镜像配在 **环境级 Gradle init script**：

```
/cache/gradle/init.d/00-cn-mirrors.gradle
```

它前置 aliyun 的 google / public / gradle-plugin 三个镜像。这是 Gradle 官方的 init script 机制，**不在项目里**，删掉那个文件只影响本机构建，不影响项目可移植性。换到能直连的环境时，不装这个 init script 也能构建。

---

## APK 在哪里

| 位置 | 说明 |
|---|---|
| `output/token-monitor-mobile-debug.apk` | **取用这个**，debug 签名，**`versionName 1.0.14-debug` / `versionCode 15`** |
| `android-client/app/build/outputs/apk/debug/app-debug.apk` | `./gradlew assembleDebug` 的原始产物 |

debug 构建的 `applicationId` 是 `com.tokenmon.mobile.debug`（正式包是 `com.tokenmon.mobile`），可以和正式包共存。当前只配置了 debug 签名，没有 release keystore。

安装：`adb install -r output/token-monitor-mobile-debug.apk`

APK 内实际声明的权限（已用 `aapt2 dump badging` 核对）：

| 权限 | 来源 | 是否必需 |
|---|---|---|
| `INTERNET` | 本 App | 必需，连 Hub |
| `ACCESS_NETWORK_STATE` | 本 App | 必需，网络恢复后立即重连 |
| `WAKE_LOCK` | WorkManager 合并进来 | 保留，WorkManager 调度需要 |
| `RECEIVE_BOOT_COMPLETED` | WorkManager 合并进来 | 保留，重启后恢复周期刷新 |
| `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | AndroidX 运行时自动生成 | 平台机制 |

`FOREGROUND_SERVICE` 被 WorkManager 的库清单注入后**显式移除**了（`tools:node="remove"`）：本 App 不跑任何前台服务，保留它等于让装好的 App 声称一个从不使用的能力。

---

## App 如何填写 Hub URL

首次打开 App → 右上角齿轮图标进设置页 → 填 Hub 地址。

| 写法 | 示例 | 说明 |
|---|---|---|
| HTTPS + 域名 | `https://hub.example.com` | **推荐（本机实际使用）**，EO CDN + Lucky 反代终结 TLS |
| HTTP + 局域网 IP | `http://203.0.113.10:17321` | 同一局域网内，明文 |
| HTTP + Tailscale IP | `http://100.101.102.103:17321` | Tailscale `100.x.x.x` 地址 |
| 裸主机名 | `token.example.com` | 没有 scheme 时**自动补 `https://`**（不会补 `http://`，未限定地址更可能是真域名） |
| 带子路径 | `https://example.com/token-monitor` | App 会正确拼成 `.../token-monitor/api/stats` |

尾部多余的 `/` 会被去掉。URL 非法时"测试连接"返回"地址无效"，不会静默改写。

主界面：顶部连接状态与数据年龄，下面是可展开的 Hub 摘要与按上游顺序排列的 Provider 卡。账号标识沿用既有兜底；窗口周期是主要标签，模型等 wire 标签作为次级信息。窗口剩余百分比置于圆环内，同时显示重置日期时间与倒计时；空间不足或字体放大时改为单列。

圆环弧长、环内数字和颜色均表示**剩余**百分比：剩余 ≤20% 红、≤40% 黄、其余绿。未知值使用破折号，金额窗口显示金额并遵循 `showMeter`。

Codex 卡片另外展示“重置卡”：可用张数与 Hub 返回的到期时间列表（按手机时区显示，附倒计时）。明确返回 0 时显示“可用 0 张”；只有时间、数量为 null 时显示“数量未知”；字段缺失时隐藏该区域。到期后显示“已到期，等待更新”，数量始终以 Hub 上报为准，不根据时间列表推算或扣减。卡数与到期信息参与实时更新的内容指纹；小组件快照保留这些字段，中／宽组件显示卡数，大组件同时显示最近到期与可滚动的完整到期列表。

2026-10-03 已对本机真实 `GET /api/stats` 核实：Codex 返回 4 张可用卡及 4 个到期时间。响应仅提取 Provider 类型、状态和重置卡字段，移除账号、设备信息后存入 `src/test/resources/fixtures/hub-codex-reset-credits-real.json`；回归测试覆盖 REST、SSE 解包、快照往返、零张/未知、无效数据、到期文案与内容变化。手机上的布局与交互仍待验收。

右上角刷新图标或下拉刷新 = 立即 `GET /api/stats`。

---

## 如何填写 Secret

Secret 就是 Hub 上的 `TOKEN_MONITOR_SECRET`。设置页填入，字段默认掩码，右侧眼睛图标切换明文。点"测试连接"验证通过后点"保存"写入；Secret 变更会立刻重连 Dashboard。

### 怎么存的

- 直接用 **Android Keystore**：`KeyGenParameterSpec` + `AES/GCM/NoPadding`，密钥 256 位，生成在 Keystore 内、**不可导出**。
- 每次写入用**新的 12 字节 IV**，密文（Base64）和 IV 存在 app 沙箱内的 DataStore。
- 不用 `androidx.security.crypto.EncryptedSharedPreferences`：AndroidX 已在当前**稳定版** `security-crypto:1.1.0` 里把 `MasterKey` 和 `EncryptedSharedPreferences` 全部标记 deprecated，官方引导方向就是直接用 Keystore。
- Key **故意不**设置 `setUserAuthenticationRequired(true)`：小组件要在后台刷新，需要用户解锁的 key 会让每次后台刷新直接抛 `UserNotAuthenticatedException`。
- Keystore 失效时（改锁屏密码、换机恢复）解密必然失败，App 返回"需要重新输入"，不崩溃也不假装成功。

### 为什么不放 URL

`Authorization: Bearer` 是首选方式。Cloudflare Worker 为兼容 iOS widget 仍接受 `?secret=`，但 URL 里的 secret 会进代理日志、平台日志和历史记录。App 不走这条路。

---

## Hub 需要开放什么接口

App 只发起 GET，Hub 侧需要能响应这四个路径：

| 路径 | 谁调用 |
|---|---|
| `GET /api/health` | App（免认证） |
| `GET /api/stats` | App |
| `GET /api/stats/stream` | App（SSE，需支持 `text/event-stream` 且不被缓冲） |
| `GET /api/devices` | App（仅测试连接） |
| `POST /api/ingest` | **电脑端 Agent**，不是 App；Hub 必须接受它才有数据 |

### Hub 侧要不要配 TOKEN_MONITOR_SECRET

要配。App 依赖它做认证。

但两种 Hub 的默认行为不同，值得知道：

| Hub | 没配 secret 时的行为 |
|---|---|
| **Node Hub** | `resolveBindHost()` 会把监听地址**强制降到 `127.0.0.1`**，并打印警告（`Warning: TOKEN_MONITOR_SECRET is not set, so the hub is bound to ... (localhost only) to keep account identity off the network.`）。也就是说不配 secret 就只有本机能连，手机连不上 |
| **Cloudflare Worker** | 没有这个绑定限制；`/api/health` 会返回 `secretRequired: false`，此时数据以匿名方式返回，App 也会提示"该 Hub 未设置 TOKEN_MONITOR_SECRET" |

上游实现见 `upstream-token-monitor/src/hub/server.js` 的 `resolveBindHost()` 与 `worker/src/index.js`。

> ⚠️ 上表的"降级到 127.0.0.1"是**唯一的保护**。`src/shared/http.js` 的
> `isAuthorized()` 是 `if (!expectedSecret) return true` —— secret 为空时 Hub
> **接受任何请求**，不是拒绝。绑定地址管住了网络可达性，但一旦 secret 丢失而
> 绑定地址又不在 `127.0.0.1`，Hub 会静默变成完全开放，不报错也不拒启。

### Hub 上还有哪些接口（本 App 不用）

列出来是为了说明**为什么 App 只用 4 个端点**——下面这些都存在，但和"看额度"无关：

| 端点 | 用途 | App 用吗 |
|---|---|---|
| `POST /api/ingest` | 采集端上报设备用量 | 否（App 是只读） |
| `GET /api/history` | 聚合历史 | 否 |
| `GET /api/subscriptions` | 共享订阅列表（记录订阅花费） | 否 |
| `PUT /api/subscriptions` | 覆盖订阅列表，带 `baseUpdatedAt` 并发控制 | 否 |
| `DELETE /api/devices/:id` | 删除设备记录（改 deviceId 后用） | 否 |

全部走同一个 secret 门。`/api/subscriptions` 尤其不该经公网暴露：那是记录
"你每个月付多少钱"的地方，而 `/api/stats` 只给额度百分比。

### 反向代理注意事项

SSE 需要代理**不要缓冲** `text/event-stream`。Hub 自己已经发 `cache-control: no-cache, no-transform` 和 `x-accel-buffering: no`，但 nginx 一类代理仍可能缓冲。App 为此并行跑了一个 `/api/stats` 轮询兜底来兜底这种情况（见下）。

---

## HTTP / HTTPS / Tailscale 使用方法

| 方式 | 地址示例 | 传输 | 说明 |
|---|---|---|---|
| HTTPS（推荐） | `https://token.example.com` | 加密 | 有域名走反代，证书校验保持 Android 默认 |
| HTTP 局域网 | `http://203.0.113.10:17321` | 明文 | 同一 Wi-Fi 下；App 会显示"当前连接未加密，仅建议用于可信局域网或 Tailscale 网络" |
| Tailscale | `http://100.101.102.103:17321` | 明文（在加密隧道内） | 推荐组合：Tailscale 提供加密，手机不需在同一局域网 |

`network_security_config.xml` 允许明文 HTTP，因为局域网/Tailscale 场景确实需要。但除此之外**没有**任何放宽：没有自定义 `TrustManager`、没有信任用户 CA、没有覆写 hostname verifier，所有 https 的证书校验保持 Android 默认。

App 在填了 `http://` 时，设置页和 Dashboard 都会显示未加密提示。

---

## 当前已实现功能

| 功能 | 位置 |
|---|---|
| 读取 `/api/health`、`/api/stats`、`/api/devices`、`/api/stats/stream` | `data/` |
| Dashboard：状态条 + 可展开 Hub 摘要 + 按 Hub 顺序的 Provider 卡列表 | `ui/DashboardScreen.kt` |
| Provider 卡：名称、账号标识兜底、状态角标、逐窗口剩余额度圆环与重置时间 | `ui/ProviderCard.kt` |
| Codex 重置卡：可用张数、到期时间列表与倒计时 | `data/model/StatsParser.kt`、`ui/ProviderCard.kt` |
| 圆环弧长、环内数字和颜色均按**剩余**百分比（≤20% 红 / ≤40% 黄 / 其余绿） | `ui/ProviderCard.kt` |
| 设置页：Hub URL、Secret、七种结果的"测试连接"、明文警告 | `ui/SettingsScreen.kt` |
| Secret 用 Android Keystore AES-256-GCM 加密存储，失效时提示重输 | `data/SecretStore.kt` |
| SSE 实时更新（含 `freshness` 增量合并） | `data/HubStream.kt`、`data/HubRepository.kt` |
| 断线指数退避重连（基准 1s、×2、上限 60s、抖动 0.5–1.0 倍） | `HubStream.backoffDelayMs` |
| 轮询兜底：SSE 断着时 30s 一次，SSE 正常时 120s 存活检查 | `HubRepository.POLL_ACTIVE_MS / POLL_IDLE_MS` |
| 网络恢复立即重连（`registerDefaultNetworkCallback`） | `TokenMonitorApp.kt` 的 `NetworkMonitor` |
| SSE 仅在前台、自动刷新开启且 Hub 有效时运行；关闭自动刷新显示暂停 | `MainActivity.kt`、`ui/DashboardViewModel.kt` |
| 倒计时文字由 UI 侧 30s 定时器刷新，不产生网络请求 | `ui/DashboardScreen.kt` |
| **两个小组件**：单账号（`AI 额度`）与多账号（`AI 额度 · 多账号`），分别添加、互不影响 | `widget/QuotaWidget.kt`、`widget/MultiQuotaWidget.kt` |
| 单账号小组件：`SizeMode.Exact`，按 Launcher 实际尺寸在 1×1／1×2／2×1／2×2 四档版式间切换（设计稿见 `AI 额度小组件.html`） | `widget/QuotaWidget.kt`、`WidgetPresentation.kt` |
| 1×1：一双同心环（外 5 小时、内 7 天），环心是窗口名 + 大百分比 + 紧凑倒计时，下方一行 7 天，右上角重置卡角标 | `widget/QuotaWidget.kt`、`WidgetRing.kt` |
| 1×2／2×1／2×2：两行（列）单环 + 文字列；2×2 额外显示更新时间；1×2 与 2×2 底部是重置卡区块（无卡／可用／快过期三态） | `widget/QuotaWidget.kt` |
| 环色按窗口身份（5 小时蓝、7 天橙），剩余不足 20% 一律转红；环心大数字同步转浅红 | `widget/WidgetSurface.kt`、`WidgetDesign.kt` |
| 多账号小组件：多 Provider、所有窗口、重置卡最近到期及完整到期列表；内容可滚动 | `widget/MultiQuotaWidget.kt` |
| 小组件背景跟随外观设置：跟随系统／浅色／深色／透明·黑字／透明·白字（透明两档不画底，文字极性由用户定死） | `widget/WidgetSurface.kt`、`data/SettingsStore.kt` |
| 小组件选择器预览是静态 layout，不显示永久转圈 | `res/layout/widget_preview.xml`、`widget_preview_multi.xml` |
| 小组件三重刷新来源：App 抓取后重画 / `updatePeriodMillis = 1800000` / WorkManager 15 分钟；改外观后立即重画，且两个组件一起重画 | `widget/`、`work/`、`ui/DashboardViewModel.kt` |
| 手动刷新（右上角图标、下拉） | `ui/DashboardViewModel.kt` |
| 关闭系统备份（`allowBackup=false` + `data_extraction_rules.xml`） | `AndroidManifest.xml`、`res/xml/` |

### 2026-10-10：修正小组件尺寸适配与字体

当前状态：四档适配与排版已修复；本机验证结果见下文。适用范围为 Android 客户端，未操作真实手机、Hub、凭据或部署；上游只读。

- 单账号按长宽比选择候选结构，再以内容预算决定缩放或降级；取消固定 150/90dp 门槛。预算包括 padding、字号下限、系统 fontScale、单环最小直径、日期、卡区、标题行和显式间距；详细档缩放范围为 0.30–1.2。尺寸缺失用 180×160dp 参考值，再走同一预算，默认字体下为 SQUARE。参考值和测试输入均不是 Launcher 实测。
- 默认字体下的回归输入：145×145 → SQUARE，215×85 → PANEL，85×215 → TALL（窄竖条上下结构），105×105 → TINY。大字体或空间不足时允许降级，不能承诺某个 Launcher 格数必然对应某档。
- 窄 TALL 将圆环置于标签、倒计时和日期上方；卡区不并排放图标，显示卡数与完整过期倒计时。其余大布局补标题、日期、卡区间距和剩余纵向空间分配；SQUARE 使用紧凑完整倒计时 `↻4d19h`，省去冗余「后重置」，顶部保持「更新 HH:mm」。PANEL 卡提示含「重置卡」及数量／过期倒计时。
- TINY 补回角标并预留高度，避免角标覆盖圆环文字；根据 fontScale 和实际剩余空间缩环，必要时省略环心次级文字和底行。修复原 TinyBody 缺失括号导致的编译问题。
- 单账号文字统一通用 SansSerif，百分数与较小的 `%` 分开排，倒计时采用同一字体。保留实际 Text 的系统字体缩放与无障碍能力。没有打包设计稿自定义字体；多账号保留既有位图数字实现。
- 单环按稿子分别使用 78/r34/线宽8、88/r38/线宽9、124/r54/线宽11 的比例；双同心环沿用稿子比例。
- 跟随系统、浅色、深色的底板均不透明；「透明 · 黑字」「透明 · 白字」仍由用户显式选择。未增加使用卡按钮、网络、服务或依赖。
- 单账号和多账号仍读同一份快照，并由统一刷新入口重画。未知卡数显示「—」，不再兜底编造一张卡。

本机验证：2026-10-10 离线运行 `:app:testDebugUnitTest :app:assembleDebug` 成功；261 项 JVM 测试，失败／错误均为 0。APK 为 `1.0.14-debug` / code 15；交付文件与本次构建原文件 SHA-256 一致：`00a5b9ab20ea8a8a6bc213ef4a27dfe7749e3ea6d3c15d4dfcc51a597ec30c9a`。存在既有 Android Parcelable 读取 API 弃用警告，不影响本次构建。回滚备份：`/tmp/widget-fix-20261010-130000/`（保持项目相对路径，含原 APK）；未初始化、提交或推送 Git。

手机验收：由用户安装 1.0.14-debug / code 15 后验证四档拖拽、窄竖条、大字体、倒计时与角标，以及五种外观。未做真机或模拟器渲染验收，字体实际度量、Launcher 内边距与圆角效果仍待核对。

### 2026-10-08：小组件背景可读性（深色壁纸下看不清字）

当前状态：客户端改动已实现并离线构建通过；未进行真机或模拟器界面验收，也未交付 APK。

- **问题**：`WidgetBody` 原本完全不画底，文字色取 `GlanceTheme.colors.onSurface`／`onSurfaceVariant`，跟的是**系统深浅主题**而不是壁纸明暗。浅色系统 + 深色壁纸就是近黑文字压在近黑像素上。
- **为什么不能靠文字自救**：动态取色走的是 API 31+ 的 `RemoteViews.setColorStateList`，由 Launcher 按自己的主题解析颜色，Glance 1.1 没有暴露这个口子；文字阴影／描边同理没有。所以纯透明的前提本身就无法保证可读，必须给文字一块自己的底。彻底自动跟壁纸要走 Compose 小组件（`RemoteComposeView`），本次不做。
- **做法**：默认画 alpha 0.8 的底板 + 16dp 圆角，底板画在 padding 之前（否则只有文字那一块有底，四边露壁纸）。颜色不再走 `GlanceTheme`，统一由纯函数 `widgetSurface(appearance, systemDark)` 定死，取 App 主题同一套 `surface`／`onSurface`／`onSurfaceVariant` 值。`GlanceTheme` 只提供颜色，本轮后已无用，一并移除；`LocalSize` 由 Glance 的 `SizeBox` 提供，不依赖 `GlanceTheme`，布局切分不受影响。
- **设置项**：设置页新增「小组件背景」单选，四选一 —— 跟随系统（默认，半透明底板）、浅色、深色、完全透明。存 `hub_settings` 的 `widget_appearance`，未知值退回 `SYSTEM`。改完立即 `updateAll`，否则要等下一次 15 分钟后台任务才生效。
- **一个容易错的地方**：固定深／浅时**文字色必须一起翻**，否则浅底浅字。同时圆环语气色的 `dark` 也改由 `widgetSurface` 给出，`QuotaRing` 不再自己读 `uiMode`，否则会出现浅色环画在深底上。
- `TRANSPARENT` 不画底，但**仍然跟随系统明暗**给文字和圆环——纯透明时唯一能用的就是主题色。

本机验证：2026-10-08 离线运行 `:app:testDebugUnitTest :app:assembleDebug` 成功；276 项测试无失败。新增 `widget/WidgetSurfaceTest` 6 个用例，覆盖"跟随系统两极都画底""透明只去底不去明暗""固定外观无视系统主题且底与文字同极""底板保持半透明""未知存储值退回 SYSTEM"。没有操作真实手机、读取真实凭据或修改部署。

手机验收：四个外观各放一个实例，在深色与浅色壁纸、系统浅色与深色四种组合下确认文字与圆环都清楚；确认固定深／浅时切换系统深浅，小组件外观不变；确认改完设置不用等 15 分钟就生效。

### 2026-10-07：1×1 小组件改为两行额度与重置卡提醒

当前状态：客户端改动已实现；适用版本 `1.0.8-debug` / code 9。新版本未进行真机或模拟器界面验收。

- 1×1 不再只显示一个环。同一账号的 5 小时与每周各占一行：`8.5sp` 标签在左、`12sp` 粗剩余百分比在右，下方一条 3dp 细进度条，颜色沿用既有的剩余阈值（≤20% 红 / ≤40% 黄 / 其余绿），未知额度显示 `—` 且只画轨道不填充。
- 重置卡最近一次到期落在 24 小时内时，在额度行下方追加两行提醒：上行 `卡 21:30 到期`（绝对时刻，粗体，闹钟图标），下行 `剩 3 小时`（倒计时，同一语气色）。不足 6 小时转为最高语气。`availableCount` 为 0、或到期时间已过去，都不显示——不在小格上常驻"已到期"。
- 一格只有约 66 × 66 dp，所以字号定死、系统字体最多跟随到 1.15 倍；空间不够时按「两组额度 → 提醒时刻行 → 账号名 → 提醒倒计时行」丢弃，只有一个额度窗口或高度不足时退回原来的单环单行。快照里有第二个账号时顶部显示账号名。
- 排版规则与文案全部在 `WidgetPresentation.compactPlan` / `cardExpiryAlert` 这两个纯函数里，`QuotaWidget` 只负责画；细条用 `Box + background + cornerRadius`，填充宽度由一格实际宽度算出（Glance 1.1 没有 `fillMaxWidth(fraction)`）。
- 行为变化：1×1 选账号改用 `focusedProvider`（与中／大格同一套规则），一格内不再跨 Provider 混搭；原先"整格只显示全局最紧张窗口"的行为只保留在放不下两行时的单环降级里。

本机验证：2026-10-07 离线运行 `:app:testDebugUnitTest :app:assembleDebug` 成功；270 项测试无失败，APK 版本为 `1.0.8-debug` / code 9。交付文件与本次构建原文件 SHA-256 一致。没有操作真实手机、读取真实凭据或修改部署。

手机验收：先删掉旧的 1×1 实例再重新放置（Launcher 会保留旧尺寸），确认两行额度与细条没有被裁、提醒出现时是琥珀色且不足 6 小时转红；顺带看深浅色壁纸与大字体下的降级表现。排版图见 `/srv/archive/media-artifacts/2026-10-07-widget-1x1-design/`，其中 74dp 是**假设值**，需与实测尺寸核对。

### 2026-10-07：圆环、自适应布局与连接恢复

当前状态：客户端改动已实现；适用版本 `1.0.7-debug` / code 8。新版本未进行真机或模拟器界面验收。

- App 显式设置中性深浅色主题；各窗口使用剩余圆环与环内数字，修正原来数字显示已用、颜色显示剩余的语义冲突。窗口周期优先显示，模型标签保留为次级信息；Hub 与重置卡详情可展开。设置页统一间距与层级，大字体时窗口改为单列。
- 小组件采用 `SizeMode.Exact`；尺寸缺失时读 Launcher options 判定未知，而非误用 XML 最小值。小格单环，中／宽格双窗口和卡数，大格提供可滚动完整详情。当时是透明背景，圆环为原生绘制资产，不依赖网络或生成图片；透明背景已在 2026-10-08 一节改为可设置底板。
- 已有数据时，短暂 SSE 断流只显示顶栏中性状态，不插入错误横幅；轮询成功后保持“定时更新”，不被重试状态覆盖。60 秒没有成功数据且有连接失败时转为离线，保留上次额度；401/403 始终明确报告认证失败，解析失败可重新连接或轮询。
- 仅当 SSE 实际观察到 HTTP/2 `stream was reset: INTERNAL_ERROR` 时，在该 feed 内改用 HTTP/1.1 重试；不改变 REST、认证、TLS 校验或服务部署。截图能确认 HTTP/2 流重置，但尚未确认具体 CDN／反代故障层；新版本是否消除每分钟断流仍待手机连接观察。
- 数据流按前台、自动刷新与有效 Hub 配置统一启停；同为“已存 Secret”的凭据替换也会重连。切换 Hub／凭据前取消旧请求并清除旧显示及组件快照。前台缓存写入后真正触发组件重绘，与缓存共用一分钟节流。后台 Worker 与配置修改共享连接锁，抓取前记录配置及内存修订号，返回后复核再写入；已切换 Hub／Secret 的在途旧请求不能回写快照。
- `UiPreviews.kt` 提供纯合成数据的浅深色、窄屏大字体与连接状态预览，不触发网络或读取真实配置；本轮未实际渲染这些 Preview。

本机验证：2026-10-07 离线运行 `:app:testDebugUnitTest :app:assembleDebug` 成功；254 项测试无失败，APK 版本为 `1.0.7-debug` / code 8。交付文件与本次构建原文件 SHA-256 一致。没有操作真实手机、读取真实凭据或修改部署。

手机验收：覆盖安装新版本，检查 1×1、中／宽、大组件拖拽缩放、重置时间与卡数；检查深浅色和大字体；保持 App 前台至少 3 分钟，确认短暂重连不闪红横幅；真实断网／错误 Secret 应仍有明确提示。

### 实时刷新的几个决定

- **抖动是必要的**：多台手机在 Hub 重启后会同时重连。没有抖动就会 lockstep 重试。
- **不会疯狂重试**：下限 1s、上限 60s，Hub 挂了也不是紧循环打它。
- **轮询兜底是给反代准备的**：某些反向代理会悄悄缓冲或丢弃 `text/event-stream`，SSE 看着"连着"但收不到帧。轮询保证数据最终会更新。
- **小组件自己不联网**：Launcher 托管的小组件只有很短的、不可预测的执行窗口，自己发请求天生不可靠；要保持连接就得常驻前台服务，而这个 App 明确不做。小组件只渲染 App 写下的快照。
- **快照存成 Hub 的 wire 格式**，用同一个 `StatsParser` 解回来 —— 一套解析器、一份 schema，Dashboard 认识的字段小组件自动就认识。
- **两套组件分工**：单账号组件按设计稿只画一个账号，多账号组件（`MultiQuotaWidget`）承接所有账号与到期日期的完整列表。两个组件读同一份快照，因此刷新入口统一走 `updateQuotaWidgets()` —— 只刷一个会留下一个显示旧数据的组件，而用户看不出另一个是旧的。
- **单账号按内容预算分四档**：`SizeMode.Exact` 使用 LocalSize；候选形状按长宽比选，缩放后仍须容纳文字、圆环、卡区、fontScale 和间距，否则降级 TINY。尺寸缺失用 180×160dp 参考值，不代表实测格数。
- **设计稿尺寸要缩放**：各档使用自己的宽高基准，详细档缩放系数以两边可用空间和内容预算共同限制；TINY 另按实际剩余高度缩环，角标预留高度。
- **提醒只有一种形态**：重置卡到期在 24 小时内就显示紧凑倒计时（`21h`）；`availableCount` 为 0 或没有卡就不显示这一块。旧实现里「绝对时刻行 + 倒计时行」的两行提醒已被设计稿的区块取代。
- **优先有效信息**：四个版式的额度行都取自同一个账号（`focusedProvider`），不混搭不同 Provider 的窗口；5 小时（上游 `session` + `windowMinutes: 300`）与 7 天（`weekly`）优先，缺一个就只画有的那个环，不编窗口。
- **圆环只表达剩余**：Glance 使用原生 Canvas 绘制透明 Bitmap，通过 Image 显示；未知值显示破折号，0% 空环、100% 完整环，金额窗口保持金额，不编造比例。端点用 `Paint.Cap.ROUND`，代价是 0% 也会点出一个圆点，所以扫过角小于 1.5° 时整段不画。环心文字**不画进位图**：位图上的字不跟随系统字体缩放，也不进无障碍树。
- **字体与边框**：单账号用通用 SansSerif，百分号缩小；未打包稿子中的自定义字体。虚线边框用嵌套 Box 近似实线，字距沿用平台默认。
- **非透明档画不透明底板**：跟随系统／固定浅／固定深均不透壁纸；两种显式透明档不画主底板，文字极性由用户选择。
- **`previewLayout` 必须自己给**。Glance 只提供 `glance_default_loading_layout`（一个 `ProgressBar`），把它当预览就是选择器里一个永远转圈的圈；`initialLayout` 仍须指向它，因为那是首次真实更新之前 Launcher 显示的东西。已加 `res/layout/widget_preview.xml` 与 `widget_preview_multi.xml` 静态预览。
- **避免吞掉组合异常**：缓存读取失败使用空快照，组合内容不再用 `runCatching` 捕获 Compose 的控制流异常；圆环输入与 Bitmap 尺寸均作边界处理。
- 平台没有 `android:widgetBackground` 这个 attr（试过，`aapt2` 报 attr not found），Launcher 侧的底框无法从 App 主题改；底板只保证 App 自己这一层画得出来。
- **没有**为了刷新新增任何 Hub 端的定时任务或脚本。

### 刷新频率：不是 App 在轮询

采集端在线时，**Agent 每约 2 秒上报一次**，Hub 每次都推一帧完整 `stats`。实测 30 秒 14 帧，而额度数字只真正变了 1 次 —— 差别在于 token 计数每次都动，额度百分比很少动。

所以 App 做了两件事（都不是"降低数据新鲜度"，是"不重复劳动"）：

| 优化 | 行为 | 效果 |
|---|---|---|
| **重绘合并** | 额度指纹没变就不重绘，但至少每 10 秒放行一次 | 14 次/30 秒 → 额度变化时立刻刷 + 10 秒节流 |
| **小组件缓存节流** | 快照最多每 60 秒写一次 DataStore | 从约 1700 次/小时降到 60 次/小时 |

判定"额度变了"用 `HubStats.contentFingerprint()`：包含 provider 身份、状态、stale、每个窗口的百分比/金额/重置时间，**不含 token 计数**——因为 token 每次上报都变，把它算进去等于没优化。

### "数据更新：刚刚" 这个显示曾经是假的

`stats.updatedAt` 是 Hub **生成这次响应的时刻**（`aggregateDevices()` 里就是 `new Date()`），每读一次就刷新一次。所以哪怕采集器停了半天，只要 App 打开读一次，照样显示"刚刚"。

现在顶部那个时间用的是 `HubStats.dataTimestamp`：取 `limits.updatedAt` 与各设备 `updatedAt` 里**最新的一个**，只有真有东西上报过才会前进。采集器停掉时它会正常显示"2小时前"，同时卡片上出现"已过期"角标。

`SummaryCard` 里的"数据时间"同样已改。

### 单元测试覆盖范围

纯 JVM，共 **276 个用例全部通过**，分 23 个测试类（2026-10-08 实测，0 失败／错误／跳过）。连接恢复使用模拟传输和虚拟时钟；不代表真实手机网络验收。

| 测试文件 | 用例 | 覆盖点 |
|---|---|---|
| `alerts/QuotaAlertEvaluatorTest` | 18 | 既有解析、契约、格式或告警回归 |
| `core/TimeFormatTest` | 13 | 既有解析、契约、格式或告警回归 |
| `data/BackoffTest` | 7 | 既有解析、契约、格式或告警回归 |
| `data/FeedConnectionPolicyTest` | 6 | 短暂重连、长期断线、轮询保持与认证终态 |
| `data/HubRepositoryRecoveryTest` | 9 | 模拟数据流完整恢复、轮询接手、组件重绘节流、取消旧请求 |
| `data/WidgetSnapshotPolicyTest` | 8 | 后台旧请求拒绝、凭据替换、Hub 返回原地址与共享锁竞争 |
| `data/HubStreamRecoveryTest` | 7 | 解析错误分类、HTTP/2 INTERNAL_ERROR 检测与 HTTP/1.1 回退 |
| `data/model/CodexResetCreditsTest` | 7 | 既有解析、契约、格式或告警回归 |
| `data/model/FreshnessTest` | 11 | 既有解析、契约、格式或告警回归 |
| `data/model/HubPayloadContractTest` | 10 | 既有解析、契约、格式或告警回归 |
| `data/model/LiveHubFrameTest` | 3 | 既有解析、契约、格式或告警回归 |
| `data/model/ProviderCatalogTest` | 15 | 既有解析、契约、格式或告警回归 |
| `data/model/ProviderContentTest` | 8 | 既有解析、契约、格式或告警回归 |
| `data/model/StatsParserTest` | 39 | 既有解析、契约、格式或告警回归 |
| `data/model/StreamFrameTest` | 5 | 既有解析、契约、格式或告警回归 |
| `data/model/WidgetSnapshotTest` | 11 | 既有解析、契约、格式或告警回归 |
| `ui/ConnectionPresentationTest` | 6 | 有数据重连不显示错误横幅、暂停与真实错误提示 |
| `ui/FeedLifecyclePolicyTest` | 7 | 前后台、自动刷新与凭据替换启停 |
| `ui/FormatTest` | 23 | 剩余百分比、金额、周期标签、重置与到期文案 |
| `widget/WidgetDesignTest` | 16 | 版式分档与真实一格尺寸、缩放系数、身份色与 20% 转红、窗口文案、重置卡三态 |
| `widget/WidgetPresentationTest` | 12 | 窗口优先级、圆环绘制指令与标签、重置与到期语义 |
| `widget/WidgetSelectionTest` | 4 | 更新时间文案与三种空状态 |
| `widget/WidgetSurfaceTest` | 8 | 底板在两极系统主题下都存在、透明只去底、固定外观底与文字同极、深色档取设计稿配色、未知存储值退回默认 |

共享 fixture 在 `TestFixtures.kt`；两份真实响应存在 `app/src/test/resources/fixtures/`：

| fixture | 来源 | 用途 |
|---|---|---|
| `hub-stats-real.json` | 上游 Node Hub 的 `GET /api/stats` 原文 | `HubPayloadContractTest` |
| `hub-stream-frame-real.json` | 同一次连接的 `snapshot` 帧，**已裁剪并脱敏**（邮箱与 accountKey 换成占位符） | `StreamFrameTest` / `LiveHubFrameTest` |

```bash
cd android-client && ./gradlew :app:testDebugUnitTest
```

按断言类型分：

| 覆盖点 | 断言 |
|---|---|
| stats JSON 解析 | 真实结构 fixture、多余字段容忍 |
| 容错 | Provider 缺字段、单行损坏不影响其他行、未知字段、未知窗口 `kind` 被丢弃 |
| 未知 Provider | 当前 Hub 会先丢掉未知 id，App 侧仍能渲染（为将来更新的 Hub 准备） |
| reset 时间 | `Z`、带小数秒、带 `+08:00`、垃圾值 → `null` 但保留 `resetsAtRaw` |
| 百分比边界 | clamp 到 0..100、字符串 `"78%"`、`used/limit` 推导、`limit = 0` 不推导 |
| 窗口语义 | `credits` 金额窗口、`showMeter: false`、`additional: true`、`boundaryKind` |
| **SSE 帧解包** | 帧比 body 多一层 `stats`；不解包会静默返回 0 个 provider（闪烁 bug 的根因） |
| **内容过滤** | Agent 会上报整个 provider 目录，`notConfigured` 行不该占卡片 |
| freshness | `applyFreshness` 只合并时间戳，保留 limits.providers 与设备明细 |
| `/api/health` | `hubBuild` 缺失（旧 Hub）与畸形两种情况 |
| API 错误 | HTML 错误页 → `StatsParseException`，可被分类成"数据格式不兼容" |

运行：`./gradlew :app:testDebugUnitTest`

### 对着真实 Hub 验证过

不是只靠手写 fixture。构建时用**上游自己的 Node Hub** 起了一个临时实例（`node src/hub/server.js`，数据文件指向 `/tmp`，未在 `upstream-token-monitor/` 里留下任何东西），灌入一份设备上报，然后核对了 App 依赖的每一条行为。

| 验证项 | 方法 | 结论 |
|---|---|---|
| `GET /api/health` 免认证 | 不带 `Authorization` 请求 | 返回 200 与完整 `hubBuild`，与 `server.js` 一致 |
| `GET /api/stats` 需要认证 | 不带 / 带错 secret | 两种情况都是 401，与 `isAuthorized()` 一致 |
| 聚合响应结构 | 抓取真实 body 存成 fixture | 见下 |
| `Authorization: Bearer` | 用真实 secret 请求 | 200 |
| SSE 首帧 | 连 `/api/stats/stream` | 首帧是完整 `snapshot`，字段为 `{type, reason, stats, at}` |
| SSE 数据帧 | 触发一次 ingest | `stats` 帧，字段同上 |
| `x-token-monitor-stream: 2` | 重复上报同一份 payload | 返回 `freshness` 帧，结构为 `{type, reason, stats:{updatedAt, staleAfterMs, limits:{updatedAt}, devices:[…]}, at}` |

真实响应纠正了四处**只看文档会想错**的地方，已全部落成断言（`HubPayloadContractTest`，10 个用例）：

1. **`limits.refreshMs` 在聚合结果里不存在。** `aggregateLimits()` 只返回 `{updatedAt, providers}`，只有设备上报体里才有 `refreshMs`。解析器不能要求它存在。
2. **`credits` 窗口确实是"只有金额、没有百分比"。** 真实值是 `usedPercent: null`、`remainingPercent: null`、`showMeter: false`、`remaining: 42.5`、`currency: "CNY"`。所以 App 对这类窗口显示 `¥42.50` 且**不画进度条**。
3. **未知 provider id 根本活不到客户端。** `normalizeProviderId()` 用 `VALID_PROVIDERS` 白名单过滤，测试里塞的 `somefutureprovider` 被 Hub 直接丢掉了。App 对未知 id 的容忍是为了**将来更新的 Hub**，不是对当前这个 Hub。
4. **`remainingPercent` 保留三位小数**，`= 100 - usedPercent`（实测 `usedPercent: 39.123` → `remainingPercent: 60.877`）。

> 说明：上述初次协议验证（2026-09-30）使用**临时本地 Hub + 临时 secret**；2026-10-03 的 Codex 重置卡验证另外读取了本机实际运行的 Hub。App 不内置 secret；手机端仍需填写自己的 Hub URL 与 Secret 后用"测试连接"确认。

---

## 尚未实现功能

| 功能 | 状态 | 为什么 |
|---|---|---|
| 通知（低额度、重置临近、Hub 离线） | **刻意不做** | 已定义 `QuotaAlertSink` 接口和纯逻辑 `QuotaAlertEvaluator`（低额度 ≤20%、重置临近 ≤10 分钟、Hub 离线），但 sink 是空实现。通知必然要引入后台调度与权限管理，不为了未来可能用到的功能现在就背上这个复杂度。以后要加，只需要提供一个真正的 sink，不用改数据通路 |
| 轮询通知的后台任务 | **刻意不做** | 同上 |
| Provider 筛选 / 搜索 / 排序 | 不做 | Hub 返回什么就显示什么。排序是展示层的主观判断，会掩盖"Hub 认为的优先级" |
| 单 Provider 下钻详情页 | 不做 | 数据都在 Hub 的 `/api/stats` 里，App 已经是全量读取，没有更深的东西 |
| 账号切换 / 多 Hub 配置 | 不做 | App 只知道一个 Hub URL 和一个 Secret。多 Hub 就是多套凭据，没需求 |
| 深色模式适配 | 已实现，跟随系统 | 显式设置中性深浅色的背景、表面与强调色；额度采用绿／黄／红 |
| release 签名与发布 | 未配置 | 只出 debug 包。debug 包 applicationId 带 `.debug`，不与正式包冲突 |
| iOS 客户端 | 不做 | 这个目录只放 Android |
| 离线缓存历史曲线 | 不做 | App 是 viewer，缓存的统计数据没有来源可信度问题；需要历史请看 Hub 侧 |
| instrumented test（真机 UI 测试） | 未做 | 只跑了纯 JVM 单元测试。Compose 界面没有在真机或模拟器上跑过，首次启动仍建议人工确认一次 |
| release 构建验证 | 未做 | 只跑过 `assembleDebug` |

---

## 已知问题与限制

1. **HTTP/SSE 协议层已对真实 Hub 验证过，但手机端到真机没跑过**。协议行为是对着上游真实 Node Hub 实例核对过的（见上一节）；用户已提供旧版 App 与 ColorOS 小组件截图；本轮没有进行新版本的真机或模拟器渲染验证，安装后需确认圆环、缩放与连接恢复。
2. **SSE 依赖代理不缓冲**。中间有反代时若缓冲 `text/event-stream`，App 会退化成轮询模式（状态条显示"轮询中"），功能正常但不再是实时，且每次 ingest 都多一次请求。
3. **后台小组件刷新受 Android 调度限制**。`updatePeriodMillis` 下限为 30 分钟，WorkManager 周期下限为 15 分钟；二者独立触发，系统省电策略可能延迟，不能保证最长陈旧时间。小组件上的 `更新 HH:mm` 用来判断新鲜度；**1×1 紧凑布局放不下这个时间戳**，那一格的新鲜度只能靠打开 App 确认。
4. **SSE 只在前台**。切到后台就断开。App 长时间不在前台时，数据靠 WorkManager 15 分钟一次的单次 `GET /api/stats` 更新小组件。
5. **HTTP 下 secret 是明文过网络的**。`Authorization: Bearer` 在明文传输下等于裸奔。App 会在 UI 上提示，但这是使用者的选择。
6. **凭据轮换要手动**。Hub 换了 `TOKEN_MONITOR_SECRET` 后 App 端要手动改，否则会持续报"Secret 错误"。
7. **Keystore key 换机即失效**。不备份是有意的（`allowBackup=false`），但代价是换机必须重新填 Secret。
8. **上游改 wire 格式**。App 会静默忽略未知字段、丢弃未知 `kind`，不会崩，但可能少显示新字段。上游升级后需要重新核对 `StatsParser`。
9. **`ProviderCatalog` 只做装饰**。上游新增 provider id 时 App 显示原 id，不影响数据读取。
10. **`limits.refreshMs` 在聚合结果里没有**。这是上游 `aggregateLimits()` 的现状（只返回 `updatedAt` 与 `providers`），App 因此拿不到"建议刷新间隔"，不影响显示。
11. **小组件新版式未在真机验收**。尺寸与空间预算已做 JVM 回归；Launcher 尺寸、字体实际度量、排版与圆角效果仍需用户手机验收。
12. **透明档对比度需用户验收**。非透明底板不透壁纸；透明黑字／白字的极性由用户选择，仍需核对壁纸对比度。
13. **紧凑布局未在真机核对**。TINY 根据 fontScale 预留角标与底行、缩环并降级环心次级文字；预算是保守估算，不替代 RemoteViews 实际渲染。

---

## 安全说明

| 项 | 做法 |
|---|---|
| Secret 存储 | Android Keystore AES-256-GCM，密钥不可导出；每次写入新 12 字节 IV；密文在 app 沙箱 DataStore |
| Secret 传输 | `Authorization: Bearer`，**绝不**放 URL query |
| Secret 生命周期 | 不写死源码、不打进 APK、不写日志、不提交 Git |
| 备份 | `android:allowBackup="false"` + `data_extraction_rules.xml` 全域排除。备份会把不可解密的密文带到别的机器，本来也没用 |
| 小组件快照 | 存在 app 沙箱内 DataStore，只有 Hub 已聚合的公开额度数据，无凭据 |
| 权限 | 本 App 声明 `INTERNET` + `ACCESS_NETWORK_STATE`；`WAKE_LOCK`、`RECEIVE_BOOT_COMPLETED`、动态接收器权限由 WorkManager / AndroidX 合并注入（共 5 个，逐条见上文「APK 在哪里」） |
| TLS | `network_security_config.xml` 只放开明文流量本身，信任锚只有系统 CA，无自定义 TrustManager、无用户 CA、无 hostname verifier 覆写 |
| 明文警告 | `http://` 时设置页和 Dashboard 都显示"当前连接未加密，仅建议用于可信局域网或 Tailscale 网络" |
| 凭据范围 | App 只持有 Hub Secret。**不**接触 Claude token、sessionKey、OpenAI OAuth token、Codex token、Kimi token、Provider API Key |

Hub Secret 的实际值属于机密，不要写进本文档或任何仓库文件；只记录它的存放位置和检查命令。

---

## 开发原则

这个项目遵守的取舍：

1. **先读官方源码，不猜 API**。端点、字段、状态闭集、帧类型全部来自 `upstream-token-monitor/src/hub/server.js`、`worker/src/index.js`、`src/shared/limits/core.js`。README 里每个字段名都能指到上游文件的一行。
2. **不建中间层**。能 `App → Hub` 就绝不做成 `App → 自制服务 → Hub`。不为兼容任何东西额外开端口、开进程、开数据库。
3. **不改 upstream**。`upstream-token-monitor/` 是只读参考。要改行为，改自己的 App，不改上游、不打 patch、不 fork。
4. **对未知字段容忍**。JSON 解析走防御式取值器 + 宽松 `Json`，未知字段忽略、未知 `kind` 丢弃、缺字段用默认值。上游加字段不会让 App 崩。
5. **一行坏数据不毁整个 Dashboard**。单个 provider 解析失败只标记那一行（`parseIssue`），其余照常显示；`showMeter: false` 或 `credits` 窗口只是不画进度条，不报错。
6. **不猜、不编造**。没有真实 Hub 就不假装验证过；不编造 secret；测试没跑过就写没跑过。
7. **结果分类要能指导下一步动作**。"Secret 错误"和"Hub 没启动"导向完全相反的操作，所以它们必须是两个不同的结果，不能合并成"发生错误"。
8. **少服务、少端口、少凭据、少依赖**。方案明显比问题本身复杂时重新评估，不继续实现。
9. **该不做就不做**。通知、筛选、下钻、多 Hub、release 签名都是明确判断后留空，不是遗漏。
