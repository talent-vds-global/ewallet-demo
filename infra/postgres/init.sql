-- Chạy 1 lần khi container Postgres khởi tạo (docker-entrypoint-initdb.d).
-- Mỗi service sở hữu 1 database riêng (mô hình microservice).
CREATE DATABASE orderdb;
CREATE DATABASE paymentdb;
CREATE DATABASE thirdpartydb;
CREATE DATABASE notifdb;

-- Schema chi tiết + seed do Flyway của từng service quản lý (src/main/resources/db/migration).
