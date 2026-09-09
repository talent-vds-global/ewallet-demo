#!/usr/bin/env bash
# Kich ban demo cac flow F1-F6 cua ewallet demo-app.
#   ./scripts/demo-flows.sh smoke      # kiem tra 7 service (chay duoc tu Stage B)
#   ./scripts/demo-flows.sh F1|F1-held|F2|F3|F3-limit|F4|F5|F6|all
#
# Endpoint nghiep vu chi ton tai SAU Stage C.

set -uo pipefail

GATEWAY="${GATEWAY:-http://localhost:18080}"
ORDER="${ORDER:-http://localhost:18082}"
BUSINESS="${BUSINESS:-http://localhost:18083}"
THIRDPARTY="${THIRDPARTY:-http://localhost:18084}"
NOTIFICATION="${NOTIFICATION:-http://localhost:18085}"
PARTNERSIM="${PARTNERSIM:-http://localhost:18090}"
DBQ_ORDER="${DBQ_ORDER:-http://localhost:19082}"

FLOW="${1:-smoke}"

C_HDR='\033[36m'; C_LBL='\033[33m'; C_OK='\033[32m'; C_ERR='\033[31m'; C_DIM='\033[90m'; C_OFF='\033[0m'

section() { printf "\n${C_HDR}%s\n  %s\n%s${C_OFF}\n" "$(printf '=%.0s' {1..78})" "$1" "$(printf '=%.0s' {1..78})"; }

newkey() {
  if command -v uuidgen >/dev/null 2>&1; then uuidgen
  elif [ -r /proc/sys/kernel/random/uuid ]; then cat /proc/sys/kernel/random/uuid
  elif command -v openssl >/dev/null 2>&1; then openssl rand -hex 16
  else echo "key-$(date +%s%N)-$RANDOM"; fi
}

# call <label> <method> <url> [json-body]
call() {
  local label="$1" method="$2" url="$3" body="${4:-}"
  printf "\n${C_LBL}> %s${C_OFF}\n${C_DIM}  %s %s${C_OFF}\n" "$label" "$method" "$url"
  local args=(-sS -m 40 -w '\n__HTTP__%{http_code} %{time_total}s' -X "$method" "$url" -H 'Accept: application/json')
  if [ -n "$body" ]; then
    args+=(-H 'Content-Type: application/json' -H "X-Idempotency-Key: $(newkey)" -d "$body")
    printf "${C_DIM}  body: %s${C_OFF}\n" "$body"
  fi
  local out; out="$(curl "${args[@]}" 2>&1)"
  local meta; meta="$(printf '%s' "$out" | tail -n1)"
  local content; content="$(printf '%s' "$out" | sed '$d')"
  local code="${meta#__HTTP__}"; code="${code%% *}"
  if [ "${code:-0}" -ge 200 ] 2>/dev/null && [ "${code:-0}" -lt 300 ] 2>/dev/null; then
    printf "${C_OK}  <- %s${C_OFF}\n" "${meta#__HTTP__}"
  else
    printf "${C_ERR}  <- %s${C_OFF}\n" "${meta#__HTTP__}"
  fi
  [ -n "$content" ] && printf "  %s\n" "$content"
  LAST_BODY="$content"
}

order_id() { printf '%s' "${LAST_BODY:-}" | sed -n 's/.*"orderId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'; }

demo_smoke() {
  section 'SMOKE - 7 service da boot va noi duoc voi nhau chua (chay duoc tu Stage B)'
  call 'gateway health'                       GET "$GATEWAY/actuator/health"
  call 'gateway -> mobileapp'                 GET "$GATEWAY/api/ping"
  call 'gateway -> mobileapp -> order'        GET "$GATEWAY/api/trace-test"
  call 'order + orderdb'                      GET "$ORDER/api/orders/ping"
  call 'business + paymentdb'                 GET "$BUSINESS/admin/ping"
  call 'third-party + thirdpartydb'           GET "$THIRDPARTY/api/thirdparty/ping"
  call 'notification + notifdb'               GET "$NOTIFICATION/api/notifications/ping"
  call 'partner-sim'                          GET "$PARTNERSIM/actuator/health"
}

