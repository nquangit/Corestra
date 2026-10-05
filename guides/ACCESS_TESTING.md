# Access Testing: hướng dẫn từ cơ bản đến nâng cao

Tài liệu này hướng dẫn thao tác trên UI hiện tại của Corestra. Ví dụ xuyên suốt: **Alice** và **Bob** là hai user cùng role; Alice sở hữu `acct-alice-001`. Ta kiểm tra Alice đọc được tài khoản của mình và Bob có bị chặn khi đọc tài khoản đó hay không.

Tên nút và trường giữ nguyên tiếng Anh để đối chiếu với extension. Hostname, ID và token trong ví dụ là dữ liệu mẫu, cần thay bằng dữ liệu của ứng dụng đang kiểm thử.

## Mục lục

1. [Các tab và thứ tự thiết lập](#1-các-tab-và-thứ-tự-thiết-lập)
2. [Chuẩn bị đúng actor và auth](#2-chuẩn-bị-đúng-actor-và-auth)
3. [Tạo Data Profile từng bước](#3-tạo-data-profile-từng-bước)
4. [Tạo target là một request trong Templates](#4-tạo-target-là-một-request-trong-templates)
5. [Đưa flow vào và chọn request cần test](#5-đưa-flow-vào-và-chọn-request-cần-test)
6. [Tạo và chạy Matrix row đầu tiên](#6-tạo-và-chạy-matrix-row-đầu-tiên)
7. [Evaluation Profiles: định nghĩa đúng kết quả](#7-evaluation-profiles-định-nghĩa-đúng-kết-quả)
8. [Đọc Results và xuất bằng chứng](#8-đọc-results-và-xuất-bằng-chứng)
9. [Sinh nhiều row và chạy plan](#9-sinh-nhiều-row-và-chạy-plan)
10. [Chuyển sang workflow nâng cao](#10-chuyển-sang-workflow-nâng-cao)
11. [Chẩn đoán và giới hạn UI hiện tại](#11-chẩn-đoán-và-giới-hạn-ui-hiện-tại)

Hướng dẫn chuyên sâu:

- [Login, Auth Provider và nhiều Auth Profile](ACCESS_AUTH_SETUP.md).
- [Flow Targets, chọn step, chạy prefix hoặc toàn flow](ACCESS_FLOW_TARGETS.md).
- [Setup, scenario, post-check, teardown, race và import](ACCESS_ADVANCED.md).
- [Cookie, Bearer, CSRF, API key và refresh](AUTH_RECIPES.md).
- [Inject template và cú pháp transform](TRANSFORMS.md).
- [Keep-alive](KEEP_ALIVE.md) và [context gắn với session](SESSION_RENEWAL.md).

## 1. Các tab và thứ tự thiết lập

Auth Providers và Auth Profiles nằm ở tab gốc **Authentication**, dùng chung với Session Handler. Bên trong **Access Testing** có:

| Tab | Dùng để làm gì? | Chuẩn bị trước |
| --- | --- | --- |
| **Data Profiles** | Lưu dữ liệu object và chủ sở hữu: accountId, documentId, tenantId… | Actor nếu object có owner |
| **Templates** | Lưu một request độc lập và thử render placeholder | Request mẫu; actor/data để preview |
| **Flow Targets** | Lưu snapshot flow, chọn response của step cần đánh giá | Flowise flow có các request đúng thứ tự |
| **Evaluation Profiles** | Định nghĩa dấu hiệu ALLOW/DENY, dữ liệu nhạy cảm và masking | Response thực tế của ứng dụng |
| **Matrix** | Ghép target + actor gửi request + data + expected; chạy từng row/plan | Actor và một Template hoặc Flow Target |
| **Results** | Đọc status, verdict, request/response đã redact và tạo finding draft | Kết quả chạy hoặc dry run |
| **Guide** | Checklist ngắn trong extension | Không có |

Toolbar chung:

- **Manage Authentication…** chuyển sang phần auth dùng chung.
- **Validate Access Project** kiểm tra cấu hình/reference/placeholder, không gửi HTTP.
- **Quick Guide** mở tab Guide.

Thứ tự dễ làm nhất:

```text
Authentication → Data Profiles → Templates hoặc Flow Targets
→ Evaluation Profiles nếu cần → Matrix → Validate → Dry Run → Live → Results
```

Không cần tạo tất cả loại đối tượng cho một bài test đơn giản. Một endpoint GET có thể chỉ cần 2 Auth Profiles, 1 Data Profile, 1 Template và 2 Matrix rows. Setup, post-check và teardown để `NONE`/trống nếu không có nhu cầu.

## 2. Chuẩn bị đúng actor và auth

### 2.1. Đã có login flow và provider

Tái sử dụng Auth Provider đang chạy ổn. Tạo một Auth Profile cho từng user và liên kết cùng provider:

| Profile | Role mẫu | Credentials | State |
| --- | --- | --- | --- |
| Alice | `CUSTOMER` | username/password của Alice | Token/cookie riêng của Alice |
| Bob | `CUSTOMER` | username/password của Bob | Token/cookie riêng của Bob |

Provider mô tả cách login; profile xác định **login bằng user nào**. Role chỉ là nhãn để phân loại/sinh Matrix, không làm server cấp quyền tương ứng.

Matrix chọn `Target Auth Profile = Bob` thì runtime bảo đảm auth cho Bob, inject auth của Bob và gửi target. Data Profile có owner Alice không chuyển target request sang Alice. Đây chính là cấu hình kiểm tra truy cập chéo.

Xem [hướng dẫn auth từng bước](ACCESS_AUTH_SETUP.md) nếu chưa có provider. Auth Provider injection hỗ trợ `HEADER`, `COOKIE`, `QUERY`; ví dụ Bearer là `Authorization = Bearer {{auth.accessToken}}`.

### 2.2. Bắt đầu nhanh bằng token/cookie đã có

Nếu muốn hiểu Matrix trước khi thiết lập login tự động:

1. **Authentication > Auth Profiles > Add**.
2. Name `Alice`, Role `CUSTOMER`, Provider `Manual`, bật **Enabled**.
3. Trong **Headers**, **Add Row**: key `Authorization`, value là toàn bộ `Bearer <token-của-Alice>`.
4. Nếu dùng cookie, trong **Cookies** thêm key là tên cookie thực, ví dụ `SESSION`, value là giá trị cookie; không nhập `Cookie:` hay `SESSION=` vào value.
5. Nếu request cần placeholder auth ngoài header/cookie, thêm key `accessToken` trong **Auth Variables** và value token thuần. Headers không tự tạo biến `auth.accessToken`.
6. Lưu, lặp lại với Bob và token/cookie của Bob.

Manual không tự login/refresh. Muốn kiểm tra anonymous, tạo profile `Anonymous`, Provider `Manual`, để Headers/Cookies/Auth Variables trống và bảo đảm target không còn auth hardcoded.

### 2.3. Những gì không phải auth

- Object ID thuộc Alice nằm trong **Data Profile**, không đặt trong credentials của Bob.
- Nonce, transactionId hoặc token một lần tạo bởi setup nằm trong `scenario.*`.
- Project Session Handler không cần thêm rule riêng để Access Testing chạy. Transport Access Testing cách ly actor, tránh session handler của Burp ghi đè actor của row.

## 3. Tạo Data Profile từng bước

### 3.1. Tạo dữ liệu tài khoản của Alice

1. Vào **Access Testing > Data Profiles > Add**.
2. Điền **Name**: `Alice account`.
3. **Owner Auth Profile**: chọn `Alice`.
4. Bật **Enabled**.
5. Trong bảng **Values**, bấm **Add Row** và điền:

| Key | Value mẫu |
| --- | --- |
| `accountId` | `acct-alice-001` |
| `customerId` | `cust-alice-001` |
| `tenantId` | `tenant-A` |

6. Kết thúc chỉnh ô bằng Enter/click ra ngoài, rồi **OK**.

Trong **Values** nhập `accountId`, không nhập `data.accountId`. Khi render, Corestra tự cung cấp `{{data.accountId}}`. Nếu nhập key `data.accountId`, bạn sẽ tạo một key khác với key mà template đang dùng.

`Owner Auth Profile` là metadata/liên kết actor sở hữu object. Nó không phải một dòng Values tên `ownerAuthProfileId`. Chọn owner trong dropdown, không nhập tên Alice vào bảng thay cho dropdown.

### 3.2. Tạo dữ liệu của Bob và dữ liệu không có owner

Lặp lại với `Bob account`, owner Bob, `accountId = acct-bob-002`. Cùng schema key giúp một target chạy với nhiều object mà không sửa request.

Để trống Owner khi dữ liệu không thuộc một actor cụ thể, ví dụ mã sản phẩm chung. Khi Setup/Post-check/Teardown dùng `DATA_OWNER`, phải có owner để runtime chọn được actor. Generator owner-vs-others cũng cần owner để suy luận quan hệ.

### 3.3. Nên chia profile như thế nào?

- Một profile chứa một bộ dữ liệu dùng cùng nhau trong bài test, ví dụ accountId + customerId + tenantId của cùng object.
- Nhiều account của Alice: tạo `Alice account 1`, `Alice account 2`, cùng owner Alice, cùng key `accountId`.
- Giá trị ổn định dùng `data.*`. Giá trị tạo mới cho từng lần test dùng setup/extract `scenario.*`.
- **Edit** sửa profile; Matrix lưu liên kết ID nên lần chạy sau dùng giá trị mới. **Delete** có thể làm row tham chiếu tới object không còn tồn tại; dùng Validate để phát hiện.

## 4. Tạo target là một request trong Templates

### 4.1. Khi nào chọn Template?

Chọn Template khi request chạy độc lập sau khi đã có auth, ví dụ `GET /api/accounts/{accountId}`. Template không chạy các step đứng trước/sau request trong flow và không mang theo Extract/Inject Rules của Flowise.

Nếu target cần các request trước để lấy processorId/CSRF/transactionId, xem phần Flow Targets hoặc cấu hình Setup riêng.

### 4.2. Tạo thủ công: ví dụ có thể làm theo ngay

1. **Templates > Add Manual Template**.
2. Điền:

| Trường | Giá trị mẫu |
| --- | --- |
| Name | `Read account` |
| Method | `GET` |
| Scheme | `https` |
| Host | `app.example.test` — chỉ hostname, không thêm `https://` |
| Port | `443` |
| Path / Query | `/api/accounts/{{data.accountId}}` |
| Operation Type | `READ_ONLY` |
| State changing | Không chọn |
| Consumable | Không chọn |
| Description | `Read account by accountId` |

3. **Headers, one per line**:

```http
Host: app.example.test
Accept: application/json
```

4. Body để trống, **OK**.

Ví dụ này để auth đến từ profile/provider. Không copy token/cookie Alice cố định vào template cần chạy bằng Bob. Với JSON body, nhập JSON hợp lệ và header Content-Type tương ứng.

Operation Type/State changing/Consumable là metadata hỗ trợ phân loại, generator và validation; đánh dấu READ_ONLY không tự đổi method hoặc ngăn request ghi dữ liệu.

### 4.3. Tạo từ Burp hoặc từ một step

| Điểm bắt đầu | Thao tác | Kết quả |
| --- | --- | --- |
| Burp request/response | Context menu Corestra > **Add to Access Templates** | Copy request vào Templates |
| Step trong Flowise | Chuột phải step > **Convert Step to Access Template** | Copy riêng Stored Request của step |
| Step trong Flowise | **Add Step as Matrix Target** | Tạo/reuse Template của step và tạo một row |
| Tab flow trong Flowise | **Create Templates from Flow Steps** | Một Template độc lập cho mỗi step enabled |

**Add Step as Matrix Target không có nghĩa là chạy flow đến step đó.** Nó tạo target kiểu request template. Để chạy prerequisites, dùng Flow Target hoặc Setup.

Convert chỉ copy request gốc, không tự chuyển `Inject Rule = data.accountId` thành placeholder. Nếu request gốc vẫn có `acct-alice-001`, đổi Data Profile sang Bob cũng chưa đổi accountId. Chuẩn bị placeholder trực tiếp trong request trước convert, hoặc tạo Manual Template đã parameterize.

UI Templates hiện có Add/Import/Delete/Preview, chưa có Edit/Save cho request đã lưu. Hai vùng Original/Rendered Preview là để xem. Muốn thay request đã lưu bằng UI, tạo Template mới rồi **Matrix > Edit Row** đổi Template của row. Không sửa flow gốc và mong Template cũ tự cập nhật.

### 4.4. Preview đúng actor và data

1. Chọn Template trong bảng.
2. Dropdown **Auth** chọn Bob; **Data** chọn Alice account.
3. Bấm **Preview**.
4. Xem **Rendered Preview**: path phải chứa `acct-alice-001`; nếu request có placeholder `auth.*`, chúng phải lấy giá trị từ state của Bob.
5. Bảng **Unresolved Variables** phải không còn biến cần thiết bị thiếu.

Preview chỉ render placeholder trong request gốc: nó chưa áp dụng Injection của Auth Provider hoặc bảng Headers/Cookies của Auth Profile. Với ví dụ Manual profile ở trên, Authorization được thêm khi **Matrix Dry Run/live**, chưa xuất hiện ở Templates Preview nếu request gốc không có header đó.

Preview không gửi HTTP, không login/refresh và không tự tạo scenario. Nếu profile chưa có cached state, `auth.*` có thể unresolved; nhập state mẫu trong Manual profile hoặc acquire auth bằng một lần chạy live rồi preview lại.

Các bảng **Env / Scenario / Legacy** chỉ là đầu vào cho Preview đang mở. Chúng không được tự chuyển sang Matrix live run. Bỏ **Compatibility mode** cho bài mới để dùng scope rõ ràng.

## 5. Đưa flow vào và chọn request cần test

Flow mẫu:

```text
1. GET /open-form       → extract scenario.processorId
2. POST /prepare       → extract scenario.transactionId
3. POST /confirm       ← request cần kiểm tra quyền
4. GET /status
```

Muốn test step 3 và chạy 1→2→3:

1. **Flow Targets > Add Flow Target**.
2. Chọn đúng **Flow**, rồi dropdown **Target Step** chọn step `POST /confirm`.
3. **OK**. Target được lưu với mode `STOP_AT_STEP`.
4. Chọn target vừa tạo, xem **Executed Steps Preview**. Dấu `*` đánh dấu step được đánh giá; bước 4 không nằm trong chuỗi chạy.
5. Bấm **Add as Matrix Target**, chọn actor/data/Expected, lưu.
6. Vào Matrix, **Edit Row** để kiểm tra lại target và actor trước khi chạy.

Target Step mặc định của dialog là step enabled cuối, vì vậy hãy chọn lại nếu test request ở giữa flow. Matrix dùng snapshot đã lưu, không có một ô Target Step riêng để tùy ý đổi step trên cùng row.

| Run Mode | Các request được gửi | Response dùng để đánh giá |
| --- | --- | --- |
| `STOP_AT_STEP` | Step enabled từ đầu đến target, bao gồm target | Target Step đã chọn |
| `FULL_FLOW_EVALUATE_STEP` | Toàn bộ các step enabled | Vẫn là Target Step đã chọn, không phải response cuối flow |

Để chọn mode thứ hai, dùng **Create Matrix Rows from Flow**: chọn Flow/Target Auth/Data, bỏ Use ở các step không cần, đặt Expected và Run Mode cho từng dòng step. Chọn nhiều step tạo nhiều Matrix rows độc lập, không phải một lần chạy chung rồi đánh giá mọi step.

Ví dụ tick step 2 và 3 với STOP_AT_STEP: row thứ nhất chạy 1→2; row thứ hai lại chạy 1→2→3. Nếu bước prepare tạo object, có thể tạo nhiều object trong các lần chạy này.

Chi tiết thao tác, auth cho prefix, snapshot stale và chọn đúng request: [ACCESS_FLOW_TARGETS.md](ACCESS_FLOW_TARGETS.md).

## 6. Tạo và chạy Matrix row đầu tiên

### 6.1. Row control: Alice đọc object của Alice

1. **Matrix > Add Row**.
2. Điền Name `Alice reads Alice account`.
3. **Target Type**: `REQUEST_TEMPLATE`.
4. Tab **Target**:

| Trường | Chọn |
| --- | --- |
| Template | `Read account` |
| Flow Target | Không dùng khi Target Type là REQUEST_TEMPLATE |
| Target Auth Profile | Alice |
| Data Profile | Alice account |
| Environment ID | Để trống cho ví dụ này |
| Expected Result | `ALLOW` |
| Evaluation Profile | Để trống nếu status HTTP đã đủ rõ |

5. Tab **Setup**: Setup Mode `NONE`.
6. **Post-checks**: để trống.
7. **Teardown**: Teardown Mode `NONE`.
8. **Enabled**: có thể bỏ chọn trong lúc chuẩn bị; bật khi muốn row tham gia Start Plan.
9. **OK**.

Nếu chọn `FLOW_TARGET`, chọn dropdown Flow Target ở tab Target thay cho Template; actor/data/expected dùng cùng cách.

`Environment ID` là ID/nhãn gắn với run/auth context, không phải URL server và không tạo một bảng env variables. UI Matrix hiện gửi map Env/Scenario/Legacy trống; các bảng Preview của Templates không cấp biến cho live Matrix.

### 6.2. Row BOLA: Bob đọc object của Alice

1. Chọn row control, bấm **Duplicate Row**.
2. Chọn bản copy, **Edit Row**.
3. Name `Bob reads Alice account`.
4. Target Auth Profile = Bob, **Data Profile vẫn là Alice account**.
5. Expected Result = `DENY`.
6. Giữ target và các phần Setup/Post-check/Teardown như row control, **OK**.

Nếu đổi cả actor và Data Profile thành Bob, bạn đang kiểm tra Bob đọc object của chính Bob, không còn là truy cập chéo.

### 6.3. Validate và Dry Run

1. Bấm **Validate Access Project**, đọc lỗi và gợi ý sửa.
2. Trong Matrix tick **Dry Run / Render Only**.
3. Chọn row control, **Run Selected**; lặp lại với row Bob.
4. Mở Results, chọn từng result, kiểm tra ID object và cấu trúc auth của request đã render.

Dry Run không gửi login, setup, target, post-check hoặc teardown. Nó không sinh output mới của extractor và không chứng minh server chấp nhận request. Flow có output runtime từ bước trước có thể còn unresolved trong dry run; xem hướng dẫn Flow Targets.

Results đã redact token/password/cookie; đừng dùng sự giống nhau của chuỗi `<redacted>` để kết luận hai actor đang dùng cùng token. Xác nhận profile và login state ở Authentication hoặc xem traffic trong Burp nếu cần đối chiếu thực tế.

### 6.4. Chạy live

1. Bỏ tick **Dry Run / Render Only**.
2. Chạy **Run Selected** cho row Alice trước để biết baseline hoạt động.
3. Sau đó chạy row Bob.
4. Đọc response thực tế và verdict ở Results.

**Run Selected** là thao tác chạy row đang chọn, kể cả row đang disabled. **Enabled** dùng để chọn row tham gia **Start Plan**; đừng coi disabled row là khóa chặn một lần chạy thủ công.

Với provider flow-backed, live run sẽ acquire/refresh auth theo policy khi cần. Manual profile dùng state đã nhập. Access Testing không chuyển actor chỉ vì object có owner khác.

## 7. Evaluation Profiles: định nghĩa đúng kết quả

### 7.1. Expected Result khác Verdict

`Expected Result` là điều **bạn mong đợi** theo thiết kế quyền. `Verdict` là nhận định về **response thực tế so với mong đợi**:

| Expected | Response | Verdict status-only |
| --- | --- | --- |
| ALLOW | 200 / 201 / 204 | OK |
| DENY | 401 / 403 / 404 | OK |
| DENY | 200 / 201 / 204 | SUSPICIOUS |
| ALLOW | 401 / 403 / 404 | SUSPICIOUS |
| Bất kỳ | 3xx | REVIEW |
| Bất kỳ | 5xx | ERROR |

Các status khác cần review theo evaluator. `OK` cho row Expected DENY nghĩa là **đã bị chặn đúng mong đợi**, không phải request được phép truy cập. 401 do token hết hạn chưa chứng minh object authorization đúng; trước hết phải bảo đảm actor đã đăng nhập hợp lệ.

### 7.2. Ứng dụng trả 200 dù không có quyền

Response từ chối:

```json
{"success":false,"error":"FORBIDDEN"}
```

1. **Evaluation Profiles > Add**.
2. Name `Account permission`, bật Enabled.
3. Trong **Allow Indicators**, nhập:

```text
JSON_PATH_EQUALS|success|true|Application success
```

4. Trong **Deny Indicators**, nhập:

```text
JSON_PATH_EQUALS|error|FORBIDDEN|Application deny
STATUS_CODE_IN||401,403,404|HTTP deny
```

5. Sensitive Fields/Masking Rules để trống cho ví dụ này, **OK**.
6. **Matrix > Edit Row** cả hai row, chọn **Evaluation Profile = Account permission**.
7. Chạy lại và kiểm tra reason.

Cú pháp mỗi dòng: `TYPE|SELECTOR|EXPECTED_VALUE|NAME`. Dùng đúng tên enum, không thêm `{{...}}` để mong evaluator tự render Expected. JSON path của Evaluation Profile là dot path như `result.success`, không phải JSONPath đầy đủ `$..`, filter hay wildcard.

| TYPE | SELECTOR | EXPECTED_VALUE |
| --- | --- | --- |
| STATUS_CODE_IN | Để trống | `200,201,204` hoặc `200-299` |
| BODY_CONTAINS / BODY_NOT_CONTAINS | Để trống | Chuỗi cần tìm/không tìm |
| HEADER_EXISTS | Tên header | Để trống |
| HEADER_VALUE_CONTAINS | Tên header | Chuỗi cần có trong header |
| JSON_PATH_EQUALS | Dot path | Giá trị text/number/boolean dạng văn bản |
| JSON_PATH_EXISTS / JSON_PATH_NOT_EXISTS | Dot path | Để trống |

Trong mỗi nhóm, chỉ cần **một indicator match**; các dòng không phải điều kiện AND. Khi cả Deny và Allow match, Deny được xét trước. Vì vậy không dùng `STATUS_CODE_IN||200|...` làm Allow duy nhất nếu response deny cũng trả 200; dùng dấu hiệu nghiệp vụ.

Nếu không indicator nào match, evaluator quay về đánh giá status. Profile không mặc nhiên biến mọi response không khớp thành DENY. Với response 302 login, chỉ thêm indicator Location nếu đã xác nhận ý nghĩa của redirect đó.

### 7.3. MASKED và các expected nâng cao

Đặt Expected `MASKED` và cấu hình Sensitive Fields/Masking Rules để kiểm tra dữ liệu nhạy cảm bị ẩn; xem [ví dụ masking](ACCESS_ADVANCED.md#4-kiểm-tra-masking).

`READ_ONLY`, `CUSTOM` và `REVIEW` chưa tự suy luận đầy đủ quyền nghiệp vụ. Chọn chúng không tự kiểm tra database hoặc tính toán side effect. Dùng post-check và đọc evidence khi cần kết luận trạng thái sau request.

## 8. Đọc Results và xuất bằng chứng

1. Mở **Results**, chọn result tương ứng Test Case.
2. Đọc **Status** trước: COMPLETED chỉ nói run hoàn thành; FAILED/INVALID/SKIPPED cần xem reason.
3. Đọc **Verdict**: OK/SUSPICIOUS/REVIEW/ERROR/NOT_RUN.
4. Đối chiếu **Redacted Request**: endpoint, method, object ID, actor dự kiến, biến còn nguyên placeholder hay không.
5. Đọc **Redacted Response**: status và nội dung thật, không chỉ một số 200/403.
6. Vùng **Post-checks** còn hiển thị kết quả post-check và teardown nếu đã cấu hình.

Với Flow Target, request/response chính là step được chọn làm target. Evidence từng step nằm trong dữ liệu evidence và finding draft; UI Results hiện không có một viewer riêng để duyệt từng step như Flow Log của Flowise.

**Create Finding Draft** dựng Markdown từ result và evidence. Đọc/chỉnh nội dung trước khi dùng làm báo cáo. Dialog có **Save Draft**, **Copy Markdown** và **Save Markdown to File**.

Để lưu cấu hình/kết quả, vào **Project > Export Project**. Chọn export mode phù hợp: SANITIZED_EXPORT redact; EXCLUDE_SECRETS loại bỏ secret; FULL_EXPORT giữ dữ liệu đầy đủ. Project export không tương đương export một finding. Các scenario runtime không được giữ trong project configuration export, nên đừng coi import project là khôi phục token một lần còn dùng được.

## 9. Sinh nhiều row và chạy plan

### 9.1. Generate Matrix cho request Templates

1. **Matrix > Generate Matrix**.
2. Chọn Templates, Data Profiles, Auth Profiles cần dùng trong các danh sách; bỏ chọn những đối tượng không thuộc đợt test.
3. Chọn Strategy; cấu hình Expected và role/tenant/branch keys nếu strategy dùng chúng.
4. Xem preview: Template, Target Auth, Data Profile, Expected, Reason, Confidence.
5. Chỉnh Expected của các row cần thiết, chọn các dòng muốn thêm và xác nhận.
6. Các row sinh tự động mặc định disabled; review và **Edit Row > Enabled** trước Start Plan.

| Strategy | Cách sử dụng |
| --- | --- |
| OWNER_VS_OTHERS | Owner baseline và actor khác đọc/thao tác object của owner |
| ANONYMOUS_ACCESS | Kiểm tra profile anonymous với target/data đã chọn |
| ROLE_ESCALATION / CROSS_ROLE | So sánh actor có role khác nhau; Expected phải theo policy ứng dụng |
| CROSS_TENANT / CROSS_BRANCH | So sánh tenantId/branchId; cần metadata đúng keys |
| MAKER_CHECKER | Các tình huống maker/checker; cấu hình nhãn Role đúng |
| OBJECT_OWNERSHIP_VARIANTS | Các biến thể object ownership từ các dữ liệu đã chọn |

Generator đề xuất bài test, không biết toàn bộ business policy. ADMIN không luôn được phép đọc mọi object; giữ Expected REVIEW nếu chưa xác minh.

**Templates > chuột phải > Create Owner-vs-Others Matrix Rows** là shortcut cho template đang chọn. Generate Matrix dùng danh sách Templates; với flow nhiều bước dùng **Flow Targets > Create Matrix Rows from Flow**, không mong generator tự chuyển Flow Target thành request đơn.

### 9.2. Start Plan

1. Chỉ bật Enabled trên các row đã review.
2. Kiểm tra checkbox Dry Run / Render Only trước khi Start Plan; nó áp dụng cho plan.
3. Bấm **Start Plan**, đọc preview/xác nhận.
4. Plan chạy các row enabled **tuần tự**. Progress và **Copy Summary** tổng hợp kết quả.
5. **Stop** yêu cầu dừng trước row kế tiếp; không coi nó là hủy tức thì HTTP request hiện tại.

Trong lúc plan chạy, các tab chỉnh cấu hình bị khóa. Matrix row duplicate không tự thay expected theo actor mới; luôn kiểm tra lại fields.

## 10. Chuyển sang workflow nâng cao

| Nhu cầu | Cấu hình nên dùng |
| --- | --- |
| Chạy các bước trước bằng cùng target actor | Flow Target, STOP_AT_STEP |
| Setup object bằng Alice, thử dùng bằng Bob | Template target + Setup Auth Binding DATA_OWNER; hoặc setup riêng và target flow được tách đúng |
| Mở trang lấy processorId mới của Bob rồi gửi action bằng Bob | Setup TARGET_ACTOR hoặc prefix trong Flow Target của Bob |
| Tạo object/nonce một lần cho mỗi row | RUN_SETUP_FLOW hoặc RUN_PREFIX, FRESH_PER_TEST |
| Target trả 200 nhưng muốn biết có đổi dữ liệu thật không | Post-check bằng owner hoặc fixed profile, assertions trên trạng thái sau |
| Dọn object sau bài test | Teardown RUN_FLOW, chọn actor và outcome cần chạy |
| Chạy cả flow nhưng đánh giá request ở giữa | FULL_FLOW_EVALUATE_STEP |
| Kiểm tra masking | MASKED + Evaluation Profile |
| Bắn đồng thời một target để kiểm tra race | Run Race cho REQUEST_TEMPLATE, chọn scenario/auth mode |

Các cấu hình đầy đủ và ví dụ có ở [ACCESS_ADVANCED.md](ACCESS_ADVANCED.md). Chỉ chọn những phần cần cho workflow; không điền Setup/Post-check/Teardown chỉ vì dialog có sẵn.

## 11. Chẩn đoán và giới hạn UI hiện tại

| Hiện tượng | Kiểm tra / xử lý |
| --- | --- |
| Đổi Data Profile nhưng URL vẫn là ID cũ | Request có placeholder/data inject chưa; convert step không mang Inject Rules |
| Test sai request trong flow | Target Step của snapshot; dấu `*` trong Executed Steps Preview; không dùng shortcut Last Step khi cần step ở giữa |
| Source flow đổi nhưng run vẫn dùng request cũ | Stale = Yes; tạo snapshot mới và đổi Flow Target trong Matrix row |
| Bob đọc được object của Bob, không phải Alice | Data Profile của row có bị đổi sang Bob cùng lúc đổi actor không |
| 401/302 nhưng Expected DENY lại có vẻ ổn | Kiểm tra actor có auth hợp lệ; token hết hạn chưa chứng minh authorization đúng |
| Dry Run thiếu biến do step trước tạo | Dry Run không gửi prerequisites; dùng cached/mẫu cho template hoặc kiểm tra một live run có kiểm soát |
| `data.data.accountId` / thiếu `data.accountId` | Key trong Data Profile phải là accountId, không có data. |
| `auth.*` thiếu trong Preview | Chưa có cached state hoặc nhập nhầm prefix trong Auth Variables |
| Setup DATA_OWNER không chọn được actor | Data Profile thiếu Owner Auth Profile |
| 200 nhưng evaluator báo SUSPICIOUS | Server dùng body để báo deny; cấu hình Evaluation Profile nghiệp vụ |
| Các ô Setup Flow ID/Template ở Post-check không nhận tên hiển thị | Những ô này cần ID nội bộ; xem cách lấy ID trong hướng dẫn nâng cao |
| Preview có env nhưng Matrix báo missing env | Env trong Preview chỉ dùng preview; Environment ID không tự cấp biến |
| Run Selected vẫn chạy row disabled | Enabled lọc Start Plan, không chặn chạy riêng thủ công |

UI hiện chưa có Edit/Refresh snapshot trong Templates/Flow Targets, chưa có picker cho mọi ô ID nâng cao, chưa có chế độ tự đổi actor giữa từng step trong cùng Flow Target. Những trường hợp này có cách thiết lập riêng trong hai hướng dẫn chuyên sâu; không cần nhân bản login flow cho mỗi user.

Template capture/import và Flow Target mới có thể hiện **Enabled = false**. UI hiện chưa có nút toggle riêng cho những object này; validator có thể báo warning khi row tham chiếu Template disabled. Flag đó không thay thế Enabled của Matrix row và không chặn một row đã được chọn chạy thủ công. Luôn kiểm tra target và row trước khi gửi.
