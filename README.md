# Chord Offline — kiểm tra tải trực tiếp & lưu lời/hợp âm (Android, Kotlin)

App Android nhỏ dùng để **kiểm tra thực tế trên điện thoại**:
- trang bài hát của Hợp Âm Việt có trả về lời và hợp âm ngay trong HTML server khi tải trực tiếp hay không,
- nếu có thì lưu bản offline cho mục đích cá nhân.

Từ v0.2.0 app có thêm: hợp âm phía trên tự xuống dòng, chuyển tone, tìm kiếm bài đã lưu và tải nhiều URL (xem mục **Tính năng v0.2.0**).

## Tình trạng kiểm chứng (16/10/2026)

| Phần | Trạng thái |
|---|---|
| Parser, kiểm tra selector, so sánh HTML, robots.txt, lưu trữ (`core/`) | ✅ Đã biên dịch bằng Kotlin 2.0.21 và chạy 10 unit test trên JVM: tất cả đều đạt |
| Parser trên file HTML đã lưu từ trình duyệt (bài *Người dưng*) | ✅ Đủ 5 selector; 53 dòng (7 dòng trống); 64 hợp âm inline; khớp với parser Python |
| Tải trực tiếp HTTP từ Android (`HttpFetcher`) | ✅ Đã chạy trên điện thoại thật (v0.1.2, 16/10/2026 19:42): bài *Vì lỡ thương nhau* trả HTTP 200, h2, `text/html; charset=UTF-8`, 358,5 KB, 450 ms, không redirect; robots.txt cho phép |
| Lời/hợp âm trong HTML server | ✅ **Có sẵn, không cần JavaScript**: đủ 5 selector; 25 dòng, 42 hợp âm inline (khớp 42 `span.chord`) |
| Lưu 2 bản độc lập | ✅ Lưu nguyên trang (358,5 KB) và bản gọn (1,8 KB), trạng thái riêng từng bản |
| Biên dịch APK (Compose UI) | ✅ GitHub Actions build thành công |
| Hợp âm phía trên (v0.1.3, trên điện thoại) | ✅ Hiển thị đúng với bài *Người dưng* (ảnh chụp ngày 16/10). Phiên bản v0.2.0 viết lại cách ngắt dòng, **chưa kiểm tra lại trên máy** |
| Chuyển tone, tìm kiếm, tải nhiều (v0.2.0) | ✅ Unit test trên JVM và CI đều đạt; build APK thành công. ⏳ **Chưa kiểm tra trên điện thoại** |
| So sánh HTML server với bản lưu từ trình duyệt (v0.1.3, *Người dưng*, cùng bài W8IUDI0D) | ✅ Lời **giống hệt** (53 dòng, 64 hợp âm). Server 296,4 KB, 510 ms |

### Kết quả so sánh: server render và JavaScript (16/10/2026)

| Có sẵn trong HTML server | Chỉ có sau JavaScript (bản lưu từ trình duyệt) |
|---|---|
| `#lyricBox`, `#lyric-version-0` (lời + `span.chord`), tên bài, tone, ID bài, điệu, tone ca sĩ, thumbnail YouTube, 32 bài liên quan | Danh sách hợp âm sử dụng `#show_tab [chord]`, sơ đồ hợp âm (jtab, SVG), quảng cáo đã nạp, 17 iframe, trình phát YouTube |

Thẻ `<script>`: server có 30, bản lưu có 0. Đây là do trình duyệt bỏ script khi lưu trang, không phải do JavaScript tạo ra.
Kết luận: mọi dữ liệu cần cho bản lời/hợp âm gọn đều có trong HTML server. Danh sách hợp âm sử dụng có thể tự tính từ các `[..]` inline nếu cần.

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
  - tab Kiểm tra URL: mỗi lần bấm chỉ gửi **một request**, cách lần trước ít nhất 5 giây;
  - tab Tải nhiều: tải **tuần tự**, nghỉ **3 giây** giữa các request (tính cả request robots.txt). Gặp HTTP 403/429/503 hoặc trang xác minh thì **tự tạm dừng cả hàng đợi** và không tự thử lại;
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
│   ├── SongStore.kt      # lưu 2 bản độc lập, mỗi bản có trạng thái riêng; kèm ca sĩ/nhạc sĩ/tone để tìm kiếm
│   ├── Transpose.kt      # chuyển tone theo bán cung + TransposeState (hoàn tác)
│   ├── LibrarySearch.kt  # tìm kiếm không dấu, sắp xếp
│   └── BatchQueue.kt     # hàng đợi tải nhiều: thêm/bỏ trùng/trạng thái/lưu ra file
├── net/HttpFetcher.kt    # OkHttp: status, URL sau redirect, Content-Type, kích thước, thời gian
├── ui/                   # Compose: Kiểm tra URL, Đã lưu (tìm kiếm), Tải nhiều, trình xem (chuyển tone)
└── MainActivity.kt
app/src/test/.../core/        # unit test, dùng HTML/lời giả (không chứa nội dung có bản quyền)
    CoreTest, ChordWrapTest, TransposeTest, LibraryAndBatchTest, RegexAndroidCompatTest
