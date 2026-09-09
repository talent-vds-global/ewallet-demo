<#
.SYNOPSIS
    Chay kich ban demo cho cac flow F1-F6 cua ewallet demo-app.

.DESCRIPTION
    Goi API qua gateway (mac dinh http://localhost:18080) va in ket qua.
    Cac endpoint nghiep vu chi ton tai SAU Stage C. Truoc do dung -Flow smoke
    de kiem tra 7 service da boot va noi duoc voi nhau.

.EXAMPLE
    .\scripts\demo-flows.ps1 -Flow smoke
    .\scripts\demo-flows.ps1 -Flow F1
    .\scripts\demo-flows.ps1 -Flow F3-limit
    .\scripts\demo-flows.ps1 -Flow all
#>
param(
    [ValidateSet('smoke','F1','F1-held','F2','F3','F3-limit','F4','F5','F6','all')]
    [string]$Flow = 'smoke',
    [string]$Gateway      = 'http://localhost:18080',
    [string]$MobileApp    = 'http://localhost:18081',
    [string]$Order        = 'http://localhost:18082',
    [string]$Business     = 'http://localhost:18083',
    [string]$ThirdParty   = 'http://localhost:18084',
    [string]$Notification = 'http://localhost:18085',
    [string]$PartnerSim   = 'http://localhost:18090',
    [string]$DbQualityOrder = 'http://localhost:19082'
)

$ErrorActionPreference = 'Continue'

function Write-Section($text) {
    Write-Host ''
    Write-Host ('=' * 78) -ForegroundColor DarkGray
    Write-Host "  $text" -ForegroundColor Cyan
    Write-Host ('=' * 78) -ForegroundColor DarkGray
}

function New-IdemKey { [guid]::NewGuid().ToString() }

function Invoke-Demo {
    param(
        [string]$Method = 'GET',
        [Parameter(Mandatory=$true)][string]$Url,
        $Body,
        [string]$IdemKey,
        [string]$Label
    )
    if ($Label) { Write-Host "`n> $Label" -ForegroundColor Yellow }
    Write-Host "  $Method $Url" -ForegroundColor DarkGray

    $headers = @{ 'Accept' = 'application/json' }
    if ($IdemKey) { $headers['X-Idempotency-Key'] = $IdemKey }

    $params = @{ Method = $Method; Uri = $Url; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 30 }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 6 -Compress
        $params['Body'] = [System.Text.Encoding]::UTF8.GetBytes($json)
        $params['ContentType'] = 'application/json; charset=utf-8'
        Write-Host "  body: $json" -ForegroundColor DarkGray
    }

    try {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $resp = Invoke-WebRequest @params
        $sw.Stop()
        Write-Host ("  <- {0} in {1} ms" -f $resp.StatusCode, $sw.ElapsedMilliseconds) -ForegroundColor Green
        if ($resp.Content) { Write-Host "  $($resp.Content)" }
        return $resp.Content
    } catch {
        $r = $_.Exception.Response
        if ($r) {
            $code = [int]$r.StatusCode
            $reader = New-Object System.IO.StreamReader($r.GetResponseStream())
            $content = $reader.ReadToEnd()
            $color = if ($code -ge 500) { 'Red' } else { 'Magenta' }
            Write-Host "  <- $code" -ForegroundColor $color
            if ($content) { Write-Host "  $content" }
            return $content
        }
        Write-Host "  <- KHONG KET NOI DUOC: $($_.Exception.Message)" -ForegroundColor Red
        return $null
    }
}

function Get-OrderId($json) {
    if (-not $json) { return $null }
    try { return ($json | ConvertFrom-Json).orderId } catch { return $null }
}

# ------------------------------------------------------------------ smoke
function Demo-Smoke {
    Write-Section 'SMOKE - 7 service da boot va noi duoc voi nhau chua (chay duoc tu Stage B)'
    Invoke-Demo -Url "$Gateway/actuator/health"            -Label 'gateway health'
    Invoke-Demo -Url "$Gateway/api/ping"                   -Label 'gateway -> mobileapp'
    Invoke-Demo -Url "$Gateway/api/trace-test"             -Label 'gateway -> mobileapp -> order (trace 3 tang)'
    Invoke-Demo -Url "$Order/api/orders/ping"              -Label 'order + orderdb'
    Invoke-Demo -Url "$Business/admin/ping"                -Label 'business + paymentdb'
    Invoke-Demo -Url "$ThirdParty/api/thirdparty/ping"     -Label 'third-party + thirdpartydb'
    Invoke-Demo -Url "$Notification/api/notifications/ping" -Label 'notification + notifdb'
    Invoke-Demo -Url "$PartnerSim/actuator/health"         -Label 'partner-sim'
    Write-Host "`nJaeger UI: http://localhost:16686" -ForegroundColor Cyan
}

