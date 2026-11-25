# DroidAssist

DroidAssist 是一套 Android 对 Android 的远程协助工具，支持实时查看屏幕、点击、长按、滑动，以及返回、Home 和最近任务操作。同一个 APK 同时支持协助端和被协助端。

当前版本：`1.1.6`。Android 最低版本为 Android 8.0 / API 26，目标版本为 API 36。

## 普通用户：下载后直接使用

不需要安装 Java、Android Studio、Git、Gradle 或编写代码。

1. 打开 [GitHub Releases](https://github.com/stonebirds/DroidAssist/releases/latest)。
2. 在两台 Android 手机上安装 `DroidAssist-1.1.6-release.apk`。
3. 根据电脑下载一个服务包：
   - Apple 芯片 Mac：`DroidAssist-Server-macOS-arm64-1.1.6.zip`
   - Intel 芯片 Mac：`DroidAssist-Server-macOS-x64-1.1.6.zip`
   - 64 位 Windows：`DroidAssist-Server-Windows-x64-1.1.6.zip`
4. 完整解压服务包，双击 `1-Start-DroidAssist` 开头的文件。
5. 浏览器会自动显示服务器地址和离线生成的二维码。在两台手机的“设置”页点击扫码图标，然后点击“注册本机”。
6. 使用结束后回到解压目录停止电脑服务：
   - Mac：双击 `3-Stop-DroidAssist.command`。
   - Windows：双击 `3-Stop-DroidAssist.cmd`。

关闭浏览器页面不会停止服务。停止脚本不会删除数据库；再次启动后如果公网地址发生变化，需要在两台手机上更新服务器地址。

完整截图式操作顺序见 [普通用户快速开始](docs/QUICK_START.md)。首次打开下载的软件时，macOS、Windows 和 Android 可能显示来源确认，这是未通过应用商店分发时的系统安全机制。

## 技术用户：自行构建和部署

技术文档没有删减，可根据需要更换服务器、数据库位置、网络入口或继续开发：

| 文档 | 适用场景 |
| --- | --- |
| [macOS 源码部署](docs/DEPLOYMENT.md) | Gradle、launchctl、环境变量、日志和数据库备份 |
| [Windows 源码部署](docs/DEPLOYMENT_WINDOWS.md) | PowerShell、手动构建、进程管理和故障排查 |
| [系统架构](docs/ARCHITECTURE.md) | REST、WebSocket、WebRTC、存储和安全边界 |
| [实现过程](docs/IMPLEMENTATION.md) | 技术决策、问题处理、测试范围和后续方向 |
| [发布维护](docs/RELEASING.md) | APK 签名、便携包构建、校验和 GitHub Release |
| [历史设计输入](docs/ANDROID_REMOTE_ASSISTANCE_HANDOFF.md) | 项目启动时的需求和原始方案 |

源码启动 Ktor 服务：

```bash
./gradlew :server:run
```

构建 Android 调试包：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

## 已实现功能

- 设备注册和 9 位匹配码。
- 临时授权、永久授权、可信设备和撤销授权。
- 最近连接匹配码本地保存。
- 竖屏扫码或使用离线模型从相册识别单个/多个服务器二维码，以及完整授权信息复制和剪贴板解析。
- WebSocket 长连接实时通知，无业务轮询。
- WebSocket 短暂离线时有界暂存关键 WebRTC 信令，重连后按顺序补发。
- MediaProjection 整屏捕获和前台共享服务。
- WebRTC P2P 屏幕画面和 DataChannel 控制。
- 点击、长按、滑动、返回、Home 和最近任务。
- 控制指令执行回执；无障碍服务未实际连接或系统拒绝操作时，协助端会显示原因并记录日志。
- 独立远控画面及可收起的固定高度底部控制面板；面板内容独立滚动，不会因拖拽继续扩张到状态栏。
- 默认网络自适应画质，并支持 720p/1080p 与 10/15/20/30 fps 手动组合。
- 协助端主动断开、被协助端停止共享、对端挂断和双端资源清理。
- macOS 与 Windows 免开发环境便携服务包。
- Cloudflare Quick Tunnel 限流识别、分段重试和明确的恢复提示。
- 设置页内置持久化诊断日志，可一键复制且不会记录令牌、密码、SDP 或 ICE 地址。
- 多 STUN 端点和 ICE 失败自动重启，降低弱网下首次连接失败概率。
- 分离 ICE、视频首帧和控制通道状态；连接未真正就绪时禁用远控操作并显示对应阶段。

## 工程结构

| 目录 | 作用 |
| --- | --- |
| `app` | Android 客户端，同时支持协助端和被协助端 |
| `protocol` | 客户端和服务端共享的序列化协议 |
| `server` | Ktor 注册、授权、在线状态、会话和 WebRTC 信令服务 |
| `deploy/mac` | macOS 源码部署脚本 |
| `deploy/portable` | Mac/Windows 便携包的一键启动模板 |
| `deploy/package` | 正式 APK、签名密钥和便携包构建工具 |

## 数据和隐私

- 服务器默认使用 SQLite，仅保存设备、授权和会话元数据。
- 设备令牌与连接密码经过 PBKDF2 哈希后保存。
- 屏幕画面和控制指令通过 WebRTC 在两台手机之间传输，业务服务器只中转信令。
- MediaProjection 和无障碍权限必须由被协助端用户在 Android 系统界面中明确允许。
- 便携服务包把数据库和日志放在当前电脑用户目录，升级软件包不会主动删除数据。

## 当前限制

- 当前使用公共 STUN，没有 TURN；严格 NAT、企业网络或受限移动网络可能无法建立 P2P。
- 默认一键部署使用 Cloudflare Quick Tunnel，完全停止后重新启动可能获得新的公网地址。
- macOS 和 Windows 便携包尚未使用商业代码签名证书，首次运行可能出现系统安全确认。
- Android APK 通过 GitHub 直接分发，安装时需要允许浏览器或文件管理器安装未知来源应用。
- DroidAssist 扫码页使用可被整屏捕获的 TextureView；第三方相机、安全页面、锁屏或使用 `FLAG_SECURE` 的应用仍可能显示黑屏或拒绝无障碍手势。

## 开源协议

DroidAssist 使用 [0BSD License](LICENSE)。任何人都可以免费使用、复制、修改、分发和商业使用本项目，无需支付费用或保留署名；软件按现状提供，不附带担保。
