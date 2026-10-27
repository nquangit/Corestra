# Extract JSON: dot path, JSON Pointer và JSONPath

Trong **Flowise** hoặc **Manual Testing**, mở **Extract Rules > Add/Edit** và chọn
**Extractor type = JSON_PATH**. Có ba cú pháp, được ghi chú ngay dưới ô Selector:

| Cách chọn | Ví dụ | Khi dùng |
| --- | --- | --- |
| Dot path cũ, **không có `$`** | `data.token` | Đọc field trong object theo cách cũ |
| JSON Pointer, bắt đầu **`pointer:`** | `pointer:/data/items/0/id` | Chọn vị trí chính xác, gồm phần tử mảng hoặc tên key đặc biệt |
| JSONPath query, bắt đầu **`$`** | `$.data.token` | Query có filter, regex, wildcard, truy cập mảng và tìm đệ quy |

JSONPath thường bắt đầu bằng `$.`, nhưng `$['data']['token']`, `$[0].id`, `$..token`
và `$` cũng hợp lệ. Không cần thêm `$` vào dot-path đang dùng; project cũ giữ hành vi
hiện tại. `pointer:` và `$` đều có thể chọn toàn bộ JSON.

## Lọc theo nội dung

Response ví dụ:

```json
{"store":{"book":[
  {"id":10,"title":"JavaScript Essentials","price":10},
  {"id":20,"title":"javascript Advanced","price":20},
  {"id":30,"title":"Java","price":30}
]}}
```

Các selector hữu ích:

```text
$.store.book[1].title
$.store.book[?(@.id == 20)].title
$.store.book[?(@.title =~ /.*JavaScript.*/i)]
$.store.book[?(@.title =~ /.*JavaScript.*/i)].id
$.store.book[*].id
$..id
$.store.book[0:2].id
```

Query regex trên khớp hai sách có JavaScript trong title, không phân biệt hoa/thường.
Query kết thúc ở `book[...]` chọn object sách; thêm `.id` hoặc `.title` để chọn field.
Cú pháp sử dụng [Jayway JsonPath](https://github.com/json-path/JsonPath/tree/json-path-2.10.0#operators).

## Chọn cách xử lý nhiều kết quả

**JSONPath results** chỉ áp dụng cho query có root `$`; dot path và JSON Pointer giữ
cách trả kết quả cũ. UI bật dropdown khi selector là JSONPath:

| Lựa chọn | Hành vi |
| --- | --- |
| **Require one match** — mặc định | Đúng một kết quả mới thành công; query mơ hồ khớp nhiều kết quả sẽ báo lỗi |
| **First match** | Chủ động lấy kết quả đầu tiên theo thứ tự query trả về |
| **All matches (JSON array)** | Trả tất cả kết quả dưới dạng JSON array, kể cả chỉ có một kết quả |

Với selector lọc `.id` trong ví dụ: Require one match báo khớp 2 giá trị; First match
trả `10`; All matches trả `[10,20]`. Muốn lấy một object theo đúng ID, ưu tiên viết
filter đủ cụ thể thay vì phụ thuộc thứ tự phần tử.

String được đưa vào biến dưới dạng text, không kèm cặp dấu nháy JSON. Number và
boolean trở thành text như `10`, `false`. Object/array được giữ dưới dạng JSON text.
Transforms chạy **sau** khi đã chọn kết quả, theo đúng thứ tự chain.

Một path xác định như `$.items` có thể chọn đúng một node mang giá trị array. Khi
đó Require one match trả toàn bộ array; không tự hiểu các phần tử của array là nhiều
match. Ví dụ `$.items` trên `{"items":[1,2]}` trả `[1,2]`; All matches trả `[[1,2]]`.
Query wildcard `$.items[*]` mới chọn từng phần tử làm match riêng.

Không có match hoặc path không tồn tại là extraction failure, kể cả ở All matches;
không tự tạo một giá trị rỗng để inject. Một match mang giá trị `null` thất bại ở
Require one/First; All giữ nguyên `null` nếu nó nằm trong danh sách match. Một node
array rỗng được chọn trực tiếp như `$.items` vẫn là giá trị JSON hợp lệ.

Extraction failure không tự thay đổi chính sách xử lý lỗi của flow hoặc xoá biến đã
có trong context. Các kiểm tra input trước injection vẫn áp dụng như hiện tại.

## Test / Preview JSON

1. Chọn **Source side = RESPONSE** để đọc JSON response, hoặc REQUEST để đọc JSON
   body của request đã lưu.
2. Nhập selector và chọn **JSONPath results** nếu cần.
3. Bấm **Test / Preview JSON**. Preview không gửi request, không tự lưu rule.
4. Xem toàn bộ kết quả trong ô có thanh cuộn, gồm kết quả sau transforms. Lỗi cú pháp,
   JSON không hợp lệ, không có match hoặc khớp nhiều giá trị đều được báo tại đây.
5. Bấm **OK** để lưu. Cú pháp query/pointer được kiểm tra khi lưu; dữ liệu thực tế
   vẫn cần kiểm tra khi chạy.

Preview ưu tiên response đang hiển thị, gồm response mới của Manual Testing; nếu
không có thì dùng snapshot response đã capture của step. Chưa có response thì cần
capture hoặc Run trước. JSON body được đọc/phân tích ở background; sửa selector hoặc
result mode trong lúc preview đang chạy sẽ không hiển thị kết quả cũ cho cấu hình mới.

## Tương thích và phạm vi

- Rule cũ không có result mode mặc định Require one match cho query `$`; dot path
  và `pointer:` không đổi. Mode được giữ khi edit và export/import project.
- JSON Pointer escape `/` trong key thành `~1`, `~` thành `~0`. Ví dụ key `a/b`
  được chọn bằng `pointer:/a~1b`; dấu chấm trong key không cần escape ở Pointer.
- Dot path cũ chỉ đi qua object; chọn mảng bằng JSON Pointer hoặc JSONPath. Hành vi
  chuyển object/array thành text của dot path cũ được giữ nguyên; muốn JSON text đầy
  đủ hãy dùng Pointer/JSONPath.
- Session extraction dùng chung engine, với result mode mặc định Require one match.
- JSON selector của **request injection**, Evaluation Profiles và post-check assertions
  là các cơ chế riêng; tính năng này nâng cấp **extractor**, không đổi cú pháp của chúng.
