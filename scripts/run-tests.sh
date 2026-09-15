#!/usr/bin/env bash
# Chay test + JaCoCo cho tat ca service roi in bang do phu.
#
#   ./scripts/run-tests.sh                # chay het
#   ./scripts/run-tests.sh order          # chi service co ten chua "order"
#   SKIP_BUILD=1 ./scripts/run-tests.sh   # chi in lai bang tu bao cao da co
#
# Moi service la mot project Maven doc lap nen phai chay lan luot tung thu muc.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FILTER="${1:-}"
MVN="${MVN:-mvn}"

SERVICES=(
    ewallet-payment-business
    ewallet-payment-order
    ewallet-third-party
    ewallet-notification
    ewallet-business-customer-mobileapp
    ewallet-gateway
    partner-sim
)

if [[ -n "$FILTER" ]]; then
    mapfile -t SERVICES < <(printf '%s\n' "${SERVICES[@]}" | grep -- "$FILTER" || true)
    if [[ ${#SERVICES[@]} -eq 0 ]]; then
        echo "Khong co service nao khop '$FILTER'" >&2
        exit 1
    fi
fi

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
    if ! command -v "$MVN" >/dev/null 2>&1; then
        echo "Khong tim thay '$MVN'. Cai Maven hoac dat bien MVN tro toi file mvn." >&2
        exit 1
    fi

    failed=()
    for s in "${SERVICES[@]}"; do
        echo
        echo "==== $s ===="
        if ! (cd "$ROOT/$s" && "$MVN" -B verify); then
            failed+=("$s")
        fi
    done

    if [[ ${#failed[@]} -gt 0 ]]; then
        echo
        echo "Test that bai o: ${failed[*]}" >&2
        exit 1
    fi
fi

echo
python "$ROOT/scripts/coverage-report.py" --gaps
