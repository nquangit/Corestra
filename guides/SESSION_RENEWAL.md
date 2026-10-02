# Session mới nhưng request vẫn trả về trang đăng nhập

Session Handler làm mới authentication và inject những field đã được cấu hình. Nó
không tự tái tạo trang, form hoặc transaction context của ứng dụng sau một lần login.
Một request có cookie mới vẫn có thể chứa hidden field thuộc phiên trước.

## Đối chiếu evidence ngày 2026-10-02

Đã kiểm tra offline 14 request/response trong `tmp/evidence.xml`. File được export
theo thứ tự mới nhất trước; bảng dưới trình bày các mốc theo thứ tự thời gian.
Không sao chép credential, token, cookie value hoặc dữ liệu tài khoản vào tài liệu này.

| Thời điểm GMT+07:00 | Request/response | Nhận xét |
| --- | --- | --- |
| 13:36:08 | GET trang chức năng, HTTP 200 | Response chứa form với `dse_pageId=5`, `dse_processorState=tattoanPage` và một `dse_processorId`. Form thuộc session trước renew. |
| 13:36:32–13:36:33 | Một chuỗi login trước đó | POST trả 200 nhưng HTML vẫn là trang login/captcha, không có cookie `token` mới. HTTP 200 riêng lẻ không chứng minh login thành công. |
| 13:36:34–13:36:35 | Chuỗi login tiếp theo | POST trả 200, HTML `ACB Online`, có cookie `token` mới và các link điều hướng mang `dse_pageId=2`. |
| 13:36:36 | POST action đang chờ, HTTP 302 | `JSESSIONID` khớp session của chuỗi login thành công; `token` khớp cookie trong response login; `dse_sessionId` trong body khớp session mới. Nhưng page ID, processor state và processor ID vẫn lấy từ form lúc 13:36:08. |
| 13:36:37–13:36:39 | GET theo redirect, tiếp tục HTTP 302 | Các request luân phiên giữa endpoint mở login và trang chưa login. Request tiếp tục mang cặp cookie auth đã inject; các response mở login có cấp `JSESSIONID` khác. |

`dse_processorId` trong POST action xuất hiện đúng một lần trong response trang chức
năng lúc 13:36:08, và không xuất hiện trong response login thành công lúc 13:36:35.
Referer của action cũng còn chứa session ID của trang trước renew.

Đây là bằng chứng request đang trộn authentication mới với form context cũ. Việc
backend kiểm tra processor ID theo session/page, hay một điều kiện khác, cần kiểm
chứng thêm; evidence không chứa code hoặc log phía server. Cookie `TS*` cũng khác
giữa hai chuỗi, nhưng hiện không có bằng chứng đủ để quy lỗi cho chúng và người dùng
xác nhận chúng không cần được quản lý trong cấu hình này.

## Kiểm chứng giả thuyết form context

1. Sau khi renew xong, mở lại trang chức năng bằng cùng session mới.
2. Lấy toàn bộ hidden field từ form vừa trả về, gồm `dse_sessionId`, `dse_pageId`,
   `dse_processorState`, `dse_processorId` và các field khác ứng dụng yêu cầu.
3. Dùng request kiểm tra phù hợp với các field mới và cùng cặp cookie của session đó.
4. Đối chiếu với request giữ processor ID cũ nhưng dùng cookie mới. Nếu trường hợp
   context mới chạy được, đó là bằng chứng mạnh hơn về ràng buộc form với session.

Tài liệu này chưa chứng minh bước 3 thành công trên server thực. Không thực hiện
request mạng nào trong quá trình đọc evidence.

## Cấu hình flow cho ứng dụng có form context

Giữ hai trách nhiệm riêng để login flow vẫn dùng chung cho nhiều chức năng:

```text
Auth Provider: login/refresh → cookie, token, session ID, đầu vào điều hướng
Setup flow: mở trang chức năng bằng session hiện tại → hidden field mới
Target: inject hidden field từ setup cùng session → gửi action
```

Với **Access Testing**, dùng setup flow của row hoặc prefix trước target step trong
flow target. Setup sử dụng đúng Auth Profile của target; các giá trị form thuộc
`scenario.*`, thay vì dùng một processor ID chung trong auth state. Dùng scenario
mới khi cần; không tái sử dụng scenario lấy từ authentication trước renew.

Với **Proxy/Scanner**, có thể dùng auto-trigger trên step action:

1. Các step trước action mở lại trang cần kiểm tra, không login lại.
2. Extract hidden field từ response của trang mới. Với regex, chọn Response/Body
   và capture group 1; dùng **Test regex** để kiểm tra đúng giá trị trong form.
3. Step action inject từng hidden field qua `REQUEST_FORM_FIELD`, chiến lược
   `OVERWRITE`, lấy biến do các prerequisite vừa extract.
4. Giới hạn trigger theo host, path, tool và dấu hiệu action trong body. Nhiều
   operation dùng chung một endpoint nên chỉ match path là chưa đủ.
5. Kiểm tra các request setup thực sự dùng session mới và bắt đầu điều hướng từ
   context hiện tại. Request GET đã capture từ trang cũ có thể chứa page ID cũ;
   chỉ thay `dse_sessionId` trong URL vẫn chưa đủ.

Auto-trigger hiện khởi tạo execution context rỗng; nó không tự seed các biến
`auth.*` của linked profile. Session-aware transport có thể inject các rule đã
cấu hình vào request setup, nhưng không tự cung cấp page/processor context.
Không đặt `{{auth.someKey}}` vào rule prerequisite rồi giả định key đó luôn tồn tại.
Cần cấu hình nguồn đầu vào/extractor phù hợp hoặc bổ sung cơ chế truyền auth
snapshot vào setup trước khi áp dụng cách này cho flow cụ thể.

## Tiêu chí login thành công

Cấu hình Auth Provider dựa vào response nghiệp vụ, không chỉ `STATUS_CODE=200`.
Evidence này có cả POST 200 trả trang captcha/login và POST 200 trả trang đã đăng nhập.
Chọn success/failure rule dựa trên dấu hiệu ổn định của trang đã login, hoặc một
validate flow phù hợp, để tránh đánh dấu phiên chưa đăng nhập là fresh.

Delay sau login hoặc chờ lock lâu hơn không tự làm hidden field cũ trở thành field
hợp lệ của session mới. Cũng không tự retry action khi gặp 302: cần dựng lại context
trước và xác định chính sách gửi lại phù hợp với operation.
