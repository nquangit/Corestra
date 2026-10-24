# Send with session: chọn session cho một live request

Tính năng này hỏi session nào sẽ được áp dụng khi gửi request từ công cụ Burp đã chọn. Nó dùng các **Session Profile đã tồn tại và có session dùng được** trong tab Session Handler; không tự đăng nhập để tạo một session mới trong popup.

## 1. Chuẩn bị session của từng người dùng

Nếu dùng linked Auth Profiles:

1. Trong **Authentication**, tạo Auth Provider và flow login như bình thường.
2. Tạo Auth Profile cho mỗi actor, ví dụ Alice và Bob, với credentials tương ứng.
3. Trong **Session Handler**, tạo một Session Profile liên kết với mỗi Auth Profile và bật profile đó.
4. Nhấn **Run Auth Now** cho từng profile; kiểm tra State có token/cookie cần thiết.
5. Cấu hình provider injection, ví dụ Cookie `JSESSIONID` và Cookie `token`, hoặc header `Authorization` với `Bearer {{auth.accessToken}}`.

Provider injection được kế thừa tự động. Không cần tạo lại những rule đã có. Nếu dùng local Injection Rules, kiểm tra danh sách tool cho phép có công cụ định gửi, ví dụ REPEATER.

Standalone Session Profiles cũng có thể được chọn nếu đang bật, có State chưa hết TTL và có rule inject áp dụng được cho công cụ đang gửi.

## 2. Bật điều kiện hỏi

Trong **Session Handler**, nhấn **Send with session…** ở thanh trên cùng. Đây là cấu hình chung của project, độc lập với profile đang được chọn trong danh sách bên trái.

Cấu hình ban đầu, chưa bật:

| Cấu hình | Giá trị |
| --- | --- |
| Enable session selection | Tắt mặc định; bật khi cần |
| Tools | REPEATER |
| Scope | CUSTOM |
| Shared condition | HEADER / `X-User-Selection` / regex rỗng |
| Timeout | 300 giây |
| Strip header | `X-User-Selection` |

Regex rỗng ở condition HEADER nghĩa là **header phải tồn tại**, giá trị header có thể bất kỳ. Nếu chỉ muốn hỏi khi header bằng `ask`, dùng `^ask$`.

Có thể giới hạn thêm host, port, protocol, path, method hoặc body bằng scope editor hiện có. Shared conditions dùng AND; các scope rule là các phương án OR. Xem [hướng dẫn scope](SESSION_SCOPE.md). Chọn BURP_TARGET_SCOPE nếu muốn hỏi dựa trên Target scope của Burp.

Chọn các tool cần áp dụng: REPEATER, PROXY, INTRUDER, SCANNER. Cấu hình cả tool lẫn điều kiện request phải khớp mới hỏi. Nếu bật, phải chọn ít nhất một tool. Nhấn **Save** để áp dụng.

## 3. Gửi từ Repeater

Thêm header vào request:

```http
X-User-Selection: ask
```

Nhấn **Send**. Popup hiển thị tên Session Profile và tên/role của linked actor nếu có. Chọn session, nhấn **Continue**. Corestra kiểm tra lại session rồi inject rules của profile được chọn và gửi request.

Header `X-User-Selection` được bỏ trước khi gửi với preset trên. Ô **Strip header** cho phép đổi tên header cần bỏ hoặc để trống để giữ nguyên. Việc bỏ header chỉ xảy ra khi session selection thực sự được kích hoạt.

Mỗi request khớp điều kiện sẽ hỏi lại. Corestra không nhớ ngầm lựa chọn của request trước.

## Session nào xuất hiện trong popup?

Một session phải đáp ứng:

- Session Profile đang bật và State không rỗng/hết TTL theo thông tin Corestra đang có.
- Với linked profile: actor đang bật; Auth Provider tồn tại và đang bật, trừ manual auth.
- Không đang có tác vụ authentication/maintenance giữ lock của profile hoặc actor.
- Có rule inject áp dụng được cho công cụ đang gửi; các rule cần thiết render đầy đủ từ State hiện tại.
- Các rule áp dụng dùng `OVERWRITE`. APPEND/SKIP_IF_PRESENT không phù hợp để đảm bảo request dùng đúng session được chọn, vì có thể giữ hoặc trộn authentication gốc.

Provider rules chưa hỗ trợ trên live Session Handler, hoặc thiếu biến khiến một phần rule bị bỏ qua, sẽ khiến profile không được đưa vào lựa chọn. Với một rule đã render nhưng không thể mutate request cụ thể, request bị dừng thay vì gửi nguyên bản.

“Dùng được” ở đây là kiểm tra trạng thái và TTL tại Corestra, không phải một request xác minh với server. Session có thể đã bị server thu hồi trước TTL. Hãy cấu hình TTL/keep-alive phù hợp và kiểm tra response thực tế.

## Scope và độ ưu tiên

Khi người dùng chọn session, **scope riêng thông thường của Session Profile được ghi đè cho request này**; điều kiện trong Send with session là scope điều khiển việc chọn. Điều này cho phép chọn Alice hoặc Bob dù request ban đầu chỉ tự động khớp một profile khác. Tool filters của local injection/extraction vẫn được giữ.

Request không khớp điều kiện chọn session giữ cơ chế chọn profile tự động hiện có. Traffic do extension tự gửi, bao gồm Proxy Pipeline của Corestra, không bật popup chọn session; Access Testing tiếp tục dùng actor isolation của chính nó.

Session selection chỉ áp dụng authentication rules đã cấu hình. Nó không tự cập nhật page ID, processor ID, transaction ID hay dữ liệu thuộc session khác trong body. Nếu endpoint có các field đó, cần lấy lại theo session được chọn. Xem [session renewal và form context](SESSION_RENEWAL.md).

Response của request đã chọn được gắn với đúng Session Profile và authentication generation để extraction không ghi nhầm vào profile tự động khác. Quy tắc chống response cũ ghi đè State mới vẫn được áp dụng.

## Huỷ hoặc không còn session hợp lệ

**Cancel**, đóng popup, Escape, timeout, không có session dùng được, profile bị tắt/hết hạn trong lúc chờ hoặc lỗi áp dụng rule đều dừng request. Không dùng session gốc, không đổi sang profile tự động khác, không tự login như một fallback.

Nếu popup mở lâu, profile có thể hết TTL hoặc scheduler có thể bắt đầu maintenance. Corestra kiểm tra lại sau khi chọn; nếu không còn dùng được, request bị dừng và cần gửi lại sau khi maintenance hoàn tất.

Popup được xếp hàng chung với Interactive input. Timeout gồm thời gian chờ đến lượt, mặc định 300 giây, cấu hình 1–3600 giây. Unload extension huỷ các popup đang chờ. Xem lý do dừng live request trong **Extensions > Corestra > Errors**.

Nếu không thấy popup, kiểm tra Enable, tool, condition HEADER/host/path và việc request có phải traffic extension hay không. Nếu không có lựa chọn, kiểm tra Enabled, Run Auth Now, State/TTL, tool filters và OVERWRITE.

## Dùng session đã chọn cho cả auto-trigger flow

Bật **Use session from triggering request for the entire execution** trong Trigger của request để các bước snapshot cùng dùng session đã chọn. Không cần thêm header chọn actor vào từng snapshot. Origin mặc định giới hạn theo request kích hoạt; session đổi giữa chừng thì flow dừng. Xem [hướng dẫn ràng buộc session cho flow](TRIGGER_SESSION.md).
