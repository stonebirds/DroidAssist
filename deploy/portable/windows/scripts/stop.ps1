$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$RuntimeDir = Join-Path $env:LOCALAPPDATA "DroidAssist\runtime"

function Stop-FromPidFile([string]$Label, [string]$Path) {
    if (-not (Test-Path $Path)) { return }
    $processId = 0
    if ([int]::TryParse((Get-Content $Path -Raw).Trim(), [ref]$processId)) {
        $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if ($null -ne $process) {
            Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
            Write-Host "$Label 已停止。"
        }
    }
    Remove-Item $Path -Force -ErrorAction SilentlyContinue
}

Stop-FromPidFile "公网隧道" (Join-Path $RuntimeDir "tunnel.pid")
Stop-FromPidFile "本地服务" (Join-Path $RuntimeDir "server.pid")
Remove-Item (Join-Path $RuntimeDir "public-url") -Force -ErrorAction SilentlyContinue

$dataDir = Join-Path $env:LOCALAPPDATA "DroidAssist\data"
Write-Host "DroidAssist 已停止，数据库仍保存在：$dataDir"
