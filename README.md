# DroidAssist

DroidAssist 是一套 Android 对 Android 的远程协助原型。两台手机通过 Mac 上的 Ktor 服务完成设备注册、连接授权和 WebRTC 信令；屏幕画面与控制指令通过 WebRTC 点对点传输，不经过业务服务端转发。

当前 Android 版本为 `1.0.8`（`versionCode 9`），最低支持 Android 8.0 / API 26，目标版本为 API 36。

## 已实现功能

- 设备注册并生成 9 位匹配码，设备身份保存在本机。
- 临时授权和永久授权，支持可信设备及撤销授权。
- 最近使用的匹配码保存在手机本地，可一键复用或清空。
- WebSocket 长连接实时通知连接请求和传递 WebRTC 信令，无业务轮询。
- MediaProjection 捕获被协助手机的整个屏幕。
- WebRTC P2P 实时画面和 DataChannel 控制指令。
- 点击、长按、滑动，以及返回、Home、最近任务等远程操作。
- 连接成功后自动打开独立的远程画面页面。
- 远控页面首次显示底部操作面板；收起后使用悬浮按钮重新展开。
- 被协助端停止共享时发送挂断信号，协助端自动退出远程画面。
- 设备、在线状态、可信设备、屏幕共享、无障碍权限和连接状态展示。

## 工程模块

| 模块 | 作用 |
| --- | --- |
| `app` | Android 客户端，同时支持协助端和被协助端角色 |
| `protocol` | 客户端与服务端共用的 REST、WebSocket 数据模型 |
| `server` | Ktor 注册、授权、在线状态、会话和信令服务，使用 SQLite |
| `deploy/mac` | macOS 本地服务和 Cloudflare Quick Tunnel 管理脚本 |

详细设计见 [架构说明](docs/ARCHITECTURE.md)，实现演进见 [实现说明](docs/IMPLEMENTATION.md)，服务部署见 [macOS 部署说明](docs/DEPLOYMENT.md)。早期需求和设计输入保留在 [历史交接文档](docs/ANDROID_REMOTE_ASSISTANCE_HANDOFF.md)。

## 双手机使用

### 1. 启动服务

在 Mac 上执行：

```bash
./deploy/mac/start.sh
./deploy/mac/status.sh
```

`status.sh` 会显示健康状态和当前公网地址。完整的安装依赖、环境变量、日志和故障排查见 [macOS 部署说明](docs/DEPLOYMENT.md)。

### 2. 注册两台手机

1. 两台手机安装同一个 APK。
2. 打开“设置”，确认服务器地址与 `./deploy/mac/status.sh` 输出的公网地址一致。
3. 点击“注册本机”或“重新连接服务”。
4. 两台手机都显示“设备注册：已注册”后再继续。

当前构建默认内置的测试地址是：

```text
https://celebrities-aggregate-limiting-arrives.trycloudflare.com
```

Quick Tunnel 地址不是固定域名，重启隧道后可能变化。地址变化时，不需要立刻重新打 APK；在两台手机的“设置”页填写新地址并点击“重新连接服务”即可。若需要为新安装包更换默认地址，修改 `AssistUiState.serverUrl` 后重新构建。

### 3. 准备被协助手机

1. 打开“本机”页，点击“开启无障碍控制”。
2. 在 Android 系统设置中启用 DroidAssist 无障碍服务，然后返回应用。
3. 选择“临时授权”或“永久授权”。
4. 将本机 9 位匹配码和本次连接密码提供给协助端。

临时授权在默认 10 分钟内有效且只能使用一次。永久授权用于首次绑定；被协助端仍会看到本次连接确认弹窗。

### 4. 发起远程协助

1. 协助端打开“设备”页，输入匹配码和连接密码，点击“发起连接”。
2. 被协助端在“远程协助请求”弹窗中选择是否设为可信设备，然后点击“允许”。
3. Android 会自动弹出系统屏幕共享授权，选择共享整个屏幕并允许。
4. WebRTC 建立后，协助端自动进入独立远程画面。
5. 使用远程画面直接点击、长按和滑动；底部面板可执行返回、Home、最近任务或退出查看。
6. 被协助端可在“本机”页点击“停止共享”结束本次会话。

Android 的系统录屏确认不能由应用跳过，每次建立新的屏幕采集都必须由用户明确允许。没有启用无障碍服务时仍可查看画面，但点击和系统导航不会生效。

## 构建与安装

推荐使用 Android Studio 自带 JBR（Java 21）。命令行构建调试包：

```bash
./gradlew :app:assembleDebug
```

生成文件：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接的设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

本机另有已验证的 `releases/DroidAssist-1.0.8-debug.apk`，SHA-256 为：

```text
3aba37d16151db03384ae3eb479d3d539a6f769427c80f5bc343676047037e7f
```

`releases/` 中的 APK 属于本机构建产物，不提交到 Git；远端可从源码重复构建。

## 验证

运行单元测试、Android Lint 和调试构建：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

服务健康检查：

```bash
curl -fsS http://127.0.0.1:8080/health
```

当前版本已在 Pixel 7 和 Pixel 9 模拟器完成双端验证，包括连接确认、系统录屏授权、ICE 连接、实时远程画面、DataChannel 控制、Home 操作和主动挂断。

## 权限与安全边界

- `INTERNET` 和网络状态权限用于 REST、WebSocket 与 WebRTC。
- 前台服务和通知权限用于保持 MediaProjection 屏幕共享。
- MediaProjection 只在系统授权后启动，系统状态栏会显示共享状态。
- 无障碍服务只在被协助端执行收到的触控和系统导航指令，必须由用户在系统设置中手动启用。
- 服务端只保存设备、授权和会话元数据，以及经过哈希的设备令牌和连接密码。
- 视频采用 WebRTC 传输；业务服务端只中转信令，不接收屏幕帧。

## 当前限制

- 当前只配置公共 STUN，没有 TURN 中继；在严格 NAT、企业网或受限移动网络下可能无法建立 P2P。
- Quick Tunnel 地址临时且可能变化，不适合作为生产环境固定入口。
- 当前是验证用原型，尚未加入生产级证书固定、TURN、服务端限流、审计、监控和自动升级。
- Android 安全页面或使用 `FLAG_SECURE` 的应用可能显示黑屏，部分厂商系统也可能限制后台共享或无障碍手势。
- 远程控制能力受 Android 无障碍 API 边界约束，不能绕过锁屏、系统确认框和系统安全策略。

## License

仓库当前未包含开源许可证；在对外分发或接受外部贡献前应先明确许可证。
