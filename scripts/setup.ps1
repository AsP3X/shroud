#Requires -Version 5.1
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
Set-Location -LiteralPath $repoRoot

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

$existingPg = $null
if (Test-Path -LiteralPath ".env") {
    $line = Get-Content -LiteralPath ".env" | Where-Object { $_ -match "^POSTGRES_PASSWORD=" } | Select-Object -Last 1
    if ($line) { $existingPg = $line.Substring("POSTGRES_PASSWORD=".Length).Trim() }
}
if ($existingPg -and $existingPg -ne "GENERATE_ME") {
    $POSTGRES_PASSWORD = $existingPg
    Write-Host "  Postgres password: reused from .env (volume already initialized)" -ForegroundColor Green
} else {
    $POSTGRES_PASSWORD = New-Secret
    Write-Host "  Postgres password: generated" -ForegroundColor Green
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

@(
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
    "COMPOSE_PROFILES=$COMPOSE_PROFILES"
    "TURN_URLS=$TURN_URLS"
    "TURN_SECRET=$TURN_SECRET"
    "RUST_LOG=info"
    "RUST_LOG_FORMAT=text"
) | Set-Content -LiteralPath ".env" -Encoding ascii

Write-Host ""
Write-Host "Wrote .env (mode $PROXY_MODE)." -ForegroundColor Green
Write-Host "  Web:  $WEB_PUBLIC_URL"
Write-Host "  API:  $API_PUBLIC_URL/api/v1"
Write-Host ""
