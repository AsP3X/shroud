#Requires -Version 5.1
<#
.SYNOPSIS
    One-command deploy for the Shroud API + web client.

.DESCRIPTION
    Windows counterpart of ./deploy.sh. Docker Desktop (Linux containers) required.

.EXAMPLE
    .\deploy.ps1
    .\deploy.ps1 -Init
    .\deploy.ps1 -Logs api
#>
param(
    [switch]$Init,
    [switch]$Status,
    [switch]$Ps,
    [switch]$Logs,
    [switch]$Restart,
    [switch]$Rebuild,
    [switch]$Down,
    [switch]$Volumes,
    [switch]$Yes,
    [switch]$NoColor,
    [switch]$Help,

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Service
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSCommandPath
Set-Location -LiteralPath $repoRoot
$script:UseColor = -not ($NoColor -or $env:NO_COLOR)

function Write-Line {
    param([string]$Text, [string]$Color)
    if ($script:UseColor -and $Color) { Write-Host $Text -ForegroundColor $Color }
    else { Write-Host $Text }
}
function Write-Step { param([string]$Message) Write-Line "-> $Message" "Cyan" }
function Write-Ok   { param([string]$Message) Write-Line "OK: $Message" "Green" }
function Write-Die  { param([string]$Message) Write-Line "ERROR: $Message" "Red"; exit 1 }

function Get-EnvValue {
    param([string]$Key)
    $path = Join-Path $repoRoot ".env"
    if (-not (Test-Path -LiteralPath $path)) { return $null }
    $line = Get-Content -LiteralPath $path | Where-Object { $_ -match "^$Key=" } | Select-Object -Last 1
    if (-not $line) { return $null }
    return ($line.Substring($Key.Length + 1).Trim().Trim('"').Trim("'"))
}

function New-HexSecret {
    param([int]$Bytes = 32)
    $buffer = New-Object byte[] $Bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buffer)
    -join ($buffer | ForEach-Object { $_.ToString("x2") })
}

# Credentials an older .env lacks, generated once and never replaced: Nebular's own secret, the
# API's access key (id + secret), the /metrics token, and the Redis password.
function Add-NebularSecrets {
    $path = Join-Path $repoRoot ".env"
    if (-not (Test-Path -LiteralPath $path)) { return }
    $lines = [System.Collections.Generic.List[string]]@(Get-Content -LiteralPath $path)
    $added = @()
    foreach ($key in @("NOS_JWT_SECRET", "NEBULAR_ACCESS_KEY_ID", "NEBULAR_SECRET_ACCESS_KEY", "NOS_METRICS_TOKEN", "REDIS_PASSWORD")) {
        $current = Get-EnvValue $key
        if ($current -and $current -ne "GENERATE_ME") { continue }
        $value = if ($key -eq "NEBULAR_ACCESS_KEY_ID") { "SHRD" + (New-HexSecret -Bytes 8).ToUpperInvariant() } else { New-HexSecret }
        $index = -1
        for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match "^$key=") { $index = $i } }
        if ($index -ge 0) { $lines[$index] = "$key=$value" } else { $lines.Add("$key=$value") }
        $added += $key
    }
    if ($added.Count -gt 0) {
        $lines | Set-Content -LiteralPath $path -Encoding ascii
        Write-Line ("Added generated credentials to .env: " + ($added -join " ")) "Green"
    }
}

# The secret coturn and the API share (docs/calls.md), for an .env from before calls had one.
# Generated once and kept: a new one only ends the TURN logins handed out so far.
function Add-TurnSecret {
    $path = Join-Path $repoRoot ".env"
    if (-not (Test-Path -LiteralPath $path)) { return }
    $current = Get-EnvValue "TURN_SECRET"
    if ($current -and $current -ne "GENERATE_ME") { return }
    $lines = [System.Collections.Generic.List[string]]@(Get-Content -LiteralPath $path)
    $value = New-HexSecret
    $index = -1
    for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match "^TURN_SECRET=") { $index = $i } }
    if ($index -ge 0) { $lines[$index] = "TURN_SECRET=$value" } else { $lines.Add("TURN_SECRET=$value") }
    $lines | Set-Content -LiteralPath $path -Encoding ascii
    Write-Line "Added the TURN relay secret to .env: TURN_SECRET" "Green"
}

