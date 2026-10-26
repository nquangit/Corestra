# Parameters: tạo wordlist riêng từ traffic

Tab **Parameters** ban đầu là một bảng quan sát thụ động: tên parameter/header, vị trí, host/path, số lần gặp và ví dụ tùy chọn. **Observations** vẫn giữ chức năng này. **Wordlists** bổ sung các tập từ riêng để tái sử dụng khi kiểm thử.

## 1. Thu thập

1. Đưa các URL cần quan sát vào Burp Target scope.
2. Mở **Corestra → Parameters → Wordlists**. Mặc định chỉ thu traffic từ **Proxy** và **In-scope only** đang bật.
3. Duyệt các chức năng ứng dụng bình thường qua Burp Proxy. Bảng tự cập nhật khi tab đang hiển thị; thu thập vẫn chạy nếu chuyển tab.
4. Chọn **Category**, **Host** và **Word filter** để xem bộ từ cần dùng.
5. Dùng **Remove selected** để bỏ từ nhiễu trong host filter hiện tại. Traffic/rescan tương lai có thể thêm chúng trở lại; đây không phải blacklist lâu dài.

**Pause live collection** dừng nhận thêm traffic mới; một tác vụ rescan chủ động vẫn chạy độc lập. **Include Repeater** mặc định tắt vì Repeater thường chứa dữ liệu đã chỉnh/payload. Scanner, Intruder và Extensions không được thu từ live HTTP handler, kể cả khi bật Repeater.

Đổi scope hoặc nguồn thu không tự xoá từ đã thu trước đó. Dùng **Clear runtime data** rồi Rescan để dựng lại theo cấu hình mới.

## 2. Các nhóm wordlist

| Nhóm | Dữ liệu |
| --- | --- |
| Query / Form parameters | Tên parameter của URL và form body |
| Request JSON keys / XML names / Multipart names | Tên field trong các kiểu body tương ứng, theo parser của Burp |
| Cookie names | Tên cookie từ request và `Set-Cookie`; không lấy token hoặc các attribute như HttpOnly/Path |
| Request / Response headers | Tên header, không lấy giá trị header |
| Response JSON keys | Tên key trong JSON, gồm nested object/array |
| HTML form names | `name` của input/select/textarea/button có attribute trong dấu nháy |
| Paths | Path không có query; segment có dạng ID/hash/UUID được chuẩn hoá thành `{id}` |
| Path segments | Các segment có dạng từ vựng; bỏ segment có dạng ID/hash/token |
| File names / File extensions | Ví dụ `login.php`, `php`; extension không có dấu chấm |
| Value words | Giá trị ngắn dạng enum của các key cho phép, không phải toàn bộ giá trị request/response |

Ví dụ `/api/orders/123456/view.php?mode=summary` tạo path `/api/orders/{id}/view.php`, các segment `api`, `orders`, `view.php`, file `view.php`, extension `php`, query key `mode`, value word `summary`.

**Paths chứa `{id}` là mẫu endpoint**: cần thay placeholder trước khi dùng như URL thật. Path segments và file names dùng như từ vựng độc lập.

Trong cùng một category, cùng một từ chỉ xuất hiện một dòng, kể cả đã thấy ở nhiều host. Host filter vẫn dùng được vì nội bộ giữ nguồn host. Export loại trùng giữa các host. **Tên parameter giữ nguyên hoa/thường** (`userId` khác `UserId`); tên header được đưa về chữ thường. Wordlist không giữ raw HTTP hoặc ví dụ giá trị làm metadata.

## 3. Value words

**Categorical values** bật mặc định, theo lựa chọn chỉ thu giá trị ngắn của `action`, `mode`, `type`, `role`, `format`, `status`, `state`, `operation`, `op`, `method`, `sort`, `order`, `direction`, `view`, `category`, `scope`, `response_type`, `grant_type`.

Ví dụ: `export`, `admin`, `active`, `json`, `authorization_code`. Chỉ nhận một từ ngắn bắt đầu bằng chữ, sau đó là chữ/số/underscore/hyphen. Bỏ giá trị có dạng token/hash/ID, ký tự payload, key nhạy cảm, giá trị dài hoặc có khoảng trắng. Không lấy giá trị cookie/header. Tắt **Categorical values** cũng xoá nhóm Value words đã có.

