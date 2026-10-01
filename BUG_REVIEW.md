# Corestra — rà soát lỗi, cập nhật ngày 2026-09-30

Phạm vi: đọc các đường chạy chính từ bootstrap đến session, auto-trigger, flow engine,
Access Testing, HTTP mutation và persistence; đối chiếu tests và thêm regression tests.
Đây là rà soát mã nguồn và unit tests, chưa phải kiểm thử tích hợp toàn bộ trong Burp.
Không thể suy ra “không còn bug” từ kết quả test suite.

## Đã sửa trong đợt rà soát

| Vấn đề | Ảnh hưởng trước sửa | Cách sửa / kiểm chứng |
| --- | --- | --- |
| Auto-trigger dùng tool/host/method/path làm khóa | Request đồng thời cùng API ghi đè context; response có thể nhận sai prerequisite hoặc mất context | Dùng Montoya `messageId()`; test hai request cùng endpoint, response về ngược thứ tự |
| Auto-trigger tìm lại rule khi response về | Sửa flow/request trong lúc chờ làm mất hoặc đổi flow đã match; response chưa từng trigger vẫn bị aggregate | Dùng snapshot đã lưu cho đúng message; response không có pending context được giữ nguyên |
| Aggregation bỏ qua cấu hình bật/tắt và target | Body response bị thay đổi khi aggregation tắt hoặc target không phải step hiện tại | Kiểm tra `enabled`, `useTriggeredStep` và `finalStepId` |
| Prerequisite thất bại vẫn để lại automation | Tiếp tục injection/response handling dù prerequisite đã fail | Tôn trọng kết quả flow; chỉ lưu pending sau khi chuẩn bị request thành công; giữ final context của engine |
| Response cũ ghi lại nguyên SessionProfile | Mất biến của response khác, quay về TTL/config cũ, hoặc tạo lại profile đã xóa | Cập nhật riêng state bằng thao tác nguyên tử trên repository; tests bảo toàn cấu hình/biến mới và không hồi sinh profile |
| Scheduler không kiểm tra lại sau khi chờ auth lock | Login do traffic vừa xong thì scheduler login lại ngay | Kiểm tra đến hạn trong cùng lock; test điều khiển thứ tự hai thread tái hiện 3 lần gửi thay vì 2 trước sửa |
| Session authentication chạy flow đã disable | Tắt flow vẫn có thể gửi request login | Bỏ qua auth flow đã disable; test số request gửi bằng 0 |
| Access Plan không giải phóng `running` khi khởi tạo/listener lỗi | Các lần chạy tiếp theo luôn báo plan đang chạy | Đưa toàn bộ phần sau khi lấy quyền chạy vào `try/finally`; test listener lỗi rồi chạy lại |
| Executor của Race Testing không đóng lúc unload | Worker/thread tồn tại sau khi unload extension | `RaceRunner` có `close()` và được đăng ký với `ServiceRegistry`; test executor shutdown |
| Safe export làm mất condition/action của flow target | Import lại làm thay đổi điều khiển flow, ví dụ mất BREAK_FLOW | Bảo toàn `conditionActionRules`; round-trip test cả SANITIZED_EXPORT và EXCLUDE_SECRETS |
| Access Testing dùng transport có Session Handler toàn cục | Profile session cùng scope có thể ghi đè token/cookie của actor đã chọn, làm request thực gửi khác evidence | Tách `accessFlowEngine` và `ActorIsolatedHttpClient` trên transport routing chưa decorate; dùng nhất quán cho auth, setup, target, race và teardown; test request actor được chuyển nguyên vẹn |
| Safe export còn để lọt secret trong flow/rule/override/assertion | File chia sẻ có thể chứa token hardcode, request/captured response hoặc expected value nhạy cảm | Dùng chung `FlowExportSanitizer` cho Flowise và Access snapshots; sanitize auth/session injection, flow overrides và assertions; bỏ captured response ở hai safe mode; fixture kiểm tra từng vị trí và giữ nguyên FULL_EXPORT |
| Ba HTTP integration path dựng lại raw HTTP/1.1 | Có thể đổi virtual Host/HTTP version, làm hỏng binary body không đổi hoặc giữ Content-Length cũ sau mutation | Áp kết quả mutation lên request gốc bằng immutable Montoya API; chỉ gọi `withBody` khi body đổi và để Montoya tính framing; test HTTP/2, custom Host, Content-Length và no-op body |
| Pending auto-trigger và Race worker thiếu giới hạn vòng đời | Pending map/common pool có thể sống mãi; một HTTP worker treo làm coordinator chờ vô hạn | Pending có TTL/capacity, Phase 3 dùng executor riêng và đóng khi unload; Race có completion deadline, cancel worker đang chờ/chạy và shutdown idempotent; test expiry/cap/close và HTTP worker treo |
| Proxy Pipeline tạo CONNECT thiếu port, đóng tunnel, tự xử lý lại CONNECT và phụ thuộc EOF | HTTPS mặc định tạo authority sai dạng `host` thay vì `host:443`, có thể bị abort khi bắt đầu TLS; Session Handler/auto-trigger có thể xử lý CONNECT của chính extension; Windows có thể báo socket abort sau khi response đã về | CONNECT luôn dùng authority `host:port`, tunnel keep-alive và recursion marker; response dừng theo Content-Length/chunked framing và chấp nhận reset thay EOF cho close-delimited response; lỗi hiện ghi rõ proxy listener và target |

