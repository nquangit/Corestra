# Access Testing: đưa flow vào và chọn đúng request

[Quay về hướng dẫn chính](ACCESS_TESTING.md). Phần này tập trung vào câu hỏi: **flow có nhiều request, nhưng tôi muốn kiểm thử quyền tại đúng một request thì cấu hình thế nào?**

## 1. Ba thao tác dễ nhầm

| Thao tác | Target được tạo | Có chạy các request trước target? |
| --- | --- | --- |
| Flowise step > Convert Step to Access Template | Một request template | Không |
| Flowise step > Add Step as Matrix Target | Template + Matrix row | Không, trừ khi cấu hình Setup riêng |
| Flow Targets > Add Flow Target | Snapshot flow + Target Step | Có, theo Run Mode |

Một Flow Target giữ request và Extract/Inject Rules của các step trong snapshot. Một Template chỉ giữ request, metadata nguồn và placeholder đã có trong request; không mang bộ rules của step sang.

Vì vậy, nếu request chỉ chạy đúng sau khi mở trang để lấy processorId, đừng dùng Add Step as Matrix Target rồi mong các request mở trang được chạy tự động.

## 2. Chuẩn bị flow mẫu

Ví dụ flow `Account action`:

| Thứ tự | Tên step | Request | Vai trò |
| --- | --- | --- | --- |
| 1 | Open form | GET `/account/action-form` | Lấy processorId của actor hiện tại |
| 2 | Prepare | POST `/account/prepare` | Lấy transactionId |
| 3 | Confirm | POST `/account/confirm` | Request cần kiểm tra quyền |
| 4 | Read status | GET `/account/status` | Bước tiếp theo, tùy mục tiêu test |

Chuẩn bị trong Flowise:

1. Capture các request theo đúng thứ tự, đổi tên step dễ nhận ra.
2. Step 1 thêm Extract Rule cho giá trị processorId thực tế, target variable `scenario.processorId`.
3. Step 2 thêm Inject Rule cho processorId và accountId:

| Target | Selector mẫu | Value source | Variable key |
| --- | --- | --- | --- |
| REQUEST_FORM_FIELD | processorId | VARIABLE | `scenario.processorId` |
| REQUEST_FORM_FIELD | accountId | VARIABLE | `data.accountId` |

4. Response step 2 extract transactionId vào `scenario.transactionId`.
5. Step 3 inject transactionId vào đúng field, dùng `scenario.transactionId`.
6. Nếu step 4 dùng transactionId trong query, thêm REQUEST_QUERY_PARAMETER rule với key đó.
7. Xóa/sửa auth hardcoded gây nhầm actor. Auth Provider và Auth Profile của Matrix sẽ được apply vào từng request; auth cần trong body/path phải được cấu hình phù hợp trong request/rules.

Các selector ở ví dụ là tên field mẫu, cần thay theo request thật. Với JSON dùng REQUEST_JSON_PATH thay cho form. Với Bearer từ một biến, chọn TEMPLATE `Bearer {{auth.accessToken}}`; xem [Transforms](TRANSFORMS.md).

Flowise chạy thủ công để kiểm tra request/rules: **Execution > Run Context…** có thể chọn actor/data của cùng owner. Nó có kiểm tra owner consistency, nên không dùng manual Run Context của Flowise thay cho Matrix để chạy Bob với Data Profile của Alice. Access Matrix cho phép ghép actor/object khác owner vì đó là bài access test.

## 3. Test một request ở giữa flow

Mục tiêu: chạy Open form → Prepare → Confirm, đánh giá response của Confirm, không gửi Read status.

1. **Access Testing > Flow Targets > Add Flow Target**.
2. Dropdown **Flow** chọn `Account action`.
3. **Target Step** chọn `Confirm`, không để mặc định step cuối `Read status`.
4. **OK** tạo snapshot với mode `STOP_AT_STEP`.
5. Chọn target trong bảng và đọc **Executed Steps Preview**:

```text
Run mode: STOP_AT_STEP
  Open form  GET /account/action-form
  Prepare    POST /account/prepare
* Confirm    POST /account/confirm
```

6. **Add as Matrix Target**: chọn Target Auth Bob, Data Profile Alice account, Expected DENY.
7. Sang **Matrix > Edit Row**: Target Type phải là `FLOW_TARGET`, Flow Target phải trỏ đúng snapshot Confirm.
8. Setup Mode `NONE` nếu không cần một setup độc lập; các step 1–2 đã thuộc prefix của Flow Target.
9. Chạy Dry Run để xem cấu trúc request, rồi live baseline Alice và live row Bob như hướng dẫn chính.

