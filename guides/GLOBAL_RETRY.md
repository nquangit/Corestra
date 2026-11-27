# Global Retry: profile, rule và điều kiện AND/OR

Tab **Global Retry** áp dụng cho **live traffic của Burp** (Proxy, Repeater, Intruder, Scanner). Với mỗi response, Corestra quyết định: **retry** (chờ rồi gửi lại), **stop** (giữ nguyên và đánh dấu), hoặc không làm gì. Công cụ Burp nhận **response cuối cùng**.

Mặc định tính năng **tắt**. Tick **Enable global retry** rồi nhấn **Save**. **Revert** bỏ thay đổi chưa lưu, **Load defaults** nạp cấu hình mặc định vào form (cần Save để giữ).

## 1. Ba tầng cấu hình

```text
Profile (chọn theo request)          ← thử từ trên xuống, profile đầu tiên khớp được dùng
 └─ Rule (quyết định theo response)  ← thử từ trên xuống, rule đầu tiên khớp quyết định
     └─ Điều kiện (group)            ← cùng group = AND, khác group = OR
```

### Profile

Mỗi profile gồm:

| Phần | Ý nghĩa |
| --- | --- |
| Tools | PROXY, REPEATER, INTRUDER, SCANNER |
| Only requests in Burp's target scope | Chỉ request trong Target scope |
| Host regex | Khớp **toàn bộ** host, không phân biệt hoa thường. Rỗng = mọi host |
| Path regex | Tìm trong path (kể cả query). Rỗng = mọi path |
| Methods | Ví dụ `GET, HEAD`. Rỗng = mọi method |
| Max retries, Base delay, Max delay, Multiplier | Backoff: `base × multiplier^lần` (giới hạn bởi Max delay) |
| Honor Retry-After | Dùng `Retry-After` của server khi rule không đặt delay cố định |
| Max total wait per request | Tổng thời gian chờ tối đa cho một request |
| Host cooldown | Khi host đang bị retry, các request khác tới host đó chờ trước khi gửi |

Ví dụ: cùng `api.example.com` nhưng `/login` cần rule khác `/api/v2` → tạo hai profile, profile cụ thể hơn đặt **trên**. Một profile "Default" không lọc host đặt cuối cùng.

### Rule

Mỗi rule có:

- **Action**: `RETRY` (chờ, gửi lại, rồi kiểm tra response mới với các rule) hoặc `STOP` (giao nguyên response, không retry nữa).
- **Fixed delay ms**: nếu điền, dùng delay cố định thay cho Retry-After/backoff.
- **Max retries**: nếu điền, giới hạn số lần retry khi rule này khớp.
- **Highlight**: tô màu response trong Burp khi response cuối cùng khớp rule này.

Response mới sau mỗi lần retry cũng được kiểm tra lại. Ví dụ 429 → retry → lần sau trả trang chặn WAF → rule STOP của WAF khớp → dừng và tô đỏ.

### Điều kiện

| Cột | Ý nghĩa |
| --- | --- |
| On | Bật/tắt điều kiện |
| Group | Cùng số group = **AND**; khác group = **OR** |
| Source | status, header, headers (status line + headers), body, response (toàn bộ), json, body.length, method, path, request.header |
| Header / JSON path | Tên header, hoặc JSON path như `$.error.code`, `items[*].id` |
| Operator | EQUALS, NOT_EQUALS, CONTAINS, NOT_CONTAINS, MATCHES_REGEX, IN (danh sách cách nhau dấu phẩy), NUMERIC_GT/GTE/LT/LTE, EXISTS, NOT_EXISTS |
| Case | Phân biệt hoa thường (mặc định không) |
| Not | Đảo kết quả điều kiện |

Biểu thức tổng hợp hiển thị ngay dưới bảng, ví dụ:

```text
(status = "403" AND header[Server] contains "cloudflare")
OR (status = "403" AND json[error.code] = "FORBIDDEN")
```

## 2. Phân biệt các loại 403

Đặt rule theo thứ tự từ cụ thể tới chung:

1. **Cloudflare challenge (stop)**: `cf-mitigated: challenge`, hoặc 403/503 + `Server: cloudflare` + body "Just a moment". Gửi lại không vượt được challenge.
2. **Cloudflare WAF block (stop)**: 403 + `Server: cloudflare` + body "Attention Required" / error 1020.
3. **Rate limited (429/503) (retry)**.
4. **Application 403 (stop)**: mọi 403 còn lại là từ chối thật của ứng dụng, không retry.

Nếu ứng dụng trả 403 kiểu JSON có thể retry (ví dụ `{"error":{"retryable":true}}`), thêm rule RETRY với điều kiện `json[error.retryable] = "true"` đặt **trên** rule "Application 403".

## 3. Preset

Chọn preset trong danh sách cạnh nút **Add preset**. Rule mới được chèn ngay dưới rule đang chọn.

| Preset | Action |
| --- | --- |
| Rate limited (429/503) | RETRY |
| Retry-After header present | RETRY |
| Cloudflare rate limit (1015) | RETRY, delay cố định 10 s |
| Cloudflare challenge | STOP, cam |
| Cloudflare WAF block | STOP, đỏ |
| AWS WAF / CloudFront block | STOP, đỏ |
| Akamai block | STOP, đỏ |
| Imperva / Incapsula block | STOP, đỏ |
| F5 BIG-IP ASM block | STOP, đỏ |
| Gateway error (502/504) | RETRY |
| Application 403 | STOP |

Chữ ký WAF thay đổi theo từng triển khai. Luôn kiểm tra preset với response thật trong tab **Test**.

## 4. Tab Test

Dán một raw response (status line, headers, dòng trống, body), điền tool/method/host/path, rồi nhấn **Evaluate**. Kết quả cho biết profile nào được chọn, rule nào khớp, vì sao, và lần chờ đầu tiên là bao lâu. Test dùng nội dung form hiện tại (kể cả chưa Save) và **không gửi request nào**.

## 5. Kết quả trên Burp

Phần **Notes** của response ghi lại kết quả, ví dụ:

```text
Corestra retry [API]: 2 resends (rule 'Rate limited'), waited 3.0s, final status 200 - recovered
Corestra retry [API]: rule 'Cloudflare WAF block' - stop, not retried
```

Request gửi lại đi trực tiếp (không qua Proxy Pipeline) và là traffic của Corestra, nên Session Handler và Auto Trigger không xử lý lại. Trong Logger chúng hiện với tool là Extensions. Global Retry chạy **trước** Session Handler, nên nếu sau khi retry vẫn mất session thì re-login vẫn chạy như bình thường.

Project lưu bằng định dạng cũ (một policy duy nhất) được tự chuyển thành một profile "Default".

## 6. Giới hạn

- **Không có response thì không retry được**: timeout, connection reset, WAF drop kết nối không đi tới handler response của Burp.
- **Scanner**: thời gian chờ retry cộng vào thời gian response mà Scanner đo, có thể gây báo nhầm ở check dựa trên thời gian. Chỉ bật SCANNER khi chấp nhận rủi ro này.
- **Method không idempotent**: retry POST/PUT/DELETE khi gặp `5xx` có thể lặp lại tác dụng phụ. Với `429` thường an toàn. Dùng profile riêng hoặc giới hạn Methods.
- Trong lúc chờ, thread của công cụ Burp bị giữ lại. Khi unload extension, mọi request đang chờ được giải phóng ngay.
