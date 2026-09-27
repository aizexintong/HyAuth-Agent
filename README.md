# HyAuth-Agent

**Hybrid Authentication Java Agent** —— 面向 Minecraft **原版服务端**的 JVM 内存切面代理。
通过 `-javaagent` + Java Instrumentation + Byte Buddy，在服务端鉴权入口挂载切面，实现：

> **官方正版账号 + LittleSkin 等外置验证账号 + 管理员手动指定 UUID 的离线账号，同一个原版服务端同服游玩。**

不需要 Fabric / Forge / Paper，不需要任何插件，客户端也不需要装任何 Mod（离线名单除外见下文，同样无需客户端改动）。

---

## 目录

* [一、这是什么（真实能力）](#一这是什么真实能力)
* [二、已验证 / 未验证范围](#二已验证--未验证范围)
* [三、运行环境](#三运行环境)
* [四、编译](#四编译)
* [五、部署](#五部署)
* [六、配置文件详解](#六配置文件详解)
* [七、离线名单（管理员指定 UUID）](#七离线名单管理员指定-uuid)
* [八、工作原理](#八工作原理)
* [九、安全设计](#九安全设计)
* [十、与《项目文档》的差异与修正](#十与项目文档的差异与修正)
* [十一、自检（可选）](#十一自检可选)
* [十二、排错手册](#十二排错手册)
* [十三、FAQ](#十三faq)
* [十四、已知限制与后续可做](#十四已知限制与后续可做)
* [附录 A：日志速查](#附录-a日志速查)
* [附录 B：文件清单](#附录-b文件清单)

---

## 一、这是什么（真实能力）

HyAuth-Agent 只做一件事：**接管服务端"这个玩家到底是谁"的那一次判断**。

原版服务端在玩家登录时会调用 `SessionService#hasJoinedServer(username, serverId, ip)` 向 Mojang 会话服务器确认身份。
本 Agent 在内存中把这次调用接过来，按 **三级优先级** 决定：

| 优先级 | 名单 | 行为 | 外部请求 |
| --- | --- | --- | --- |
| 1 | `offline_players`（名字 + 管理员指定 UUID） | 直接以指定 UUID / 名字放行 | **无** |
| 2 | `littleskin_players` | 向 LittleSkin 发 `hasJoined` 并做 RSA 验签，用返回的 UUID / 名称 / 皮肤属性放行 | 只打 LittleSkin |
| 3 | 其它玩家 | 立即放行原方法（不跳过），原版 Mojang 校验照常执行 | 只打 Mojang |

配套能力：

* **热重载**：改完 `littleskin_config.json` 保存即生效（约 0.5 s），增删玩家不用重启。
* **失败关闭**：名单内玩家一旦校验失败就断开，**绝不降级回退 Mojang**，避免同名正版账号被冒用。
* **属性强制验签**：皮肤/披风属性必须通过验证服务器公钥的 `SHA1withRSA` 验签才会采用，未签名或验签失败一律丢弃。
* **宽版本兼容**：一份字节码同时适配 Authlib 3.x / 6.x / 10.x 三代 API（见[兼容矩阵](#authlib-兼容矩阵)）。
* **原版 Bundler 适配**：能跑在官方 `server.jar`（1.18+ 的 Bundler 形态）上，无需解包服务端。

---

## 二、已验证 / 未验证范围

诚实说明——下面这张表是本项目的真实测试状态（全部为本机实测，非推测）：

| 项目 | 状态 | 证据 |
| --- | --- | --- |
| **MC 26.3 原版服务端开服** | ✅ 实测通过 | 官方 `server.jar`（SHA1 `33680f5f…`）启动到 `Done (0.714s)!`，切面命中并**成功改写** `com.mojang.authlib.services.MinecraftServicesSessionService` |
| **Bundler 类加载器隔离下的切面挂载** | ✅ 实测通过 | 新增 `verify\loaderiso.ps1`：还原「authlib 在子加载器、Agent 在 system 加载器」拓扑，6/6 断言通过（含离线名单接管与 UUID 校验）。**这是 v1.0.1 修掉的一个致命 bug，见 [§8.4](#84-切面类字节码的硬性约束必读)** |
| **Authlib 10.0.77（MC 26.x）** | ✅ 实测通过 | 自检模式 `real-10`：20/20 断言 |
| **Authlib 6.0.54（MC 1.20.5 ~ 1.21.x 系）** | ✅ 实测通过 | 自检模式 `real-modern`：20/20 断言 |
| **Authlib 3.11.49（MC ≤ 1.20.4 系）** | ✅ 实测通过 | 自检模式 `real-legacy`：20/20 断言 |
| **新旧两种方法签名（GameProfile→GameProfile / String→ProfileResult）** | ✅ 实测通过 | 自检模式 `mock-modern` / `mock-legacy`：各 22/22 断言 |
| **LittleSkin 公钥自动获取** | ✅ 真实网络实测 | 真实访问 `https://littleskin.cn/api/yggdrasil`，取到 `signaturePublickey` 并成功解析 |
| **离线名单握手改写（shouldAuthenticate）** | ✅ 真实 26.3 类实测 | 直接实例化 `ClientboundHelloPacket`：名单内 `false`、名单外 `true` |
| **真实客户端登录 · 离线名单玩家** | ✅ 实测通过 | 用户真实环境（Linux + 26.3 + `online-mode=true`）：`shouldAuthenticate=false` → `已按离线名单放行（UUID: 4510a1f8…）` → `UUID of player azxt is 4510a1f8…` → 成功进入游戏 |
| **真实客户端登录 · 正版玩家** | ✅ 实测通过（零干扰） | 多个正版账号正常进服，日志中**没有任何 `[HyAuth]` 输出**（证明未名单玩家完全走原版逻辑） |
| **真实客户端登录 · LittleSkin 外置账号** | ✅ 实测通过（含聊天密钥） | 真实环境：`匹配到 LittleSkin 白名单玩家: SUNxiaohaohao` → `通过 LittleSkin 鉴权成功！` → **`聊天密钥桥接：皮肤站公钥验签通过（算法 SHA1withRSA）`** → 正常进入并聊天。即：皮肤站确实用 `signaturePublickey` 签发了玩家证书，桥接做的是**真实密码学验签**，不是"一律放行" |
| **真实客户端登录 · 离线名单玩家聊天** | ✅ 实测通过 | 服务端保持 `enforce-secure-profile=true`：`离线名单玩家已豁免 enforce-secure-profile` → 玩家发言以 `[Not Secure] <azxt> …` 正常广播（正版玩家仍强制校验） |
| **外置账号聊天广播（逐接收者分流）** | ✅ 离线回归实测（11/11 断言） | 新增 `verify\chat.ps1`：把真实切面内联进 26.3 形状的替身，断言"发给别人→伪装聊天、发给自己→原版签名消息、正版不动、开关可关、父类声明也能命中"（见 [§8.7](#87-交替发言必被踢--外置玩家自己看不到自己发的消息)）；真机现象（自己看不到 + 红字聊天验证错误）由此修复，待用户客户端复测确认 |
| **配置热重载** | ✅ 实测通过 | 运行中改写名单，`Beta -> true` 无需重启；用户环境亦实测（人数 2→3） |
| **真实玩家完整登录（真实客户端 + 公网服务端）** | ⚠️ 部分联调 | **离线名单玩家与正版玩家已在用户真实环境跑通**（见上）；LittleSkin 外置账号登录仍待实测。请首次上线时按[排错手册](#十二排错手册)核对日志 |
| **MC 1.20.x 及更早的"离线名单握手改写"** | ⚠️ 未实现 | 这些版本的 `net.minecraft.*` 是混淆的，挂钩需要额外映射；此时离线名单**退化为仅服务端放行**（[见 §7.3](#73-已知边界)） |
| **MC 1.18 ~ 1.21.x 的 Bundler 形态** | ⚠️ 未逐一实测 | `LoaderBridge` 注入逻辑与版本无关，但早期 Bundler 会 fork 子进程，行为可能不同；26.3 已实测 |

> 结论：**分流核心（hasJoinedServer）、26.3 服务端开服、Bundler 类加载器隔离下的切面挂载、
> 离线名单的真实客户端登录与聊天、LittleSkin 外置账号的登录与聊天密钥验签都是硬性验证过的**；
> 三类玩家（正版 / 外置 / 离线名单）已在同一台真实服务器上同时跑通。

---

## 三、运行环境

| 项目 | 要求 |
| --- | --- |
| 服务端 | Minecraft **原版** `server.jar`（Mojang 官方，任何档位；本 README 以 **26.3** 为准） |
| Java（运行） | Minecraft 26.3 要求 **Java 25**；Agent 自身字节码目标为 Java 8，理论上 Java 8+ 均可运行 |
| Java（编译） | 任意能编译 `--release 8` 的 JDK（本机用 Microsoft OpenJDK **25.0.1** 验证） |
| 构建 | Apache Maven **3.6+**（本机用 **3.9.9** 验证） |
| 网络（运行） | 能访问 `https://littleskin.cn`（或你自建/镜像的 `api_root`） |

**本机实测环境**

```text
服务端 : Minecraft 26.3 官方 server.jar（59.41 MB，SHA1 33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c）
Java   : C:\Users\liu22\AppData\Roaming\.minecraft\runtime\java-runtime-epsilon\bin\java.exe (25.0.1)
Maven  : 3.9.9
依赖   : Byte Buddy 1.17.5；authlib 3.11.49 / 6.0.54 / 10.0.77；gson 2.10.1 / 2.14.0；guava 31.0.1 / 33.6.0
```

> **原版 `server.jar` 是 Bundler（1.18+）**：它自身只有几百 KB 代码，内部打包了 authlib / gson / guava 等依赖，
> 启动时解包并用一个 **父加载器为 platform 的 URLClassLoader** 加载真正的服务端。
> 这正是必须加 `--add-opens java.base/java.lang=ALL-UNNAMED` 的原因（见 [§5.2](#52-startbat-逐行说明)）。

---

## 四、编译

### 4.1 标准方式（推荐）

在项目根目录执行：

```bat
:: Windows
mvn clean package
```

```bash
# Linux / macOS（等价，且会自动找 Maven / 自动推导 JAVA_HOME）
chmod +x build.sh && ./build.sh
```

产物：

```text
target/HyAuth-Agent-1.0.0.jar            ← 直接拿来用（已内置 Byte Buddy）
target/original-HyAuth-Agent-1.0.0.jar   ← 未打包依赖的原始 jar，不要用
```

> 每次构建后的产物大小与 SHA256 记录在项目根目录的 `BUILD.txt` 里，升级前可先核对。

构建过程做了什么：

1. `maven-compiler-plugin` 以 `--release 8` 编译 `src/main/java`（20 个源文件）；
2. 依赖解析：`net.bytebuddy:byte-buddy` 与 `com.google.code.gson:gson` 走 Maven Central；
   `com.mojang:authlib` 只发布在 Mojang 官方仓库，`pom.xml` 里已配置 `https://libraries.minecraft.net/`；
3. `maven-shade-plugin` 把 Byte Buddy 打进最终 jar，并写入清单：
   `Premain-Class` / `Agent-Class` / `Can-Redefine-Classes` / `Can-Retransform-Classes`。

首次构建需要联网下载依赖与插件（本机首次约 2~12 分钟，取决于网速）；之后增量构建约 5 秒。

### 4.2 常用参数

```bat
:: 跳过测试（项目本身没有测试用例，仅为加速）
mvn clean package -DskipTests

:: 首次成功联网构建后，可完全离线构建
mvn -o clean package -DskipTests

:: 指定 Byte Buddy 版本（默认 1.17.5）
mvn clean package -Dbyte-buddy.version=1.17.5

:: 使用公司内网镜像 / 代理：编辑 %USERPROFILE%\.m2\settings.xml 的 <mirrors>/<proxies>
```

> ⚠️ **Byte Buddy 版本别乱降**：`1.14.12`（项目文档里写的版本）在 Java 25 上会直接抛
> `IllegalArgumentException: Java 25 (69) is not supported by the current version of Byte Buddy which officially supports Java 22 (66)`，
> 切面完全失效。若你确实要在 Java 21 及以下使用 1.14.12，可 `-Dbyte-buddy.version=1.14.12`；
> 在 Java 25 上想用旧版必须再加 `-Dnet.bytebuddy.experimental=true`（不推荐）。

### 4.3 本机没有 Maven 怎么办

三种办法任选（**Linux / macOS 直接 `chmod +x build.sh && ./build.sh` 即可**，它做的就是下面 ① 的逻辑：
先找 `PATH` 上的 `mvn`，再找项目内的 `tools/apache-maven-3.9.9/bin/mvn`，并自动推导 `JAVA_HOME`；
项目里那份 Maven 同时带了 Windows 的 `mvn.cmd` 和 Unix 的 `mvn`/`mvnDebug`，两边都能用）：

**① 用项目自带的 `build.bat`（会自动识别）**

```bat
build.bat
```

它会依次尝试：`PATH` 中的 `mvn` → 本地 `tools\apache-maven-3.9.9\` → 打印手动安装指引。

**② 用项目内自带的 Maven（本机开发目录里有；**仓库里没有**，见下方说明）**

`tools\apache-maven-3.9.9\` 是一份 Apache Maven 3.9.9（约 11 MB，Apache-2.0 许可，纯粹为了在没装 Maven 的机器上也能一键构建）：
只要 `tools\apache-maven-3.9.9\bin\m2.conf` 存在，`build.bat` 就会自动用它。

> ⚠️ **`tools/` 已被 `.gitignore` 排除**（11 MB 的第三方工具链不适合进仓库），
> 所以 `git clone` 下来的仓库里没有它 —— `build.bat` / `build.sh` 会照常退回 `PATH` 上的 `mvn`，
> 或在 CI 里用 runner 自带的 Maven。需要离线/零安装构建时按下面自己补一份即可。

* 不需要它时，直接删除整个 `tools\` 目录即可（`build.bat` 会转而使用 PATH 里的 `mvn`）。
* 若该目录缺失，也可以自己补：

  1. 下载 <https://repo1.maven.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip>
  2. 解压到项目的 `tools\`，得到 `tools\apache-maven-3.9.9\bin\m2.conf`
  3. 重新运行 `build.bat`（或按下面 ③ 的命令行手工执行）

> 无论用哪种方式，**首次构建都需要联网**下载依赖与插件（约 30 MB，会缓存到 `%USERPROFILE%\.m2\repository`；
> 之后的增量构建可加 `-o` 离线完成）。

**③ 直接用 Java 启动 Maven（不依赖 PATH，本机实测用过这条路）**

```bat
set MVN=tools\apache-maven-3.9.9
"%JAVA_HOME%\bin\java.exe" ^
  -Dclassworlds.conf="%MVN%\bin\m2.conf" ^
  -Dmaven.home="%MVN%" ^
  -Dmaven.multiModuleProjectDirectory="%CD%" ^
  -classpath "%MVN%\boot\plexus-classworlds-2.8.0.jar" ^
  org.codehaus.plexus.classworlds.launcher.Launcher clean package
```

### 4.4 编译产物自检

```bat
:: Windows：清单里必须有 Premain-Class / Agent-Class
jar xf target\HyAuth-Agent-1.0.0.jar META-INF/MANIFEST.MF && type META-INF\MANIFEST.MF
```

```bash
# Linux / macOS
cd "$(mktemp -d)" && jar xf ~/HyAuth-Agent/target/HyAuth-Agent-1.0.0.jar META-INF/MANIFEST.MF
cat META-INF/MANIFEST.MF
sha256sum ~/HyAuth-Agent/target/HyAuth-Agent-1.0.0.jar
```

正常应为：

```text
Agent-Class: com.hyauth.agent.AgentMain
Can-Redefine-Classes: true
Can-Retransform-Classes: true
Premain-Class: com.hyauth.agent.AgentMain
```

### 4.5 GitHub Actions：自动构建与发行版

仓库自带两条工作流（`.github/workflows/`），**不需要任何额外配置**：

| 工作流 | 触发 | 做什么 |
| --- | --- | --- |
| `ci.yml` | push 到 `main` / 提 PR / 手动 | `mvn clean package` → 依次跑 `verify\loaderiso.ps1`、`verify\chat.ps1`、`verify\verify.ps1` → 上传 `HyAuth-Agent-*.jar` 与 `BUILD.txt` 作为 Actions 产物 |
| `release.yml` | **推送 `v*` 标签** / 手动指定标签 | 构建 + 同上三套自检 → 计算 SHA256 → 自动创建 GitHub Release，附 `HyAuth-Agent-1.0.0.jar`、同名带版本号的副本（如 `HyAuth-Agent-v1.0.2.jar`）与 `BUILD.txt` |

发一个版本就这么简单：

```bash
git tag -a v1.0.2 -m "聊天逐接收者分流 + CI/Release"
git push origin v1.0.2        # → Actions 自动构建并生成 Release
```

> 想换 runner / JDK，直接改 `.github/workflows/*.yml` 即可
> （自检脚本按 Windows 路径开发，所以两条工作流都用 `windows-latest`；换成 `ubuntu-latest` 需要
> 自行适配脚本里的 `$env:TEMP` 等 Windows 习惯）。

### 4.6 仓库里不提交什么（`.gitignore`）

只提交**源码 / 文档 / 自检脚本**；下面这些都已排除，避免仓库被几十 MB 的产物撑大：

```text
target/  .m2repo/  verify/libs/  tools/           ← 构建产物、依赖缓存、可选的免安装 Maven
*.jar                                            ← 发布用的 jar 由 Actions 构建并挂到 Release
server.jar  server.properties  eula.txt  logs/  world*/  libraries/  littleskin_config.json
.idea/  *.iml  .vscode/  Thumbs.db  Desktop.ini  .DS_Store
```

---

## 五、部署

### 5.1 目录布局

把 **构建产物** 与启动脚本一起复制到服务端根目录（哪个脚本看你的系统，两个脚本做的事完全一样）：

```text
你的服务端目录/
├── server.jar                 # 原版服务端（保持原样，不要动）
├── HyAuth-Agent-1.0.0.jar     # ← 从 target\ 复制过来
├── start.bat                  # ← Windows 用这个
├── start.sh                   # ← Linux / macOS 用这个
├── eula.txt                   # eula=true
├── server.properties          # online-mode=true（正版验证开启）
├── littleskin_config.json     # 首次启动自动生成
├── libraries/ versions/       # 原版 Bundler 解包出来的目录（本来就有，别删）
└── world/ logs/ ...
```

> 如果你的目录里已经有 `libraries/` 和 `versions/`，说明原版服务端**至少成功启动过一次**，属于 1.18+ 的
> Bundler 布局，正是本 Agent 验证过的形态。想看具体是哪个版本：`ls versions/*/`（如 `server-26.3.jar`）。

### 5.2 `start.bat` 逐行说明

```cmd
set "JAVA_PATH=...\java-runtime-epsilon\bin\java.exe"   :: Java 25 运行时；不存在则回退到 PATH 的 java
"%JAVA_PATH%" ^
  -Xms4G -Xmx8G ^
  --add-opens java.base/java.net=ALL-UNNAMED ^          :: 现代 JVM 强封装突破
  --add-opens java.base/java.lang=ALL-UNNAMED ^         :: ★必需：Bundler 类注入用 ClassLoader#defineClass
  --add-opens java.base/java.util=ALL-UNNAMED ^         :: 现代 JVM 强封装突破
  -javaagent:HyAuth-Agent-1.0.0.jar ^                   :: 挂载 Agent
  -jar server.jar ^
  nogui
```

**为什么 `--add-opens java.base/java.lang` 是必需的**（不是可选项）：

1. 原版 `server.jar` 是 Bundler，它把服务端类放在一个 **父加载器为 platform 的 `URLClassLoader`** 里，
   `-javaagent` 的类（system 加载器）**服务端看不到**；
2. 因此 Agent 在命中目标类时，会用反射调用 `ClassLoader#defineClass` 把自己的辅助类**注入服务端类加载器**；
3. 反射访问 `java.base/java.lang` 需要显式开权限，缺了它 → 服务端能开服，但**玩家登录时鉴权会失败**。

### 5.3 等价的手工命令行

`start.bat` / `start.sh` 里其实就这一条命令，参数顺序（`-javaagent` 必须在 `-jar` 之前）不要调换：

```cmd
:: Windows
"C:\Users\liu22\AppData\Roaming\.minecraft\runtime\java-runtime-epsilon\bin\java.exe" ^
  -Xms4G -Xmx8G ^
  --add-opens java.base/java.net=ALL-UNNAMED ^
  --add-opens java.base/java.lang=ALL-UNNAMED ^
  --add-opens java.base/java.util=ALL-UNNAMED ^
  -javaagent:HyAuth-Agent-1.0.0.jar ^
  -jar server.jar ^
  nogui
```

```bash
# Linux / macOS（用你自己的 java 25 路径）
java -Xms4G -Xmx8G \
  --add-opens java.base/java.net=ALL-UNNAMED \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/java.util=ALL-UNNAMED \
  -javaagent:HyAuth-Agent-1.0.0.jar \
  -jar server.jar \
  nogui
```

### 5.4 首次启动会发生什么

1. 生成默认 `littleskin_config.json`（含示例 LittleSkin 名单与空的 `offline_players`）；
2. 打印配置与热监听状态；
3. 挂载切面；当服务端加载 `SessionService` 实现类时打印"命中目标类"；
4. 当**第一个玩家连接**时，才会加载登录处理器与握手包，此时会打印两条"命中登录…"（这是懒加载，属正常现象）。

启动成功的完整日志样例见[附录 A](#附录-a日志速查)。

### 5.5 Linux / macOS：`start.sh` 完整步骤

典型场景：Windows 上构建，Linux 服务器上跑（目录里已经有 `server.jar`、`libraries/`、`versions/`、`world/`）。

**① 把 jar 和脚本传过去**（服务端机器**不需要**装 Maven、不需要源码）

```bash
# 在 Windows 上（PowerShell / cmd 都行）
scp target\HyAuth-Agent-1.0.0.jar root@<服务器IP>:~/original/
scp start.sh                    root@<服务器IP>:~/original/
```

> 也可以直接在 Linux 上构建：把整个项目目录传过去，然后 `chmod +x build.sh && ./build.sh`
> （需要联网拉依赖，见 [§4.3](#43-本机没有-maven-怎么办)）。

**② 启动**

```bash
cd ~/original

# 校验传输完整（应与构建机一致，注意大小写无关）
sha256sum HyAuth-Agent-1.0.0.jar
# 期望：c064bcdbcaa04b6f4a87428926d08b52fff507421509a26664a6569cf749618c

chmod +x start.sh          # ★必需，否则 Permission denied
./start.sh
```

第一次启动会：在**当前目录**生成 `littleskin_config.json` → 打印配置与热监听 → 挂载切面。
看到 `Done (x.xxx s)! For help, type "help"` 就成功了。

**③ `start.sh` 与 `start.bat` 的差异**（命令参数本身**完全一致**）

| 行为 | `start.bat`（Windows） | `start.sh`（Linux/macOS） |
| --- | --- | --- |
| 定位 Java | 写死文档里的 Java 25 路径，不存在则回退 `java` | 默认 `PATH` 上的 `java`，可用 `JAVA_PATH=` 覆盖 |
| 切工作目录 | `cd /d "%~dp0"` | `cd "$(dirname "$0")"` |
| 内存 | `-Xms4G -Xmx8G` | 相同，可用 `MIN_RAM=` / `MAX_RAM=` 覆盖 |
| `--add-opens` | 三个（含**必需**的 `java.lang`） | 三个，一字不差 |
| Java 版本提示 | 无 | 解析 `java -version`，低于 25 时告警 |
| 关服 | 控制台 `stop` | 相同；脚本用 `exec`，`Ctrl+C` / `systemctl stop` 都能优雅关服 |

```bash
# 需要覆盖时（例如 4G 内存的小机器）
JAVA_PATH=/usr/lib/jvm/java-25-openjdk/bin/java MIN_RAM=1G MAX_RAM=3G ./start.sh
```

**④ 让它长期活着**（直接 `./start.sh` 一断 SSH 就会被 SIGHUP 掉）

```bash
# screen
screen -S mc && ./start.sh        # Ctrl+A 然后 D 脱离；screen -r mc 回来

# tmux
tmux new -s mc && ./start.sh      # Ctrl+B 然后 D 脱离；tmux a -t mc 回来

# nohup（日志丢到文件）
nohup ./start.sh > logs/hyauth-console.log 2>&1 &
```

**⑤ 注册成 systemd 服务（可选，开机自启）**

```ini
# /etc/systemd/system/minecraft.service
[Unit]
Description=Minecraft Server (HyAuth-Agent)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=mc
WorkingDirectory=/home/mc/original
ExecStart=/home/mc/original/start.sh
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload
systemctl enable --now minecraft
journalctl -u minecraft -f      # 看实时日志
systemctl stop minecraft        # 停服（发 SIGTERM，服务端会自己优雅保存并退出）
```

> 服务端**不要用 root 跑**（`User=mc` 换成你自己的普通用户并 `chown -R mc:mc ~/original`）。
> 首次用 systemd 启动时，`littleskin_config.json` 会生成在 `WorkingDirectory` 里，也就是服务端根目录。

**⑥ 两个容易踩的点**

* `server.properties` 里的 `online-mode` **保持 `true`**：离线名单要的就是「正版验证开着，也让指定玩家用离线账号进来」；
  改成 `false` 反而会让所有玩家都免验证（见 [§九 安全设计](#九安全设计)）。
* 老版本（1.20.x~1.21.x）的**服务端类是混淆过的**，离线名单那套握手改写挂不上去（见 [§十四](#十四已知限制与后续可做)），
  但 LittleSkin 名单分流仍然可用。26.3 没有这个问题。

### 5.6 手把手：从上传到"玩家真的进游戏"

**第一步 · 服务端侧（约 5 分钟）**

```bash
cd ~/original
# 1) 文件已在同一目录：server.jar / HyAuth-Agent-1.0.0.jar / start.sh
# 2) 先启动一次，只为让它生成配置文件
./start.sh
# 看到 `Done (x.xxx s)!` 后，在控制台输入 stop 停服
```

生成的 `littleskin_config.json` 里带着两个**示例名字**（`Xiao_Ming`、`Player_Demo`），必须改成你自己的：

```json
{
  "littleskin_players": [ "你的LittleSkinID" ],
  "offline_players": [
    { "name": "离线玩家名", "uuid": "11112222-3333-4444-5555-666677778888" }
  ],
  "debug": true
}
```

* `littleskin_players`：走皮肤站（LittleSkin）验证的玩家；
* `offline_players`：**完全不走验证**、由你钦定 UUID 的玩家（优先级最高）；
* `debug: true` 只是让日志多打 `[HyAuth][DEBUG] …`（HTTP 状态码等），上线稳定后建议改回 `false`。

**UUID 填什么？** 两种常见选择：

```bash
# ① 沿用"离线服务器"的历史 UUID（与老存档、老白名单一致）—— 原版算法即 UUID.nameUUIDFromBytes("OfflinePlayer:"+名字)
cat > /tmp/OffUUID.java <<'EOF'
public class OffUUID {
    public static void main(String[] a) {
        System.out.println(java.util.UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + a[0]).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
EOF
java /tmp/OffUUID.java 离线玩家名

# ② 或者随便指定一个合法 UUID（想给某个玩家"发一张新身份证"时用）
#    例如 11112222-3333-4444-5555-666677778888，32 位不带连字符也认。
```

改完**不需要重启**：文件一保存，0.5 秒内热重载，日志会重打一行
`[HyAuth] 配置重载完成（JSON 配置加载成功），当前 LittleSkin 白名单人数: N，离线名单人数: M，API: …`。

**第二步 · 客户端侧（玩家需要做什么）**

| 玩家类型 | 客户端要装东西吗 | 玩家怎么登录 | 服务端最终认到的 UUID |
| --- | --- | --- | --- |
| 正版玩家 | 不需要 | 照常正版登录 | Mojang 返回的 |
| 列在 `littleskin_players` | **不需要装 Mod**，但启动器要支持**外置登录**（authlib-injector） | 在启动器里添加外置登录，API 填 `https://littleskin.cn/api/yggdrasil`，用皮肤站账号登录后再进服 | LittleSkin 返回的 |
| 列在 `offline_players` | 不需要 | 启动器选**离线登录**，名字填成与配置一致（大小写不敏感） | **你配置里写的那个** |

> ⚠️ LittleSkin 玩家**必须先用 LittleSkin 账号登录启动器**。如果他用正版账号（或离线）进来，
> `hasJoined` 会返回 204，Agent 会按"失败关闭"直接拒绝——这是有意设计，避免同名正版账号被冒用。

**第三步 · 别被白名单挡住（很容易踩）**

`server.properties` 里如果 `white-list=true`，那么 `whitelist.json` 里的 UUID 必须与 Agent 给的**完全一致**，
离线名单玩家尤其容易在这里被 `You are not whitelisted` 拦住。先把测试打通再开白名单：

```bash
# 测试阶段最省事：先关掉白名单
# server.properties → white-list=false
```

非要开白名单时，**手工把 UUID 写进 `whitelist.json`** 再 `/whitelist reload`：

```json
[ { "uuid": "11112222-3333-4444-5555-666677778888", "name": "离线玩家名" } ]
```

（OP 同理：`ops.json` 里按 UUID 给权限。）

**第四步 · 确认真的生效了**

服务端日志里对照这三组（玩家尝试登录时才会打印）：

```text
# 离线名单玩家
[HyAuth] 匹配到离线名单玩家: TestGuy（管理员指定 UUID: 1111…），跳过全部外部鉴权。
[HyAuth] 离线名单玩家 TestGuy：已关闭客户端会话校验（shouldAuthenticate=false）…
[HyAuth] 玩家 TestGuy 已按离线名单放行（UUID: 1111…）。

# LittleSkin 玩家
[HyAuth] 匹配到 LittleSkin 白名单玩家: YourLSName，发起外置鉴权...
[HyAuth] 玩家 YourLSName 通过 LittleSkin 鉴权成功！

# 正版玩家：什么都不打印（这是对的，代表零额外开销地走了原版逻辑）
```

进服后在游戏里核对身份：`/list` 看在线玩家，`/data get entity <玩家名> UUID`（需 OP）看实际 UUID 是否符合预期。

**上线顺序建议**：先让一个正版账号进（确认没搞坏原版流程）→ 再让一个 LittleSkin 小号进 →
最后测离线名单玩家。三段都过，再开白名单、再把 `debug` 关掉。

---

## 六、配置文件详解

文件名固定为 `littleskin_config.json`，位置为**服务端工作目录**（即 `server.jar` 所在目录；用 `start.sh`/`start.bat` 启动时就是脚本所在目录）。

**这个文件由 Agent 自动生成，你只需要在它生成之后往里填名字。** 完整流程：

1. 首次启动时 Agent 发现文件不存在 → **自动创建**一份带示例值的配置，日志打印
   `[HyAuth] 已自动创建默认配置文件: littleskin_config.json`
   （原版 Bundler 环境下，这一步发生在 Agent 完成类注入之后，也就是**开服阶段、玩家进服之前**）；
2. 你把示例名字改成真实名字——`nano littleskin_config.json`、`vim`，或在本机改好再 `scp` 覆盖都行；
   **保存即生效**（约 0.5 秒热重载，不用重启服务端）；
3. 此后 Agent **只读不改**这个文件，你的修改不会被它覆盖；
4. 只有当文件被**删除**后，下次启动才会重新生成默认内容——想从零开始就删掉它重启。

也可以反过来：**不等它生成，自己手写一个同名文件放进去**（文件已存在时 Agent 不会覆盖）。手写注意三点：

* 用 **UTF-8 无 BOM** 编码（Linux 上 `nano`/`vim` 天然满足；在 Windows 记事本里改完再传上去要留意）；
* 不要写注释（`//`、`/* */`）和多余的逗号；
* 写坏了也不致命：Agent 会打印 `[HyAuth] 读取配置文件失败，请检查 JSON 格式: …`，
  并**继续沿用上一次生效的名单**（不会清空、不会把玩家全挡在外面）。

> 游戏内**没有**任何名单管理命令，名单只认这一个文件。

### 6.1 完整示例

```json
{
  "description": "HyAuth-Agent 配置文件：littleskin_players = 走 LittleSkin 外置验证的玩家；offline_players = 管理员手动指定 UUID 的离线玩家（优先级最高）",
  "littleskin_players": [
    "Xiao_Ming",
    "Player_Demo"
  ],
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
  "debug": false
}
```

### 6.2 字段说明

| 字段 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `littleskin_players` | string[] | 否 | 示例值 | 走 LittleSkin 外置验证的玩家名，**判定不区分大小写** |
| `offline_players` | object[] | 否 | `[]` | 离线名单，见[第七节](#七离线名单管理员指定-uuid) |
| `offline_players[].name` | string | 是 | — | 玩家名（大小写以此为准，登录后会统一成这个写法） |
| `offline_players[].uuid` | string | 是 | — | 管理员指定的 UUID，支持 `8-4-4-4-12` 与 32 位无符号两种写法 |
| `api_root` | string | 否 | `https://littleskin.cn/api/yggdrasil` | Yggdrasil API 根地址，可指向自建皮肤站/镜像 |
| `public_key` | string | 否 | 空 | 验签公钥（PEM 或裸 Base64）。留空则自动从 `api_root` 的 `signaturePublickey` 获取 |
| `connect_timeout_ms` | int | 否 | `5000` | 连接 LittleSkin 超时 |
| `read_timeout_ms` | int | 否 | `5000` | 读取 LittleSkin 响应超时 |
| `relax_chat_keys` | bool | 否 | `true` | 启用聊天密钥桥接（Mojang 验签 → LittleSkin 公钥验签），避免外置账号被 `Invalid signature for profile public key` 踢下线，见 [§8.5](#85-外置账号的聊天签名密钥-relax_chat_keys) |
| `chat_key_strict` | bool | 否 | `false` | 桥接三级全部验不过时：`false` 放行 / `true` 拒绝该密钥（等同原版严格行为）。建议先确认日志里出现过"皮肤站公钥验签通过"再打开 |
| `bypass_signed_commands` | bool | 否 | `true` | 让**离线名单玩家**的指令按"未签名"执行（修复 `/say`、`/me`、`/msg` 报红字不生效），见 [§8.6](#86-离线名单玩家发不出指令missing-profile-public-key) |
| `offline_chat_exempt` | bool | 否 | `true` | 只对**离线名单玩家**豁免 `enforce-secure-profile`，让他们能发未签名聊天；**不需要关闭服务器的全局开关**，正版玩家仍强制校验。见 [§8.6](#86-离线名单玩家发不出指令missing-profile-public-key) |
| `debug` | bool | 否 | `false` | 打印 204（未登录）等调试信息 |

### 6.3 行为边界

* 名单为空 / 配置读不到 → 所有玩家都走原版 Mojang 校验（外置与离线玩家进不来，但**不存在越权风险**）。
* JSON 写坏 → 打印报错并**保留上一次生效的配置**，不会把名单清空。
* `offline_players` 条目缺 `name`、`uuid` 非法 → 跳过该条并告警，不影响其它条目。
* 同名条目重复 → 后者生效并告警。

### 6.4 热重载（改名单不用重启服务端）

**没有命令、没有开关：编辑文件 → 保存 → 约 0.5 秒后自动生效。** Agent 里有一个守护线程
`HyAuth-ConfigWatcher` 用 `WatchService` 监视配置所在**目录**（不是文件本身），
所以无论你是原地改写、还是"删掉重建 / 改名覆盖"，都能感知到。

```bash
# 服务端保持运行，直接编辑（nano 存盘是原地改写，必然触发）
nano /original/littleskin_config.json      # Ctrl+O 保存，Ctrl+X 退出

# 若服务端跑在 screen/tmux 里，切回去即可看到这行（<1 秒）：
# [HyAuth] 配置重载完成（JSON 配置加载成功），当前 LittleSkin 白名单人数: 3，离线名单人数: 1，API: …
```

```bash
# 若用 systemd 托管：
journalctl -u minecraft -f
```

要点：

* **只影响之后的登录**：已经在线的玩家不受影响；名单里新增的玩家下一次尝试登录就能进。
* **换名单不用重启，换 Agent jar 才要重启。**
* 人数没变就说明没读进去（多半是 JSON 写错、或名字重复被去重）。
* 判定成功的唯一依据是那行 `配置重载完成…人数`；`touch` 一下也会触发重载，只是人数不变。

**热重载没反应时按顺序查**：

| 检查项 | 说明 |
| --- | --- |
| 改的是不是同一个文件 | 启动日志里 `配置热监听已启动: /original/littleskin_config.json` 才是它监视的路径；改到别处没用 |
| JSON 是否合法 | 写坏会打印 `[HyAuth] 读取配置文件失败，请检查 JSON 格式: …`，并**继续沿用上一次生效的名单**（不会清空、不会把玩家全挡在外面） |
| 编辑器写法 | `vim` + `:wq`、`scp` 覆盖、`mv` 进来都行（目录被监视）；只有把文件放到**别的目录**再改才无效 |
| 特殊环境 | Docker/K8s 的某些挂载驱动下 inotify 事件可能丢失，此时重启服务端即可（或直接 `touch` 一下文件重试） |

---

## 七、离线名单（管理员指定 UUID）

在 **`online-mode=true`（正版验证开启）** 的前提下，允许**指定玩家以离线方式登录**，且 UUID 由管理员固定。

### 7.1 为什么需要它

* 离线客户端的 UUID 原本由玩家名推导（`OfflinePlayer:<name>`），**谁算出这个名字就能拿到同一份存档数据**；
* 由管理员显式指定 UUID 后，这些玩家与"名字哈希出来的 UUID"以及正版账号的 UUID 彻底隔离，不会互相覆盖存档。

### 7.2 它是怎么做到的（关键在客户端那一步）

Minecraft 客户端只有在服务端要求验证时才会调用会话服务，**一旦调用失败，客户端自己就会断开**——
这是纯服务端无法干预的一步。以下均为 26.3 真实字节码（客户端 jar 未混淆，可直接读）：

| 环节 | 真实行为 |
| --- | --- |
| 客户端 `ClientHandshakePacketListenerImpl.handleHello` | `shouldAuthenticate == true` → 调 `authenticateServer()` → `SessionService.joinServer(...)`，抛 `AuthenticationException` 就返回 `disconnect.loginFailedInfo.*`（客户端断开）；`false` → **直接跳过会话上报**，继续加密握手 |
| 服务端 `ServerLoginPacketListenerImpl.handleHello` | 把 `ClientboundHelloPacket(..., shouldAuthenticate)` 第 4 个参数**硬编码为 true**；之后**无论该值为何**，在线模式分支都会照常调用 `hasJoinedServer(username, serverId, ip)` |
| 服务端验证线程 `ServerLoginPacketListenerImpl$1` | `result != null` → 用返回的 `ProfileResult.profile()` 的 id/name 登录；`null` → 单人存档才放行为离线，否则断开 `unverified_username`（**不校验名字是否等于请求名**） |
| `PlayerList.placeNewPlayer` | `enforceSecureProfile()` 只作为参数发给客户端的 `ClientboundLoginPacket`，**不会因为缺少聊天签名而踢人** |

因此本 Agent 挂三个点即可：

```text
玩家连接
   │
   ├─ ServerLoginPacketListenerImpl#handleHello          ← LoginNameAdvice：取出 LoginStart 里的玩家名
   │        │
   │        ├─ ClientboundHelloPacket#<init>             ← HelloPacketAdvice：名单内玩家把 shouldAuthenticate 改写为 false
   │        │
   │        └─ （加密握手完成，服务端进入在线模式校验分支）
   │
   └─ SessionService#hasJoinedServer                     ← HasJoinedAdvice：返回管理员指定的 ProfileResult
```

### 7.3 已知边界

* **需要 26.x 这类未混淆的服务端类名**（26.3 已实测）。1.20.x 及更早版本的 `net.minecraft.*` 是混淆的，
  无法按名字挂钩 → 离线名单**退化为"仅服务端放行"**：只有能自行完成会话上报的客户端
  （authlib-injector 外置登录 / 打过补丁的客户端）才进得来。
  判断方法：玩家连接时看日志有没有 `[HyAuth] 命中登录握手包: …ClientboundHelloPacket…`。
* **名字即凭证**：名单内玩家谁先连上谁就拿到那个 UUID；同名并发登录会共享同一 UUID（数据冲突风险）。
  → **只登记你自己掌控的名字**。
* 这类玩家没有正版会话 ⇒ **没有聊天签名**（客户端会显示"聊天不安全"，不影响进服），默认也没有皮肤。
* 单人/局域网世界（`isSingleplayer()`）本身就会离线放行，不属于本功能范围。

---

## 八、工作原理

### 8.1 分流流程

```text
            玩家连接 → 服务端调用 SessionService#hasJoinedServer
                                  │
                    ┌─────────────┴─────────────┐
                    ▼                           ▼
        【offline_players 命中】          【其它玩家】
                    │                           │
        直接返回指定 UUID/名字        ┌──────────┴──────────┐
        （无任何外部请求）            ▼                     ▼
                            【LittleSkin 名单】     【不在名单】
                                      │                    │
                        GET {api_root}/sessionserver/   立即返回 null，
                        session/minecraft/hasJoined     原方法照常执行
                                      │                 （Mojang 官方校验）
                        ┌─────────────┴─────────────┐
                        ▼                           ▼
                 200 + 验签通过               204 / 网络异常 / 验签失败
                        │                           │
              用 LittleSkin 的 UUID、        抛 AuthenticationUnavailableException
              名称、已验签属性替换返回值      → 服务端断开（不降级回退 Mojang）
```

### 8.2 Authlib 兼容矩阵

| Authlib / Minecraft | 实现类 | 方法签名 | 返回值 | Agent 处理 |
| --- | --- | --- | --- | --- |
| 3.x ~ 5.x（MC ≤ 1.20.4） | `…yggdrasil.YggdrasilMinecraftSessionService` | `hasJoinedServer(GameProfile, String, InetAddress)` | `GameProfile` | 构造 `GameProfile` 并直接返回 |
| 6.x ~ 9.x（MC 1.20.5 ~ 1.21.x） | `…yggdrasil.YggdrasilMinecraftSessionService` | `hasJoinedServer(String, String, InetAddress)` | `…yggdrasil.ProfileResult` | 反射构造 `ProfileResult(GameProfile)` |
| **10.x（MC 26.x，含 26.3）** | **`com.mojang.authlib.services.MinecraftServicesSessionService`** | `hasJoinedServer(String, String, InetAddress)` | **`…services.ProfileResult`** | `GameProfile` 变成 record＋属性需构造时传入不可变 `PropertyMap`，需专门适配 |

* 目标类**不写死类名**，而是匹配「`com.mojang.authlib.` 包前缀 + 声明了 `hasJoinedServer`(3 参数) + 非接口」，
  以后版本改名也能命中；
* 切面统一用 `Object` 接收参数/返回值，并注册**宽松赋值器**（允许 Byte Buddy 对引用类型插入 `CHECKCAST`），
  因此同一份字节码可适配上述所有签名；
* 三种 `GameProfile` / `PropertyMap` / `PropertyResult` 的构造差异全部由反射吃掉（见 `AuthlibProfileFactory`）。

### 8.3 原版 Bundler 与 `LoaderBridge`

```text
-javaagent 的类（system 加载器）          服务端类（Bundler 的 URLClassLoader，父=platform）
        │                                              │
        │  ① 命中目标类时反射调用 defineClass            │
        └──────────► 把 com.hyauth.agent.* 注入 ────────►│
                                                       │
   ② 切面内联进 authlib 类，与本 Agent 辅助类同源，可互相调用；
      两侧都能看到服务端自带的 gson / authlib，不会 NoClassDefFoundError。
```

### 8.4 切面类字节码的硬性约束（必读）

**切面类（`HasJoinedAdvice` 等）的字节码里，不允许出现任何 authlib 类型** —— 包括
方法签名、`throws`、`new`、`instanceof`、`X.class`，**一个都不行**。

原因（这是 v1.0.1 修掉的一个致命 bug，代价是"服务端一切正常但名单完全不生效"）：

1. Byte Buddy 的 `Advice.to(切面类)` 会反射切面类（`Class#getDeclaredMethods()`），
   而反射会触发 JVM 对切面类做**类校验**，校验时会解析**该类字节码直接引用到的所有类型**；
2. 切面类由 `-javaagent` 加载，属于 **system（应用）加载器**；而 Bundler 把
   authlib 放在**子加载器**里 —— 应用加载器看不见它；
3. 于是只要切面类字节码里出现 `new AuthenticationUnavailableException(...)`（哪怕
   `throws` 已去掉），挂载阶段就会：

   ```text
   [Byte Buddy] ERROR com.mojang.authlib.services.MinecraftServicesSessionService …
   java.lang.NoClassDefFoundError: com/mojang/authlib/exceptions/AuthenticationUnavailableException
       at net.bytebuddy.asm.Advice.to(Advice.java:367)
   ```

   结果是**目标类完全不被改写**：服务端照常开服、正版玩家照常能进，但名单一个都不生效。

**实测边界**（`verify\loaderiso` 用两个探针类验证过，可以照此判断改动是否安全）：

| 切面类的写法 | 结果 |
| --- | --- |
| 自己字节码里 `new X` / `instanceof X` / `X.class`（X 在当前加载器不可见） | ❌ 挂载失败 |
| 自己字节码里只**调用**别的类（哪怕那个类内部引用了 authlib / gson） | ✅ 安全 |

所以正确的做法是：

* 需要 authlib 类型的动作（构造异常）→ 走 `com.hyauth.agent.util.AuthRejection` 这种
  **纯 JDK 反射中转**，真正的类型解析推迟到运行期（那时字节码已内联进目标类，由目标类的加载器解析）；
* 需要 authlib 类型的业务逻辑 → 放在**别的类**里（如 `AuthlibProfileFactory`）被切面调用即可；
* 改完切面类**务必跑一遍** `pwsh -File verify\loaderiso.ps1`。

### 8.5 外置账号的聊天签名密钥（`relax_chat_keys`）

**现象**：LittleSkin 玩家鉴权明明成功、也进到了游戏，却在 1 秒内被踢：

```text
[HyAuth] 玩家 SUNxiaohaohao 通过 LittleSkin 鉴权成功！
[Server thread/ERROR]: Failed to validate profile key
net.minecraft.world.entity.player.ProfilePublicKey$ValidationException: Invalid signature for profile public key.
[Server thread/INFO]: SUNxiaohaohao lost connection: Invalid signature for profile public key.
```

**机制**（全部来自 26.3 真实字节码，客户端与服务端两侧都核对过）：

| 步骤 | 位置 | 关键字节码 / 结论 |
| --- | --- | --- |
| 1. 客户端决定生成并上报聊天密钥 | 客户端 `ClientPacketListener.handleLogin` | `if (packet.onlineMode()) prepareKeyPair();` —— **只看 `onlineMode`**（即 `online-mode=true`），进服后 `setKeyPair` 无条件发送 `ServerboundChatSessionUpdatePacket` |
| 2. 服务端校验该密钥的签名 | 服务端 `ServerGamePacketListenerImpl.handleChatSessionUpdate` | 取 `Services#profileKeySignatureValidator()`（**Mojang 服务密钥**）校验；失败 → `disconnect(...)` **踢人** |
| 3. 外置账号必然失败 | — | LittleSkin 账号的密钥不是 Mojang 签的，签名永远对不上 |
| 4. 这条路**不看** `enforce-secure-profile` | 同上 + `performUnsignedChatCommand` | `enforce-secure-profile` 只影响"无签名指令"的拦截，**治不了这个踢人** |

**修复（三级桥接验签）**：本 Agent 挂上 `net.minecraft.server.Services#profileKeySignatureValidator()`，
把返回值换成 `ChatKeyBridge` 的桥接校验器 —— **能验就真验，验不了才放宽**：

```text
 玩家上报聊天密钥
        │
        ├─ 第 1 级  Mojang 官方校验器（原版那一套）        ── 通过 → 放行
        │                                                   （正版账号走这级，零额外网络请求）
        ├─ 第 2 级  LittleSkin 公钥（signaturePublickey，
        │          与属性验签同一把）依次尝试
        │          SHA1withRSA / SHA256withRSA / SHA512withRSA ── 通过 → 放行
        │                                                   （说明该密钥确由皮肤站签发）
        └─ 第 3 级  由 chat_key_strict 决定
                   false（默认）→ 放行，并在日志里说明
                   true         → 拒绝该密钥（等同原版严格行为）
```

* 外置账号正常进入，聊天链路照常建立，**不会再被踢**；
* 该校验器在 26.3 全 jar 中**只被这一处调用**（已做字节码扫描确认），不影响登录/皮肤等其它功能；
* **每一级的命中都会打印一次日志**，所以"LittleSkin 到底用哪个算法签的"可以直接在真机上观测：

  ```text
  [HyAuth] 聊天密钥桥接：Mojang 官方验签通过（正版账号，走原版校验）。
  [HyAuth] 聊天密钥桥接：皮肤站公钥验签通过（算法 SHA1withRSA）—— 外置账号的密钥确实是皮肤站签发的。
  [HyAuth] 聊天密钥桥接：Mojang 与皮肤站公钥均未通过验签，按 relax 策略放行（想改为拒绝请设 chat_key_strict=true）。
  ```

* 配置开关：
  * `relax_chat_keys: false` → 完全不介入，恢复原版严格校验（外置账号会重新被踢）；
  * `chat_key_strict: true` → 三级都验不过时拒绝。**建议先在日志里看到"皮肤站公钥验签通过"再打开**，
    否则会把验不出来的外置账号又踢下去。

> **实测结论（用户真实服务器）**：正版账号命中第 1 级（`Mojang 官方验签通过`），
> LittleSkin 账号命中第 2 级（`皮肤站公钥验签通过（算法 SHA1withRSA）`）——
> 也就是说 LittleSkin **确实用 `signaturePublickey` 签发了玩家证书**，这一层是真实验签。
> 但**仍建议保持 `chat_key_strict=false`（默认）**：离线名单玩家的客户端有可能自行生成并上报
> 一把谁也验不了的密钥（真机上出现过），严格模式会把这类玩家重新踢下线；
> 而他们本来就走"按玩家豁免 + 未签名聊天"这条路，不需要靠内层校验放行。

> 顺带的结论：网上常见的"把 `enforce-secure-profile` 设成 false"**并不能**解决这个踢人 ——
> 它只解决"无签名指令被拒"（`Received unsigned command packet ... but the command requires signable arguments`）。
> 两者可以一起用，但本 Agent 的聊天密钥桥接才是针对踢人的那一刀。

### 8.6 离线名单玩家发不出指令（missing profile public key）

**现象**：离线名单玩家能进服、能发普通聊天，但 `/say`、`/me`、`/msg`、`/tell`、`/w`、`/teammsg`
这类指令发出去没反应，控制台一条 WARN：

```text
[Server thread/WARN]: Failed to update secure chat state for azxt: 'Chat disabled due to missing profile public key. Please try reconnecting.'
```

**机制**（26.3 真实字节码，两侧都核对过）：

| 步骤 | 位置 | 关键结论 |
| --- | --- | --- |
| 1. 客户端决定用不用签名版指令包 | 客户端 `ClientPacketListener.sendCommand` | 只看指令**有没有可签名实参**：有 → 无条件发 `ServerboundChatCommandSignedPacket`；没有（`/list`、`/tp`…）→ 发未签名的 `ServerboundChatCommandPacket`。**既不看自己有没有聊天公钥，也不看服务端开没开 `enforce-secure-profile`** |
| 2. 服务端解码实参签名 | 服务端 `ServerGamePacketListenerImpl.collectSignedArguments` | 实参签名列表非空 → 逐条解码签名链 → 该玩家没有聊天公钥 → `DecodeException(chat.disabled.missingProfileKey)` → 红字 + 指令丢弃 |
| 3. 原版自带的后门 | 同上第一行 | `if (entries.isEmpty()) return collectUnsignedArguments(arguments);` —— 没有实参签名时**本来就会按未签名执行** |

也就是说：这不是本 Agent 引入的问题，而是"没有聊天公钥的玩家 + 带实参指令"的原版限制
（普通聊天走的是未签名路径，不受影响；所以 `azxt` 的普通发言是正常的）。

**修复（必须做的那一步在客户端，服务端切面替代不了）**

**① 必做：`server.properties` 里 `enforce-secure-profile=false`（改完要重启）**

真正的拦路虎在**客户端**（26.3 真实字节码）：

```java
// net.minecraft.client.multiplayer.PlayerInfo
private static SignedMessageValidator fallbackMessageValidator(boolean enforceSecureProfile) {
    return enforceSecureProfile ? SignedMessageValidator.REJECT_ALL   // 丢弃一切
                                : SignedMessageValidator.ACCEPT_UNSIGNED;
}
// SignedMessageValidator.REJECT_ALL：
LOGGER.error("Received chat message from {}, but they have no chat session initialized and secure chat is enforced");
return null;    // → 聊天框不显示该消息，并弹出红字 chat.validation_error（“聊天验证错误”）
```

* 外置账号的密钥不是 Mojang 签的、离线名单玩家根本没有密钥 ⇒ **每个客户端**都认为他们
  "没有可信的聊天会话"；
* 这时客户端用 `REJECT_ALL` 还是 `ACCEPT_UNSIGNED`，取决于登录包里的 `enforcesSecureChat`
  （= 服务端这个开关）。**这是各客户端自己做的判断，服务端切面无法干预** ——
  所以会出现"服务端日志明明广播成功了，玩家自己却看不到、还弹红字"的现象；
* 改成 `false` 后客户端改用 `ACCEPT_UNSIGNED`，验证不了签名的消息照常显示（标注 `[Not Secure]`）；
  **正版玩家的 Mozilla 签名消息不受影响**，仍然显示为安全聊天。
* **v1.0.2 起**：**外置（LittleSkin）账号的普通聊天已经不走这条路了** —— Agent 会把他们的消息
  按接收者改成原版「伪装聊天」（见 [§8.7](#87-交替发言必被踢--外置玩家自己看不到自己发的消息)），
  客户端不做签名校验，因此**不受这个开关影响**；
  **离线名单玩家仍然需要 `enforce-secure-profile=false`**（他们发出去的就是未签名聊天包）。

```properties
# server.properties
enforce-secure-profile=false
```

> Agent 启动时会直接把这件事喊出来（名单非空且该开关为 true 时）：

```text
[HyAuth] 重要提示：server.properties 的 enforce-secure-profile 是 true（或未设置），而名单里有外置/离线玩家（LightSkin N 人，离线 M 人）。
[HyAuth]   外置（LittleSkin）账号：本版把他们的聊天按「逐接收者未签名（伪装聊天）」广播，客户端不再走签名校验 ⇒ 不受本开关影响；作者本人收到的仍是原版签名消息（自己看得到自己发的话）。
[HyAuth]   离线名单玩家：他们没有聊天会话，客户端在 enforce-secure-profile=true 时用 REJECT_ALL 逐条丢弃他们的消息并弹红字「聊天验证错误」（连自己看自己发的话也一样）。
[HyAuth]   该判断由每个客户端按登录包里的 enforcesSecureChat（= 本开关）决定，服务端切面无法干预。
[HyAuth]   解决：server.properties 改成 enforce-secure-profile=false 并重启服务端。正版玩家的签名聊天不受影响。
```

**② Agent 侧已做的配套（没有 ① 时它们只能保证"服务端收下并广播"）**

| 切面 | 作用 |
| --- | --- |
| `Decoder.unsigned` 按玩家豁免 | 服务端不为离线名单玩家校验聊天密钥（否则他们的消息在服务端就被 `DecodeException` 丢掉） |
| `collectSignedArguments` 回退 | 带可签名实参的指令（`/say`、`/me`、`/msg`…）按未签名执行 |
| `Services#profileKeySignatureValidator` 三级桥接 | 外置账号（LittleSkin）的密钥做**真实验签**，不再被踢下线 |
| `sendPlayerChatMessage` 逐接收者分流 | 外置账号的聊天发给别人时改成原版伪装聊天（客户端不校验、不进签名账本），发给作者本人时保持原版签名消息 |

配置项 `offline_chat_exempt` / `bypass_signed_commands`（都默认 true）可分别关掉前两项。

### 8.7 交替发言必被踢 / 外置玩家"自己看不到自己发的消息"

**现象**：外置玩家与正版玩家**交替发言**时，*下一个*发言的人被踢：
`_xxx lost connection: Chat message validation failure`。**与客户端时钟无关**
（没有 `Received expired chat` 也会发生）。

**机制**（真机客户端日志 + 26.3 字节码，两层都有证据）：

```text
[ERROR] Failed to validate profile key for player: 'SUNxiaohaohao'
net.minecraft.world.entity.player.ProfilePublicKey$ValidationException: 无效的玩家档案公钥签名。
    at RemoteChatSession$Data.validate(RemoteChatSession.java:41)
    at ClientPacketListener.initializeChatSession(ClientPacketListener.java:2075)
```

1. 外置账号的聊天密钥由**皮肤站**签发 → 别人的（正版）客户端验不过 →
   **清空该玩家的聊天会话**，改用 `SignedMessageValidator.ACCEPT_UNSIGNED`：
   **它不再接收该玩家"带签名"的消息**，也就不把签名记进自己的 last-seen 账本；
2. 服务端却照旧把签名记进"我发给这个客户端哪些签名"的账本（`LastSeenMessagesValidator`）；
3. 两边账本不一致 → **该客户端下一次发言**的校验和对不上 →
   `Checksum mismatch on last seen update: the client and server must have desynced`
   → 服务端把它踢下线（`聊天消息验证失败`，原版要求重连重新对齐）。

**修复（v1.0.2 起：按接收者分流，作者本人保留签名消息）**：切在
`ServerGamePacketListenerImpl#sendPlayerChatMessage(PlayerChatMessage, ChatType.Bound)`
—— 这是原版**每个接收者各调用一次**的发送方法（调用链：
`PlayerList#broadcastChatMessage` → `ServerPlayer#sendChatMessage`
→ `OutgoingChatMessage.Player#sendToPlayer` → 本方法，26.3 原版源码已核对）：

```text
   发给作者本人 → 原样放行（原版「带签名」消息）
                  · 客户端用自己的公钥验签通过 ⇒ 自己看得到自己发的话
                  · 服务端账本与客户端记录一致 ⇒ 不会被踢
   发给其他人   → 改用原版自带的「伪装聊天」sendDisguisedChatMessage(Component, ChatType.Bound)
                  · 客户端直接上屏，完全不走签名校验 ⇒ 谁都不会弹「聊天验证错误」
                  · 服务端不再记"已发送签名" ⇒ 与客户端记录天然一致，不会被踢
```

这正是原版自己对「系统来源的玩家消息」用的那条管线（`OutgoingChatMessage.create()` 里
`message.isSystem()` 分支就走 `Disguised`），所以显示效果与原版聊天一致
（服务端只送内容，客户端照旧用 `ChatType.Bound` 拼出 `<玩家名> 内容`）。

```text
[HyAuth] 命中玩家指令/聊天监听器: net.minecraft.server.network.ServerGamePacketListenerImpl，挂载「无公钥指令回退」与「外置账号逐接收者未签名广播」切面。
[HyAuth] 外置账号的聊天改为「逐接收者未签名（伪装聊天）」广播：作者本人仍收到原版签名消息（所以自己看得到自己发的话），其他人收到不参与签名账本的消息（既不弹「聊天验证错误」，也不会因记账不一致被踢）。
```

* 配置项 `unsigned_external_chat`（默认 **true**，可关）；
* 配置项 `unsigned_all_chat`（默认 **false**）：设为 `true` 后，**正版玩家的消息发给别人时也走同一条**
  伪装聊天管线（作者本人依旧保留原版签名消息）。因为不再产生"未签名玩家聊天包"，
  v1.0.1 里 `true` 会导致的"正版↔正版聊天验证错误"已经不存在了；
  代价是这些消息不再携带聊天签名（身份仍在**登录时**验证过）。
* **非玩家来源**（控制台 `/say`、命令源等）走的本来就是伪装聊天，不经过本切面；
* 任何反射失败都原样放行（fail-safe）并打印一次原因；
* 发送方法声明在监听器子类还是父类都能接管（`ServerGamePacketListenerImpl` 与
  `ServerCommonPacketListenerImpl` 一起匹配，未声明的那边是空操作）。

---

## 九、安全设计

1. **失败关闭（fail-closed）**：名单内玩家校验失败 → 中断登录；**绝不回退 Mojang**，避免同名正版账号被冒用。
2. **属性强制验签**：`properties`（皮肤/披风）必须用验证服务器公钥通过 `SHA1withRSA` 验签才采用；
   未签名或验签失败的一律丢弃（玩家仍可进入，但看不到该属性）。
3. **角色名一致性校验**：LittleSkin 返回的 `name` 必须与请求名一致（忽略大小写），否则拒绝，防返回他人资料。
4. **正版玩家零额外开销**：不在任何名单内的玩家在切面入口立即返回，不产生任何外部请求。
5. **公钥缓存 + 30 s 退避**：公钥只取一次并缓存；取不到时退避重试，避免验证服务器故障时放大请求。
6. **离线名单最小授权**：必须显式登记"名字 + UUID"才放行，且不依赖任何外部服务；名单外的玩家不受影响。
7. **登录握手上下文一次性**：`ThreadLocal` 在 `handleHello` 退出时无条件清理，不会串到别的连接。

---

## 十、与《项目文档》的差异与修正

项目文档里的示例代码有多处**无法直接运行**或**在新版本上完全失效**，本实现按"行为一致、可实际运行"的原则修正：

| # | 文档写法 | 本实现 | 原因 |
| --- | --- | --- | --- |
| 1 | `ElementMatchers.takeArguments(3)` | `ElementMatchers.takesArguments(3)` | Byte Buddy 无 `takeArguments` 方法，文档代码**编译不过** |
| 2 | 只拦截 `GameProfile hasJoinedServer(GameProfile, …)` | 同时兼容 `ProfileResult hasJoinedServer(String, …)` | Authlib ≥ 6.x 参数与返回值均已改变；按文档写法在 1.20.5+ 上**完全不生效** |
| 3 | 硬编码 `LITTLESKIN_PUBLIC_KEY_PEM`（文档中是占位符 `加上LittleSkin完整的Base64公钥字符串...`） | 自动从 `api_root` 的 `signaturePublickey` 获取，也可用 `public_key` 固定 | 占位符不是合法 Base64，静态初始化必然失败；动态获取还能跟随公钥轮换 |
| 4 | `new FileWriter(file, StandardCharsets.UTF_8)` / `FileReader(...)` | `OutputStreamWriter` / `InputStreamReader` | 带 Charset 的 `FileWriter`/`FileReader` 构造器是 Java 11+ API，与 Java 8 目标冲突 |
| 5 | 失败时返回"空 ID Profile"再由 `OnMethodExit` 置空 | 失败时抛 `AuthenticationUnavailableException` | 语义一致（拒绝且不回退 Mojang），但不依赖 skip/exit 执行顺序，且 `ProfileResult` 无法用"空 ID"表示 |
| 6 | Byte Buddy `1.14.12` | `1.17.5`（`-Dbyte-buddy.version=` 可覆盖） | 1.14.12 在 Java 25 下抛 `Java 25 (69) is not supported … supports Java 22 (66)`，切面彻底失效 |
| 7 | `@Advice.Return(readOnly = false) GameProfile result` | `Object` 返回值 + 宽松 `Assigner` | 默认赋值器对 `Object → GameProfile/ProfileResult` 抛 `Cannot assign class java.lang.Object to …` |
| 8 | 配置仅 `littleskin_players` | 追加 `offline_players` / `api_root` / `public_key` / 超时 / `debug` | 支持离线名单、私有部署与排障 |
| 9 | 热重载原地 `clear()` + `addAll()` | volatile 引用整体替换 | 避免重载瞬间出现"空名单窗口" |
| 10 | 未处理 HTTP 204、无名称校验 | 204 视为未登录；校验角色名一致 | 符合 Yggdrasil 规范并防冒用 |
| 11 | 仅 `premain` | 追加 `agentmain` | 支持运行期动态 attach 排障 |
| 12 | 写死目标类名 | 按包前缀 + 方法签名匹配，并适配 authlib 10.x | MC 26.3 的 authlib 10.0.77 已不存在 `yggdrasil` 包，按文档写法**完全不会命中** |
| 13 | 未考虑原版 Bundler | `LoaderBridge` 向服务端类加载器注入 Agent 类 | 1.18+ 的 `server.jar` 是 Bundler，否则切面内联调用会在登录时报 `NoClassDefFoundError` |
| 14 | 无离线名单能力 | 新增 `offline_players` + 登录握手改写（`LoginNameAdvice` / `HelloPacketAdvice`） | 让纯离线客户端在 `online-mode=true` 下也能按管理员 UUID 进入 |

---

## 十一、自检（可选）

`verify/` 内置一套**可离线运行**的端到端自检：本地起一个模拟 LittleSkin 服务，用真实 `-javaagent` 挂载切面，
分别在「模拟 + 真实」Authlib 的新旧三种签名下验证 6 类场景。

```powershell
# 在项目根目录
pwsh -File verify\verify.ps1

# 指定 JDK（默认用文档中的 Java 25 运行时，找不到则用 PATH 上的 java）
pwsh -File verify\verify.ps1 -JavaHome "C:\Program Files\Java\jdk-21"
```

脚本会自动：下载自检依赖（authlib 3/6/10、gson、guava、slf4j、commons-lang3/io）→ 编译脚手架 →
依次运行 5 个模式 + 1 个热重载用例 → 打印汇总，全部通过时退出码为 0。

断言覆盖：

| 场景 | 期望 |
| --- | --- |
| 非名单玩家 | 透传原版逻辑，**外部请求数为 0** |
| LittleSkin 名单玩家且登录有效 | 用 LittleSkin 的 UUID/名称/已验签属性替换返回值 |
| LittleSkin 名单玩家但会话失效（204） | 抛 `AuthenticationUnavailableException`，不回退原版 |
| 属性签名被篡改 / 未签名 | 属性被丢弃，玩家本体仍可登录 |
| 离线名单玩家 | 返回管理员指定 UUID，名字统一为管理员写法，**不请求 LittleSkin** |
| 热重载 | 运行中新增白名单玩家后立即生效 |

最近一次结果（Java 25.0.1，Byte Buddy 1.17.5）：

```text
mock-modern   22/22 断言通过      mock-legacy   22/22 断言通过
real-modern   20/20 断言通过      real-legacy   20/20 断言通过
real-10       20/20 断言通过      hot-reload    通过
```

#### 11.1 外置账号聊天广播回归测试（**改聊天切面必跑**）

```powershell
pwsh -File verify\chat.ps1
```

它复现 26.3 的真实发送形状
（`PlayerList#broadcastChatMessage` → `ServerPlayer#sendChatMessage`
→ `OutgoingChatMessage.Player#sendToPlayer` → `ServerGamePacketListenerImpl#sendPlayerChatMessage`），
把 Agent 真正的切面 `ExternalChatAdvice` 用 Byte Buddy 内联进替身类，断言：

```text
[PASS] 外置玩家发给别人：改用原版伪装聊天（不参与签名账本）
[PASS] 外置玩家自己那一份：保持原版签名消息（所以自己看得到、不会弹聊天验证错误）
[PASS] 外置玩家发给另一个外置玩家：也是伪装聊天（对方客户端不再拒收）
[PASS] 正版玩家：聊天完全不动（签名链路照旧）
[PASS] unsigned_all_chat=true：正版玩家发给别人也走伪装聊天 / 作者本人依旧是原版签名消息
[PASS] unsigned_external_chat=false：完全不干预（恢复原版）
[PASS] 发送者查不到时按原版处理（fail-safe，不误伤）
[PASS] 旧版取名字 + 玩家表没有 getPlayer(UUID)：仍能识别外置账号
[PASS] 发送方法声明在父类、实例是子类：同样生效
```

#### 11.2 类加载器隔离回归测试（**改切面必跑**）

上面那套自检把 authlib 放在应用类路径上，因此**发现不了** Bundler 类加载器隔离导致的切面失效
（v1.0.1 就是这么漏掉一个致命 bug 的，见 [§8.4](#84-切面类字节码的硬性约束必读)）。
这个脚本专门还原真实拓扑：

```powershell
pwsh -File verify\loaderiso.ps1
```

它把 authlib **只**放进「父加载器为 platform 的 URLClassLoader」，Agent 与 Byte Buddy 留在应用加载器，
然后断言：切面能挂上、能在子加载器里完成内联注入、离线名单玩家被接管且 UUID 等于配置值、
非名单玩家透传原方法。

```text
[DIAG] loaderiso.MissingTypeUser getDeclaredMethods() 失败: NoClassDefFoundError: …AuthenticationUnavailableException
[DIAG] loaderiso.Caller getDeclaredMethods() 成功（本类不引用缺失类型，只调用上面那个类）
[PASS] 前提：应用加载器看不到 authlib（复现 Bundler 隔离）
[PASS] 阶段一：Advice.to(Agent 加载器中的 HasJoinedAdvice) 不抛异常
[PASS] 阶段二：在 platform 父加载器下完成切面内联并注入
[PASS] 离线名单玩家被切面接管，未落入原方法（返回 com.mojang.authlib.services.ProfileResult）
[PASS] 返回的 UUID 等于配置中的管理员指定值
[PASS] 非名单玩家原样透传（返回 ORIGINAL）
[loaderiso] 全部通过
```

`verify\loaderiso\MissingTypeUser.java` 与 `Caller.java` 是两个诊断探针，用来固化
[§8.4](#84-切面类字节码的硬性约束必读) 那条实测边界（"自己引用" vs "只是调用"）。

此外还有两项**真实服务端**验证：

1. 官方 26.3 `server.jar` + Agent 开服 → `Done (0.714s)!`，命中 `MinecraftServicesSessionService`，
   12 个 Agent 类注入成功，并打印
   `已改写目标类字节码: com.mojang.authlib.services.MinecraftServicesSessionService`（**无任何 `[Byte Buddy] ERROR`**）；
2. 真实 26.3 类上的握手改写 → 名单内 `shouldAuthenticate=false`、名单外保持 `true`、`ThreadLocal` 已清理。

---

## 十二、排错手册

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 启动日志出现 `FATAL ERROR in native method: processing of -javaagent failed` + `NoClassDefFoundError: com/google/gson/...` | `-javaagent` 指向的不是构建产物（或 jar 损坏） | 用 `target/HyAuth-Agent-1.0.0.jar`；重新 `mvn clean package` |
| 启动日志出现 `[HyAuth] 当前阶段无法初始化配置（原版 Bundler 启动的正常现象）` | Bundler 环境下 premain 阶段看不到 gson（**预期行为**） | 无需处理；注入完成后会自动重新初始化配置 |
| 开服正常，但玩家进不来，且**从未**出现 `[HyAuth] 命中目标类` | 目标类没被加载 / 类名与该版本不符 | 对照[兼容矩阵](#82-authlib-兼容矩阵)；查看是否有 `[Byte Buddy] ERROR` |
| 开服正常、有 `命中目标类`，但**没有** `已改写目标类字节码`（或有 `[Byte Buddy] ERROR: NoClassDefFoundError: com/mojang/authlib/…`） | **切面没挂上，Agent 静默失效**：切面类字节码里引用了 authlib，而 Bundler 环境下 system 加载器看不到 authlib | 升级到修复版 jar；改切面代码时遵守 [§8.4](#84-切面类字节码的硬性约束必读)，并跑 `pwsh -File verify\loaderiso.ps1` |
| 出现 `[Byte Buddy] ERROR …` | 切面挂载失败（版本不匹配/被其它 Agent 干扰） | 把该段报错发出来；确认没有同时挂载其它字节码 Agent |
| 玩家登录直接失败，日志里**看不到任何 `[HyAuth]`** | Agent 类没有注入成功——最常见是缺 `--add-opens java.base/java.lang` | 补上该启动参数 |
| `-bash: ./start.sh: Permission denied` | 复制/解压后丢了执行位 | `chmod +x start.sh` |
| `./start.sh: /usr/bin/env: bad interpreter` 或 `$'\r': command not found` | 脚本被存成了 Windows 换行（CRLF） | `sed -i 's/\r$//' start.sh`，或 `dos2unix start.sh` |
| 一关 SSH 服务端就跟着退出 | 没放进 `screen`/`tmux`/`nohup` | 见 [§5.5 ④](#55-linux--macosstartsh-完整步骤) |
| 找不到 `littleskin_config.json` | 启动时的工作目录不是服务端根目录 | 用 `start.sh`（会自己 `cd`），或先 `cd` 到 `server.jar` 所在目录再启动 |
| `Could not reserve enough space for object heap` | 机器内存撑不住 `-Xmx8G` | `MIN_RAM=1G MAX_RAM=3G ./start.sh` |
| `java: command not found` / 提示主版本过低 | `PATH` 里没有 Java 25 | `JAVA_PATH=/usr/lib/jvm/java-25-openjdk/bin/java ./start.sh` |
| LittleSkin 玩家进不来，日志 `玩家 X 在名单中，但 LittleSkin 鉴权失败/未登录！` | 玩家没有有效会话（没在启动器里用 LittleSkin 登录，或 serverId 不匹配） | 让玩家重新登录外置账号；开 `debug` 看是否返回 204 |
| 能进服但**没有皮肤** | 公钥取不到，或属性验签失败 | 检查 `api_root` 是否可访问；日志会打印 `未能获取 LittleSkin 验签公钥 …` 或 `属性 textures 签名校验未通过，已丢弃`；可在配置里写死 `public_key` |
| 离线名单玩家仍进不来，且日志**没有** `命中登录握手包: …ClientboundHelloPacket…` | 服务端类被混淆（1.20.x 及更早）或该版本没有 `shouldAuthenticate` 字段 | 升级到 26.x，或接受"仅服务端放行"（客户端需能完成会话上报） |
| 离线名单玩家进服后聊天显示"不安全" | 无正版会话 ⇒ 无聊天签名 | 预期行为；如确需关闭提示，可在 `server.properties` 设 `enforce-secure-profile=false`（请自行评估影响） |
| **LittleSkin 玩家鉴权成功、刚进服就被踢**：`Failed to validate profile key` / `Invalid signature for profile public key` | 外置账号的聊天密钥不是 Mojang 签的，服务端严格校验必然失败 | 用 `relax_chat_keys: true`（默认开启）的 jar；若被手动关掉请改回。见 [§8.5](#85-外置账号的聊天签名密钥-relax_chat_keys) |
| 名单玩家发消息后**自己聊天框不显示**，并弹红字「聊天验证错误」；服务端日志里却能看到该消息 | ① **外置（LittleSkin）账号**：v1.0.1 把消息整条换成"未签名"，而作者自己的客户端用的是"链校验器"（它验得过自己那把皮肤站密钥）→ 拒收未签名消息；② **离线名单玩家**：客户端对"没有可信聊天会话"的玩家使用 `REJECT_ALL`（由登录包 `enforcesSecureChat` 决定），逐条丢弃 | ① 用 v1.0.2 及以后的 jar（外置账号改为「逐接收者分流」：作者本人收到原版签名消息，其他人收到伪装聊天），见 [§8.7](#87-交替发言必被踢--外置玩家自己看不到自己发的消息)；② 离线名单玩家仍需 `server.properties` 的 `enforce-secure-profile=false` + 重启（服务端切面干预不了这一步），见 [§8.6](#86-离线名单玩家发不出指令missing-profile-public-key) |
| 外置账号皮肤显示**默认皮肤**，客户端日志 `Discarding incorrectly sized (128x128) skin texture from https://…` | **原版客户端只接受 64×64 / 64×32 的普通皮肤**，皮肤站上的"高清皮肤"（128×128 及以上）会被直接丢弃（`SkinTextureDownloader.processLegacySkin`）。此时服务端链路是**正常**的：客户端已拿到属性并成功下载了图片 | ① 让玩家在皮肤站换成 **64×64 普通皮肤**（0 成本，立刻全员可见）；② 想保留高清皮肤 → 客户端装 CustomSkinLoader（支持 HD 皮肤）。见下方"皮肤链路"说明 |
| `玩家 X 通过 LittleSkin 鉴权成功！资料属性: 皮肤 https://… （签名有）` | 皮肤数据已由皮肤站下发并通过验签，**服务端侧没问题** | 客户端日志里搜 `Discarding incorrectly sized`（尺寸问题）/ 能否打开该链接（网络） |
| `Could not authorize you against Realms server … HTTP 401` | 外置账号本来就没有 Mojang 权益 | 与本 Agent 无关，可忽略 |
| 离线名单玩家没有皮肤 | 他们没有任何皮肤来源 | 预期行为（如需皮肤，需在客户端装 CSL 之类，并给角色指定皮肤） |
| `玩家 X 通过 LittleSkin 鉴权成功！资料属性: 没有 textures 属性…` | 皮肤站没返回皮肤属性（该角色就是默认皮肤） | 去皮肤站上传皮肤；这不是 Agent 的问题 |
| 正版玩家没有皮肤 | 正版皮肤由 Mojang 下发，Agent 不干预 | 与本 Agent 无关 |
| 离线名单玩家聊天在服务端被拒（日志 `Failed to update secure chat state … missing profile public key`） | 服务端未签名解码器拒绝 | 用本版 jar（`offline_chat_exempt` 默认 true） |
| 离线名单玩家 `/say`、`/me`、`/msg` 发不出去（聊天正常） | 客户端对"带实参指令"一定发签名版指令包 | 用 `bypass_signed_commands: true`（默认开启）的 jar；见 [§8.6](#86-离线名单玩家发不出指令missing-profile-public-key) |
| `[Server thread/ERROR]: Couldn't parse player advancements in ./world/players/advancements/<uuid>.json` + `MalformedJsonException` | **与 Agent 无关**：该玩家的进度文件是坏 JSON（多由旧版本数据、硬杀进程或手工编辑造成）；26.x 用严格 JSON 解析（`StrictJsonParser`），旧版容忍的写法现在会报错 | 备份后删除该文件（该玩家进度清零）或修好语法；下次保存会用新格式重写。参见下方 `Ignored advancement … doesn't exist anymore?`（同一原因：数据来自旧版本） |
| `Ignored advancement 'minecraft:xxx' in progress file … - it doesn't exist anymore?` | **与 Agent 无关**：进度文件记录了一个当前版本已移除的进度 | 无需处理 |
| `… lost connection: Timed out` / `Server empty for 60 seconds, pausing` | 客户端网络超时 / 无人时的空服暂停 | 无需处理 |
| 只想知道"名单到底生效没有" | — | 看这三行：`配置重载完成…人数` / `命中目标类` / 玩家登录时的 `通过 LittleSkin 鉴权成功` 或 `已按离线名单放行` |

---

## 十三、FAQ

**1. 正版玩家进服会有延迟或额外开销吗？**
没有。名单判定在切面入口完成，命中"非名单"后立即返回并放行原版逻辑，不发起任何外部请求。

**2. 正版玩家与外置玩家重名会冲突吗？**
服务端以 Yggdrasil 返回的 **UUID** 区分玩家，两者存档、背包、权限在底层完全隔离。
唯一例外是**离线名单**：那是"名字即凭证"的模式，见 [§7.3](#73-已知边界)。

**3. 可以直接改 UUID 来迁移老存档吗？**
可以。把玩家登记到 `offline_players` 并填入老存档使用的 UUID，该玩家登录后即落到同一份数据上
（名字大小写也会统一成配置里的写法）。

**4. 支持 Paper / Spigot / Fabric 吗？**
本项目只针对原版 `server.jar`。Paper 等实现会重写登录与鉴权流程，切面是否命中未做验证。

**5. `api_root` 可以指向自建皮肤站吗？**
可以，只要它遵循 [Yggdrasil 服务端技术规范](https://yushijinhun.github.io/authlib-injector/zh/Yggdrasil-%E6%9C%8D%E5%8A%A1%E7%AB%AF%E6%8A%80%E6%9C%AF%E8%A7%84%E8%8C%83.html)
（`GET /` 返回 `signaturePublickey`，`/sessionserver/session/minecraft/hasJoined` 可用）。

**6. 我改完名单需要重启吗？**
不需要，保存文件后约 0.5 秒生效。只有换 Agent jar 才需要重启。

---

## 十四、已知限制与后续可做

**已知限制**

* 离线名单的握手改写只实现了 26.x（未混淆类名）；更早版本需要额外映射。
* 离线名单玩家暂时没有皮肤注入能力（可后续在名单条目里加可选 `textures` 字段）。
* 未在 Paper/Spigot 等非原版服务端上验证。
* 未在真实客户端 + 公网服务端上做完整登录联调（见[第二节](#二已验证--未验证范围)）。

**后续可做**

1. `offline_players[].textures`：为离线玩家注入皮肤属性（可复用现有验签与构造链路）；
2. 更早版本的登录挂钩映射（1.20.5 ~ 1.21.x）；
3. 把 26.3 服务端开服验证与握手挂钩验证脚本化进 `verify/`；
4. 名单级限速/审计日志（谁在什么时间以哪个 UUID 登录）。

---

## 附录 A：日志速查

```text
==================================================
  HyAuth-Agent 内存鉴权分流代理已启动
  版本: 1.0.0
  Java: 25.0.1 (Microsoft)
==================================================
[HyAuth] 配置重载完成（JSON 配置加载成功），当前 LittleSkin 白名单人数: 2，离线名单人数: 1，API: https://littleskin.cn/api/yggdrasil
[HyAuth] 配置热监听已启动: /srv/mc
[HyAuth] 已成功挂载 Mojang Authlib 验证切面。
[HyAuth] 命中目标类: com.mojang.authlib.services.MinecraftServicesSessionService，开始挂载 hasJoinedServer 切面。
[HyAuth] 已把 12 个 Agent 辅助类注入服务端类加载器: java.net.URLClassLoader@58be6e8
[HyAuth] 已改写目标类字节码: com.mojang.authlib.services.MinecraftServicesSessionService（加载器: java.net.URLClassLoader@58be6e8）
[Server thread/INFO]: Done (0.714s)! For help, type "help"
```

> **判断 Agent 到底有没有生效，就看这一行**：`命中目标类` 之后必须跟着
> `已改写目标类字节码`。只有前者没有后者（或出现 `字节码改写失败` / `[Byte Buddy] ERROR`），
> 说明切面没挂上，名单不会生效——原因与解法见 [§8.4](#84-切面类字节码的硬性约束必读)。

玩家登录相关（按需出现）：

| 日志 | 含义 |
| --- | --- |
| `命中登录处理器: …ServerLoginPacketListenerImpl…` | 首次有玩家连接，登录挂钩已生效 |
| `命中登录握手包: …ClientboundHelloPacket…` | 离线名单握手改写已生效（26.x） |
| `匹配到 LittleSkin 白名单玩家: X，发起外置鉴权...` | 命中 LittleSkin 名单 |
| `已加载 LittleSkin 验签公钥，来源: …` | 公钥获取成功（首次或缓存失效后） |
| `玩家 X 通过 LittleSkin 鉴权成功！` | 外置鉴权通过，已替换返回值 |
| `玩家 X 在名单中，但 LittleSkin 鉴权失败/未登录！` | 已拒绝（不回退 Mojang） |
| `安全拦截：属性 textures 签名校验未通过，已丢弃。` | 属性验签失败，仅丢弃属性 |
| `属性 textures 未携带签名，已丢弃。` | 属性无签名 |
| `匹配到离线名单玩家: x（管理员指定 UUID: …），跳过全部外部鉴权。` | 命中离线名单 |
| `离线名单玩家 X：已关闭客户端会话校验（shouldAuthenticate=false）…` | 握手已改写，客户端会跳过会话上报 |
| `玩家 X 已按离线名单放行（UUID: …）。` | 离线放行完成 |
| `未能获取 LittleSkin 验签公钥…` | 公钥获取失败，本次丢弃全部属性（皮肤不可见） |
| `已改写目标类字节码: …（加载器: …）` | **切面真的挂上了**——这行是判断 Agent 是否生效的关键 |
| `命中聊天密钥校验器来源: net.minecraft.server.Services，挂载「外置账号放宽」切面。` | 启动阶段就会出现（`Services` 加载得早） |
| `已启用聊天密钥桥接（relax_chat_keys=true）：Mojang 验签 → LittleSkin 公钥验签 → …` | 外置账号第一次上报聊天密钥时打印一次 |
| `聊天密钥桥接：Mojang 官方验签通过（正版账号，走原版校验）。` | 第 1 级命中 |
| `聊天密钥桥接：皮肤站公钥验签通过（算法 SHA1withRSA）…` | 第 2 级命中：该密钥确实由皮肤站签发 |
| `聊天密钥桥接：Mojang 与皮肤站公钥均未通过验签，按 relax 策略放行…` | 第 3 级：未验过但放行（或 `chat_key_strict=true` 时为拒绝） |
| `离线名单玩家没有聊天公钥：其指令改按「未签名」处理…` | 无公钥的离线玩家发带实参指令时打印一次（见 [§8.6](#86-离线名单玩家发不出指令missing-profile-public-key)） |
| `命中聊天解码器工厂: net.minecraft.network.chat.SignedMessageChain$Decoder，挂载「离线玩家豁免安全档案」切面。` | 有玩家连接时出现（该类懒加载） |
| `离线名单玩家已豁免 enforce-secure-profile：他们可以正常聊天（显示 [Not Secure]）…` | 离线名单玩家连接后打印一次 |
| `离线名单玩家 N 人：已按玩家豁免 enforce-secure-profile…` | 启动阶段打印（离线名单非空且服务端强制安全档案时） |
| `命中玩家指令/聊天监听器: …ServerGamePacketListenerImpl… 挂载「无公钥指令回退」与「外置账号逐接收者未签名广播」切面。` | 有玩家连接时出现（该类懒加载）；方法若声明在父类，这里会显示 `ServerCommonPacketListenerImpl` |
| `外置账号的聊天改为「逐接收者未签名（伪装聊天）」广播：作者本人仍收到原版签名消息…` | 外置玩家第一次被广播聊天时打印一次（见 [§8.7](#87-交替发言必被踢--外置玩家自己看不到自己发的消息)） |
| `字节码改写失败: …` | 切面没挂上，Agent 静默失效（服务端仍能开服），完整原因见紧随其后的 `[Byte Buddy] ERROR` |
| `[Byte Buddy] ERROR …` | 切面挂载失败，需要排查版本兼容；最常见原因是切面类字节码引用了 authlib，见 [§8.4](#84-切面类字节码的硬性约束必读) |

---

## 附录 B：文件清单

```text
HyAuth-Agent/
├── pom.xml                                   # Maven 构建（shade + 清单）
├── .gitignore                                # 只提交源码/文档/自检脚本（忽略 target、.m2repo、tools、verify/libs…）
├── .gitattributes                            # 统一 LF，避免 Linux 上 "$'\r': command not found"
├── .github/workflows/
│   ├── ci.yml                                # 推送/PR：构建 + 三套自检 + 上传 jar 产物
│   └── release.yml                           # 推送 v* 标签：构建 + 自检 + 自动发 Release（附 jar 与 SHA256）
├── build.bat                                 # 一键构建（Windows）
├── build.sh                                  # 一键构建（Linux / macOS）
├── start.bat                                 # 服务端启动脚本（Windows，含 --add-opens 说明）
├── start.sh                                  # 服务端启动脚本（Linux / macOS，参数完全一致）
├── run.sh                                    # 最小启动脚本（Linux，写死本机 Java 25 路径，最省事）
├── README.md                                 # 本文档
├── BUILD.txt                                 # 构建产物信息（大小 / SHA256 / 本版变化 / 验证记录）
├── tools/apache-maven-3.9.9/                 # 可选：免安装的 Maven（已被 .gitignore 排除，按需自行下载）
├── src/main/java/com/hyauth/agent/
│   ├── AgentMain.java                        # 入口：premain/agentmain、切面挂载、宽松赋值器、改写结果播报
│   ├── HasJoinedAdvice.java                  # hasJoinedServer 切面（三级分流/放行/拒绝）
│   ├── LoginNameAdvice.java                  # 登录握手 1/2：记录 LoginStart 玩家名
│   ├── HelloPacketAdvice.java                # 登录握手 2/2：离线名单改写 shouldAuthenticate
│   ├── ChatKeyValidatorAdvice.java            # 聊天密钥校验器切面：外置账号不被踢
│   ├── ChatCommandAdvice.java                 # 无公钥离线玩家的指令回退切面（§8.6）
│   ├── ExternalChatAdvice.java                # 外置账号逐接收者未签名（伪装聊天）广播切面（§8.7）
│   ├── OfflineDecoderAdvice.java              # 离线玩家按玩家豁免 enforce-secure-profile（§8.6）
│   ├── LoginFlowContext.java                 # 登录期 ThreadLocal 上下文 + 名字解析
│   ├── LoaderBridge.java                     # Bundler 类加载器注入
│   ├── config/
│   │   ├── ListManager.java                  # 配置解析 / 名单 / 热监听
│   │   └── OfflinePlayer.java                # 离线名单条目（名字 + UUID）
│   └── util/
│       ├── YggdrasilAuthUtil.java            # LittleSkin 请求 + RSA 验签 + 公钥获取
│       ├── AuthlibProfileFactory.java        # 按 Authlib 版本构造 GameProfile/ProfileResult
│       ├── VerifiedProfile.java              # 版本中立的已验签角色信息
│       ├── VerifiedProperty.java             # 版本中立的已验签属性
│       ├── AuthRejection.java                # ★ 纯 JDK 反射中转：把 authlib 异常类型挡在切面字节码之外
│       ├── ChatKeyPolicy.java                # ★ 聊天密钥策略入口（relax_chat_keys / chat_key_strict）
│       ├── ChatKeyBridge.java                # ★ 三级桥接验签：Mojang → 皮肤站公钥 → 策略
│       ├── KeylessChatBypass.java            # ★ 无聊天公钥的离线玩家：指令按未签名执行
│       ├── ExternalChatBroadcast.java        # ★ 外置账号聊天按接收者分流（作者签名 / 他人伪装聊天）（§8.7）
│       └── OfflineChatExempt.java            # ★ 离线玩家按玩家豁免 enforce-secure-profile
└── verify/                                   # 自检脚手架（不参与 Agent 构建）
    ├── verify.ps1                            # 主自检：模拟/真实 Authlib × 新旧签名 + 热重载
    ├── loaderiso.ps1                         # ★ 类加载器隔离回归测试（还原 Bundler 拓扑，改切面必跑）
    ├── chat.ps1                              # ★ 外置账号聊天广播回归测试（改聊天切面必跑，§11.1）
    ├── chat/ChatAdviceMain.java              #   断言：别人→伪装聊天、自己→原版签名消息、开关行为
    ├── chat/net/minecraft/…                  #   26.3 形状的替身（PlayerChatMessage / ChatType.Bound /
    │                                         #   ServerGamePacketListenerImpl / ServerCommonPacketListenerImpl…）
    ├── loaderiso/{LoaderIsoMain,IsoTarget,MissingTypeUser,Caller}.java
    ├── loaderiso/net/minecraft/util/SignatureValidator.java   # 校验器替身（验证 relax_chat_keys）
    ├── tools/Fetch.java
    ├── common/harness/{HarnessMain,MockLittleSkin,Sleeper,VanillaProbe}.java
    ├── mockmodern/…  mocklegacy/…            # 模拟新旧签名的目标类
```

> **版本号说明**：源码里的 `1.0.0` 是 Maven 版本（`pom.xml` 未改动）；本文档中的
> "v1.0.1" 指的是**修复了 Bundler 类加载器隔离 bug 之后重新构建的这一版 jar**，
> "v1.0.2" 指的是**修复"外置玩家自己看不到自己发的消息"（逐接收者聊天分流）之后的那一版**，
> 文件名仍然是 `HyAuth-Agent-1.0.0.jar`。请以 **SHA256** 区分新旧产物：
> 旧版 `c064bcdb…`（有问题，切面挂不上），最新版见项目根目录 `BUILD.txt`。
