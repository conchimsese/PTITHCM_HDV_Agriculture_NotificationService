# Nền tảng Notification Service

Nguồn chuẩn: `BA - SOA.docx`, phần Notification Service Specification MVP và CONTRACT CHUNG MVP v2.1 (07/10/2026); đối chiếu `Prompt_Notification_Service.md`. Phạm vi lượt này theo yêu cầu trực tiếp: application, cấu hình, schema và health. Không consumer Kafka, API nghiệp vụ, cleanup hoặc JWT/access-state trong lượt này. Không sửa User Service.

## Đối chiếu và mâu thuẫn

- Notification §5.1, dòng `reference_type`, chỉ liệt kê OFFER/CONVERSATION/CONTRACT/DEPOSIT. Notification §10 và Contract v2.1 §9/§14 quy định ba event User dùng ACCOUNT. Đề xuất sửa bảng §5.1 bổ sung ACCOUNT; migration theo contract hiện tại hỗ trợ ACCOUNT và đủ 10 type.
- Notification §8, bảng subscription, chỉ có 7 event; §3/§9/§26 và Contract v2.1 §9/§14 bổ sung 3 User event. Đề xuất cập nhật bảng thành 10; schema không giới hạn theo bảng cũ.
- Notification §25 còn viết receipt hoặc alternative horizon cần duyệt, trong khi §4/§5.2/§11 và Contract v2.1 §10 đã duyệt notification_receipts. Đề xuất sửa câu DoD theo quyết định hiện tại; tạo receipts không FK và không xóa theo nội dung.
- BA User phần “Stack triển khai hiện tại” ghi Java21/Boot3.5.x/Maven; Prompt §1 áp dụng hướng này cho repo Notification trống. Không có patch Boot hoặc phiên bản Maven chính xác được nhóm chốt. Chọn kỹ thuật Boot 3.5.16, Maven 3.9.11, Wrapper 3.3.4; không coi đây là quyết định phiên bản chính xác của nhóm. Dependencies do Boot BOM quản lý.

Tham chiếu kỹ thuật: https://docs.spring.io/spring-boot/3.5/system-requirements.html (Java21 và Maven3.6.3+ tương thích); https://maven.apache.org/tools/wrapper/ (wrapper Windows only-script).

## Cấu hình và giới hạn

- API port cố định 8086. DB_URL/DB_USERNAME/DB_PASSWORD bắt buộc, không mật khẩu mặc định. `.env.example` nêu JDBC localhost:5432/notification_db và user notification_app.
- Spring Config Data nạp `optional:file:.env[.properties]` từ working directory. Đây là Java properties, không phải dotenv shell; biến môi trường ưu tiên hơn file. Không tự ghi/in các secret. README mô tả escaping.
- Flyway chạy khi ứng dụng khởi động; clean disabled, không baseline tự động. Hibernate ddl-auto=validate, không tạo/update schema. Chưa có entity nên kiểm schema chính là Flyway và integration test PostgreSQL.
- Chỉ expose `/actuator/health`, không details/components/config. Health UP gồm trạng thái DB. Chưa có endpoint nghiệp vụ cần JWT.
- Index/unique/check theo Notification §5.1–5.2. Không thêm business dedup hoặc ràng buộc mapping source/type/reference vượt schema đã chốt. Receipt PK hỗ trợ tra event_id; receipt+content transaction/hash/replay cần thực hiện ở lượt consumer.
- Không có AGENTS.md trong thư mục hiện tại hoặc các thư mục cha; source ban đầu chỉ có hai file docs, chưa có Git repository. `xulycontract(1).txt` được BA nhắc tới nhưng không được cung cấp riêng; không giả định đã đọc file đó, dùng nội dung baseline đã tích hợp trong BA.

## Kết quả kiểm tra 12/10/2026

- Windows: `mvnw.cmd -version` xác nhận Maven3.9.11, Java21.0.12.1.
- `mvnw.cmd -B -ntp clean verify`: BUILD SUCCESS; 3 tests, 0 failure/error/skipped. Tạo executable jar trong target.
- Docker/Testcontainers: PostgreSQL16.15 tạm với database notification_db/user notification_app; Flyway apply V1, validate và migrate lại không chạy thêm migration. Constraints và health HTTP được kiểm tra bằng FoundationTest.
- PostgreSQL localhost:5432 có mở port nhưng chưa kiểm tra xác thực/migration trên DB local vì không có credential. Không tạo/sửa role hoặc database trên instance đó.
- Health được kiểm tra trên port ngẫu nhiên của integration test; chưa chạy jar trên 8086 hoặc kiểm tra `.env` với secret thực. Cổng8086 và cơ chế import `.env` được cấu hình trong application.yml. Build/test không cần `.env`.
- Chưa kiểm tra Kafka/User/Gateway và REST nghiệp vụ, nằm ngoài phạm vi lượt này.
