# Chay test + JaCoCo cho tat ca service roi in bang do phu.
#
#   .\scripts\run-tests.ps1                  # chay het
#   .\scripts\run-tests.ps1 -Service order   # chi mot service (khop mot phan ten)
#   .\scripts\run-tests.ps1 -SkipBuild       # chi in lai bang tu bao cao da co
#
# Moi service la mot project Maven doc lap nen phai chay lan luot tung thu muc.

param(
    [string]$Service = "",
    [switch]$SkipBuild,
    [switch]$Gaps = $true
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

$services = @(
    "ewallet-payment-business",
    "ewallet-payment-order",
    "ewallet-third-party",
    "ewallet-notification",
    "ewallet-business-customer-mobileapp",
    "ewallet-gateway",
    "partner-sim"
)

if ($Service) {
    $services = $services | Where-Object { $_ -like "*$Service*" }
    if (-not $services) {
        Write-Error "Khong co service nao khop '$Service'"
    }
}

# Maven: uu tien mvn tren PATH, khong co thi dung ban Maven wrapper da tai ve ~/.m2/wrapper.
$mvn = (Get-Command mvn -ErrorAction SilentlyContinue)
if ($mvn) {
    $mvnCmd = $mvn.Source
} else {
    $mvnCmd = Get-ChildItem "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-*\*\bin\mvn.cmd" -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
    if ($mvnCmd) { Write-Host "Khong co 'mvn' tren PATH, dung: $mvnCmd" -ForegroundColor DarkGray }
}

if (-not $SkipBuild) {
    if (-not $mvnCmd) {
        Write-Error "Khong tim thay 'mvn' tren PATH lan trong ~/.m2/wrapper. Cai Maven hoac them vao PATH roi chay lai."
    }

    $failed = @()
    foreach ($s in $services) {
        $dir = Join-Path $root $s
        Write-Host ""
        Write-Host "==== $s ====" -ForegroundColor Cyan
        Push-Location $dir
        try {
            & $mvnCmd -B verify
            if ($LASTEXITCODE -ne 0) { $failed += $s }
        } finally {
            Pop-Location
        }
    }

    if ($failed.Count -gt 0) {
        Write-Host ""
        Write-Host "Test that bai o: $($failed -join ', ')" -ForegroundColor Red
        exit 1
    }
}

Write-Host ""
$args = @("$root\scripts\coverage-report.py")
if ($Gaps) { $args += "--gaps" }
& python @args
