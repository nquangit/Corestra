# Auto Trigger: dùng chung flow với Auth Profile và Data Profile

Một flow có thể tiếp tục làm Login/Refresh Flow của Auth Provider, đồng thời dùng để
scan bằng Auto Trigger. Chọn nguồn input tại **Flowise > chọn request > Auto Trigger
Flow**, không cần đổi inject rule của flow hoặc sao chép flow theo từng user.

## 1. Hai lựa chọn profile đều là tùy chọn

| Cấu hình | Dữ liệu cung cấp cho riêng lượt trigger |
| --- | --- |
| **Auth Profile = Alice** | Credentials dưới `credentials.*`; Auth State hiện có dưới `auth.*`, `auth.header.*`, `auth.cookie.*` |
| **Data Profile = Account A** | Các Values dưới `data.*` |
| **None** | Không cung cấp dữ liệu từ loại profile đó; không tự chọn profile khác |

Ví dụ Credentials có key `username` thì rule dùng `credentials.username`.
Auth Variables có key `accessToken` thì dùng `auth.accessToken`. Data Values có key
`accountId` thì dùng `data.accountId`. Tên key phân biệt hoa/thường.

Để tương thích Login Flow, credentials và Auth Variables còn có alias không prefix,
ví dụ `username`, `accessToken`. Nên dùng tên có prefix để nguồn dữ liệu rõ ràng.
Data Values chỉ có namespace `data.*`.

**Chọn Auth Profile chỉ đọc dữ liệu, không gọi Auth Provider để login/refresh**, không
tự áp Provider Injection Rules và không sửa Session Handler. Nếu flow này chính là
login flow thì các bước login vẫn chạy theo flow đó, với credentials của actor đã chọn.

Auth State đã hết TTL không được đưa vào context; credentials vẫn được cung cấp để
login lại. TTL bằng 0 nghĩa là không có thời điểm hết hạn được khai báo, không phải
bằng chứng server vẫn chấp nhận session. Nếu cần state mới, thực hiện authentication
cho profile trước, hoặc để flow tự lấy state mới ở bước trước bước sử dụng.

Data Profile có owner Bob không tự chọn credentials/session của Bob. Có thể chủ động
dùng Auth Profile Alice với Data Profile của Bob để kiểm tra truy cập chéo.

## 2. None và các trường hợp thiếu dữ liệu

| Trường hợp | Hành vi |
| --- | --- |
| Flow chỉ có giá trị static, không dùng profile | Để cả hai **None**; vẫn chạy |
| Flow chỉ dùng biến extract ở bước trước | Để cả hai **None**; extractor phải lấy được giá trị khi chạy |
| Chỉ cần `credentials.username` | Chọn Auth Profile; Data Profile có thể **None** |
| Chỉ cần `data.accountId` | Chọn Data Profile; Auth Profile có thể **None** |
| Rule cần `credentials.username` nhưng Auth Profile là None | Dừng nếu không có extractor trước đó cung cấp đúng key |
| Profile được chọn không có key cần dùng | Báo key và step bị thiếu, dừng |
| Key tồn tại nhưng giá trị rỗng/chỉ có whitespace | Xem là thiếu input, dừng |
| Profile có nhiều key hơn flow cần | Các key dư không bắt buộc được dùng |
| Chọn profile bị tắt hoặc đã bị xoá | Dừng và báo cấu hình sai, kể cả profile không có key đang được dùng |
| Rule bị disable hoặc thuộc step disable | Không yêu cầu input cho rule/step đó |
| Rule ở sau điểm dừng | Không bắt cung cấp input cho step không chạy |
| Rule target được bỏ qua trong Target inject rules | Không yêu cầu input của rule đó trên request live |
| Extractor đã khai báo output nhưng chạy thực tế không lấy được | Nếu input vẫn thiếu tại bước sử dụng, dừng trước khi gửi bước đó |
| Biến chỉ xuất hiện trong một step sau bước sử dụng | Không đáp ứng dependency; không thể dùng giá trị tương lai |

None không có nghĩa là lấy lại credentials/token/data hardcode trong request đã capture.
Nếu **inject rule đang bật** cần biến mà không resolve được, công cụ không gửi request
với giá trị cũ. Những field không có inject rule vẫn giữ nguyên như request gốc.

Kiểm tra tĩnh diễn ra trước khi gửi prerequisite; kiểm tra thực tế diễn ra ngay trước
mỗi request. Phân tích tĩnh chỉ biết extractor **có thể** tạo output, không khẳng định
response sẽ có output đó. Với action gọi child flow, phần phụ thuộc sau action được
kiểm tra khi chạy vì child flow có thể cung cấp thêm biến.

