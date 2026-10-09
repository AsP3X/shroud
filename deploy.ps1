#Requires -Version 5.1
<#
.SYNOPSIS
    One-command deploy for the Shroud API + web client.

.DESCRIPTION
    Windows counterpart of ./deploy.sh. Docker Desktop (Linux containers) required.

.EXAMPLE
    .\deploy.ps1
    .\deploy.ps1 -Init
    .\deploy.ps1 -Admin
    .\deploy.ps1 -Admin bootstrap
    .\deploy.ps1 -Logs api
    .\deploy.ps1 -DataDir D:\shroud-data
#>
param(
    [switch]$Init,
    [switch]$Admin,
    [switch]$Status,
    [switch]$Ps,
    [switch]$Logs,
    [switch]$Restart,
    [switch]$Rebuild,
    [switch]$Down,
    [switch]$Volumes,
    # -DataDir [path]: the optional path arrives with the remaining arguments ($Service).
    [switch]$DataDir,
    [switch]$NamedVolumes,
    [switch]$Migrate,
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

# .env as UTF-8 without a byte-order mark, which Compose would read as part of the first key;
# Windows PowerShell's Get-Content and Set-Content would otherwise use the ANSI code page and
# mangle a data folder with non-ASCII letters.
function Read-EnvLines {
    $path = Join-Path $repoRoot ".env"
    # The comma keeps PowerShell from unrolling the list into an array (or one string).
    return ,([System.Collections.Generic.List[string]]@(Get-Content -LiteralPath $path -Encoding UTF8))
}
function Write-EnvLines {
    param($Lines)
    [System.IO.File]::WriteAllLines((Join-Path $repoRoot ".env"), [string[]]@($Lines))
}

function Get-EnvValue {
    param([string]$Key)
    $path = Join-Path $repoRoot ".env"
    if (-not (Test-Path -LiteralPath $path)) { return $null }
    $line = (Read-EnvLines) | Where-Object { $_ -match "^$Key=" } | Select-Object -Last 1
    if (-not $line) { return $null }
    return ($line.Substring($Key.Length + 1).Trim().Trim('"').Trim("'"))
}

# Replaces KEY's line in .env, or appends one.
function Set-EnvValue {
    param([string]$Key, [string]$Value)
    $lines = Read-EnvLines
    $index = -1
    for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match "^$Key=") { $index = $i } }
    if ($index -ge 0) { $lines[$index] = "$Key=$Value" } else { $lines.Add("$Key=$Value") }
    Write-EnvLines $lines
}

function Remove-EnvValue {
    param([string]$Key)
    if (-not (Test-Path -LiteralPath (Join-Path $repoRoot ".env"))) { return }
    $lines = Read-EnvLines
    if (-not ($lines | Where-Object { $_ -match "^$Key=" })) { return }
    Write-EnvLines ($lines | Where-Object { $_ -notmatch "^$Key=" })
}

# Runs a native command and returns its standard output, for callers that read $LASTEXITCODE.
# Its stderr must not stop the script under Windows PowerShell's "Stop".
function Invoke-NativeOutput {
    param([scriptblock]$Block)
    $eap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { & $Block 2>$null } finally { $ErrorActionPreference = $eap }
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
    $lines = Read-EnvLines
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
        Write-EnvLines $lines
        Write-Line ("Added generated credentials to .env: " + ($added -join " ")) "Green"
    }
}

# The secret coturn and the API share (docs/calls.md), for an .env from before calls had one.
# Generated once and kept: a new one only ends the TURN logins handed out so far.
function Test-ProfileEnabled {
    param([string]$Name)
    $current = (Get-EnvValue "COMPOSE_PROFILES")
    if (-not $current) { $current = "" }
    $current = ($current -replace '\s', '').Trim(',')
    return ("," + $current + ",").Contains("," + $Name + ",")
}

function Enable-Profile {
    param([string]$Name)
    if (Test-ProfileEnabled $Name) { return }
    $current = Get-EnvValue "COMPOSE_PROFILES"
    if ($current) { $current = ($current -replace '\s', '').Trim(',') }
    if (-not $current) { Set-EnvValue "COMPOSE_PROFILES" $Name }
    else { Set-EnvValue "COMPOSE_PROFILES" "$current,$Name" }
}

