#Requires -Version 5.1
# First-time setup wizard, run by deploy.ps1 (-Init, or when .env is missing), whose storage
# helpers it uses. -DataDir / -SwitchCommand / -Migrate carry deploy.ps1's storage flags; with
# them the storage question isn't asked.
param(
    [string]$DataDir,
    [string]$SwitchCommand,
    [switch]$Migrate
)
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
Set-Location -LiteralPath $repoRoot

if (-not (Get-Command Initialize-StorageSwitch -ErrorAction SilentlyContinue)) {
    Write-Host "Run the wizard through deploy.ps1: .\deploy.ps1 -Init"
    exit 1
}

function New-Secret {
    param([int]$Bytes = 32)
    $buffer = New-Object byte[] $Bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buffer)
    -join ($buffer | ForEach-Object { $_.ToString("x2") })
}

function Read-Prompt {
    param([string]$Label, [string]$Default)
    if ($env:SHROUD_SETUP_ASSUME_YES -eq "1") { return $Default }
    $hint = if ($Default) { " [$Default]" } else { "" }
    $value = Read-Host "  $Label$hint"
    if ([string]::IsNullOrWhiteSpace($value)) { return $Default }
    return $value
}

if ((Test-Path -LiteralPath ".env") -and $env:SHROUD_SETUP_ASSUME_YES -ne "1") {
    Write-Host ""
    Write-Host "Existing .env detected." -ForegroundColor Yellow
    $overwrite = Read-Host "  Overwrite and reconfigure? [y/N]"
    if ($overwrite -notmatch '^[yY]') {
        Write-Host "Cancelled."
        exit 0
    }
}

Write-Host ""
Write-Host "==============================================" -ForegroundColor Cyan
Write-Host "  Shroud — first-time setup" -ForegroundColor Cyan
Write-Host "==============================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  How will you reach the stack?"
Write-Host "  1) Local ports     — web :8081, API :8080"
Write-Host "  2) Nginx Proxy Manager — join proxy-network"
$modeChoice = Read-Prompt "Choice" "1"

if ($modeChoice -eq "2") {
    $PROXY_MODE = "npm"
    $WEB_PUBLIC_URL = Read-Prompt "Public web URL" "https://web.example.com"
    $API_PUBLIC_URL = Read-Prompt "Public API URL (iOS)" "https://api.example.com"
    $WEB_PORT = "8081"
    $API_PORT = "8080"
} else {
    $PROXY_MODE = "local"
    $WEB_PORT = Read-Prompt "Web host port" "8081"
    $API_PORT = Read-Prompt "API host port" "8080"
    $WEB_PUBLIC_URL = "http://localhost:$WEB_PORT"
    $API_PUBLIC_URL = "http://localhost:$API_PORT"
}