Nếu lỗi trước target, request live bị drop và không đăng ký downstream. Nếu target
đã gửi rồi nhưng response không cung cấp đủ dữ liệu cho downstream, dừng downstream;
không thể thu hồi target đã gửi. Không tự retry các action nghiệp vụ.

Lỗi hiển thị trong log lỗi của extension ở Burp, có tên flow, step và key cần kiểm tra.
Không popup lỗi cho từng payload scan.

### Scanner chạy song song và prerequisite trả 403

Nếu một prerequisite có condition/action rule đang bật với **Retry current step**
hoặc **Retry flow from start**, Auto Trigger chạy các chuỗi prerequisite của cùng
flow lần lượt. Lượt hiện tại giữ quyền thực thi suốt **Sleep** và retry; payload scan
khác chờ, không gửi thêm prerequisite trong khoảng chờ đó. Flow khác vẫn chạy độc lập.
Mỗi payload vẫn có context và chạy prerequisite riêng; không dùng chung response,
token hoặc kết quả prerequisite giữa các payload. Flow không có retry ở prerequisite
tiếp tục chạy đồng thời như trước.

Ví dụ ở bước 1: condition **Source = RESPONSE_STATUS**, **Operator = IN**,
**Value Source = STATIC**, **Expected = 403,429**. Với một condition này có thể để
**Match = ALL**. Nếu tạo hai condition `EQUALS 403` và `EQUALS 429`, phải chọn
**Match = ANY** (OR). **ALL** là AND: một response không thể vừa có status 403 vừa
có status 429. `EQUALS 403,429` hoặc `EQUALS 403|429` cũng không phải phép OR;
chọn `IN` với danh sách phân cách bằng dấu phẩy. Editor giải thích và kiểm tra các
cách nhập nhầm này khi lưu rule đang bật; không tự thay đổi rule đã lưu.

Thêm actions theo thứ tự **Sleep**, Parameter `10` **seconds**, rồi
**Retry flow from start**, Parameter `2` (số lần retry bổ sung).
Cả hai retry action cho chỉnh Parameter trong khoảng **0–10**, mặc định **1** nếu
rule cũ chưa khai báo. Giới hạn được giữ khi mở/sửa/lưu rule và export/import.
`0` nghĩa là không gửi lại. Không có chế độ retry vô hạn. Lưu ý những bản trước
không hiển thị Parameter của retry và có thể làm mất `maxAttempts` khi sửa rule;
kiểm tra lại ô này sau khi nâng cấp, công cụ không tự tăng số lần retry đã lưu.

Parameter `2` là tối đa 2 lượt gửi lại ngoài lần đầu. Khi một lượt retry thành công, flow tiếp
tục với dữ liệu của lượt đó. Nếu lần cuối vẫn 403, flow thất bại và không chạy bước
tiếp theo; request scan bị drop. **Extensions → Corestra → Errors** ghi tên flow,
bước trigger, lý do hết retry và status cuối. Burp có thể biểu diễn request bị drop
thành lỗi/response rỗng; extension không tạo response thành công giả để Scanner chạy tiếp.
Nhiều lỗi liên tiếp có thể làm Scanner dừng insertion point. Số item trong Logger
không thể dùng làm số retry của một lượt: mỗi request scan có một lượt trigger riêng.

Nếu thiếu `cifId`/`otpId`, log có **messageId**, tên/id bước tạo biến và HTTP status
gần nhất của bước đó. Kiểm tra response và extractor của producer, không chỉ bước
inject báo thiếu biến. Nếu producer trả **200** nhưng thiếu dữ liệu, rule chỉ kiểm tra
403/429 sẽ không khớp; cấu hình thêm điều kiện phù hợp với response thực tế nếu muốn
retry trường hợp này. Flow vẫn dừng khi biến bắt buộc chưa có. Sau retry cả flow,
chỉ context của lượt cuối được dùng; dữ liệu từ lượt đã bị bỏ không được ghép trở lại.

Sleep được cấu hình bằng **giây**: `10` là 10.000 ms. Chờ prerequisite và retry làm
tăng thời gian hoàn thành một request scan, nhất là khi có nhiều payload đang chờ.
Giới hạn retry vẫn được áp dụng; tính năng này không đảm bảo server sẽ hết rate limit.
Chỉ chuỗi prerequisite được tuần tự hoá, không khoá target live hoặc toàn bộ traffic
đến host đó. Rule trên chính target live không được engine tự replay; downstream chỉ
chạy sau response live như trước.

Hướng dẫn trong **Check inputs…** và **Target inject rules…** là văn bản thuần,
tự xuống dòng theo chiều rộng. Dialog giới hạn kích thước theo màn hình và có vùng
cuộn cho nội dung dài; các nút xác nhận/huỷ nằm ngoài vùng cuộn.

