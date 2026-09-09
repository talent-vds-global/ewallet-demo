-- Tỉ giá cho demo cross-currency (F2). Chạy sau V900__seed_demo.sql.
INSERT INTO fx_rates (currency, rate_to_vnd) VALUES
    ('VND', 1),
    ('USD', 25000)
ON CONFLICT (currency) DO NOTHING;
