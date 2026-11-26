# WII-UU web installer (Windows PowerShell 5.1+).
# & ([scriptblock]::Create((irm https://wiiuu.stoppedwumm.net/get.ps1)))
[CmdletBinding()]
param(
    [string]$Roms,
    [ValidateRange(1,65535)][int]$Port = 8080,
    [switch]$Uninstall,
    [switch]$Optimize,
    [switch]$VirtualDisplay,
    [switch]$Yes
)
$ErrorActionPreference = 'Stop'
if ($Uninstall -and $Optimize) { throw 'Choose either -Uninstall or -Optimize.' }

# Windows PowerShell 5.1 can otherwise negotiate legacy TLS.
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$installerUrl = if ($env:WIIUU_INSTALL_SCRIPT_URL) { $env:WIIUU_INSTALL_SCRIPT_URL } else { 'https://wiiuu.stoppedwumm.net/download/install.ps1' }
$zipUrl = if ($env:WIIUU_ZIP_URL) { $env:WIIUU_ZIP_URL } else { 'https://wiiuu.stoppedwumm.net/download/WII-UU-latest.zip' }

function Get-WebFile([string]$Source, [string]$Destination) {
    # A local path override is useful for offline recovery and integration tests.
    if (Test-Path -LiteralPath $Source -PathType Leaf) {
        Copy-Item -LiteralPath $Source -Destination $Destination
    } else {
        Invoke-WebRequest -UseBasicParsing -Uri $Source -OutFile $Destination
    }
}

$forward = @()
if ($PSBoundParameters.ContainsKey('Roms')) { $forward += @('-Roms', $Roms) }
if ($PSBoundParameters.ContainsKey('Port')) { $forward += @('-Port', "$Port") }
if ($Uninstall) { $forward += '-Uninstall' }
if ($Optimize) { $forward += '-Optimize' }
if ($VirtualDisplay) { $forward += '-VirtualDisplay' }
if ($Yes) { $forward += '-Yes' }
$powerShell = (Get-Command powershell.exe -ErrorAction Stop).Source

# Keep bootstrap tiny, even if the data drive is nearly full. Do not stage a ZIP
# until the existing installer's maintenance menu explicitly requests it (exit 42).
$bootstrap = Join-Path ([IO.Path]::GetTempPath()) ('wiiuu-bootstrap.' + [Guid]::NewGuid().ToString('N'))
$stage = $null
try {
    New-Item -ItemType Directory -Path $bootstrap | Out-Null
    $script = Join-Path $bootstrap 'install.ps1'
    Get-WebFile $installerUrl $script
    & $powerShell -NoProfile -ExecutionPolicy Bypass -File $script -Bootstrap @forward
    $status = $LASTEXITCODE
    if ($status -eq 0) { return }
    if ($status -ne 42) { throw "WII-UU maintenance failed (exit $status)." }
    if ($Uninstall -or $Optimize) { throw 'Maintenance must not download a release ZIP.' }

    $data = if ($env:WIIUU_HOME) { $env:WIIUU_HOME } else { Join-Path $env:USERPROFILE '.wiiuu' }
    $location = Join-Path $env:USERPROFILE '.wiiuu-location'
    if (-not $env:WIIUU_HOME -and (Test-Path -LiteralPath $location -PathType Leaf)) {
        $data = [IO.File]::ReadAllText($location).TrimEnd([char]13, [char]10)
        if (-not [IO.Path]::IsPathRooted($data) -or -not (Test-Path -LiteralPath $data -PathType Container)) {
            throw "WII-UU storage is unavailable: $data"
        }
    }
    $tempRoot = Join-Path $data 'tmp'
    New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
    $stage = Join-Path $tempRoot ('wiiuu-web.' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $stage | Out-Null
    $zip = Join-Path $stage 'wiiuu.zip'
    Write-Host '==> Downloading WII-UU' -ForegroundColor Cyan
    Get-WebFile $zipUrl $zip
    $expanded = Join-Path $stage 'release'
    Expand-Archive -LiteralPath $zip -DestinationPath $expanded
    Remove-Item -LiteralPath $zip -Force
    $packages = @(Get-ChildItem -LiteralPath $expanded -Directory | Where-Object {
        $_.Name -like 'WII-UU-*' -and (Test-Path -LiteralPath (Join-Path $_.FullName 'install.ps1') -PathType Leaf)
    })
    if ($packages.Count -ne 1) { throw 'Invalid WII-UU release archive.' }
    $install = Join-Path $packages[0].FullName 'install.ps1'
    # Legacy stable archives may not yet accept -SkipMaintenance. Use the
    # freshly downloaded installer with the extracted files in that case.
    Copy-Item -LiteralPath $script -Destination $install -Force
    & $powerShell -NoProfile -ExecutionPolicy Bypass -File $install -SkipMaintenance @forward
    if ($LASTEXITCODE -ne 0) { throw "WII-UU installation failed (exit $LASTEXITCODE)." }
} finally {
    if ($stage -and (Test-Path -LiteralPath $stage)) {
        Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $bootstrap) {
        Remove-Item -LiteralPath $bootstrap -Recurse -Force -ErrorAction SilentlyContinue
    }
}