## 3. Ví dụ scan request thứ 3 trong flow 4 request

Flow dùng chung:

```text
1. Login: inject credentials.username/password → extract token
2. Mở chức năng: inject auth.token → extract processorId
3. Action cần scan: inject auth.token, processorId, data.accountId
4. Kiểm tra kết quả: dùng output từ response của bước 3
```

1. Giữ nguyên cấu hình Auth Provider/Session Handler đang tham chiếu flow này.
2. Trong Flowise chọn request **3**, mở **Auto Trigger Flow**.
3. Chọn **Auth Profile** chứa credentials cho actor cần scan.
4. Chọn **Data Profile** nếu các inject rule thực sự cần `data.*`; nếu không cần thì
   chọn **None**.
5. Bấm **Check inputs…**: xem Step, Variable, nguồn là Selected profile hay Extract
   runtime, và các key đang Missing. Bảng không hiện giá trị credentials/token.
6. Chọn tool **Scanner** và cấu hình match đủ cụ thể: host, path, header/body phân
   biệt operation. Chọn **Full flow** nếu cần bước 4; **Up to this step** nếu chỉ cần
   prerequisite và target.
7. Bật trigger rồi gửi request mục tiêu từ Burp. Bước 1–2 dùng template của flow;
   bước 3 dùng **request live của Scanner**, không thay bằng request snapshot;
   bước 4 chỉ chạy sau khi nhận response của bước 3.

Output `token` ở bước 1 có thể dùng ở bước sau bằng `token` hoặc `auth.token`.
Output `processorId` cũng có alias `auth.processorId`. Các output đã có namespace
khác như `data.*` không bị tự thêm prefix `auth.`.

Trên request live, request-side extract chạy trước injection; response-side extract
chỉ chạy khi có response thật. Với prerequisite/downstream, extraction chạy sau khi
request của step đó đã gửi và nhận response; không dùng extractor của chính step
đó làm nguồn cho injection trước khi gửi nó.

## 4. Giữ payload của Scanner bằng Target inject rules

Nếu Scanner đang fuzz `accountId` mà rule của step 3 luôn ghi `data.accountId`, payload
của Scanner sẽ bị ghi đè. Profile selection không tự biết tham số nào Scanner đang fuzz.

1. Trong **Auto Trigger Flow**, bấm **Target inject rules…**.
2. Bỏ chọn **Apply on live target** cho rule inject `accountId`.
3. Giữ chọn các rule cần thiết khác, ví dụ token và processorId.
4. Xác nhận để lưu. Muốn bật lại thì mở bảng và chọn lại rule đó.

Việc bỏ chọn chỉ áp dụng cho **request live của trigger này**. Rule gốc không bị
disable; Auth Provider, Session Handler, Run Flow và các bước prerequisite/downstream
vẫn dùng rule gốc. Một target có thể bỏ qua hết injection, nhưng extraction và các
bước trước/sau vẫn hoạt động.

Nếu xoá một inject rule đã được chọn bỏ qua, trigger sẽ báo reference không còn tồn
tại. Mở lại **Target inject rules…**, review và xác nhận để xoá reference cũ. Không tự
đổi exclusion sang một rule khác có cùng tên.

## 5. Cách ly dữ liệu và quan hệ với Session Handler

- Mỗi request live có một context riêng, đọc một snapshot profile lúc bắt đầu.
  Không dùng context của lượt scan trước, kể cả các response về khác thứ tự.
- Extract mới chỉ cập nhật context đang chạy. Không ghi vào Auth Profile/Data Profile;
  muốn cập nhật Auth State dùng chung vẫn dùng luồng authentication hiện có.
- Chọn profile tại trigger không thay các profile mà Session Handler hoặc Access
  Testing đang sử dụng. Chạy qua Auth Provider không áp các lựa chọn riêng của trigger.
- Session Handler, session-selection và **Use session from triggering request for the
  entire execution** vẫn là cơ chế riêng. Profile selection không tự kích hoạt chúng.
- Khi bật kế thừa session, session được áp **sau** inject rules, có thể thay cookie/header
  mà flow vừa inject. Không chọn credentials của Alice rồi kỳ vọng option kế thừa một
  session Bob sẽ tự chuyển thành Alice; chọn actor/session phù hợp với mục đích chạy.
- Chạy login/refresh có thể đổi session và làm run đang pin session phải dừng. Chỉ dùng
  kế thừa session cho flow tái sử dụng session đó, không login lại để đổi nó giữa run.

Cấu hình chỉ lưu ID của profile và inject rule, không copy credentials/token vào flow.
Flow export riêng không mang theo các profile; khi import sang project khác cần import
profile tương ứng hoặc chọn lại chúng. Project cũ không có bindings mở ra với **None**
và không có rule exclusion; các trigger thiếu biến nay dừng thay vì âm thầm bỏ injection.

