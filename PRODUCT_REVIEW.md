# Corestra — rà soát tổng thể tính năng, UI/UX và lộ trình (2026-11-26)

## Phạm vi và cách làm

Chỉ đọc mã nguồn tại commit `0f3070e`, chia theo 4 mảng:
- Flowise
- Session Handler và traffic thật
- Access Testing và Authentication
- Manual Testing, Project, Parameters và chất lượng kỹ thuật

Mỗi phát hiện đều có vị trí `file:line`. Các phát hiện mức **High** đã được kiểm chứng lại trực tiếp
trong code (cột "KC" = đã kiểm chứng). Không chạy Burp thật, nên mọi đánh giá UI/UX dựa trên đọc
code Swing. Ảnh trong `screenshots/` từ tháng 5 vẫn là layout tab dọc cũ, không còn phản ánh UI
hiện tại.

Đường dẫn bên dưới tính từ `src/main/java/dev/nquangit/corestra/`.

---

## 1. Tổng quan: mảng nào ổn, mảng nào chưa

| Mảng | Đánh giá | Ghi chú ngắn |
| --- | --- | --- |
| Flow engine (retry, sleep, trigger, điều kiện) | **Ổn** | Có giới hạn rõ (retry ≤ 10, sleep ≤ 300 s, depth ≤ 5, phát hiện vòng lặp); retry quay lại đúng context; lỗi listener được cô lập |
| Extract (JSONPath, regex 3 phạm vi, raw bytes, transform) | **Ổn** | Phong phú; preview chạy ngoài EDT. Lỗi: rule bị tắt vẫn chạy (B1) |
| Inject | **Chưa tốt** | Query injection cẩn thận. Form/JSON injection làm hỏng dữ liệu (B6, B7); thiếu biến thì gửi nhầm giá trị cũ mà không báo (B2) |
| Capture request vào flow | **Chưa tốt** | Mất virtual Host, body nhị phân, HTTP version (B8) |
| Flowise UI | **Dùng được nhưng nặng thao tác** | Request editor chỉ đọc; không có nút Stop; kết quả chạy khó đọc |
| Chống tự xử lý lại traffic của chính Corestra, chống ghi đè state cũ | **Rất ổn** | Dùng nonce + message ID, generation counter, map có giới hạn |
| Session Handler | **Chưa tốt ở điểm cốt lõi** | Không đăng nhập lại khi nhận 401 (B3); login sai lặp vô hạn (B4); UI khó thiết lập |
| Access Testing: runner, race gate, strict rendering | **Khung tốt** | Pipeline rõ ràng, lỗi trả về dạng typed result |
| Access Testing: độ đúng của verdict | **Chưa đáng tin** | Identity rò giữa các actor (B5); evaluator không đọc được mảng JSON, không so với baseline (B10) |
| Access Testing: luồng UX | **Đứt gãy** | Template capture không bật lên được nên Generate Matrix ra 0 dòng (B9); Results khó đọc |
| Manual Testing | **Ổn về an toàn** | Kiểm tra fingerprint session trước khi gửi, chặn origin lạ. UI quá dày |
| Project import/export | **Có rủi ro dữ liệu** | Export Flow(s) không sanitize (B11); Merge vẫn thay hết dữ liệu khác (B12); không tương thích ngược (B13) |
| Parameters / wordlist | **Ổn** | Có giới hạn kích thước, redact ví dụ, ghi file kiểu atomic |
| Kỹ thuật | **Trung bình** | ~1000 test nhưng UI phủ ít; 5 god class; log chủ yếu qua `System.err` hoặc nuốt lỗi |

---

## 2. Lỗi và thiếu sót cần sửa, theo thứ tự ưu tiên

### Mức High — ảnh hưởng an toàn, độ đúng hoặc dữ liệu

