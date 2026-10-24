# Manual Testing: sửa request và thử flow bằng tay

**Manual Testing** là workspace bên cạnh **Flowise**. Bạn sao chép flow sang đây để sửa request trực tiếp, dùng placeholder trong path/header/body, chạy từng request và thay đổi biến giữa các bước. Không cần bật Auto Trigger.

Bản sao có ID riêng, không sửa flow gốc và không được dùng làm flow login/scheduler/auto-trigger của Flowise. Các action `TRIGGER_FLOW` đang bật được sao chép kèm dependency và đổi tham chiếu sang các manual draft tương ứng. Nếu thiếu child flow, thao tác copy báo lỗi trước khi lưu. Mỗi lần copy tối đa 64 flow, kể cả dependency.

## 1. Bắt đầu từ flow có sẵn

1. Trong **Flowise**, chọn flow rồi nhấn **Copy active flow to Manual Testing**. Hoặc vào **Manual Testing → Copy from Flowise…**.
2. Chọn draft trong dropdown **Draft**. Khi mở draft, request đầu tiên được chọn.
3. Danh sách bên trái cho chọn request, bật/tắt và đổi thứ tự như Flowise. Các nút dưới danh sách cho thêm, duplicate hoặc xoá request.
4. Trên editor, sửa tên bước và **Target origin**, ví dụ `https://api.example.test:443`. Target là nơi kết nối thực tế; sửa `Host:` trong message không tự đổi target.
5. Sửa method, path, headers và body ngay trong editor request. Response bên phải là kết quả nhận được, chỉ đọc.
6. Nhấn **Save template**, hoặc chạy request. Chuyển request/draft cũng lưu phần request đang sửa. Nếu request/target không hợp lệ, việc chuyển bị chặn để giữ nội dung chưa lưu.

Template dùng mô hình request HTTP/1.1 của flow hiện tại. Phiên bản trong request line được chuẩn hoá; đây không phải raw socket editor để giữ nguyên mọi byte framing. `Content-Length` được Montoya cập nhật khi chuẩn bị body thực tế. Override **Send via** vẫn thuộc từng request và ưu tiên hơn Project.

## 2. Inject rule được chuyển như thế nào?

Các rule chuyển được an toàn sẽ trở thành nội dung ngay trong request:

| Rule gốc | Template manual thường gặp |
| --- | --- |
| Header `Authorization`, template `Bearer {{token}}` | `Authorization: Bearer {{token}}` |
| Cookie `token` từ biến `token` | `Cookie: token={{token}}` |
| Query/Form `id` từ biến `objectId` | `id={{objectId|urlEncode}}` |
| JSON Path `token` từ biến `token` | `"token":"{{token|jsonEscape}}"` |
| Raw Body Replace từ biến `payload` | `{{payload}}` ở body |
| Giá trị static | Áp dụng vào snapshot của bản sao ngay khi copy |

Transform của VARIABLE được giữ trong placeholder khi biểu diễn được. Query/Form có thêm `urlEncode`, JSON string có thêm `jsonEscape`. Phần query không liên quan, ví dụ `path=/login.php`, giữ nguyên.

**Regex replacement, interactive input, template có transform phức tạp, hoặc mutation không chuyển an toàn được** sẽ ở lại tab **Extract / Inject Rules**. Để giữ đúng thứ tự, các inject rule phía sau rule chưa chuyển cũng được giữ lại. Thông báo sau khi copy liệt kê chúng. Các rule còn bật này chạy **sau** template và có thể ghi đè nội dung bạn sửa.

Nếu muốn sửa hoàn toàn bằng tay, vào **Extract / Inject Rules → Disable injects for this request**. Nút chỉ tắt inject rule của request trong draft; extractor vẫn giữ nguyên. Sau đó dùng placeholder hoặc giá trị literal ngay trong editor. Session injection là lớp riêng, tuân theo lựa chọn Session ở toolbar.

## 3. Placeholder, biến và transform

Cú pháp cơ bản:

```http
POST /api/items/{{objectId|urlEncode}} HTTP/1.1
Host: api.example.test
Authorization: Bearer {{auth.accessToken}}
Content-Type: application/json

{"processor":"{{processorId|jsonEscape}}","amount":"{{amount|jsonEscape}}"}
```

- `{{token}}`: thay bằng giá trị biến `token`.
- `{{id|urlEncode}}`: encode riêng giá trị này; không encode lại cả URL.
- `{{name|trim|lowercase}}`: transform theo thứ tự.
- `{{token|prefix:Bearer }}`: thêm `Bearer `, giữ khoảng trắng cuối argument.
- `{{value|jsonEscape}}`: escape nội dung JSON string, không thêm dấu nháy bao quanh.
- `\{{literal}}`: gửi literal `{{literal}}`.

