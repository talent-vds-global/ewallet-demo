# Đồng bộ proto canonical -> các service dùng gRPC (mô hình đa repo giữ bản sao trong mỗi repo).
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $PSScriptRoot "proto\payment.proto"
$targets = @(
  "ewallet-payment-order\src\main\proto\payment.proto",
  "ewallet-payment-business\src\main\proto\payment.proto"
)
foreach ($t in $targets) {
  $dst = Join-Path $root $t
  New-Item -ItemType Directory -Force -Path (Split-Path $dst) | Out-Null
  Copy-Item $src $dst -Force
  Write-Host "synced -> $t"
}
