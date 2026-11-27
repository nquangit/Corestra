# Global retry: tự retry khi bị rate limit / WAF chặn

Tính năng này áp dụng cho **live traffic của Burp** (Proxy, Repeater, Intruder, Scanner). Khi response trông giống bị rate limit hoặc bị WAF chặn, Corestra chờ rồi gửi lại request gốc. Công cụ Burp nhận **response cuối cùng** thay cho response lỗi.

Mặc định tính năng **tắt**.

## 1. Bật và cấu hình

Vào **Project → Settings → Global retry → Configure…**

| Cấu hình | Mặc định | Ý nghĩa |
| --- | --- | --- |
| Retry rate-limited / blocked responses | Tắt | Bật/tắt toàn bộ |
| Tools | PROXY, REPEATER, INTRUDER | Công cụ được retry. SCANNER tắt mặc định (xem mục 4) |
| Only requests in Burp's target scope | Bật | Chỉ retry request nằm trong Target scope |
| Host regex | rỗng = mọi host | Regex phải khớp **toàn bộ** host, không phân biệt hoa thường |
| Methods | rỗng = mọi method | Ví dụ `GET, HEAD` nếu không muốn retry POST |
| Retry on status | `429, 503` | Status kích hoạt retry |
| …or response regex | rỗng | Regex trên toàn bộ response (status line + headers + dòng trống + body) |
| Max retries | 3 | Số lần gửi lại tối đa (1–10) |
| Base delay / Max delay / Multiplier | 1000 ms / 30000 ms / 2.0 | Backoff: 1s → 2s → 4s…, mỗi lần chờ không quá Max delay |
| Honor Retry-After | Bật | Dùng `Retry-After` (số giây hoặc HTTP-date) nếu server trả về |
| Max total wait per request | 60 s | Tổng thời gian chờ tối đa cho một request |
| Host cooldown | Bật | Khi host đang bị rate limit, các request khác tới host đó chờ trước khi gửi |

Ví dụ regex cho trang chặn của WAF:

```text
(?i)request blocked|access denied|cf-mitigated|captcha
```

## 2. Cách hoạt động

1. Request từ công cụ được chọn đi qua Corestra. Nếu host đang trong cooldown, request chờ (tối đa bằng Max total wait).
2. Khi response về và khớp điều kiện, Corestra chờ theo `Retry-After` hoặc backoff, rồi gửi lại đúng request đã gửi đi.
3. Lặp lại tới khi response không còn khớp, hết số lần retry, hoặc lần chờ tiếp theo vượt quá ngân sách thời gian.
4. Công cụ Burp nhận response cuối cùng. Phần **Notes** của response ghi lại kết quả, ví dụ:
   `Corestra retry: 2 resends, waited 3.0s, final status 200 (recovered)`.

Request gửi lại đi trực tiếp (không qua Proxy Pipeline) và là traffic của Corestra, nên Session Handler và Auto Trigger không xử lý lại. Trong Logger chúng hiện với tool là Extensions. Proxy/Repeater history vẫn hiện request gốc.

Global retry chạy **trước** Session Handler. Nếu sau khi retry response vẫn cho thấy mất session, Session Handler vẫn re-login và gửi lại như bình thường.

## 3. Gợi ý cấu hình

- **Intruder với rate limit chặt:** bật Host cooldown, giữ Honor Retry-After, tăng Max total wait lên 120–300 s. Nên giảm số thread trong resource pool của Intruder.
- **WAF trả 403 kèm trang chặn:** để status `429, 503` và thêm response regex cho trang chặn. Không nên thêm `403` vào status vì sẽ retry cả các lỗi phân quyền thật.
- **Chỉ một vài host:** tắt "Only requests in Burp's target scope" và dùng Host regex, ví dụ `api\.example\.com`.

## 4. Giới hạn

- **Không có response thì không retry được:** timeout, connection reset, WAF drop kết nối không đi tới handler response của Burp.
- **Scanner:** thời gian chờ retry cộng vào thời gian response mà Scanner đo, có thể gây báo nhầm ở các check dựa trên thời gian (blind SQLi kiểu sleep). Chỉ bật SCANNER khi chấp nhận rủi ro này.
- **Method không idempotent:** retry POST/PUT/DELETE khi gặp `5xx` có thể lặp lại tác dụng phụ (tạo bản ghi trùng). Với `429` thường an toàn vì server chưa xử lý request. Giới hạn Methods nếu cần.
- Trong lúc chờ, thread của công cụ Burp bị giữ lại (browser chờ với Proxy, thread Intruder chờ với Intruder). Đây là chủ ý, giống throttle.
- Khi unload extension, mọi request đang chờ được giải phóng ngay và nhận response hiện có.
