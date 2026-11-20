# AGENTS.md — 给下一个 AI / 开发者

开始之前先读这个，不要全库扫描。DSHA 的 AGENTS.md 是本文件的模板，思路一致：少踩一遍别人踩过的坑。

## 项目是什么

ZCodeA（ZCode Harness 安卓启动器，仓库历史名 zsha）：在 Android 上免 ROOT 运行 **ZCode**（GLM 官方编程智能体，npm 包 `zcode-app-cli`，bin = `zcode`）。对标 [DSHA](https://github.com/DSH-APP/DSHA)（DeepSeek Harness 安卓启动器），运行时从 DSH 换成 ZCode。**注意：ZCode 是另一个独立产品，本仓库不是 ZCode 本体，只是它的安卓启动器。**

## 结构（单模块 :app，Kotlin + Compose M3）

```
app/src/main/java/zsh/android/client/
├── ZshaApp.kt            # 单例容器：settings / envManager / runtimeManager
├── env/                  # 装机层（纯 JVM 可测）
│   ├── Abi.kt            # ABI 归一化（arm64-v8a→aarch64/arm64/arm64；x86_64→x86_64/amd64/x64）
│   ├── Sources.kt        # 下载源：多镜像候选 + Termux pool 目录解析 + 版本排序
│   ├── Downloader.kt     # OkHttp 多候选下载（.part 原子改名、4xx 换源）
│   ├── Checksum.kt       # SHA-256 + SHA256SUMS 格式解析
│   ├── Tar.kt            # tar.gz 解压（符号链接真建、硬链接物化为拷贝——SELinux 禁 lnk_file create）、deb(ar+xz) 提取、路径穿越防护
│   ├── EnvPaths.kt       # filesDir/zsha 目录布局
│   ├── Receipt.kt        # 安装回执（每步 ok/版本），探测跳过的依据
│   └── EnvManager.kt     # 五步流水线：proot → rootfs → node → zcode → verify；探针失败自动换引擎
├── runtime/
│   ├── ProotCmd.kt       # proot 命令模板（-0 假 root、bind /dev /proc /sys、env -i 清环境、PATH 前置 /opt/node/bin）
│   ├── AppServerClient.kt# ZCode Protocol stdio 客户端（v0.2 会话模式）
│   └── RuntimeManager.kt # agent 会话 / -p 单发 / 终端 / 登录任务
├── service/RuntimeService.kt  # 前台服务 + 看门狗（桥挂了自动拉起）+ 端口占用自动 +1
├── bridge/BridgeServer.kt     # NanoHTTPD，仅 127.0.0.1:3090，/app/help 有全量端点
├── device/ZshAccessibilityService.kt  # 读屏/点按/输入/滚动（agent 的"手"）
├── store/Settings.kt
└── ui/                   # Compose：home / setup / terminal / settings / webview
```

## 硬约束（动代码前必读）

1. **targetSdk 必须停在 28**。≥29 时 Android W^X 禁止 exec 应用数据目录的二进制，proot 直接废。Termux 停 28 同因。别"升级"它。
2. **proot 双引擎**：主 = green-green-avk/build-proot-android（静态 talloc + 外置 loader，tar 内有 `root/` 前缀，解压后要归一化）；回退 = Termux deb（proot + libtalloc + libandroid-shmem 三个包一起取）。`runtime/proot` 是当前激活引擎的**真实文件**（不是软链），`runtime/libexec/proot/loader` 必须与它同布局。
3. **guest 动态 ELF 探针**：`/bin/echo PROBE_OK` 通过才算 rootfs 步骤完成。失败自动换引擎重试一次，再失败给出明确错误（模拟器内核限制，见下）。
4. **桥只绑 127.0.0.1**。要开局域网必须同时上 token 鉴权（v0.2 未做，别裸开）。
5. **ZCode Protocol**（`zcode app-server --stdio`）：换行 JSON；客户端请求 `{method,params,id}`；服务端会反向发请求（如 `session/requestRuntimePreferences`，result schema 见 AppServerClient.handleServerRequest）——**不回应会话会挂起**。`session/create` 的响应可能迟到，要同时监听 `session/lifecycle/changed` 事件兜底取 sessionId。
6. **所有设备/下载操作都要能"探测再执行、失败只修失败项"**（Receipt 驱动），这是 DSHA 的核心工程习惯。

## 已知坑（都是真实踩过的）

- **模拟器（google_apis x86_64，API 33/35 均实测）guest 动态 ELF 必 ENOENT**：与 SELinux（permissive+root 复现）、proot 构建（三种复现）、权限无关；无解释器静态 ELF 可执行。真机 arm64 不受影响。别在模拟器上试图"修好"它，浪费时间；要在模拟器全链路验证需要非 google 内核镜像或真机。
- **uiautomator dump 对 Compose 应用输出空文件；`input tap/swipe/motionevent`、`adb emu event mouse` 在 google_apis 镜像上静默失效**（keyevent 正常）。UI 自动化别指望注入，用 `am start --es zsha_action install|start_service` 钩子 + 桥端点驱动。
- **run-as 域不能 exec app_data_file**（但可以 cp/chmod）；**su 域同样不能 exec app 数据里的二进制**。要手测宿主侧 exec，用 debug 桥端点 `POST /app/env/exec` 的 `!host` 前缀。
- **Git Bash（MSYS）路径转换**：`/data/local/tmp` 会被转成 `C:/Program Files/Git/...`。对 adb shell 内路径一律 `MSYS_NO_PATHCONV=1`，但同一命令里的**本地文件参数**又必须是 Windows 形式（`D:/...`）。别混。
- 这台开发机的 `grep` 是 ugrep 别名：复杂正则直接用 python。
- NanoHTTPD 2.3.1：`getListeningPort()`（不是 getPort）、`isAlive()` 是 final 不能 override、POST JSON 要 `Content-Type: application/json` 才有 `postData`。
- commons-compress 解 xz 需要 `org.tukaani:xz`；zip 的 zstd 需要额外 jni（Termux deb 目前是 xz，遇到 data.tar.zst 直接报错提示）。
- org.json 在 JVM 单测里是 stub，`testImplementation("org.json:json")` 替换。
- Windows 下 `ls -la` 走 run-as 的组合输出顺序不可靠，**验证文件状态用桥端点 `!host`，别用 shell 猜**。

## 构建与验证

```
gradlew :app:testDebugUnitTest   # 17+ 单测，纯 JVM
gradlew :app:assembleDebug
```
开发机环境：JDK17 + SDK 在 `D:/build-env`（见 local.properties）。模拟器冒烟用 `am start --es zsha_action start_service` + `adb forward tcp:13090 tcp:3090` + curl 驱动，全程无需点 UI。

## 发布纪律

- 不上传任何遥测；诊断信息只留在本机日志与回执。
- 破坏性操作（清环境）必须二次确认。
- 每个能力默认关闭、按需授权（无障碍、通知）。
