# Notification Service — nền tảng

Java 21, Spring Boot 3.5.16, Maven 3.9.11 (Wrapper), API port 8086. Flyway tạo `notifications` và `notification_receipts` theo contract v2.1. Chỉ có health; chưa triển khai Kafka/API nghiệp vụ. Đối chiếu nguồn và mâu thuẫn tại [implementation-decisions.md](docs/implementation-decisions.md).

## Chuẩn bị PostgreSQL

PostgreSQL ở localhost:5432. Dùng tài khoản quản trị PostgreSQL để tạo riêng role/database, không dùng DB User Service. Trong psql:

```sql
CREATE ROLE notification_app LOGIN;
```
```text
\password notification_app
```
```sql
CREATE DATABASE notification_db OWNER notification_app;
```

`\password` hỏi mật khẩu tương tác, tránh ghi mật khẩu trong SQL/source/history. Nếu role/database đã có, kiểm owner/quyền thay vì tạo lại. Flyway cần quyền tạo bảng/index trong schema public của notification_db.

## Chạy PowerShell

Chạy từ thư mục notification-service, Java 21 có trên PATH (hoặc JAVA_HOME). Wrapper tự tải Maven lần đầu, cần Internet; không cần Maven cài riêng.

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/notification_db'
$env:DB_USERNAME = 'notification_app'
$dbSecret = Read-Host 'Database password' -AsSecureString
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $dbSecret).Password
Remove-Variable dbSecret
.\mvnw.cmd spring-boot:run
```

Trong terminal khác:

```powershell
Invoke-RestMethod http://localhost:8086/actuator/health
```

Kỳ vọng `status: UP`, không có config hoặc thông tin DB. DB phải kết nối được và migration thành công để ứng dụng khởi động. Sau khi dừng có thể xóa biến mật khẩu bằng `Remove-Item Env:DB_PASSWORD`.

Có thể dùng file `.env`: `Copy-Item .env.example .env`, rồi nhập mật khẩu cục bộ qua editor. Spring Boot nạp trực tiếp file này từ **working directory** bằng `spring.config.import=optional:file:.env[.properties]`; Maven/IDE không tự chuyển nó thành environment. Biến môi trường có ưu tiên cao hơn `.env`. Định dạng **Java properties UTF-8**: `KEY=value`, không `export`, không quote bao quanh giá trị; dấu `#` comment chỉ khi đầu dòng, không hỗ trợ inline comments. Backslash trong mật khẩu phải viết `\\`; khoảng trắng đầu giá trị phải escape `\ `. Có thể dùng biến môi trường với prompt ở trên để tránh quy tắc escaping. Không đưa mật khẩu vào command line hoặc commit `.env`.

## Build và kiểm tra migration

```powershell
.\mvnw.cmd clean verify
java -jar .\target\notification-service-0.0.1-SNAPSHOT.jar
```

`verify` chạy FoundationTest với PostgreSQL16 thật qua Testcontainers: migration mới/validate/chạy lại, unique hai bảng, status/read_at, 10 type, ACCOUNT, không FK, receipt tồn tại sau xóa nội dung, HTTP health và endpoint không được expose. Cần Docker Engine đang chạy, tải được image postgres:16-alpine; test dùng DB tạm và port ngẫu nhiên, không tác động localhost:5432. Không skip test khi Docker thiếu. Chạy jar cần cấu hình DB như trên; Flyway tự chạy trước khi ứng dụng sẵn sàng.

Không có cleanup receipt/nội dung, consumer, JWT/access-state hay 4 REST nghiệp vụ trong bản nền này. Không coi constraint test là kiểm chứng dedup consumer/transaction/offset.
