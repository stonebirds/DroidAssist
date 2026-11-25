$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$RuntimeDir = Join-Path $env:LOCALAPPDATA "DroidAssist\runtime"
try {
    $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:8080/health" -TimeoutSec 3
    if ($response.StatusCode -ne 200) { throw "not healthy" }
    Write-Host "本地服务：运行正常" -ForegroundColor Green
    $publicUrlFile = Join-Path $RuntimeDir "public-url"
    if (Test-Path $publicUrlFile) {
        $publicUrl = (Get-Content $publicUrlFile -Raw).Trim()
        Write-Host "公网地址：$publicUrl"
        try { Set-Clipboard -Value $publicUrl } catch { }
        $setupPage = Join-Path $RuntimeDir "setup.html"
        if (Test-Path $setupPage) { Start-Process $setupPage }
    } else {
        Write-Host "公网地址：尚未生成"
    }
    Write-Host "日志目录：$RuntimeDir"
} catch {
    Write-Host "DroidAssist 当前没有运行，请双击启动脚本。" -ForegroundColor Yellow
    exit 1
}
