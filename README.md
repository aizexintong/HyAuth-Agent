# HyAuth-Agent

[![License: GPL v3](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg)](LICENSE)
[![Java 25](https://img.shields.io/badge/Java-25-orange.svg)](#二运行环境)
[![Minecraft 原版 server.jar](https://img.shields.io/badge/Minecraft-%E5%8E%9F%E7%89%88%20server.jar-green.svg)](#二运行环境)

**开源协议：[GPL-3.0-or-later](LICENSE)（带传染性的 copyleft）** —— 可自由使用、修改、再分发（含商用）；
但**对外分发**本项目或其衍生作品时，必须同样以 GPL-3.0-or-later 开源并提供完整源码，改过的版本要标注"已修改"。
完整文本见 [`LICENSE`](LICENSE)，说明与致谢见[第十一章](#十一许可证与致谢)。

**Hybrid Authentication Java Agent** —— 跑在 Minecraft **原版服务端**上的 JVM 内存切面代理（`-javaagent` +
Java Instrumentation + Byte Buddy）。一个 jar 干三件事，全部是纯服务端能力，**不用装插件、不用装 Mod、不改协议**：

| 能力 | 一句话 |
| --- | --- |
| **三合一登录** | 官方正版号 + LittleSkin 等外置验证号 + 管理员指定 UUID 的离线号，**同一台原版服务端同服游玩** |
| **区块卡顿勘探** | 一条命令列出"哪个区块在吃 tick"的**坐标范围**，聊天里点一下 tp 到现场 |
| **空置域挖掘** | 对角选两点 → 预演 → 确认，服务端用原版 `/fill` 把区域挖成空置域（命令版"世吞"），可随时中止 |

> 运行环境：**原版** `server.jar`（Mojang 官方 Bundler 形态）+ **Java 25**。不需要 Fabric / Forge / Paper，
> 客户端也不需要任何改动（离线名单玩家只需客户端用离线登录）。

---

## 命令汇总表（全部命令，一页看完）

命令根默认 `/hy`（可配 `commands.roots`，默认 `hy` / `ha` / `hyauth`；`/lag` 是勘探组快捷方式）。
**批量＝名字之间用空格隔开**。权限：名单与勘探需要原版权限等级 ≥ `commands.op_level`（默认 2）；
空置域挖掘单独更高（`clear.op_level`，默认 3）；控制台与 RCON 天然是 4。

> **命令只对 OP 与后台（控制台 / RCON）可见可用**：Agent 启动时把命令节点注册进真实的 Brigadier 命令树，
> 并带上 `requires(权限 ≥ op_level)` —— 原版给每个玩家下发的命令树按这个过滤，所以普通玩家的补全里
> 不会出现这些命令，客户端也不会把它们发出去。命令根与一级子命令都注册了，**Tab 补全可用**。

### 1. 名单管理（改完自动热重载，不用重启）

| 命令 | 作用 |
| --- | --- |
| `/hy add ex <名字> [名字…]` | 加入 **LittleSkin 外置**名单（玩家用外置账号登录）；批量：`/hy add ex A B C` |
| `/hy add off <名字> [名字…]` | 加入**离线**名单：**先查户口**——有历史身份（`usercache.json` / 存档 / 原版离线算法）就**沿用**，没有才发新 UUIDv7 并查重 |
| `/hy off <名字> [名字…] force` | 给已有玩家**换一个全新身份**（新 UUIDv7）。老存档的背包/成就仍挂在旧 UUID 下，不会跟过来 |
| `/hy del <名字> [名字…]` | **按名字删除**（名字唯一，不用管在外置还是离线名单） |
| `/hy list` ／ `/hy ls` | 查看两个名单（离线名单显示 UUID、版本与生成时间） |
| `/hy whois <名字>` ／ `/hy id` | 查户口：这个名字在服务端上的历史 UUID 分别在哪 |
| `/hy reload` | 重读配置并同步常驻采样开关 |
| `/hy status` | 版本、命令根与权限门槛、配置版本、名单人数、每个切点的能力探测 |

> 为什么没有"手填 UUID"的命令：**沿用是自动的**（查户口查得到），真要换人用 `force`，
> 这两条已经覆盖全部情况，不需要管理员手抄 UUID。

### 2. 区块卡顿勘探（纯服务端，无需客户端 Mod）

| 命令 | 作用 |
| --- | --- |
| `/lag` | **直接出报告**（最常用；等价于 `/hy lag`） |
| `/lag <秒>` | 采样 N 秒后自动出报告（等价于 `/hy lag scan N`，默认 30 秒） |
| `/lag top [N]` | 单区块耗时榜（默认 10 条，按单次峰值排序） |
| `/lag tp <序号>` | 传送到报告里第 N 组异常范围的中心（聊天里点报告那一行也行） |
| `/lag tp <x> <z> [玩家]` | 传送到指定方块坐标；控制台可带玩家名 |
| `/lag here` | 看**脚下这块**的耗时分项（到场勘查时用） |
| `/lag on` ／ `/lag off` | 常驻采样开 / 关（默认开，开销极低） |
| `/lag stop` | 提前结束按需扫描并立刻出报告 |
| `/lag clear` | 清空累计数据 |
| `/lag status` | 采样状态 + 每个切点"能不能挂"的能力探测 |

### 3. 空置域挖掘（命令版"世吞"）

| 命令 | 作用 |
| --- | --- |
| `/hy clear <x1> <z1> <x2> <z2>` | **最常用**：对角两点直接建选区，**选完自动预演**（例：`/hy clear -576 -66 -193 -448`） |
| `/hy clear <x2> <z2>` | 以**你当前所站**的方块为角点 1，参数为角点 2 |
| `/hy clear <边长>` | 以**你为中心**的正方形（例：`/hy clear 100` = 100×100） |
| `/hy clear pos1` ／ `pos2 [x z]` | 一个角一个角地选（不带坐标＝取当前所站位置） |
| `/hy clear dim <维度>` | 控制台 / RCON 指定要挖的维度（`the_nether`、`the_end`，可写简写） |
| `/hy clear preview` | 只预演（体积 / 批次数 / 预计耗时，**不动世界**） |
| `/hy clear go` | 开始挖（= `/hy clear start confirm`；预演里也有可点击的「确认开始」） |
| `/hy clear status` ／ `stop` ／ `reset` | 进度 / 立即中止（已挖的不回滚）/ 清空选区 |

---

## 目录

* [命令汇总表（全部命令，一页看完）](#命令汇总表全部命令一页看完)
* [一、它到底做了什么](#一它到底做了什么)
* [二、运行环境](#二运行环境)
* [三、编译](#三编译)
* [四、部署](#四部署)
* [五、配置文件（含版本校对与自动补齐）](#五配置文件含版本校对与自动补齐)
* [六、三类玩家怎么配](#六三类玩家怎么配)
* [七、区块卡顿勘探](#七区块卡顿勘探)
* [八、管理员命令](#八管理员命令)
* [九、空置域挖掘](#九空置域挖掘)
* [十、工作原理与硬性约束](#十工作原理与硬性约束)
* [十一、许可证与致谢](#十一许可证与致谢)
* [十二、自检（可选，但建议跑）](#十二自检可选但建议跑)
* [十三、排错速查](#十三排错速查)
* [十四、已知限制与后续可做](#十四已知限制与后续可做)
* [附录 A：日志速查](#附录-a日志速查)
* [附录 B：文件清单](#附录-b文件清单)

---

## 一、它到底做了什么

HyAuth-Agent 只接管一件事：**服务端"这个玩家到底是谁"的那一次判断**。

原版服务端在玩家登录时会调用 `SessionService#hasJoinedServer(username, serverId, ip)` 向 Mojang 会话服务器确认身份。
本 Agent 在内存里把这次调用接过来，按**三级优先级**决定：

| 优先级 | 名单 | 行为 | 外部请求 |
| --- | --- | --- | --- |
| 1 | `offline_players`（名字 + UUID） | 直接以指定 UUID / 名字放行 | **无** |
| 2 | `littleskin_players` | 向 LittleSkin 发 `hasJoined` 并做 RSA 验签，用返回的 UUID / 名称 / 皮肤属性放行 | 只打 LittleSkin |
| 3 | 其它玩家 | **立即放行原方法**（不跳过），原版 Mojang 校验照常执行 | 只打 Mojang |

配套能力：

* **热重载**：改完 `littleskin_config.json` 保存即生效（约 0.5 s），也可以直接用命令改名单。
* **失败关闭**：名单内玩家校验失败就断开，**绝不降级回退 Mojang**，避免同名正版账号被冒用。
* **属性强制验签**：皮肤/披风属性必须通过验证服务器公钥的 `SHA1withRSA` 验签才会采用。
* **宽版本兼容**：一份字节码同时适配 Authlib 3.x / 6.x / 10.x 三代 API。
* **原版 Bundler 适配**：能跑在官方 `server.jar` 上（1.18+ 的 Bundler 形态），无需解包服务端。
* **命令注册 + 入口拦截双保险**：启动时把 `/hy`、`/lag`（以及一级子命令）注册进真实 Brigadier 命令树
  —— 只注册我们自己的名字，因此不可能与原版或插件命令撞名，同时**有 Tab 补全**、聊天里点一下能直接执行；
  实际执行仍由分发入口的切面接管（注册的节点带 `requires`，只对 OP / 控制台 / RCON 可见）。

### 已验证 / 未验证

| 项目 | 状态 |
| --- | --- |
| MC 26.3 原版服务端开服 + 切面命中并改写 `MinecraftServicesSessionService` | ✅ 实测 |
| Bundler 类加载器隔离下切面仍挂上（`verify/loaderiso.ps1`，6/6 断言） | ✅ 实测 |
| Authlib 10.0.77 / 6.0.54 / 3.11.49 三代（各 20/20 断言） | ✅ 实测 |
| 真实客户端登录：正版号（零 `[HyAuth]` 输出）、离线名单玩家、LittleSkin 外置号 | ✅ 实测 |
| 外置账号聊天（皮肤站公钥验签通过）+ 逐接收者分流（`verify/chat.ps1`，11/11） | ✅ 实测 / 离线回归 |
| 区块卡顿勘探 + 管理员命令 + 空置域挖掘（`verify/lag.ps1`，93 项断言 + 替身与 26.3 真实形状一致） | ✅ 离线回归（真实切面 + 26.x 形状替身） |
| 26.3 真实 server.jar 上的切点签名（`verify/realjar.ps1`，20 项） | ✅ 实测（对你的 26.3 官方 server.jar 逐条核对） |
| 勘探 / 挖掘在**真实存档**上的表现 | ⚠️ 待你在测试存档上验一次 |
| MC 1.20.x 及更早的离线握手改写 | ⚠️ 未实现（这些版本类名混淆，需要额外映射；此时离线名单退化为"仅服务端放行"） |

> 所有"✅"都是本机或用户真实环境实测，不是推测；脚本化的那几项就是 `verify/` 下的四套自检。

---

## 二、运行环境

| 项目 | 要求 |
| --- | --- |
| 服务端 | Minecraft **原版** `server.jar`（Mojang 官方；本文以 **26.3** 为准） |
| Java（运行） | **Java 25**（26.x 服务端要求；Agent 自身字节码目标是 Java 8） |
| Java（编译） | 任意能编译 `--release 8` 的 JDK（本机用 Microsoft OpenJDK 25.0.1 验证） |
| 构建 | Apache Maven 3.6+（本机 3.9.9；项目内自带一份，见 §3.3） |
| 网络（运行） | 能访问 `https://littleskin.cn`（或你自建/镜像的 `api_root`） |
| 依赖 | Byte Buddy 1.17.5（已 shade 进 jar）、gson 2.10.1；编译期 `com.mojang:authlib` 三代 |

> **别把 Byte Buddy 降到 1.14.12**：它在 Java 25 上会直接抛
> `Java 25 (69) is not supported by the current version of Byte Buddy…`，切面完全失效。

---

## 三、编译

### 3.1 标准方式

```bat
:: Windows
build.bat
:: 或者
mvn clean package
```

```bash
# Linux / macOS（自动找 Maven、自动推导 JAVA_HOME）
chmod +x build.sh && ./build.sh
```

产物：

```text
target/HyAuth-Agent-1.0.0.jar            ← 拿去用（已内置 Byte Buddy）
target/original-HyAuth-Agent-1.0.0.jar   ← 未打包依赖的原始 jar，不要用
```

每次构建的大小与 SHA256 记录在项目根目录 `BUILD.txt` 里（**发行页上的 SHA256 才是官方值**；本机自构建的哈希
因为时间戳不同必然不一样，不代表文件损坏）。首次构建需要联网下依赖（约 2~12 分钟），之后增量约 5 秒，
完全离线可用 `mvn -o clean package`。

### 3.2 GitHub Actions：自动构建 + 自动发行

| 工作流 | 触发 | 做什么 |
| --- | --- | --- |
| `.github/workflows/ci.yml` | push / PR | Java 25 上构建 + 跑**四套自检** |
| `.github/workflows/release.yml` | push 到 `main`（自动修订号 +1）/ 推 `v*` 标签 / 手动 | 算版本号 → 构建 + 四套自检 → 打标签并创建 Release（附 jar、带版本号的副本、`BUILD.txt`） |

所以平时什么都不用做：往 `main` 推一次代码，就会自动发布一个修订号自增的版本。CI 与 Release 都用 **Java 25**，
和你的服务端运行时一致。

### 3.3 本机没有 Maven

项目里带了一份 Maven 3.9.9（`tools/apache-maven-3.9.9/`，不提交进仓库），`build.bat` / `build.sh` 会自动使用：

```bat
build.bat                 :: Windows
```
```bash
chmod +x build.sh && ./build.sh    # Linux / macOS
```

---

## 四、部署

### 4.1 目录布局

```text
服务端根目录/
├── server.jar                 ← 原版服务端
├── HyAuth-Agent-1.0.0.jar     ← 本 Agent
├── start.bat / start.sh       ← 启动脚本（含必须的 --add-opens）
├── littleskin_config.json     ← 首次启动自动生成
├── whitelist.json / ops.json / server.properties …
└── world/
```

### 4.2 启动参数（关键就三行）

```bat
"%JAVA_PATH%" ^
  -Xms4G -Xmx8G ^
  --add-opens java.base/java.net=ALL-UNNAMED ^
  --add-opens java.base/java.lang=ALL-UNNAMED ^
  --add-opens java.base/java.util=ALL-UNNAMED ^
  -javaagent:HyAuth-Agent-1.0.0.jar ^
  -jar server.jar nogui
```

| 参数 | 为什么必须 |
| --- | --- |
| `-javaagent:HyAuth-Agent-1.0.0.jar` | 挂载切面本体 |
| `--add-opens java.base/java.lang=ALL-UNNAMED` | 原版 `server.jar` 是 Bundler，`Agent` 需要反射调用 `ClassLoader#defineClass` 把辅助类注入服务端加载器；**缺它登录会失败** |
| `--add-opens java.base/java.net` / `java.util` | 突破现代 JVM 强封装（外置校验与配置读取用得到） |

Linux / macOS 用 `start.sh`，参数完全一致，另外支持环境变量覆盖：

```bash
MIN_RAM=1G MAX_RAM=3G ./start.sh                     # 小内存机器
JAVA_PATH=/usr/lib/jvm/java-25-openjdk/bin/java ./start.sh
```

> 一关 SSH 服务端就退出？放进 `screen` / `tmux` / `nohup`，或写成 systemd 服务。

### 4.3 首次启动会发生什么

1. `[HyAuth] 已把 N 个 Agent 辅助类注入服务端类加载器` —— 注入成功；
2. `[HyAuth] 已自动创建默认配置文件: littleskin_config.json` —— 配置生成好了；
3. `[HyAuth] 命中目标类 …` + `已改写目标类字节码` —— 切面真的挂上了；
4. `Done (x.xxx s)!` —— 服务端就绪。

四条都在，就可以开始配名单了（见下一节）。

---

## 五、配置文件（含版本校对与自动补齐）

文件名固定 `littleskin_config.json`，位置是**服务端工作目录**（`server.jar` 所在目录；用启动脚本时就是脚本目录）。
Agent 只在文件**不存在**时创建它，之后**只读不改**——除了下面这个例外。

### 5.1 配置版本与自动补齐（升级友好）

配置文件带一个 `config_version`（当前 **v5**）。每次启动 / 热重载都会校对：

| 情况 | 行为 |
| --- | --- |
| 文件版本 **<** 插件支持版本 | **自动补齐**缺失的配置项（按默认值写回文件）并盖上当前版本号；**已有的账号信息与已有值一律原样保留**，未知字段也不删 |
| 文件版本 **=** 插件支持版本 | 不写文件；只有发现某项被手工删掉时才补回来 |
| 文件版本 **>** 插件支持版本（配置来自更新的插件） | **只读取、不写回**，并在日志里提示，避免把新字段抹掉 |

日志长这样：

```text
[HyAuth] 配置版本 v0 → v4，已自动补齐 37 个新版新增项（填的是默认值，可直接在文件里改）：[public_key, …, clear.break_bedrock]
[HyAuth] 原有的账号信息（littleskin_players / offline_players）与已有配置值均原样保留。
```

配置版本对应关系：

| 版本 | 新增内容 |
| --- | --- |
| v1 | 三合一鉴权（`littleskin_players` / `offline_players` / `api_root` …） |
| v2 | 聊天链路开关（`relax_chat_keys` / `bypass_signed_commands` / `offline_chat_exempt` …） |
| v3 | 区块卡顿勘探 + 管理员命令（`chunk_lag.*` / `commands.*`） |
| v4 | 空置域挖掘（`clear.*`，含基岩开关） |
| v5 | 额外信任的管理员名单（`commands.extra_admins`） |

`/hy status` 会显示"配置文件版本 vN（本插件支持 vM）"。

### 5.2 完整示例

```json
{
  "config_version": 4,
  "description": "HyAuth-Agent 配置：littleskin_players = 外置验证玩家；offline_players = 管理员指定 UUID 的离线玩家",
  "littleskin_players": [ "Xiao_Ming", "Player_Demo" ],
  "offline_players": [
    { "name": "ZhangSan_LS", "uuid": "11112222-3333-4444-5555-666677778888" },
    { "name": "LiSi", "uuid": "99998888777766665555444433332222" }
  ],
  "api_root": "https://littleskin.cn/api/yggdrasil",
  "public_key": "",
  "connect_timeout_ms": 5000,
  "read_timeout_ms": 5000,
  "relax_chat_keys": true,
  "chat_key_strict": false,
  "bypass_signed_commands": true,
  "offline_chat_exempt": true,
  "debug": false,
  "chunk_lag": {
    "resident": true,
    "ewma_alpha": 0.05,
    "flag_threshold_ms": 1.0,
    "flag_relative_factor": 6.0,
    "scan_default_seconds": 30,
    "report_top": 10,
    "max_clusters": 8,
    "max_tracked_chunks": 20000
  },
  "commands": { "roots": [ "hy", "ha", "hyauth", "lag" ], "op_level": 2, "extra_admins": [] },
  "clear": {
    "op_level": 3,
    "interval_ticks": 4,
    "fills_per_step": 2,
    "load_wait_ticks": 5,
    "kill": "items",
    "min_y": -64,
    "top_y": 63,
    "above_height": 256,
    "trench": true,
    "trench_block": "sand",
    "batch_chunks": 4,
    "max_side": 2048,
    "max_volume": 500000000,
    "announce_percent": 10,
    "break_bedrock": true,
    "bedrock_top_y": -60
  }
}
```

### 5.3 字段说明

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `config_version` | 当前 4 | 配置架构版本，见 §5.1 |
| `littleskin_players` | 示例值 | 走 LittleSkin 外置验证的玩家名，**判定不区分大小写** |
| `offline_players` | `[]` | 离线名单，每项 `{ "name": …, "uuid": … }`（UUID 支持 `8-4-4-4-12` 与 32 位两种写法） |
| `api_root` | `https://littleskin.cn/api/yggdrasil` | Yggdrasil API 根地址，可指向自建皮肤站 |
| `public_key` | 空 | 验签公钥；留空则自动从 `api_root` 的 `signaturePublickey` 获取 |
| `connect_timeout_ms` / `read_timeout_ms` | 5000 | 访问外置验证服务的超时 |
| `relax_chat_keys` | `true` | 聊天密钥桥接（Mojang 验签 → 皮肤站公钥验签），防外置号被 `Invalid signature for profile public key` 踢下线 |
| `chat_key_strict` | `false` | 桥接全部验不过时：放行 / 拒绝 |
| `bypass_signed_commands` | `true` | 让离线名单玩家的指令按"未签名"执行（修 `/say`、`/me`、`/msg` 报红字） |
| `offline_chat_exempt` | `true` | 只对离线名单玩家豁免 `enforce-secure-profile`，正版玩家仍强制校验 |
| `debug` | `false` | 打印 204（未登录）等调试信息 |
| `chunk_lag.resident` | `true` | 常驻采样开关（命令里可随时 `lag on\|off`） |
| `chunk_lag.ewma_alpha` | `0.05` | 近期均值平滑系数（`0.001~1`，越大越跟手） |
| `chunk_lag.flag_threshold_ms` | `1.0` | 判定异常区块的绝对阈值（合计 ms） |
| `chunk_lag.flag_relative_factor` | `6.0` | 相对判据：合计 ≥ 全体中位数 × 该倍数也算异常（自适应机器） |
| `chunk_lag.scan_default_seconds` | `30` | `/hy lag scan` 不带秒数时的默认时长 |
| `chunk_lag.report_top` | `10` | 单区块榜条数 |
| `chunk_lag.max_clusters` | `8` | 报告最多列多少组坐标范围 |
| `chunk_lag.max_tracked_chunks` | `20000` | 常驻窗口最多跟踪多少区块 |
| `commands.roots` | `["hy","ha","hyauth","lag"]` | 命令根（小写字母/数字/下划线，1~16 位；`lag` 自动保留）；**与原版命令同名的会被拒绝** |
| `commands.op_level` | `2` | 名单/勘探命令所需权限等级（0~4） |
| `commands.extra_admins` | `[]` | **额外信任的管理员**（名字或 UUID，不区分大小写）。原版 op 按 UUID 认人，换账号登录（正版名 vs 离线名）会判成 0 级；把那个名字/UUID 写进来即可。判定顺序：extra_admins → `hasPermission(int)`（旧版）→ `permissions()/PermissionSet`（26.x）→ **直接读原版 op 名单（ops.json）** |
| `clear.op_level` | `3` | 挖掘命令所需权限等级（默认更高，因为不可撤销） |
| `clear.interval_ticks` | `4` | 每多少 tick 推进一步（越大越温柔） |
| `clear.fills_per_step` | `2` | 每个节拍最多推进几步 |
| `clear.load_wait_ticks` | `5` | `forceload add` 后等几个 tick 再 fill |
| `clear.kill` | `items` | 区域内实体清理：`none` / `items`（只清掉落物）/ `all` |
| `clear.min_y` / `top_y` / `above_height` | `-64` / `63` / `256` | 挖掘的 y 分层（会按该维度真实建筑高度夹取） |
| `clear.trench` / `trench_block` | `true` / `sand` | 是否挖防爆沟、用什么方块填 |
| `clear.batch_chunks` | `4` | 每批 4×4 区块（64×64 方块） |
| `clear.max_side` / `max_volume` | `2048` / `5e8` | 选区边长 / 单次体积上限，超限拒绝预演 |
| `clear.announce_percent` | `10` | 进度播报间隔 |
| `clear.break_bedrock` / `bedrock_top_y` | `true` / `-60` | **基岩开关**：`true` 连基岩一起挖（底部是虚空）；`false` 保留基岩，从 `bedrock_top_y` 上一格开始清 |

### 5.4 行为边界

* 名单为空 / 配置读不到 → 所有玩家都走原版 Mojang 校验（外置与离线玩家进不来，但**不存在越权风险**）。
* JSON 写坏 → 打印报错并**保留上一次生效的配置**，不会把名单清空。
* `offline_players` 条目缺 `name`、`uuid` 非法 → 跳过该条并告警。
* 手写配置注意：UTF-8 **无 BOM**、不能有注释和多余逗号。

### 5.5 热重载

**编辑文件 → 保存 → 约 0.5 秒生效**（也可以用命令改，命令同样是"改文件 + 立刻重载"）。
Agent 用 `WatchService` 监视配置所在**目录**，所以原地改写、删掉重建、改名覆盖都能感知到：

```text
[HyAuth] 配置重载完成（JSON 配置加载成功），当前 LittleSkin 白名单人数: 3，离线名单人数: 1，API: …
```

---

## 六、三类玩家怎么配

| 玩家类型 | 你要做的 | 玩家要做的 |
| --- | --- | --- |
| **正版** | 什么都不用做（不在任何名单里即可） | 用正版账号登录 |
| **外置（LittleSkin）** | `/hy add ex 名字` 或写进 `littleskin_players` | 在启动器里用皮肤站账号登录 |
| **离线（指定 UUID）** | `/hy add off 名字`（有历史身份会自动沿用；新玩家发 UUIDv7） | 客户端用**离线登录**，名字与配置一致 |

补充说明：

* 跑通"离线名单玩家"还需要服务端 `server.properties` 里 `online-mode=true`（保持正版校验）；
  离线名单玩家的握手会被改写成"无需正版会话"，其余玩家不受影响。
* 离线名单玩家默认豁免 `enforce-secure-profile`（`offline_chat_exempt`），但他们**没有皮肤来源**（预期行为）。
* `white-list` 开着的话，记得把离线玩家的 UUID 也加进 `whitelist.json`（`/hy whois 名字` 能查到该 UUID，
  加完名单后用 `/hy status` 看人数对不对）。
* 想给某个玩家**换一个全新身份**：`/hy off 名字 force`（旧存档的背包/成就仍挂在旧 UUID 下，不会跟过来）。

---

## 七、区块卡顿勘探

**目标**：服务器卡的时候，管理员一条命令就知道"是哪个区块在吃 tick"，并**点一下 tp 到现场**。
不需要客户端 Mod、不发网络包、不改协议。

### 7.1 它量了什么

| 分项 | 挂钩位置 |
| --- | --- |
| 区块 tick | `ServerLevel#tickChunk(LevelChunk,int)` |
| 实体（非乘客 / 乘客） | `ServerLevel#tickNonPassenger(Entity)` / `tickPassenger(Entity,Entity)` |
| 方块实体 | `LevelChunk#tickBlockEntities()`（运行期探测是否已含在区块 tick 里，避免重复计数） |
| 整服 MSPT | `MinecraftServer#tickServer(BooleanSupplier)` |

报告口径：**合计 = 区块 tick + 实体 + 方块实体**（若某版本方块实体已被包含在区块 tick 里，就自动按不重复口径显示）。

### 7.2 30 秒上手

```text
/lag                     直接出报告（最常用）
/lag 30                  采样 30 秒后出报告
/lag top 20              最卡的 20 个单区块
/lag tp 1                传送到报告里第 1 组范围的中心
/lag here                看脚下这块的耗时分项
/lag status              采样状态 + 每个切点能不能挂
```

报告长这样（聊天里那行可以直接点）：

```text
===== HyAuth 区块卡顿勘探 =====
采样 30.02s / 600 tick · 覆盖 1 个维度 412 个区块 · 全服 MSPT 均值 47.15ms（近期 46.80ms，峰值 71.2ms）
区块 tick 耗时分布: 中位 0.412ms · P95 2.881ms · 单次峰值 38.02ms
异常判据: 合计 ≥ 1.00ms 或 ≥ 中位数 6.0 倍 → 命中 3 个区块，合并为 2 组范围
  #1 主世界 x[160..191] z[-48..-17]  6 区块  合计均值 22.41ms 峰值 38.02ms  [区块 3.1 | 实体 18.2(≈96只) | 方块实体 1.1]  点击传送   [命令: /lag lag tp 1]
  #2 主世界 x[-32..-1] z[240..271]   2 区块  合计均值 9.77ms 峰值 12.40ms  [区块 1.2 | 实体 8.4(≈41只) | 方块实体 0.1]  点击传送   [命令: /lag lag tp 2]
```

### 7.3 判定"异常"的两条规则

1. **绝对值**：合计耗时 ≥ `flag_threshold_ms`（默认 1.0 ms）；
2. **相对值**：合计耗时 ≥ 全体区块中位数 × `flag_relative_factor`（默认 6 倍）——这条让不同性能的机器都能用同一套默认值。

相邻的异常区块会**合并成一组坐标范围**，方便直接圈出"问题区域"。报告里不会出现具体玩家名。

### 7.4 两种模式

| 模式 | 说明 |
| --- | --- |
| **常驻**（默认开） | 一直在采样，几乎零开销；随时 `/lag` 立刻有数据。`chunk_lag.resident=false` 或 `/lag off` 关掉 |
| **按需** | `/lag 30` 开始一次限时扫描，到点自动出报告；`/lag stop` 可提前结束 |

### 7.5 传送是怎么做的

不自研落点。Agent 先算好目标方块坐标，**把 `/tp` 原样交给原版执行**（包括区块加载、落点修正），
y 取自 `MOTION_BLOCKING` 高度图，所以不会把人塞进地底。

> 思路来源：**只借鉴了"按区块分项归因"这个想法**（来自 [MsptMap](https://github.com/Drizzle379/MsptMap)，
> 一个 Fabric Mod，把每区块 MSPT 画到 Xaero 地图上）。本项目落地方式完全不同：
> 纯服务端切面 + 命令报告 + 点击传送，不做客户端热力图、不发网络包、未使用其任何代码。

---

## 八、管理员命令

两层配合：

1. **注册**：Agent 在 `Commands` 构造完成时把 `/hy`、`/lag` 与一级子命令注册进真实命令树，
   并加 `requires(权限等级 ≥ commands.op_level)` —— 于是 OP 有 **Tab 补全**、聊天里点一下就能执行，
   而普通玩家下发的命令树里干脆没有这些命令（客户端也不会发出去）；
2. **拦截**：命令真正执行时在分发入口（`performPrefixedCommand` / `performCommand`）被切面接管，
   控制台 / 游戏内 / RCON 一次覆盖；`performCommand(ParseResults,String)` 这条游戏内路径会先从
   ParseResults 里取出真正的 `CommandSourceStack`（否则回复发不到聊天框、权限也判不出来）。

非本命令根一律返回 `false` 交给原版（自检里专门断言 `/tp` 不被吞掉）。

* 完整命令表见[文首](#命令汇总表全部命令一页看完)；
* 权限门槛 `commands.op_level`（默认 2 = OP），挖掘另算 `clear.op_level`（默认 3）；
* 名单类命令都是"读文件 → 改字段 → 原子替换 → 立刻 reload"，**文件始终是唯一真相**，
  手工编辑和命令改名单不会互相覆盖；
* `/hy reload` 重读配置，`/hy status` 打印版本、配置版本、名单人数与切点能力探测。

---

## 九、空置域挖掘

**目标**：小服务器真造世吞（几万格 TNT + 上万实体）会把自己拖死。这个功能让管理员**接受滞空域**：
一条命令把选区挖空、并把边界处理成"不会炸回来"的形态。

### 9.1 挖完能得到什么

* 选区**从地表到上方全部清空**（默认挖到 y=319），内圈一路清到 `min_y`（默认 -64，即连基岩一起挖）；
* 选区**四边有一圈防爆沟**（默认填沙子），避免外面的水/爆炸物灌进来；
* 区域内的掉落物/实体按 `clear.kill` 清理（默认只清掉落物）；
* 全程**服务端节流**执行：每 `interval_ticks` tick 推进 `fills_per_step` 步，单次会话不会把 TPS 砸穿；
* 随时 `/hy clear status` 看进度、`/hy clear stop` 中止（**已挖掉的部分不回滚**）。

### 9.2 怎么用

```text
/hy clear -576 -66 -193 -448     对角两点（x1 z1 x2 z2），选完自动预演 ← 最常用
/hy clear 31 31                  以你当前所站的方块为角点 1
/hy clear 100                    以你为中心的 100×100
/hy clear pos1 / pos2            一个角一个角地选
/hy clear dim the_nether         控制台/RCON 指定维度（pos1/pos2 之前用）
/hy clear preview                只预演，不动世界
/hy clear go                     开始挖（= /hy clear start confirm）
/hy clear status | stop | reset  进度 / 中止 / 清空选区
```

预演会告诉你体积、批次、预计耗时，并给一个**可点击的「确认开始挖掘」**：

```text
===== 空置域挖掘预演（还没有动世界）=====
内圈: x[-576..-193] z[-448..-66]（384 x 383 方块）· 外圈各扩 1 格 · 维度 主世界
阶段 1 外圈上方清空: y 63 .. 319
阶段 2 防爆沟: 四边填 sand（y -63 .. 62）
阶段 3 内圈清空: y -64 .. 62
计划: 1234 步（fill 900 条）· 96 批次 · 预计清理约 41,000,000 方块
节流: 每 4 tick 做 2 步 → 预计约 25 分钟（已计入 forceload 等待，实际取决于机器与磁盘）
实体清理: items（仅限本区域范围）
基岩层: 一起挖掉（break_bedrock=true，底部是虚空 —— 东西会掉下去）
  ▶ 点击确认开始挖掘（不可撤销）   [命令: /hy clear start confirm]
```

### 9.3 三阶段在干什么

| 阶段 | 动作 | 为什么 |
| --- | --- | --- |
| 1 | 外圈上方（`top_y` 以上，含外扩 1 格）填成空气 | 先把"天花板"掀掉，避免后续挖掘时上方方块掉落/遮挡 |
| 2 | 外圈四边从下到上填 `trench_block` | 防爆沟：挡住外面的水与爆炸 |
| 3 | 内圈从 `min_y` 清到 `top_y` | 真正挖空置域（`break_bedrock=false` 时保留基岩层） |

实现上是"内存任务 + tick 驱动"：按批 `forceload add` → 用原版 `/fill`（每条 ≤ 32768 方块）分层清 →
`kill @e[x=…,dx=…]` 只清区域内的实体 → `forceload remove`。不生成数据包、不建红石机器、不产生 TNT 实体。

### 9.4 安全设计（不可撤销的破坏性操作）

* 默认权限门槛 **3**（比其它命令高），且必须 `preview` 看过 + `go` / `start confirm` 才执行；
* 选区边长超过 `max_side`（默认 2048）或体积超过 `max_volume`（默认 5 亿方块）→ 直接拒绝预演；
* y 范围按该维度真实建筑高度夹取；
* 进度每 10% 播报一次，随时可 `stop`；
* **`break_bedrock=true`（默认）会把基岩挖掉、底部变成虚空**：东西掉下去就没了；
  不想动基岩就设 `break_bedrock=false`（下界基岩顶面默认 y=4，主世界/末地按 `bedrock_top_y`）。

### 9.5 想调整效果时改哪里

| 想要的效果 | 改哪个 |
| --- | --- |
| 更温柔 / 更快 | `clear.interval_ticks`（大＝温柔）、`clear.fills_per_step`（小＝温柔） |
| 不挖防爆沟 | `clear.trench=false` |
| 保留基岩层 | `clear.break_bedrock=false` |
| 挖得更深 / 更浅 | `clear.min_y`、`clear.bedrock_top_y` |
| 多清/少清实体 | `clear.kill` = `none` / `items` / `all` |
| 进度播报更勤 | `clear.announce_percent`（默认 10，即每 10%） |
| 允许更大选区 | `clear.max_side`、`clear.max_volume`（**自己承担风险**） |

---

## 十、工作原理与硬性约束

### 10.1 分流流程

```text
玩家登录
  └─ SessionService#hasJoinedServer(username, serverId, ip)
       ├─ ① offline_players 命中 → 直接返回指定 GameProfile（不发任何外部请求）
       ├─ ② littleskin_players 命中 → 请求 LittleSkin hasJoined + RSA 验签 → 返回其 UUID/名称/属性
       └─ ③ 其它 → 立即放行原方法（原版 Mojang 校验照常）
```

聊天链路（外置账号）另有三段桥接：把 Mojang 聊天密钥的验签换成皮肤站公钥验签，并在广播时**逐接收者分流**
（作者本人收原版签名消息，其他人收伪装聊天），解决"外置玩家自己看不到自己发的消息"和"交替发言必被踢"。

### 10.2 原版 Bundler 与 `LoaderBridge`

`server.jar`（1.18+）是 Bundler：自身只有几百 KB 代码，启动时解包并用一个**父加载器为 platform 的
URLClassLoader** 加载真正的服务端。Agent 的辅助类必须用反射 `ClassLoader#defineClass` 注入那个加载器
（这就是 `--add-opens java.base/java.lang=ALL-UNNAMED` 的原因），否则切面里的辅助类会 `NoClassDefFoundError`。

注入是**逐类、多轮重试**的（`defineClass` 在定义某个类的当场就会解析它的父类/接口，顺序不对会失败）：
现在 58 个辅助类全部注入成功，日志里能看到 `[HyAuth] 已把 58 个 Agent 辅助类注入服务端类加载器`。

### 10.3 切面类字节码的硬性约束（改代码必读）

**所有 `*Advice` 类里不能出现任何 `net.minecraft.*` / authlib 的类型引用**——因为切面类由 system 加载器加载，
在 Bundler 环境下它看不到服务端类，一旦引用就会在类加载阶段静默失败（**切面挂不上，功能整块失效**）。

所以约定是：

* Advice 里只用 `Object`、基本类型与 JDK 类型，方法签名按参数**个数 + 可赋值性**匹配；
* 要读服务端字段/调服务端方法，一律通过 `VanillaReflect`（反射 + 缓存 + MISSING 哨兵）；
* 自检里有一条专门扫所有 Advice 类字节码，确认不含 `net/minecraft` 常量池引用。

### 10.4 安全设计

* 名单内玩家校验失败 → **断开**，不降级回退 Mojang（避免同名正版账号被冒用）；
* 属性（皮肤/披风）必须通过公钥 `SHA1withRSA` 验签才会采用；
* 管理员命令有权限门槛，且拒绝把命令根设成原版命令名（那会把原版命令整条遮蔽）；
* 挖掘命令默认更高权限门槛 + 体积/边长上限 + 预演确认，属"防手滑"设计。

### 10.5 26.x 的真实 API（换大版本时最容易踩的坑）

钩子是按名字 + 参数个数 + **返回类型**匹配的，而这些在 26.x 里变过。已经踩过并修好的三处：

| 位置 | 老版本（≤1.21.x） | 26.3 实测 | 踩坑后果 |
| --- | --- | --- | --- |
| 命令入口 `Commands#performPrefixedCommand` / `performCommand` | 返回 `int` | 返回 **`void`** | 匹配器要求 int ⇒ 一个方法都没匹配上，**所有 `/hy` 命令落到原版报"未知命令"**。现在按 `void` / `int` / `boolean` 三种返回类型分别挂切面 |
| 权限 `CommandSourceStack#hasPermission(int)` | 存在 | **已删除**，改为 `permissions()` → `PermissionSet#hasPermission(Permission)`，用 `Permissions.COMMANDS_MODERATOR/GAMEMASTER/ADMIN/OWNER` 对应等级 1~4 | 只走旧路径会把所有人判成 0 级 ⇒ 命令挂上了却"权限不足"。现在两条路径都试 |
| 方块实体 tick | `LevelChunk#tickBlockEntities()` 按区块 | **已移除**，改为 `Level#tickBlockEntities()` **维度级**遍历 | 方块实体分项恒为 0。现在挂 `TickingBlockEntity#tick()`（每个方块实体一次），用 `getPos()` 反推区块，保住"按区块"粒度 |

还有一处同样的"null 当失败"写法：内部派发命令（勘探的 tp、空置域挖掘的 `/fill`）原来用
"反射返回值 != null"判断成功，而 `void` 方法成功也返回 `null` ⇒ 挖掘任务在真机上刚开就报"命令派发失败"。
现在统一用"**方法找得到且调用没抛异常**"判断（`VanillaReflect.callMatchingQuietly`）。

> 判断某个功能到底挂上没有：看 `verify/realjar.ps1` 的输出，或游戏里 `/hy status` 的逐条能力探测。
> **不要**只看日志里的"命中…切面 / 已改写目标类字节码"—— 这两行在方法匹配为空时也会打印。

---

## 十一、许可证与致谢

### 11.1 许可证：GPL-3.0-or-later

本项目以 **[GNU General Public License v3.0 or later](LICENSE)** 发布（`SPDX: GPL-3.0-or-later`）。

* ✅ 可以：自由使用、修改、再分发，包括商业用途；
* ⚠️ 条件（copyleft）：**对外分发**本项目或其衍生作品时，必须同样以 GPL-3.0-or-later 授权、
  **提供完整对应源码**，并标注你修改过的地方；
* ❌ 不能：把修改后的版本闭源分发。

### 11.2 致谢与来源说明

* **三合一登录**（正版 + 外置 + 离线名单在同一台原版服务端上共存）是本项目最早、也是自己的实现；
* **区块卡顿勘探**的"按区块分项归因"想法借鉴自 [MsptMap](https://github.com/Drizzle379/MsptMap)
  （Fabric Mod，把每区块 MSPT 画到 Xaero 世界地图上）——**仅借鉴想法，未使用其任何代码**，
  落地方式完全不同（纯服务端切面 + 命令报告 + 点击传送，无客户端组件、无网络包）；
* 其余功能（离线名单握手改写、聊天密钥桥接与逐接收者分流、管理员命令、UUIDv7 发证与查重、
  空置域挖掘）均为本项目自己的实现。

---

## 十二、自检（可选，但建议跑）

四套自检都是"**把真实切面内联进 26.x 形状的替身类**"的离线回归，不需要真服务端、不联网（除首次下依赖）。
另外还有一个**真机核对**脚本：它直接反编译你的 `server.jar`，把每个切点的方法名/参数个数/返回类型核一遍 ——
这是唯一能防住"日志说已改写、功能却整块失效"的检查（v1.0.6 就栽在这里，见 §10.5）。

```powershell
# 需要 JDK 25（默认按本机路径找，找不到就用 PATH 上的 java/javac）
pwsh -File verify\loaderiso.ps1 -JavaHome "C:\path\to\jdk-25"
pwsh -File verify\chat.ps1      -JavaHome "C:\path\to\jdk-25"
pwsh -File verify\lag.ps1       -JavaHome "C:\path\to\jdk-25"
pwsh -File verify\verify.ps1    -JavaHome "C:\path\to\jdk-25"

# 真机核对：把你的 server.jar 指给它（Bundler 会自动拆内层 jar）
pwsh -File verify\realjar.ps1 -ServerJar "C:\path\to\server.jar" -JavaHome "C:\path\to\jdk-25"
```

| 脚本 | 验什么 | 断言 |
| --- | --- | --- |
| `verify/loaderiso.ps1` | 还原"authlib 在子加载器、Agent 在 system"拓扑，切面仍挂上；58 个辅助类全部注入 | 6/6 |
| `verify/chat.ps1` | 外置账号聊天逐接收者分流（发给别人→伪装聊天、发给自己→原版消息、正版不动、开关可关） | 11/11 |
| `verify/lag.ps1` | 勘探采样/聚类/报告/传送、命令接管与权限、**命令树注册与 OP 门槛**、运行期路径（ParseResults）、UUIDv7 与沿用逻辑、配置版本校对与自动补齐、空置域挖掘计划与节流 | 93 项 |
| `verify/verify.ps1` | Authlib 3/6/10 三代真实字节码 + 新旧签名 + 握手改写 + 热重载 | 各 20/20 |
| `verify/realjar.ps1` | **真 server.jar 上的切点签名**：命令入口、两代权限 API、勘探切点、鉴权/聊天切点 | 20 项 |

CI（`.github/workflows/ci.yml`）在 Java 25 + Windows 上跑的是前四套；`realjar.ps1` 需要你本地的 `server.jar`，
所以放在本机跑（换服务端版本、或升级大版本后建议跑一次）。

---

## 十三、排错速查

| 现象 | 原因 / 处理 |
| --- | --- |
| `processing of -javaagent failed` + `NoClassDefFoundError: com/google/gson/...` | `-javaagent` 指的不是构建产物；用 `target/HyAuth-Agent-1.0.0.jar`，必要时重新 `mvn clean package` |
| `[HyAuth] 当前阶段无法初始化配置（原版 Bundler 启动的正常现象）` | 预期行为，注入完成后会自动重新初始化，无需处理 |
| 玩家进不来，日志**从没**出现 `[HyAuth] 命中目标类` | 目标类没加载 / 类名不符；查是否有 `[Byte Buddy] ERROR` |
| 有 `命中目标类`，但**没有** `已改写目标类字节码` | 切面没挂上（多半是 Advice 里引用了服务端类型）；跑 `verify\loaderiso.ps1` |
| 玩家登录失败，且日志里**没有任何 `[HyAuth]`** | 缺 `--add-opens java.base/java.lang=ALL-UNNAMED`（最常见） |
| 找不到 `littleskin_config.json` | 启动时的工作目录不是服务端根目录；用启动脚本或先 `cd` 过去 |
| `Could not reserve enough space for object heap` | 内存不够，改小 `-Xmx`（`MIN_RAM=1G MAX_RAM=3G ./start.sh`） |
| 外置玩家进不来：`在名单中，但 LittleSkin 鉴权失败/未登录！` | 玩家没在启动器里用皮肤站登录；开 `debug` 看是否返回 204 |
| 能进服但没皮肤，客户端日志 `Discarding incorrectly sized (128x128) skin texture` | 原版客户端只接受 64×64 / 64×32 普通皮肤；让玩家在皮肤站换成普通皮肤（或用 CSL）。**服务端链路是正常的** |
| 外置玩家刚进服就被踢：`Invalid signature for profile public key` | 保持 `relax_chat_keys: true`（默认） |
| 离线名单玩家 `/say`、`/me`、`/msg` 不生效 | 保持 `bypass_signed_commands: true`（默认） |
| 名单玩家发消息自己看不到 + 红字"聊天验证错误" | 用 v1.0.2 及以后的 jar（逐接收者分流）；离线名单玩家还需 `server.properties` 的 `enforce-secure-profile=false` |
| `Couldn't parse player advancements …` / `Ignored advancement …` | 与 Agent 无关：旧版本数据或坏 JSON；备份后删掉该文件即可 |
| 只想确认"名单生效没有" | 看三行：`配置重载完成…人数` / `命中目标类` / 登录时的 `通过 LittleSkin 鉴权成功` 或 `已按离线名单放行` |

---

## 十四、已知限制与后续可做

**已知限制**

* 离线名单的握手改写只实现 26.x（未混淆类名）；更早版本需要额外映射，此时离线名单退化为"仅服务端放行"。
* 勘探与挖掘同样只认 26.x 未混淆类名；更早版本对应类别自动降级为 0（`/hy lag status` 会告诉你哪个挂得上），不影响鉴权主链路。
* 未在 Paper / Spigot / Fabric 上验证（它们会重写登录与鉴权流程）。
* 离线名单玩家没有皮肤注入能力。
* 挖掘是**不可撤销**的：请先在测试存档上小范围试一次。

**后续可做**

1. `offline_players[].textures`：给离线玩家注入皮肤属性；
2. 更早版本（1.20.5 ~ 1.21.x）的登录挂钩映射；
3. 名单级审计日志（谁在什么时间以哪个 UUID 登录）；
4. 勘探数据落盘（CSV/JSON）与跨重启基线对比（当前刻意不落盘，口头报告 + 点击传送已够用）；
5. 命令的 Tab 补全（需要往 Brigadier 注册节点，会引入 `com.mojang.brigadier` 类型引用，要再权衡）。

---

## 附录 A：日志速查

```text
[HyAuth] 已把 58 个 Agent 辅助类注入服务端类加载器: …
[HyAuth] 已自动创建默认配置文件: littleskin_config.json
[HyAuth] 配置版本 v0 → v4，已自动补齐 37 个新版新增项（填的是默认值，可直接在文件里改）: […]
[HyAuth] 配置重载完成（JSON 配置加载成功），当前 LittleSkin 白名单人数: 3，离线名单人数: 1，API: …
[HyAuth] 管理员命令: /hy …（另有 ha, hyauth, lag），需要权限等级 ≥ 2；区块卡顿勘探常驻采样: 开
[HyAuth] 命中目标类: com.mojang.authlib.services.MinecraftServicesSessionService
[HyAuth] 已改写目标类字节码
[HyAuth] 匹配到 LittleSkin 白名单玩家: SUNxiaohaohao
[HyAuth] 通过 LittleSkin 鉴权成功！
[HyAuth] 聊天密钥桥接：皮肤站公钥验签通过（算法 SHA1withRSA）
[HyAuth] 已按离线名单放行（UUID: 4510a1f8-…）
[HyAuth] 离线名单玩家已豁免 enforce-secure-profile
[HyAuth] 读取配置文件失败，请检查 JSON 格式: …
```

> 正版玩家登录时**不会有任何 `[HyAuth]` 输出**——这正说明他们零额外开销地走了原版逻辑。

## 附录 B：文件清单

```text
README.md                  本文档
LICENSE                    GPL-3.0-or-later 全文
BUILD.txt                  版本变化 + 功能总览 + 自检清单 + 怎么核对产物哈希（发行时 CI 会在末尾追加当次构建记录）
pom.xml                    Maven 构建（Byte Buddy 1.17.5，--release 8，shade 打包）
build.bat / build.sh       一键构建（自带 Maven 时自动使用项目内那份）
start.bat / start.sh       启动脚本（含必须的 --add-opens 与 -javaagent）
src/main/java/com/hyauth/agent/
  AgentMain / LoaderBridge 入口与辅助类注入（逐类多轮重试）
  *Advice                  切面（登录 / 握手 / 聊天 / 区块 / 实体 / 方块实体 / 整服 tick / 命令）
  config/ListManager       配置读取、版本校对、自动补齐、名单查询
  util/                    VanillaReflect / ChunkLagSampler / LagReport / AdminCommands /
                           ClearJob / ConfigWriter / ExistingUuidScan / UuidV7 / ChatOut
verify/                    四套离线自检 + 真机切点核对（verify/realjar.ps1）与替身
.github/workflows/         ci.yml（构建 + 四套自检）、release.yml（自动版本号 + 发行）
```

---

---

## 升级策略（重要）

**每次换新 jar，配置文件都会用"当前版本的最新默认模板"重写一遍**，只保留用户信息 —— 这样旧版本留下的
无用/过期字段不会残留、也不会因为旧值造成奇怪问题。

* **保留（用户信息）**：`littleskin_players`、`offline_players`、`commands.extra_admins`；
* **其余一律回到默认值**（默认值就是"所有功能开箱即用"的那套；要改成自定义请在重写之后再改）；
* 旧文件会**备份**成 `littleskin_config.json.bak-v<旧版本>`，要找回某一项就对比它；
* 触发条件：文件里的 `config_version` 与当前 jar 的版本序号不一致；
* **`config_version` 跟着编译版本走**：版本序号 = `major*10000 + minor*100 + patch`（`v1.0.11` → `10011`）。
  版本来源优先级：系统属性 `hyauth.version` → 环境变量 `HYAUTH_VERSION` → jar 清单 `Implementation-Version`
  → 兜底 `1.0.0`。也就是说**发一个新版本号，配置就会自动按新模板重写一次**，不需要你手动删文件。
* 特殊约定：`config_version: -1` 表示"不要重置"（自检脚本用；正式配置不要这么写）。