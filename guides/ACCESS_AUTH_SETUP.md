# Access Testing: thiết lập Auth Provider và actor

Tài liệu này mô tả luồng làm việc hoàn chỉnh từ request đăng nhập đến một Matrix row chạy bằng đúng danh tính người dùng. Các ví dụ dùng tên biến thống nhất: `accessToken`, `refreshToken`, `sessionId`, `csrfToken`, `expiresAt` và `expiresIn`.

Đây là phần xác thực của bộ hướng dẫn. Bắt đầu tại [Access Testing: hướng dẫn đầy đủ](ACCESS_TESTING.md) để xem từng tab, tạo Data Profile, chọn request cần test và chạy Matrix. Các bước chọn flow target được mô tả riêng tại [Flow Targets](ACCESS_FLOW_TARGETS.md).

## 1. Mô hình cần nắm

Corestra tách xác thực thành hai đối tượng:

- **Auth Provider** mô tả cách xác thực: login flow nào được chạy, refresh/validate ra sao và auth state được inject vào target request thế nào.
- **Auth Profile** là một actor cụ thể: Alice, Bob, Maker, Checker, Admin hoặc Anonymous. Profile giữ credentials và auth state riêng của actor đó.

Một provider có thể dùng chung cho nhiều profile. Ví dụ `Retail Web Login` dùng chung cho Alice và Bob, nhưng mỗi profile có `credentials.username`, `credentials.password`, token, cookie và thời điểm hết hạn riêng.

Khi chạy một Matrix row, Corestra lấy `Target Auth Profile` của row, khóa theo profile ID, bảo đảm auth state của profile đó, inject state vào target request rồi mới gửi request. Access Testing dùng transport cách ly actor nên Project Session Handler không ghi đè credential của actor đang chọn.

## 2. Chuẩn bị login flow trong Flowise

Ví dụ API đăng nhập:

```http
POST /api/login HTTP/1.1
Host: app.example.test
Content-Type: application/json

{"username":"alice","password":"secret"}
```

Response:

```http
HTTP/1.1 200 OK
Set-Cookie: SESSION=s-abc; Path=/; HttpOnly; Secure

{"access_token":"ey...","refresh_token":"r-123","expires_in":900,"csrf":"c-456"}
```

Thực hiện:

1. Capture request vào Flowise và tạo flow `Auth - Login`.
2. Trong request step, thay credential cố định bằng hai Inject Rule:
   - Target `REQUEST_JSON_PATH`, selector `username`, variable key `credentials.username`.
   - Target `REQUEST_JSON_PATH`, selector `password`, variable key `credentials.password`.
3. Thêm Extract Rule từ response:
   - JSON path `access_token` → `accessToken`.
   - JSON path `refresh_token` → `refreshToken`.
   - JSON path `expires_in` → `expiresIn`.
   - JSON path `csrf` → `csrfToken`.
   - Cookie `SESSION` → `sessionId`.
4. Mở **Execution > Run Context…**, chọn Auth Profile hoặc nhập custom variables `credentials.username=alice` và `credentials.password=...`, rồi **Run Flow**. Kiểm tra extractor trên stored response nếu chưa muốn gửi login.
5. Đảm bảo tên biến không kèm `auth.`. Flow lưu `accessToken`; khi dùng trong Access Testing, scoped template sẽ là `{{auth.accessToken}}`.

Auth flow nhận đồng thời dạng có prefix và dạng tương thích cũ. Nên dùng `credentials.username`, `credentials.password`, `auth.refreshToken` và `env.baseUrl` để tránh nhầm nguồn.

## 3. Refresh và validate flow

### Refresh flow

Tạo flow riêng `Auth - Refresh` nếu ứng dụng có refresh token. Request có thể inject:

```text
auth.refreshToken
auth.sessionId
```

Flow phải extract lại token mới và expiry mới. Nếu server xoay refresh token, extract cả `refreshToken` mới.

**Lần chạy đầu chưa có refresh token**: với policy REFRESH_THEN_LOGIN, Corestra kiểm tra input trước khi gửi refresh. Nếu Inject Rule cần `auth.refreshToken` nhưng profile chưa có key đó, hoặc giá trị rỗng/toàn khoảng trắng, refresh không được gửi; runtime chuyển sang login của đúng profile. Khi login đã extract token, các lần refresh sau mới dùng token của profile đó.

