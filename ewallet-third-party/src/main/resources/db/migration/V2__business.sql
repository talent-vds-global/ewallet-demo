-- thirdpartydb — Stage C: cột nghiệp vụ cho giao dịch đối tác.
-- Nguồn: docs/specs/00-domain-and-conventions.md §10.3

ALTER TABLE partner_transactions
    ADD COLUMN service_type VARCHAR(24),
    ADD COLUMN bill_code    VARCHAR(64),
    ADD COLUMN attempt      INT NOT NULL DEFAULT 1,
    ADD COLUMN fail_reason  VARCHAR(64);
