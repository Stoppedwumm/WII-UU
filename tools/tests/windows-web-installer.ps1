# Exercise the actual bootstrap and maintenance boundary without downloading a release
# or touching a real installation. Run from the repository root on Windows.
$ErrorActionPreference = 'Stop'
$root = Join-Path ([IO.Path]::GetTempPath()) ('wiiuu-installer-test-' + [Guid]::NewGuid().ToString('N'))
$names = @('WIIUU_HOME', 'LOCALAPPDATA', 'USERPROFILE', 'WIIUU_INSTALL_SCRIPT_URL', 'WIIUU_ZIP_URL', 'WIIUU_TEST_LOG', 'WIIUU_TEST_EXIT')
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
function Assert($condition, $message) { if (-not $condition) { throw $message } }
try {
    New-Item -ItemType Directory -Path $root | Out-Null
    $env:WIIUU_HOME = Join-Path $root 'data'
    $env:LOCALAPPDATA = Join-Path $root 'local'
    $env:USERPROFILE = Join-Path $root 'profile'
    New-Item -ItemType Directory -Path $env:USERPROFILE,$env:LOCALAPPDATA | Out-Null
    $env:WIIUU_TEST_LOG = Join-Path $root 'calls.txt'
    $windowsPowerShell = (Get-Command powershell.exe).Source
    & $windowsPowerShell -NoProfile -ExecutionPolicy Bypass -File (Resolve-Path 'install.ps1').Path -Bootstrap -Yes
    Assert ($LASTEXITCODE -eq 42) 'Real installer must request the package for a fresh install'
    Assert (-not (Test-Path $env:WIIUU_HOME)) 'Maintenance probe must not create the data directory'

    $stub = Join-Path $root 'mock-installer.ps1'
    @'
param([switch]$Bootstrap,[switch]$SkipMaintenance,[switch]$Yes,[switch]$Optimize,[switch]$Uninstall,[switch]$VirtualDisplay,[string]$Roms,[int]$Port)
Add-Content -LiteralPath $env:WIIUU_TEST_LOG -Value ("bootstrap=$Bootstrap skip=$SkipMaintenance optimize=$Optimize uninstall=$Uninstall yes=$Yes virtual=$VirtualDisplay roms=$Roms port=$Port")
if ($Bootstrap) { exit ([int]$env:WIIUU_TEST_EXIT) }
if (-not $SkipMaintenance -or -not (Test-Path (Join-Path $PSScriptRoot 'wiiuu.jar'))) { exit 9 }
exit 0
'@ | Set-Content -LiteralPath $stub -Encoding ASCII
    $env:WIIUU_INSTALL_SCRIPT_URL = $stub
    # An invalid ZIP path proves maintenance never even attempts the download.
    $env:WIIUU_ZIP_URL = Join-Path $root 'nonexistent.zip'
    $env:WIIUU_TEST_EXIT = '0'
    & ./docs/get.ps1 -Optimize -Yes
    Assert ((Get-Content $env:WIIUU_TEST_LOG -Raw) -match 'bootstrap=True skip=False optimize=True') 'Optimize must be forwarded'
    & ./docs/get.ps1 -Uninstall
    Assert ((Get-Content $env:WIIUU_TEST_LOG -Raw) -match 'uninstall=True') 'Uninstall must be forwarded'
    Assert (-not (Test-Path $env:WIIUU_HOME)) 'Maintenance must not stage a ZIP'

    $package = Join-Path $root 'WII-UU-fixture'
    New-Item -ItemType Directory -Path $package | Out-Null
    Copy-Item -LiteralPath $stub -Destination (Join-Path $package 'install.ps1')
    Set-Content -LiteralPath (Join-Path $package 'wiiuu.jar') -Value 'fixture'
    $env:WIIUU_ZIP_URL = Join-Path $root 'fixture.zip'
    Compress-Archive -Path $package -DestinationPath $env:WIIUU_ZIP_URL
    $env:WIIUU_TEST_EXIT = '42'
    $roms = Join-Path $root 'custom games'
    & ./docs/get.ps1 -Yes -VirtualDisplay -Roms $roms -Port 8123
    $calls = Get-Content $env:WIIUU_TEST_LOG -Raw
    Assert ($calls -match 'bootstrap=False skip=True optimize=False uninstall=False yes=True virtual=True') 'Install must run once without a second maintenance menu'
    Assert ($calls.Contains("roms=$roms port=8123")) 'Custom parameters must survive both invocations'
    Assert (@(Get-ChildItem -LiteralPath (Join-Path $env:WIIUU_HOME 'tmp') -Force).Count -eq 0) 'Successful install must remove staging files'

    $env:WIIUU_ZIP_URL = Join-Path $root 'broken.zip'
    Set-Content -LiteralPath $env:WIIUU_ZIP_URL -Value 'not a zip'
    $failed = $false
    try { & ./docs/get.ps1 -Yes } catch { $failed = $true }
    Assert $failed 'Corrupt release must fail'
    Assert (@(Get-ChildItem -LiteralPath (Join-Path $env:WIIUU_HOME 'tmp') -Force).Count -eq 0) 'Failed install must remove staging files'
    Write-Host 'Windows web installer tests passed'
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
}