Tên key phải khớp chính xác. Nếu login extract vào `refresh_token`, refresh phải inject `auth.refresh_token`, không phải `auth.refreshToken`. Ví dụ refresh bằng header:

```text
Target: REQUEST_HEADER
Selector: Authorization
Value source: TEMPLATE
Value template: Bearer {{auth.refresh_token}}
Write strategy: OVERWRITE
```

Flow auth kiểm tra tất cả biến mà enabled Inject Rules tham chiếu, gồm VARIABLE và TEMPLATE. Không giữ token/credential gốc trong request mẫu để gửi khi thiếu biến. Nếu extractor ở bước trước không lấy được giá trị cần cho bước sau, request phụ thuộc bị chặn và flow auth thất bại, kể cả step chọn CONTINUE_FLOW. Lỗi transform/JSON injection hoặc regex không match trong flow auth cũng chặn request. Success rule HTTP 200 không được biến lỗi thực thi thành thành công.

Biến do step trước thực sự extract có thể dùng ở step sau trong cùng auth flow bằng tên thuần hoặc `auth.*`. Các output chưa sinh vì chưa chạy login không được coi là state có sẵn chỉ vì Flow Contract liệt kê chúng. Diagnostics giữ lý do refresh bị bỏ qua khi fallback login thành công.

Nếu project đã chạy bằng bản cũ và state có thể được lấy từ token của actor khác, reload extension rồi **Clear State** của linked Session Handler hoặc **Clear Auth State** của Auth Profile bị ảnh hưởng. Sau đó **Run Auth Now** để lấy lại state đúng actor. Cấu hình credentials và tên key vẫn được giữ.

### Validate flow

Tạo flow nhẹ như `GET /api/me` hoặc `GET /session/status`. Flow này không cần extract token mới. Auth Provider dùng success/failure rule trên response cuối để biết session còn hợp lệ hay không.

Không dùng endpoint thay đổi dữ liệu làm validate flow.

## 4. Tạo Auth Provider

Mở **Authentication > Auth Providers > Add**.

1. Chọn Login Flow, Refresh Flow và Validate Flow phù hợp.
2. Xem phần **Flow Contract** để kiểm tra input cần thiết và các Auth State key mà flow thực sự extract. Tên extractor chuẩn không có prefix, ví dụ `accessToken`; khi dùng sẽ là `{{auth.accessToken}}`. Cấu hình thiếu flow bắt buộc, flow bị disable hoặc template sai sẽ chặn lưu.
3. Chọn fallback policy:

| Policy | Hành vi khi auth state không còn fresh |
| --- | --- |
| `LOGIN_ONLY` | Chạy login flow. Phù hợp khi không có refresh token. |
| `REFRESH_THEN_LOGIN` | Thử refresh; nếu thất bại thì login. |
| `VALIDATE_THEN_REFRESH_THEN_LOGIN` | Validate trước, sau đó refresh rồi login nếu cần. |
| `MANUAL_ONLY` | Không tự chạy auth flow. |

4. Đặt **Auth State TTL**. TTL chỉ được dùng khi flow không extract expiry. Giá trị mặc định là 300 giây; có thể đặt `180` nếu muốn coi state là stale sau 3 phút. TTL không phải lịch gửi keep-alive. Đặt `0` nếu muốn auth state lập tức stale. Cấu hình duy trì session tại [Keep-alive](KEEP_ALIVE.md).
5. Trong tab Injection, chọn trực tiếp value template được gợi ý từ extractor hoặc bấm **Generate from flow**. Nút này chỉ thêm các gợi ý Bearer/Cookie/CSRF chưa tồn tại và không ghi đè rule hiện có. Provider injection hiện hỗ trợ `HEADER`, `COOKIE` và `QUERY`.
6. Thêm success/failure rule. Các loại đang thực thi là `STATUS_CODE`, `HEADER_EXISTS`, `BODY_CONTAINS`, `BODY_NOT_CONTAINS`.
7. Chỉ bật target retry nếu failure rule phân biệt được auth failure với authorization denial của bài test.