| # | Vấn đề | Kịch bản hỏng | Vị trí | KC |
| --- | --- | --- | --- | --- |
| B1 | Extract rule đã **tắt** vẫn chạy trong Flowise và Auto-Trigger | Bỏ tick một extractor `token` cũ, nó vẫn ghi đè `token` mỗi lần chạy, trong khi bộ kiểm tra phụ thuộc lại tin là biến không được sinh ra | `flow/execution/StepExecutor.java:180`, `integration/AutoTriggerHttpHandler.java:507`; `ExtractionService` không kiểm tra `enabled()` | ✔ |
| B2 | Chạy Flowise thường: thiếu biến thì **gửi nhầm giá trị đã capture** và báo OK | Bấm Run Step ở step 2: `{{token}}` không có trong context, nên request gửi token cũ từ Proxy và vẫn báo SUCCESS. Diagnostic "skipped" bị bỏ đi, UI không hiển thị | `actions/RunFlowAction.java:105-116`, `StepExecutor.java:121-123`, `ui/flowise/ExecutionPanel.java:279` | ✔ (khớp với cách `apply()` hoạt động) |
| B3 | ✅ **Đã sửa 2026-11-26.** Failure rule + `targetRetryPolicy` do người dùng cấu hình trên Auth Provider **chỉ được áp dụng cho response của Access Testing/Race**, không áp dụng cho **traffic thật đi qua Session Handler**. Profile standalone (không liên kết actor) chỉ có TTL | Server thu hồi token trước TTL: Access Testing tự refresh/login lại theo policy, nhưng Repeater/Scanner đi qua Session Handler thì cứ nhận response thất bại cho tới khi hết TTL, trừ khi bật keep-alive (probe định kỳ). Không có gì sai trong rule người dùng đặt, chỉ là đường live không gọi tới chúng | `access/runner/TargetAuthFailureDetector.java` chỉ được dùng ở `SingleAccessTestRunner:406,1368` và `RaceRunner:159`; `integration/CorestraSessionHttpHandler.java:191-220` chỉ chạy extraction; `session/SessionManager.java:291-307` | ✔ |
| B4 | **Login sai bị lặp lại trên mọi request**, không backoff, mất luôn lý do lỗi | Sai password + Intruder 20 thread sinh ra hàng trăm request login, có nguy cơ **khóa tài khoản**; traffic còn bị xếp hàng sau lock | `SessionManager.java:375-388, 781-791, 724` | ✔ |
| B5 | **Identity đã capture rò sang actor khác** trong Access Testing | Capture request bằng admin rồi chạy actor Anonymous: request vẫn mang cookie admin, nên ra SUSPICIOUS giả. Actor "user" chỉ đặt `session=` thì cookie `remember_me=` của admin vẫn còn | `access/runner/ManualAuthApplier.java:58-92`, `AuthProviderRequestApplier.java:106-126` | ✔ |
| B9 | Template capture được lưu ở trạng thái **tắt** và **không có cách bật hay sửa** | Luồng chính "Proxy → Add to Access Templates → Generate Matrix" ra **0 dòng** | `ui/access/AccessUiController.java:667-676`, `access/matrix/MatrixGenerator.java:580`, `ui/access/TemplatesPanel.java:103-106` | ✔ |
| B10 | Evaluator **không đi được vào mảng JSON** và **không so với baseline** | Endpoint danh sách `[{"ssn":"123…"}]` vẫn được xếp OK ("absent or masked"). Path sai, 404 cho mọi actor thì mọi dòng DENY đều OK | `access/evaluate/BasicResultEvaluator.java:216-220, 176-178, 262-263` | ✔ (mảng) |
| B11 | **Export Flow(s) không sanitize** | Header `Authorization`/`Cookie`, giá trị inject tĩnh và response đã capture (Base64, có cả Set-Cookie) được ghi nguyên ra file, dù combobox "Access export: Sanitized" nằm ngay cạnh | `ui/project/ProjectPanel.java:748`, `persistence/ProjectSerializer.java:149-152` | ✔ |
| B12 | Import Project chọn **"Merge" vẫn thay toàn bộ** settings, Access workspace (gồm evidence) và session profiles | Gộp file của đồng nghiệp làm mất Auth/Data profile, evidence và session của mình. Hộp thoại chỉ hỏi về "existing flows" | `ProjectPanel.java:712-731` | ✔ |
| B13 | `FlowSerializer` báo lỗi khi gặp field lạ, rồi **cách ly và xoá** document flow | Mở project lưu bởi bản mới hơn bằng jar cũ: Flowise trống, dữ liệu nằm trong một ô cách ly duy nhất, không có UI để khôi phục. Hai serializer access/session đã tắt kiểm tra này, riêng flow thì chưa | `persistence/FlowSerializer.java:27`, `FlowRepository.java:64-66, 98-106` | ✔ |
| B14 | Setup tạo dữ liệu nhưng **teardown không chạy** khi auth hoặc render lỗi sau setup. Race ở chế độ fresh-per-worker chỉ teardown scenario đầu tiên | Race 10 worker × 10 iteration để lại 99 bản ghi rác trên hệ thống đích | `access/runner/SingleAccessTestRunner.java:375, 389, 2202-2222`, `access/race/RaceRunner.java:209, 654-660` | chưa |

### Mức Medium