function Disable-Profile {
    param([string]$Name)
    $current = Get-EnvValue "COMPOSE_PROFILES"
    if (-not $current) { $current = "" }
    $next = @()
    foreach ($part in (($current -replace '\s', '').Trim(',') -split ',')) {
        if ($part -and $part -ne $Name) { $next += $part }
    }
    Set-EnvValue "COMPOSE_PROFILES" ($next -join ',')
}

# Secrets the console needs once its profile is on. Generated once and never replaced.
function Add-AdminSecrets {
    if (-not (Test-ProfileEnabled "admin")) { return }
    # Refuse a bad web address before writing a port or a secret.
    Set-AdminPublicUrl
    foreach ($key in @("ADMIN_DB_PASSWORD", "ADMIN_SECRET_KEY", "OPERATOR_TOKEN")) {
        $current = Get-EnvValue $key
        if ($current -and $current -ne "GENERATE_ME") { continue }
        Set-EnvValue $key (New-HexSecret)
        Write-Line "Added the admin console secret to .env: $key" "Green"
    }
    $url = Get-EnvValue "ADMIN_DATABASE_URL"
    if (-not $url -or $url -eq "GENERATE_ME") {
        $password = Get-EnvValue "ADMIN_DB_PASSWORD"
        Set-EnvValue "ADMIN_DATABASE_URL" "postgres://shroud_admin:${password}@postgres:5432/shroud"
    }
    if (-not (Get-EnvValue "ADMIN_PORT")) { Set-EnvValue "ADMIN_PORT" "8082" }
    if (-not (Get-EnvValue "OPERATOR_PORT")) { Set-EnvValue "OPERATOR_PORT" "8090" }
}

# The console's public address: WEB_PUBLIC_URL plus /admin. $null when that address is not http(s).
function Get-AdminPublicUrl {
    $web = Get-EnvValue "WEB_PUBLIC_URL"
    if ($web) { $web = $web.TrimEnd('/') }
    if (-not $web -or $web -notmatch '^https?://' -or $web -match '[\s$]') { return $null }
    return "$web/admin"
}

# Writes that address into .env. A refused address leaves the profile unchanged when called first.
function Set-AdminPublicUrl {
    $url = Get-AdminPublicUrl
    if (-not $url) {
        Write-Die "WEB_PUBLIC_URL is not set. The console is served at that address plus /admin."
    }
    Set-EnvValue "ADMIN_PUBLIC_URL" $url
}

# The init script only runs on an empty data directory. This creates shroud_admin on a volume
# that already existed, then re-applies the column grants.
function Apply-AdminDb {
    if (-not (Test-ProfileEnabled "admin")) { return }
    $user = Get-EnvValue "POSTGRES_USER"; if (-not $user) { $user = "shroud" }
    $db = Get-EnvValue "POSTGRES_DB"; if (-not $db) { $db = "shroud" }
    $pass = Get-EnvValue "ADMIN_DB_PASSWORD"
    if (-not $pass) { Write-Die "the admin profile is on and ADMIN_DB_PASSWORD is empty." }
    $files = Get-ComposeArgs
    $roleSql = @'
SELECT set_config('shroud.admin_password', :'pwd', false);
DO $body$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'shroud_admin') THEN
    EXECUTE format(
      'CREATE ROLE shroud_admin LOGIN PASSWORD %L',
      current_setting('shroud.admin_password')
    );
  END IF;
END
$body$;
GRANT CONNECT, CREATE ON DATABASE :"dbname" TO shroud_admin;
GRANT USAGE ON SCHEMA public TO shroud_admin;
'@
    Invoke-WithDataDirEnv {
        $roleSql | & docker compose @files exec -T postgres psql -q -v ON_ERROR_STOP=1 -U $user -d $db -v "pwd=$pass" -v "dbname=$db" | Out-Null
    }
    if ($LASTEXITCODE -ne 0) { throw "could not create the shroud_admin role" }
    $grants = Join-Path $repoRoot "admin\api\grants.sql"
    Invoke-WithDataDirEnv {
        Get-Content -Raw -LiteralPath $grants | & docker compose @files exec -T postgres psql -q -v ON_ERROR_STOP=1 -U $user -d $db -f - | Out-Null
    }
    if ($LASTEXITCODE -ne 0) { throw "could not apply admin/api/grants.sql" }
}

