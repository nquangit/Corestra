# Corestra: rà soát chậm giao diện và dung lượng project

Ngày kiểm tra: 2026-10-08. Phạm vi: source hiện tại và mô phỏng headless với API/editor/persistence giả lập; không chạy Burp thật, không gửi traffic và không đọc project thật của người dùng. Báo cáo giữ lại phép đo trước khi sửa; phần dưới ghi các thay đổi đã triển khai và phép đo sau sửa.

## Các thay đổi đã triển khai

- Flowise tái sử dụng một workspace gồm bốn native editors cho các flow tabs; tab chi tiết và vị trí divider vẫn được nhớ riêng cho từng flow. Thêm/xóa flow không tạo lại workspace/listener. Captured responses được dọn khi xóa flow hoặc step.
- Chọn flow/request không dựng lại tab headers. Request/response chỉ được nạp khi nội dung đổi; Step List chỉ dựng lại những row chịu ảnh hưởng khi đổi lựa chọn.
- Project không đọc storage khi nhận selection event. Đo dung lượng chạy bằng SwingWorker khi khởi tạo/mở Project hoặc bấm Refresh size; các yêu cầu đo đồng thời được gộp. Đếm UTF-8 không tạo bản sao byte array của cả JSON.
- Auth state-only updates lưu checkpoint riêng, gắn với revision của Access document. Cấu hình/Evidence thay đổi vẫn lưu document đầy đủ và gộp state mới vào đó. Đọc project cũ vẫn được hỗ trợ, checkpoint sai/hỏng không thay thế document hợp lệ; export vẫn lấy toàn bộ state hiện tại từ repository trong RAM. Không trì hoãn ghi state hay thay chính sách lưu secrets.
- Flow repository giữ immutable cache sau load/save, đồng bộ hóa các thao tác và bỏ qua save có nội dung không đổi.
- Flow Log gộp thông báo chờ EDT, tránh refresh lúc ẩn và nạp lại viewer có cùng nội dung. Log giữ tối đa 1,000 entries và 32 MiB raw HTTP text (ước tính hai bytes/ký tự). Exchange quá lớn giữ metadata và đánh dấu payload omitted; chỉ bản sao log bị giới hạn, không ảnh hưởng dữ liệu thực dùng cho execution/extract/inject.

Các số liệu phần điều tra bên dưới là **trước sửa**. Phép đo sau sửa và kiểm thử được bổ sung ở cuối báo cáo.

## Kết luận

Corestra có các vấn đề hiệu năng xác nhận được, có thể góp phần làm giao diện Burp chậm, tăng CPU và giữ nhiều đối tượng trong RAM. Tuy nhiên, chưa đủ bằng chứng để quy toàn bộ dung lượng hàng chục GB của Burp project cho extension. Lượng JSON gửi tới persistence khác với dung lượng vật lý mà Burp ghi hoặc giữ trong project.

Trong bản trước sửa, chỉ chuyển giữa các flow đang có không tạo thêm editor trong phép thử và không ghi Flow document. Nó vẫn kích hoạt cập nhật tất cả panel, đọc lại dữ liệu project và tải lại các native editor đang ẩn. Nếu còn tạo/xóa flow trong phiên, listener và panel cũ bị giữ lại, khiến lượng công việc tăng theo lịch sử thao tác.

## 1. Panel bị xóa vẫn được giữ qua listener — ưu tiên cao

`ui/flowise/FlowTabsPanel.java:178` bỏ panel khỏi `panelCache` khi flow bị xóa. Nhưng các panel con đã đăng ký callback vào `FlowWorkspaceController.changeListeners` và `FlowExecutionLog.listeners`; các API này chỉ có `add`, không có đường unsubscribe.

Một `FlowWorkspacePanel` đăng ký nhiều listener qua Step List, Request/Response, Rules, Conditions, Execution, Log, Trigger, Aggregation và Notes. Bỏ khỏi cache/tab không cắt những tham chiếu này. Panel, Swing component và native editor vẫn được callback sau khi flow đã bị xóa.

Phép thử dùng chính các lớp production, với 2 flow, mỗi flow 1 step:

| Thời điểm | Flow còn lại | Controller listeners | Log listeners | Editor đã tạo |
| --- | ---: | ---: | ---: | ---: |
| Khởi tạo FlowTabsPanel | 2 | 23 | 2 | 8 |
| Sau 10 lần thêm/xóa flow tạm | 2 | 133 | 12 | 48 |