**Store redacted examples** thuộc tab Observations và mặc định tắt. Nó không mở rộng bộ giá trị được phép đưa vào Value words. Ví dụ trong Observations không được export cùng wordlist.

## 4. Export

- **Export filtered TXT…**: chọn một Category; lưu UTF-8, một từ mỗi dòng, sắp xếp và unique.
- **Export filtered ZIP…**: mỗi category có dữ liệu là một file TXT riêng, như `query-parameters.txt`, `request-headers.txt`, `path-segments.txt`, `value-words.txt`.
- Category, Host và Word filter đang chọn đều áp dụng khi export. Chọn **All categories / All hosts**, xoá Word filter trước nếu muốn xuất toàn bộ.
- Bảng hiển thị tối đa 5.000 dòng để UI nhẹ; export vẫn gồm toàn bộ kết quả phù hợp đang có trong corpus.
- Export ghi snapshot tại lúc bấm nút. File chỉ được thay khi ghi thành công; nếu file đã có, giao diện hỏi trước khi ghi đè.

## 5. Rescan sau khi mở lại project

1. Mở lại Burp project có **Proxy HTTP history** đã lưu.
2. Cấu hình scope và Categorical values.
3. Bấm **Rescan Proxy History**. Nó đọc các request/response đã lưu; **không bắn lại request**, không chạy flow, login, scanner hoặc lấy Site Map.
4. Xem số record đã xét/thu/bỏ qua. Rescan merge vào corpus hiện tại, không tạo từ trùng. Observations có thể tăng số lần quan sát vì đây là một lượt đọc lại.
5. **Cancel job** dừng ở record kế tiếp sau khi thao tác đọc hiện tại trả về; dữ liệu đã thu vẫn giữ. Clear/export bị khoá khi job còn chạy.

Nếu history không còn, extension không thể dựng lại phần traffic đã mất; dùng các file TXT/ZIP đã export. Wordlist không tự ghi vào Burp project hay file ngầm, tránh tăng kích thước project.

## 6. Lọc nhiễu và giới hạn

Live collection dùng allowlist nguồn Proxy, thêm Repeater chỉ khi chọn rõ. Request có marker proxy pipeline của Corestra bị bỏ qua. Những request có dấu hiệu payload SQLi/XSS/traversal/template injection, callback OAST hoặc scanner phổ biến bị bỏ qua cả exchange, gồm các dạng percent-encode thông thường. Tên field cũng phải có dạng tên hợp lệ; response không dùng để thu từ exchange đã bị loại.

Rescan loại record bị sửa trong Proxy và record có marker ở original/final request. **Proxy History API không cung cấp ToolSource gốc cho mọi record**: traffic từ scanner bên ngoài hoặc công cụ chạy qua một Burp Proxy khác có thể trông như traffic Proxy bình thường. Bộ lọc payload/nguồn giảm nhiễu, **không bảo đảm nhận diện mọi payload**. Các tên/ngữ cảnh hợp lệ chứa từ nghi vấn cũng có thể bị loại; hãy xem lại corpus trước khi dùng, và rescan sau khi thay scope nếu cần.

Corpus giữ tối đa **20.000 entry theo category/host**, evict entry cũ khi đầy. Observations vẫn giới hạn 5.000 entry. Live analysis chạy qua một worker với queue 32 task; message có body trên khoảng 1 MB hoặc queue đầy bị bỏ thay vì làm chậm gửi request. Counter dưới bảng cho biết evictions, queue/processing drops và skipped messages. Rescan có thể lấy tên header của response lớn nhưng không phân tích body lớn. JSON/HTML và số field/node/depth đều có giới hạn; nội dung binary không được parse thành từ.

Đây là corpus runtime: unload/reload extension sẽ xoá dữ liệu trong RAM. **Clear runtime data** xoá cả Wordlists và Observations, không xoá Proxy History hoặc file đã export. Không có automatic scan hoặc request gửi ra từ tính năng này.
