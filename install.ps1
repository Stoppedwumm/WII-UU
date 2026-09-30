# WII-UU installer for Windows.
#   powershell -ExecutionPolicy Bypass -File install.ps1 [-Roms D:\Games\roms] [-Port 8080] [-VirtualDisplay] [-Uninstall] [-Yes]
#   -VirtualDisplay: also install a virtual display (split DS/3DS screens: top on the TV, touch screen on the phone)
# or just double-click install.bat
param(
    [string]$Roms = (Join-Path $env:USERPROFILE "WiiUU\roms"),
    [int]$Port = 8080,
    [switch]$Uninstall,
    [switch]$VirtualDisplay,
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
Set-Content -Encoding ASCII -Path (Join-Path $Prefix "wiiuu.cmd") -Value "@echo off`r`nstart `"`" `"$javaw`" -Xmx384m -jar `"$Prefix\wiiuu.jar`" %*"
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
    $lnk.Arguments = "-Xmx384m -jar `"$Prefix\wiiuu.jar`""
    $lnk.WorkingDirectory = $Prefix
    $lnk.Description = "WII-UU emulator launcher"
    $lnk.Save()
}
Ok "Shortcuts on the Start menu and Desktop"

# ---------------------------------------------------------------- GamePad screen speed
if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) {
    if ((Get-Command winget -ErrorAction SilentlyContinue) -and (Ask "Install ffmpeg for a smooth GamePad screen on your phone?")) {
        winget install --id Gyan.FFmpeg -e --accept-source-agreements --accept-package-agreements
    } else {
        Warn "Install ffmpeg (winget install Gyan.FFmpeg) for a much higher GamePad frame rate."
    }
}

# ---------------------------------------------------------------- virtual display (split DS/3DS screens)
# RetroArch mode puts a DS/3DS game's window on a display the TV doesn't show; Windows needs a driver
# for one. WII-UU switches it on only while such a game runs. Never installed by -Yes alone (updates).
function Test-VirtualDisplay {
    [bool](Get-PnpDevice -Class Display -ErrorAction SilentlyContinue | Where-Object { $_.FriendlyName -match 'Virtual Display Driver|IddSampleDriver' })
}
$wantVdd = $VirtualDisplay
if (-not $wantVdd -and -not $Yes -and -not (Test-VirtualDisplay)) {
    $wantVdd = Ask "Install a virtual display, so DS and 3DS games show the top screen on the TV and the touch screen on the phone? (RetroArch mode; asks for administrator rights once)"
}
if ($wantVdd) {
    if (Test-VirtualDisplay) {
        Ok "Virtual display already installed"
    } elseif ($env:PROCESSOR_ARCHITECTURE -ne "AMD64") {
        Warn "The virtual display driver is installed for x64 PCs only; install it by hand: github.com/VirtualDrivers/Virtual-Display-Driver"
    } else {
        Say "Installing the Virtual Display Driver (github.com/VirtualDrivers/Virtual-Display-Driver, MIT licence, signed)"
        $vdd = Join-Path $env:TEMP "wiiuu-vdd"
        New-Item -ItemType Directory -Force -Path $vdd | Out-Null
        $vddScript = Join-Path $vdd "install-vdd.ps1"
        # the project's own silent install (Community Scripts/silent-install.ps1): its signing certificate, then the driver via nefcon
        Set-Content -Path $vddScript -Encoding UTF8 -Value @'
$ErrorActionPreference = "Stop"
$dir = Join-Path $env:TEMP "wiiuu-vdd"
Invoke-WebRequest -UseBasicParsing -Uri "https://github.com/nefarius/nefcon/releases/download/v1.14.0/nefcon_v1.14.0.zip" -OutFile "$dir\nefcon.zip"
Expand-Archive -Force -Path "$dir\nefcon.zip" -DestinationPath $dir
Invoke-WebRequest -UseBasicParsing -Uri "https://github.com/VirtualDrivers/Virtual-Display-Driver/releases/download/25.7.23/VirtualDisplayDriver-x86.Driver.Only.zip" -OutFile "$dir\driver.zip"
Expand-Archive -Force -Path "$dir\driver.zip" -DestinationPath $dir
$certs = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2Collection
$certs.Import([System.IO.File]::ReadAllBytes("$dir\VirtualDisplayDriver\mttvdd.cat"))
foreach ($c in $certs) {
    $f = "$dir\$($c.Thumbprint).cer"
    [System.IO.File]::WriteAllBytes($f, $c.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Cert))
    Import-Certificate -FilePath $f -CertStoreLocation "Cert:\LocalMachine\TrustedPublisher" | Out-Null
}
Push-Location $dir
& "$dir\x64\nefconw.exe" install .\VirtualDisplayDriver\MttVDD.inf "Root\MttVDD"
Start-Sleep -Seconds 8
Pop-Location
'@
        try {
            Start-Process powershell -Verb RunAs -Wait -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$vddScript`""
        } catch {
            Warn "Administrator rights were not given: the virtual display was not installed"
        }
        if (Test-VirtualDisplay) { Ok "Virtual display installed" } else { Warn "The virtual display did not install; see github.com/VirtualDrivers/Virtual-Display-Driver" }
        Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $vdd
    }
    if (Test-VirtualDisplay) {
        # off until a game needs it, so the desktop stays as it was
        $ErrorActionPreference = "Continue"
        & java -jar (Join-Path $Prefix "wiiuu.jar") --virtual-display-off 2>&1 | Out-Null
        $ErrorActionPreference = "Stop"
        Ok "WII-UU switches the virtual display on only while a DS/3DS game runs in RetroArch mode"
    }
}

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
