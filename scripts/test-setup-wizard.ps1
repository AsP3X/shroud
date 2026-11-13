#Requires -Version 5.1
# Regression check for scripts\setup.ps1: a re-run keeps the .env lines the wizard doesn't ask
# about (COMPOSE_PROJECT_NAME and the host ports of a second stack above all) and offers the
# ports .env already has. Runs the wizard in a scratch folder with deploy.ps1's own .env helpers
# and stubs for the storage ones, so no Docker is needed. Exits 1 on the first failed check.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\test-setup-wizard.ps1
$ErrorActionPreference = "Stop"
$sourceRoot = Split-Path -Parent (Split-Path -Parent $PSCommandPath)

# The helpers setup.ps1 takes from deploy.ps1, read out of deploy.ps1 itself rather than
# copied, so the check runs against the functions the wizard really calls. They find .env
# through $repoRoot, which setup.ps1 sets to the scratch folder while it runs.
$tokens = $null
$parseErrors = $null
$deployAst = [System.Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $sourceRoot "deploy.ps1"), [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count -gt 0) { throw "deploy.ps1 does not parse: $($parseErrors[0].Message)" }
$wanted = @("Write-Line", "Write-Die", "Read-EnvLines", "Write-EnvLines", "Get-EnvValue")
$functions = $deployAst.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $wanted -contains $node.Name
    }, $false)
if (@($functions).Count -ne $wanted.Count) { throw "deploy.ps1 no longer defines all of: $($wanted -join ', ')" }
foreach ($function in $functions) { . ([scriptblock]::Create($function.Extent.Text)) }
$UseColor = $false

# Storage stubs: named volumes, no data yet, nothing to switch.
function Initialize-StorageSwitch { param([string]$NewValue, [string]$SwitchCommand, [switch]$Migrate) }
function Resolve-DataDir { param([string]$Value) return $Value }
function Get-StorageLabel { param([string]$Storage) return "named Docker volumes" }
function Test-PgDataExists { param([string]$Storage) return $false }

$scratch = Join-Path ([System.IO.Path]::GetTempPath()) ("shroud-setup-test-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path (Join-Path $scratch "scripts") | Out-Null
Copy-Item -LiteralPath (Join-Path $sourceRoot "scripts\setup.ps1") -Destination (Join-Path $scratch "scripts")
$envPath = Join-Path $scratch ".env"
$failures = 0

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if ($Condition) {
        Write-Host "ok   $Message"
    } else {
        Write-Host "FAIL $Message"
        $script:failures++
    }
}

function Invoke-Wizard {
    $previousYes = $env:SHROUD_SETUP_ASSUME_YES
    $env:SHROUD_SETUP_ASSUME_YES = "1"
    try {
        & (Join-Path $scratch "scripts\setup.ps1") *> $null
        if ($LASTEXITCODE -ne 0) { throw "setup.ps1 exited $LASTEXITCODE" }
    } finally {
        $env:SHROUD_SETUP_ASSUME_YES = $previousYes
    }
    return @([System.IO.File]::ReadAllLines($envPath))
}

function Get-Lines {
    param([string[]]$Lines, [string]$Key)
    return @($Lines | Where-Object { $_ -match "^$Key=" })
}

Push-Location
try {
    # A second stack's .env, as the README's "Two stacks on one machine" asks for it.
    [System.IO.File]::WriteAllLines($envPath, [string[]]@(
            "PROXY_MODE=local"
            "WEB_PORT=8181"
            "API_PORT=8180"
            "POSTGRES_PASSWORD=keepme"
            "# second stack on this machine"
            "COMPOSE_PROJECT_NAME=shroud-two"
            "POSTGRES_PORT=5532"
            "REDIS_PORT=6479"
            "NEBULAR_PORT=9100"
            "IOS_MIN_VERSION=1.2.0"
        ))

    $first = Invoke-Wizard
    foreach ($kept in @("COMPOSE_PROJECT_NAME=shroud-two", "POSTGRES_PORT=5532", "REDIS_PORT=6479",
            "NEBULAR_PORT=9100", "IOS_MIN_VERSION=1.2.0", "# second stack on this machine")) {
        Assert-True ($first -contains $kept) "a re-run keeps '$kept'"
    }
    Assert-True ($first -contains "WEB_PORT=8181") "the web port defaults to the one in .env"
    Assert-True ($first -contains "API_PORT=8180") "the API port defaults to the one in .env"
    Assert-True ($first -contains "WEB_PUBLIC_URL=http://localhost:8181") "the web URL follows the kept port"
    Assert-True ($first -contains "POSTGRES_PASSWORD=keepme") "the Postgres password is reused"
    foreach ($key in @("WEB_PORT", "API_PORT", "POSTGRES_PASSWORD", "PROXY_MODE", "COMPOSE_PROJECT_NAME")) {
        Assert-True ((Get-Lines $first $key).Count -eq 1) "$key appears once"
    }

    $second = Invoke-Wizard
    $header = @($second | Where-Object { $_ -like "# Kept from the previous .env*" })
    Assert-True ($header.Count -eq 1) "a second re-run writes the kept-settings header once"
    Assert-True ((Get-Lines $second "COMPOSE_PROJECT_NAME").Count -eq 1) "a second re-run keeps COMPOSE_PROJECT_NAME once"
    Assert-True (@($second | Where-Object { $_ -eq "# second stack on this machine" }).Count -eq 1) "a second re-run keeps a comment once"
    Assert-True (@($second | Where-Object { $_ -eq "" }).Count -eq 1) "a second re-run adds no blank lines"

    # A first run has nothing to keep and the usual ports.
    Remove-Item -LiteralPath $envPath
    $fresh = Invoke-Wizard
    Assert-True ($fresh -contains "WEB_PORT=8081" -and $fresh -contains "API_PORT=8080") "a first run uses ports 8081 and 8080"
    Assert-True (@($fresh | Where-Object { $_ -like "# Kept from*" }).Count -eq 0) "a first run keeps nothing"
    Assert-True ((Get-Lines $fresh "COMPOSE_PROJECT_NAME").Count -eq 0) "a first run sets no project name"
} finally {
    # setup.ps1 changes into the scratch folder.
    Pop-Location
    Remove-Item -LiteralPath $scratch -Recurse -Force
}

if ($failures -gt 0) {
    Write-Host "$failures check(s) failed."
    exit 1
}
Write-Host "All checks passed."
exit 0