Project cũ có extractor như `auth.accessToken` vẫn chạy: Corestra chuẩn hóa thành key `accessToken` khi cập nhật Auth State. Flow Contract sẽ cảnh báo; nút **Fix auth.* extractor names** đổi tên trên các auth flow đang chọn và cập nhật Inject Rule nằm sau extractor. Trường hợp có cả hai tên `accessToken` và `auth.accessToken` sẽ được bỏ qua để tránh thay đổi thứ tự ghi đè ngoài ý muốn.

Thứ tự expiry:

1. `expiresAt` được hiểu là Unix epoch milliseconds.
2. `expiresIn` hoặc `expires_in` được hiểu là số giây tính từ lúc auth flow hoàn tất.
3. Nếu không có các biến trên, Corestra dùng Auth State TTL của provider.

## 5. Tạo đúng profile cho từng user/role

Mở **Authentication > Auth Profiles > Add**.

Ví dụ Alice:

```text
Name: Alice
Role: customer
Provider: Retail Web Login

Credentials:
username = alice
password = alice-password
deviceId = alice-device
```

Tạo Bob thành profile khác:

```text
Name: Bob
Role: customer
Provider: Retail Web Login

Credentials:
username = bob
password = bob-password
deviceId = bob-device
```

Không đặt token runtime vào Credentials. Credentials là đầu vào ổn định của login flow. Token/cookie do flow extract sẽ được Corestra lưu trong **Auth Variables** của đúng profile.

Trong bảng Credentials, nhập key `username`, không phải `credentials.username`; prefix được runtime thêm khi đưa vào flow. Tương tự, Auth Variables dùng key `accessToken`, không phải `auth.accessToken`. Tên key phân biệt hoa/thường và phải khớp với Inject Rule của flow. Trường Role là nhãn phân loại của Corestra; nó không đổi quyền phía server và không thay việc chọn đúng credentials.

Các tab khác trong profile:

- **Auth Variables**: token/state đã có sẵn hoặc state được flow cập nhật.
- **Headers**: header cố định cho manual auth, ví dụ API key.
- **Cookies**: cookie cố định cho manual auth.

Khi chỉnh một profile đã đăng nhập, Corestra giữ credentials, cached state và expiry hiện tại. Cột `Auth State` cho biết `Not acquired`, `Valid until`, `Expired`, `Expiry unknown` hoặc `Manual`.

Khi đổi provider, nghi ngờ token/cookie bị sai, hoặc muốn buộc login lại, chọn profile rồi bấm **Clear Auth State**. Credentials được giữ nguyên; lần chạy live kế tiếp sẽ acquire state mới theo provider.

### Dùng cùng actor trong Session Handler

Không cần tạo `Login - Alice`, `Login - Bob` và thêm một bản login khác cho Session Handler. Sau khi provider và các profile Alice/Bob đã hoạt động:

1. Mở **Session Handler** và tạo một Session Profile dùng để xác định scope traffic.
2. Chọn **Authentication = Linked Auth Profile**.
3. Chọn Alice, Bob hoặc actor cần dùng trong Burp Proxy/Repeater/Scanner.
4. Cấu hình Scope và Scheduler của Session Handler.

Session Handler gọi chung auth runtime của Authentication. Login/refresh/validate flow, credentials, token, cookie, expiry và provider injection rules đều thuộc actor đã chọn. Các Injection Rule cục bộ của Session Handler chạy sau provider rules và phù hợp cho override riêng theo tool hoặc traffic. Extraction Rule từ live response cập nhật Auth Variables của actor được liên kết.

Trong Injection Rule cục bộ của linked profile, dùng `{{auth.accessToken}}`. Dropdown **Value Template** gợi ý output của login/refresh/validate flow kể cả trước lần login đầu tiên, đồng thời gợi ý Auth State hiện có như `{{auth.csrfToken}}`, `{{auth.header.Authorization}}` và `{{auth.cookie.SESSION}}`. Không cần tạo lại rule Authorization/Cookie nếu Auth Provider đã có rule tương ứng, vì provider rule được áp dụng tự động trước local Session rule.

