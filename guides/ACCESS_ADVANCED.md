# Access Testing: setup, kiểm chứng nghiệp vụ và workflow nâng cao

Đọc [hướng dẫn chính](ACCESS_TESTING.md) trước để có Auth Profiles, Data Profile, target và hai row baseline/BOLA. Tài liệu này giải thích các tab **Setup / Post-checks / Teardown** trong dialog Matrix, cùng masking, generator, race và import.

Ví dụ: Alice tạo một giao dịch ở trạng thái `DRAFT`; Bob thử confirm giao dịch của Alice. Ta muốn biết Bob có bị chặn **và giao dịch có thực sự giữ nguyên trạng thái** hay không.

## Mục lục

1. [Setup và chọn actor](#1-setup-tạo-context-trước-target)
2. [Post-checks và cú pháp assertions](#2-post-checks-xác-nhận-trạng-thái-thật)
3. [Teardown](#3-teardown-dọn-dữ-liệu-sau-test)
4. [Masking](#4-kiểm-tra-masking)
5. [Generator theo owner/role/tenant/branch](#5-generator-owner-role-tenant-và-branch)
6. [Race](#6-race-nhiều-request-cạnh-tranh)
7. [Import OpenAPI/Postman](#7-import-templates-từ-openapipostman)
8. [Lấy ID và xuất cấu hình](#8-lấy-id-lưu-và-xuất-cấu-hình)
9. [Checklist](#9-checklist-trước-một-plan-nâng-cao)

## 1. Setup: tạo context trước target

### 1.1. Chọn mode và actor

Mở **Matrix > Add Row/Edit Row > Setup**:

| Trường | Điền thế nào? |
| --- | --- |
| Setup Mode | NONE, RUN_PREFIX, RUN_SETUP_FLOW hoặc REUSE_SCENARIO |
| Setup Flow ID | ID nội bộ của flow chuẩn bị; không phải tên flow |
| Until Before Step ID | Với RUN_PREFIX: ID của step dừng trước nó, step này không được gửi |
| Setup Auth Binding | Actor dùng để gửi các request setup |
| Setup Auth Profile ID | Chỉ cần khi binding là FIXED_PROFILE |
| Scenario Policy | Thông thường chọn FRESH_PER_TEST |
| Scenario Instance ID | Chỉ điền khi đã có một instance runtime cụ thể muốn reuse |

| Mode | Chuỗi thực hiện |
| --- | --- |
| NONE | Không chạy setup độc lập; Flow Target vẫn chạy prefix theo mode của chính nó |
| RUN_PREFIX | Chạy các step trước Until Before Step ID của Setup Flow |
| RUN_SETUP_FLOW | Chạy flow chuẩn bị riêng, không cắt prefix |
| REUSE_SCENARIO | Lấy scenario instance đã tồn tại theo ID; không tạo context mới |

| Auth Binding | Actor setup/post-check/teardown |
| --- | --- |
| DATA_OWNER | Owner Auth Profile của Data Profile được dùng |
| TARGET_ACTOR | Target Auth Profile của row |
| FIXED_PROFILE | Auth Profile được chỉ định bằng ID ở trường riêng |

Setup cần flow tuyến tính có các request rõ ràng. Khi validator báo flow không phù hợp, tách phần chuẩn bị thành flow nhỏ thay vì dùng một flow có branching/trigger phức tạp.

### 1.2. Ví dụ hoàn chỉnh: Alice tạo, Bob thử confirm

**Chuẩn bị flow trong Flowise**:

1. Tạo flow `Prepare draft`, chứa POST `/api/transactions`.
2. Request mẫu có JSON `{"accountId":"replace-me","amount":100}`.
3. Thêm Inject Rule REQUEST_JSON_PATH, selector `accountId`, variable `data.accountId`.
4. Thêm auth injection **ngay trong flow setup**. Ví dụ REQUEST_HEADER `Authorization`, source TEMPLATE, value `Bearer {{auth.accessToken}}`. Nếu dùng cookie, thêm REQUEST_COOKIE theo đúng tên cookie với biến `auth.sessionId`/key đã extract.
5. Response mẫu: `{"transactionId":"tx-new-001","status":"DRAFT"}`. Thêm JSON extractor selector `transactionId`, variable name **`scenario.transactionId`**.
6. Chạy thử flow với actor Alice và data của Alice qua Run Context; xác nhận object được tạo và extractor có giá trị.

Setup bảo đảm auth cho actor được chọn và truyền state vào context, nhưng **không tự áp dụng bộ target Injection của Auth Provider như target request**. Flow setup phải có các Inject Rules cần thiết. Request raw của setup cũng không tự render mọi placeholder như Access Template; dùng các rule của Flowise để đưa auth/data vào đúng header, cookie, JSON, form hoặc query parameter.

**Tạo target Template**:

| Trường | Giá trị mẫu |
| --- | --- |
| Name | Confirm transaction |
| Method | POST |
| Scheme / Host / Port | https / app.example.test / 443 |
| Path / Query | `/api/transactions/{{scenario.transactionId}}/confirm` |
| Operation Type | STATE_CHANGING |
| State Changing | Bật |

Giữ auth target theo Provider hoặc Manual profile của row, tránh để token Alice cố định trong Template.

**Matrix row**:

| Tab | Trường | Giá trị |
| --- | --- | --- |
| Target | Target Type | REQUEST_TEMPLATE |
| Target | Template | Confirm transaction |
| Target | Target Auth Profile | Bob |
| Target | Data Profile | Alice account, owner Alice |
| Target | Expected Result | DENY |
| Setup | Setup Mode | RUN_SETUP_FLOW |
| Setup | Setup Flow ID | ID flow Prepare draft |
| Setup | Until Before Step ID | Trống |
| Setup | Setup Auth Binding | DATA_OWNER |
| Setup | Setup Auth Profile ID | Trống |
| Setup | Scenario Policy | FRESH_PER_TEST |
| Setup | Scenario Instance ID | Trống |

Khi chạy live:

```text
Auth Alice → Alice tạo DRAFT → extract scenario.transactionId
→ Auth Bob → Bob confirm ID vừa tạo → đánh giá response target
→ Post-check nếu có → Teardown nếu có
```

Tạo thêm row control Alice/ALLOW với cùng setup để xác nhận confirm request đúng. Mỗi row dùng FRESH_PER_TEST tạo giao dịch mới, tránh baseline consume object trước row Bob.

### 1.3. ProcessorId/CSRF thuộc session

Object ID có thể là tài nguyên của Alice cần dùng trong request Bob; processorId/CSRF có thể lại chỉ hợp lệ trong session của actor gửi request. Không lấy processorId của Alice rồi ghép với cookie của Bob.

- Nếu chỉ cần mở form của Bob rồi gửi action: chọn Setup Auth Binding **TARGET_ACTOR**, hoặc dùng prefix của Flow Target Bob.
- Nếu cần cả object Alice và context Bob: Setup riêng tạo object bằng DATA_OWNER, sau đó Flow Target Bob mở form của Bob và thực hiện action trên `scenario.transactionId` từ setup.
- Khi login đổi session, context cũ có thể mất hiệu lực. [Keep-alive](KEEP_ALIVE.md) giúp duy trì session; nó không khiến processorId của actor khác dùng được.

### 1.4. Suggest Setup from Flow và chính sách reuse

**Matrix > Suggest Setup from Flow** phân tích biến `scenario.*` target cần và các extractor có thể tạo chúng. Gợi ý prefix là điểm bắt đầu; kiểm tra lại Flow ID, điểm cắt và actor. Binding mặc định DATA_OWNER không nhất thiết đúng nếu biến cần tạo thuộc session TARGET_ACTOR.

Scenario Policy hiện có REUSE_EXISTING, FRESH_PER_TEST, FRESH_PER_ACTOR, FRESH_PER_BATCH. Với runner thông thường, các policy FRESH đều dẫn đến chạy setup mới; chưa có cache tự động riêng theo actor/batch như tên có thể gợi ý. Dùng FRESH_PER_TEST khi cần hành vi dễ dự đoán.

REUSE_EXISTING chỉ có thể reuse khi chỉ định Scenario Instance ID và instance đó còn hạn/chưa consumed; nếu không, setup chạy mới. REUSE_SCENARIO là mode lấy trực tiếp instance theo ID, không nên dùng để thay cho việc kiểm tra hạn/consumed. UI hiện chưa có trình quản lý scenario để chọn instance thuận tiện. Runtime scenarios không được đưa vào project configuration export; không nhập object ID như `tx-new-001` vào Scenario Instance ID vì đó là hai ID khác nhau.

Dry Run không chạy setup, nên không tạo `scenario.transactionId` mới. Templates Preview cho phép nhập Scenario mẫu để kiểm tra riêng target, nhưng các mẫu này không tự cấp cho live Matrix. Xác nhận bằng một live baseline trước khi chạy nhiều row.

## 2. Post-checks: xác nhận trạng thái thật

Target trả 403 chưa đủ chứng minh thao tác không có side effect. Post-check là request sau target, có actor và assertions riêng.

1. Tạo Manual Template `Read transaction status`: GET `/api/transactions/{{scenario.transactionId}}`, Operation Type READ_ONLY.
2. Mở **Matrix > Edit Row > Post-checks > Add Row**.
3. Điền bảng:

| Cột | Giá trị mẫu |
| --- | --- |
| Name | Draft must remain unchanged |
| Enabled | true |
| Template | ID nội bộ của Read transaction status |
| Auth Binding | DATA_OWNER |
| Auth Profile | Trống; chỉ dùng ID nếu FIXED_PROFILE |
| Data Profile | Trống để dùng Data Profile của target |
| Scenario | Trống để dùng scenario hiện tại của run |
| Assertions | `STATUS_CODE\|\|200\|false; JSON_PATH_EQUALS\|status\|DRAFT\|false` |

Các ô Template/Auth Profile/Data Profile/Scenario ở bảng này nhận **ID**, không phải tên hiển thị. Cách lấy ID cấu hình ở phần 8 bên dưới.

Cú pháp Assertions, ngăn từng assertion bằng dấu `;`:

```text
TYPE|EXPRESSION|EXPECTED|NEGATE
```

| Type | Expression | Expected |
| --- | --- | --- |
| STATUS_CODE | Để trống | 200 hoặc danh sách status, ví dụ 200,204 |
| BODY_CONTAINS | Để trống | Chuỗi phải xuất hiện |
| BODY_NOT_CONTAINS | Để trống | Chuỗi không được xuất hiện |
| HEADER_EXISTS | Tên header | Để trống |
| HEADER_VALUE_CONTAINS | Tên header | Chuỗi nằm trong giá trị header |
| JSON_PATH_EXISTS | Dot path, ví dụ transaction.status | Để trống |
| JSON_PATH_EQUALS | Dot path | Giá trị literal |
| JSON_PATH_NOT_EQUALS | Dot path | Giá trị literal |

NEGATE = true đảo kết quả kiểm tra; thông thường để false. JSON selector là dot path đơn giản, không phải đầy đủ cú pháp JSONPath với `$`, wildcard hoặc filter. Expected của assertion là literal, không được template-render từ `{{scenario.*}}`. UI phân tách bằng `|` và `;`, không có escape riêng cho các delimiter đó.

Ví dụ response `{"status":"DRAFT"}`: hai assertions phải cùng pass. Với row Bob/DENY, nếu status trở thành `CONFIRMED` thì verdict có thể thành SUSPICIOUS dù target có response trông như deny. Với row control Alice/ALLOW, đổi assertion sang CONFIRMED hoặc dùng post-check khác; không giữ assertion DRAFT cho cả hai row.

Xem **Results > Post-checks** để đọc request/response và từng assertion. Post-check không có assertion sẽ không chứng minh trạng thái đúng; runner ghi cảnh báo/REVIEW. Post-check lỗi có thể làm verdict REVIEW mà không có nghĩa target chưa được gửi.

## 3. Teardown: dọn dữ liệu sau test

1. Tạo flow tuyến tính `Delete draft` trong Flowise.
2. Ví dụ ứng dụng có POST `/api/transactions/delete`, body `{"transactionId":"replace-me"}`: thêm REQUEST_JSON_PATH, selector `transactionId`, variable `scenario.transactionId`. Nếu API dùng query, dùng REQUEST_QUERY_PARAMETER với tên field thật. Chỉ dùng endpoint mà ứng dụng thực sự hỗ trợ.
3. Với flow-backed auth, thêm auth Inject Rules cho actor dọn dữ liệu, như flow setup; không dựa vào Provider target Injection tự thêm vào teardown. Đường teardown hiện có áp dụng Headers/Cookies của state cho Manual profile; điều đó không đồng nghĩa bộ Injection của Provider cũng được áp dụng.
4. **Matrix > Edit Row > Teardown**, điền:

| Trường | Giá trị mẫu |
| --- | --- |
| Teardown Mode | RUN_FLOW |
| Teardown Flow ID | ID flow Delete draft |
| Teardown Auth Binding | DATA_OWNER |
| Teardown Auth Profile ID | Trống, hoặc ID với FIXED_PROFILE |
| Run on success | Bật |
| Run on failure | Bật nếu cần dọn cả bài bị lỗi/SUSPICIOUS |
| Run on skipped | Chọn theo việc setup có thể đã tạo dữ liệu trước khi bỏ qua target |

Các checkbox chọn theo outcome thực tế của run/verdict, không theo chữ ALLOW/DENY ở Expected. Một bài Expected DENY có verdict OK vẫn thuộc success; failure gồm lỗi chạy và kết quả SUSPICIOUS. Đọc Teardown evidence trong Results để biết cleanup có thành công không.

Không giả định teardown luôn có transactionId: nếu setup thất bại trước extractor, flow dọn có thể thiếu biến. Chọn endpoint dọn phù hợp cả trạng thái DRAFT/CONFIRMED theo ứng dụng; teardown thất bại cần xử lý dữ liệu còn lại bằng cách hợp lệ của ứng dụng.

Inject Rules hiện không có target thay path segment; REQUEST_REGEX_REPLACEMENT chỉ sửa body. Setup/teardown cũng không render path placeholder tự động như Access Template/Flow Target. Nếu endpoint dọn chỉ nhận DELETE `/transactions/{id}` và ID sinh lúc chạy, cấu hình teardown bằng các rule hiện tại chưa đủ; không điền `{{scenario.transactionId}}` vào path rồi coi nó đã được thay. Khi đó dùng request cleanup riêng đã render đúng ID hoặc dọn thủ công theo API ứng dụng.

## 4. Kiểm tra masking

Dùng Expected **MASKED** khi actor được xem response nhưng dữ liệu nhạy cảm phải ẩn.

1. **Evaluation Profiles > Add**, Name `Masked customer`.
2. Cấu hình Allow/Deny Indicators phù hợp response nghiệp vụ như hướng dẫn chính.
3. **Sensitive Fields**, mỗi dòng `dot.path|Tên mô tả`:

```text
customer.idNumber|Identity number
customer.phone|Phone number
```

4. **Masking Rules**, mỗi dòng `dot.path|REGEX|Tên mô tả`:

```text
customer.idNumber|\*{4}\d{4}|Four stars and last four digits
customer.phone|\*+|Phone hidden with stars
```

5. Matrix row chọn Expected Result MASKED và Evaluation Profile `Masked customer`.
6. Chạy baseline và xem response thực tế/Reason.

Regex dùng Java và phải match **toàn bộ** giá trị field, không chỉ tìm một substring. `****1234` match rule idNumber ở trên; `12345678` không match. Field thiếu/null được coi là không lộ; field có mặt mà không match masking thì bị xem là lộ. Dùng ít nhất một Sensitive Field đúng selector để bài kiểm tra có ý nghĩa.

Selector ở đây là dot path đơn giản. Không dùng `$.customer.idNumber` hoặc filter JSONPath. UI dùng `|` chia cột nên không nhập regex alternation chứa `|` như thể nó được escape riêng.

## 5. Generator: owner, role, tenant và branch

**Matrix > Generate Matrix** hoạt động trên request Templates. Với Flow Targets, dùng **Create Matrix Rows from Flow** hoặc Duplicate Row/Edit Row.

| Strategy | Cần chuẩn bị |
| --- | --- |
| OWNER_VS_OTHERS | Data Profiles có owner; actor cùng/khác owner |
| ANONYMOUS_ACCESS | Manual anonymous profile không có auth; target không hardcode cookie/token |
| ROLE_ESCALATION / CROSS_ROLE | Role của Auth Profiles và policy thật của endpoint |
| MAKER_CHECKER | Actor được gắn role MAKER/CHECKER; object owner đúng người tạo |
| CROSS_TENANT / CROSS_BRANCH | Values tenantId/branchId trong các Data Profiles có owner |
| OBJECT_OWNERSHIP_VARIANTS | Object có owner và metadata đủ cho các filter đã chọn |

Ví dụ tạo Alice account (owner Alice, tenantId A, branchId HN) và Bob account (owner Bob, tenantId B, branchId HCM). Generator suy tenant/branch của actor từ Data Profiles mà actor sở hữu; không có ô Tenant/Branch trong dialog Auth Profile để thay thế liên kết này. Trong generator, Tenant Key/Branch Key phải khớp key bạn dùng trong Values.

Nếu một actor sở hữu nhiều profile có metadata mâu thuẫn, đừng trông chờ generator suy ra membership chính xác. Review warnings và từng row. Role/tenant/branch chỉ hỗ trợ sinh bài test; chúng không thay đổi auth trên server. Admin hoặc cross-role có thể cần REVIEW vì quyền thực phụ thuộc policy nghiệp vụ.

Sau preview, sửa Expected nếu cần, kiểm tra filters, rồi lưu các row đã review. Không bật Start Plan chỉ vì generator tạo được nhiều row.

## 6. Race: nhiều request cạnh tranh

Run Race hiện dành cho **REQUEST_TEMPLATE**. Không dùng nó để chạy một Flow Target nhiều bước; hãy tách target cuối thành Template và chuẩn bị context bằng Setup nếu cần.

1. Chạy một row thường thành công trước, xác nhận auth/scenario đúng.
2. Chọn row trong Matrix, bấm **Run Race**.
3. Đặt Concurrency = 2, Iterations = 1 cho lần đầu; review **Total target requests**.
4. Chọn Scenario Mode và Auth Mode theo mục tiêu.
5. Bật **Synchronize start** nếu cần các worker bắt đầu gần nhau.
6. Dùng **Dry-run / render only** để kiểm tra request; khi chạy live có dialog xác nhận tiếp theo.
7. Đọc từng result cùng post-check sau batch, không chỉ tổng số 200.

| Scenario Mode | Khi dùng |
| --- | --- |
| REUSE_SINGLE_SCENARIO | Cùng object/token cho mọi worker và iteration, phù hợp thử double-submit |
| FRESH_SCENARIO_PER_WORKER | Mỗi worker chuẩn bị context riêng, reuse trong các iteration của nó |
| FRESH_SCENARIO_PER_ITERATION | Mỗi cặp worker/iteration có context mới |

| Auth Mode | Hành vi |
| --- | --- |
| SHARED_AUTH_PROFILE_STATE | Dùng state đã có, không acquire auth mới ở bước chuẩn bị |
| ENSURE_AUTH_BEFORE_GATE | Bảo đảm auth trước khi mở gate gửi target |

Các mode fresh của Race được runner race xử lý riêng, khác với Scenario Policy của run thông thường. Nếu test tranh chấp cùng một giao dịch nhưng chọn fresh mỗi iteration, bạn có thể đang test các giao dịch khác nhau.

Concurrency × Iterations là số target request; login/setup/post-check/teardown có thể làm tổng traffic cao hơn. Đồng bộ start ở phía client không bảo đảm server nhận các request cùng thời điểm. Chọn **Run post-checks after batch** để kiểm tra số lần side effect, **Run teardown after batch** để dọn dữ liệu sau khi batch hoàn tất.

## 7. Import Templates từ OpenAPI/Postman

1. **Templates > Import Templates > Choose File**.
2. Chọn Import Format hoặc auto-detect. Hỗ trợ OpenAPI 3.x JSON, Swagger/OpenAPI 2.0 JSON, Postman Collection v2.1 JSON; YAML cần chuyển sang JSON trước.
3. Kiểm tra Default Scheme/Host/Port và Base Path Override. Defaults chỉ bù/điều chỉnh theo cấu hình import; review URL của từng request ở preview.
4. Chọn Generate path param placeholders; bật Generate query param placeholders nếu muốn parameterize query.
5. Review Generate body example placeholders và Include deprecated operations theo collection/spec.
6. Parse rồi review danh sách template, warnings và mapping; chỉ lưu các mục muốn dùng.
7. Tạo Data Profiles/Auth Profiles tương ứng; import không tạo đầy đủ actor và dữ liệu cho bạn.
8. Chọn Template trong Matrix và Dry Run trước live baseline.

Ví dụ `/accounts/{accountId}` được parameterize thành path chứa `{{data.accountId}}`; Values của Data Profile phải có `accountId`. Postman `{{token}}` có thể được map thành `{{auth.accessToken}}`; biến không nhận diện được có thể sang `legacy.*` kèm warning. Đọc mapping thay vì cho rằng mọi biến đã có nguồn runtime.

Postman pre-request scripts, tests và auth helpers không được thực thi. Host/path còn phụ thuộc env/legacy phải được xử lý trước live Matrix; các bảng Env/Legacy ở Templates Preview không được mang sang Matrix live run.

**Disabled by default** tạo template với flag disabled. UI chưa có toggle/Edit riêng cho saved Template; flag này không phải khóa chặn mọi row tham chiếu. Dùng Matrix row Enabled để kiểm soát Start Plan, và kiểm tra target cụ thể trước Run Selected/Run Race.

## 8. Lấy ID, lưu và xuất cấu hình

Các dropdown Target hiển thị tên và tự giữ ID. Các ô Setup Flow ID, Until Before Step ID, fixed Auth Profile ID và bảng Post-checks cần ID nội bộ. UI hiện chưa có picker cho tất cả những ô này.

Cách lấy ID mà không sửa persistence:

1. Vào **Project**, chọn export mode `SANITIZED_EXPORT` hoặc `EXCLUDE_SECRETS` cho phần Authentication/Access Testing.
2. Bấm **Export Project**, lưu JSON tại nơi phù hợp.
3. Mở JSON, tìm đúng tên object. Đọc trường `id` của object đó, không lấy ID của object khác trong phần lân cận.
4. Với flow, tìm `flows` → flow có `name` đúng → `id`; trong `steps`, tìm step cần cắt → `id`.
5. Với Template/Auth Profile/Data Profile, tìm tên tương ứng trong phần access workspace và lấy `id`.
6. Copy ID vào dialog, Validate để phát hiện reference sai trước run.

Đây là cách tra ID cấu hình, không tạo ra Scenario Instance runtime. Các mode export trên kiểm soát secret trong phần Authentication/Access Testing; request capture/flow của bạn có thể chứa dữ liệu đã ghi trực tiếp nên vẫn cần review file trước chia sẻ. Chọn FULL_EXPORT khi cần bản có secret và hiểu đúng nội dung được lưu.

Project persistence giữ các cấu hình dùng lại; runtime scenarios không thuộc snapshot configuration export. Flow Target snapshot không tự cập nhật khi sửa Flowise. Sau thay đổi request/rules nguồn, tạo snapshot mới và đổi Flow Target của row, rồi Validate/Dry Run lại.

## 9. Checklist trước một plan nâng cao

- Baseline chạy được với actor có quyền; object ID trong request đúng với bài test.
- Auth thuộc target actor; object owner và setup actor được chọn có chủ đích.
- ProcessorId/CSRF được tạo trong session của đúng actor, không ghép context Alice với cookie Bob.
- Setup/teardown có auth/data/scenario Inject Rules cần thiết; Extract Rule dùng `scenario.*` cho output runtime.
- Snapshot Flow Target đúng Target Step/Run Mode và không stale so với nguồn muốn dùng.
- Expected và Evaluation Profile thể hiện policy nghiệp vụ; 200/302 không được diễn giải máy móc.
- Post-check có assertions đúng cho riêng baseline và row bị chặn; teardown chạy theo outcomes cần dọn.
- Placeholder không được tự URL/JSON-escape ở mọi vị trí khi render Template; kiểm tra request cuối hoặc dùng Inject Rules phù hợp trong Flow Target.
- Đã review warnings/dry run và một live row trước khi bật nhiều row/worker.

Quay lại [hướng dẫn chính](ACCESS_TESTING.md) hoặc [chọn đúng request trong flow](ACCESS_FLOW_TARGETS.md).
