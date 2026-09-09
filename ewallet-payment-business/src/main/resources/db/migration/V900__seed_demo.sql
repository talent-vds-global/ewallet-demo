-- paymentdb — dữ liệu demo. Version 900 để luôn chạy SAU mọi migration nghiệp vụ (V2, V3...).
-- Nguồn sự thật: docs/specs/00-domain-and-conventions.md §11.
-- Chỉ dùng cột có từ V1__init.sql, nên file này chạy được ngay cả trước Stage C.

-- ---------- Ví khách hàng ----------
INSERT INTO accounts (id, customer_id, account_type, currency, status) VALUES
    ('00000000-0000-4000-8000-000000000001', 'CUST-001', 'WALLET', 'VND', 'ACTIVE'),  -- Nguyen Van A
    ('00000000-0000-4000-8000-000000000002', 'CUST-002', 'WALLET', 'VND', 'ACTIVE'),  -- Tran Thi B
    ('00000000-0000-4000-8000-000000000003', 'CUST-003', 'WALLET', 'VND', 'ACTIVE'),  -- Le Van C  (demo han muc + HELD)
    ('00000000-0000-4000-8000-000000000004', 'CUST-004', 'WALLET', 'VND', 'ACTIVE'),  -- Pham Thi D (demo INSUFFICIENT_FUNDS)
    ('00000000-0000-4000-8000-000000000005', 'CUST-DLQ', 'WALLET', 'VND', 'ACTIVE'),  -- Vu Van E   (demo retry + DLT)
    ('00000000-0000-4000-8000-000000000006', 'CUST-LOCK','WALLET', 'VND', 'LOCKED')   -- demo ACCOUNT_INACTIVE
ON CONFLICT (id) DO NOTHING;

-- ---------- Tài khoản hệ thống ----------
INSERT INTO accounts (id, customer_id, account_type, currency, status) VALUES
    ('00000000-0000-4000-8000-0000000000f1', 'SYSTEM',  'SYSTEM_SUSPENSE', 'VND', 'ACTIVE'),  -- nguon cua TOP_UP
    ('00000000-0000-4000-8000-0000000000f2', 'SYSTEM',  'SYSTEM_FEE',      'VND', 'ACTIVE'),  -- thu phi
    ('00000000-0000-4000-8000-0000000000a1', 'VNPAY',   'PARTNER_SETTLE',  'VND', 'ACTIVE'),
    ('00000000-0000-4000-8000-0000000000a2', 'EVN',     'PARTNER_SETTLE',  'VND', 'ACTIVE'),
    ('00000000-0000-4000-8000-0000000000a3', 'VTELCO',  'PARTNER_SETTLE',  'VND', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

-- ---------- Số dư ban đầu ----------
INSERT INTO account_balances (account_id, balance) VALUES
    ('00000000-0000-4000-8000-000000000001',     5000000),
    ('00000000-0000-4000-8000-000000000002',     1000000),
    ('00000000-0000-4000-8000-000000000003',   300000000),
    ('00000000-0000-4000-8000-000000000004',       50000),
    ('00000000-0000-4000-8000-000000000005',    10000000),
    ('00000000-0000-4000-8000-000000000006',     1000000),
    ('00000000-0000-4000-8000-0000000000f1',           0),
    ('00000000-0000-4000-8000-0000000000f2',           0),
    ('00000000-0000-4000-8000-0000000000a1',           0),
    ('00000000-0000-4000-8000-0000000000a2',           0),
    ('00000000-0000-4000-8000-0000000000a3',           0)
ON CONFLICT (account_id) DO NOTHING;

-- ---------- Cấu hình hạn mức (GIÁ TRỊ ĐÚNG THEO SPEC) ----------
-- V1 da chen DAILY_TRANSFER_LIMIT = 50000000. Bo sung cac khoa con lai.
-- LUU Y: Stage C se code LimitPolicy dung HANG SO 100_000_000 thay vi doc bang nay -> LOI CO CHU DICH #1.
INSERT INTO limit_config (id, limit_value, currency) VALUES
    ('DAILY_TRANSFER_LIMIT', 50000000, 'VND'),
    ('REVIEW_THRESHOLD',     20000000, 'VND'),
    ('MIN_TXN_AMOUNT',          10000, 'VND'),
    ('MAX_TXN_TOP_UP',       50000000, 'VND'),
    ('MAX_TXN_BILL',         50000000, 'VND'),
    ('MAX_TXN_P2P',          30000000, 'VND')
ON CONFLICT (id) DO NOTHING;

-- ---------- Stage C bo sung ----------
-- Sau khi V2__business.sql tao bang fx_rates, them file V901__seed_fx.sql:
--   INSERT INTO fx_rates (currency, rate_to_vnd) VALUES ('VND', 1), ('USD', 25000);
