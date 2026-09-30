# WII-UU: installs the Virtual Display Driver (github.com/VirtualDrivers/Virtual-Display-Driver, MIT, signed)
# for split DS/3DS screens. Run with administrator rights; WII-UU starts it that way (Windows asks once).
# The project's own silent install (Community Scripts/silent-install.ps1): its signing certificate, then nefcon.
$ErrorActionPreference = "Stop"
$dir = Join-Path $env:TEMP "wiiuu-vdd"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$log = Join-Path $dir "install.log"
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
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
    $arch = if ($env:PROCESSOR_ARCHITECTURE -eq "ARM64") { "ARM64" } else { "x64" }
    Push-Location $dir
    & "$dir\$arch\nefconw.exe" install .\VirtualDisplayDriver\MttVDD.inf "Root\MttVDD"
    Start-Sleep -Seconds 8
    Pop-Location
    "ok" | Set-Content $log
} catch {
    "failed: $($_.Exception.Message)" | Set-Content $log
}
