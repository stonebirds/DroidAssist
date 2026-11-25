# DroidAssist macOS 部署说明

本文面向需要从源码运行、自定义环境变量或维护服务的技术用户。普通用户请下载 GitHub Release 中内置 Java 和 cloudflared 的 Mac 一键服务包，并阅读 [普通用户快速开始](QUICK_START.md)。

## 0. 一键便携包

Apple 芯片 Mac 使用 `macOS-arm64` 包，Intel Mac 使用 `macOS-x64` 包。完整解压后双击启动、状态或停止 `.command` 文件即可，不要求 Android Studio、JDK、Homebrew 或管理员权限。

便携包数据保存在：

```text
~/Library/Application Support/DroidAssist
```

以下章节介绍源码部署和现有 Mac 开发环境维护方式。

## 1. 部署形态

当前测试环境在一台 Mac 上运行：

- Ktor 服务监听 `127.0.0.1:8080`。
- SQLite 默认保存在 `data/droidassist.db`。
- `cloudflared` Quick Tunnel 把本地服务暴露为临时 `https://*.trycloudflare.com` 地址。
- `launchctl submit` 在当前登录用户会话内运行服务和隧道。

Quick Tunnel 适合开发和双手机验证，不保证固定地址和可用性。正式部署应使用固定域名、受控 Cloudflare Tunnel 或反向代理，并补充 TURN 服务。

## 2. 前置依赖

### 必需软件

1. Android Studio，以及它自带的 JBR 21：

   ```text
   /Applications/Android Studio.app/Contents/jbr/Contents/Home
   ```

2. `cloudflared`：

   ```bash
   brew install cloudflared
   cloudflared --version
   ```

3. `curl`，macOS 已默认提供。
4. 可选的 Android SDK Platform Tools，用于 `adb install`。

部署脚本默认在 `/opt/homebrew/bin` 查找 `cloudflared`，适用于 Apple Silicon Homebrew。Intel Mac 若安装在 `/usr/local/bin`，需要把该目录加入 `deploy/mac/run-tunnel.sh` 的 `PATH`。

### 检查工程环境

```bash
cd /Users/leefly/AndroidStudioProjects/DroidAssist
./gradlew --version
cloudflared --version
curl --version
```

服务模块使用 Java 21 toolchain，Android 模块编译目标为 Java 11 bytecode。

## 3. 一键启动

在工程根目录执行：

```bash
./deploy/mac/start.sh
```

脚本按以下顺序工作：

1. 检查 `http://127.0.0.1:8080/health`。
2. 服务未运行时执行 `./gradlew :server:installDist`。
3. 通过 `launchctl submit` 启动 `com.stonebirds.droidassist.server`。
4. 等待本地健康检查通过。
5. 启动 `com.stonebirds.droidassist.tunnel`。
6. 从 Cloudflare 日志提取公网 URL，写入 `.runtime/public-url`。

成功后终端输出类似：

```text
DroidAssist is ready: https://example.trycloudflare.com
```

当前 Mac 已部署的测试地址可以用以下命令获取，实际结果以命令输出为准：

```bash
./deploy/mac/status.sh
```

## 4. 状态、日志与停止

查看健康状态和当前公网地址：

```bash
./deploy/mac/status.sh
```

直接检查本地服务：

```bash
curl -fsS http://127.0.0.1:8080/health
```

查看日志：

```bash
tail -f .runtime/server.log
tail -f .runtime/cloudflared.log
```

停止 Ktor 和隧道：

```bash
./deploy/mac/stop.sh
```

`launchctl submit` 创建的是当前登录会话中的临时任务，不是永久 LaunchAgent。Mac 重启或用户重新登录后，需要再次运行 `start.sh`。

## 5. 手机连接服务

### 通过 Quick Tunnel

1. 执行 `./deploy/mac/status.sh` 获取 `Public URL`。
2. 在两台手机的 DroidAssist“设置”页填写完整 HTTPS 地址。
3. 两台手机分别点击“注册本机”或“重新连接服务”。
4. 确认两台手机都显示设备已注册和长连接运行中。

Quick Tunnel 每次重建都可能生成新地址。地址变化时，必须在两台手机上同时更新；只更新其中一台会导致两台设备连接到不同的信令入口。

当前 APK 内置一个测试地址作为首次启动默认值，但用户在设置页填写并注册后，地址会保存在 DataStore。要改变源码默认值，修改 `app/src/main/java/com/leefly/droidassist/AssistViewModel.kt` 中的 `AssistUiState.serverUrl` 并重新构建。

### Android 模拟器直连

Android Emulator 用 `10.0.2.2` 访问 Mac 的 loopback：

```text
http://10.0.2.2:8080
```