## 6. Auto Trigger từ extension khác, ví dụ Active Scan++

1. Chọn request target trong Flowise, mở **Auto Trigger Flow**.
2. Trong **Source tools**, bật **Extensions / Extender**. Có thể bật cả Scanner nếu
   Burp báo request của công cụ đó là Scanner thay vì Extensions.
3. Giữ criteria host/path/header/body đủ cụ thể và chọn Auth/Data Profile nếu cần.
   Khi target là JSON `POST`, thêm **Method / `^POST$`**. Param Miner có thể gửi
   request phụ đổi sang `GET`; không nên chạy prerequisite rồi ép inject JSON lên
   request đó.
4. Chọn phạm vi bước rồi bật trigger như với Repeater/Scanner.

Burp cung cấp loại tool `EXTENSIONS`, không cung cấp tên extension qua trường này;
checkbox không chỉ chọn riêng Active Scan++. Dùng criteria để giới hạn request.
Giá trị cấu hình cũ `EXTENDER` được chấp nhận như alias của `EXTENSIONS`.

Nguồn Extensions là **opt-in**. Filter cũ rỗng vẫn giữ phạm vi “all ordinary tools”
trước đây, không tự kích hoạt các extension khác sau nâng cấp. Bỏ chọn hết checkbox
nghĩa là không có tool được trigger, không tự đổi thành all tools.

Corestra gắn nonce tạm cho mỗi lần gửi Standard và theo dõi message ID khi Burp xử
lý callback, nên request nội bộ vẫn được nhận ra trên thread khác. Handler gỡ header
`X-Corestra-Internal-Send` trước khi ra server. Chỉ nonce đang được Corestra đăng ký
mới được xem là nội bộ; header trùng tên với giá trị bất kỳ không tạo quyền bỏ qua.
Trạng thái nhận diện chỉ ở RAM trong thời gian gửi và được dọn cả khi lỗi; không dùng
so khớp host/body để đoán nguồn, không làm lẫn request giống nhau của extension khác.
Proxy Pipeline tiếp tục dùng marker và cơ chế gỡ marker sẵn có. Login/refresh,
prerequisite/downstream, Access Testing và Manual Testing qua các transport này
không tự kích hoạt lại Auto Trigger.

Nếu bật **Use session from triggering request**, Session Handler phải chuẩn bị được
session cho traffic đó và injection rules phải cho phép `EXTENSIONS`. Chỉ trigger đã
opt-in Extensions và bật kế thừa session mới yêu cầu capture traffic Extensions;
checkbox này không tự bật Session Handler cho toàn bộ extension traffic. Session
binding vẫn kiểm tra generation, nguồn và destination như các tool khác.

Một số extension legacy, gồm các phiên bản Param Miner dùng API cũ, biểu diễn request
bị `drop` bằng một đối tượng có **response rỗng** và có thể tự lỗi khi đọc response đó.
Corestra vẫn dừng nếu thiếu input hoặc inject bắt buộc thất bại, kể cả không chọn
profile. Gửi lại snapshot chưa inject có thể dùng nhầm token/cifId cũ và làm sai kết
quả scan. Bản thử nghiệm trước từng cho Extensions bypass khi chuẩn bị thất bại;
hành vi đó đã được bỏ.

Nếu Logger chỉ có prerequisite #1/#2 mà không có target #3, xem **Corestra > Errors**:
target có thể đã bị chặn trước khi Burp gửi. Lỗi inject nay kèm method, Content-Type,
số ký tự body và dạng body (empty/object-like/array-like/other), không in giá trị body.
`Invalid JSON body` kèm vị trí lỗi, `Empty JSON body`, `JSON root is not an object`
và lỗi không duyệt được path là các trường hợp khác nhau. `object-like` chỉ nói body
bắt đầu bằng `{`, không đảm bảo JSON hợp lệ.

Với JSON injection, `OVERWRITE` thay giá trị. `APPEND`/`SKIP_IF_PRESENT` giữ nguyên
field đã có theo cấu hình và không làm fail request; nếu cần cifId/token mới từ
prerequisite, dùng `OVERWRITE`. Khi Param Miner cố tình đổi body thành dạng khác,
criterion Method/Content-Type có thể giới hạn những request mà bộ rule JSON hỗ trợ;
các probe ngoài criterion không chạy flow. Đây là giới hạn phạm vi test, không tự
chuyển rule JSON sang form/query hay sửa payload không hợp lệ.

Lỗi kết nối của target live vẫn do bên gửi/Burp xử lý; condition/action phục hồi của
engine áp dụng cho prerequisite và downstream. Xem [Lỗi thực thi và retry](EXECUTION_FAILURES.md).
