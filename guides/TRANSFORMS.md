# Inject template và biến đổi giá trị

## 1. Gắn refresh token vào header Bearer

Trong **Flowise**, chọn request refresh token rồi tạo **Inject Rule**:

| Trường | Giá trị |
| --- | --- |
| Target | `REQUEST_HEADER` |
| Selector | `Authorization` |
| Value source | `TEMPLATE` |
| Value template | `Bearer {{auth.refreshToken}}` |
| Write strategy | `OVERWRITE` |
| Transforms | Để trống |

Bạn có thể nhập `Bearer `, chọn `auth.refreshToken` ở **Variable key**, rồi nhấn **Insert selected variable**. Placeholder được chèn tại con trỏ, hoặc thay phần đang bôi chọn. Dropdown vẫn gợi ý biến từ các extractor trước request, Auth Profiles và Data Profiles; cũng có thể nhập key riêng.

Nếu state chứa `refreshToken = abc123`, header gửi đi là:

```http
Authorization: Bearer abc123
```

Auth Provider chạy refresh flow với state của actor đang sử dụng. Login flow nên extract token thuần vào `refreshToken`; khi tiêu thụ state trong refresh flow, dùng `auth.refreshToken`. Injection của Auth Provider dành cho target request không thay thế việc cấu hình Inject Rule trong request refresh này.

Khi chạy thủ công, chọn actor ở **Execution > Run Context…**; profile cần có state `refreshToken`. Hoặc cung cấp biến mẫu `auth.refreshToken=abc123` trong custom variables. Một extractor trước request trong cùng flow cũng có thể cung cấp biến, với đúng tên key của nó.

## 2. Ba nguồn giá trị

| Value source | Cách nhập | Kết quả |
| --- | --- | --- |
| `VARIABLE` | `auth.refreshToken` | Đọc nguyên giá trị của một key; không nhập `{{...}}` |
| `STATIC` | `Bearer fixed-token` | Giữ nguyên văn bản, không render placeholder |
| `TEMPLATE` | `Bearer {{auth.refreshToken}}` | Kết hợp văn bản cố định và giá trị runtime |

Template hỗ trợ nhiều placeholder, ví dụ `{{credentials.username}}:{{data.accountId}}`, và biến không có scope nếu extractor đã tạo đúng key đó, ví dụ `{{token}}`. Key phân biệt hoa/thường. Khoảng trắng bao quanh key trong `{{ auth.refreshToken }}` được bỏ qua; khoảng trắng trong key không hợp lệ.

Không có hàm hay biểu thức trong placeholder. Template không render đệ quy: nếu giá trị biến chứa `{{other}}`, chuỗi đó được giữ nguyên. Muốn đưa dấu `{{...}}` ra nguyên văn, dùng `STATIC`.

Placeholder trống, thiếu dấu đóng, dấu đóng dư hoặc lồng nhau bị từ chối khi lưu/import. Nếu thiếu bất kỳ biến nào lúc chạy, cả rule bị bỏ qua, target được giữ nguyên và log có diagnostic nêu key bị thiếu. Quy tắc này không tự hủy toàn bộ flow; hãy kiểm tra Run Context và log nếu header chưa được thay.

## 3. Transform chạy lúc nào?

- **Extract Rule**: đọc dữ liệu → chạy từng transform → lưu vào biến.
- **Inject Rule**: lấy giá trị từ VARIABLE/STATIC/TEMPLATE → chạy từng transform → ghi vào request.

Đối với ảnh/file binary, chọn **BODY_RAW** để lấy nguyên byte, sau đó thêm các transform theo từng bước. Ví dụ PNG: `base64Encode` → `prefix:data:image/png;base64,`. Extractor không encode sẵn. `base64Encode` dùng byte gốc nếu đầu vào là raw, hoặc byte UTF-8 nếu đầu vào là text. `BODY_TEXT` rồi `base64Encode` chỉ phù hợp với text, không bảo đảm giữ nguyên binary. Xem [hướng dẫn raw body và chuỗi transform](BINARY_BODY.md).

Mỗi dòng là một operation, chạy từ trên xuống. Transforms của inject áp dụng lên **toàn bộ giá trị sau render**, không áp dụng riêng từng placeholder.

Ví dụ giữ token thuần trong Auth State và chỉ thêm Bearer tại inject:

```text
Value source: VARIABLE
Variable key: auth.refreshToken
Transforms: prefix:Bearer <một dấu cách cuối dòng>
```

Phần `<một dấu cách cuối dòng>` ở trên là chú thích; không nhập nguyên văn. Nhấn **Add Bearer prefix** để chèn đúng `prefix:Bearer `, gồm dấu cách cuối dòng. Cách TEMPLATE ở phần 1 dễ nhìn hơn cho trường hợp này.

Raw bytes được giữ trong chuỗi transform đến khi chuyển sang text bằng `base64Encode`, `hexEncode` hoặc `decodeText[:CHARSET]`. Trước bước đó, `substring` cắt theo **vị trí byte**; sau bước đó, nó cắt theo vị trí trong chuỗi Java. Các phép text như `trim`, `prefix`, `replaceRegex` phải đặt sau conversion. `decodeText` mặc định dùng UTF-8, hoặc charset được chỉ định như `decodeText:ISO-8859-1`; dữ liệu không hợp lệ báo lỗi, không tự thay byte bằng ký tự `�`.

Biến execution hiện lưu chuỗi. Vì vậy `BODY_RAW` không có conversion cuối cùng sẽ báo lỗi; Corestra không giả lập binary bằng một chuỗi text. Extract Rule editor kiểm tra thứ tự kiểu dữ liệu trước khi lưu; độ dài và nội dung thực tế được kiểm tra lúc chạy/test.

