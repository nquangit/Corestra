# Corestra — rà soát lỗi, cập nhật ngày 2026-11-26

## Đợt 2026-11-26: regex trên toàn bộ request/response

Yêu cầu: regex extract rule bị cho là chỉ lấy được trong body, chưa lấy được trong full request;
kiểm tra tương tự cho response; rà soát các thiếu sót liên quan và sửa.

**Kết luận về yêu cầu gốc:** phần lõi extract đã có `RegexSource` = BODY / HEADERS /
FULL_MESSAGE cho **cả request và response** (`ExtractionService`), nên một Request rule chọn
`FULL_MESSAGE` đọc được request line, header và body. Những gì thật sự chỉ dùng được với body,
hoặc chạy sai lệch khi không dùng body, nằm ở các chỗ xung quanh. Dưới đây là các chỗ đó.

| Vấn đề | Ảnh hưởng trước sửa | Cách sửa / kiểm chứng |
| --- | --- | --- |
| Gợi ý regex chỉ lấy từ selection ở **response editor**; selection vắt qua header và body bị từ chối | Không gợi ý được regex cho request rule, cũng không gợi ý được kiểu FULL_MESSAGE; source side luôn bị đặt cứng là RESPONSE | `RegexSelection.fromRawMessage` ánh xạ selection ở request hoặc response vào HEADERS / BODY / FULL_MESSAGE, xử lý cả CRLF lẫn LF; nút đổi thành **Suggest from editor selection** và đặt source side theo editor có selection. Kiểm chứng: `RegexSelectionTest`, gồm một test gợi ý trên request line rồi chạy đúng regex đó qua `ExtractionService` |
| Văn bản mà gợi ý dùng cho HEADERS **khác** văn bản lúc runtime: gợi ý bỏ status/request line, runtime thì có | Offset và độ duy nhất của regex được tính trên một chuỗi khác chuỗi rule thực sự tìm; regex có thể khớp nhầm ở start line | Thêm `HttpMessageSnapshot.regexInput(source)` làm nguồn chung, cả `ExtractionService` lẫn `RegexSuggestionService` đều gọi nó. Kiểm chứng: `RegexSelectionTest#regexInputUsesTheSameTextForEverySource`; đã cập nhật `RegexSuggestionServiceTest` |
| Status line của response khác nhau giữa các đường chạy: flow engine và teardown có reason phrase, còn auto-trigger, Session Handler, Manual và preview trong editor thì không | Regex HEADERS / FULL_MESSAGE dạng `200 OK`, hoặc neo theo reason phrase, test trong editor thấy pass nhưng chạy trên traffic thật thì không khớp (hoặc ngược lại); HTTP/2 còn để lại khoảng trắng thừa ở cuối dòng | Thêm `integration.HttpMessageSnapshots` (request/response/statusLine) và dùng cho StepExecutor, AutoTrigger, CorestraSessionHttpHandler, ManualSessionHttpClient, SingleAccessTestRunner, TeardownFlowRunner, RuleEditorDialog và ResponseExtractionDialog. Reason phrase rỗng thì bỏ, không để khoảng trắng thừa. Kiểm chứng: `RegexSelectionTest#startLineOmitsBlankReasonPhrase` |
| Inject regex (`REQUEST_REGEX_REPLACEMENT`) chỉ thay được trong body | Không thay được path segment, query trong request line hay header bằng regex; guide Access ghi rõ là teardown không đổi được ID trong path | Thêm target `REQUEST_FULL_REGEX_REPLACEMENT`: regex chạy trên `request-line CRLF headers CRLF CRLF body`, sau đó tách ngược lại; nếu kết quả làm mất dòng trống phân cách thì bỏ qua rule (hoặc báo lỗi khi rule là bắt buộc). Session binding và việc chuyển rule sang Manual Testing đối xử với target này giống regex body. Enum value được thêm sau giá trị cũ nên tài liệu đã lưu vẫn đọc được. Kiểm chứng: `InjectionServiceTest$FullRequestRegexReplacementInjection` |
| Giá trị từ VARIABLE đi vào `replaceAll` dưới dạng replacement syntax | Token hoặc password chứa `$` hay `\` bị hiểu thành group reference: hoặc chèn sai, hoặc ném `IllegalArgumentException` làm step thất bại | VARIABLE và INTERACTIVE được chèn literal; STATIC và TEMPLATE vẫn dùng cú pháp `$1`. Replacement sai (ví dụ `$9`) chỉ sinh diagnostic, và chỉ ném lỗi khi rule là bắt buộc. Regex sai cú pháp ở chế độ bắt buộc giờ báo "Invalid regex …" thay vì ném `PatternSyntaxException` thô. Đã xóa `RegexBodyInjector` vì không còn dùng. **Thay đổi hành vi:** rule VARIABLE nào trước đây cố ý dùng `$1` trong giá trị biến thì nay được chèn nguyên văn |
| Nguồn condition chỉ có header theo tên hoặc body; không có toàn bộ message | `MATCHES_REGEX` không kiểm tra được điều kiện vắt qua nhiều phần, ví dụ `302` kèm `Location: /login` | Thêm `REQUEST_FULL_MESSAGE` và `RESPONSE_FULL_MESSAGE` (response sẽ rỗng nếu không nhận được response). Kiểm chứng: `StepConditionEvaluatorTest#fullMessageSourcesSearchStartLineHeadersAndBody` |
| **Teardown gửi request dọn dẹp khi inject rule thiếu biến** | Rule bị bỏ qua lặng lẽ, request (thường là DELETE) được gửi với giá trị cũ lấy từ request mẫu, có thể xóa nhầm bản ghi khác. Flow engine, auth flow và auto-trigger đều đã chặn trường hợp này, riêng teardown thì chưa | Teardown kiểm tra `FlowInputValidator.missingStepVariables` và gọi `applyRequired`. Nếu thiếu biến, regex không khớp hoặc injection lỗi thì **không gửi**; evidence ghi `Not sent: …`. Ngoài ra teardown giờ dùng method sau khi inject, giống runner chính. Kiểm chứng: `SingleAccessTestRunnerTest#teardownWithMissingInjectVariableIsNotSent` |