Tên biến phân biệt hoa/thường. Dùng placeholder không có khoảng trắng trong path/query để giữ request line hợp lệ. Các transform text của [hướng dẫn Transforms](TRANSFORMS.md) dùng được trong pipeline. Ký tự `|` là dấu tách filter; regex có `|` hoặc argument phức tạp nên đặt ở extractor/retained inject rule thay vì inline pipeline.

Giá trị được thay **một lần**: nếu giá trị biến chứa `{{other}}`, nội dung đó không được render lần nữa. Biến không tồn tại hoặc transform lỗi sẽ dừng request, kể cả step đặt Continue on error. Biến đã tồn tại với giá trị rỗng được phép, để test trường rỗng có chủ ý.

Không tự encode giá trị nhập tay. Bạn quyết định chỗ nào dùng `urlEncode`/`jsonEscape` và chỗ nào gửi nguyên giá trị để thử dữ liệu bất thường.

### Ví dụ lấy processor ID rồi sửa action

1. Request 1 mở trang chức năng; giữ extractor xuất `processorId`.
2. Request 2 có body `processor={{processorId|urlEncode}}&action=view`.
3. Chọn request 1, nhấn **Run selected**. Xem response và biến trong **Variables**.
4. Chọn request 2 rồi chạy để dùng processor vừa lấy.
5. Muốn thử giá trị khác, sửa biến `processorId` trong bảng, hoặc thay placeholder ở body bằng `invalid-processor`. Không cần sửa extractor/inject rule gốc.

### Sử dụng bảng Variables

- **+ Variable / Remove**: thêm/xoá key và value; value có thể rỗng.
- **Load Auth / Data…**: nạp `credentials.*`, `auth.*`, `auth.header.*`, `auth.cookie.*`, `data.*` từ profile đang bật. Đây là nạp giá trị đang có, không tự login. Data Profile có owner phải đi cùng đúng Auth Profile. Những key trùng được thay bằng giá trị nạp.
- **Available**: gợi ý các key trong bảng và extractor của các bước bật trước request đang chọn.
- **Copy placeholder**: copy key đang chọn trong bảng; nếu chưa chọn row, dùng key ở Available. Dán vào editor tại vị trí muốn thử.

Kết quả extract tự cập nhật Variables sau mỗi lần chạy. Bạn có thể sửa nó trước lần chạy tiếp theo. Biến runtime của mỗi draft được giữ riêng; muốn bắt đầu một biến thể độc lập, copy flow thành draft khác.

## 4. Các cách chạy

| Nút | Hành vi |
| --- | --- |
| **Run selected** | Chạy đúng request đang chọn, kể cả request đang tắt. Không chạy Conditions & Actions của nó, nên retry/child flow không làm phát sinh request bất ngờ. Vẫn extract và cập nhật biến. Giữ focus để xem response. |
| **Run from here** | Chạy các bước bật từ request đang chọn đến cuối; dùng các biến đang có. Conditions & Actions hoạt động theo cấu hình. |
| **Run all** | Chạy các bước bật từ đầu đến cuối; dùng bảng Variables hiện tại. Conditions & Actions hoạt động theo cấu hình. |
| **Reset runtime** | Xoá biến và ràng buộc session của draft hiện tại. Template/rule không bị xoá. Response cũ vẫn tra được trong Flow Log nhưng không còn hiển thị như response của runtime mới. |
| **Stop** | Yêu cầu dừng, đóng prompt đang chờ và ngăn gửi bước tiếp theo. Một request đã gửi hoặc thao tác login đang chờ network không thể thu hồi; chờ job kết thúc mới chạy tiếp hoặc clean project. |

`Run all` không ngầm xoá Variables: đây là workspace thử tay. Dùng Reset runtime trước nếu cần chạy từ đầu mà không tái dùng bất kỳ biến cũ nào.

Mỗi lần Run dùng snapshot immutable của draft lúc bắt đầu. Editor vẫn cho chuẩn bị sửa template; nội dung đó có hiệu lực ở lần chạy tiếp. Chỉ có một job manual đang chạy trong workspace. Các child flow vẫn có giới hạn cycle/retry như engine hiện tại.

**Flow Log** lưu request thực tế sau render/session injection, response, status và lỗi. Trạng thái “Completed” cho biết lượt thực thi đã kết thúc; cần xem response/HTTP status để kết luận về test case. Continue on error có thể tạo lượt Completed with failed steps. Sau khi một request đã gửi, lỗi ở bước sau không hoàn tác request trước.

## 5. Session Handler và chọn actor

**Rule tool** là nguồn tool logic để xét các rule Session Handler, mặc định **REPEATER**. Đây không phải gửi request sang một tab Repeater; network request vẫn do extension gửi. Chọn PROXY/SCANNER/INTRUDER nếu muốn áp dụng filter tương ứng. Không cần bật Trigger trong Flowise.

### Session Handler scope / selection

Đây là mặc định. Với từng request đã render, Corestra xét scope, policy login/refresh, provider injection, local injection và response extraction của Session Handler.