| # | Vấn đề | Vị trí |
| --- | --- | --- |
| B6 | Form injection decode/encode lại cả body: mất key trùng (`a=1&a=2`), đổi encoding (hỏng body có ký HMAC); APPEND hoạt động như OVERWRITE; body có `%` lẻ (`100%`) làm `URLDecoder` ném lỗi và mất toàn bộ record của lần chạy | `http/inject/InjectionService.java:347, 386-411` |
| B7 | JSON injection luôn ghi giá trị dạng **string** (`123` thành `"456"`), không hỗ trợ mảng hay `$.`/`pointer:` dù phía extract có hỗ trợ; serialize lại body | `http/inject/JsonBodyInjector.java:70-101` |
| B8 | Capture xoá `Host` rồi dựng lại từ service (sai với virtual host); service không rõ thì thành `example.com`; body lưu bằng `bodyToString()` nên hỏng multipart/binary; luôn replay bằng HTTP/1.1 | `actions/CaptureRequestIntoFlowAction.java:128-147` |
| B15 | Không có nút **Stop** cho lần chạy Flowise (SLEEP 300 s × RETRY 10 khoá panel gần một giờ) | `ui/flowise/ExecutionPanel.java:377-387` |
| B16 | **Ghi chú (Notes) đang gõ dở bị xoá** mỗi khi controller phát thông báo, ví dụ lúc chạy xong | `ui/flowise/FlowWorkspacePanel.java:174-178` ✔ |
| B17 | **Clear State** khoá profile ngay trên EDT; nếu đang login thì UI Burp bị treo | `ui/session/SessionHandlerPanel.java:930`, `SessionManager.java:234` ✔ |
| B18 | Profile session mới **bật ngay lập tức**, dùng Burp scope và actor đầu tiên; mỗi lần sửa ô bảng là tự lưu và áp dụng ngay vào traffic | `SessionHandlerPanel.java:724-743, 441` |
| B19 | Chi phí trên mỗi request ở handler thread: parse lại `ProjectSettings` JSON mỗi lần, compile lại regex trigger, chuyển body sang string, duyệt mọi flow/step, ghi lại toàn bộ document profiles kể cả khi giá trị không đổi | `persistence/ProjectSettingsRepository.java:44-57`, `flow/StepTriggerRule.java:306`, `SessionManager.java:588` |
| B20 | Race: kết quả các worker ghi đè lẫn nhau (chỉ còn worker cuối); không có assertion "tối đa N thành công"; mỗi thread tự kết nối nên đồng bộ thời gian kém | `access/race/RaceRunner.java:490-523` |
| B21 | Access plan ghi **4 lần toàn bộ document** cho mỗi dòng, mỗi message lưu 3 bản, không giới hạn kích thước body. 300 dòng × 50 KB ra document ~45 MB bị ghi lại ~1200 lần | `access/runner/AccessPlanRunner.java:61, 123-126` |
| B22 | Redaction bỏ sót query (`?access_token=`), `access_token`/`refresh_token`/`client_secret`/`api_key` trong body, header `X-API-Key`/`X-Auth-Token`, và JWT trần | `access/security/RedactionService.java:13-15`, `DefaultSensitiveKeyMatcher` |
| B23 | Nhận diện vai trò theo chuỗi con: "Non-admin clerk" bị coi là admin | `access/matrix/MatrixGenerator.java:711-724` |
| B24 | OpenAPI importer: không đọc YAML, không resolve `$ref`, bỏ qua header param, placeholder biến số thành chuỗi | `access/importer/OpenApiAccessTemplateImporter.java:86, 261-345` |
| B25 | Import file đã sanitize sẽ lưu nguyên chữ `<redacted>` làm giá trị session thật mà không cảnh báo; parse lỗi thì âm thầm thay bằng mặc định | `persistence/ProjectSerializer.java:186-245` |
| B26 | Race nhẹ trên danh sách flow: `ArrayList` bị sửa trên EDT trong khi handler thread đọc qua `List.copyOf` | `ui/flowise/FlowWorkspaceController.java:34` |

### Mức Low (tóm tắt)

- Cảnh báo "Multiple session profiles matched" được tạo ra nhưng bị bỏ đi.
- Lỗi binding đi vào `System.err` thay vì `api.logging()`; có 59 khối `catch (… ignored)`.
- Trigger host match theo chuỗi con (`api.example.com` khớp cả `api.example.com.evil.net`).
- Token được làm mới qua extraction không gia hạn TTL.
- Xoá rule hoặc xoá request trong Manual không hỏi xác nhận.
- Hàm `sendToRepeater()` không còn ai gọi tới.
- Merge import không remap `targetFlowId`.
- Ghi file export không atomic.

---

## 3. UI/UX — có nên tối ưu không? **Có.** Đây là điểm yếu lớn nhất sau độ đúng của Access Testing.