function Get-ProxyMode {
    $mode = $env:PROXY_MODE
    if (-not $mode) { $mode = Get-EnvValue "PROXY_MODE" }
    if (-not $mode) { $mode = "local" }
    $mode = $mode.ToLowerInvariant()
    if ($mode -in @("npm", "external", "proxy")) { return "npm" }
    return "local"
}

function Get-ComposeArgs {
    $args = @("-f", (Join-Path $repoRoot "docker-compose.yml"))
    if ((Get-ProxyMode) -eq "npm") {
        $args += @("-f", (Join-Path $repoRoot "docker-compose.npm.yml"))
    } else {
        $args += @("-f", (Join-Path $repoRoot "docker-compose.local.yml"))
    }
    return $args
}

# The web bundle's build id: a hash of what goes into the web image, so a redeploy that
# changes the web client offers open tabs a reload and one that doesn't stays quiet. Compose
# bakes it into the bundle (VITE_WEB_BUILD); once the stack is up, Publish-WebBuild writes it to
# .shroud-run\web-build, which the API reads on each question (GET /client-version) without
# being restarted. Outside a git checkout, or when git fails, every deploy gets a new one.
$script:webBuild = $null
function Get-WebBuildId {
    if (Get-Command git -ErrorAction SilentlyContinue) {
        # Native stderr must not stop the deploy under Windows PowerShell's "Stop".
        $previous = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            & git -C $repoRoot rev-parse --git-dir *> $null
            if ($LASTEXITCODE -eq 0) {
                # The image's files: web/.dockerignore leaves out the top-level Markdown and .env
                # files; .gitignore the rest. quotePath=false keeps non-ASCII names as on disk.
                $files = @(& git -C $repoRoot -c core.quotePath=false ls-files -co --exclude-standard -- web 2>$null |
                    Where-Object {
                        -not ($_ -clike "web/*.md" -and $_ -notlike "web/*/*") -and
                        (Test-Path -LiteralPath (Join-Path $repoRoot $_) -PathType Leaf)
                    })
                if ($files.Count -gt 0) {
                    $hashes = @($files | & git -C $repoRoot hash-object --stdin-paths 2>$null)
                    if ($LASTEXITCODE -eq 0) {
                        $id = [string]((($files + $hashes) -join "`n") | & git -C $repoRoot hash-object --stdin 2>$null)
                        if ($LASTEXITCODE -eq 0 -and $id.Length -ge 12) { return $id.Substring(0, 12) }
                    }
                }
            }
        } catch {
        } finally {
            $ErrorActionPreference = $previous
        }
    }
    return (New-HexSecret -Bytes 6)
}

# Tells the running API which bundle the web container now serves. Written only after `up`
# succeeded; the directory is bind-mounted read-only into the API (docker-compose.yml).
function Publish-WebBuild {
    $dir = Join-Path $repoRoot ".shroud-run"
    try {
        $tmp = Join-Path $dir ("web-build." + (New-HexSecret -Bytes 4))
        Set-Content -LiteralPath $tmp -Value $script:webBuild -Encoding ascii
        Move-Item -LiteralPath $tmp -Destination (Join-Path $dir "web-build") -Force
    } catch {
        Write-Line "WARNING: cannot write $dir - open tabs won't be offered a reload" "Yellow"
    }
}

