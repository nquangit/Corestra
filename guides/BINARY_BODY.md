# Raw body → chuỗi transform → request tiếp theo

Ví dụ: request 1 trả về ảnh PNG; request 2 nhận ảnh dưới dạng chuỗi `data:image/png;base64,...` trong JSON. Hai request nằm trong cùng flow, request lấy ảnh đứng trước request gửi ảnh.

## 1. Thêm hai request vào flow

Trong Burp, gửi và kiểm tra request lấy ảnh. Response cần là dữ liệu ảnh trực tiếp, ví dụ `Content-Type: image/png`, không phải trang HTML có thẻ `<img>` hay JSON chứa URL ảnh. Dùng context menu của Corestra để thêm request/response này vào flow.

Thêm request thứ hai vào cùng flow. Ví dụ request POST có header `Content-Type: application/json` và body mẫu:

```json
{"image":"replace-me","question":"Đọc nội dung ảnh"}
```

Sắp xếp request lấy ảnh trước request POST; bật cả hai step. Chạy **Run Flow** để thực hiện cả chuỗi. Chạy riêng **Run Step** của request thứ hai không tự chạy request lấy ảnh để tạo biến.

## 2. Extract toàn bộ body của ảnh

Chọn request lấy ảnh, thêm **Extract Rule**:

| Trường | Giá trị |
| --- | --- |
| Name | `Image data URL` |
| Enabled | Bật |
| Source side | `RESPONSE` |
| Extractor type | `BODY_RAW` |
| Selector | Để trống; trường này không sử dụng |
| Target variable | `imageUrl` |
| Transforms | Hai dòng: `base64Encode`, rồi `prefix:data:image/png;base64,` |

Extractor `BODY_RAW` chỉ lấy toàn bộ byte của **body**, không gồm status line/header, và không tự encode. Nhập chuỗi transform theo đúng thứ tự, mỗi operation một dòng:

```text
base64Encode
prefix:data:image/png;base64,
```

Bước `base64Encode` nhận byte gốc và tạo Base64 chuẩn, không chèn xuống dòng. Bước `prefix` nhận chuỗi Base64 và thêm phần đầu của data URL. Giá trị cuối được lưu trong biến chuỗi `imageUrl`, nên có thể dùng các inject rule hiện có.

Với JPEG, đổi transform thành `prefix:data:image/jpeg;base64,`. Prefix phải khớp loại ảnh thực tế; Corestra không tự đoán MIME type hay chuyển đổi định dạng ảnh.

Nếu API chỉ nhận Base64 thuần, chỉ giữ transform `base64Encode`, bỏ dòng `prefix` và có thể đặt tên biến là `imageBase64`.

`BODY_TEXT` lấy toàn bộ body dưới dạng text; khi dùng `base64Encode` với text, transform encode UTF-8. Với ảnh binary, chọn `BODY_RAW` để giữ nguyên byte đến bước chuyển đổi. Không đặt `prefix` trước `base64Encode`, vì prefix là phép xử lý text.

Đây là chuỗi xử lý tổng quát, không phải extractor chuyên cho ảnh. Ví dụ khác:

- `BODY_RAW` → `substring:8` → `base64Encode`: bỏ 8 byte đầu trước khi encode.
- `BODY_RAW` → `hexEncode`: biểu diễn nguyên byte dưới dạng hex.
- `BODY_RAW` → `decodeText:UTF-8` → `trim`: decode text bằng charset được chỉ định, rồi xử lý text. Decode thất bại nếu byte không hợp lệ cho charset đó.

Trong **Syntax & test…**, chọn **Raw bytes (hex)** để nhập mẫu byte và test các bước. Execution variables hiện lưu chuỗi: raw body phải được chuyển rõ ràng sang text trong chain trước khi lưu. Chain để nguyên bytes hoặc dùng text operation trước conversion sẽ báo lỗi, không tự đổi charset.

## 3. Kiểm tra extractor trước khi chạy

Trong dialog Extract Rule, nhấn **Test extraction**. Corestra sử dụng response mẫu đã lưu trong step, không gửi request mới. Kết quả cho biết độ dài chuỗi và phần đầu giá trị; với cấu hình trên, chuỗi phải bắt đầu bằng `data:image/png;base64,`.

Preview đọc byte từ response raw đã lưu, không decode body thành text trước khi encode Base64. Nếu step chưa có response mẫu, hoặc dữ liệu lưu không hợp lệ, preview báo không có response. Hãy capture lại request kèm response từ Burp.