Bản sửa keep-alive ở lượt trước vẫn được giữ: login không extract biến vẫn ghi nhận
thời điểm xác thực/TTL; flow thực thi thất bại hoặc không chạy step không được ghi nhận
là một lần xác thực thành công.

## Trạng thái các mục từng để mở

Bốn mục từng để mở trong bản rà soát ngày 2026-09-16 đã được xử lý trong mã nguồn và có
regression test. Phần còn cần làm thủ công là smoke-test trong Burp thật để xác nhận hành vi
của Montoya/Burp trên đường truyền thực, đặc biệt với HTTP/2 và payload nhị phân.

## Giới hạn kiểm chứng và kiểm tra thực tế

- Tests dùng fake transport/proxy Montoya; không gửi traffic đến hệ thống của người dùng.
- Test callback auto-trigger cài factory giả trong phạm vi test và khôi phục sau test.
- Đã kiểm tra API `messageId()` từ Javadocs/JAR Montoya 2026.4 trong workspace: request
  và response tương ứng dùng cùng ID.
- Cần smoke-test JAR trong Burp: keep-alive 180 giây; scan song song cùng endpoint;
  aggregation bật/tắt; login flow disable; hai actor trùng scope Session Handler; virtual
  host/HTTP/2/binary body; request race bị treo; unload/reload trong lúc auto-trigger/race chạy.
- HTTP 401/429 tự nó chưa đồng nghĩa `ExecutionResult.success() == false`: flow engine
  phân biệt lỗi thực thi với kết quả nghiệp vụ HTTP. Cấu hình condition/action phải phản
  ánh tiêu chí login thất bại; đây cũng là điểm cần kiểm tra khi ứng dụng có rate limit.

Chính sách retry sau auth flow thất bại vẫn chưa có backoff; bản sửa scheduler tránh
chạy trùng sau một lần thành công, không bổ sung chính sách retry mới.

## Kết quả verification

- `mvn -o -q package`: thành công sau các thay đổi cuối cùng.
- 576 tests: 0 failures, 0 errors, 1 skipped (môi trường headless).
- `git diff --check`: sạch.
- JAR: `target/corestra-0.1.0-SNAPSHOT-all.jar`.
- So với mốc verification 551 tests: thêm 21 test cases cho các mục đã xử lý tiếp theo.