# ------------------------------------------------------------------ F1
function Demo-F1 {
    Write-Section 'F1 - Nap tien vi qua doi tac (happy path)'
    Invoke-Demo -Method POST -Url "$Gateway/api/wallet/topup" -IdemKey (New-IdemKey) `
        -Label 'Nap 500.000d qua VNPAY' `
        -Body @{ customerId='CUST-001'; amount=500000; currency='VND'; partnerCode='VNPAY'; partnerAccountRef='9704000000001234' }

    Invoke-Demo -Url "$Business/admin/accounts/CUST-001/balance" -Label 'So du sau khi nap (ky vong 5.500.000d)'

    Write-Section 'F1 - Idempotency (goi 2 lan cung key -> chi 1 don)'
    $key = New-IdemKey
    $body = @{ customerId='CUST-001'; amount=100000; currency='VND'; partnerCode='VNPAY'; partnerAccountRef='9704000000001234' }
    Invoke-Demo -Method POST -Url "$Gateway/api/wallet/topup" -IdemKey $key -Body $body -Label 'lan 1'
    Invoke-Demo -Method POST -Url "$Gateway/api/wallet/topup" -IdemKey $key -Body $body -Label 'lan 2 - phai tra ve dung don cu'
}

function Demo-F1Held {
    Write-Section 'F1 - Nhanh HELD (cham LOI #2 khong publish PaymentHeld va LOI #5 sleep 700ms)'
    Invoke-Demo -Method POST -Url "$Gateway/api/wallet/topup" -IdemKey (New-IdemKey) `
        -Label 'Nap 25.000.000d - vuot REVIEW_THRESHOLD 20.000.000d' `
        -Body @{ customerId='CUST-003'; amount=25000000; currency='VND'; partnerCode='VNPAY'; partnerAccountRef='9704000000009999' }
    Write-Host "`nKy vong: 202 status=HELD. Mo Jaeger va so trace nay voi trace COMPLETED:" -ForegroundColor Cyan
    Write-Host "  - thieu span publish ewallet.payment.events  (LOI #2)" -ForegroundColor Cyan
    Write-Host "  - span AuthorizePayment > 700ms              (LOI #5)" -ForegroundColor Cyan
}

# ------------------------------------------------------------------ F2
function Demo-F2 {
    Write-Section 'F2 - Thanh toan hoa don EVN'
    Invoke-Demo -Url "$Gateway/api/wallet/bill?partnerCode=EVN&billCode=PE0123456789" -Label 'S0 tra cuu hoa don'
    Invoke-Demo -Method POST -Url "$Gateway/api/wallet/bill/pay" -IdemKey (New-IdemKey) `
        -Label 'Thanh toan hoa don 1.250.000d (phi 5.000d)' `
        -Body @{ customerId='CUST-001'; partnerCode='EVN'; billCode='PE0123456789'; amount=1250000; currency='VND' }

    Write-Section 'F2 - Nap dien thoai VTELCO'
    Invoke-Demo -Method POST -Url "$Gateway/api/wallet/telco/topup" -IdemKey (New-IdemKey) `
        -Label 'Nap 100.000d cho 0987654321 (phi 0)' `
        -Body @{ customerId='CUST-001'; partnerCode='VTELCO'; phoneNumber='0987654321'; amount=100000; currency='VND' }

    Write-Section 'F2 - Cross-currency (cham LOI #6 gRPC bo qua field currency)'
    Invoke-Demo -Method POST -Url "$Gateway/api/wallet/bill/pay" -IdemKey (New-IdemKey) `
        -Label '1000 USD = 25.000.000d -> theo spec phai HELD' `
        -Body @{ customerId='CUST-003'; partnerCode='EVN'; billCode='PE0123456789'; amount=1000; currency='USD' }

    Write-Section 'F2 - Hoa don khong ton tai'
    Invoke-Demo -Url "$Gateway/api/wallet/bill?partnerCode=EVN&billCode=PE0000000000" -Label 'Ky vong BILL_NOT_FOUND'
}

