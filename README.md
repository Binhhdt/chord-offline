# Chord Offline — kiểm tra tải trực tiếp & lưu lời/hợp âm (Android, Kotlin)

App Android nhỏ dùng để **kiểm tra thực tế trên điện thoại**:
- trang bài hát của Hợp Âm Việt có trả về lời và hợp âm ngay trong HTML server khi tải trực tiếp hay không,
- nếu có thì lưu bản offline cho mục đích cá nhân.

App chưa được chạy thử trên thiết bị thật. Kết quả tải trực tiếp chỉ có sau khi bạn chạy app theo mục **Cách kiểm tra**.

## Tình trạng kiểm chứng (16/10/2026)

| Phần | Trạng thái |
|---|---|
| Parser, kiểm tra selector, so sánh HTML, robots.txt, lưu trữ (`core/`) | ✅ Đã biên dịch bằng Kotlin 2.0.21 và chạy 10 unit test trên JVM: tất cả đều đạt |
| Parser trên file HTML đã lưu từ trình duyệt (bài *Người dưng*) | ✅ Đủ 5 selector; 53 dòng (7 dòng trống); 64 hợp âm inline; khớp với parser Python |
| Tải trực tiếp HTTP từ Android (`HttpFetcher`) | ✅ Đã chạy trên điện thoại thật (v0.1.2, 16/10/2026 19:42): bài *Vì lỡ thương nhau* trả HTTP 200, h2, `text/html; charset=UTF-8`, 358,5 KB, 450 ms, không redirect; robots.txt cho phép |
| Lời/hợp âm trong HTML server | ✅ **Có sẵn, không cần JavaScript**: đủ 5 selector; 25 dòng, 42 hợp âm inline (khớp 42 `span.chord`) |
| Lưu 2 bản độc lập | ✅ Lưu nguyên trang (358,5 KB) và bản gọn (1,8 KB), trạng thái riêng từng bản |
| Biên dịch APK (Compose UI) | ✅ GitHub Actions build thành công |
| So sánh với file HTML đã lưu trên điện thoại / mở offline ở chế độ máy bay | ⏳ Chưa kiểm tra |

Lỗi đã gặp và đã sửa: v0.1.0–0.1.1 crash do `PatternSyntaxException`. Regex ICU trên Android không chấp nhận `}`/`]` chưa escape. Lỗi đã được sửa ở v0.1.2, kèm test `RegexAndroidCompatTest` để chặn lỗi này tái diễn.

## Tuân thủ website