function Add-TurnSecret {
    $path = Join-Path $repoRoot ".env"
    if (-not (Test-Path -LiteralPath $path)) { return }
    $current = Get-EnvValue "TURN_SECRET"
    if ($current -and $current -ne "GENERATE_ME") { return }
    $lines = Read-EnvLines
    $value = New-HexSecret
    $index = -1
    for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match "^TURN_SECRET=") { $index = $i } }
    if ($index -ge 0) { $lines[$index] = "TURN_SECRET=$value" } else { $lines.Add("TURN_SECRET=$value") }
    Write-EnvLines $lines
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

# A SHROUD_DATA_DIR value as an absolute path: relative to the repository root (not the current
# directory), ~ for the home folder, no trailing separator. Empty stays empty (named volumes).
function Resolve-DataDir {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return "" }
    if ($Value -eq "~" -or $Value.StartsWith("~/") -or $Value.StartsWith("~\")) {
        $Value = $HOME + $Value.Substring(1)
    }
    if (-not [System.IO.Path]::IsPathRooted($Value)) { $Value = Join-Path $repoRoot $Value }
    $full = [System.IO.Path]::GetFullPath($Value)
    if ($full -ne [System.IO.Path]::GetPathRoot($full)) { $full = $full.TrimEnd('\', '/') }
    return $full
}

# Where the stack keeps its data, from .env only: a one-off environment variable would switch
# storage for one command and leave the next on the other side. "" means named volumes.
function Get-DataDir {
    return (Resolve-DataDir (Get-EnvValue "SHROUD_DATA_DIR"))
}

function Get-ComposeArgs {
    $args = @("-f", (Join-Path $repoRoot "docker-compose.yml"))
    if ((Get-ProxyMode) -eq "npm") {
        $args += @("-f", (Join-Path $repoRoot "docker-compose.npm.yml"))
    } else {
        $args += @("-f", (Join-Path $repoRoot "docker-compose.local.yml"))
    }
    if (Get-DataDir) {
        $args += @("-f", (Join-Path $repoRoot "docker-compose.data-dir.yml"))
    }
    return $args
}

# Runs $Block with SHROUD_DATA_DIR set to the absolute folder the overlay reads, or unset for
# named volumes, as .env says. The window's own value comes back afterwards.
function Invoke-WithDataDirEnv {
    param([scriptblock]$Block)
    $previousDataDir = $env:SHROUD_DATA_DIR
    $env:SHROUD_DATA_DIR = Get-DataDir
    try { & $Block } finally { $env:SHROUD_DATA_DIR = $previousDataDir }
}

# Storage is either named volumes, written "" below, or a folder: SHROUD_DATA_DIR resolved by
# Resolve-DataDir. Each holds three kinds of data.
$script:DataKinds = @(
    @{ Volume = "shroud_pg_data"; Folder = "database" },
    @{ Volume = "shroud_nebular_data"; Folder = "nebular" },
    @{ Volume = "shroud_media_data"; Folder = "media" }
)
# The image that copies and wipes data owned by the containers' users; the stack already pulls it.
$script:DataToolImage = "postgres:16-alpine"

# The Compose project name, which prefixes the named volumes (<project>_shroud_pg_data).
function Get-ProjectName {
    $composeFile = Join-Path $repoRoot "docker-compose.yml"
    foreach ($line in @(Invoke-NativeOutput { docker compose -f $composeFile config --no-interpolate })) {
        if ($line -match '^name: (.+)$') { return $Matches[1].Trim() }
    }
    return ((Split-Path -Leaf $repoRoot).ToLowerInvariant() -replace '[^a-z0-9_-]', '')
}

function Get-StorageLabel {
    param([string]$Storage)
    if ($Storage) { return "the folder $Storage" }
    return "named Docker volumes"
}

# What `docker run -v` mounts for one kind of data: a volume name or a folder.
function Get-StorageSource {
    param([string]$Storage, [string]$Project, $Kind)
    if ($Storage) { return (Join-Path $Storage $Kind.Folder) }
    return "$($Project)_$($Kind.Volume)"
}

# Whether storage has a kind of data. A volume counts once it exists; a folder we can't look
# into counts as full (Postgres keeps its own at mode 700 for uid 70).
function Test-StorageHas {
    param([string]$Storage, [string]$Project, $Kind)
    $source = Get-StorageSource $Storage $Project $Kind
    if ($Storage) {
        if (-not (Test-Path -LiteralPath $source -PathType Container)) { return $false }
        try {
            return [bool](Get-ChildItem -LiteralPath $source -Force -ErrorAction Stop | Select-Object -First 1)
        } catch {
            return $true
        }
    }
    Invoke-NativeOutput { docker volume inspect $source } | Out-Null
    return ($LASTEXITCODE -eq 0)
}

# Whether Postgres has already set up its data in $Storage (default: the current storage), so
# POSTGRES_PASSWORD no longer applies.
function Test-PgDataExists {
    param([string]$Storage = (Get-DataDir))
    return (Test-StorageHas $Storage (Get-ProjectName) $script:DataKinds[0])
}

function Get-PgDataLocation {
    $dir = Get-DataDir
    if ($dir) { return (Join-Path $dir "database") }
    return "the volume $(Get-ProjectName)_shroud_pg_data"
}

# Asks a yes/no question, default no. -Yes answers yes.
function Confirm-Choice {
    param([string]$Question)
    if ($env:SHROUD_SETUP_ASSUME_YES -eq "1") {
        Write-Host "$Question [y/N]: y (-Yes)"
        return $true
    }
    return ((Read-Host "$Question [y/N]") -match '^[yY]')
}

# The commands that copy every kind of data from one storage to another and switch with
# $SwitchCommand, for a stack that is down. Nothing is deleted.
function Write-CopyCommands {
    param([string]$From, [string]$To, [string]$Project, [string]$SwitchCommand)
    Write-Host "    .\deploy.ps1 -Down"
    if ($To) {
        $folders = ($script:DataKinds | ForEach-Object { "`"$(Join-Path $To $_.Folder)`"" }) -join ","
        Write-Host "    New-Item -ItemType Directory -Force $folders"
    }
    foreach ($kind in $script:DataKinds) {
        if (-not (Test-StorageHas $From $Project $kind)) { continue }
        $src = Get-StorageSource $From $Project $kind
        $dst = Get-StorageSource $To $Project $kind
        if (-not $To) {
            Write-Host "    docker volume create --label com.docker.compose.project=$Project --label com.docker.compose.volume=$($kind.Volume) $dst"
        }
        Write-Host "    docker run --rm -v `"${src}:/from:ro`" -v `"${dst}:/to`" $($script:DataToolImage) cp -a /from/. /to/"
    }
    Write-Host "    $SwitchCommand"
}

# How to remove storage once the data has moved on; nothing here removes it.
function Write-CleanupCommands {
    param([string]$Storage, [string]$Project)
    if ($Storage) {
        Write-Host "    docker run --rm -v `"${Storage}:/data`" $($script:DataToolImage) rm -rf /data/database /data/nebular /data/media"
        return
    }
    $existing = @($script:DataKinds | Where-Object { Test-StorageHas "" $Project $_ } |
        ForEach-Object { Get-StorageSource "" $Project $_ })
    if ($existing.Count -gt 0) { Write-Host "    docker volume rm $($existing -join ' ')" }
}

# Copies every kind of data $From has into $To, through a container that keeps each file's
# owner. The stack must be down; $From is left as it is.
function Copy-Storage {
    param([string]$From, [string]$To, [string]$Project)
    if ($To) {
        foreach ($kind in $script:DataKinds) {
            New-Item -ItemType Directory -Force -Path (Join-Path $To $kind.Folder) | Out-Null
        }
    }
    foreach ($kind in $script:DataKinds) {
        if (-not (Test-StorageHas $From $Project $kind)) { continue }
        $src = Get-StorageSource $From $Project $kind
        $dst = Get-StorageSource $To $Project $kind
        if (-not $To) {
            # Labelled as Compose labels its own, or every `up` warns that it didn't create it.
            & docker volume create --label "com.docker.compose.project=$Project" --label "com.docker.compose.volume=$($kind.Volume)" $dst | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "docker volume create $dst exited $LASTEXITCODE" }
        }
        Write-Step "Copying $src -> $dst"
        & docker run --rm -v "${src}:/from:ro" -v "${dst}:/to" $script:DataToolImage cp -a /from/. /to/ | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "copying $src exited $LASTEXITCODE" }
    }
}

# Gets a switch of storage ready before .env changes: $NewValue is the new SHROUD_DATA_DIR value
# (empty for named volumes), $SwitchCommand the command that makes the switch, for the
# instructions. When the data would stay behind on the old side, stops the script having
# changed nothing, unless -Migrate and the user confirms the copy. Stops a running stack. Never
# deletes or overwrites data.
function Initialize-StorageSwitch {
    param([string]$NewValue, [string]$SwitchCommand, [switch]$Migrate)
    $from = Get-DataDir
    $to = Resolve-DataDir $NewValue
    if ($from -eq $to) { return }
    if ($to -and $to -eq [System.IO.Path]::GetPathRoot($to)) { Write-Die "the data folder can't be a drive's root ($to)." }
    $project = Get-ProjectName
    $fromLabel = Get-StorageLabel $from
    $toLabel = Get-StorageLabel $to
    $copy = $false
    if (Test-PgDataExists $from) {
        if (Test-PgDataExists $to) {
            if ($Migrate) {
                Write-Die "There is already a database in $toLabel; -Migrate never overwrites data. Switch without -Migrate to use the data there, or empty it first."
            }
            Write-Host "Note: $fromLabel and $toLabel both hold a database."
            Write-Host "  Shroud switches to the one in $toLabel; the data in $fromLabel is left as it is."
        } elseif ($Migrate) {
            foreach ($kind in $script:DataKinds) {
                if (Test-StorageHas $to $project $kind) {
                    Write-Die "$(Get-StorageSource $to $project $kind) already holds data; -Migrate never overwrites data."
                }
            }
            Write-Host "This copies the data in $fromLabel to $toLabel."
            Write-Host "  The stack is stopped for the copy; the data in $fromLabel is left as it is."
            if (-not (Confirm-Choice "  Copy the data now?")) {
                Write-Host "Nothing was changed."
                exit 1
            }
            $copy = $true
        } else {
            Write-Line "ERROR: this server's data is in $fromLabel, and there is none in $toLabel." "Red"
            Write-Host "  Switching now would start Shroud on an empty database. Nothing was changed."
            Write-Host ""
            Write-Host "  Copy the data first, with the stack down:"
            Write-CopyCommands $from $to $project $SwitchCommand
            Write-Host ""
            Write-Host "  Or let deploy.ps1 copy it, after asking: $SwitchCommand -Migrate"
            Write-Host "  Either way the data in $fromLabel stays; remove it yourself once the switch works."
            exit 1
        }
    } elseif ($Migrate) {
        Write-Host "Nothing to copy: there is no database in $fromLabel."
    }
    $files = Get-ComposeArgs
    $running = @(Invoke-WithDataDirEnv { Invoke-NativeOutput { docker compose @files ps -q } })
    if ($running.Count -gt 0) {
        Write-Step "Stopping the stack to switch storage..."
        Invoke-Compose @("down")
    }
    if ($copy) {
        try {
            Copy-Storage $from $to $project
        } catch {
            Write-Line "ERROR: copying failed; storage was not switched, and the data in $fromLabel is unchanged." "Red"
            Write-Host "  Remove the partial copy in $toLabel before trying again:"
            Write-CleanupCommands $to $project
            throw
        }
        Write-Host "Copied. The data in $fromLabel is still there; once Shroud works from $toLabel, remove it with:"
        Write-CleanupCommands $from $project
    }
}

# Empties the three data folders in $Dir and makes them again. Their contents belong to uid 70,
# 10001 and nobody, so a container removes them. Nothing else in $Dir is touched.
function Clear-DataDir {
    param([string]$Dir)
    if (-not (Test-Path -LiteralPath $Dir -PathType Container)) { return }
    & docker run --rm -v "${Dir}:/data" $script:DataToolImage rm -rf /data/database /data/nebular /data/media | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "wiping the data in $Dir exited $LASTEXITCODE" }
    foreach ($kind in $script:DataKinds) {
        New-Item -ItemType Directory -Force -Path (Join-Path $Dir $kind.Folder) | Out-Null
    }
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
    $dataDir = if ($ComposeArgs[0] -eq "up") { Get-DataDir } else { "" }
    if ($dataDir) {
        foreach ($kind in $script:DataKinds) {
            New-Item -ItemType Directory -Force -Path (Join-Path $dataDir $kind.Folder) | Out-Null
        }
    }
    try {
        $files = Get-ComposeArgs
        if ($dataDir) {
            # Nebular's entrypoint hands only /data/blobs and /data/meta to its user (uid 10001),
            # not /data itself. A new named volume takes /data's owner from the image; a folder
            # doesn't, and Nebular then fails with "Permission denied". A one-off Nebular
            # container gives it to uid 10001 as root. Postgres and the API chown their own.
            Invoke-WithDataDirEnv {
                & docker compose @files run --rm --no-deps -T --user 0 --entrypoint chown nebular 10001:10001 /data | Out-Null
            }
            if ($LASTEXITCODE -ne 0) { throw "cannot give $(Join-Path $dataDir "nebular") to Nebular's user (uid 10001)" }
        }
        Invoke-WithDataDirEnv { & docker compose @files @ComposeArgs }
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
    $dataDir = Get-DataDir
    Write-Host ""
    Write-Host "  Proxy mode:  $mode"
    if ($dataDir) { Write-Host "  Storage:     $dataDir (SHROUD_DATA_DIR)" }
    else { Write-Host "  Storage:     named Docker volumes" }
    Write-Host "  Web client:  $web"
    Write-Host "  API (iOS):   $api/api/v1"
    if (Test-ProfileEnabled "admin") {
        $adminUrl = Get-AdminPublicUrl
        if (-not $adminUrl) { $adminUrl = Get-EnvValue "ADMIN_PUBLIC_URL" }
        Write-Host "  Admin console: $adminUrl"
    } else {
        Write-Host "  Admin console: off (.\deploy.ps1 -Admin to turn it on)"
    }
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
    Write-Host "    .\deploy.ps1 -Admin              Turn the admin console on and deploy it"
    Write-Host "    .\deploy.ps1 -Admin off          Turn it off; its key and passwords stay in .env"
    Write-Host "    .\deploy.ps1 -Admin bootstrap    Print a one-time operator setup link"
    Write-Host "                                     (--recover, and --name NAME when several exist)"
    Write-Host "    .\deploy.ps1 -Status             Show URLs and container status"
    Write-Host "    .\deploy.ps1 -Ps                 Container table only"
    Write-Host "    .\deploy.ps1 -Logs [svc...]      Follow logs"
    Write-Host "    .\deploy.ps1 -Restart [svc...]   Restart services"
    Write-Host "    .\deploy.ps1 -Rebuild            Rebuild images, then start"
    Write-Host "    .\deploy.ps1 -Down               Stop and remove all services"
    Write-Host "    .\deploy.ps1 -Down -Volumes      Also wipe the Postgres / Nebular / media data"
    Write-Host "    .\deploy.ps1 -Help               This help"
    Write-Host ""
    Write-Host "  Data storage (saved in .env as SHROUD_DATA_DIR, then deploys):"
    Write-Host "    .\deploy.ps1 -DataDir [path]     Keep data in a folder (default .\data, relative"
    Write-Host "                                     to this repository) instead of named volumes"
    Write-Host "    .\deploy.ps1 -NamedVolumes       Back to named Docker volumes (the default)"
    Write-Host "    ... -Migrate                     Copy the data across when switching, after asking;"
    Write-Host "                                     the old volumes or folder stay for you to remove"
    Write-Host ""
}

if ($Help) { Show-Help; exit 0 }

$verbs = @()
if ($Init)    { $verbs += "init" }
if ($Admin)   { $verbs += "admin" }
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

$storage = ""
$dataDirValue = ""
$switchCommand = ""
if ($Admin -and ($DataDir -or $NamedVolumes)) {
    Write-Die "-Admin does not change where data is stored."
}
if ($DataDir -and $NamedVolumes) { Write-Die "-DataDir and -NamedVolumes can't be used together." }
if ($DataDir -or $NamedVolumes) {
    if ($cmd -notin @("up", "rebuild", "init")) {
        Write-Die "-DataDir and -NamedVolumes switch storage for a deploy; they don't go with -$cmd."
    }
    if ($DataDir) {
        $storage = "dir"
        if ($services.Count -gt 1) { Write-Die "-DataDir takes one folder (got: $($services -join ' '))." }
        $dataDirValue = if ($services.Count -eq 1) { $services[0] } else { "./data" }
        $services = @()
        # .env holds it unquoted, and Compose would interpolate a $ in it.
        if ($dataDirValue -match '[$"''#]') { Write-Die "the data folder can't contain `$ `" ' or #: $dataDirValue" }
        $switchCommand = ".\deploy.ps1 -DataDir `"$dataDirValue`""
    } else {
        $storage = "volumes"
        $switchCommand = ".\deploy.ps1 -NamedVolumes"
    }
}
if ($Migrate -and -not $storage) {
    Write-Die "-Migrate copies data while switching storage. Example: .\deploy.ps1 -DataDir .\data -Migrate"
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Die "Docker is not installed or not on PATH."
}
docker compose version 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Die "Docker Compose v2 is required." }

function Invoke-Wizard {
    # The wizard takes a storage choice from -DataDir / -NamedVolumes instead of asking.
    $wizardArgs = @{}
    if ($storage) {
        $wizardArgs.DataDir = $dataDirValue
        $wizardArgs.SwitchCommand = $switchCommand
        $wizardArgs.Migrate = [bool]$Migrate
    }
    & (Join-Path $repoRoot "scripts\setup.ps1") @wizardArgs
    if ($LASTEXITCODE -ne 0) { throw "setup.ps1 exited $LASTEXITCODE" }
    if ((Get-ProxyMode) -eq "npm") {
        docker network inspect proxy-network 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Step "Creating Docker network proxy-network"
            docker network create proxy-network | Out-Null
        }
    }
    Invoke-Compose @("up", "-d", "--build", "--remove-orphans")
    Apply-AdminDb
    Show-Info
}

$script:adminNote = ""
if ($cmd -eq "admin") {
    if (-not (Test-Path -LiteralPath (Join-Path $repoRoot ".env"))) {
        Write-Die "there is no .env yet. Set up the API and web client first: .\deploy.ps1"
    }
    $action = "on"
    $extra = @()
    if ($services.Count -ge 1) {
        $action = $services[0]
        if ($services.Count -gt 1) { $extra = @($services | Select-Object -Skip 1) }
    }
    switch ($action) {
        "on" {
            if ($extra.Count -gt 0) { Write-Die "unexpected argument: $($extra -join ' ')" }
            $already = Test-ProfileEnabled "admin"
            Set-AdminPublicUrl
            if (-not (Get-EnvValue "ADMIN_PORT")) { Set-EnvValue "ADMIN_PORT" "8082" }
            Enable-Profile "admin"
            if ($already) { Write-Step "Admin console is already on. Deploying it..." }
            else { Write-Step "Turning the admin console on..." }
            $script:adminNote = "on"
            $cmd = "up"
        }
        "off" {
            if ($extra.Count -gt 0) { Write-Die "unexpected argument: $($extra -join ' ')" }
            if (-not (Test-ProfileEnabled "admin")) {
                Write-Ok "Admin console is already off."
                Write-Host "  Turn it on with .\deploy.ps1 -Admin"
                exit 0
            }
            Write-Step "Turning the admin console off..."
            Disable-Profile "admin"
            $script:adminNote = "off"
            $cmd = "up"
        }
        "bootstrap" {
            if (-not (Test-ProfileEnabled "admin")) {
                Write-Die "the admin console is off. Run .\deploy.ps1 -Admin first."
            }
            Set-AdminPublicUrl
            Write-Step "Creating a one-time setup link..."
            $files = Get-ComposeArgs
            Invoke-WithDataDirEnv { & docker compose @files run --rm --no-deps -T admin bootstrap @extra }
            if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
            exit 0
        }
        default {
            Write-Die "unknown admin command: $action`n  Valid: .\deploy.ps1 -Admin [off | bootstrap [--recover] [--name NAME]]"
        }
    }
}

try {
    switch ($cmd) {
        "status"  { Show-Info; exit 0 }
        "ps"      { Invoke-Compose @("ps"); exit 0 }
        "logs"    {
            Write-Step "Following logs (Ctrl-C to stop)..."
            $files = Get-ComposeArgs
            Invoke-WithDataDirEnv { & docker compose @files logs -f --tail 200 @services }
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
            $dataDir = Get-DataDir
            if ($Volumes -and $dataDir) {
                Write-Step "Removing containers and wiping the Postgres, Nebular and media data in $dataDir..."
                Invoke-Compose @("down", "--volumes", "--remove-orphans")
                Clear-DataDir $dataDir
            } elseif ($Volumes) {
                Write-Step "Removing containers and named volumes (Postgres, Nebular and media data will be wiped)..."
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
    Add-AdminSecrets
    $envFile = Join-Path $repoRoot ".env"
    if (Select-String -LiteralPath $envFile -Pattern '=(GENERATE_ME)\s*$' -Quiet) {
        Write-Die ".env still contains GENERATE_ME placeholders. Run .\deploy.ps1 -Init."
    }

    if ($storage) {
        Initialize-StorageSwitch -NewValue $dataDirValue -SwitchCommand $switchCommand -Migrate:$Migrate
        if ($storage -eq "dir") { Set-EnvValue "SHROUD_DATA_DIR" $dataDirValue } else { Remove-EnvValue "SHROUD_DATA_DIR" }
        Write-Ok "Storage: $(Get-StorageLabel (Get-DataDir)) (saved in .env)"
        Write-Host ""
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
            Write-Host "  The Postgres image applies POSTGRES_PASSWORD only the first time it sets up"
            Write-Host "  its data ($(Get-PgDataLocation)). A new password in .env is ignored after that."
            Write-Host ""
            Write-Host "  Keep data:  set POSTGRES_PASSWORD in .env to the original (old default: shroud)"
            Write-Host "  Wipe data:  .\deploy.ps1 -Down -Volumes ; .\deploy.ps1"
            Write-Host ""
        }
        throw
    }
    Apply-AdminDb
    $elapsed = (Get-Date) - $startedAt
    Write-Host ""
    Write-Ok ("Deploy finished in {0}m {1}s." -f [int][math]::Floor($elapsed.TotalMinutes), $elapsed.Seconds)
    Show-Info
    if ($script:adminNote -eq "on") {
        $user = Get-EnvValue "POSTGRES_USER"; if (-not $user) { $user = "shroud" }
        $db = Get-EnvValue "POSTGRES_DB"; if (-not $db) { $db = "shroud" }
        $files = Get-ComposeArgs
        $count = ""
        try {
            $count = Invoke-WithDataDirEnv {
                & docker compose @files exec -T postgres psql -q -t -A -v ON_ERROR_STOP=1 -U $user -d $db -c "SELECT count(*) FROM admin.operators"
            }
        } catch { $count = "" }
        if ($count) { $count = ($count | Out-String).Trim() }
        Write-Host ""
        if ($count -match '^[1-9][0-9]*$') {
            Write-Host "  Operators are already enrolled. Open $(Get-EnvValue 'ADMIN_PUBLIC_URL')"
        } else {
            Write-Host "  Create the first operator. The link is shown once:"
            Write-Host "    .\deploy.ps1 -Admin bootstrap"
        }
    } elseif ($script:adminNote -eq "off") {
        Write-Host ""
        Write-Ok "Admin console is off."
        Write-Host "  Its key, database password and operator token stay in .env."
        Write-Host "  Operators and the audit log stay in schema admin."
        Write-Host "  Turn it back on with .\deploy.ps1 -Admin"
    }
}
catch {
    Write-Host ""
    Write-Line "Failed: $($_.Exception.Message)" "Red"
    Write-Line "  Inspect:  .\deploy.ps1 -Ps" "DarkGray"
    Write-Line "  Logs:     .\deploy.ps1 -Logs" "DarkGray"
    exit 1
}
