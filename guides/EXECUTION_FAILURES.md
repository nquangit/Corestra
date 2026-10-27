# Lỗi kết nối và retry trong flow

Condition/action được đánh giá sau mỗi lần engine thực thi một bước. Lỗi timeout,
socket reset/abort, DNS hoặc không nhận được response là **lỗi thực thi**, khác với
HTTP 403/429/500: khi có HTTP response hợp lệ, bản thân việc gửi vẫn thành công.

## 1. Chọn điều kiện phù hợp

Mở request trong Flowise hoặc Manual Testing, mở phần Condition/Actions của bước đó.

| Source | Operator / Expected mẫu | Ý nghĩa |
| --- | --- | --- |
| `EXECUTION_FAILURE` | `EQUALS` / `true` | Bước thực thi thất bại; không tự khớp HTTP 403/429 |
| `FAILURE_TYPE` | `EQUALS` / `TIMEOUT` | Chỉ retry khi transport cung cấp lỗi timeout có cấu trúc |
| `FAILURE_TYPE` | `IN` / `TIMEOUT,CONNECTION_ERROR,CONNECTION_REFUSED,NO_RESPONSE,TRANSPORT_ERROR` | Một số lỗi gửi/kết nối thường gặp |
| `ERROR_MESSAGE` | `CONTAINS` hoặc `MATCHES_REGEX` | Kiểm tra thông báo lỗi; phụ thuộc nội dung/ngôn ngữ của transport |
| `RESPONSE_STATUS` | `NOT_EXISTS` | Không có HTTP status; không phân biệt riêng loại lỗi thực thi |

`RESPONSE_STATUS EQUALS 0` không khớp: status nội bộ 0 được xem là không có response.
`EXECUTION_FAILURE` dùng `EQUALS true`, không dùng `EXISTS` vì giá trị boolean luôn có
(có thể là `false`). Các điều kiện kiểu `FAILURE_TYPE`/`ERROR_MESSAGE` không cần Selector.

Để xử lý cả 403/429 **hoặc** lỗi thực thi, tạo hai condition:

1. `RESPONSE_STATUS / IN / STATIC / 403,429`.
2. `FAILURE_TYPE / IN / STATIC / TIMEOUT,CONNECTION_ERROR,CONNECTION_REFUSED,NO_RESPONSE,TRANSPORT_ERROR`.

Chọn **Match = ANY**. Nếu muốn bắt cả lỗi thực thi chưa được phân loại, có thể dùng
`EXECUTION_FAILURE / EQUALS / STATIC / true` cho condition thứ hai. Không dùng ALL
cho phép OR này.

## 2. Sleep và retry

Ví dụ actions theo thứ tự:

1. **Sleep**, Parameter `10`: chờ 10 giây.
2. **Retry current step**, Parameter `3`: tối đa 3 lần gửi lại, ngoài lần đầu.

Trong Manual Testing, **Run selected** không chạy Conditions & Actions theo hành vi
single-step hiện có. Dùng **Run from here** hoặc **Run all** để thử vòng sleep/retry.

Nếu cần chạy lại các bước chuẩn bị từ đầu, chọn **Retry flow from start**. Trong Auto
Trigger, phần engine đang chạy là prerequisite hoặc downstream; restart phần này
không tự gửi lại request live của Scanner/extension khác.

Giới hạn retry vẫn là 0–10, mặc định 1 nếu chưa khai báo. Hết giới hạn thì flow thất
bại; log nêu HTTP status cuối hoặc loại lỗi thực thi cuối. Sleep không có nghĩa là
server chắc chắn phục hồi. Request timeout/reset có thể đã được server xử lý; với
bước tạo OTP/giao dịch, chọn chính sách retry theo hành vi thực tế của endpoint.

Lỗi thiếu input bắt buộc, huỷ/interrupted và session binding không hợp lệ vẫn dừng
flow trước condition/action, kể cả có rule `EXECUTION_FAILURE = true`. Không dùng
retry để bỏ qua các kiểm tra này.

## 3. Danh sách FAILURE_TYPE

| Giá trị | Ý nghĩa |
| --- | --- |
| `NONE` | Bước hoàn tất; kể cả response là HTTP 403/429 |
| `NO_RESPONSE` | Transport không cung cấp HTTP response hợp lệ |
| `TIMEOUT` | Cause là socket/HTTP timeout |
| `CONNECTION_REFUSED` | Cause là ConnectException |
| `CONNECTION_ERROR` | Lỗi socket, có thể là reset/abort; không đoán riêng từ thông báo theo ngôn ngữ OS |
| `DNS_ERROR` | Không phân giải được hostname |
| `TLS_ERROR` | Có lỗi TLS trong cause chain |
| `TRANSPORT_ERROR` | Gửi thất bại, không có cause cụ thể hơn |
| `API_UNAVAILABLE` | HTTP API/nguồn gửi chưa sẵn sàng hoặc bị giới hạn |
| `REQUEST_PREPARATION` | Không dựng/chuẩn bị được request trước khi gửi |
| `INPUT_ERROR` | Thiếu/sai input bắt buộc hoặc template |
| `SESSION_BINDING` | Session đã chọn không còn dùng được hoặc sai destination |
| `CANCELLED` | Bị huỷ/interrupted/unload |
| `UNKNOWN` | Record lỗi cũ chưa có category |

Standard và Proxy Pipeline đều giữ cause khi có thông tin. Nếu Burp chỉ cung cấp một
lỗi chung hoặc không có response, extension không đoán `TIMEOUT`/`CONNECTION_ERROR`
từ chuỗi thông báo; kiểm tra `TRANSPORT_ERROR`/`NO_RESPONSE` trong trường hợp đó.
Record cũ vẫn đọc được; category không được suy diễn lại từ thông báo cũ.

## 4. Request nào trong Auto Trigger được áp dụng?

| Vị trí | Hành vi |
| --- | --- |
| Prerequisite | Engine bắt lỗi gửi và áp condition/action của chính bước đó; target chờ |
| Target live của Scanner hoặc extension khác | Bên gọi/Burp trực tiếp gửi; Corestra chưa có vòng retry lỗi kết nối cho request này |
| Downstream | Engine có thể retry; target live trước đó không bị gửi lại |

Target không có response thì Corestra không giả lập response thành công để chạy tiếp
downstream. Bật nguồn **Extensions / Extender** không thay đổi giới hạn này. Khi
prerequisite thất bại, request live có thể bị dừng và Burp có thể báo request aborted.

Xem [Auto Trigger: profiles, nguồn Extensions và cách ly session](AUTO_TRIGGER_INPUTS.md).
