[CmdletBinding()]
param([string] $PostgresBin = '')

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$dataDirectory = Join-Path $projectDirectory '.local\postgres'
$versionFile = Join-Path $dataDirectory 'PG_VERSION'
if (-not (Test-Path -LiteralPath $versionFile)) { throw 'This project has no initialized local PostgreSQL cluster.' }
if ([string]::IsNullOrWhiteSpace($PostgresBin)) {
    $majorVersion = (Get-Content -LiteralPath $versionFile).Trim()
    if ($majorVersion -notmatch '^\d+$') { throw 'Invalid PostgreSQL cluster version.' }
    $PostgresBin = "C:\Program Files\PostgreSQL\$majorVersion\bin"
}
$pgCtl = Join-Path $PostgresBin 'pg_ctl.exe'
if (-not (Test-Path -LiteralPath $pgCtl)) { throw "PostgreSQL executable not found: $pgCtl" }
& $pgCtl '-D' $dataDirectory 'status' *> $null
if ($LASTEXITCODE -eq 3) {
    Write-Output 'The project PostgreSQL cluster is already stopped.'
    exit 0
}
if ($LASTEXITCODE -ne 0) { throw 'Could not determine the project PostgreSQL status.' }
& $pgCtl '-D' $dataDirectory '-m' 'fast' '-w' 'stop'
if ($LASTEXITCODE -ne 0) { throw 'Failed to stop the project PostgreSQL cluster.' }
Write-Output 'The project PostgreSQL cluster is stopped.'
