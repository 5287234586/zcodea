# THIRD_PARTY_NOTICES

本产品（ZCodeA）使用的第三方组件及许可：

| 组件 | 许可 | 用途 |
| --- | --- | --- |
| [Termux terminal-emulator / terminal-view](https://github.com/termux/termux-app) | Apache-2.0 | 真 PTY 终端（JNI 伪终端），经 JitPack 依赖 `com.github.termux.termux-app:terminal-*:v0.118.3` |
| [proot-android](https://github.com/green-green-avk/build-proot-android) | GPL-2.0（proot） | 主容器运行时（proot 二进制 + 外置 loader） |
| proot / libtalloc / libandroid-shmem（Termux 包） | GPL-2.0 / LGPL-2.1+ | 回退容器运行时（运行时从 Termux 仓库下载提取） |
| [Ubuntu Base](https://ubuntu.com/download/server) | Ubuntu知识产权声明（CC BY-SA 3.0（部分）） | 容器 rootfs（运行时从官方/镜像下载） |
| [Node.js](https://nodejs.org/) | MIT（含捆绑组件） | 容器内运行时（运行时下载） |
| [zcode-app-cli](https://www.npmjs.com/package/zcode-app-cli) | 随包许可 | 被运行的 ZCode CLI（容器内 npm 安装；为 Z.ai 的 ZCode 运行时的社区打包） |
| [NanoHTTPD](https://github.com/NanoHttpd/NanoHttpd) | BSD-3-Clause | 本机桥接 HTTP 服务器 |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) | Apache-2.0 | tar/deb/xz 解包 |
| [XZ for Java](https://tukaani.org/xz/java.html) | 公有领域 | xz 解压 |
| [OkHttp](https://square.github.io/okhttp/) | Apache-2.0 | 下载器 |
| [kotlinx-coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache-2.0 | 并发 |
| JetBrains Kotlin / Android Jetpack Compose | Apache-2.0 | 语言与 UI 框架 |

## 运行时下载声明

Ubuntu Base / Node.js / Termux deb / zcode-app-cli 在**装机阶段**从下列来源下载（多镜像回退 + SHA-256 校验）：
Ubuntu 官方 cdimage 与 mirrors.tuna.tsinghua.edu.cn；nodejs.org 与清华/npmmirror 镜像；
packages.termux.dev 与清华镜像；registry.npmmirror.com。
本应用自身**不上传任何数据、无遥测**。

## 商标

ZCode 与 GLM 为 Z.ai 的商标；DeepSeek 为深度求索的商标。本应用与上述主体无隶属关系。
