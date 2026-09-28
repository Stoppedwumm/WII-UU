# WII-UU installer for Windows.
#   powershell -ExecutionPolicy Bypass -File install.ps1 [-Roms D:\Games\roms] [-Port 8080] [-Uninstall] [-Yes]
# or just double-click install.bat
param(
    [string]$Roms = (Join-Path $env:USERPROFILE "WiiUU\roms"),
    [int]$Port = 8080,
    [switch]$Uninstall,
    [switch]$Yes
)
$ErrorActionPreference = "Stop"
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Prefix = Join-Path $env:LOCALAPPDATA "WII-UU"
$ConfDir = if ($env:WIIUU_HOME) { $env:WIIUU_HOME } else { Join-Path $env:USERPROFILE ".wiiuu" }
$StartMenu = Join-Path ([Environment]::GetFolderPath("Programs")) "WII-UU.lnk"
$DesktopLnk = Join-Path ([Environment]::GetFolderPath("Desktop")) "WII-UU.lnk"
$Systems = "nes snes gb n64 gba gc nds wii 3ds wiiu switch sms genesis saturn dc ps1 ps2 psp ps3 ps4".Split(" ")

function Say($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Ok($m) { Write-Host "  + $m" -ForegroundColor Green }
function Warn($m) { Write-Host "  ! $m" -ForegroundColor Yellow }
function Ask($q) { if ($Yes) { return $true }; return (Read-Host "$q [y/N]") -match '^[Yy]' }

if ($Uninstall) {
    Say "Removing WII-UU"
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $Prefix, $StartMenu, $DesktopLnk
    Ok "Removed. Your ROMs ($Roms) and settings ($ConfDir) were kept."
    exit 0
}

Write-Host "`n  WII-UU installer`n" -ForegroundColor Cyan

# ---------------------------------------------------------------- Java
function Get-JavaMajor {
    $java = Get-Command java -ErrorAction SilentlyContinue
    if (-not $java) { return 0 }
    $prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"   # java -version writes to stderr
    $out = (& java -version 2>&1 | Out-String)
    $ErrorActionPreference = $prev
    if ($out -match 'version "(\d+)(\.(\d+))?') {
        if ($Matches[1] -eq "1") { return [int]$Matches[3] } else { return [int]$Matches[1] }
    }
    return 0
}

Say "Checking Java"
$jv = Get-JavaMajor
if ($jv -lt 17) {
    Warn "Java 17 or newer is required (found: $jv)."
    if ((Get-Command winget -ErrorAction SilentlyContinue) -and (Ask "Install Eclipse Temurin 21 with winget now?")) {
        winget install --id EclipseAdoptium.Temurin.21.JRE -e --accept-source-agreements --accept-package-agreements
        $env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [Environment]::GetEnvironmentVariable("Path", "User")
        $jv = Get-JavaMajor
    }
    if ($jv -lt 17) { throw "Please install Java 17+ from https://adoptium.net and run this installer again." }
}
Ok "Java $jv"
$javaw = (Get-Command javaw -ErrorAction SilentlyContinue).Source
if (-not $javaw) { $javaw = (Get-Command java).Source }

# ---------------------------------------------------------------- files
Say "Installing to $Prefix"
$jar = @((Join-Path $Here "wiiuu.jar"), (Join-Path $Here "build\wiiuu.jar")) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $jar) { throw "wiiuu.jar not found next to install.ps1 (use the release zip, or build with build.sh first)." }
New-Item -ItemType Directory -Force -Path $Prefix | Out-Null
Copy-Item -Force $jar (Join-Path $Prefix "wiiuu.jar")
$ErrorActionPreference = "Continue"
& java -jar (Join-Path $Prefix "wiiuu.jar") --write-icon (Join-Path $Prefix "wiiuu.png") 2>&1 | Out-Null
$ErrorActionPreference = "Stop"
Set-Content -Encoding ASCII -Path (Join-Path $Prefix "wiiuu.cmd") -Value "@echo off`r`nstart `"`" `"$javaw`" -jar `"$Prefix\wiiuu.jar`" %*"
Ok "Launcher: $Prefix\wiiuu.cmd"

Say "Creating ROM folders in $Roms"
foreach ($s in $Systems) { New-Item -ItemType Directory -Force -Path (Join-Path $Roms $s) | Out-Null }
Ok "Drop games into $Roms\<system> (nes, snes, n64, gc, wii, wiiu, switch, ps1 ... ps4)"

# settings: only add keys that are not set yet (Properties format needs \\ in paths)
New-Item -ItemType Directory -Force -Path $ConfDir | Out-Null
$conf = Join-Path $ConfDir "config.properties"
if (-not (Test-Path $conf)) { New-Item -ItemType File -Path $conf | Out-Null }
$lines = @(Get-Content $conf)
function Set-Conf($key, $value) {
    if (-not ($script:lines | Where-Object { $_ -like "$key=*" })) {
        $script:lines += "$key=" + $value.Replace("\", "\\")
    }
}
Set-Conf "roms.base" $Roms
Set-Conf "server.port" "$Port"
Set-Content -Path $conf -Value $lines -Encoding ASCII
Ok "Settings: $conf"

# ---------------------------------------------------------------- shortcuts
$shell = New-Object -ComObject WScript.Shell
foreach ($lnkPath in @($StartMenu, $DesktopLnk)) {
    $lnk = $shell.CreateShortcut($lnkPath)
    $lnk.TargetPath = $javaw
    $lnk.Arguments = "-jar `"$Prefix\wiiuu.jar`""
    $lnk.WorkingDirectory = $Prefix
    $lnk.Description = "WII-UU emulator launcher"
    $lnk.Save()
}
Ok "Shortcuts on the Start menu and Desktop"

# ---------------------------------------------------------------- firewall
Say "Allowing phones to reach the GamePad server (TCP $Port)"
$rule = "WII-UU GamePad ($Port)"
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if ($isAdmin) {
    if (-not (Get-NetFirewallRule -DisplayName $rule -ErrorAction SilentlyContinue)) {
        # 8443: the secure GamePad address, needed for gyro on phones
        New-NetFirewallRule -DisplayName $rule -Direction Inbound -Protocol TCP -LocalPort $Port,8443 -Action Allow -Profile Private | Out-Null
    }
    Ok "Firewall rule '$rule' (private networks)"
} else {
    Warn "Not running as administrator: when Windows asks on first start, allow Java on Private networks."
}

Write-Host "`nDone! Start WII-UU from the Start menu or Desktop." -ForegroundColor Green
Write-Host "Set your emulators' paths in Settings (F1). On the TV press + / F2 and scan the QR code with your phone.`n"