Khi row này chạy live:

```text
Auth của Bob
  → step 1 bằng Bob
  → extract processorId
  → step 2 bằng Bob, accountId lấy từ Alice account
  → extract transactionId
  → step 3 bằng Bob
  → đánh giá response step 3 theo Expected/Evaluation Profile của row
```

Đổi Data Profile không đổi actor prefix. Nếu prepare tạo object mới dưới danh tính Bob, workflow có thể đang test object vừa được Bob tạo chứ không phải object của Alice; kiểm tra semantics của endpoint, không chỉ tên profile.

## 4. Chạy toàn flow nhưng đánh giá request ở giữa

Mục tiêu: gửi 1→2→3→4 nhưng đánh giá quyền ở step 3.

Dialog **Add Flow Target** hiện chỉ chọn Flow/Target Step và tạo STOP_AT_STEP. Để cấu hình `FULL_FLOW_EVALUATE_STEP` bằng UI:

1. Trong **Flow Targets**, bấm **Create Matrix Rows from Flow**.
2. Chọn Flow `Account action`, Target Auth Bob, Data Profile Alice account.
3. Bấm **Clear** để bỏ chọn tất cả rồi tick **Use** chỉ ở dòng Confirm.
4. Chọn Expected `DENY` ngay trên dòng Confirm.
5. Chọn Run Mode `FULL_FLOW_EVALUATE_STEP` ngay trên dòng đó.
6. Nếu dùng Default Expected/Default Run Mode ở trên, bấm **Apply Defaults** để áp dụng chúng vào các dòng; đổi default không có nghĩa mọi dòng cũ đã đổi.
7. **OK** tạo row disabled và snapshot tương ứng.
8. Kiểm tra lại Flow Target trong bảng và preview; sang Matrix review row rồi chạy.

Response dùng cho verdict vẫn là **Confirm**. Một status 200 của Read status không biến kết quả quyền ở Confirm thành ALLOW. Lỗi gửi HTTP ở downstream sau target có thể được ghi warning trong khi target vẫn có kết quả; đọc evidence/Reason, không suy từ COMPLETED rằng mọi bước đều đạt nghiệp vụ.

Nếu cần xác nhận trạng thái thật sau Confirm, cấu hình **Post-checks** với assertions. Chỉ đưa Read status vào flow không tự tạo assertion trên response của nó.

## 5. Tạo nhiều bài test cho nhiều request trong một flow

**Create Matrix Rows from Flow** có các cột:

| Cột | Ý nghĩa |
| --- | --- |
| Use | Chọn step để tạo row đánh giá tại step này |
| Step / Method / Path | Nhận diện request |
| Expected | Mong đợi của riêng row tại step đó |
| Run Mode | Prefix tới step hoặc toàn flow |

Ví dụ chọn Prepare và Confirm, cả hai STOP_AT_STEP:

| Row | Chuỗi chạy |
| --- | --- |
| Prepare | Open form → Prepare |
| Confirm | Open form → Prepare → Confirm |

Hai row độc lập: Open form và Prepare được gửi lại cho row Confirm. Không có một chuỗi HTTP duy nhất dùng chung cho hai verdict. Token một lần/object consumable cần được chuẩn bị lại theo semantics ứng dụng.

Muốn cùng các target này cho Alice và Bob, tạo các row cho actor thứ nhất, rồi tạo tiếp actor thứ hai hoặc Duplicate Row/Edit Row. Khi duplicate, chọn lại actor, Expected và Data Profile có chủ đích.

## 6. Có thể chỉ gửi đúng một request mà không chạy prefix không?

Flow Target STOP_AT_STEP **bao gồm prefix**, không phải chỉ target step. Nếu mục tiêu là gửi riêng Confirm:

- Tạo một **Template** của Confirm với các placeholder cần thiết.
- Nếu Confirm cần context mới, cấu hình **Setup RUN_SETUP_FLOW/RUN_PREFIX** để cấp `scenario.*` trước khi gửi Template.
- Hoặc tạo một flow nhỏ chỉ chứa request cần thiết rồi snapshot nó.

Không có run mode SELECTED_STEP_ONLY trong Flow Targets hiện tại. Nếu disable step prefix trong flow thì chúng không chạy và các biến do chúng tạo có thể thiếu; đó không thay thế một setup đúng.

## 7. Prefix bằng Alice, target bằng Bob

Một Flow Target dùng **Target Auth Profile của row** cho các step của nó. UI không có một trường Auth Profile cho từng step. Nó không tự chuyển sang Data Owner ở đầu flow.

