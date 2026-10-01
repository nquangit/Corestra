# Access Testing: công thức xác thực

Các công thức dưới đây giả định login flow đã extract biến vào auth state. `Target` và `Value Template` là hai cột trong **Auth Provider > Injection**.

## 1. Bearer token

Login flow extract:

```text
access_token → accessToken
expires_in   → expiresIn
```

Provider injection:

| Location | Target | Value Template | Mode |
| --- | --- | --- | --- |
| `HEADER` | `Authorization` | `Bearer {{auth.accessToken}}` | `OVERRIDE` |

Nếu response trả chuỗi `Bearer ey...` sẵn, extract token thuần bằng transform `stripPrefix:Bearer ` hoặc dùng template `{{auth.authorizationValue}}` không thêm prefix lần nữa.

## 2. Session cookie

Login response:

```http
Set-Cookie: SESSION=s-abc; Path=/; HttpOnly; Secure
```

Flowise Extract Rule:

```text
Source side: RESPONSE
Type: COOKIE
Selector: SESSION
Variable: sessionId
```

Provider injection:

| Location | Target | Value Template | Mode |
| --- | --- | --- | --- |
| `COOKIE` | `SESSION` | `{{auth.sessionId}}` | `OVERRIDE` |

`COOKIE` injection gộp cookie mới với cookie khác trong template và thay đúng cookie cùng tên.

Với cookie session không trả expiry, đặt Auth State TTL theo thời gian an toàn, ví dụ 180 giây. Điều này ngăn login flow chạy theo tỷ lệ 1:1 với target request.

## 3. Bearer + cookie + CSRF

Login flow extract `accessToken`, `sessionId`, `csrfToken`, `expiresIn`.

Provider injection:

| Location | Target | Value Template | Mode |
| --- | --- | --- | --- |
| `HEADER` | `Authorization` | `Bearer {{auth.accessToken}}` | `OVERRIDE` |
| `COOKIE` | `SESSION` | `{{auth.sessionId}}` | `OVERRIDE` |
| `HEADER` | `X-CSRF-Token` | `{{auth.csrfToken}}` | `OVERRIDE` |

Nếu CSRF gắn với session cookie, luôn extract cả hai từ cùng login/refresh flow để tránh ghép state của hai phiên khác nhau.

## 4. Hai cookie hoặc nhiều cookie

Tạo một rule cho từng cookie:

```text
COOKIE / SESSIONID / {{auth.sessionId}} / OVERRIDE
COOKIE / ROUTEID   / {{auth.routeId}}   / OVERRIDE
```

Không tạo một rule có target `Cookie` và value chứa toàn bộ chuỗi cookie. Location `COOKIE` yêu cầu target là tên từng cookie.

## 5. API key cố định

Nếu mỗi actor có API key riêng nhưng không cần login flow:

1. Chọn provider `Manual` cho Auth Profile.
2. Trong **Headers**, nhập:

```text
X-API-Key = actor-specific-key
```

Nếu API key cần dùng trong template body/query, đặt thêm trong **Auth Variables**:

```text
apiKey = actor-specific-key
```

Sau đó dùng `{{auth.apiKey}}`.

## 6. Basic authentication

Với header đã encode sẵn, manual profile:

```text
Authorization = Basic dXNlcjpwYXNz
```

Nếu login flow tạo giá trị base64, extract thành `basicCredential` và dùng:

```text
HEADER / Authorization / Basic {{auth.basicCredential}} / OVERRIDE
```

## 7. Token trong query string

```text
QUERY / access_token / {{auth.accessToken}} / OVERRIDE
```

Query injection URL-encode tên và giá trị. Không đặt dấu `?` hoặc `=` trong Target.

## 8. Device ID, nonce hoặc hash trong login

Giá trị riêng theo actor nhưng ổn định nên đặt trong **Credentials**:

```text
deviceId = device-alice
loginHash = precomputed-hash-alice
```

Login flow inject bằng variable key `credentials.deviceId` và `credentials.loginHash`. Nếu hash phải tính lại theo timestamp hoặc nonce, hãy tạo giá trị bằng setup flow hoặc Flowise transform phù hợp; không lưu một hash hết hạn làm credential cố định.

## 9. Refresh token rotation

Refresh flow inject `auth.refreshToken` và extract lại:

```text
accessToken
refreshToken
expiresIn
```

AuthManager merge extracted variables vào state hiện tại, nên refresh token mới sẽ thay refresh token cũ. Nếu flow không extract refresh token mới, giá trị cũ được giữ.

## 10. Validate bằng endpoint `/me`

Provider:

```text
Fallback: VALIDATE_THEN_REFRESH_THEN_LOGIN
Validate Flow: GET /api/me
Success rule: STATUS_CODE / 200
Failure rule: STATUS_CODE / 401
```

Validate flow nhận auth state của profile. Nó có thể dùng Inject Rule trong chính flow với `auth.accessToken` hoặc `auth.sessionId`.

## 11. Anonymous actor

Tạo manual profile `Anonymous`, không nhập headers/cookies/variables. Dùng profile này cho Matrix row cần xác nhận endpoint có chặn unauthenticated request.

Đảm bảo target template không chứa Authorization hoặc Cookie hardcoded.

## 12. Các trường hợp hiện chưa hỗ trợ trong Auth Provider injection

Provider injection hiện thực thi `HEADER`, `COOKIE`, `QUERY`. Các location `BODY_JSON`, `BODY_FORM`, `PATH` trong dữ liệu cũ sẽ sinh warning và không sửa request.

Khi auth material bắt buộc nằm ở body hoặc path:

- Đưa nó vào Access Template dưới dạng `{{auth.variableName}}`; hoặc
- Dùng Flow Target với Flowise Inject Rule phù hợp.

Auth success/failure rule hiện thực thi `STATUS_CODE`, `HEADER_EXISTS`, `BODY_CONTAINS`, `BODY_NOT_CONTAINS`. `JSON_PATH` và `CUSTOM` trong dữ liệu cũ chưa được evaluator thực thi.

## 13. Chẩn đoán nhanh

| Hiện tượng | Kiểm tra |
| --- | --- |
| Login chạy trước mọi target | Login có extract expiry chưa; Auth State TTL có đang bằng `0` không. |
| Alice dùng token của Bob | Matrix row chọn đúng profile chưa; credentials có tách profile không; template có token hardcoded không. |
| Cookie không xuất hiện | Target của COOKIE phải là tên cookie; biến `auth.*` có tồn tại sau login không. |
| Bearer thành `Bearer Bearer ...` | Token extract đã chứa prefix; bỏ prefix ở template hoặc dùng `stripPrefix:Bearer `. |
| Refresh thành công nhưng target vẫn 401 | Kiểm tra refresh flow có extract token mới và provider injection dùng đúng tên biến không. |
| 403 gây login lại | Bỏ failure rule 403 nếu 403 là authorization denial hợp lệ. |
| Dry Run unresolved `auth.*` | Profile chưa có cached state; chạy auth live một lần hoặc dùng state mẫu khi kiểm tra template. |
