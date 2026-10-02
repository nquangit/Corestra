# Giữ session sống bằng Keep-alive Flow

Keep-alive là flow tùy chọn của **Auth Provider**, dùng session của từng **Auth Profile**
để duy trì phiên hiện tại. Login và Refresh Flow tiếp tục giữ trách nhiệm xác thực cũ.
Không cần sao chép login/keep-alive flow cho mỗi người dùng.

## 1. Tạo flow trong Flowise

1. Capture request đã kiểm chứng giữ session sống, tạo flow `Session - Keep alive`.
2. Loại bỏ credential/token/cookie hardcode của request đã capture, hoặc cấu hình
   Inject Rule `OVERWRITE` cho từng auth field.
3. Trong step, chọn Source **Variable** và dùng key từ state của actor:

| Target | Variable key minh họa |
| --- | --- |
| Cookie `JSESSIONID` | `auth.JSESSIONID_COOKIE` |
| Cookie `token` | `auth.token` |
| Query hoặc form `dse_sessionId`, nếu endpoint yêu cầu | `auth.JSESSIONID_URL` |

Tên variable key phải khớp extractor của Login Flow. Login export key không cần
prefix, ví dụ `token`; Auth runtime cung cấp key đó dưới dạng `auth.token` cho flow.
Header/cookie trong Auth Profile có thể dùng `auth.header.Header-Name` hoặc
`auth.cookie.CookieName`. Các input `credentials.*` cũng thuộc actor đang chạy.

**Provider Injection Rules không tự áp vào các step của Keep-alive Flow.** Chúng
áp vào target/live traffic. Step keep-alive phải có injection của chính nó; không
dựa vào Session Handler toàn cục vì auth transport cách ly actor.

Nếu server không đổi token/cookie, flow không cần extractor. Nếu server xoay một
giá trị, extract giá trị mới vào đúng key đã dùng. Tránh extract giá trị rỗng hoặc
cookie của trang chưa đăng nhập rồi ghi đè state đang có.

Kiểm chứng endpoint giữ nguyên session và không phá processor/form đang mở.
Một GET điều hướng có thể thay đổi ngữ cảnh ứng dụng dù cookie không đổi.

## 2. Cấu hình Auth Provider

Mở **Authentication > Auth Providers > Edit**:

- **Login Flow**, **Refresh Flow**, **Validate Flow**, Fallback Policy: giữ cấu hình cũ.
- **Keep-alive Flow (optional)**: chọn flow vừa tạo. Chọn **None** để tắt.
- **Auth State TTL**: đặt một khoảng tái sử dụng state phù hợp, dài hơn interval
  scheduler. Ví dụ interval 60 giây, TTL 180 giây nếu đã kiểm chứng với ứng dụng.

Trong tab **Keep-alive** có hai bảng đặt cạnh nhau:

- **Keep-alive Success**: dấu hiệu chứng minh session còn sống. Phải có ít nhất một
  rule bật; bất kỳ rule nào khớp thì thành công, với response 2xx/3xx và flow chạy thành công.
- **Session Expired**: dấu hiệu rõ ràng session đã hết hạn. Bất kỳ rule nào khớp thì
  cho phép chạy lại chính sách xác thực cũ; bảng này được xét trước Success.

Hai bảng hoàn toàn độc lập với tab Success/Failure của Login/Refresh/Validate.

Ví dụ: keep-alive response có một đoạn HTML chỉ xuất hiện khi đã đăng nhập thì
chọn `BODY_CONTAINS` đoạn đó. Nếu endpoint trả 200 cho cả trang login, không chỉ
dùng `STATUS_CODE=200`. Có thể dùng `STATUS_CODE=401` hoặc dấu hiệu trang login
cho Session Expired. Chỉ dùng `STATUS_CODE=302` nếu đã kiểm chứng endpoint đó
không dùng 302 cho các redirect hợp lệ khác.