```

### Hai chế độ lưu

Hai bản độc lập với nhau: mỗi bản có trạng thái *Chưa lưu / Đang lưu / Đã lưu / Lỗi*, thời điểm lưu và kích thước riêng, và xoá riêng được.

| Bản | File | Nội dung |
|---|---|---|
| Nguyên trang | `page.html` + `page.properties` | Chuỗi HTML **đúng như server trả về**, không qua chuẩn hoá lại vì jsoup pretty-print sẽ làm mất xuống dòng của lời. Kèm URL, HTTP status, Content-Type. Khi xem offline, JavaScript và mạng bị tắt, nên trang không có CSS/ảnh. |
| Lời/hợp âm gọn | `song.cho` + `compact.properties` | Phần đầu là các dòng `{title:}`, `{composer:}`, `{key:}`, `{source:}`, `{fetched_at:}`. Phần lời giữ nguyên dòng trống và hợp âm inline `[Am]`. |

Dữ liệu nằm trong `/data/data/com.binh.chordoffline/files/songs/<ID>/`.

## Tính năng v0.2.0

### Hợp âm phía trên
- Mỗi hợp âm được **neo vào đúng ký tự** của lời. Khi màn hình hẹp hoặc phóng to chữ (A+), dòng tự xuống ở khoảng trắng mà hợp âm vẫn nằm đúng trên chữ.
- Nếu hợp âm ở cuối đoạn bị tràn quá độ rộng, app lùi điểm ngắt dòng về khoảng trắng trước đó.
- Các trường hợp đặc biệt được giữ đúng:
  - dòng trống và nhãn đoạn (`ĐK:`);
  - dòng chỉ có hợp âm, được xếp thành nhiều dòng nếu cần;
  - hai hợp âm liền nhau (cách nhau 1 khoảng trắng);
  - từ dài không có khoảng trắng, được cắt cứng.
- Số cột được tính từ độ rộng màn hình và cỡ chữ monospace hiện tại.

### Chuyển tone
- Các nút **−½ / +½** chuyển tone xuống/lên một bán cung. Có thêm nút **Hoàn tác** từng bước và **Về gốc**. Dòng tone hiển thị dạng `Am → Bm (+2)`.
- Hỗ trợ hợp âm trưởng, thứ, 7, maj7, m7, m7b5, sus2/sus4, dim/dim7, aug/+, add9, 6/9/11/13, `(b9)` và slash chord (`C/E` → `D/F#`).
- Dùng dấu `#` hay `b` tuỳ theo tone đích, ví dụ Am +1 → **Bbm**, Am −1 → **G#m**.
- Ký hiệu không phải hợp âm như `[x2]` hay `[ĐK]` được giữ nguyên.
- **Chỉ đổi phần hiển thị**: file `.cho` gốc không bị sửa. Mức chuyển tone được giữ khi xoay màn hình, và trở về gốc khi đóng bài.

### Tìm kiếm bài đã lưu (tab Đã lưu)
- Tìm theo **tên bài, ca sĩ, nhạc sĩ**, hoặc tất cả cùng lúc. Không phân biệt hoa/thường và **không cần gõ dấu** ("nguoi dung" vẫn ra "Người dưng"). Có thể gõ nhiều từ (bài phải chứa đủ các từ) và gõ dở chữ.
- Sắp xếp theo: Mới lưu, Tên A–Z, Nhạc sĩ A–Z, Ca sĩ A–Z. Bài thiếu thông tin được xếp cuối.
- Mọi thứ chạy offline, mở bài không cần mạng. Bài lưu bằng bản cũ vẫn tìm được, vì app đọc thông tin từ phần đầu file `.cho`.

