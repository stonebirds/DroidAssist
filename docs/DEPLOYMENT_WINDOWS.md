# DroidAssist Windows 部署说明

本文面向需要从源码运行、自定义端口、替换数据库位置或二次打包的技术用户。普通用户请使用 GitHub Release 中的 Windows 一键服务包，并阅读 [普通用户快速开始](QUICK_START.md)。

## 1. 一键便携包

Windows x64 便携包已经包含：

- Azul Zulu OpenJDK JRE 21。
- Ktor 服务端及全部运行依赖。
- 官方 `cloudflared.exe`。
- 启动、停止和状态脚本。
- 本地使用页面和第三方许可证说明。

双击脚本后，运行数据保存在：

```text
%LOCALAPPDATA%\DroidAssist
```

目录结构：

```text
data\droidassist.db
runtime\server.out.log
runtime\server.error.log
runtime\cloudflared.out.log
runtime\cloudflared.error.log
runtime\public-url
```

脚本只绑定 `127.0.0.1:8080`，外部访问由 Cloudflare 出站隧道提供，通常不需要开放 Windows 入站防火墙端口。

## 2. 源码运行前置条件

- 64 位 Windows 10 或 Windows 11。
- JDK 21。
- Git，可选；也可以直接下载源码 ZIP。
- `cloudflared.exe`，仅公网访问时需要。
- Android Studio，仅在需要构建 APK 时需要。

确认环境：

```powershell
java -version
.\gradlew.bat --version
cloudflared --version
```

## 3. 手动启动服务

在 PowerShell 中进入工程根目录：

```powershell
$env:DROIDASSIST_HOST = "127.0.0.1"
$env:DROIDASSIST_PORT = "8080"
$env:DROIDASSIST_DB = "$PWD\data\droidassist.db"

.\gradlew.bat :server:run
```

另开 PowerShell 检查：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/health
```

启动临时公网入口：

```powershell
cloudflared tunnel --url http://127.0.0.1:8080 --no-autoupdate
```

将终端输出的 `https://*.trycloudflare.com` 地址填入两台手机。

## 4. 构建可运行分发目录

```powershell
.\gradlew.bat :server:installDist
```

生成目录：

```text
server\build\install\server
```

在已配置 Java 21 的环境中运行：

```powershell
.\server\build\install\server\bin\server.bat
```

## 5. 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DROIDASSIST_HOST` | `127.0.0.1` | HTTP 监听地址 |
| `DROIDASSIST_PORT` | `8080` | HTTP 监听端口 |
| `DROIDASSIST_DB` | `data/droidassist.db` | SQLite 文件路径 |

如果要让局域网设备直接访问，可以绑定 `0.0.0.0`，但需要同时处理 Windows 防火墙、Android 明文 HTTP 策略和局域网安全。默认 Quick Tunnel 方案不需要这样做。

## 6. 便携包构建

仓库中的 `deploy/package/build-portable.sh` 可以在 macOS 或 Linux 构建 Windows 包。脚本会下载固定版本的 Windows Azul Zulu JRE 和 cloudflared，验证 SHA-256 后再打包：

```bash
./deploy/package/build-portable.sh windows-x64
```

输出位于 `dist/`。便携包内容不提交到 Git，通过 GitHub Release 分发。

## 7. 长期后台运行

一键包使用当前 Windows 用户下的后台进程，不安装系统服务，也不要求管理员权限。电脑注销或重启后需要再次双击启动。

高级部署可选择：

- Windows 任务计划程序：用户登录后运行启动脚本。
- WinSW 或 NSSM：安装为 Windows Service，需要管理员权限。
- 固定 Cloudflare Tunnel：使用自己的 Cloudflare 账号和域名，避免 Quick Tunnel 地址变化。

这些方式涉及机器策略和长期凭据，不作为普通用户默认配置。

## 8. 数据备份

停止服务后备份：

```powershell
Copy-Item "$env:LOCALAPPDATA\DroidAssist\data\droidassist.db" "D:\Backup\droidassist.db"
```

SQLite 使用 WAL 模式。运行时可能存在 `-wal` 和 `-shm` 文件，因此不要在服务运行过程中只复制主数据库文件。

## 9. 故障排查

### 端口被占用

```powershell
Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue
```

停止占用端口的旧进程，或在源码运行模式下修改 `DROIDASSIST_PORT`。便携包脚本固定使用 8080。

### Quick Tunnel 没有地址

检查：

```text
%LOCALAPPDATA%\DroidAssist\runtime\cloudflared.error.log
```

确认 Windows 可以访问 Cloudflare，且安全软件没有拦截官方 `cloudflared.exe`。

### 服务启动失败

检查 `server.error.log`。常见原因是软件包没有完整解压、文件被安全软件隔离或 8080 端口被占用。
