# Android 远程协助项目交接文档

> 历史文档：本文记录项目启动时的需求、方案和交接信息，其中的页面结构、阶段计划及“待实现”描述不代表当前代码状态。当前使用方法见 [`README.md`](../README.md)，现行架构见 [`ARCHITECTURE.md`](ARCHITECTURE.md)，实现结果见 [`IMPLEMENTATION.md`](IMPLEMENTATION.md)。

## 使用方式

本文件用于将项目交接给 Mac mini 上的 Codex。
实际项目必须创建为独立工程，不得修改 DroidMirror 源代码。

建议工程目录：

`~/AndroidStudioProjects/DroidAssist`

## 产品目标

开发一款 Android 控制 Android 的一对一远程协助应用。
同一个 APK 同时支持控制端和被控端。

应用只有两个底部 Tab：

- 协助：展示本机授权信息，或输入对方匹配码和密码发起连接。
- 管理：显示在线设备、永久授权设备、连接历史和撤销入口。

## 已确认架构

```text
Android 手机
    │ HTTPS / WSS
    ▼
Cloudflare Quick Tunnel
    │
    ▼
Mac mini：Ktor + SQLite

两台 Android 手机之间通过 WebRTC P2P 传输画面和控制数据。
```

- Mac mini 负责匹配码、身份认证、授权关系、在线状态和 WebRTC 信令。
- Cloudflare Quick Tunnel 为 Mac 提供 IPv4/IPv6 公网入口。
- 使用公共 STUN 协助 WebRTC 穿透。
- 不使用华为云。
- 不部署 TURN。
- 不购买域名，测试阶段使用 `trycloudflare.com` 临时地址。
- Mac 不保存、不转发屏幕视频。

## 已完成的网络验证

- Mac mini 可以正常运行 `cloudflared`。
- Quick Tunnel 的 DNS、QUIC、TCP 和 API 检查均通过。
- 手机流量网络支持 IPv6。
- 公司测试机只有 IPv4，但可以通过 Quick Tunnel 访问 Mac。
- 上述测试仅证明信令可访问，不代表所有网络都能建立 WebRTC P2P。

## Android 技术选型

- Kotlin
- Jetpack Compose
- MediaProjection
- AccessibilityService
- WebRTC
- Android Keystore
- DataStore 或 Room
- 前台服务和常驻通知

最低版本建议 Android 8（API 26），目标版本使用当前稳定 SDK。

## Mac 服务端技术选型

- Kotlin + Ktor
- SQLite
- WebSocket 信令
- REST API
- Cloudflare Quick Tunnel
- 本地管理页和二维码

服务默认只监听：

`127.0.0.1:8080`

## 授权规则

### 临时授权

- 随机生成密码。
- 默认有效期 10 分钟。
- 成功建立一次会话后立即作废。
- 被控端必须确认连接请求。
- 限制密码尝试次数并记录安全日志。

### 永久授权

- 使用固定匹配码和固定密码完成首次绑定。
- 首次绑定必须由被控端确认。
- 服务端只保存密码哈希，不保存明文密码。
- 首次绑定后保存双方设备公钥。
- 默认可信设备可以免除后续 App 内确认。
- 被控端可以随时撤销授权或关闭免确认。

Android 系统的 MediaProjection 授权不能绕过。
重启、进程被杀或开始新的录屏会话时，系统可能要求重新确认。

## 屏幕和控制

- 被控端使用 MediaProjection 采集屏幕。
- 控制端通过 WebRTC 显示画面。
- 使用 WebRTC DataChannel 传输控制指令。
- 坐标使用归一化数值，适配分辨率和旋转。
- AccessibilityService 实现点击、长按、滑动和多段手势。
- 返回、桌面、最近任务使用无障碍全局操作。
- 被控端必须显示持续通知和“立即停止协助”入口。

## 视频策略