### Tải nhiều (tab Tải nhiều)
1. Tick ô xác nhận quyền.
2. Dán danh sách link, mỗi dòng một link. Dòng trống và dòng bắt đầu bằng `#` được bỏ qua.
3. Bấm **Thêm vào hàng đợi**. App tự xử lý danh sách:
   - đổi `http` thành `https`, bỏ `www.` và bỏ phần `#…`;
   - **bỏ link trùng**, kể cả trùng với link đã có trong hàng đợi;
   - báo các dòng không hợp lệ kèm lý do (sai website, hoặc là `/chord/printsong/` bị robots.txt chặn).
4. Chọn kiểu lưu: bản gọn, nguyên trang, hoặc cả hai.
5. Bấm **Bắt đầu**. App tải **tuần tự**, nghỉ 3 giây giữa các bài, và hiện tiến độ (Xong / Lỗi / Chờ / Huỷ) cùng thanh phần trăm.
6. Các nút điều khiển:
   - **Tạm dừng**: dừng sau bài đang tải.
   - **Tiếp tục**.
   - **Huỷ**: các mục chưa tải chuyển sang "Đã huỷ", mục đã xong được giữ.
   - **Thử lại lỗi**: đưa các mục lỗi và đã huỷ về hàng chờ.
   - **Bỏ mục xong** và **Xoá hết**.
7. Trạng thái hàng đợi được lưu vào `files/batch_queue.tsv` sau mỗi thay đổi. Nếu app bị tắt giữa chừng, mục "Đang tải" sẽ quay về "Chờ"; mở lại app rồi bấm **Tiếp tục**.
8. Hàng đợi chỉ chạy khi app đang mở, vì app không có dịch vụ chạy nền. Nếu Android tắt app, trạng thái vẫn được giữ như ở bước 7.

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

Nếu workflow báo lỗi biên dịch, hãy gửi log của bước *Build debug APK* để sửa.

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

### Kiểm tra v0.2.0 trên điện thoại
1. **Hợp âm phía trên**: mở bài *Người dưng*, chọn "Hợp âm phía trên", rồi bấm A+ nhiều lần. Kiểm tra hợp âm vẫn nằm đúng trên chữ ở mọi cỡ chữ, và dòng trống trước `T-ĐK:` vẫn còn.
2. **Chuyển tone**: bấm +½ hai lần, tone phải hiện `Am → Bm (+2)` và hợp âm `[G]` thành `[A]`. Bấm **Hoàn tác** một lần thì về +1, bấm **Về gốc** thì về Am. Đóng rồi mở lại bài, hợp âm phải là bản gốc.
3. **Tìm kiếm**: gõ `nguoi dung`, `jack` (ở chế độ Ca sĩ) và `tran kim` (ở chế độ Nhạc sĩ). Bật chế độ máy bay rồi mở bài để chắc chắn xem được offline.
4. **Tải nhiều**:
   - dán 3 link, trong đó có 1 link trùng và 1 link sai website, rồi xem app báo kết quả thêm vào hàng đợi;
   - bấm Bắt đầu, sau đó Tạm dừng, rồi Tiếp tục;
   - thoát hẳn app khi đang tải, mở lại và xem trạng thái còn giữ không;
   - bật chế độ máy bay giữa chừng để tạo lỗi, rồi bấm **Thử lại lỗi**.

## Giới hạn đã biết

- Bản nguyên trang chỉ có HTML, không tải kèm CSS, ảnh hay JS, để giảm số request và tránh sao chép tài nguyên của website.
- Parser phụ thuộc các id hiện tại của trang. Nếu website đổi giao diện, mục **2. Selector** sẽ báo ❌ ngay.
- Chỉ đọc khối `#lyric-version-0`, tức phiên bản lời đầu tiên.
- Chuyển tone không được lưu lại sau khi đóng bài (theo yêu cầu "chỉ đổi hiển thị").
- Hàng đợi tải nhiều không chạy nền khi app đã đóng.