demo_f1() {
  section 'F1 - Nap tien vi qua doi tac (happy path)'
  call 'Nap 500.000d qua VNPAY' POST "$GATEWAY/api/wallet/topup" \
    '{"customerId":"CUST-001","amount":500000,"currency":"VND","partnerCode":"VNPAY","partnerAccountRef":"9704000000001234"}'
  call 'So du sau khi nap (ky vong 5.500.000d)' GET "$BUSINESS/admin/accounts/CUST-001/balance"
}

demo_f1_held() {
  section 'F1 - Nhanh HELD (cham LOI #2 va LOI #5)'
  call 'Nap 25.000.000d - vuot REVIEW_THRESHOLD' POST "$GATEWAY/api/wallet/topup" \
    '{"customerId":"CUST-003","amount":25000000,"currency":"VND","partnerCode":"VNPAY","partnerAccountRef":"9704000000009999"}'
  printf "\n${C_HDR}Ky vong 202 HELD. So trace nay voi trace COMPLETED trong Jaeger:\n  - thieu span publish (LOI #2)\n  - AuthorizePayment > 700ms (LOI #5)${C_OFF}\n"
}

demo_f2() {
  section 'F2 - Thanh toan hoa don EVN'
  call 'S0 tra cuu hoa don' GET "$GATEWAY/api/wallet/bill?partnerCode=EVN&billCode=PE0123456789"
  call 'Thanh toan 1.250.000d (phi 5.000d)' POST "$GATEWAY/api/wallet/bill/pay" \
    '{"customerId":"CUST-001","partnerCode":"EVN","billCode":"PE0123456789","amount":1250000,"currency":"VND"}'
  section 'F2 - Nap dien thoai VTELCO'
  call 'Nap 100.000d cho 0987654321' POST "$GATEWAY/api/wallet/telco/topup" \
    '{"customerId":"CUST-001","partnerCode":"VTELCO","phoneNumber":"0987654321","amount":100000,"currency":"VND"}'
  section 'F2 - Cross-currency (cham LOI #6)'
  call '1000 USD = 25.000.000d -> theo spec phai HELD' POST "$GATEWAY/api/wallet/bill/pay" \
    '{"customerId":"CUST-003","partnerCode":"EVN","billCode":"PE0123456789","amount":1000,"currency":"USD"}'
  section 'F2 - Hoa don khong ton tai'
  call 'Ky vong BILL_NOT_FOUND' GET "$GATEWAY/api/wallet/bill?partnerCode=EVN&billCode=PE0000000000"
}

demo_f3() {
  section 'F3 - Chuyen tien P2P (khong qua third-party)'
  call 'CUST-001 chuyen 300.000d cho CUST-002 (phi 0)' POST "$GATEWAY/api/wallet/transfer" \
    '{"customerId":"CUST-001","destCustomerId":"CUST-002","amount":300000,"currency":"VND","message":"tra tien com trua"}'
  call 'CUST-003 chuyen 3.000.000d cho CUST-002 (phi 2.200d)' POST "$GATEWAY/api/wallet/transfer" \
    '{"customerId":"CUST-003","destCustomerId":"CUST-002","amount":3000000,"currency":"VND","message":"gop von"}'
  call 'So du nguoi nhan' GET "$BUSINESS/admin/accounts/CUST-002/balance"
}

demo_f3_limit() {
  section 'F3 - Vuot han muc ngay (cham LOI #1: code 100tr vs spec 50tr)'
  for i in 1 2 3; do
    call "Lan $i - chuyen 18.000.000d" POST "$GATEWAY/api/wallet/transfer" \
      '{"customerId":"CUST-003","destCustomerId":"CUST-002","amount":18000000,"currency":"VND"}'
  done
  call 'daily_usage sau 3 lan' GET "$BUSINESS/admin/accounts/CUST-003/balance"
  call 'Han muc trong DB (phai la 50.000.000d)' GET "$BUSINESS/admin/limits"
  printf "\n${C_HDR}Theo spec lan 3 phai bi 422 LIMIT_EXCEEDED. Neu qua duoc -> da tai hien LOI #1.${C_OFF}\n"
}

