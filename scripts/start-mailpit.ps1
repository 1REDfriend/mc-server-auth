# Starts Mailpit, a fake mail server that catches every email locally. No Docker needed.
# SMTP: 127.0.0.1:41853   Web inbox: http://127.0.0.1:41854
# The first run downloads the official Windows build from GitHub into tools\mailpit (git-ignored).
$ErrorActionPreference = 'Stop'
$version = 'v1.31.6'
$sha256 = '5d5ad094bfb3bac9004f9d1105d2335fea9f8952c09951df73ae0a280e34d3b1'
$root = Split-Path -Parent $PSScriptRoot
$dir = Join-Path $root 'tools\mailpit'
$exe = Join-Path $dir 'mailpit.exe'

if (-not (Test-Path $exe)) {
    New-Item -ItemType Directory -Force $dir | Out-Null
    $zip = Join-Path $dir 'mailpit-windows-amd64.zip'
    Invoke-WebRequest "https://github.com/axllent/mailpit/releases/download/$version/mailpit-windows-amd64.zip" -OutFile $zip
    $actual = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $sha256) {
        Remove-Item $zip
        throw 'Mailpit download checksum mismatch; download removed.'
    }
    Expand-Archive $zip -DestinationPath $dir -Force
    Remove-Item $zip
    if (-not (Test-Path $exe)) { throw "mailpit.exe not found in $dir after extracting." }
}

$listening = Get-NetTCPConnection -LocalPort 41853 -State Listen -ErrorAction SilentlyContinue
if ($listening) {
    Write-Host 'Something is already listening on port 41853 (Mailpit is probably running).'
} else {
    Start-Process $exe -ArgumentList '--smtp', '127.0.0.1:41853', '--listen', '127.0.0.1:41854' -WindowStyle Minimized
    Start-Sleep -Seconds 2
    if (-not (Get-NetTCPConnection -LocalPort 41853 -State Listen -ErrorAction SilentlyContinue)) {
        throw 'Mailpit did not start listening on port 41853.'
    }
}
Write-Host 'Mailpit running. Inbox: http://127.0.0.1:41854  (close its minimized window to stop it)'