Sau các lần thêm/xóa, một lần chọn flow gọi `setRequest` 24 lần và `setResponse` 24 lần. Một sự kiện clear log gọi mỗi setter 12 lần. Những editor này gồm cả editor thuộc panel đã bị xóa. Các con số không bao gồm listener của root Flowise và Project panel trong bootstrap thật.

`FlowWorkspaceController.capturedResponses` cũng không được dọn khi xóa flow/step; response capture cũ có thể còn được giữ trong RAM.

## 2. Cập nhật tất cả flow/editor khi chỉ chọn một flow — ưu tiên cao

Mọi workspace panel dùng cùng controller, không có ràng buộc chỉ cập nhật flow đang hiển thị. `RequestResponseController.refresh()` dựng lại request và decode response Base64, rồi gọi native editor setters mỗi khi controller thông báo, kể cả khi dữ liệu đang xem không thay đổi.

`FlowTabsPanel.refresh()` còn `removeAll()` và dựng lại các tab/header trên mỗi thông báo: chọn flow, chọn step, đổi cấu hình hoặc nhận kết quả execution. `StepListPanel.refresh()` cũng dựng lại toàn bộ các row.

Với 2 flow đang có, 20 lần chọn flow gọi `setRequest` 80 lần và `setResponse` 80 lần; số editor vẫn là 8, persistence write là 0. Như vậy có công việc thừa ngay cả khi không có leak từ thao tác thêm/xóa. Các native editor trong phép thử chỉ đếm lời gọi; phép thử không đo được chi phí parse/render của editor Burp thật hay chứng minh độ trễ 5 giây.

## 3. Tab Project đang ẩn đọc lại mọi document trên luồng giao diện — ưu tiên cao

`ui/project/ProjectPanel.java:210` đăng ký `refreshStats` với mọi thay đổi controller. `refreshStats()` gọi `refreshProjectDataUsage()`; hàm này gọi `ProjectDataMaintenanceService.storageUsage()`.

Storage usage đọc mọi string trong extension data rồi tạo UTF-8 byte array để đếm bytes. Công việc này chạy đồng bộ cùng thao tác chọn flow/step trên EDT, dù Project tab không hiển thị. Document lớn làm phát sinh đọc, cấp phát và thu gom bộ nhớ lặp lại chỉ để cập nhật một label.

Phép thử với Access document giả lập khoảng 8 MiB và một flow:

- 20 thông báo chọn step đọc stored strings 40 lần, tổng khoảng 160.03 MiB nội dung.
- Không có persistence write trong 20 thao tác này.
- Payload là ASCII nên số ký tự đọc tương ứng số UTF-8 bytes; production còn tạo byte arrays tương ứng để đếm dung lượng.

Đây là một mối liên hệ trực tiếp giữa dữ liệu project lớn và việc chọn request chậm.

## 4. Một cập nhật auth nhỏ ghi lại toàn bộ Access workspace — ưu tiên cao

Chuỗi gọi khi keep-alive thành công:

```text
AuthManager.maintainKeepAlive
  -> updateAuthState (expiry và lastUpdated thay đổi)
  -> InMemoryAccessRepository.saveAuthProfile
  -> persistProjectState / snapshotDocument
  -> AccessWorkspaceProjectRepository.save
  -> serialize FULL_EXPORT toàn workspace
  -> extensionData.setString
```

Document chứa cả cấu hình, auth state, matrix, results, evidence, summary và report drafts; chỉ runtime scenarios bị bỏ. Một actor thay đổi timestamp vẫn làm serialize lại cả phần dữ liệu ổn định của các actor khác và các kết quả đã lưu. Callback persistence chạy khi repository vẫn giữ synchronized lock.

Phép thử đưa một Data Profile giả lập chứa 8 MiB dữ liệu ổn định vào cùng workspace, rồi cập nhật một auth state nhỏ 20 lần:

- 20 `setString` calls.
- Tổng nội dung JSON gửi tới persistence: khoảng 160.01 MiB.
- Document hiện tại vẫn chỉ khoảng 8.00 MiB.

Nếu một actor chạy mỗi 10 giây, có thể có khoảng 8,640 maintenance/ngày. Với document 8 MiB, ghi toàn bộ mỗi lần tương đương khoảng 67.5 GiB JSON/ngày gửi qua persistence API. Đây là phép tính cho khối lượng ghi logic, **không phải dự đoán file project sẽ tăng 67.5 GiB/ngày**. Cần quan sát project thật để biết dữ liệu vật lý được giữ như thế nào.

