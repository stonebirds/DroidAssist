$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$Root = Split-Path $PSScriptRoot -Parent
$DataDir = Join-Path $env:LOCALAPPDATA "DroidAssist"
$RuntimeDir = Join-Path $DataDir "runtime"
$DatabaseDir = Join-Path $DataDir "data"
$ServerPidFile = Join-Path $RuntimeDir "server.pid"
$TunnelPidFile = Join-Path $RuntimeDir "tunnel.pid"
$PublicUrlFile = Join-Path $RuntimeDir "public-url"
$ServerOutLog = Join-Path $RuntimeDir "server.out.log"
$ServerErrorLog = Join-Path $RuntimeDir "server.error.log"
$TunnelOutLog = Join-Path $RuntimeDir "cloudflared.out.log"
$TunnelErrorLog = Join-Path $RuntimeDir "cloudflared.error.log"

function Test-PidFile([string]$Path) {
    if (-not (Test-Path $Path)) { return $false }
    $processId = 0
    if (-not [int]::TryParse((Get-Content $Path -Raw).Trim(), [ref]$processId)) { return $false }
    return $null -ne (Get-Process -Id $processId -ErrorAction SilentlyContinue)
}

function Wait-ForHealth {
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:8080/health" -TimeoutSec 2
            if ($response.StatusCode -eq 200) { return $true }
        } catch { }
        Start-Sleep -Seconds 1
    }
    return $false
}

$required = @(
    (Join-Path $Root "runtime\bin\java.exe"),
    (Join-Path $Root "tools\cloudflared.exe"),
    (Join-Path $Root "server\lib"),
    (Join-Path $Root "web\setup-template.html")
)
foreach ($path in $required) {
    if (-not (Test-Path $path)) {
        throw "缺少运行文件：$path。请重新下载并完整解压服务包。"
    }
}

New-Item -ItemType Directory -Force -Path $RuntimeDir, $DatabaseDir | Out-Null

$serverHealthy = $false
if (Test-PidFile $ServerPidFile) {
    $serverHealthy = Wait-ForHealth
}
if (-not $serverHealthy) {
    Remove-Item $ServerPidFile -Force -ErrorAction SilentlyContinue
    Remove-Item $ServerOutLog, $ServerErrorLog -Force -ErrorAction SilentlyContinue
    $env:DROIDASSIST_HOST = "127.0.0.1"
    $env:DROIDASSIST_PORT = "8080"
    $env:DROIDASSIST_DB = Join-Path $DatabaseDir "droidassist.db"
    $java = Join-Path $Root "runtime\bin\java.exe"
    $classpath = Join-Path $Root "server\lib\*"
    $arguments = "-cp `"$classpath`" com.leefly.droidassist.server.ApplicationKt"
    $serverProcess = Start-Process -FilePath $java -ArgumentList $arguments -WindowStyle Hidden `
        -RedirectStandardOutput $ServerOutLog -RedirectStandardError $ServerErrorLog -PassThru
    Set-Content -Path $ServerPidFile -Value $serverProcess.Id -Encoding ASCII
}

Write-Host "正在检查本地服务..."
if (-not (Wait-ForHealth)) {
    Write-Host "本地服务启动失败，日志位置：$ServerErrorLog" -ForegroundColor Red
    if (Test-Path $ServerErrorLog) { Get-Content $ServerErrorLog -Tail 30 }
    exit 1
}

$publicUrl = $null
if ((Test-PidFile $TunnelPidFile) -and (Test-Path $PublicUrlFile)) {
    $publicUrl = (Get-Content $PublicUrlFile -Raw).Trim()
    Write-Host "公网隧道已经运行。"
} else {
    Remove-Item $TunnelPidFile, $PublicUrlFile, $TunnelOutLog, $TunnelErrorLog -Force -ErrorAction SilentlyContinue
    $cloudflared = Join-Path $Root "tools\cloudflared.exe"
    $tunnelProcess = Start-Process -FilePath $cloudflared `
        -ArgumentList "tunnel --url http://127.0.0.1:8080 --no-autoupdate" `
        -WindowStyle Hidden -RedirectStandardOutput $TunnelOutLog `
        -RedirectStandardError $TunnelErrorLog -PassThru
    Set-Content -Path $TunnelPidFile -Value $tunnelProcess.Id -Encoding ASCII

    Write-Host "正在创建公网地址，通常需要几秒钟..."
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        $logText = ""
        if (Test-Path $TunnelOutLog) { $logText += Get-Content $TunnelOutLog -Raw }
        if (Test-Path $TunnelErrorLog) { $logText += Get-Content $TunnelErrorLog -Raw }
        $matches = [regex]::Matches($logText, "https://[a-z0-9-]+\.trycloudflare\.com")
        if ($matches.Count -gt 0) {
            $publicUrl = $matches[$matches.Count - 1].Value
            break
        }
        Start-Sleep -Seconds 1
    }
    if ([string]::IsNullOrWhiteSpace($publicUrl)) {
        Write-Host "公网隧道启动失败，日志位置：$TunnelErrorLog" -ForegroundColor Red
        if (Test-Path $TunnelErrorLog) { Get-Content $TunnelErrorLog -Tail 30 }
        exit 1
    }
    Set-Content -Path $PublicUrlFile -Value $publicUrl -Encoding ASCII
}

$template = Get-Content (Join-Path $Root "web\setup-template.html") -Raw
$qrUrl = "https://api.qrserver.com/v1/create-qr-code/?size=260x260&data=$publicUrl"
$html = $template.Replace("__PUBLIC_URL__", $publicUrl).Replace("__QR_URL__", $qrUrl)
$setupPage = Join-Path $RuntimeDir "setup.html"
Set-Content -Path $setupPage -Value $html -Encoding UTF8

try { Set-Clipboard -Value $publicUrl } catch { }
Start-Process $setupPage

Write-Host ""
Write-Host "DroidAssist 服务已启动：$publicUrl" -ForegroundColor Green
Write-Host "地址已复制到 Windows 剪贴板，并已打开使用页面。"
Write-Host "日志目录：$RuntimeDir"
