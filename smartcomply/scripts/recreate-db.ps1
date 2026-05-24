<#
.SYNOPSIS
    Recommended re-tenant flow: drop the smartcomply database, recreate it,
    let Flyway run all migrations on app boot, then seed 9 fresh users.

.DESCRIPTION
    Safer and cleaner than truncating in-place. Use this when starting work
    for a new company.

    Steps performed:
      1. Confirm with the user.
      2. pg_dump the current database to ./backups/<timestamp>.sql.gz.
      3. DROP DATABASE (terminating live connections first).
      4. CREATE DATABASE.
      5. Print the next steps the user must do manually:
           - Start Spring Boot (Flyway runs V1.0..V1.22).
           - Stop the app.
           - Run scripts/seed-users.sql.

.PARAMETER PgHost      PostgreSQL host (default: localhost).
.PARAMETER PgPort      PostgreSQL port (default: 5432).
.PARAMETER SuperUser   Superuser to drop/create the DB (default: postgres).
.PARAMETER DbName      Database name to recreate (default: smartcomply).
.PARAMETER BackupDir   Where to put the pg_dump (default: ./backups).
.PARAMETER NoBackup    Skip pg_dump (use only for throwaway dev DBs).

.EXAMPLE
    ./scripts/recreate-db.ps1 -PgHost localhost -SuperUser postgres
#>
[CmdletBinding()]
param(
    [string]$PgHost     = 'localhost',
    [int]   $PgPort     = 5432,
    [string]$SuperUser  = 'postgres',
    [string]$DbName     = 'smartcomply',
    [string]$BackupDir  = './backups',
    [switch]$NoBackup
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# Auto-detect PostgreSQL bin directory if tools are not on PATH.
# Searches standard Windows installation paths (all versions).
# ---------------------------------------------------------------------------
function Find-PgBin {
    $onPath = Get-Command psql -ErrorAction SilentlyContinue
    if ($onPath) { return $null }   # already on PATH — nothing to do

    $roots = @(
        'C:\Program Files\PostgreSQL',
        'C:\PostgreSQL',
        'C:\Program Files (x86)\PostgreSQL'
    )
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        # Pick the highest version number found
        $binDir = Get-ChildItem $root -Directory |
            Where-Object { $_.Name -match '^\d' } |
            Sort-Object { [double]($_.Name -replace '[^\d].*','') } -Descending |
            Select-Object -First 1 |
            ForEach-Object { Join-Path $_.FullName 'bin' }
        if ($binDir -and (Test-Path (Join-Path $binDir 'psql.exe'))) {
            return $binDir
        }
    }
    return $null
}

$pgBin = Find-PgBin
if ($pgBin) {
    Write-Host "    (Adding PostgreSQL bin to session PATH: $pgBin)" -ForegroundColor DarkGray
    $env:PATH = "$pgBin;$env:PATH"
}

function Require-Cmd($name) {
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
        throw "$name not found. Tried auto-detect; ensure PostgreSQL client tools are installed."
    }
}
Require-Cmd psql
if (-not $NoBackup) { Require-Cmd pg_dump }

Write-Host "==> Target: $SuperUser@${PgHost}:$PgPort  database=$DbName" -ForegroundColor Cyan
$confirm = Read-Host "This will DROP and RECREATE '$DbName'. Type the database name to confirm"
if ($confirm -ne $DbName) {
    Write-Host "Aborted." -ForegroundColor Yellow
    exit 1
}

# 1. Backup
if (-not $NoBackup) {
    if (-not (Test-Path $BackupDir)) { New-Item -ItemType Directory -Path $BackupDir | Out-Null }
    $stamp     = Get-Date -Format 'yyyyMMdd-HHmmss'
    $backupSql = Join-Path $BackupDir "$DbName-$stamp.sql"
    Write-Host "==> Backing up to $backupSql" -ForegroundColor Cyan
    & pg_dump -h $PgHost -p $PgPort -U $SuperUser -d $DbName -F p -f $backupSql
    if ($LASTEXITCODE -ne 0) { throw "pg_dump failed (exit $LASTEXITCODE)" }
    Write-Host "    Backup OK" -ForegroundColor Green
}

# 2. Terminate connections to the DB
Write-Host "==> Terminating live connections to $DbName" -ForegroundColor Cyan
$terminateSql = @"
SELECT pg_terminate_backend(pid)
FROM   pg_stat_activity
WHERE  datname = '$DbName' AND pid <> pg_backend_pid();
"@
& psql -h $PgHost -p $PgPort -U $SuperUser -d postgres -v ON_ERROR_STOP=1 -c $terminateSql
if ($LASTEXITCODE -ne 0) { throw "terminate-connections failed" }

# 3. Drop + create
Write-Host "==> Dropping $DbName" -ForegroundColor Cyan
& psql -h $PgHost -p $PgPort -U $SuperUser -d postgres -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS `"$DbName`";"
if ($LASTEXITCODE -ne 0) { throw "DROP DATABASE failed" }

Write-Host "==> Creating $DbName" -ForegroundColor Cyan
& psql -h $PgHost -p $PgPort -U $SuperUser -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE `"$DbName`";"
if ($LASTEXITCODE -ne 0) { throw "CREATE DATABASE failed" }

# Helper: run a SQL file and bail on error
function Invoke-SqlFile($label, $file, $user = $SuperUser) {
    $absPath = Resolve-Path $file
    Write-Host "==> $label" -ForegroundColor Cyan
    & psql -h $PgHost -p $PgPort -U $user -d $DbName -v ON_ERROR_STOP=1 -f $absPath
    if ($LASTEXITCODE -ne 0) { throw "$label failed (exit $LASTEXITCODE)" }
}

# 4. Apply base schema (all tables except ai_assessments)
Invoke-SqlFile "Applying base schema (docs/original-schema.sql)" "docs/original-schema.sql"

# 5. Create ai_assessments table + populate flyway_schema_history
#    Flyway's V1.0 is an empty placeholder; the original schema was created by
#    Hibernate ddl-auto. This step creates the missing ai_assessments table
#    (added by migrations V1.19 + V1.20) and records all migrations as done so
#    the Spring Boot app's Flyway has nothing to run on first boot.
Invoke-SqlFile "Applying Flyway baseline (scripts/flyway-baseline.sql)" "scripts/flyway-baseline.sql"

# 6. Seed roles, permissions, role_permissions
Invoke-SqlFile "Seeding master data (docs/seed-data.sql)" "docs/seed-data.sql"

$msg = @"

Schema and master data applied successfully.

Next: seed the 9 fresh users and start the app.

  psql -h $PgHost -p $PgPort -U $SuperUser -d $DbName ``
       -v email_domain=acme.com -v temp_pwd='ChangeMe!2026' ``
       -f scripts/seed-users.sql

  Then start the app (Flyway will do nothing; schema is already complete):
    ./mvnw package -DskipTests
    java -jar target/smartcomply-0.0.1-SNAPSHOT.war

  Log in as superadmin and force a password change for all seed users.
"@
Write-Host $msg -ForegroundColor Green
