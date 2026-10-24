# Auto-trigger: dùng cùng session cho toàn bộ lần chạy flow

Nếu request từ Repeater mang `X-User: user-x`, Session Handler có thể chọn đúng profile X. Tuy nhiên các request snapshot trong flow thường không có header đó. Bật ràng buộc session để các bước trước, request đang gửi và các bước sau cùng dùng profile X, không phải thêm `X-User` vào từng snapshot.

## 1. Chuẩn bị Session Profiles

Ví dụ có hai người dùng X và Y:

1. Tạo Auth Provider với flow login và các injection rule xác thực cần thiết.
2. Tạo hai Auth Profiles với credentials riêng.
3. Trong **Session Handler**, tạo hai Session Profiles liên kết với hai Auth Profiles đó và bật cả hai.
4. Với profile X, cấu hình Custom scope có Shared condition `HEADER / X-User / ^user-x$`; profile Y dùng `^user-y$`. Thêm điều kiện host nếu cần. Xem [Session Scope](SESSION_SCOPE.md).
5. Kiểm tra provider injection đủ các trường xác thực, ví dụ Cookie `JSESSIONID` và Cookie `token`, hoặc Header `Authorization` = `Bearer {{auth.accessToken}}`.
6. Nếu có local Injection Rules, bật chúng cho tool định dùng, ví dụ **REPEATER**. Provider injection được kế thừa tự động. Các rule dùng để ràng buộc phải là **OVERWRITE/OVERRIDE**, có giá trị giải được và không rỗng.
7. Chạy **Run Auth Now** và kiểm tra State của từng actor.

Có thể dùng standalone Session Profiles tương tự. Khi request kích hoạt chưa có session, Session Handler vẫn thực hiện chính sách đăng nhập thông thường trước; flow sau đó ràng buộc vào session đã chuẩn bị. Không có session/profile đủ điều kiện thì request bị dừng.

## 2. Cấu hình Trigger

1. Trong **Flowise**, mở flow, chọn request sẽ được kích hoạt bằng request thật của Burp.
2. Mở **Trigger**, bật **Enable auto-trigger for this step**.
3. Chọn source tool, ví dụ **Repeater**, và đặt Match rules đủ cụ thể theo host/path.
4. Chọn phạm vi thực thi: **Up to this step**, **Up to step**, hoặc **Full flow**.
5. Bật **Use session from triggering request for the entire execution**.
6. Thông thường để trống **Additional session origins**. Nếu các bước cần gửi session sang origin khác, điền các origin chính xác, ngăn bằng dấu phẩy, rồi nhấn **Enter** để lưu:

   ```text
   https://api.example.test, https://files.example.test:8443
   ```

Origin gồm **scheme + host + port**. `https://api.example.test` tương đương port 443; `http://api.example.test` là origin khác. Không nhận wildcard, regex, path, query hay username/password trong origin. Origin của request kích hoạt luôn được cho phép. Thêm origin nghĩa là cho phép gửi **các rule session đã cấu hình** đến origin đó.

Tính năng tắt mặc định, kể cả với project/flow cũ. Cấu hình được lưu cùng trigger của request đó; không thêm một setting chung ở cấp flow.

## 3. Gửi request

Trong Repeater, thêm:

```http
X-User: user-x
```

Khi gửi, Session Handler chọn X như bình thường. Corestra giữ riêng profile, tool gốc và dấu phiên cho lần thực thi đó:

```text
Các bước trước → request thật của Repeater → các bước sau
                     cùng session X
```

Đổi header thành `X-User: user-y` để lần chạy tiếp theo dùng Y. Hai lần chạy đồng thời giữ hai context riêng; response về ngược thứ tự không làm tráo actor. Child flow chạy bằng action `TRIGGER_FLOW` cũng kế thừa ràng buộc và giới hạn origin.

Không cần sao chép header chọn actor vào các snapshot. Scope ban đầu chọn actor cho request kích hoạt; các bước nội bộ dùng profile đã chọn và kiểm tra origin được cho phép, không chọn lại actor theo scope snapshot. Local injection vẫn xét **tool gốc**, ví dụ REPEATER, thay vì đổi thành PROXY.

Nếu dùng popup **Send with session…**, session chọn trong popup cũng được kế thừa. Match criterion HEADER xét header thực tế đã chuẩn bị; với header bị loại bỏ (ví dụ header chọn session), nó dùng giá trị ban đầu để vẫn kích hoạt được flow. Xem [Send with session](SEND_WITH_SESSION.md).