Để tạo object bằng Alice rồi thử hành động bằng Bob:

1. Tách flow tạo object thành `Prepare Alice object`.
2. Extract ID của object vào `scenario.transactionId` hoặc key tương ứng.
3. Tạo target Template nhận `{{scenario.transactionId}}`.
4. Matrix Target Auth = Bob, Data Profile = object/config của Alice.
5. Setup Mode = RUN_SETUP_FLOW, Setup Flow ID = ID của flow chuẩn bị, Setup Auth Binding = DATA_OWNER, Scenario Policy = FRESH_PER_TEST.
6. Thêm Post-check DATA_OWNER để Alice đọc lại trạng thái sau nếu cần.

Xem [ví dụ setup đổi actor](ACCESS_ADVANCED.md#1-setup-tạo-context-trước-target).

Nếu processorId/CSRF gắn riêng với session của Bob, **không** lấy chúng từ setup Alice rồi inject vào session Bob. Dùng TARGET_ACTOR cho phần lấy context Bob; object của Alice có thể được chuẩn bị riêng trước. Có thể dùng Setup Alice + một Flow Target nhỏ của Bob để lấy context Bob rồi gửi action, miễn ID object từ Alice được truyền qua `scenario.*` và không prepare object lần nữa trong prefix Bob.

## 8. Snapshot, Stale và sửa target

Flow Target là bản chụp flow tại thời điểm tạo. Nó giữ request, rules, thứ tự, enabled và Send via của các step. Sửa Flowise sau đó không cập nhật snapshot.

- Cột **Stale = Yes** khi flow nguồn hiện có thay đổi hash so với snapshot.
- **Stale = No** không chứng minh tất cả state hoặc ID runtime còn hợp lệ.
- UI hiện không có Edit/Refresh Snapshot; không có nút đổi Target Step tại Matrix row.

Sau khi sửa source flow hoặc muốn chọn target khác:

1. Tạo snapshot/row mới bằng Add Flow Target hoặc Create Matrix Rows from Flow.
2. Nếu muốn giữ row cũ và các cấu hình Setup/Post-check/Teardown, **Matrix > Edit Row** đổi dropdown Flow Target sang snapshot mới.
3. Kiểm tra Target Step/Run Mode trong Flow Targets; chạy Validate và Dry Run lại.
4. Chỉ xóa snapshot cũ sau khi không còn row dùng nó.

**Add Flow Last Step as Matrix Target** ở context menu tab Flowise nhắm step enabled cuối. Dùng Add Flow Target với Target Step cụ thể khi request cần test không nằm cuối.

## 9. Dry Run và biến do extractor tạo

Dry Run không gửi step 1/2, nên không có response mới để extract processorId/transactionId. Nó render các request dựa trên state/biến đã có; không mô phỏng output login hoặc setup.

Vì vậy:

- Xem Dry Run như kiểm tra cấu trúc, actor/data bindings và placeholder, không phải xác nhận toàn flow thực thi thành công.
- Flow có biến sinh ở bước trước có thể còn unresolved hoặc ghi diagnostic ở bước sau; inspect request và warnings.
- Với Template đơn, bảng Scenario trong Templates Preview cho phép nhập giá trị mẫu để thử render. Những mẫu này không tự cấp cho live Matrix.
- Để xác nhận dữ liệu thực được truyền đúng giữa các step, chạy live một row baseline và đối chiếu traffic/evidence, rồi mở rộng matrix.

## 10. Phạm vi thực thi của Flow Target hiện tại

Access Flow Target runner chạy các step enabled theo thứ tự, render request, áp dụng step injection, provider/manual auth, gửi và extract để cấp dữ liệu cho bước sau. Đây là đường chạy riêng của Access Testing, không đồng nghĩa mọi điều khiển của FlowEngine/Flowise được thực thi giống nhau.

- Không dựa vào auto-trigger, điều kiện/action, retry/sleep của flow hoặc prerequisite flow để mô tả branching trong Flow Target; dùng flow tuyến tính có các request cần thiết.
- Target auth retry tự động như request-template target chưa được hỗ trợ cho cả Flow Target. Nếu target match auth failure, có warning; không coi nó đã refresh và chạy lại toàn flow.
- Row Evaluation Profile áp dụng tại target đã chọn. Các bước prefix/downstream không tự có cùng verdict.
- Send via ở từng request trong snapshot vẫn áp dụng; Project mode là fallback cho request không override.

Các hạn chế này không đòi nhân bản login theo actor: login/refresh vẫn do Auth Provider và profile của row xử lý trước target flow.