Write-Host ""
Write-Host "  Postgres, Nebular and media data live in named Docker volumes, or in a folder you can"
Write-Host "  see and back up (database\, nebular\ and media\ inside it)."
$existingDataDir = Get-EnvValue "SHROUD_DATA_DIR"
if ($PSBoundParameters.ContainsKey("DataDir")) {
    # Chosen with deploy.ps1 -DataDir or -NamedVolumes.
    $SHROUD_DATA_DIR = $DataDir
} else {
    # A first setup defaults to named volumes; a re-run keeps what .env has.
    $hint = if ($existingDataDir) { "[Y/n]" } else { "[y/N]" }
    $answer = Read-Prompt "Store data in a folder instead of Docker volumes? $hint" ""
    if (-not $answer) { $answer = if ($existingDataDir) { "y" } else { "n" } }
    $SHROUD_DATA_DIR = ""
    if ($answer -match '^[yY]') {
        while ($true) {
            $SHROUD_DATA_DIR = Read-Prompt "Data folder (relative to this repository, or absolute)" $(if ($existingDataDir) { $existingDataDir } else { "./data" })
            # .env holds it unquoted, and Compose would interpolate a $ in it.
            if ($SHROUD_DATA_DIR -notmatch '[$"''#]') { break }
            Write-Host "  It can't contain `$ `" ' or #." -ForegroundColor Red
            if ($env:SHROUD_SETUP_ASSUME_YES -eq "1") { exit 1 }
        }
    }
}
if (-not $SwitchCommand) {
    $SwitchCommand = if ($SHROUD_DATA_DIR) { ".\deploy.ps1 -DataDir `"$SHROUD_DATA_DIR`"" } else { ".\deploy.ps1 -NamedVolumes" }
}
# Leaving data behind on the other side stops here, before .env changes.
Initialize-StorageSwitch -NewValue $SHROUD_DATA_DIR -SwitchCommand $SwitchCommand -Migrate:$Migrate
$dataDirResolved = Resolve-DataDir $SHROUD_DATA_DIR
$storageLabel = Get-StorageLabel $dataDirResolved
Write-Host "  Storage: $storageLabel" -ForegroundColor Green

$existingPg = $null
if (Test-Path -LiteralPath ".env") {
    $line = Get-Content -LiteralPath ".env" | Where-Object { $_ -match "^POSTGRES_PASSWORD=" } | Select-Object -Last 1
    if ($line) { $existingPg = $line.Substring("POSTGRES_PASSWORD=".Length).Trim() }
}
if ($existingPg -and $existingPg -ne "GENERATE_ME") {
    $POSTGRES_PASSWORD = $existingPg
    Write-Host "  Postgres password: reused from .env (data already initialized)" -ForegroundColor Green
} else {
    $POSTGRES_PASSWORD = New-Secret
    Write-Host "  Postgres password: generated" -ForegroundColor Green
    if (Test-PgDataExists $dataDirResolved) {
        Write-Host "  Postgres already has data in $storageLabel. The new password will not apply to it." -ForegroundColor Yellow
        Write-Host "  Wipe it first: .\deploy.ps1 -Down -Volumes"
    }
}
$NOS_JWT_SECRET = New-Secret
$NEBULAR_ACCESS_KEY_ID = "SHRD" + (New-Secret -Bytes 8).ToUpperInvariant()
$NEBULAR_SECRET_ACCESS_KEY = New-Secret
$NOS_METRICS_TOKEN = New-Secret
$REDIS_PASSWORD = New-Secret
Write-Host "  Nebular OS secret and the API's access key: generated" -ForegroundColor Green
# Reused like the Postgres password: a new one only ends the TURN logins already handed out.
$existingTurn = $null
if (Test-Path -LiteralPath ".env") {
    $line = Get-Content -LiteralPath ".env" | Where-Object { $_ -match "^TURN_SECRET=" } | Select-Object -Last 1
    if ($line) { $existingTurn = $line.Substring("TURN_SECRET=".Length).Trim() }
}
$TURN_SECRET = if ($existingTurn -and $existingTurn -ne "GENERATE_ME") { $existingTurn } else { New-Secret }
# Reused like TURN_SECRET. 16 bytes: the server rejects a 32-byte secret. A new salt makes
# every username stop matching. Clients download this value, so it is not a secret.
$existingSalt = $null
if (Test-Path -LiteralPath ".env") {
    $line = Get-Content -LiteralPath ".env" | Where-Object { $_ -match "^USERNAME_KDF_SALT=" } | Select-Object -Last 1
    if ($line) { $existingSalt = $line.Substring("USERNAME_KDF_SALT=".Length).Trim() }
}
$USERNAME_KDF_SALT = if ($existingSalt -and $existingSalt -ne "GENERATE_ME") { $existingSalt } else { New-Secret -Bytes 16 }

Write-Host ""
Write-Host "  Calls between networks that block direct connections (many mobile carriers) need the"
Write-Host "  TURN relay (coturn) on this server. It needs UDP/TCP 3478 and UDP 49160-49259 open,"
Write-Host "  reachable at a public hostname or IP. Leave it blank to go without."
$turnDefault = ""
if ($PROXY_MODE -eq "npm") { $turnDefault = ([Uri]$API_PUBLIC_URL).Host }
$TURN_HOST = Read-Prompt "Relay hostname or IP" $turnDefault
if ($TURN_HOST) {
    $TURN_URLS = "turn:${TURN_HOST}:3478?transport=udp,turn:${TURN_HOST}:3478?transport=tcp"
    $COMPOSE_PROFILES = "calls"
    Write-Host "  TURN relay: on at $TURN_HOST" -ForegroundColor Green
} else {
    $TURN_URLS = ""
    $COMPOSE_PROFILES = ""
    Write-Host "  TURN relay: off"
}

Write-Host ""
Write-Host "── Admin console ──"
Write-Host "  The operator console is a page of this site: $($WEB_PUBLIC_URL.TrimEnd('/'))/admin."
Write-Host "  To turn it on or off later, use .\deploy.ps1 -Admin. That does not run this wizard."
$adminChoice = "n"
if ($env:SHROUD_SETUP_ASSUME_YES -eq "1") {
    Write-Host "  Enable the admin console? [y/N]: n"
} else {
    $adminChoice = Read-Host "  Enable the admin console? [y/N]"
    if ([string]::IsNullOrWhiteSpace($adminChoice)) { $adminChoice = "n" }
}
function Get-ReusedOrNewSecret([string]$Name) {
    $current = $null
    if (Test-Path -LiteralPath ".env") {
        $line = Get-Content -LiteralPath ".env" | Where-Object { $_ -match "^$Name=" } | Select-Object -Last 1
        if ($line) { $current = $line.Substring("$Name=".Length).Trim() }
    }
    if ($current -and $current -ne "GENERATE_ME") { return $current }
    return New-Secret
}
function Get-KeptAdminValue([string]$Name) {
    $current = Get-EnvValue $Name
    if ($current -and $current -ne "GENERATE_ME") { return $current }
    return ""
}
$ADMIN_PORT = Get-KeptAdminValue "ADMIN_PORT"
if (-not $ADMIN_PORT) { $ADMIN_PORT = "8082" }
$ADMIN_PUBLIC_URL = Get-KeptAdminValue "ADMIN_PUBLIC_URL"
$ADMIN_DB_PASSWORD = Get-KeptAdminValue "ADMIN_DB_PASSWORD"
$ADMIN_DATABASE_URL = Get-KeptAdminValue "ADMIN_DATABASE_URL"
$ADMIN_SECRET_KEY = Get-KeptAdminValue "ADMIN_SECRET_KEY"
$OPERATOR_PORT = Get-KeptAdminValue "OPERATOR_PORT"
if (-not $OPERATOR_PORT) { $OPERATOR_PORT = "8090" }
$OPERATOR_TOKEN = Get-KeptAdminValue "OPERATOR_TOKEN"
if ($adminChoice -match '^[yY]') {
    if ($COMPOSE_PROFILES) { $COMPOSE_PROFILES = "$COMPOSE_PROFILES,admin" } else { $COMPOSE_PROFILES = "admin" }
    $web = $WEB_PUBLIC_URL
    if ($web) { $web = $web.TrimEnd('/') }
    if (-not $web -or $web -notmatch '^https?://' -or $web -match '[\s$]') {
        Write-Die "WEB_PUBLIC_URL is not set. The console is served at that address plus /admin."
    }
    $ADMIN_PUBLIC_URL = "$web/admin"
    $ADMIN_DB_PASSWORD = Get-ReusedOrNewSecret "ADMIN_DB_PASSWORD"
    $ADMIN_DATABASE_URL = "postgres://shroud_admin:${ADMIN_DB_PASSWORD}@postgres:5432/shroud"
    $ADMIN_SECRET_KEY = Get-ReusedOrNewSecret "ADMIN_SECRET_KEY"
    $OPERATOR_TOKEN = Get-ReusedOrNewSecret "OPERATOR_TOKEN"
    Write-Host "  Admin console: on at $ADMIN_PUBLIC_URL" -ForegroundColor Green
} else {
    if ($ADMIN_SECRET_KEY) {
        Write-Host "  Admin console: off (its settings and secrets stay in .env for when you turn it back on)"
    } else {
        Write-Host "  Admin console: off"
    }
}

$envLines = @(
    "PROXY_MODE=$PROXY_MODE"
    "WEB_PUBLIC_URL=$WEB_PUBLIC_URL"
    "API_PUBLIC_URL=$API_PUBLIC_URL"
    "WEB_PORT=$WEB_PORT"
    "API_PORT=$API_PORT"
    "CORS_ALLOWED_ORIGINS=$WEB_PUBLIC_URL"
    "POSTGRES_USER=shroud"
    "POSTGRES_PASSWORD=$POSTGRES_PASSWORD"
    "POSTGRES_DB=shroud"
    "NOS_JWT_SECRET=$NOS_JWT_SECRET"
    "NEBULAR_ACCESS_KEY_ID=$NEBULAR_ACCESS_KEY_ID"
    "NEBULAR_SECRET_ACCESS_KEY=$NEBULAR_SECRET_ACCESS_KEY"
    "NOS_METRICS_TOKEN=$NOS_METRICS_TOKEN"
    "REDIS_PASSWORD=$REDIS_PASSWORD"
    "USERNAME_KDF_SALT=$USERNAME_KDF_SALT"
    "COMPOSE_PROFILES=$COMPOSE_PROFILES"
    "TURN_URLS=$TURN_URLS"
    "TURN_SECRET=$TURN_SECRET"
    "ADMIN_PORT=$ADMIN_PORT"
    "ADMIN_PUBLIC_URL=$ADMIN_PUBLIC_URL"
    "ADMIN_DB_PASSWORD=$ADMIN_DB_PASSWORD"
    "ADMIN_DATABASE_URL=$ADMIN_DATABASE_URL"
    "ADMIN_SECRET_KEY=$ADMIN_SECRET_KEY"
    "OPERATOR_PORT=$OPERATOR_PORT"
    "OPERATOR_TOKEN=$OPERATOR_TOKEN"
    "RUST_LOG=info"
    "RUST_LOG_FORMAT=text"
)
if ($SHROUD_DATA_DIR) { $envLines += "SHROUD_DATA_DIR=$SHROUD_DATA_DIR" }
# UTF-8 without a byte-order mark (deploy.ps1's Write-EnvLines).
Write-EnvLines $envLines

Write-Host ""
Write-Host "Wrote .env (mode $PROXY_MODE)." -ForegroundColor Green
Write-Host "  Web:  $WEB_PUBLIC_URL"
Write-Host "  API:  $API_PUBLIC_URL/api/v1"
if ($ADMIN_PUBLIC_URL) { Write-Host "  Admin: $ADMIN_PUBLIC_URL" }
Write-Host "  Data: $storageLabel"
Write-Host ""