Kết quả: `mvn -o test` trong WSL kali-linux: **999 test, 0 failure, 0 error, 8 skipped**
(các test Swing bị skip khi chạy headless). Chưa kiểm thử thủ công trong Burp, nên phần UI
(gợi ý từ request editor và combobox target/source mới) chỉ mới được kiểm qua compile và unit
test của phần logic thuần.

Tài liệu đã cập nhật: `USAGE.md` (regex trên cả hai phía, gợi ý từ selection, target
FULL_REGEX, condition FULL_MESSAGE), `guides/INTERACTIVE_INPUT.md`,
`guides/ACCESS_ADVANCED.md` (thay path segment ở teardown), `CLAUDE.md` (viết lại theo code
hiện tại).

**Còn mở / chưa làm:**
- Rule của Access Testing và Auth (`AssertionType`, `IndicatorType`, `AuthRuleType`,
  `PostCheckAssertionType`) chưa có toán tử regex nào, chỉ có contains, JSON và status. Đây là
  tính năng mới chứ không phải lỗi, nên chưa thêm.
- Khi cả request lẫn response editor đều có selection thì selection của response được ưu tiên;
  muốn gợi ý cho request thì cần bỏ chọn ở response trước.
- Request snapshot ở teardown, Access runner và preview trong editor dùng `HTTP/1.1` cố định
  vì `StoredRequestTemplate` không lưu HTTP version. Regex FULL_MESSAGE ở phía request nên tránh
  neo theo version.
