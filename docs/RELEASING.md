# DroidAssist 发布维护说明

本文面向项目维护者，说明如何生成正式签名 APK、三个桌面便携服务包以及 GitHub Release。

## 1. 发布产物

每个版本应包含：

```text
DroidAssist-<version>-release.apk
DroidAssist-Server-macOS-arm64-<version>.zip
DroidAssist-Server-macOS-x64-<version>.zip
DroidAssist-Server-Windows-x64-<version>.zip
APK-SHA256.txt
SHA256SUMS.txt
```

二进制产物存放在本机 `dist/` 并上传为 GitHub Release Assets，不提交到 Git 历史。

## 2. 正式 APK 签名密钥

首次发布只执行一次：

```bash
./deploy/package/create-signing-key.sh
```

脚本生成：

```text
~/.droidassist-signing/droidassist-release.jks
```

密码保存在 macOS Keychain，服务名为：

```text
com.stonebirds.droidassist.release
```

签名密钥不进入仓库。必须把 JKS 和密码分别备份到可靠的离线位置；丢失密钥后无法为已安装用户提供可覆盖升级的 APK。

查看证书指纹不会泄露私钥：

```bash
keytool -list -v -keystore ~/.droidassist-signing/droidassist-release.jks
```

## 3. 构建正式 APK

```bash
./deploy/package/build-release-apk.sh
```

脚本从 macOS Keychain 读取密码，通过以下环境变量传给 Gradle：

- `DROIDASSIST_KEYSTORE_PATH`
- `DROIDASSIST_KEYSTORE_PASSWORD`
- `DROIDASSIST_KEY_ALIAS`
- `DROIDASSIST_KEY_PASSWORD`

Gradle 配置不会打印或保存这些值。脚本构建后使用 Android SDK `apksigner` 独立验证签名。

## 4. 构建桌面便携包

构建全部平台：

```bash
./deploy/package/build-portable.sh
```

只构建指定平台：

```bash
./deploy/package/build-portable.sh macos-arm64
./deploy/package/build-portable.sh macos-x64
./deploy/package/build-portable.sh windows-x64
```

构建脚本会：

1. 编译 Ktor `installDist`。
2. 下载固定版本的 Azul Zulu OpenJDK JRE。
3. 下载固定版本的官方 cloudflared。
4. 校验所有下载文件的 SHA-256。
5. 复制一键脚本、使用页面、许可证和第三方声明。
6. 在 `dist/` 生成 ZIP 和校验文件。

依赖版本和校验值写在 `build-portable.sh` 中，升级依赖时必须同时更新 URL、版本和 SHA-256。

## 5. 发布前验证

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :server:test :server:installDist
git diff --check
```

至少在以下环境验证：

- 当前架构 Mac：内置 Java 启动、健康检查、隧道地址、停止和数据保留。
- Windows 10 或 11 x64：解压、双击启动、PowerShell 脚本、浏览器页面和停止。
- 两台 Android 真机：覆盖安装、注册、共享、画面、控制和挂断。

跨平台包能在 macOS/Linux 生成，但 Windows PowerShell 行为仍应在真实 Windows 或 Windows CI 上验证。

## 6. GitHub Release

确认 `app/build.gradle.kts` 的 `versionCode` 和 `versionName` 已更新，代码已提交并推送，然后创建同版本 tag：

```bash
git tag -a v1.1.0 -m "DroidAssist 1.1.0"
git push origin v1.1.0
```

GitHub CLI 登录后上传：

```bash
gh release create v1.1.0 dist/* \
  --repo stonebirds/DroidAssist \
  --title "DroidAssist 1.1.0" \
  --notes-file docs/releases/v1.1.0.md
```

仓库的 `Build release artifacts` workflow 可以在 GitHub Actions 页面手动运行。输入 tag，并选择是否直接发布。自动构建需要配置以下 GitHub Actions Secrets：

- `DROIDASSIST_KEYSTORE_BASE64`
- `DROIDASSIST_KEYSTORE_PASSWORD`
- `DROIDASSIST_KEY_ALIAS`
- `DROIDASSIST_KEY_PASSWORD`

不要把 JKS、密码、Base64 内容或 `keystore.properties` 提交到仓库。

## 7. 兼容性约束

- 已发布 APK 必须始终使用同一签名密钥。
- `versionCode` 每次发布必须递增。
- 服务端协议发生不兼容变化时，应同步更新 APK 和桌面服务包。
- Quick Tunnel 不保证固定域名或 SLA；正式运营需要固定隧道或自有域名。
- Mac/Windows 包未经商业代码签名时，首次启动可能触发 Gatekeeper 或 SmartScreen。
