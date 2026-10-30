$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root

$javaCommand = Get-Command java -ErrorAction SilentlyContinue
if (-not $javaCommand) {
    throw 'Java 21 is required. Install a Java 21 JDK (for example, Temurin 21), add java.exe to PATH, then run scripts\run.bat again.'
}
# PowerShell 5 treats Java's version text on stderr as a terminating native-command error
# when ErrorActionPreference is Stop. Capture the merged streams through cmd.exe instead.
$javaOutput = & $env:ComSpec /d /c 'java -XshowSettings:properties -version 2>&1'
$javaVersionMatch = $javaOutput | Select-String 'java.specification.version\s*=\s*([0-9.]+)' | Select-Object -First 1
if (-not $javaVersionMatch) {
    throw 'Could not read the Java version. Install a Java 21 JDK and ensure java.exe is on PATH.'
}
$javaVersion = $javaVersionMatch.Matches[0].Groups[1].Value
$javaMajor = [int]($javaVersion -split '\.')[0]
if ($javaMajor -lt 21) {
    throw "Aurora requires Java 21 or newer; detected Java $javaVersion. Install/select a newer JDK and retry."
}

$gradleVersion = '9.1.0'
# Keep Gradle outside the checkout so it survives repo re-extraction or updates.
if ($env:LOCALAPPDATA) {
    $toolDir = Join-Path $env:LOCALAPPDATA 'Aurora\tools'
} else {
    $toolDir = Join-Path $root '.tools'
}
$gradleHome = Join-Path $toolDir "gradle-$gradleVersion"
$gradleExe = Join-Path $gradleHome 'bin\gradle.bat'
if (-not (Test-Path $gradleExe)) {
    New-Item -ItemType Directory -Force -Path $toolDir | Out-Null
    $zipPath = Join-Path $toolDir "gradle-$gradleVersion-bin.zip"
    # Reuse a cache made by older script versions before downloading again.
    $oldToolDir = Join-Path $root '.tools'
    $oldGradleHome = Join-Path $oldToolDir "gradle-$gradleVersion"
    $oldZipPath = Join-Path $oldToolDir "gradle-$gradleVersion-bin.zip"
    if (Test-Path (Join-Path $oldGradleHome 'bin\gradle.bat')) {
        Copy-Item -Path $oldGradleHome -Destination $toolDir -Recurse -Force
    } else {
        if (-not (Test-Path $zipPath) -and (Test-Path $oldZipPath)) { Copy-Item $oldZipPath $zipPath }
        if (-not (Test-Path $zipPath)) {
            Write-Host "Downloading Gradle $gradleVersion..."
            Invoke-WebRequest "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip" -OutFile $zipPath
        }
        Expand-Archive -Path $zipPath -DestinationPath $toolDir -Force
    }
}

Write-Host 'Building AuroraLobby...'
$gradleCommand = '"' + $gradleExe + '" --no-daemon --console=plain :lobby:jar 2>&1'
& $env:ComSpec /d /c $gradleCommand
if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }

$serverDir = Join-Path $root 'server-local'
$pluginsDir = Join-Path $serverDir 'plugins'
New-Item -ItemType Directory -Force -Path $pluginsDir | Out-Null
$paperJar = Join-Path $serverDir 'paper.jar'
if (-not (Test-Path $paperJar)) {
    Write-Host 'Looking up the latest Paper build for Minecraft 1.21.11...'
    $version = '1.21.11'
    $headers = @{ 'User-Agent' = 'AuroraServerBootstrap/1.0 (https://github.com/idk321687-coder/vulkan-raytracer)' }
    $buildInfo = Invoke-RestMethod "https://fill.papermc.io/v3/projects/paper/versions/$version/builds" -Headers $headers
    $build = $buildInfo | Where-Object { $_.channel -eq 'STABLE' } | Select-Object -First 1
    if (-not $build) { throw "No stable Paper builds found for Minecraft $version." }
    $download = $build.downloads.'server:default'
    if (-not $download.url) { throw "Paper API returned no server download for Minecraft $version build $($build.id)." }
    Write-Host "Downloading Paper $version build $($build.id)..."
    Invoke-WebRequest $download.url -Headers $headers -OutFile $paperJar
}

$pluginJar = Join-Path $root 'packages\lobby\build\libs\lobby-0.1.0.jar'
Copy-Item -Force $pluginJar (Join-Path $pluginsDir 'AuroraLobby.jar')

$propertiesPath = Join-Path $serverDir 'server.properties'
if (-not (Test-Path $propertiesPath)) {
    $properties = @(
        'server-port=25565',
        'server-ip=',
        'online-mode=true',
        'motd=§3✦ §bAURORA §8| §fFrozen-neon minigames',
        'spawn-protection=0',
        'view-distance=8',
        'simulation-distance=6',
        'max-players=20',
        'enable-rcon=false'
    )
    [System.IO.File]::WriteAllLines($propertiesPath, $properties, [System.Text.UTF8Encoding]::new($false))
}

$eulaPath = Join-Path $serverDir 'eula.txt'
if (-not (Test-Path $eulaPath) -or (Get-Content $eulaPath -Raw) -notmatch 'eula=true') {
    Write-Host ''
    Write-Host 'To start the server, you must review and agree to the Minecraft EULA:'
    Write-Host 'https://www.minecraft.net/eula'
    $answer = Read-Host 'Do you agree to the EULA? Type Y to continue'
    if ($answer -notmatch '^(y|yes)$') {
        [System.IO.File]::WriteAllText($eulaPath, "eula=false`r`n", [System.Text.UTF8Encoding]::new($false))
        Write-Host 'Not starting. You can review the EULA and run this script again later.'
        exit 0
    }
    [System.IO.File]::WriteAllText($eulaPath, "eula=true`r`n", [System.Text.UTF8Encoding]::new($false))
}

Write-Host ''
Write-Host 'Starting Aurora locally on port 25565. Connect with Minecraft 1.21.11 at localhost:25565.'
Write-Host 'Stop the server with Ctrl+C. Runtime files are in server-local\.'
Set-Location $serverDir
$serverCommand = 'java -Xms2G -Xmx2G -XX:+UseG1GC -jar "' + $paperJar + '" nogui 2>&1'
& $env:ComSpec /d /c $serverCommand
exit $LASTEXITCODE