Session Profile state cũng được ghi lại nguyên document profiles khi cập nhật marker. Auth flow lookup dùng `FlowRepository.loadAll()`, đọc và deserialize toàn bộ Flow document mỗi lần tìm flow. Hai đường này làm thêm công việc khi scheduler chạy.

## 5. Flow Log vừa cập nhật lúc ẩn, vừa chưa có giới hạn bytes

`FlowLogPanel` được tạo cho mọi flow, gồm một cặp native editor. Mỗi panel đăng ký listener toàn cục với log. Mỗi completed run, kể cả do scheduler, có thể enqueue refresh lên EDT cho tất cả panel. Không có coalescing callback đang chờ, kiểm tra visibility hay kiểm tra editor content đã giống dữ liệu mới.

Refresh quét snapshot log, dựng lại bảng và chọn dòng đầu, dẫn tới tải request/response vào editor ngay cả khi tab log không được mở. Vì mọi panel dùng active flow của controller, các panel ẩn có thể cùng render lại một dữ liệu.

`FlowExecutionLog` giữ tối đa 1,000 entries, nhưng mỗi entry giữ toàn raw request/response, không có giới hạn tổng bytes. Entry count bị chặn không đồng nghĩa dung lượng RAM bị chặn ở mức nhỏ. Response lớn hoặc body chứa ảnh Base64 có thể làm log nặng. Log này ở RAM, không được persist trực tiếp vào Burp project.

Capture Flowise lưu response đầy đủ dạng Base64 trong `FlowStep.lastResponseRaw`, đồng thời giữ response trong map RAM. Base64 tăng kích thước dữ liệu nhị phân khoảng một phần ba. Flow saves ghi nguyên Flow document đồng bộ; document có nhiều response lớn làm các lần sửa cấu hình nặng hơn. Chuyển flow đơn thuần không gọi saveAll.

## Thứ tự khắc phục đề xuất

1. Gỡ listener khi panel bị loại bỏ và dọn captured responses của flow/step đã xóa. Xác nhận số listener/editor không tăng sau nhiều chu kỳ thêm/xóa/import.
2. Giảm số workspace/native editor hoạt động; dùng một workspace được tái sử dụng hoặc tạo lười và chỉ cập nhật panel đang cần hiển thị. Giữ bố cục và hành vi chọn request đầu tiên hiện tại.
3. Phân biệt selection change với thay đổi flow/configuration/results. Không dựng lại tab strip hay đọc storage usage khi chỉ chọn request. Đo storage ngoài EDT, cập nhật có giới hạn tần suất hoặc theo yêu cầu.
4. Tách auth state được cập nhật thường xuyên khỏi Access document chứa results/evidence; kết hợp các cập nhật và giữ flush khi unload/export. Không mất dữ liệu người dùng hay silently đổi chính sách persist secrets.
5. Coalesce/throttle cập nhật log, tải editor khi người dùng chọn entry/hiển thị log và giới hạn bytes giữ trong RAM. Giới hạn preview/log phải tách khỏi bytes thật dùng để extract/inject.
6. Cache Flow repository với invalidation đúng khi save/import/clear và tránh ghi document không thay đổi.

## Cần đối chiếu trong Burp thật

- Làm rõ “mở đi mở lại” là chuyển tab có sẵn hay có xóa/tạo/import lại flow. Hai đường có cơ chế gây chậm khác nhau.
- Ghi số flow/step, scheduler/actor đang chạy và interval. Kiểm tra có request lặp ngoài lịch dự kiến không.
- Ghi dòng **Project → Danger Zone → Current Corestra data** (bấm **Refresh size**) và dung lượng file project ở hai thời điểm. Label chỉ đo snapshot strings hiện tại của extension, không đo overhead/container hay lịch sử ghi vật lý.
- Khi Burp chậm, lấy thread dump/CPU profile: kiểm tra EDT có ở Flowise refresh, Project storage usage, native editor setters hoặc đang chờ lock. Quan sát GC/heap để xác định retained panels/response/log strings.
- Trên bản sao project, so sánh cùng thao tác khi bật và tắt Corestra; so sánh thêm scheduler bật/tắt. Việc tắt extension đồng thời thay đổi traffic và auth nên cần ghi lại điều kiện để tránh kết luận sai.