## 4. Danh sách cú pháp đầy đủ

Tên operation không phân biệt hoa/thường. Các đối số là văn bản nguyên dạng, không đặt trong dấu nháy.

| Cú pháp | Ý nghĩa | Ví dụ input → output |
| --- | --- | --- |
| `trim` | Bỏ khoảng trắng hai đầu | `  abc  ` → `abc` |
| `lowercase` hoặc `lower` | Chuyển chữ thường | `AbC` → `abc` |
| `uppercase` hoặc `upper` | Chuyển chữ hoa | `AbC` → `ABC` |
| `urlEncode` | UTF-8, kiểu form URL encoding | `a b+c` → `a+b%2Bc` |
| `urlDecode` | Giải mã kiểu form; `+` thành dấu cách | `a+b%2Bc` → `a b+c` |
| `base64Encode` | Raw bytes → Base64 chuẩn; text được encode bằng UTF-8 | `abc` → `YWJj` |
| `base64Decode` | Decode Base64 chuẩn thành text UTF-8 | `YWJj` → `abc` |
| `hexEncode` | Raw bytes → hex; text dùng UTF-8 | bytes `00 ff` → `00ff` |
| `decodeText[:CHARSET]` | Raw bytes → text theo charset, mặc định UTF-8, strict | bytes `61 62 63` → `abc` |
| `substring:START` | Lấy từ vị trí START đến hết | `substring:2` trên `abcdef` → `cdef` |
| `substring:START:END` | START bắt đầu từ 0, END không lấy vào | `substring:1:4` trên `abcdef` → `bcd` |
| `prefix:TEXT` | Thêm TEXT vào đầu | `prefix:id-` trên `123` → `id-123` |
| `suffix:TEXT` | Thêm TEXT vào cuối | `suffix:-ok` trên `123` → `123-ok` |
| `stripPrefix:TEXT` | Bỏ một prefix nếu khớp đúng | `stripPrefix:id-` trên `id-123` → `123` |
| `stripSuffix:TEXT` | Bỏ một suffix nếu khớp đúng | `stripSuffix:-ok` trên `123-ok` → `123` |
| `replaceRegex:PATTERN=>REPLACEMENT` | Java regex, thay mọi match | `replaceRegex:user-(\d+)=>id:$1` trên `user-123` → `id:123` |

`stripPrefix`/`stripSuffix` phân biệt hoa/thường; không khớp thì giữ nguyên. Substring trên text dùng vị trí ký tự UTF-16 của Java; trên raw body dùng vị trí byte. END có thể bỏ trống (`substring:2:`); index âm, START > END hoặc END vượt độ dài là lỗi.

URL encoding ở đây là kiểu form, không phải encode toàn bộ URL. Query/form injectors đã tự URL-encode giá trị; chỉ thêm `urlEncode` khi endpoint thực sự cần thêm một lớp encoding. Base64 ở đây không phải Base64URL của JWT, cũng không phải bộ giải mã dữ liệu nhị phân tùy ý.

Regex dùng cú pháp Java. `$1`, `$2` là capture group trong replacement; separator là `=>` đầu tiên. Trong UI nhập `\d`, không phải `\\d` như trong Java string. Đối số prefix/suffix không giải mã escape: `\s` sẽ là hai ký tự nguyên văn.

Khoảng trắng **cuối dòng** trong đối số được giữ nguyên khi lưu và chạy. `prefix:Bearer ` khác `prefix:Bearer`; `stripPrefix:Bearer ` bỏ cả dấu cách. Dấu nháy cũng là ký tự nguyên văn: `prefix:"Bearer "` sẽ thêm cả dấu nháy. Những dấu cách đầu dòng dùng để căn lề được bỏ qua.

## 5. Thử trước khi chạy

Trong cả Extract Rule và Inject Rule, nhấn **Syntax & test…**:

1. Xem danh sách operation và cú pháp.
2. Nhập **Sample input**.
3. Nhấn **Test current transforms** để chạy chain đang có trong rule editor.
4. Xem **Transformed output**, độ dài kết quả hoặc lỗi cụ thể.

Tester chỉ dùng mẫu bạn nhập, không gửi HTTP request và không đăng nhập. Khi test inject dùng TEMPLATE, sample là giá trị **sau render**, ví dụ `Bearer abc123`.

Nếu một transform lỗi (unknown operation, regex/Base64 sai, substring vượt giới hạn…), toàn bộ extract/inject rule bị bỏ qua với diagnostic chỉ rõ operation và vị trí. Giá trị trung gian không được inject/lưu vào biến. Một chain đúng cú pháp vẫn có thể lỗi với dữ liệu runtime có độ dài hoặc định dạng khác mẫu.

Để tránh `Bearer Bearer ...`, giữ token thuần trong state hoặc bỏ một prefix bằng `stripPrefix:Bearer ` trước khi thêm prefix mới. Thứ tự ví dụ:

```text
trim
stripPrefix:Bearer 
prefix:Bearer 
```

Hai dòng cuối trong ví dụ có một dấu cách sau `Bearer`.

Trong **Syntax & test…**, chọn **Sample type: Raw bytes (hex)** để nhập mẫu byte như `89 50 4e 47 00 ff` và kiểm tra chuỗi transform. Chọn **Text** khi muốn kiểm tra các transform text như trước. **Test extraction** trong Extract Rule dùng response đã lưu, nên phù hợp để kiểm tra trực tiếp dữ liệu ảnh.