### Vấn đề chung

- **Quá nhiều bước cho tác vụ cơ bản.**
  - "Capture login → extract token → inject → run" mất khoảng 15–20 click và 3–4 hộp thoại modal.
  - Nút Suggest inject chỉ khớp theo *tên*, nên `Authorization: Bearer …` không bao giờ được gợi ý cho biến `accessToken`.
  - Thiết lập "login → inject mọi traffic → re-login khi 401" phải đi qua 3 tab gốc (Flowise → Authentication → Session Handler), và bước cuối hiện chưa làm được.
  - Luồng Access cần khoảng 8 tab con và bị đứt ở bước template.
- **Không thấy được chuyện gì đã xảy ra.**
  - Bảng Execution chỉ hiện *số lượng* biến.
  - Không có chỗ xem tên/giá trị biến đã extract, rule bị bỏ qua, hay các lần retry/sleep.
  - Session profile chỉ hiện `name` hoặc `[off] name`, không có trạng thái hết hạn, lần login cuối hay lỗi cuối.
  - Nhãn trạng thái dùng chung bị "State updated" ghi đè liên tục.
- **Bảng rộng, sửa trực tiếp, tự lưu.** Bảng extraction của Session có 9 cột, cột Tools là ô text tự do. Sửa dở cũng được áp dụng ngay vào traffic.
- **Request editor của Flowise chỉ đọc.** Muốn đổi một tham số thì phải capture lại hoặc copy sang Manual Testing. Đây là điểm vướng lớn nhất.
- **Hầu như không có phím tắt.** Chỉ có Escape trong hộp thoại prompt; không có Delete, Ctrl+Enter hay mnemonic.
- **Không nhất quán.**
  - "Remove" lúc thì `danger`, lúc thì `neutral`.
  - "+ New Flow" và "+ New flow".
  - Có 15 nút `new JButton` bỏ qua `StyledButton`.
  - Trong Manual Testing, mọi nút đều neutral, kể cả Delete và Run all.
  - Nhãn "Access export" thực ra điều khiển cả flow và session.
- **Results của Access khó đọc.**
  - Cột đầu là `testCaseId` khó hiểu.
  - Không sort/filter theo verdict, không có cột actor/endpoint.
  - Message hiển thị bằng `JTextArea` thường thay vì editor của Burp, không có Send to Repeater.
  - Matrix không hiện verdict.
  - Bảng chỉ cho chọn một dòng, nên bật 60 dòng là 60 hộp thoại.

### Đề xuất UI/UX cụ thể (làm được dần)

1. **Flowise:**
   - Cho phép sửa request, kèm nút "Save request".
   - Thêm nút **Stop** và "Run from here".
   - Thêm panel **Variables/Diagnostics** cho mỗi lần chạy: biến đã extract (che giá trị nhạy cảm), rule bị bỏ qua, lịch sử action.
   - Run Step lấy biến từ lần chạy trước.
   - Thêm toggle **Strict inputs** cho mỗi flow.
2. **Session:**
   - Mỗi profile có một dòng sức khoẻ: state / hết hạn sau / kết quả auth cuối.
   - Profile mới mặc định tắt, phải bấm "Activate".
   - Sửa extraction qua form hoặc dialog theo loại extractor thay vì bảng 9 cột.
   - Có wizard "Provider → Profile → Scope".
3. **Access:**
   - Bật/sửa template và chọn nhiều dòng trong Matrix (bulk enable / set expected / run / delete).
   - Results có cột tên dòng, actor, verdict, kèm `TableRowSorter`, editor của Burp và Send to Repeater.
   - Có nút xuất báo cáo một chạm.
4. **Project:** tách khu "Project file" và "Flows file", mỗi khu có chế độ sanitize riêng (mặc định Sanitized). Import cho chọn Merge/Replace/Skip theo từng phần.
5. **Chung:**
   - Quy ước vai trò nút (Remove/Delete = danger, Add = primary).
   - Một component dùng chung cho trạng thái rỗng.
   - Phím tắt cơ bản (Delete, Ctrl+Enter = Run, F2 = rename).
   - Gom các dòng hướng dẫn dài vào phần thu gọn được.

---

## 4. Tính năng nên bổ sung hoặc phát triển

Xếp theo giá trị so với công sức. Mỗi mục đều gắn với một lỗ hổng đã quan sát trong code.