Theo `robots.txt` và [Điều khoản](https://hopamviet.vn/info/policy), đọc ngày 16/10/2026:

- `robots.txt` cho phép `/chord/song/`. Các đường dẫn bị chặn gồm `/chord/printsong/`, `/chord/search`, `/auth/`, `/user/` và `/sheet/download/`. App đọc `robots.txt` trước lần tải đầu tiên; nếu không đọc được thì dùng bản sao dự phòng. App từ chối URL bị chặn và chỉ nhận URL dạng `https://hopamviet.vn/chord/song/...`.
- Điều khoản cho phép sử dụng **phi thương mại**, nhưng yêu cầu **ghi nguồn và giữ đường dẫn gốc**. Việc **sao chép, sửa đổi hoặc đăng lại** cần **văn bản chấp thuận trước**. Vì vậy:
  - người dùng phải tick xác nhận quyền trước khi tải;
  - mỗi bản lưu đều ghi URL nguồn và thời điểm tải;
  - dữ liệu chỉ nằm trong bộ nhớ riêng của app: không đồng bộ, không có nút chia sẻ, `allowBackup=false`;
  - nếu bạn định phát hành app hoặc chia sẻ bản lưu, hãy **xin phép Hợp Âm Việt bằng văn bản** trước.
- Hành vi mạng của app:
  - mỗi lần bấm chỉ gửi **một request**, cách lần trước ít nhất 5 giây;
  - User-Agent ghi đúng tên app, không giả làm trình duyệt;
  - không cookie, không cache, không đăng nhập;
  - không vượt captcha hay chặn bot, không chạy JavaScript ẩn để lấy nội dung.

## Cấu trúc

```
app/src/main/java/com/binh/chordoffline/
├── core/                 # Kotlin thuần + jsoup, test được trên JVM
│   ├── SongParser.kt     # đọc lời/hợp âm & thông tin bài
│   ├── ChordText.kt      # định dạng .cho (ChordPro), tách hợp âm lên trên
│   ├── HtmlInspection.kt # kiểm tra selector, so sánh server vs bản lưu, chẩn đoán
│   ├── RobotsRules.kt    # robots.txt + giới hạn URL
│   └── SongStore.kt      # lưu 2 bản độc lập, mỗi bản có trạng thái riêng
├── net/HttpFetcher.kt    # OkHttp: status, URL sau redirect, Content-Type, kích thước, thời gian
├── ui/                   # Compose: màn Kiểm tra URL, Đã lưu, trình xem
└── MainActivity.kt
app/src/test/.../CoreTest.kt  # dùng HTML mẫu với lời giả
```

### Hai chế độ lưu

Hai bản độc lập với nhau: mỗi bản có trạng thái *Chưa lưu / Đang lưu / Đã lưu / Lỗi*, thời điểm lưu và kích thước riêng, và xoá riêng được.

| Bản | File | Nội dung |
|---|---|---|
| Nguyên trang | `page.html` + `page.properties` | Chuỗi HTML **đúng như server trả về**, không qua chuẩn hoá lại vì jsoup pretty-print sẽ làm mất xuống dòng của lời. Kèm URL, HTTP status, Content-Type. Khi xem offline, JavaScript và mạng bị tắt, nên trang không có CSS/ảnh. |
| Lời/hợp âm gọn | `song.cho` + `compact.properties` | Phần đầu là các dòng `{title:}`, `{composer:}`, `{key:}`, `{source:}`, `{fetched_at:}`. Phần lời giữ nguyên dòng trống và hợp âm inline `[Am]`. |

Dữ liệu nằm trong `/data/data/com.binh.chordoffline/files/songs/<ID>/`.

## Build APK bằng GitHub Actions

1. Tạo một repo **private** trên GitHub. Nên để private vì đây là app cá nhân.
2. Đẩy toàn bộ thư mục này lên nhánh `main`:
   ```bash
   cd ChordOffline
   git init && git add . && git commit -m "Chord Offline: kiểm tra tải trực tiếp"
   git branch -M main
   git remote add origin https://github.com/<bạn>/chord-offline.git
   git push -u origin main
   ```
3. Mở tab **Actions** → workflow **Build APK**. Workflow tự chạy khi push; muốn chạy lại thì bấm *Run workflow*. Workflow chạy `testDebugUnitTest` rồi `assembleDebug`.
4. Khi workflow chạy xong (dấu ✅), mở lần chạy đó và tải artifact **chord-offline-debug-apk**. Giải nén để lấy `app-debug.apk`.
5. Cài lên điện thoại:
   - chép file APK vào máy, bật "Cài ứng dụng không rõ nguồn gốc" rồi mở file; hoặc
   - dùng lệnh `adb install -r app-debug.apk`.

Nếu workflow báo lỗi biên dịch, hãy gửi cho mình log của bước *Build debug APK* để sửa. Phần UI chưa được build lần nào trước lần chạy đầu tiên này.

Muốn build trên máy tính: cài Android Studio (JDK 17, SDK 35), mở thư mục rồi chạy `./gradlew assembleDebug`.

## Cách kiểm tra trên điện thoại

1. Mở app, vào tab **Kiểm tra URL**. URL mặc định là bài *Người dưng*.
2. Tick ô xác nhận quyền, rồi bấm **Tải trực tiếp & kiểm tra**.
3. Ghi lại các thông tin ở mục **1. Phản hồi HTTP**: status, URL sau redirect, số lần redirect, Content-Type, kích thước, thời gian.
4. Ở mục **2. Selector**, xem 5 selector `#lyricBox`, `#lyric-version-0`, `#songHeaderCard h1`, `#song-tone`, `#btn-like[data-song-id]` được ✅ hay ❌.
5. Ở mục **3. So sánh**, chọn file HTML bạn đã lưu từ trình duyệt (chép file vào máy trước). App sẽ chia kết quả thành 4 nhóm:
   - *Có trong HTML server*: nội dung server render sẵn.
   - *Chỉ có trong bản đã lưu*: do JavaScript tạo ra, ví dụ sơ đồ hợp âm jtab đã vẽ, iframe quảng cáo, trình phát YouTube.
   - *Chỉ có trong HTML server*.
   - *Không có ở cả hai*.

   Dòng "Lời giống hệt" cho biết lời trong HTML server có trùng với lời trong bản lưu hay không.
6. Nếu có lời:
   - ở mục **4**, bấm **Lưu** cho từng bản và kiểm tra trạng thái từng bản;
   - mở tab **Đã lưu**, rồi bật chế độ máy bay và mở lại cả hai bản để chắc chắn xem được offline;
   - thử xoá một bản và kiểm tra bản còn lại vẫn còn.
7. Nếu không có lời, app hiện mục *Không lấy được lời/hợp âm* với nguyên nhân rút ra từ phản hồi thật: HTTP 403/429, trang xác minh chống bot, Content-Type sai, thiếu `#lyricBox`, hoặc `#lyric-version-0` rỗng (lời được nạp bằng JS). App **không** thử vượt qua các trường hợp này.

### Biên bản kiểm tra (điền sau khi chạy)

| Mục | Kết quả thực tế |
|---|---|
| Thiết bị / Android | |
| Mạng (Wi-Fi/4G) | |
| HTTP status | |
| URL sau redirect | |
| Content-Type | |
| Kích thước / thời gian | |
| 5 selector (✅/❌) | |
| Lời giống bản lưu? | |
| Nội dung chỉ có sau JS | |
| Lưu nguyên trang / bản gọn | |
| Mở offline (chế độ máy bay) | |

## Giới hạn đã biết

- Bản nguyên trang chỉ có HTML, không tải kèm CSS, ảnh hay JS, để giảm số request và tránh sao chép tài nguyên của website.
- Parser phụ thuộc các id hiện tại của trang. Nếu website đổi giao diện, mục **2. Selector** sẽ báo ❌ ngay.
- Chỉ đọc khối `#lyric-version-0`, tức phiên bản lời đầu tiên.
