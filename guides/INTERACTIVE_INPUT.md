# Interactive input: nhập OTP, SMS hoặc giá trị bên ngoài flow

Nguồn **INTERACTIVE** trong Inject Rule yêu cầu người dùng nhập giá trị ngay trước khi gửi request chứa rule đó. Đây là nguồn dữ liệu mới của injection; Target, Selector, Write strategy và Transforms vẫn dùng cơ chế hiện có.

## Ví dụ flow đăng nhập có SMS OTP

Giả sử đăng nhập gồm:

1. `POST /login` gửi username/password và kích hoạt SMS.
2. `POST /verify` gửi `otp` cùng transaction ID lấy từ bước 1.
3. Request tiếp theo lấy cookie/token đã xác thực.

Trong **Flowise**, thêm các request theo thứ tự này. Ở bước 1, extract transaction ID; ở bước 2, inject transaction ID bằng nguồn **VARIABLE** như bình thường.

Ở bước 2, mở **Inject Rules > Add**, rồi cấu hình:

| Trường | Giá trị ví dụ |
| --- | --- |
| Name | `SMS OTP` |
| Target | `REQUEST_FORM_FIELD` |
| Selector | `otp` |
| Value source | `INTERACTIVE` |
| Input prompt | `Nhập mã SMS vừa nhận cho lần đăng nhập này` |
| Mask input | Bật để che ký tự trong popup |
| Timeout (s) | `120` |
| Write strategy | `OVERWRITE` |
| Transforms | `trim` |

Nhấn **OK**, chạy **Run Flow**. Bước 1 chạy trước; trước khi gửi bước 2, popup xuất hiện. Nhập OTP và nhấn **Continue**. Giá trị được transform rồi inject; flow tiếp tục các bước sau.

Nếu endpoint nhận JSON, chọn `REQUEST_JSON_PATH` với Selector là đường dẫn field, ví dụ `otp`. Nếu nhận header, chọn `REQUEST_HEADER`; nếu cần toàn bộ body, chọn `REQUEST_RAW_BODY_REPLACE`.

**Run Step** chỉ chạy request được chọn, nên không tự thực hiện bước gửi SMS hay lấy transaction ID trước đó. Dùng Run Flow khi các bước có phụ thuộc.

## Transform và cách ghi

Transforms chạy lần lượt trên chuỗi đã nhập, cùng cú pháp như các nguồn khác. Ví dụ nhập token rồi thêm Bearer:

```text
trim
prefix:Bearer 
```

Khoảng trắng sau `Bearer` có ý nghĩa. Xem [cú pháp transform đầy đủ](TRANSFORMS.md).

`OVERWRITE` phù hợp với OTP hoặc token cần thay thế giá trị có sẵn. `APPEND` và `SKIP_IF_PRESENT` vẫn giữ ý nghĩa thông thường: APPEND có thể thêm một occurrence; SKIP_IF_PRESENT có thể giữ giá trị cũ. Chọn OVERWRITE nếu mục đích là luôn sử dụng dữ liệu vừa nhập.

Với Target `REQUEST_REGEX_REPLACEMENT` (chỉ body) và `REQUEST_FULL_REGEX_REPLACEMENT` (request line + headers + body), giá trị interactive được chèn như text literal: `$` và `\` trong dữ liệu nhập không trở thành tham chiếu capture group. Regex vẫn phải khớp phạm vi tương ứng. Nguồn VARIABLE cũng được chèn literal (giá trị lấy từ traffic có thể chứa `$`). STATIC/TEMPLATE giữ cú pháp replacement của Java (`$1`).

## Huỷ, timeout và chạy đồng thời

- Input rỗng hoặc chỉ có khoảng trắng không được xác nhận; popup yêu cầu nhập lại.
- **Cancel**, đóng cửa sổ, Escape, timeout hoặc ngắt execution đều dừng request cần input và dừng flow. Giá trị mẫu của request không được gửi thay thế.
- Lỗi transform hoặc mutation của rule interactive cũng dừng execution.
- Huỷ không bị biến thành retry bởi CONTINUE_FLOW hoặc condition/action rules, và không kích hoạt fallback refresh → login của Auth Provider.
- Timeout mặc định là 300 giây; phạm vi cấu hình 1–3600 giây. Thời gian xếp hàng chờ popup khác cũng nằm trong timeout.
- Corestra chỉ mở một popup tương tác tại một thời điểm. Các flow/request khác chờ đến lượt; request không cần tương tác vẫn chạy theo cấu hình hiện có.
- Unload extension đóng popup và giải phóng các request đang chờ.

Mỗi rule interactive được thực thi sẽ hỏi một lần. Hai rule khác nhau không tự chia sẻ câu trả lời. Nếu chạy lại flow, hoặc một retry hợp lệ quay lại bước trước khi có huỷ, lần thực thi mới sẽ hỏi lại.

## Auth Provider, Session Handler và Access Testing

Có thể đặt rule này trong flow login được Auth Provider sử dụng. Khi actor cần login, flow hỏi OTP ở đúng bước. Credentials vẫn nằm trong Auth Profile; OTP được hỏi cho từng lần chạy, không tự ghi vào Credentials/Data Profile hay biến trong execution context.

Session Handler, Access Testing và auto-trigger sử dụng cùng cơ chế input. Một flow scheduler có rule interactive sẽ cần người dùng trả lời khi chạy đến bước đó; vì vậy nên đặt OTP ở login, còn flow keep-alive tự chạy bằng session đã có nếu ứng dụng cho phép.

Che input chỉ che ký tự trong popup. Sau khi inject, giá trị trở thành nội dung HTTP request và có thể xuất hiện trong Flow execution log, evidence hoặc Burp Logger/history theo cấu hình lưu trữ hiện có. Không xem Mask input như cơ chế che dữ liệu trong log.

Khi execution bị dừng, xem lý do trong kết quả chạy flow hoặc **Extensions > Corestra > Errors** đối với live request. Interactive input cần Burp đang có giao diện và execution chạy ngoài Swing EDT; nếu không có UI, execution bị dừng.
