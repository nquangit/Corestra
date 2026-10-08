# Session Handler scope: nhiều host và điều kiện header

Scope quyết định **Session Profile nào được chọn cho một request**. Một profile vẫn dùng một flow login hoặc một linked Auth Profile; scope rule không tự đổi actor.

## 1. Vì sao cấu hình nhiều host trước đây không chạy?

Scope Custom cũ là một danh sách điều kiện nối bằng **AND**. Hai dòng `HOST = api.example.test` và `HOST = admin.example.test` buộc request khớp cả hai host, nên không có request nào khớp nếu dùng hai pattern chính xác khác nhau.

Trường Pattern là **Java regex**, không phải danh sách host phân cách bằng dấu phẩy. `api.example.test,admin.example.test` cũng không biểu diễn hai host thay thế.

Scope mới hỗ trợ:

```text
Common conditions
AND
(Rule 1 OR Rule 2 OR Rule 3 ...)
```

Mỗi rule là một record logic: tất cả điều kiện trong record phải khớp **cùng một request**. Host của rule này không được ghép với header hay port của rule khác.

## 2. Đọc giao diện Scope và tạo cấu hình đầu tiên

Scope có ba khu vực, không cần tự gán tên nhóm cho từng dòng:

- **Bên trái:** danh sách rule, ví dụ `API`, `Admin`, và **Shared conditions (all rules)** — các điều kiện chung cho mọi rule.
- **Bên phải:** chỉ hiện các điều kiện của nhóm đang chọn. **Add condition** thêm vào đúng nhóm đó; **Remove condition** xóa dòng đang chọn.
- **Configuration preview:** tóm tắt tất cả nhóm đang nhập, hiển thị yêu cầu chung, các lựa chọn thay thế và lỗi cấu hình. Đây là bản xem trước cấu hình, không phải kết quả thử một request. Nếu trạng thái là **Not saved**, runtime vẫn dùng cấu hình hợp lệ đã lưu trước đó.

**Trong một rule, mọi điều kiện đều phải khớp cùng request. Giữa các rule, chỉ cần khớp một rule. Shared conditions luôn phải khớp.** Khi chọn một dòng, hướng dẫn và ví dụ regex của loại điều kiện đó hiện dưới bảng.

Ví dụ profile liên kết với Auth Profile Alice, chỉ áp dụng khi request có `X-User: Alice`, cho hai host khác nhau:

1. Trong **Session Handler**, chọn Session Profile của Alice; tạm bỏ Enabled trong lúc nhập cấu hình.
2. Mở tab **Scope**, chọn **CUSTOM**.
3. Nhấn **Add scope rule**. Rule mới được chọn tự động. Thay HOST regex `(?!)` bằng `^api\.example\.test$`. Nhấn **Rename rule** để đặt tên `API`.
4. Nhấn **Add scope rule** lần nữa, đặt HOST regex là `^admin\.example\.test$`, đổi tên rule thành `Admin`.
5. Chọn **Shared conditions (all rules)** ở bên trái. Nhấn **Add condition**, đổi Type thành `HEADER`, Header name thành `X-User`, Regex pattern thành `^Alice$`.
6. Đọc bản tóm tắt: header `X-User` phải là Alice, và host phải khớp API **hoặc** Admin. Kiểm tra trạng thái **Saved**, sau đó bật lại Enabled.

| Nhóm ở bên trái | Điều kiện cần nhập ở bên phải |
| --- | --- |
| Shared conditions (all rules) | HEADER · `X-User` · `^Alice$` |
| API | HOST · `^api\.example\.test$` |
| Admin | HOST · `^admin\.example\.test$` |

Request đến một trong hai host và có đúng header sẽ chọn profile Alice. Header vắng mặt, có giá trị Bob hoặc host khác sẽ không khớp profile này.

**Rename rule** đổi tên toàn bộ nhóm, không phải từng dòng; tên trống, trùng tên rule khác và tên dành riêng `Common` sẽ bị từ chối. **Remove rule** xóa cả nhóm. **Move condition to…** chuyển một dòng sang nhóm khác, hữu ích khi chuyển scope cũ. Trong dữ liệu lưu, Shared conditions vẫn dùng tên `Common` để giữ tương thích.

## 3. Mỗi host có port, protocol, path và header riêng

Ví dụ cùng một Session Profile cần được áp dụng trên hai endpoint có dấu hiệu traffic khác nhau:

| Type | Selector | Regex pattern | Scope rule |
| --- | --- | --- | --- |
| HOST | | `^api\.example\.test$` | API |
| PORT | | `^443$` | API |
| PROTOCOL | | `^https$` | API |
| PATH | | `^/api(?:/|$)` | API |
| HEADER | `X-User` | `^tester-api$` | API |
| HOST | | `^admin\.example\.test$` | Admin |
| PORT | | `^8443$` | Admin |
| PROTOCOL | | `^https$` | Admin |
| PATH | | `^/admin(?:/|$)` | Admin |
| HEADER | `X-User` | `^tester-admin$` | Admin |

Tạo từng rule bằng **Add scope rule**, đổi tên bằng **Rename rule**, sau đó chọn rule ở bên trái và nhấn **Add condition** để thêm PORT, PROTOCOL, PATH hoặc HEADER. Bảng ví dụ trên biểu diễn các nhóm cấu hình; giao diện chỉ hiện điều kiện của nhóm đang chọn.

Một request API có header `tester-admin` không khớp bằng cách lấy host từ record API và header từ record Admin. Port, protocol và path cũng phải khớp trong đúng record đó.