- `pom.xml` khai báo Montoya `2026.4`, còn máy local có jar `2026.7`; các API dùng trong đợt này
  đã được kiểm bằng `javap` trên jar 2026.7.

## Đợt 2026-09-30

Phạm vi: đọc các đường chạy chính từ bootstrap đến session, auto-trigger, flow engine,
Access Testing, HTTP mutation và persistence; đối chiếu tests và thêm regression tests.
Đây là rà soát mã nguồn và unit tests, chưa phải kiểm thử tích hợp toàn bộ trong Burp.
Không thể suy ra “không còn bug” từ kết quả test suite.

## Đã sửa trong đợt rà soát

| Vấn đề | Ảnh hưởng trước sửa | Cách sửa / kiểm chứng |
| --- | --- | --- |
| Auto-trigger dùng tool/host/method/path làm khóa | Request đồng thời cùng API ghi đè context; response có thể nhận sai prerequisite hoặc mất context | Dùng Montoya `messageId()`; test hai request cùng endpoint, response về ngược thứ tự |
| Auto-trigger tìm lại rule khi response về | Sửa flow/request trong lúc chờ làm mất hoặc đổi flow đã match; response chưa từng trigger vẫn bị aggregate | Dùng snapshot đã lưu cho đúng message; response không có pending context được giữ nguyên |
| Aggregation bỏ qua cấu hình bật/tắt và target | Body response bị thay đổi khi aggregation tắt hoặc target không phải step hiện tại | Kiểm tra `enabled`, `useTriggeredStep` và `finalStepId` |
| Prerequisite thất bại vẫn để lại automation | Tiếp tục injection/response handling dù prerequisite đã fail | Tôn trọng kết quả flow; chỉ lưu pending sau khi chuẩn bị request thành công; giữ final context của engine |
| Response cũ ghi lại nguyên SessionProfile | Mất biến của response khác, quay về TTL/config cũ, hoặc tạo lại profile đã xóa | Cập nhật riêng state bằng thao tác nguyên tử trên repository; tests bảo toàn cấu hình/biến mới và không hồi sinh profile |
| Scheduler không kiểm tra lại sau khi chờ auth lock | Login do traffic vừa xong thì scheduler login lại ngay | Kiểm tra đến hạn trong cùng lock; test điều khiển thứ tự hai thread tái hiện 3 lần gửi thay vì 2 trước sửa |
| Session authentication chạy flow đã disable | Tắt flow vẫn có thể gửi request login | Bỏ qua auth flow đã disable; test số request gửi bằng 0 |
| Access Plan không giải phóng `running` khi khởi tạo/listener lỗi | Các lần chạy tiếp theo luôn báo plan đang chạy | Đưa toàn bộ phần sau khi lấy quyền chạy vào `try/finally`; test listener lỗi rồi chạy lại |
| Executor của Race Testing không đóng lúc unload | Worker/thread tồn tại sau khi unload extension | `RaceRunner` có `close()` và được đăng ký với `ServiceRegistry`; test executor shutdown |
| Safe export làm mất condition/action của flow target | Import lại làm thay đổi điều khiển flow, ví dụ mất BREAK_FLOW | Bảo toàn `conditionActionRules`; round-trip test cả SANITIZED_EXPORT và EXCLUDE_SECRETS |
| Access Testing dùng transport có Session Handler toàn cục | Profile session cùng scope có thể ghi đè token/cookie của actor đã chọn, làm request thực gửi khác evidence | Tách `accessFlowEngine` và `ActorIsolatedHttpClient` trên transport routing chưa decorate; dùng nhất quán cho auth, setup, target, race và teardown; test request actor được chuyển nguyên vẹn |
| Safe export còn để lọt secret trong flow/rule/override/assertion | File chia sẻ có thể chứa token hardcode, request/captured response hoặc expected value nhạy cảm | Dùng chung `FlowExportSanitizer` cho Flowise và Access snapshots; sanitize auth/session injection, flow overrides và assertions; bỏ captured response ở hai safe mode; fixture kiểm tra từng vị trí và giữ nguyên FULL_EXPORT |
| Ba HTTP integration path dựng lại raw HTTP/1.1 | Có thể đổi virtual Host/HTTP version, làm hỏng binary body không đổi hoặc giữ Content-Length cũ sau mutation | Áp kết quả mutation lên request gốc bằng immutable Montoya API; chỉ gọi `withBody` khi body đổi và để Montoya tính framing; test HTTP/2, custom Host, Content-Length và no-op body |
| Pending auto-trigger và Race worker thiếu giới hạn vòng đời | Pending map/common pool có thể sống mãi; một HTTP worker treo làm coordinator chờ vô hạn | Pending có TTL/capacity, Phase 3 dùng executor riêng và đóng khi unload; Race có completion deadline, cancel worker đang chờ/chạy và shutdown idempotent; test expiry/cap/close và HTTP worker treo |
| Proxy Pipeline tạo CONNECT thiếu port, đóng tunnel, tự xử lý lại CONNECT và phụ thuộc EOF | HTTPS mặc định tạo authority sai dạng `host` thay vì `host:443`, có thể bị abort khi bắt đầu TLS; Session Handler/auto-trigger có thể xử lý CONNECT của chính extension; Windows có thể báo socket abort sau khi response đã về | CONNECT luôn dùng authority `host:port`, tunnel keep-alive và recursion marker; response dừng theo Content-Length/chunked framing và chấp nhận reset thay EOF cho close-delimited response; lỗi hiện ghi rõ proxy listener và target |