| Ưu tiên | Tính năng | Giải quyết | Công sức |
| --- | --- | --- | --- |
| 1 | **Áp dụng failure rule + `targetRetryPolicy` của Provider cho traffic thật trong Session Handler** (không hardcode 401: không có rule thì không làm gì), làm state mất hiệu lực theo policy, tùy chọn gửi lại 1 lần khi policy là `*_AND_RETRY_ONCE`; thêm failure rule tùy chọn cho profile standalone | B3, dùng lại đúng thiết kế đã có | Trung bình |
| 2 | **Backoff khi login thất bại** + lưu kết quả auth cuối + chỉ báo sức khoẻ profile | B4, rủi ro khoá tài khoản | Thấp |
| 3 | **Bỏ cookie/Authorization đã capture** khi tạo Access template (và một chế độ inject "thay hết") | B5, độ đúng của verdict | Thấp |
| 4 | **Bật/sửa template + thao tác hàng loạt trong Matrix** | B9, luồng Access bị đứt | Thấp |
| 5 | **Strict inputs + panel Diagnostics/Variables** trong Flowise | B2, khả năng quan sát | Thấp |
| 6 | **Sửa an toàn dữ liệu Project**: sanitize Export Flow(s), import theo từng phần, đọc được field lạ, nút khôi phục dữ liệu cách ly | B11–B13, B25 | Thấp → Trung bình |
| 7 | **Verdict dựa trên baseline** + **diff phản hồi giữa các actor** song song trong Results | B10, giảm false positive/negative | Trung bình |
| 8 | **Auto-correlation theo giá trị**: tìm giá trị đã extract trong các request sau (kể cả tiền tố `Bearer`, path segment) và đề xuất inject rule | Suggest chỉ khớp theo tên | Trung bình |
| 9 | **JSON injection có kiểu** (số/bool/null/object) + selector mảng/JSONPath; form injection giữ nguyên encoding và key trùng | B6, B7, test IDOR với ID kiểu số | Trung bình |
| 10 | **Fidelity khi capture**: giữ Host, body dạng bytes, HTTP version | B8 | Trung bình |
| 11 | **Teardown trên mọi lối thoát sau setup** + teardown cho từng scenario trong race | B14, rác trên hệ thống đích | Trung bình |
| 12 | **Race nâng cao**: assertion "tối đa N thành công", giữ kết quả mọi worker, thử `Http.sendRequests`/HTTP/2 để đồng bộ tốt hơn | B20 | Trung bình |
| 13 | **Trace session cho từng request** (profile khớp, ứng viên khác, rule áp dụng/bỏ qua) + log qua `api.logging()` | B19 (log), khả năng debug | Trung bình |
| 14 | **Xuất báo cáo cấp lần chạy** (Markdown/HTML/CSV) | Hiện chỉ có "Copy Summary" | Thấp |
| 15 | **Transform mới**: `base64urlDecode`, đọc claim JWT, `sha256`/`hmac` cho API có ký | API ký request, JWT | Thấp |
| 16 | **OpenAPI YAML + `$ref` + sinh ví dụ từ schema** | B24 | Trung bình |
| 17 | **Chế độ passive kiểu Autorize** (replay traffic thật với actor khác rồi diff) | Chỉ nên làm sau mục 3 và 7, vì dùng lại hai phần đó | Cao |

### Đầu tư kỹ thuật

- Cache `ProjectSettings`, compile sẵn regex trigger, bỏ qua ghi khi giá trị không đổi (B19).
- Gộp ghi persistence trong lúc chạy plan, giới hạn kích thước evidence (B21).
- Thêm JaCoCo + SpotBugs/Checkstyle vào `mvn verify`. Tách `ProjectPanel` import/export ra một `ProjectTransferService` có test.
- Tách các god class: `SingleAccessTestRunner` 2477 dòng, `AccessUiController` 1976, `MatrixPanel` 1619, `SessionHandlerPanel` 1339. Phần duplicate `jsonValue` giữa runner và evaluator phải sửa cùng lúc.
- Ghi rõ phiên bản Burp tối thiểu (pom đang pin Montoya `2026.4`).

---

## 5. Đề xuất thứ tự thực hiện

1. **Đợt 1 — sửa lỗi an toàn/độ đúng, công sức thấp:** B1, B2 (strict + diagnostics), B4, B5, B9, B11, B13, B16, B17, B18.
2. **Đợt 2 — tính năng cốt lõi:** re-login khi nhận 401 (B3), import theo từng phần (B12), evaluator hiểu mảng + baseline (B10), teardown (B14), form/JSON injection (B6, B7).
3. **Đợt 3 — UX:** sửa request trong Flowise, Stop, Results/Matrix mới, sức khoẻ session, xuất báo cáo.
4. **Đợt 4 — mở rộng:** auto-correlation, race nâng cao, trace session, OpenAPI, Autorize-style.
