# Downloads Paper 26.3 (once), installs the freshly built plugin and starts a local offline-mode server on port 34127.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$run = Join-Path $root 'run'
$pluginData = Join-Path $run 'plugins\McServerAuth'
New-Item -ItemType Directory -Force $pluginData | Out-Null

$headers = @{ 'User-Agent' = 'mc-server-auth-dev/0.1 (local test server)' }
$jar = Join-Path $run 'paper.jar'
if (-not (Test-Path $jar)) {
    $build = Invoke-RestMethod 'https://fill.papermc.io/v3/projects/paper/versions/26.3/builds/latest' -Headers $headers
    $download = $build.downloads.'server:default'
    Invoke-WebRequest $download.url -OutFile $jar -Headers $headers
    $actual = (Get-FileHash $jar -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $download.checksums.sha256) {
        Remove-Item $jar
        throw 'Paper jar checksum mismatch; download removed.'
    }
    Write-Host "Downloaded Paper 26.3 build $($build.id)"
}

$eula = Join-Path $run 'eula.txt'
if (-not ((Test-Path $eula) -and (Select-String -Path $eula -Pattern '^eula=true' -Quiet))) {
    Write-Host 'Read the Minecraft EULA at https://aka.ms/MinecraftEULA.'
    Write-Host "If you agree, create $eula containing the line: eula=true"
    exit 1
}

$props = Join-Path $run 'server.properties'
if (-not (Test-Path $props)) {
    Set-Content $props @('online-mode=false', 'server-port=34127', 'server-ip=127.0.0.1', 'motd=mc-server-auth test')
}

$config = Join-Path $pluginData 'config.yml'
if (-not (Test-Path $config)) {
    Copy-Item (Join-Path $root 'dev\test-config.yml') $config
}

$built = Get-ChildItem (Join-Path $root 'build\libs') -Filter 'mc-server-auth-*.jar' | Select-Object -First 1
if (-not $built) { throw 'Plugin jar not found. Run .\gradlew.bat build first.' }
Copy-Item $built.FullName (Join-Path $run 'plugins\McServerAuth.jar') -Force

Push-Location $run
try {
    java -Xms1G -Xmx2G -jar paper.jar --nogui
} finally {
    Pop-Location
}