Preview xác minh mẫu đã lưu. Khi chạy flow, extractor lấy response vừa nhận; hãy kiểm tra HTTP status và `Content-Type` thực tế để tránh encode trang báo lỗi/đăng nhập thành một chuỗi mang prefix ảnh. Body rỗng vẫn encode thành chuỗi Base64 rỗng; extractor không xác minh ảnh hợp lệ.

## 4. Inject vào JSON của request thứ hai

Chọn request POST, thêm **Inject Rule**:

| Trường | Giá trị |
| --- | --- |
| Target | `REQUEST_JSON_PATH` |
| Selector | `image` |
| Value source | `VARIABLE` |
| Variable key | Chọn `imageUrl` từ extractor của step trước |
| Write strategy | `OVERWRITE` |
| Transforms | Để trống |

Variable key nhập/chọn là `imageUrl`, không có `{{...}}`. JSON injector ghi chuỗi vào field `image`, xử lý việc escape JSON; các field khác vẫn giữ nguyên giá trị.

Với JSON lồng nhau như `{"input":{"image":"replace-me"}}`, dùng Selector `input.image`.

Bạn cũng có thể giữ biến Base64 thuần rồi thêm prefix ở request thứ hai: Value source `TEMPLATE`, Value template `data:image/png;base64,{{imageBase64}}`. Chỉ thêm prefix tại một vị trí.

## 5. Body dạng form hoặc text

- **Form URL encoded**: chọn Target `REQUEST_FORM_FIELD`, Selector là tên field ảnh, Value source `VARIABLE`, key `imageUrl`. Injector tự thực hiện form encoding; không thêm `urlEncode` nữa. Điều này giữ đúng các ký tự `+`, `/`, `=` của Base64 khi server parse form.
- **Text body**: để body mẫu là `IMAGE_PLACEHOLDER`, dùng Target `REQUEST_REGEX_REPLACEMENT`, Selector `IMAGE_PLACEHOLDER`, Value source `VARIABLE`, key `imageUrl`. Đặt `Content-Type` theo API nhận dữ liệu. Base64 và prefix MIME thông thường không chứa ký tự replacement đặc biệt của Java regex.

Tính năng này truyền **chuỗi Base64/data URL**, không upload file binary trực tiếp hoặc tạo multipart attachment.

## 6. Byte, encoding và dung lượng

- Khi chạy, `BODY_RAW` lấy byte do Montoya `body()` cung cấp ở ranh giới transport. Transform `base64Encode` xử lý trực tiếp byte này, không dùng `bodyToString()` và không chuyển charset. Standard và Proxy Pipeline đều đi qua bước extraction này.
- Extractor không tự giải nén `Content-Encoding`, bỏ framing còn có trong body, resize ảnh hay đổi định dạng. Nếu response còn gzip/br hoặc chunk framing, Base64 của những byte đó không phải ảnh thuần. Với endpoint lấy ảnh, thử `Accept-Encoding: identity` và kiểm tra body thực nhận ở Burp; dùng response mẫu đã được Burp xử lý framing khi preview.
- Nếu snapshot chỉ có text và không có byte gốc, extraction báo không có raw body bytes; không tự encode lại text để giả lập binary. Điều này cũng áp dụng cho snapshot được dựng lại từ log text, chẳng hạn local Session Handler extraction từ flow execution record. Với chuỗi xử lý ảnh, đặt extractor trực tiếp trên step lấy ảnh trong Flowise.
- Biến vẫn là chuỗi, không phải một binary store mới. Base64 dài `4 × ceil(số byte / 3)`, cộng prefix nếu có; ảnh lớn làm tăng bộ nhớ và kích thước request tiếp theo. Tính năng không thêm bản sao raw binary mới vào cấu hình project; dữ liệu bạn chủ động lưu vào state/results vẫn chịu chính sách persistence hiện có.
- Raw-body bytes được sao chép khi tạo/đọc snapshot để tránh một thành phần sửa dữ liệu ảnh của thành phần khác.

Xem thêm [cú pháp transform](TRANSFORMS.md) và [hướng dẫn chung](../USAGE.md).

Cấu hình `BODY_BASE64` của bản trước tự được chuyển khi đọc thành `BODY_RAW` + transform `base64Encode` ở đầu chain. Các transform đã lưu được giữ nguyên sau bước đó; dropdown chỉ hiển thị `BODY_RAW`, không còn tùy chọn encode chuyên biệt.