function Invoke-Compose {
    param([string[]]$ComposeArgs)
    # `build` and `up` bake the build id into the web image; it is set for this call only, so
    # a later hand-run `docker compose` in this window doesn't reuse it.
    $stampsWeb = $ComposeArgs[0] -in @("up", "build")
    $previousBuild = $env:SHROUD_WEB_BUILD
    if ($stampsWeb) {
        if (-not $script:webBuild) { $script:webBuild = Get-WebBuildId }
        $env:SHROUD_WEB_BUILD = $script:webBuild
        # Created here, not by Docker: a missing bind-mount source would be made by the daemon.
        New-Item -ItemType Directory -Force -Path (Join-Path $repoRoot ".shroud-run") | Out-Null
    }
    try {
        $files = Get-ComposeArgs
        & docker compose @files @ComposeArgs
        if ($LASTEXITCODE -ne 0) { throw "docker compose $($ComposeArgs -join ' ') exited $LASTEXITCODE" }
    } finally {
        if ($stampsWeb) { $env:SHROUD_WEB_BUILD = $previousBuild }
    }
    if ($ComposeArgs[0] -eq "up") { Publish-WebBuild }
}

function Show-Info {
    $mode = Get-ProxyMode
    $web = Get-EnvValue "WEB_PUBLIC_URL"; if (-not $web) { $web = "http://localhost:8081" }
    $api = Get-EnvValue "API_PUBLIC_URL"; if (-not $api) { $api = "http://localhost:8080" }
    Write-Host ""
    Write-Host "  Proxy mode:  $mode"
    Write-Host "  Web client:  $web"
    Write-Host "  API (iOS):   $api/api/v1"
    if ($mode -eq "npm") {
        Write-Host ""
        Write-Host "  Nginx Proxy Manager hosts:"
        Write-Host "    web  ->  http://shroud-web:80"
        Write-Host "    api  ->  http://shroud-api:8080"
    }
    Write-Host ""
    Invoke-Compose @("ps")
}

function Show-Help {
    Write-Host ""
    Write-Line "  Shroud — API + web client (Docker)" "White"
    Write-Host ""
    Write-Host "    .\deploy.ps1                     First-time setup, or start / redeploy"
    Write-Host "    .\deploy.ps1 -Init               Force the setup wizard again"
    Write-Host "    .\deploy.ps1 -Status             Show URLs and container status"
    Write-Host "    .\deploy.ps1 -Ps                 Container table only"
    Write-Host "    .\deploy.ps1 -Logs [svc...]      Follow logs"
    Write-Host "    .\deploy.ps1 -Restart [svc...]   Restart services"
    Write-Host "    .\deploy.ps1 -Rebuild            Rebuild images, then start"
    Write-Host "    .\deploy.ps1 -Down               Stop and remove all services"
    Write-Host "    .\deploy.ps1 -Down -Volumes      Also wipe Postgres / Redis / media volumes"
    Write-Host "    .\deploy.ps1 -Help               This help"
    Write-Host ""
}

if ($Help) { Show-Help; exit 0 }

$verbs = @()
if ($Init)    { $verbs += "init" }
if ($Status)  { $verbs += "status" }
if ($Ps)      { $verbs += "ps" }
if ($Logs)    { $verbs += "logs" }
if ($Restart) { $verbs += "restart" }
if ($Rebuild) { $verbs += "rebuild" }
if ($Down)    { $verbs += "down" }
if ($verbs.Count -gt 1) { Write-Die "only one command at a time (got: $($verbs -join ', '))" }
$cmd = if ($verbs.Count -eq 1) { $verbs[0] } else { "up" }
if ($Volumes -and $cmd -ne "down") {
    Write-Die "-Volumes is only valid with -Down. Example: .\deploy.ps1 -Down -Volumes"
}

$services = @()
if ($Service) { $services = @($Service | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) }
if ($Yes) { $env:SHROUD_SETUP_ASSUME_YES = "1" }

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Die "Docker is not installed or not on PATH."
}
docker compose version 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Die "Docker Compose v2 is required." }