Các giá trị `tester-api` và `tester-admin` ở đây chỉ là dấu hiệu để lọc traffic. **Cả hai rule vẫn dùng cùng Auth Profile của Session Profile hiện tại.** Nếu thực sự cần Alice và Bob đăng nhập bằng tài khoản khác nhau, tạo hai Session Profile, mỗi profile liên kết actor tương ứng và dùng điều kiện header phù hợp. Có thể dùng cùng một login flow/Auth Provider được tham số hóa.

## 4. Ý nghĩa từng loại điều kiện

| Type | Dữ liệu được kiểm tra | Ví dụ |
| --- | --- | --- |
| HOST | Host/domain/IP của destination HTTP service | `(?i)^api\.example\.test$` |
| PORT | Port thực tế của destination service | `^(443|8443)$` |
| PROTOCOL | `http` hoặc `https` theo transport | `^https$` |
| PATH | Path của request, không gồm query string | `^/api(?:/|$)` |
| METHOD | HTTP method | `^(GET|POST)$` |
| HEADER | Giá trị của header có tên trong Selector | Selector `X-User`, pattern `^Alice$` |
| BODY | Body dưới dạng text | `"operation"\s*:\s*"read"` |

- Regex dùng `find()`, nên không tự yêu cầu khớp toàn bộ giá trị. Dùng `^…$` cho host, port, method hay giá trị header chính xác. Escape dấu chấm trong domain bằng `\.`; thêm `(?i)` nếu cần match không phân biệt hoa/thường.
- `HOST` có thể là domain hoặc IP; không nhập cả URL `https://...` vào HOST. Muốn lọc virtual host khác destination service, dùng thêm `HEADER` với Selector `Host` và regex phù hợp.
- `PORT` là port máy chủ đích, không phải port listener của Burp Proxy. API HTTPS thông thường dùng 443 dù Burp listener nằm ở `127.0.0.1:8080`.
- Tên header không phân biệt hoa/thường; giá trị vẫn theo regex của bạn. Corestra dùng giá trị của header đầu tiên có tên đó. Header phải tồn tại kể cả khi pattern trống hoặc là `.*`. Header tồn tại nhưng giá trị rỗng có thể khớp `^$`.
- Pattern trống có nghĩa không giới hạn giá trị. Riêng PORT/PROTOCOL vẫn cần metadata thực tế; nếu không biết metadata thì criterion không khớp, không tự đoán 443/https.
- Selector chỉ dùng cho HEADER. Không cần tạo criterion cho một thuộc tính mà bạn không muốn giới hạn.

## 5. Chuyển scope cũ và kiểm tra cấu hình

Scope cũ được giữ nguyên ý nghĩa AND khi mở lại project. Các dòng cũ hiển thị trong **Shared conditions (all rules)**. Nếu có nhiều HOST ở nhóm này, bản tóm tắt nhắc rằng các host vẫn phải khớp đồng thời.

Để chuyển hai host thành các lựa chọn thay thế, tạo hai rule bằng **Add scope rule** rồi cấu hình HOST của từng rule. Chọn nhóm Shared, xóa các HOST cũ đã được thay thế; giữ lại các yêu cầu chung. Nếu header/path trước đây chỉ dành cho một host, chọn dòng đó và dùng **Move condition to…** để chuyển sang đúng rule. Tạm tắt profile trong lúc chuyển và chỉ bật lại khi bản tóm tắt đúng, trạng thái Saved.

Regex sai hoặc HEADER thiếu Selector sẽ báo **Not saved**, không ghi đè cấu hình hợp lệ trước đó. Hoàn tất các ô rồi kiểm tra trạng thái Saved. Regex được compile khi tạo criterion, không compile lại cho từng request.

Rule mới bắt đầu bằng HOST regex `(?!)`, nghĩa là không khớp gì cho đến khi bạn sửa. Xóa điều kiện cuối của một rule sẽ để lại HOST `(?!)` để nhóm rỗng không vô tình khớp tất cả host. Xóa rule cuối cùng sẽ để lại nhóm `Empty custom scope` không khớp traffic, kể cả khi còn các điều kiện Shared; thêm rule mới để cấu hình lại. Các scope cũ vốn có danh sách Custom rỗng được giữ tương thích và hiển thị rõ bằng Shared HOST `.*`.

Chế độ **BURP_TARGET_SCOPE** tiếp tục dùng Target scope của Burp; bảng Custom không tham gia matching. Các điều kiện Custom vẫn được lưu để bạn chuyển lại.

## 6. Kiểm tra trong Burp

1. Gửi request đến host đã cấu hình, đúng port/path và có đúng header `X-User`.
2. Kiểm tra Logger/request gửi đi có session của profile mong muốn.
3. Thử bỏ header, đổi header, đổi host hoặc port; profile đó phải không được chọn.
4. Kiểm tra host thứ hai với đúng bộ điều kiện của rule thứ hai.

Nếu nhiều Session Profile cùng khớp, hệ thống vẫn chọn profile đầu tiên và đưa cảnh báo. Nếu không có custom profile khớp, **General** vẫn có thể được chọn nếu đang bật và scope của General khớp; hãy kiểm tra General khi thấy injection ngoài dự kiến. Các giới hạn tool của injection/extraction rule vẫn áp dụng như trước.

Xem thêm [hướng dẫn chung](../USAGE.md), [Keep-alive](KEEP_ALIVE.md) và [session renewal](SESSION_RENEWAL.md).
