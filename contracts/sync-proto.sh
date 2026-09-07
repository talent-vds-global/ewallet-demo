#!/usr/bin/env bash
# Đồng bộ proto canonical -> các service dùng gRPC.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(dirname "$here")"
src="$here/proto/payment.proto"
for t in \
  "ewallet-payment-order/src/main/proto/payment.proto" \
  "ewallet-payment-business/src/main/proto/payment.proto"; do
  mkdir -p "$(dirname "$root/$t")"
  cp "$src" "$root/$t"
  echo "synced -> $t"
done