function Invoke-Wizard {
    & (Join-Path $repoRoot "scripts\setup.ps1")
    if ($LASTEXITCODE -ne 0) { throw "setup.ps1 exited $LASTEXITCODE" }
    if ((Get-ProxyMode) -eq "npm") {
        docker network inspect proxy-network 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Step "Creating Docker network proxy-network"
            docker network create proxy-network | Out-Null
        }
    }
    Invoke-Compose @("up", "-d", "--build", "--remove-orphans")
    Show-Info
}

try {
    switch ($cmd) {
        "status"  { Show-Info; exit 0 }
        "ps"      { Invoke-Compose @("ps"); exit 0 }
        "logs"    {
            Write-Step "Following logs (Ctrl-C to stop)..."
            $files = Get-ComposeArgs
            & docker compose @files logs -f --tail 200 @services
            exit 0
        }
        "restart" {
            $what = if ($services.Count -gt 0) { $services -join ' ' } else { "all services" }
            Write-Step "Restarting $what..."
            Invoke-Compose (@("restart") + $services)
            Show-Info
            exit 0
        }
        "down"    {
            if ($Volumes) {
                Write-Step "Removing containers and named volumes (Postgres data will be wiped)..."
                Invoke-Compose @("down", "--volumes", "--remove-orphans")
            } else {
                Invoke-Compose @("down")
            }
            exit 0
        }
        "init"    { Invoke-Wizard; exit 0 }
    }

    Write-Host ""
    Write-Line "====================================================" "Cyan"
    Write-Line "  Shroud — API + web client" "Cyan"
    Write-Line "====================================================" "Cyan"
    Write-Host ""

    if (-not (Test-Path -LiteralPath (Join-Path $repoRoot ".env"))) {
        Invoke-Wizard
        exit 0
    }

    Add-NebularSecrets
    Add-TurnSecret
    $envFile = Join-Path $repoRoot ".env"
    if (Select-String -LiteralPath $envFile -Pattern '=(GENERATE_ME)\s*$' -Quiet) {
        Write-Die ".env still contains GENERATE_ME placeholders. Run .\deploy.ps1 -Init."
    }

    $startedAt = Get-Date
    if ((Get-ProxyMode) -eq "npm") {
        docker network inspect proxy-network 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) { docker network create proxy-network | Out-Null }
    }
    if ($cmd -eq "rebuild") {
        Write-Step "Rebuilding images (--pull)..."
        Invoke-Compose @("build", "--pull")
    }
    try {
        Invoke-Compose @("up", "-d", "--build", "--remove-orphans")
    } catch {
        $apiLogs = & docker logs shroud-api 2>&1 | Select-Object -Last 80
        if ($apiLogs -match "password authentication failed") {
            Write-Host ""
            Write-Line "ERROR: Postgres rejected the API password." "Red"
            Write-Host ""
            Write-Host "  The Postgres image applies POSTGRES_PASSWORD only the first time the"
            Write-Host "  data volume is created. A new password in .env is ignored after that."
            Write-Host ""
            Write-Host "  Keep data:  set POSTGRES_PASSWORD in .env to the original (old default: shroud)"
            Write-Host "  Wipe data:  .\deploy.ps1 -Down -Volumes ; .\deploy.ps1"
            Write-Host ""
        }
        throw
    }
    $elapsed = (Get-Date) - $startedAt
    Write-Host ""
    Write-Ok ("Deploy finished in {0}m {1}s." -f [int][math]::Floor($elapsed.TotalMinutes), $elapsed.Seconds)
    Show-Info
}
catch {
    Write-Host ""
    Write-Line "Failed: $($_.Exception.Message)" "Red"
    Write-Line "  Inspect:  .\deploy.ps1 -Ps" "DarkGray"
    Write-Line "  Logs:     .\deploy.ps1 -Logs" "DarkGray"
    exit 1
}
