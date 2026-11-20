# ZCodeA — ZCode Harness 安卓启动器

[![CI](https://github.com/5287234586/zcodea/actions/workflows/ci.yml/badge.svg)](https://github.com/5287234586/zcodea/actions/workflows/ci.yml)

在手机上跑完整的 **ZCode**（GLM 官方编程智能体）—— 免 ROOT、免 Termux、装完即用。
对标 [DSHA](https://github.com/DSH-APP/DSHA)（DeepSeek Harness 安卓启动器）的容器派路线，把运行时换成 ZCode。

## 架构

```
┌──────────────────────── APK ────────────────────────┐
│ 原生 Android（Kotlin · Compose · Material3 · 单模块）│
│  ├ 主页 / 装机流水线 / 设置 / 简易终端 / WebView     │
│  ├ 前台服务 + 看门狗 + 通知                          │
│  ├ App 桥 :3090（聊天 SSE / 终端 / 无障碍 / 剪贴板） │
│  └ 无障碍服务（读屏 / 点按 / 输入 / 滚动 / 返回）    │
├─────────────────────────────────────────────────────┤
│ proot（Termux 构建，deb 提取；arm64 + x86_64）       │
├─────────────────────────────────────────────────────┤
│ Ubuntu 24.04 base（rootfs，首启下载 ~35MB）          │
│  ├ Node.js 22 LTS（linux-arm64 / linux-x64）        │
│  └ zcode-app-cli（npm 全局安装，bin = zcode）        │
└─────────────────────────────────────────────────────┘
```

## 与 DSHA 的对照

| | DSHA | ZCodeA v0.2 |
| --- | --- | --- |
| Harness | `@deepseek-ai/dsh` | `zcode-app-cli`（纯 Node，无原生模块） |
| 驱动方式 | Web UI 插件 | **ZCode Protocol**（`app-server --stdio` 常驻会话，流式）+ `-p` 单发回退 |
| rootfs 分发 | 内置离线（302MiB APK） | 首启下载（官方/清华镜像回退 + SHA-256 校验） |
| 容器运行时 | proot / proroot | 双引擎：proot-android（主）+ Termux proot（回退），探针失败自动切换 |
| 移动 UI | dsh-web-mobile | 内置聊天 Web UI（SSE 实时上屏、停止/新建会话） |
| 桥端口 | :3090 | :3090（仅 127.0.0.1） |
| 设备能力 | 无障碍 + ADB/Shizuku/Root | 无障碍 + 剪贴板 + 通知（ADB 通道规划中） |
| 架构 | 仅 arm64 | arm64 + x86_64（模拟器可开发，见已知限制） |

### 会话模式（v0.2）

默认开启（设置里可关）。开启后每次发消息走常驻的 `zcode app-server --stdio`：

- `session/create`（工作目录 /root/zsha）→ `session/send` → 事件流经 SSE 直推 Web UI
- 会话跨消息保持上下文（ZCode 自身的会话持久化），应用重启后自动续接
- 服务端反向请求（运行时偏好等）由桥自动应答；未知请求原样透传到 Web UI 供人工介入
- 关闭时回退 v0.1 的 `-p` 单发模式（`POST /app/chat`）

## 使用

1. 安装 APK → 主页点「安装环境」（下载 Ubuntu base + Node + ZCode，全程校验 SHA-256）
2. 点「启动服务」→ 前台通知出现
3. 点「打开 Web UI」→ 右上角「登录」→ 选 Z.AI 账号 → 在手机浏览器完成 GLM OAuth 授权
4. 之后直接在 Web UI 里给 ZCode 派活；每次消息以 `zcode -p "<消息>" --no-color --mode <档位>` 在容器内运行，工作目录 `/root/zsha`
5. 「终端」页可在容器里开交互 bash（`script -qfc bash /dev/null` 挂 PTY）

无障碍授权后，agent 通过本机桥获得读屏/点按/输入/滚动/返回能力（端点清单见 `GET /app/help`）。

## 构建

需要 JDK 17、Android SDK（platform 36 / build-tools 35）。模拟器用 x86_64 镜像即可全链路验证。

```
./gradlew :app:testStandardDebugUnitTest   # 若无 flavor，用 :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

## 已知限制（诚实披露）

- **targetSdk 28（刻意）**：targetSdk ≥29 的 W^X 策略禁止 exec 应用数据目录里的二进制，proot 全系应用（含 Termux）都停在 28。侧载分发不受商店政策影响。
- **模拟器全链路验证受内核限制**：google_apis 模拟器镜像的内核（API 33/35 x86_64 均实测）在 proot 的动态 ELF 换轨（PT_INTERP/loader 机制）上存在缺陷——静态 ELF 可执行、动态 ELF 一律 ENOENT，与 SELinux 模式、proot 构建（Termux / proot-me / build-proot-android 三种）、权限均无关（已逐一排除）。**真机 arm64 不受影响**（Termux / DSHA 生态在真机上久经验证）。要在模拟器上做完整验证，需要非 google 内核的镜像或真机。诊断表现：装机在「容器探针」一步报明确错误。
- **proot 双引擎**：主引擎为 [build-proot-android](https://github.com/green-green-avk/build-proot-android)（NDK 构建、静态 talloc、外置 loader），回退引擎为 Termux deb（proot + libtalloc + libandroid-shmem）；容器探针失败时自动互换重试一次。
- **首次装机需联网**：rootfs/Node/ZCode 走官方源 + 清华镜像 + npmmirror 多路回退 + SHA-256 清单校验；断网无法装机（v0.2 计划内置离线包，像 DSHA 一样打进 APK）。
- **无内核级沙箱**：proot 只是用户态路径转译，bubblewrap 在 Android sepolicy 下同样不可用（容器派与 Termux 派的共同边界）。权限靠 `--mode` 档位控制，默认 `yolo`，可在设置改为 `guarded`。
- **简易终端非完整 xterm**：展示时清洗 ANSI 转义序列，vim/htop 等全屏 TUI 显示不完整；v0.2 计划接入 Termux terminal-emulator JNI。
- **局域网访问（v0.3）**：主页「局域网访问」开关开启后，桥绑定 0.0.0.0；非回环请求必须带 token（`Authorization: Bearer` / `X-Token` / `?token=` 任一），UI 地址栏自带 `?token=`。注意是**明文 HTTP**，只在可信 Wi-Fi 下用；token 可随时重新生成，开关切换需重启服务。
- 调试端点 `POST /app/env/exec`（容器内执行）仅 debug 构建开放 `!host` 前缀。
- **聊天为单发模式**：每次消息一个 `zcode -p` 进程，串行执行；会话恢复靠 zcode 自身的 `--resume`/`-c`（尚未接出）。
- **无遥测**：不上传任何日志或使用数据。

## 许可

MIT。第三方组件：proot / libtalloc（GPL，随 Termux 分发）、commons-compress（Apache-2.0）、NanoHTTPD（BSD）、OkHttp（Apache-2.0）。