Bản sửa keep-alive ở lượt trước vẫn được giữ: login không extract biến vẫn ghi nhận
thời điểm xác thực/TTL; flow thực thi thất bại hoặc không chạy step không được ghi nhận
là một lần xác thực thành công.

## Trạng thái các mục từng để mở

Bốn mục từng để mở trong bản rà soát ngày 2026-09-16 đã được xử lý trong mã nguồn và có
regression test. Phần còn cần làm thủ công là smoke-test trong Burp thật để xác nhận hành vi
của Montoya/Burp trên đường truyền thực, đặc biệt với HTTP/2 và payload nhị phân.

## Giới hạn kiểm chứng và kiểm tra thực tế

- Tests dùng fake transport/proxy Montoya; không gửi traffic đến hệ thống của người dùng.
- Test callback auto-trigger cài factory giả trong phạm vi test và khôi phục sau test.
- Đã kiểm tra API `messageId()` từ Javadocs/JAR Montoya 2026.4 trong workspace: request
  và response tương ứng dùng cùng ID.
- Cần smoke-test JAR trong Burp: keep-alive 180 giây; scan song song cùng endpoint;
  aggregation bật/tắt; login flow disable; hai actor trùng scope Session Handler; virtual
  host/HTTP/2/binary body; request race bị treo; unload/reload trong lúc auto-trigger/race chạy.
- HTTP 401/429 tự nó chưa đồng nghĩa `ExecutionResult.success() == false`: flow engine
  phân biệt lỗi thực thi với kết quả nghiệp vụ HTTP. Cấu hình condition/action phải phản
  ánh tiêu chí login thất bại; đây cũng là điểm cần kiểm tra khi ứng dụng có rate limit.

Chính sách retry sau auth flow thất bại vẫn chưa có backoff; bản sửa scheduler tránh
chạy trùng sau một lần thành công, không bổ sung chính sách retry mới.

## Kết quả verification

- `mvn -o -q package`: thành công sau các thay đổi cuối cùng.
- 576 tests: 0 failures, 0 errors, 1 skipped (môi trường headless).
- `git diff --check`: sạch.
- JAR: `target/corestra-0.1.0-SNAPSHOT-all.jar`.
- So với mốc verification 551 tests: thêm 21 test cases cho các mục đã xử lý tiếp theo.