Không có Session Expired rule thì lỗi keep-alive **không tự chạy login**. Cấu hình
đó được phép lưu và sẽ được cảnh báo trong Flow Contract.

## 3. Cấu hình Session Handler

1. Chọn **Linked Auth Profile**, liên kết actor muốn giữ sống.
2. Bật **Scheduler**, đặt interval nhỏ hơn timeout phía server đã kiểm chứng.
3. Bấm **Maintain Session Now** để kiểm tra ngay; xem thông báo kết quả và state.
4. **Run Auth Now** vẫn cố ý chạy chính sách xác thực cũ; nó không ưu tiên keep-alive.

Hành vi runtime:

| Trạng thái | Xử lý |
| --- | --- |
| Chưa có auth state | Chạy chính sách Login/Refresh/Validate đã cấu hình. |
| Đến lịch, đã có state | Chạy Keep-alive Flow bằng state của actor, kể cả khi TTL cục bộ đã hết. |
| Keep-alive thành công | Giữ giá trị auth cũ, merge extractor mới nếu có, cập nhật timestamp và expiry. Không login lại. |
| Response khớp Session Expired | Chạy chính sách xác thực cũ. Nếu session thay đổi, form/processor cũ vẫn cần tái tạo. |
| Timeout, lỗi mạng hoặc response không kết luận được | Giữ state/TTL, backoff rồi thử lại. Không tự login. |
| Authentication thất bại khi chưa có session hoặc sau expiry đã xác nhận | Báo thất bại và backoff; không báo actor đã được xác thực. |

Backoff dùng chung theo Auth Profile: 30, 60, 120, 240 giây, tối đa 5 phút; không
chặn worker bằng sleep. Một scheduler tick chỉ retry khi đến hạn backoff và profile
đang cần maintenance. Keep-alive thành công xóa backoff. Backoff là runtime state,
không lưu vào project và không còn sau reload extension.

Khi TTL cục bộ hết mà có traffic, Auth runtime cũng thử keep-alive trước khi thay
session. TTL chỉ là thời hạn tin cậy của cache; hết TTL không chứng minh server đã
hết session. Khi Scheduler tắt, không có keep-alive tự chạy lúc idle.

Nếu flow extract expiry mới thì expiry đó được ưu tiên. Nếu không, TTL của provider
được tính lại từ lần keep-alive thành công. Keep-alive không extract gì vẫn gia hạn
TTL và giữ nguyên token/cookie.

## 4. Dùng cho nhiều actor

Một provider dùng chung hai flow; Alice, Bob và Admin có Auth Profile riêng,
credentials riêng và state riêng. Mỗi actor cần scheduler có một Session Profile
liên kết. Request keep-alive được render từ đúng actor, không lấy cookie của profile
đang được chọn trong Proxy.

Các binding cùng actor dùng chung timestamp auth để tránh gửi keep-alive lặp trong
cùng khoảng lịch. Backoff của Alice không chặn maintenance của Bob; scheduler hiện
chạy tuần tự nên một flow chậm vẫn có thể làm actor sau chạy muộn.

Để inject live traffic đúng actor, scope cần phân biệt các Session Profile. Nếu
nhiều profile cùng match, Session Handler chọn profile khớp đầu tiên. Access Testing
chọn actor theo row và vẫn cách ly state giữa các actor.

## 5. Tương thích và giới hạn

Project cũ hoặc provider không chọn Keep-alive Flow giữ hành vi scheduler cũ.
Export/import giữ flow ID và hai bộ rule, gồm sanitization ở các chế độ safe export.

Keep-alive thành công mà không đổi auth values không đổi authentication generation,
nên response đang bay vẫn có thể extract như bình thường. Khi re-authentication hoặc
extractor thực sự thay auth values, response của generation cũ bị bỏ qua.

Keep-alive duy trì session, không tự sửa form/processor của một session đã bị thay.
Xem [SESSION_RENEWAL.md](SESSION_RENEWAL.md) cho trường hợp đó.