Nếu request khớp điều kiện project **Send with session…**, popup hiện ra, header chọn session được loại theo setting và session chọn được được giữ cho phần còn lại của runtime. Không hỏi lại ở từng bước. Huỷ popup hoặc không có session hợp lệ thì dừng.

Nếu không có popup, scope được xét độc lập từng request. Header `X-User` chỉ có ở request 1 không tự được sao chép sang request 2. Muốn **chắc chắn toàn flow dùng X**, chọn `Use: X` hoặc Ask once.

### Use: tên Session Profile

Chọn rõ một Session Profile đang bật. Policy authentication hiện tại được dùng để chuẩn bị session khi cần; sau đó profile, tool và dấu phiên được giữ cho mọi request trong runtime, kể cả child flow và những lần **Run selected** tiếp theo.

Scope chọn profile ban đầu không hạn chế snapshot thiếu header `X-User`, vì bạn đã chọn actor rõ ràng. Destination mặc định giới hạn đúng origin của request đầu. Khai báo **Extra session origins** khi cần nhiều origin; không tự lấy mọi host trong flow làm host được phép.

### Ask once for this runtime

Hỏi chọn session hiện có ở request đầu tiên, không cần header trigger và không cần bật project Send with session. Chỉ chọn session đang bật, chưa hết TTL và có đầy đủ injection rule cho Rule tool; chạy **Run Auth Now** trước nếu chưa có session. Phiên chọn được được giữ đến khi Reset runtime hoặc đổi cấu hình session.

### No session injection

Không áp dụng Session Handler/selection/extraction của Corestra. Request vẫn được render từ bảng Variables và chạy retained inject rules nếu còn bật. Cookie/header literal đang có trong template vẫn được gửi; muốn test anonymous cần tự xoá chúng.

### Thứ tự và đổi actor

```text
Template {{variables}} → retained inject rules → session injection → send → extract
```

Session injection chạy cuối cùng. Muốn thử sửa chính token/cookie bằng tay mà không bị profile ghi đè, chọn **No session injection**.

Đổi Session/Rule tool mặc định xoá runtime để tránh dùng biến thuộc actor cũ. Nếu có chủ ý test X-owned data bằng session Y, bật **Keep variables when changing session** trước khi đổi. Ràng buộc session vẫn được xoá; chỉ biến được giữ theo lựa chọn rõ ràng của bạn. Review các giá trị auth/form trước khi gửi.

Các mode giữ session yêu cầu injection OVERWRITE/OVERRIDE và target áp dụng lặp lại an toàn: Header, Cookie, Query, Form, JSON, Raw Body Replace. Regex/Raw Body Append không được dùng làm session injection cho binding; có thể dùng trong retained flow rules hoặc chế độ scope không pin. Scope thông thường vẫn giữ write strategy của rule.

Session hết TTL, profile/actor/provider bị tắt, plan thiếu giá trị, login/refresh đổi dấu phiên hoặc token/cookie thay đổi thì runtime đã pin sẽ dừng. Dùng Reset runtime và chạy lại các bước lấy dữ liệu theo phiên. Keep-alive chỉ kéo dài TTL, giữ nguyên auth thì có thể tiếp tục. Kiểm tra đích của các bước trong range trước khi bắt đầu; child flow và request thực tế còn được kiểm tra khi gửi. TTL/dấu phiên cục bộ không chứng minh server vẫn chấp nhận session.

Response extraction của Session Handler có thể cập nhật State chung của profile như traffic thông thường. Variables của manual draft là dữ liệu riêng, không tự đồng bộ mọi `auth.*` từ Session Profile. Nạp profile hoặc extract đúng key khi template cần chúng.

## 6. Ảnh binary và lưu project

Response binary vẫn extract từ bytes gốc. Ví dụ giữ rule **Response → Raw body → base64Encode → image**, rồi dùng body request sau:

```json
{"image":"data:image/png;base64,{{image}}"}
```

Template request được chỉnh dưới dạng text. Không convert binary response qua `bodyToString` để tính base64. Xem [Binary body](BINARY_BODY.md).

Drafts (request/template, rule, step order và route override) được lưu trong Burp project riêng với Flowise và đi cùng **Project import/export**. Export sanitized/exclude-secrets áp dụng cả manual drafts. Project cũ không có manual drafts vẫn mở bình thường.

Variables, session binding và lịch sử chạy manual chỉ ở RAM, không nằm trong project configuration; mở lại project cần nạp/chạy lại runtime. **Flow Log** riêng của workspace bị chặn ở 1.000 entry và 32 MiB raw HTTP text; entry cũ bị loại hoặc payload quá lớn được ghi rõ là omitted. Editor được dùng lại giữa các draft, không tạo thêm native editor cho từng lần mở draft.

**Clean Burp Project Data** xoá cả manual drafts/runtime. Khi job manual chưa kết thúc, cleanup/import bị chặn để tránh job cũ gửi thêm request hoặc ghi trả State vào project vừa thay.