- 首选 H.264 硬件编码。
- H.264 不可用时回退 VP8。
- 第一版不支持 H.265。
- 根据 WebRTC RTT、丢包率和带宽动态调整画质。

建议档位：

- 1080p / 30fps：网络良好。
- 720p / 20–30fps：普通网络。
- 540p / 15–20fps：较弱网络。
- 360p / 10–15fps：弱网保连接。

## 无域名阶段

Mac 启动工具需要自动完成：

1. 启动 Ktor。
2. 启动 Cloudflare Quick Tunnel。
3. 解析生成的临时公网地址。
4. 在本地管理页展示地址和二维码。
5. 允许 Android App 扫码或手动输入地址。

App 需要保存最近使用的服务地址，并允许快速替换。
Tunnel 地址变化后需要重新扫码，但永久设备关系不需要重新绑定。

## P2P 限制

没有 TURN 时，以下网络组合可能连接失败：

- 对称 NAT。
- 企业网络屏蔽 UDP。
- 运营商严格限制点对点通信。
- 访客 Wi-Fi 或 AP 隔离。

失败时必须明确提示用户：

- 切换手机流量。
- 使用手机热点。
- 更换 Wi-Fi。
- 检查 VPN 或企业网络限制。

第一版不承诺所有网络环境都能成功连接。
ICE 配置由 Mac 动态下发，未来可以增加 TURN 而不重构 App。

## 第一版不包含

- 麦克风和语音通话
- 文件传输
- 剪贴板同步
- APK 安装
- 屏幕录像保存
- 摄像头
- 多人控制
- iOS 或 Windows 客户端
- H.265
- TURN
- 应用商店发布

## 建议模块

```text
DroidAssist/
├── app/                 Android 应用
├── server/              Mac 上的 Ktor 服务
├── protocol/            信令协议和公共数据模型
├── deploy/mac/          Mac 启动、停止和状态脚本
├── docs/                架构、部署与测试文档
└── settings.gradle.kts
```

## 实施顺序

1. 创建独立 Gradle 多模块工程。
2. 定义信令协议和安全模型。
3. 实现 Ktor、SQLite 和 WebSocket。
4. 实现 Android 权限引导与被控端前台服务。
5. 接入 MediaProjection 和 WebRTC。
6. 实现控制端画面和 DataChannel。
7. 实现 AccessibilityService 手势映射。
8. 实现临时授权、永久授权和管理页。
9. 实现 Quick Tunnel 启动工具和二维码。
10. 完成跨网络真机测试、文档和安装包。

## 验收标准

- 两台 Android 手机可以完成临时授权连接。
- 临时密码使用一次后失效。
- 永久授权设备可以重新连接并可以撤销。
- 控制端可以看到被控端屏幕。
- 点击、滑动、返回、桌面和最近任务有效。
- 屏幕旋转后坐标仍正确。
- 断网后状态明确，恢复后可以重新连接。
- 画质能够根据网络质量自动调整。
- 被控端始终显示前台通知并可立即停止。
- 服务端不保存视频、音频或屏幕截图。
- P2P 失败时给出可操作的网络提示。

## Mac mini 环境

- Apple Silicon M4
- 8 GB 内存
- 256 GB 存储
- Homebrew 已安装
- `cloudflared` 已安装
- Quick Tunnel 已验证可用

## 交给 Mac mini Codex 的启动提示词

在 Mac mini 的 Codex 中打开本文件，然后由用户发送：

```text
请读取这份交接文档，将 Android 远程协助项目创建为独立工程，
不要修改 DroidMirror。先检查本机 Android、Java、Gradle 和
cloudflared 环境，然后根据文档列出准确的模块和文件范围。
等待我确认实施范围后，再开始实现、构建和验证。
```

如果用户希望跳过逐文件审批，需要由用户在 Mac mini 的对话中
明确说明“直接修改、无需逐文件确认”。文档本身不代替用户授权。