# ------------------------------------------------------------------ F3
function Demo-F3 {
    Write-Section 'F3 - Chuyen tien P2P (khong qua third-party)'
    Invoke-Demo -Method POST -Url "$Gateway/api/wallet/transfer" -IdemKey (New-IdemKey) `
        -Label 'CUST-001 chuyen 300.000d cho CUST-002 (phi 0)' `
        -Body @{ customerId='CUST-001'; destCustomerId='CUST-002'; amount=300000; currency='VND'; message='tra tien com trua' }

    Invoke-Demo -Method POST -Url "$Gateway/api/wallet/transfer" -IdemKey (New-IdemKey) `
        -Label 'CUST-003 chuyen 3.000.000d cho CUST-002 (phi 2.200d)' `
        -Body @{ customerId='CUST-003'; destCustomerId='CUST-002'; amount=3000000; currency='VND'; message='gop von' }

    Invoke-Demo -Url "$Business/admin/accounts/CUST-002/balance" -Label 'So du nguoi nhan'
}

function Demo-F3Limit {
    Write-Section 'F3 - Vuot han muc ngay (cham LOI #1: code 100tr vs spec 50tr)'
    for ($i = 1; $i -le 3; $i++) {
        Invoke-Demo -Method POST -Url "$Gateway/api/wallet/transfer" -IdemKey (New-IdemKey) `
            -Label "Lan $i - chuyen 18.000.000d (tong ngay se la $($i * 18)tr)" `
            -Body @{ customerId='CUST-003'; destCustomerId='CUST-002'; amount=18000000; currency='VND' }
    }
    Invoke-Demo -Url "$Business/admin/accounts/CUST-003/balance" -Label 'daily_usage sau 3 lan'
    Write-Host "`nKy vong theo spec: lan 3 phai bi tu choi 422 LIMIT_EXCEEDED (54tr > 50tr)." -ForegroundColor Cyan
    Write-Host "Neu lan 3 van thanh cong -> da tai hien LOI #1." -ForegroundColor Cyan
    Invoke-Demo -Url "$Business/admin/limits" -Label 'Han muc trong DB (phai la 50.000.000d)'
}

# ------------------------------------------------------------------ F4
function Demo-F4 {
    Write-Section 'F4a - Doi tac tu choi -> tu dong hoan tien (so tien co duoi 999)'
    Invoke-Demo -Url "$Business/admin/accounts/CUST-001/balance" -Label 'So du TRUOC'
    $json = Invoke-Demo -Method POST -Url "$Gateway/api/wallet/topup" -IdemKey (New-IdemKey) `
        -Label 'Nap 500.999d -> partner-sim tra DECLINED' `
        -Body @{ customerId='CUST-001'; amount=500999; currency='VND'; partnerCode='VNPAY'; partnerAccountRef='9704000000001234' }
    Invoke-Demo -Url "$Business/admin/accounts/CUST-001/balance" -Label 'So du SAU (phai bang truoc)'
    $oid = Get-OrderId $json
    if ($oid) { Invoke-Demo -Url "$Business/admin/transactions/$oid" -Label 'But toan: 2 REVERSED + 2 nguoc chieu POSTED' }

    Write-Section 'F4a - Doi tac timeout (so tien co duoi 888)'
    Invoke-Demo -Method POST -Url "$Gateway/api/wallet/topup" -IdemKey (New-IdemKey) `
        -Label 'Nap 500.888d -> partner-sim ngu 5s, third-party timeout 3s' `
        -Body @{ customerId='CUST-001'; amount=500888; currency='VND'; partnerCode='VNPAY'; partnerAccountRef='9704000000001234' }

    Write-Section 'F4b - So du khong du (khong can bu tru)'
    Invoke-Demo -Method POST -Url "$Gateway/api/wallet/transfer" -IdemKey (New-IdemKey) `
        -Label 'CUST-004 (so du 50.000d) chuyen 300.000d' `
        -Body @{ customerId='CUST-004'; destCustomerId='CUST-002'; amount=300000; currency='VND' }
}