demo_f4() {
  section 'F4a - Doi tac tu choi -> tu dong hoan tien (duoi 999)'
  call 'So du TRUOC' GET "$BUSINESS/admin/accounts/CUST-001/balance"
  call 'Nap 500.999d -> DECLINED' POST "$GATEWAY/api/wallet/topup" \
    '{"customerId":"CUST-001","amount":500999,"currency":"VND","partnerCode":"VNPAY","partnerAccountRef":"9704000000001234"}'
  OID="$(order_id)"
  call 'So du SAU (phai bang truoc)' GET "$BUSINESS/admin/accounts/CUST-001/balance"
  [ -n "$OID" ] && call 'But toan 2 REVERSED + 2 nguoc chieu POSTED' GET "$BUSINESS/admin/transactions/$OID"

  section 'F4a - Doi tac timeout (duoi 888)'
  call 'Nap 500.888d -> partner ngu 5s, timeout 3s' POST "$GATEWAY/api/wallet/topup" \
    '{"customerId":"CUST-001","amount":500888,"currency":"VND","partnerCode":"VNPAY","partnerAccountRef":"9704000000001234"}'

  section 'F4b - So du khong du (khong can bu tru)'
  call 'CUST-004 (50.000d) chuyen 300.000d' POST "$GATEWAY/api/wallet/transfer" \
    '{"customerId":"CUST-004","destCustomerId":"CUST-002","amount":300000,"currency":"VND"}'
}

demo_f5() {
  section 'F5 - Thong bao bat dong bo'
  printf "${C_LBL}Mo terminal khac va chay: curl -N \"%s/api/notifications/stream?customerId=CUST-001\"${C_OFF}\n" "$GATEWAY"
  call 'Sinh mot event PaymentCompleted' POST "$GATEWAY/api/wallet/transfer" \
    '{"customerId":"CUST-001","destCustomerId":"CUST-002","amount":300000,"currency":"VND"}'
  sleep 2
  call 'Outbox cua CUST-001' GET "$NOTIFICATION/api/notifications?customerId=CUST-001&limit=10"

  section 'F5 - Retry + Dead Letter (CUST-DLQ)'
  call 'Giao dich cua CUST-DLQ -> 3 lan thu roi vao DLT' POST "$GATEWAY/api/wallet/transfer" \
    '{"customerId":"CUST-DLQ","destCustomerId":"CUST-002","amount":100000,"currency":"VND"}'
  printf "\n${C_HDR}Kiem tra Kafka:${C_OFF}\n"
  echo '  docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --all-groups'
  echo '  docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic ewallet.payment.events.DLT --from-beginning --max-messages 5'
}

demo_f6() {
  section 'F6 - Lich su giao dich (cham LOI #4 N+1 query)'
  echo 'Tao 30 giao dich nho...'
  for i in $(seq 1 30); do
    curl -sS -o /dev/null -m 30 -X POST "$GATEWAY/api/wallet/transfer" \
      -H 'Content-Type: application/json' -H "X-Idempotency-Key: $(newkey)" \
      -d '{"customerId":"CUST-001","destCustomerId":"CUST-002","amount":10000,"currency":"VND"}' || true
    [ $((i % 10)) -eq 0 ] && echo "  ...$i"
  done
  call 'Duong client qua BFF' GET "$GATEWAY/api/wallet/transactions?customerId=CUST-001&limit=30"
  call 'Duong Ops qua route /orders/**' GET "$GATEWAY/orders/history?customerId=CUST-001&limit=30"

  section 'F6 - Bang chung tu database-quality-library'
  call 'Ep phan tich ngay' POST "$DBQ_ORDER/analyze-now"
  call 'Findings (tim N_PLUS_ONE va MISSING_INDEX)' GET "$DBQ_ORDER/findings"
  printf "${C_HDR}Dashboard db-quality cua order: %s${C_OFF}\n" "$DBQ_ORDER"
}

case "$FLOW" in
  smoke)    demo_smoke ;;
  F1)       demo_f1 ;;
  F1-held)  demo_f1_held ;;
  F2)       demo_f2 ;;
  F3)       demo_f3 ;;
  F3-limit) demo_f3_limit ;;
  F4)       demo_f4 ;;
  F5)       demo_f5 ;;
  F6)       demo_f6 ;;
  all)      demo_smoke; demo_f1; demo_f1_held; demo_f2; demo_f3; demo_f3_limit; demo_f4; demo_f5; demo_f6 ;;
  *)        echo "Khong biet flow: $FLOW"; echo "Dung: $0 smoke|F1|F1-held|F2|F3|F3-limit|F4|F5|F6|all"; exit 1 ;;
esac

printf "\n${C_HDR}Xem trace: http://localhost:16686${C_OFF}\n"
printf "${C_HDR}File trace cho Trace Analyzer: infra/otel-collector/traces/traces.jsonl${C_OFF}\n"
