[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$environmentPath = Join-Path $projectDirectory '.env'
if (-not (Test-Path -LiteralPath $environmentPath)) {
    throw 'Missing .env. Run powershell -ExecutionPolicy Bypass -File scripts/Init-Env.ps1 first.'
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
foreach ($required in @('POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD', 'JWT_SECRET', 'ADMIN_USERNAME', 'ADMIN_PASSWORD', 'APP_PORT', 'PG_PORT')) {
    if ([string]::IsNullOrWhiteSpace($settings[$required])) { throw "Missing $required in .env." }
}

$env:DATABASE_URL = "jdbc:postgresql://localhost:$($settings['PG_PORT'])/$($settings['POSTGRES_DB'])"
$env:DATABASE_USER = $settings['POSTGRES_USER']
$env:DATABASE_PASSWORD = $settings['POSTGRES_PASSWORD']
$env:JWT_SECRET = $settings['JWT_SECRET']
$env:ADMIN_USERNAME = $settings['ADMIN_USERNAME']
$env:ADMIN_PASSWORD = $settings['ADMIN_PASSWORD']
$env:PORT = $settings['APP_PORT']
if ($settings.ContainsKey('JWT_TTL_SECONDS')) { $env:JWT_TTL_SECONDS = $settings['JWT_TTL_SECONDS'] }

Push-Location $projectDirectory
try {
    & (Join-Path $projectDirectory 'mvnw.cmd') compile exec:java
    $buildExitCode = $LASTEXITCODE
} finally { Pop-Location }
exit $buildExitCode
