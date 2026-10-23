[CmdletBinding()]
param([string] $PostgresBin = '')

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$localDirectory = Join-Path $projectDirectory '.local'
$dataDirectory = Join-Path $localDirectory 'postgres'
$environmentPath = Join-Path $projectDirectory '.env'

if (-not (Test-Path -LiteralPath $environmentPath)) {
    & (Join-Path $PSScriptRoot 'Init-Env.ps1')
}

$settings = @{}
foreach ($line in Get-Content -LiteralPath $environmentPath) {
    $trimmed = $line.Trim()
    if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }
    $parts = $trimmed.Split(@('='), 2)
    if ($parts.Count -ne 2 -or $parts[0] -notmatch '^[A-Z_][A-Z0-9_]*$') {
        throw 'Invalid .env line. Expected NAME=value without shell expressions.'
    }
    $settings[$parts[0]] = $parts[1]
}
foreach ($required in @('POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD', 'PG_PORT')) {
    if ([string]::IsNullOrWhiteSpace($settings[$required])) { throw "Missing $required in .env." }
}
$databaseName = $settings['POSTGRES_DB']
$databaseUser = $settings['POSTGRES_USER']
if ($databaseName -notmatch '^[a-z_][a-z0-9_]{0,62}$' -or $databaseUser -notmatch '^[a-z_][a-z0-9_]{0,62}$') {
    throw 'POSTGRES_DB and POSTGRES_USER must use lowercase letters, digits and underscores, without a leading digit.'
}
[int] $databasePort = 0
if (-not [int]::TryParse($settings['PG_PORT'], [ref] $databasePort) -or $databasePort -lt 1 -or $databasePort -gt 65535) {
    throw 'PG_PORT must be an integer between 1 and 65535.'
}

if ([string]::IsNullOrWhiteSpace($PostgresBin)) {
    $versionFile = Join-Path $dataDirectory 'PG_VERSION'
    if (Test-Path -LiteralPath $versionFile) {
        $majorVersion = (Get-Content -LiteralPath $versionFile).Trim()
        if ($majorVersion -notmatch '^\d+$') { throw 'Invalid PostgreSQL cluster version.' }
        $PostgresBin = "C:\Program Files\PostgreSQL\$majorVersion\bin"
    } else {
        $installation = Get-ChildItem 'C:\Program Files\PostgreSQL' -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '^\d+$' } |
            Sort-Object { [int] $_.Name } -Descending | Select-Object -First 1
        if ($null -eq $installation) { throw 'PostgreSQL is not installed. Install PostgreSQL 17 or use Docker Compose.' }
        $PostgresBin = Join-Path $installation.FullName 'bin'
    }
}
$pgCtl = Join-Path $PostgresBin 'pg_ctl.exe'
$initDb = Join-Path $PostgresBin 'initdb.exe'
$psql = Join-Path $PostgresBin 'psql.exe'
$createDb = Join-Path $PostgresBin 'createdb.exe'
foreach ($binary in @($pgCtl, $initDb, $psql, $createDb)) {
    if (-not (Test-Path -LiteralPath $binary)) { throw "PostgreSQL executable not found: $binary" }
}

New-Item -ItemType Directory -Path $localDirectory -Force | Out-Null
if (-not (Test-Path -LiteralPath (Join-Path $dataDirectory 'PG_VERSION'))) {
    $passwordFile = Join-Path $localDirectory ('initdb-password-' + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        [IO.File]::WriteAllText($passwordFile, $settings['POSTGRES_PASSWORD'], (New-Object Text.UTF8Encoding($false)))
        & $initDb '-D' $dataDirectory '-U' $databaseUser '--encoding=UTF8' '--locale=C' '--auth=scram-sha-256' "--pwfile=$passwordFile"
        if ($LASTEXITCODE -ne 0) { throw 'initdb failed. See the preceding PostgreSQL diagnostic.' }
    } finally {
        if (Test-Path -LiteralPath $passwordFile) { Remove-Item -LiteralPath $passwordFile }
    }
    Add-Content -LiteralPath (Join-Path $dataDirectory 'postgresql.conf') -Value "`nlisten_addresses = '127.0.0.1'`nport = $databasePort`n"
}

& $pgCtl '-D' $dataDirectory 'status' *> $null
if ($LASTEXITCODE -ne 0) {
    $arguments = @('-D', ('"' + $dataDirectory + '"'), '-l', ('"' + (Join-Path $localDirectory 'postgres.log') + '"'), '-w', 'start')
    $process = Start-Process -FilePath $pgCtl -ArgumentList $arguments -WindowStyle Hidden -PassThru
    # Start-Process -Wait also waits for the background server, so wait only for pg_ctl.
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "PostgreSQL failed to start. See .local/postgres.log; port $databasePort may already be occupied."
    }
}

$previousPassword = $env:PGPASSWORD
$previousTimeout = $env:PGCONNECT_TIMEOUT
try {
    $env:PGPASSWORD = $settings['POSTGRES_PASSWORD']
    $env:PGCONNECT_TIMEOUT = '5'
    $connectionArguments = @('-h', '127.0.0.1', '-p', $databasePort, '-U', $databaseUser, '-w')
    $query = "SELECT 1 FROM pg_database WHERE datname = :'target_database';"
    $exists = $query | & $psql @connectionArguments '-d' 'postgres' '-qAt' '-v' 'ON_ERROR_STOP=1' '-v' "target_database=$databaseName"
    if ($LASTEXITCODE -ne 0) { throw 'Local PostgreSQL connection failed. Check the existing .env credentials and cluster port.' }
    if ($exists -ne '1') {
        & $createDb @connectionArguments $databaseName
        if ($LASTEXITCODE -ne 0) { throw 'Failed to create the project database.' }
    }
} finally {
    $env:PGPASSWORD = $previousPassword
    $env:PGCONNECT_TIMEOUT = $previousTimeout
}
Write-Output "PostgreSQL is ready: 127.0.0.1:$databasePort / $databaseName (user: $databaseUser)."