由于当前 Android Manifest 没有允许明文 HTTP，常规 APK 应优先使用 HTTPS Quick Tunnel。若只做本机模拟器调试，需要为 debug 构建显式配置 Network Security Policy 后再使用 HTTP，不建议把明文策略带入生产构建。

### 局域网真机直连

服务默认只绑定 `127.0.0.1`，局域网真机无法直接访问。开发时可改为：

```bash
DROIDASSIST_HOST=0.0.0.0 ./gradlew :server:run
```

然后使用 Mac 的局域网 IP 和端口。该方式同样涉及 Android 明文 HTTP 策略和 macOS 防火墙，测试完成后应恢复 loopback 绑定。

## 6. 手动开发运行

不使用 `launchctl` 和公网隧道时，可以直接启动 Ktor：

```bash
./gradlew :server:run
```

另一个终端执行：

```bash
curl -fsS http://127.0.0.1:8080/health
```

需要临时公网入口时再运行：

```bash
cloudflared tunnel --url http://127.0.0.1:8080 --no-autoupdate
```

这种方式的日志直接显示在各自终端，不写入 `.runtime/`，除非手动重定向。

## 7. 服务配置

Ktor 服务读取以下环境变量：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DROIDASSIST_HOST` | `127.0.0.1` | HTTP 监听地址 |
| `DROIDASSIST_PORT` | `8080` | HTTP 监听端口 |
| `DROIDASSIST_DB` | `data/droidassist.db` | SQLite 文件路径，相对路径基于工程根目录 |

手动运行示例：

```bash
DROIDASSIST_HOST=127.0.0.1 \
DROIDASSIST_PORT=9090 \
DROIDASSIST_DB=/Users/leefly/Library/Application\ Support/DroidAssist/droidassist.db \
./gradlew :server:run
```

`deploy/mac/run-server.sh` 由 launchd 调用。需要给 launchd 任务设置自定义变量时，可以在启动前使用 `launchctl setenv`，并相应修改健康检查端口；当前 `start.sh` 和 `status.sh` 的健康检查固定为 8080，因此一键脚本建议保持默认端口。

## 8. 数据与备份

默认数据库文件：

```text
data/droidassist.db
```

SQLite 使用 WAL 模式，运行时还可能出现 `droidassist.db-wal` 和 `droidassist.db-shm`。可靠备份应先停止服务：

```bash
./deploy/mac/stop.sh
cp data/droidassist.db /path/to/backup/droidassist-$(date +%Y%m%d-%H%M%S).db
```

数据库和 `.runtime/` 都已被 Git 忽略，不会推送到远端。删除数据库会丢失服务端设备、授权和会话记录；手机中缓存的旧令牌也需要通过“重新连接服务”刷新。

## 9. 更新服务和 APK

拉取代码后重新部署服务：

```bash
git pull --ff-only
./deploy/mac/stop.sh
./deploy/mac/start.sh
```

构建 Android 调试 APK：

```bash
./gradlew :app:assembleDebug
```

安装或覆盖安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

覆盖安装会保留应用 DataStore 和设备身份。卸载后重装会生成新的 installation ID 和 Keystore 身份，需要重新注册。

## 10. 故障排查

### `status.sh` 显示服务不可达

```bash
tail -n 200 .runtime/server.log
lsof -nP -iTCP:8080 -sTCP:LISTEN
./deploy/mac/stop.sh
./deploy/mac/start.sh
```

常见原因是 JBR 路径变化、端口被占用或 Gradle 构建失败。

### 没有生成 Public URL

```bash
which cloudflared
tail -n 200 .runtime/cloudflared.log
```

确认 `cloudflared` 位于脚本 PATH、Mac 可以访问 Cloudflare，并重新运行 `start.sh`。

### 手机显示已注册但收不到请求

- 两台手机必须使用完全相同的服务器地址。
- 地址变化后，两台手机都要点击“重新连接服务”。
- 查看服务端日志是否有 WebSocket 连接和异常。
- 确认系统没有冻结 DroidAssist 的网络或后台活动。

### 允许连接后没有画面

- 被协助端必须在系统录屏弹窗中选择共享整个屏幕并允许。
- 检查“本机”页的屏幕共享和 WebRTC 状态。
- 当前只有 STUN；跨运营商或严格 NAT 网络可能需要 TURN。
- 使用 `adb logcat -s DroidAssistWebRTC` 查看 SDP、ICE 和 DataChannel 状态。

### 有画面但不能控制

- 在被协助端系统设置中确认 DroidAssist 无障碍服务已开启。
- 返回应用后确认“无障碍控制”状态为“已开启”。
- 某些系统安全页面、锁屏和厂商受保护界面不接受无障碍手势，这是 Android 安全边界。
