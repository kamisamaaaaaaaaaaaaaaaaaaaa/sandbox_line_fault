# 部署产物到 Linux 目标机（用 pscp/plink，密码走命令行参数或环境变量）
# 用法：.\deploy-to-linux.ps1 [-HostIp 192.168.193.131] [-User lys2] [-Password <pwd>]
param(
    [string]$HostIp = "192.168.193.131",
    [string]$User = "lys2",
    [Parameter(Mandatory = $true)][string]$Password
)

$root = Split-Path -Parent $PSScriptRoot
$pscp = "A:\putty\pscp.exe"
if (-not (Test-Path $pscp)) { $pscp = "pscp" }

$artifacts = @(
    @{ src = "$root\fault-module\build\libs\fault-module-1.0.0.jar"; dst = "/home/lys2/sandbox/sandbox-modules/" },
    @{ src = "$root\fault-agent\build\libs\fault-agent-1.0.0.jar";  dst = "/home/lys2/" },
    @{ src = "$root\test-app\build\libs\test-app.jar";              dst = "/home/lys2/" }
)

foreach ($a in $artifacts) {
    if (-not (Test-Path $a.src)) {
        Write-Error "artifact missing: $($a.src)"
        exit 1
    }
    Write-Host "uploading $($a.src) -> $($User)@$($HostIp):$($a.dst)"
    & $pscp -batch -pw $Password $a.src "$($User)@$($HostIp):$($a.dst)"
    if ($LASTEXITCODE -ne 0) {
        Write-Error "upload failed: $($a.src)"
        exit 1
    }
}

Write-Host "deploy done. start target app on Linux:"
Write-Host "  java -javaagent:/home/lys2/fault-agent-1.0.0.jar=lib.whitelist=fault-test-lib -jar /home/lys2/test-app.jar"