# ------------------------------------------------------------------ F5
function Demo-F5 {
    Write-Section 'F5 - Thong bao bat dong bo'
    Write-Host 'Mo mot cua so PowerShell khac va chay lenh sau de xem SSE:' -ForegroundColor Yellow
    Write-Host "  curl.exe -N `"$Gateway/api/notifications/stream?customerId=CUST-001`"" -ForegroundColor Gray

    Invoke-Demo -Method POST -Url "$Gateway/api/wallet/transfer" -IdemKey (New-IdemKey) `
        -Label 'Sinh mot event PaymentCompleted' `
        -Body @{ customerId='CUST-001'; destCustomerId='CUST-002'; amount=300000; currency='VND' }

    Start-Sleep -Seconds 2
    Invoke-Demo -Url "$Notification/api/notifications?customerId=CUST-001&limit=10" -Label 'Outbox cua CUST-001'

    Write-Section 'F5 - Retry + Dead Letter (khach CUST-DLQ duoc cau hinh gui that bai)'
    Invoke-Demo -Method POST -Url "$Gateway/api/wallet/transfer" -IdemKey (New-IdemKey) `
        -Label 'Giao dich cua CUST-DLQ -> 3 lan thu roi vao DLT' `
        -Body @{ customerId='CUST-DLQ'; destCustomerId='CUST-002'; amount=100000; currency='VND' }

    Write-Host "`nKiem tra 2 consumer group va DLT:" -ForegroundColor Cyan
    Write-Host '  docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --all-groups' -ForegroundColor Gray
    Write-Host '  docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic ewallet.payment.events.DLT --from-beginning --max-messages 5' -ForegroundColor Gray
}

# ------------------------------------------------------------------ F6
function Demo-F6 {
    Write-Section 'F6 - Lich su giao dich (cham LOI #4 N+1 query)'
    Write-Host 'Tao 30 giao dich nho de lich su du dai...' -ForegroundColor Yellow
    for ($i = 1; $i -le 30; $i++) {
        $headers = @{ 'X-Idempotency-Key' = (New-IdemKey); 'Content-Type' = 'application/json' }
        $body = (@{ customerId='CUST-001'; destCustomerId='CUST-002'; amount=10000; currency='VND' } | ConvertTo-Json -Compress)
        try {
            Invoke-WebRequest -Method POST -Uri "$Gateway/api/wallet/transfer" -Headers $headers `
                -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -UseBasicParsing -TimeoutSec 30 | Out-Null
        } catch { }
        if ($i % 10 -eq 0) { Write-Host "  ...$i" -ForegroundColor DarkGray }
    }

    Invoke-Demo -Url "$Gateway/api/wallet/transactions?customerId=CUST-001&limit=30" -Label 'Duong client qua BFF'
    Invoke-Demo -Url "$Gateway/orders/history?customerId=CUST-001&limit=30"          -Label 'Duong Ops qua route /orders/**'

    Write-Section 'F6 - Bang chung tu database-quality-library'
    Invoke-Demo -Method POST -Url "$DbQualityOrder/analyze-now" -Label 'Ep phan tich ngay'
    Invoke-Demo -Url "$DbQualityOrder/findings"                 -Label 'Findings (tim rule N_PLUS_ONE va MISSING_INDEX)'
    Write-Host "`nDashboard db-quality cua order: $DbQualityOrder" -ForegroundColor Cyan
}

# ------------------------------------------------------------------ main
switch ($Flow) {
    'smoke'    { Demo-Smoke }
    'F1'       { Demo-F1 }
    'F1-held'  { Demo-F1Held }
    'F2'       { Demo-F2 }
    'F3'       { Demo-F3 }
    'F3-limit' { Demo-F3Limit }
    'F4'       { Demo-F4 }
    'F5'       { Demo-F5 }
    'F6'       { Demo-F6 }
    'all'      { Demo-Smoke; Demo-F1; Demo-F1Held; Demo-F2; Demo-F3; Demo-F3Limit; Demo-F4; Demo-F5; Demo-F6 }
}

Write-Host ''
Write-Host 'Xem trace: http://localhost:16686' -ForegroundColor Cyan
Write-Host 'File trace cho Trace Analyzer: infra/otel-collector/traces/traces.jsonl' -ForegroundColor Cyan
