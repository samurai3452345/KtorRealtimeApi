[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$environmentPath = Join-Path $projectDirectory '.env'
if (-not (Test-Path -LiteralPath $environmentPath)) { throw 'Missing .env. Run scripts/Init-Env.ps1 first.' }
$settings = @{}
foreach ($line in Get-Content -LiteralPath $environmentPath) {
    $trimmed = $line.Trim()
    if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }
    $parts = $trimmed.Split(@('='), 2)
    if ($parts.Count -ne 2 -or $parts[0] -notmatch '^[A-Z_][A-Z0-9_]*$') { throw 'Invalid .env line.' }
    $settings[$parts[0]] = $parts[1]
}
foreach ($required in @('POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD', 'PG_PORT')) {
    if ([string]::IsNullOrWhiteSpace($settings[$required])) { throw "Missing $required in .env." }
}
$env:TEST_DATABASE_URL = "jdbc:postgresql://localhost:$($settings['PG_PORT'])/$($settings['POSTGRES_DB'])"
$env:TEST_DATABASE_USER = $settings['POSTGRES_USER']
$env:TEST_DATABASE_PASSWORD = $settings['POSTGRES_PASSWORD']
Push-Location $projectDirectory
try {
    & (Join-Path $projectDirectory 'mvnw.cmd') -B verify
    $buildExitCode = $LASTEXITCODE
} finally { Pop-Location }
exit $buildExitCode