## 4. Inject và biến của flow

Rule inject thông thường của bước chạy trước; session đã ràng buộc được áp dụng sau cùng. Token/cookie cũ trong snapshot hoặc rule static không thể ghi đè lại những trường session được cấu hình.

Phải cấu hình đủ **mọi trường xác thực cần thay thế** trong provider/session injection. Những cookie/header khác không có rule tương ứng vẫn giữ giá trị của request. Corestra không tự suy ra tất cả trường nào thuộc session.

Ràng buộc áp dụng authentication bằng các rule đó; không tự đưa toàn bộ State vào biến của flow. Các biến khác, như processor ID hoặc CSRF, vẫn cần extract/inject theo luồng. Khi bật ràng buộc, inject thiếu biến hoặc mutation thất bại sẽ dừng thực thi, tránh gửi dữ liệu snapshot cũ.

Các rule session phải áp dụng được cho mọi bước được thực thi trong phạm vi đã chọn. Ví dụ rule JSON Path sẽ báo lỗi nếu bước đó có body không phải JSON.

Các target session phù hợp: Header, Cookie, Query Parameter, Form Field, JSON Path và Raw Body Replace. Với Raw Body Replace, toàn bộ body sẽ bị thay bằng giá trị của rule như bình thường. **Raw Body Append và Regex Replacement không được chấp nhận làm session injection trong chế độ này**, vì session có thể được áp dụng lại khi chuẩn bị request thật. Chuyển trường xác thực sang một target cụ thể. Regex/Append vẫn dùng được trong các inject rule thông thường của flow.

Override **Standard/Proxy Pipeline** của từng request trong flow được giữ nguyên. Request thật khởi phát từ Repeater/Proxy vẫn do Burp gửi.

## 5. Phiên thay đổi, scheduler và lỗi

Corestra kiểm tra session trước khi bắt đầu flow và trước mỗi request được gửi:

- Profile/actor/provider phải còn được bật; session còn TTL và có đầy đủ rule áp dụng được.
- Request phải đến một origin được phép. Các destination snapshot đã biết được kiểm tra trước khi gửi bước đầu; child flow và destination thực tế được kiểm tra khi chạy.
- Login/refresh bắt đầu một dấu phiên mới, kể cả khi thất bại. Thay token/cookie hoặc plan inject giữa lần chạy cũng làm ràng buộc hết hiệu lực. Corestra **dừng**, không tự chuyển sang actor khác hoặc tiếp tục với snapshot.
- Keep-alive chỉ kéo dài TTL, giữ nguyên dữ liệu xác thực thì lần chạy có thể tiếp tục. Validation đợi tối đa 5 giây cho lock profile và tối đa 5 giây cho lock actor đang bảo trì; quá hạn thì dừng. Không giữ lock trong suốt flow hoặc trong lúc gửi network request.

Ưu tiên keep-alive nếu hệ thống có page/processor ID gắn với session. Khi login/refresh đổi session, bắt đầu một lần chạy mới để lấy lại các giá trị theo phiên. Xem [Keep-alive](KEEP_ALIVE.md) và [Session renewal](SESSION_RENEWAL.md).

Lỗi trước khi gửi request thật làm Burp **drop** request đó. Bước prerequisite đã gửi không được hoàn tác. Nếu session đổi sau response của request thật, các bước downstream bị dừng; request thật đã gửi cũng không thể hoàn tác. Downstream vẫn chạy bất đồng bộ sau khi extract response. Ràng buộc bị lỗi luôn dừng, kể cả có `Continue on error` hoặc retry action.

Xem output của extension để tìm thông báo `Trigger stopped` hoặc `Auto-trigger Phase 3 failed` với marker `[SESSION_BINDING_ABORTED]`. Thông báo sẽ phân biệt thiếu session, session đổi, origin ngoài danh sách hoặc lỗi inject.

TTL và dấu phiên là kiểm tra trạng thái phía extension. Chúng không bảo đảm server vẫn chấp nhận phiên; server có thể huỷ session bên ngoài, và một lần renew vẫn có thể bắt đầu ngay sau lần validation cuối cùng. Ràng buộc giúp tránh trộn actor/dấu phiên giữa các bước, không đóng băng session phía server.