TTL và Scheduler có vai trò khác nhau. TTL chỉ đánh dấu Auth State đã cũ và không tự gửi request. Khi Scheduler tắt, request phù hợp sau khi TTL hết sẽ kiểm tra auth theo nhu cầu. Bật Scheduler nếu cần giữ session sống khi idle. Nếu provider chọn **Keep-alive Flow**, scheduler ưu tiên duy trì session hiện tại; nếu không chọn, scheduled run giữ hành vi chạy provider policy cũ. Xem [hướng dẫn Keep-alive](KEEP_ALIVE.md) để cấu hình flow dùng chung nhiều actor, outcome rules riêng và backoff khi lỗi mạng.

Khi muốn đổi user đang dùng cho traffic thủ công, đổi **Shared Auth Profile** thay vì sao chép flow. Nếu vừa tạo actor ở Authentication, bấm **Reload** cạnh danh sách profile.

## 6. Data Profile và bài test nhiều actor

Data Profile mô tả object thuộc về ai. Ví dụ:

```text
Alice account:
ownerAuthProfileId = Alice
accountId = acct-alice-001

Bob account:
ownerAuthProfileId = Bob
accountId = acct-bob-002
```

Một BOLA row điển hình:

```text
Target Auth Profile = Bob
Data Profile = Alice account
Expected = DENY
```

Target template dùng `{{data.accountId}}`. Auth material của request lấy từ Bob, còn object ID lấy từ Alice. Đây là điểm quyết định để kiểm thử đúng hướng ngang.

## 7. Template, Matrix và Dry Run

1. Tạo Access Template hoặc Flow Target.
2. Dùng placeholder rõ scope: `{{auth.accessToken}}`, `{{data.accountId}}`, `{{scenario.transferId}}`.
3. Tạo Matrix row và chọn đúng `Target Auth Profile` cùng `Data Profile`.
4. Bật **Dry Run / Render Only**, chạy từng actor và kiểm tra request render.
5. Xác nhận `Authorization`, `Cookie`, CSRF và object ID thuộc đúng actor/object dự kiến.
6. Chạy một row live trước khi chạy cả plan.
7. Đọc `Run Status` riêng với verdict `OK`, `SUSPICIOUS`, `REVIEW`, `ERROR` hoặc `NOT_RUN`. `ALLOW`/`DENY` là Expected Result của bài test; request gửi thành công không đồng nghĩa quyền truy cập đúng.

## 8. Retry khi target trả auth failure

Phần này áp dụng cho target kiểu **REQUEST_TEMPLATE**. Flow Target chưa tự refresh rồi chạy lại cả flow khi target match auth failure; xem [phạm vi Flow Target](ACCESS_FLOW_TARGETS.md#10-phạm-vi-thực-thi-của-flow-target-hiện-tại).

Target retry dùng **failure rules của Auth Provider**. Ví dụ failure rule `STATUS_CODE = 401` và policy `REFRESH_THEN_LOGIN_AND_RETRY_ONCE`:

1. Target request lần đầu trả 401.
2. Corestra chạy refresh; nếu thất bại thì login.
3. Request được render lại với auth state mới.
4. Corestra gửi lại đúng một lần.

Không dùng `403` làm auth failure rule nếu ứng dụng dùng 403 cho authorization denial. Nếu dùng, một kết quả DENY hợp lệ có thể kích hoạt login/refresh không cần thiết.

## 9. Kiểm tra trước khi chạy plan

- Login flow chạy được độc lập và extract đúng biến.
- Mỗi profile có credentials riêng; không copy token Alice sang Bob.
- TTL hoặc expiry đã được cấu hình để tránh login trên mọi target.
- Provider injection không để lại Authorization/Cookie hardcoded trong template.
- Với request độc lập, Dry Run không còn unresolved variable. Với output chỉ sinh sau login/setup/prefix, Dry Run chưa thể mô phỏng response; kiểm tra bằng một live baseline trước khi mở rộng plan.
- Data owner và target actor đúng với mục tiêu của Matrix row.
- Success/failure rule dùng loại đang hỗ trợ.
- Bấm **Validate Access Project** và xử lý lỗi trước khi chạy.

Xem các cấu hình cụ thể tại [AUTH_RECIPES.md](AUTH_RECIPES.md).

Các phần Data Profile, Template và Matrix ở trên chỉ là tóm tắt; xem [hướng dẫn chính](ACCESS_TESTING.md) để thao tác từng ô và [setup/post-check/teardown](ACCESS_ADVANCED.md) cho workflow nhiều bước.