Chưa xóa project data, chưa chạy bản sửa trong Burp thật và không suy luận rằng Clean Burp Project Data sẽ làm file project vật lý nhỏ lại ngay.

Harness và stdout của lần đo này nằm tại `/tmp/corestra-performance-audit/` (`FlowiseProbe.java`, `StorageProbe.java`, `flowise-probe.txt`, `storage-probe.txt`). Dùng Java 21, `target/classes`, shaded JAR và Montoya API 2026.4; các static factory Montoya được thay bằng proxy chỉ trong process chẩn đoán, không trong production. Các editor là fake nên chỉ dùng kết quả làm bằng chứng về lifecycle/số lời gọi/khối lượng serialization, không làm benchmark độ trễ Burp.


## Kiểm chứng sau sửa

Dùng lại production-class harness với 2 flow / 1 step mỗi flow, request giống nội dung nhau và response rỗng:

| Phép đo | Trước sửa | Sau sửa |
| --- | ---: | ---: |
| Native editors khi khởi tạo | 8 | 4 |
| Controller listeners sau 10 vòng thêm/xóa | 133 | 12 (không tăng từ lúc khởi tạo) |
| Log listeners sau 10 vòng thêm/xóa | 12 | 1 (không tăng) |
| Tổng editors đã tạo sau 10 vòng | 48 | 4 |
| setRequest / setResponse sau 20 lần chuyển flow cùng nội dung | 80 / 80 | 0 / 0 |
| Stored-string reads do 20 selection events, sau đo size ban đầu | 40 | 0 |
| JSON gửi persistence cho 20 auth updates, lần đầu tạo actor, workspace ổn định 8 MiB | 160.01 MiB | 8.01 MiB |

Sau khi actor đã tồn tại, **20 state-only updates tiếp theo chỉ ghi tổng 10,220 ký tự JSON**, thay cho khoảng 160 MiB. Chỉ tạo actor/chỉnh cấu hình mới cần ghi document đầy đủ. Checkpoint tiếp tục được lưu ngay, không làm mất TTL/state khi đóng extension.

Các regression tests kiểm tra request khác nhau vẫn nạp đúng một lần mỗi lần đổi; đổi response/captured response vẫn cập nhật editor; đổi metadata không tải lại editor; sample được khôi phục khi bỏ chọn request. Có kiểm tra row không liên quan giữ nguyên, view state riêng từng flow, cache invalidation, UTF-8 (tiếng Việt/emoji/surrogate lỗi), measurement không chạy trên EDT, measurement bị thiếu, payload budget, checkpoint round-trip, legacy upgrade, stale/corrupt checkpoint và retry sau lỗi ghi.

Đây vẫn là bằng chứng về số lời gọi/lifecycle/khối lượng serialization. Chưa đo CPU, heap, độ trễ editor hoặc mức tăng file project vật lý trong Burp thật. Với snapshot của người dùng dưới 100 KiB, cần đối chiếu thêm traffic/history và profile Burp để xác định phần dung lượng còn lại; không có căn cứ để khẳng định Corestra chiếm hàng chục GB từ số liệu hiện có.

Xác minh bản cuối: `mvn -o package` thành công; JUnit ghi nhận **771 tests, 0 failures, 0 errors, 1 skipped** (770 tests chạy thành công; bootstrap test bỏ qua khi headless). JAR để nạp lại vào Burp: `target/corestra-0.1.0-SNAPSHOT-all.jar`. `git diff --check` không phát hiện lỗi whitespace.


### Bổ sung: highlight request sau tối ưu Step List

Click listener gán `selectedStepId` trước khi thông báo controller, khiến refresh so sánh hai ID đã giống nhau và bỏ qua cập nhật highlight. Đã bỏ các phép gán từ click/context-menu/remove; chỉ refresh đọc lựa chọn của controller để cập nhật trạng thái đã render. Context menu dùng container ổn định vì dòng vừa chọn có thể được thay thế.

Regression test mô phỏng MouseEvent đã tái hiện lỗi trước sửa; sau sửa kiểm tra click dòng/label, đổi flow, bỏ chọn và giữ nguyên các dòng không liên quan. `mvn -o package` thành công với **772 tests, 0 failures, 0 errors, 1 skipped** (771 passed). Chưa chạy kiểm tra thủ công trong Burp.
