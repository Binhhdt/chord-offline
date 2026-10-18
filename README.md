# Chord Offline — kiểm tra tải trực tiếp & lưu lời/hợp âm (Android, Kotlin)

App Android nhỏ dùng để **kiểm tra thực tế trên điện thoại**:
- trang bài hát của Hợp Âm Việt có trả về lời và hợp âm ngay trong HTML server khi tải trực tiếp hay không,
- nếu có thì lưu bản offline cho mục đích cá nhân.

Từ v0.2.0 app có thêm: hợp âm phía trên tự xuống dòng, chuyển tone, tìm kiếm bài đã lưu và tải nhiều URL (xem mục **Tính năng v0.2.0**).
Từ v0.4.0: **chữ ký APK cố định** (cài đè không mất dữ liệu), tìm kiếm có gợi ý kiểu website, tone ca sĩ kèm ảnh video YouTube, thư mục lưu file ngoài app (xem mục **Tính năng v0.4.0**).
Từ v0.3.0 app **lấy được danh sách bài từ trang danh mục** để xem trước và chọn, có nhập file TXT, và không tải lại bài đã lưu (xem mục **Tính năng v0.3.0**).

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
| v0.4.0: chữ ký cố định, tìm kiếm gợi ý, tone ca sĩ + YouTube, thư mục lưu file | ✅ 55 unit test trên JVM và CI đều đạt; build thành công; CI kiểm tra chữ ký SHA-256 khớp keystore cố định. Phần đọc ca sĩ chạy đúng trên HTML thật (*Người dưng*: Jack - J97, Bm, video `vLa5jvCJMGk`). ⏳ **Chưa kiểm tra trên điện thoại** |
| Lấy danh sách từ trang danh mục (v0.3.0) | ✅ Unit test trên JVM và CI đều đạt; build APK thành công. Parser đã chạy thử trên HTML thật của trang bài hát (cùng khung với trang danh mục): loại đúng 7 link ở cột bên `aside`. ⏳ **Chưa quét thử trang danh mục thật trên điện thoại** |
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
  - tab Tải nhiều (từ v0.8.0): tải **song song có kiểm soát**, mặc định 3 luồng, **tổng tối đa ~3 request/giây** cho cả app (tính cả request robots.txt). Gặp 429/502/503/504 thì tự giảm luồng và thử lại có backoff (tối đa 3 lần, tôn trọng Retry-After). Gặp 403 hoặc trang xác minh thì **dừng cả hàng đợi** và không thử vượt qua;
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
│   ├── BatchQueue.kt     # hàng đợi tải nhiều: thêm/bỏ trùng/trạng thái/lưu ra file
│   ├── SingerExtractor.kt # ca sĩ + tone + mã video YouTube
│   └── ListingParser.kt  # lấy link bài từ trang danh mục + ListingPolicy + Discovery (giới hạn trang, chống vòng lặp)
├── net/HttpFetcher.kt    # OkHttp: status, URL sau redirect, Content-Type, kích thước, thời gian
├── ui/StorageBackup.kt   # thư mục lưu file (SAF): sao lưu / khôi phục
├── ui/                   # Compose: Kiểm tra URL, Đã lưu (tìm kiếm), Tải nhiều, trình xem (chuyển tone)
└── MainActivity.kt
app/src/test/.../core/        # unit test, dùng HTML/lời giả (không chứa nội dung có bản quyền)
    CoreTest, ChordWrapTest, TransposeTest, LibraryAndBatchTest, ListingTest, SingerAndBackupTest, RegexAndroidCompatTest
keystore/debug.keystore       # chữ ký cố định — KHÔNG xoá/đổi
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

## v0.12.0: bài có nhiều phiên bản lời (Phiên bản 1 / 2 / …)

Trên website, một số bài có các tab "Phiên bản 1", "Phiên bản 2", "Tất cả". Kiểm tra trên trang thật "Lời hứa vu vơ (Paroles, Paroles)": thanh tab là `#lyric-tab-bar`, mỗi nút `#lyric-tab-N` mang tên phiên bản, lời của từng phiên bản nằm trong `#lyric-version-N`. Trước bản này, app chỉ lấy `#lyric-version-0`.

**A. Lấy đủ mọi phiên bản khi tải:**
- `SongParser.extractVersions()` đọc mọi `#lyric-version-N` và lấy tên tab tương ứng.
- File `.cho` vẫn bắt đầu bằng phiên bản 1 như cũ. Các phiên bản sau được nối vào cuối, mỗi phiên bản mở đầu bằng dòng `{version: Phiên bản 2}`, kèm `{version_label: …}` ở phần đầu file.
- **Bài một phiên bản được lưu y hệt định dạng cũ.** Sao lưu, khôi phục, mục lục và tìm kiếm không thay đổi.

**Màn xem bài:**
- Bài có nhiều phiên bản hiện thêm các nút **Phiên bản 1 | Phiên bản 2 | Tất cả**.
- Chuyển tone, hợp âm phía trên, cỡ chữ và màu hợp âm áp dụng cho phiên bản đang xem.

**B1. Không cần mạng:**
- Bài đã lưu "Nguyên trang" mà bản gọn chưa có các phiên bản thì khi mở bài, app đọc lại `page.html` và bổ sung, giữ nguyên thời điểm tải cũ.
- Mỗi bài chỉ thử một lần (cờ `versions_checked`).

**B2. Nút "⟳ Tải lại bài này"** ở cuối màn xem bài:
- Tải lại riêng bài đó: 1 request, qua giới hạn tốc độ chung và robots.txt.
- Cập nhật bản gọn kèm mọi phiên bản. Nếu bài đã có bản nguyên trang thì cập nhật cả bản đó.
- Gặp 403/429/503 hoặc trang xác minh thì dừng và giữ nguyên bản đã lưu.

App **không** tự tải lại cả thư viện để dò bài nhiều phiên bản: khoảng 47.000 request, quá nặng cho website.

## v0.11.1: sửa lỗi bài tải về thiếu ảnh YouTube

**Hiện tượng:** bài mới tải hiện "▶ Video (chưa có ảnh)".

**Nguyên nhân:**
- Ảnh YouTube và tự sao lưu dùng **chung một hàng việc nền**, xử lý lần lượt từng bài.
- Lần sao lưu đầu phải liệt kê thư mục sao lưu (~47.000 thư mục bài) bằng `DocumentFile.listFiles()` rồi đọc `.name`/`.isDirectory` của từng mục. Mỗi lần đọc là một lần hỏi Android, nên mất rất lâu.
- Trong lúc đó, ảnh của các bài vừa tải phải chờ. Tắt app thì những ảnh này mất luôn và không được tải bù.

**Cách sửa:**
- Tách thành **hai hàng riêng**: ảnh YouTube (2 luồng, tải từ img.youtube.com) và sao lưu. Sao lưu chậm không còn chặn ảnh.
- Liệt kê thư mục sao lưu bằng **một truy vấn `DocumentsContract`**, lấy cả tên, loại và kích thước cùng lúc. Áp dụng cho cả sao lưu và khôi phục.
- **Mở một bài mà chưa có ảnh** thì app tự tải bù khi có mạng rồi hiện ngay. Cách này sửa luôn các bài đã bị thiếu ảnh trước đây.

## v0.11.0: kiểm tra bài mới trên Hợp Âm Việt (A + B) + sửa tab Đã lưu

**Sửa lỗi:** bấm tab "Đã lưu" ở thanh dưới vẫn gọi lệnh đọc lại **toàn bộ** thư viện (~15 giây với 47.565 bài). Lệnh này chạy trùng lúc app nạp mục lục nên màn hình báo "0/0 bài". Giờ bấm tab chỉ ghép các bài vừa thay đổi. Nếu đang có lượt đọc toàn bộ (ví dụ vừa Khôi phục), màn hình vẫn hiện "Đang tải thư viện…" tới khi xong.

**A. Nút "Kiểm tra bài mới"** (tab Tải nhiều, thẻ 🆕 "Bài mới trên Hợp Âm Việt"; `core/NewSongCheck.kt`):
- App đọc trang **"Hợp âm mới nhất"** (`/chord/latest`, robots.txt cho phép). Trang này có khoảng 150 bài mới nhất, 30 bài mỗi trang.
- Đọc từ trang 1. Gặp bài đã có trong máy hoặc trong hàng đợi thì dừng, vì các bài phía sau đều cũ hơn. Thường chỉ tốn **1–2 request**, tối đa 5. Giữa các trang app vẫn nghỉ, và dùng chung giới hạn tốc độ với việc tải.
- App hiện danh sách bài mới chưa lưu, có ô chọn, rồi bấm **"Tải N bài mới đã chọn"** (cần tick xác nhận quyền lưu).
- Nếu cả 5 trang đều là bài chưa có, app cảnh báo có thể còn bài mới cũ hơn đã trôi khỏi danh sách, và gợi ý quét lại danh mục.
- Gặp 403/429/503 hoặc trang xác minh thì dừng, không thử vượt qua.

**B. Tự kiểm tra mỗi ngày** (công tắc trong thẻ trên, mặc định bật):
- Dùng WorkManager, chạy một lần mỗi ngày và **chỉ khi có Wi-Fi**. Lần đầu chạy sau khi mở app khoảng 1 giờ.
- Có bài mới **chưa từng báo** thì hiện thông báo "Hợp Âm Việt có bài mới". Chạm vào thông báo sẽ mở tab Tải nhiều cùng danh sách để chọn tải.
- App **không tự tải**. Lỗi mạng thì WorkManager tự thử lại sau. Website giới hạn hoặc chặn thì app bỏ qua hôm đó.
- Android 13 trở lên: app xin quyền thông báo một lần.
- Kết quả lần kiểm tra gần nhất được lưu ở `new_songs.tsv`.

## v0.10.1: sửa sổ mục lục bị rỗng

Video của người dùng với v0.10.0: mở app đã nhanh, nhưng tab Đã lưu vẫn hiện "0/0 bài · Chưa lưu bài nào" khoảng 15 giây rồi mới ra 47.565 bài, và lặp lại y hệt ở lần mở sau.

**Nguyên nhân:**
- File mục lục bị ghi ở trạng thái rỗng. App tin file đó, rồi lúc đối chiếu thấy 47.565 thư mục "mới" nên đọc từng bài một, chậm như quét toàn bộ.
- Mục lục đúng chỉ được ghi sau 5 giây. Người dùng tắt app trước đó, nên lần mở sau vẫn gặp mục lục rỗng.

**Cách sửa:**
- Mục lục rỗng không được tin, app quét toàn bộ.
- App **không bao giờ ghi** mục lục rỗng, và không ghi khi thư viện còn đang nạp.
- Mục lục được ghi **ngay** sau khi quét toàn bộ hoặc sau khi đối chiếu có thay đổi. Các thay đổi khác được ghi gộp sau 2 giây.
- Chỉ một luồng được ghi mục lục tại một thời điểm.

## v0.10.0: mở app & thư viện nhanh hơn, tìm kiếm nhanh, sửa lỗi văng app, hiện số phiên bản

Video của người dùng (thư viện 47.565 bài): mở app bị màn hình trắng ~4 giây, tab Đã lưu mất ~21 giây mới hiện danh sách (trong lúc chờ lại báo "Chưa lưu bài nào"), bấm Tìm mất ~4 giây, và lần chạy trước bị văng app với lỗi `ForegroundServiceDidNotStartInTimeException`.

- **Sửa lỗi văng app:** dịch vụ chạy nền luôn gọi `startForeground()` trước. Nếu app muốn dừng trong lúc dịch vụ chưa kịp hiện thông báo, dịch vụ tự dừng ngay sau đó thay vì bị `stopService()` cắt ngang. Trên Android 15, việc bị cắt ngang này làm app bị đóng.
- **A. Sổ mục lục thư viện** (`library_index.tsv`, `core/LibraryIndex.kt`):
  - Thông tin mọi bài nằm trong một file. Mở app chỉ đọc file này, sau đó đối chiếu với **một lần liệt kê thư mục** để thêm bài mới và bỏ bài đã xoá.
  - Mục lục được ghi gộp mỗi khi thư viện đổi, tối đa mỗi 5 giây, theo kiểu ghi file tạm rồi đổi tên.
  - Chưa có mục lục, mục lục hỏng, hoặc vừa Khôi phục từ thư mục sao lưu: app quét toàn bộ như trước rồi tạo lại mục lục.
  - Trong lúc đọc, màn hình hiện "Đang tải thư viện…" thay vì "Chưa lưu bài nào".
- **B. Tìm kiếm nhanh:**
  - App tính sẵn bản bỏ dấu của tên bài, ca sĩ và nhạc sĩ (`LibrarySearch.Entry`). Khi thư viện thay đổi, chỉ mục được dựng lại ở luồng nền và dùng lại phần không đổi.
  - Gợi ý và kết quả trong lúc tìm hiện "Đang tìm…" thay vì "Không có bài nào".
  - Test: 10 lượt tìm trên 50.000 bài mất ~0,3 giây trên JVM.
- **C. Mở app nhanh hơn:**
  - File hàng đợi được đọc ở luồng nền nên giao diện hiện ngay.
  - Trong lúc đọc, các nút thao tác hàng đợi báo "Đang đọc hàng đợi…", để không ghi đè hàng đợi cũ.
  - Mọi lần ghi file hàng đợi chạy trên một luồng ghi riêng, tuần tự, không còn ghi trên luồng giao diện.
  - Nút "Bỏ mục xong" có sẵn giúp hàng đợi gọn hơn. Bài đã tải vẫn còn trong thư viện và vẫn được nhận ra để không tải trùng.
- **Số phiên bản** hiện ở đầu tab Kiểm tra URL và tab Tải nhiều, và ở dòng đếm bài của tab Đã lưu, dạng "Phiên bản 0.10.0 (bản dựng 18)". App đọc số này từ chính gói APK đang cài.

## v0.9.1: sửa lỗi app đơ ("isn't responding") khi thư viện rất lớn

**Hiện tượng:** thư viện ~15.000 bài, đang tải nền. Mở tab "Đã lưu" rồi gõ tìm kiếm thì app đơ và Android báo "Chord Offline isn't responding".

**Nguyên nhân:**
- Mỗi lần làm mới, `store.list()` đọc lại **toàn bộ** thư viện, khoảng 5–6 file mỗi bài, tức gần 90.000 lần đọc file. Khi đang tải, việc này chạy mỗi 5 giây và không chờ lượt trước xong, nên các lượt chồng lên nhau và làm máy quá tải.
- Lọc, sắp xếp và gợi ý tìm kiếm chạy ngay trên luồng giao diện. Mỗi lần gõ, app bỏ dấu lại cho hàng chục nghìn chuỗi.

**Cách sửa:**
- Đọc toàn bộ thư viện chỉ **một lượt tại một thời điểm**. Gọi thêm trong lúc đang đọc thì app đọc lại đúng một lần sau đó.
- Khi tải, lưu một bài, xoá bài hay mở lại tab: app chỉ đọc **các bài vừa thay đổi** (`SongStore.load(key)`) rồi ghép vào danh sách sẵn có.
- Lọc, sắp xếp và gợi ý chạy ở **luồng nền**. Gợi ý đợi ngừng gõ 0,2 giây rồi mới tìm. Bản bỏ dấu được tính một lần rồi dùng lại (bộ nhớ đệm). Test: 5 lượt tìm trên 20.000 bài phải xong dưới 5 giây trên JVM.

## v0.9.0: quét danh mục nhanh hơn (A + C)

**Vì sao quét chậm:** website trả mỗi trang danh mục trong khoảng 0,3–0,6 giây, nhưng app tốn thêm thời gian ở mấy chỗ sau:
- Sau mỗi trang, app so **từng bài tìm được (tới 600) với cả hàng đợi (hàng nghìn bài)**, chuẩn hoá lại URL mỗi lần so, nên mỗi trang mất hàng triệu phép so.
- App ghi tiến độ quét vào thư mục sao lưu (SAF) sau mỗi trang. Mỗi lần ghi phải liệt kê lại thư mục có hàng nghìn bài.
- Nhật ký quét dài mãi nên màn hình vẽ lại ngày càng nặng.
- Chế độ Tự động phải đợi tải xong một vòng rồi mới quét vòng sau.

**A: bỏ việc phụ** (không tăng tải cho website):
- Chỉ xét các bài **mới** của trang vừa quét. Bài trong hàng đợi được tra bằng bảng băm dựng một lần cho mỗi trang.
- Tiến độ quét trong máy vẫn ghi sau mỗi trang. Bản trong thư mục sao lưu chỉ ghi **một lần khi kết thúc đợt quét**, và app nhớ sẵn vị trí file để không phải liệt kê lại thư mục.
- Nhật ký chỉ giữ **60 dòng** gần nhất.

**C: Tự động quét vòng sau ngay trong lúc tải vòng trước**:
- Quét và tải dùng **chung một cổng tốc độ** (tổng ≤ ~3 request/giây), nên tổng tải lên website không tăng. Giữa các trang quét vẫn nghỉ theo lựa chọn "Nghỉ giữa các trang khi quét danh mục".
- Hàng đợi chỉ giữ trước tối đa **~1 vòng** (số trang × 30 bài). Nhiều hơn thì app đợi tải bớt rồi mới quét tiếp.
- Hàng đợi bị ngắt (website giới hạn/chặn, bạn tạm dừng) thì quét cũng dừng ngay ở trang kế tiếp, rồi chế độ tự động dừng và ghi rõ lý do, như trước.
- Quét bằng tay (nút "Quét danh sách" / "Quét tiếp") vẫn như cũ: không chạy khi hàng đợi đang tải.

## v0.8.0: tải song song có kiểm soát (phương án A + B)

**Vì sao bản cũ chậm** (đo trên mã nguồn v0.7.0):
- `runBatch()` tải **từng bài một**: chờ `pace()` (1–3 giây, tự tăng tới 10 giây khi server chậm) trước mỗi request, rồi mới tải bài kế.
- Sau **mỗi** bài còn làm thêm các việc nằm ngay trên đường tải:
  - `refreshLibrary()` đọc lại toàn bộ thư mục bài;
  - `setQueue()` ghi lại cả file hàng đợi ~2000 dòng (2 lần mỗi bài);
  - tự sao lưu SAF dùng `findFile()`, mà hàm này liệt kê lại cả thư mục gần 2000 bài mỗi lần gọi;
  - tải ảnh YouTube tuần tự.
- Kết nối mạng **không** phải nguyên nhân: app dùng một `OkHttpClient` chung, nên kết nối HTTP/2 keep-alive được dùng lại và không mở kết nối mới cho mỗi bài. App không dùng cookie hay đăng nhập (`CookieJar.NO_COOKIES`), nên không có session nào phải giữ khi tải song song. App viết bằng Kotlin, dùng coroutines + OkHttp; các thư viện Python như asyncio/aiohttp/httpx không áp dụng.

**A: bỏ việc phụ khỏi đường tải** (không tăng tải cho website):
- File hàng đợi được ghi gộp mỗi 3 giây và khi dừng/tạm dừng/thoát, thay vì ghi sau mỗi bài.
- Thư viện "Đã lưu" làm mới tối đa mỗi 5 giây và một lần khi xong.
- Ảnh YouTube và tự sao lưu chạy ở **hàng nền riêng**, không bắt worker phải chờ. Thông báo hiện "Đang hoàn tất…" tới khi hàng nền chạy xong.
- Sao lưu SAF liệt kê thư mục một lần rồi giữ lại để tra nhanh, thay vì gọi `findFile` mỗi lần. Nếu thư mục bị đổi từ bên ngoài, app làm mới bộ nhớ đệm và thử lại.
- Bài coi là "đã có" khi file đã lưu tồn tại **và không rỗng**. File 0 byte (do tắt app giữa chừng) sẽ được tải lại.

**B: song song có kiểm soát** (`core/ConcurrentDownload.kt`):

| Cấu hình | Giá trị | Chọn trong app |
|---|---|---|
| `MAX_WORKERS` | mặc định **3**, chọn 1/2/3 | Tải nhiều → Kiểu lưu → "Số luồng tải cùng lúc" |
| `MAX_REQUESTS_PER_SECOND` | **3**, trần chung cho mọi luồng (cổng `RateGate`) | cố định |
| `DELAY` | nghỉ thêm sau mỗi bài của một luồng, mặc định **0**, chọn 0/0,5/1/2 giây | "Nghỉ thêm sau mỗi bài" |
| `MAX_RETRIES` | **3** | cố định |

- Mỗi bài chỉ một luồng nhận: lệnh `claimNext()` chạy trong vùng khoá, đã có test với 3 luồng × 300 bài, không bài nào bị tải 2 lần.
- Khi website báo bận (429/502/503/504 hoặc lỗi mạng):
  - app giảm một nửa số luồng (tối thiểu 1) và giãn khoảng cách giữa các request;
  - chờ 1 → 2 → 4 giây cộng jitter, hoặc theo **Retry-After** nếu server gửi. Trong lúc chờ Retry-After, cả nhóm luồng đều dừng gửi;
  - khi ổn định lại (20 lần thành công liên tiếp và đã qua 30 giây), app tăng dần số luồng;
  - nếu 3 bài liên tiếp vẫn lỗi sau khi thử lại, app tạm dừng cả hàng đợi.
- Gặp 403 hoặc trang xác minh (CAPTCHA, "Just a moment"…), app **dừng hẳn** và không vượt qua.
- Tiến độ: "Đang tải X/Y · Thành công · Thất bại · Đang chờ · Tốc độ: x,x bài/giây · ETA: m phút ss giây". Tốc độ và ETA đo trên **60 giây gần nhất** (`ThroughputMeter`). Thẻ tiến độ cũng hiện số luồng đang chạy và danh sách bài đang tải. Thông báo trên thanh trạng thái hiện cùng thông tin này.
- **"Thử lại lỗi"** chỉ tải lại đúng các bài lỗi hoặc đã huỷ, không đụng tới các bài đang chờ khác. Nếu tạm dừng giữa lượt thử lại, bấm Tiếp tục sẽ vẫn chỉ tải các bài lỗi đó.
- Quét trang danh mục vẫn tuần tự, nghỉ theo lựa chọn "Nghỉ giữa các trang khi quét danh mục".

Ước tính cho ~770 bài: bản cũ tải 1 bài mỗi 2–3 giây, mất khoảng 30–40 phút. Bản mới bị giới hạn ở tối đa ~3 bài/giây và thực tế phụ thuộc thời gian server trả lời; tốc độ thật hiện ngay trong app. **Chưa đo trên điện thoại thật.**

## v0.7.0: quay lại giữ trang tìm kiếm, chọn khoảng nghỉ, tải chạy nền

- **Sửa lỗi nút quay lại:**
  - **Lỗi cũ:** khi mở một bài từ kết quả tìm kiếm rồi bấm ← (hoặc phím Back), app quay về danh sách từ đầu, mất từ khoá tìm kiếm, gợi ý và vị trí đang cuộn.
  - **Đã sửa:** giờ trình xem mở chồng lên trên danh sách, nên khi quay lại, từ khoá, gợi ý, bộ lọc và vị trí cuộn vẫn giữ nguyên. Áp dụng cho cả 3 tab.
- **Chọn khoảng nghỉ giữa các bài** (tab Tải nhiều → Kiểu lưu):
  - có các mức **3 / 2 / 1,5 / 1 giây**, mặc định 2 giây, thấp nhất 1 giây;
  - vẫn tải tuần tự, không tải song song;
  - website trả lời chậm thì app tự nghỉ bằng thời gian trả lời đó, tối đa 10 giây;
  - gặp 429/503 thì vẫn tự dừng như trước;
  - thời gian còn lại ước tính được tính theo khoảng nghỉ đã chọn. Ví dụ 341 bài: 3 giây ≈ 21 phút, 2 giây ≈ 15 phút, 1 giây ≈ 10 phút.
- **Tải chạy nền:**
  - khi đang quét, tải hoặc chạy tự động, app bật một **dịch vụ tiền cảnh** có thông báo tiến độ (ví dụ "Đang tải 444/785 · ✅443 ❌1 ⏳341 · còn ~15 phút");
  - tắt màn hình hoặc chuyển sang app khác vẫn tải tiếp; bấm vào thông báo để mở lại app;
  - app giữ CPU hoạt động khi tắt màn hình, tối đa 10 giờ;
  - lần đầu chạy, Android 13+ sẽ hỏi quyền hiện thông báo;
  - vuốt tắt app khỏi danh sách đa nhiệm thì dừng tải, nhưng hàng đợi và tiến độ vẫn được lưu để tải tiếp.
  - Android 15 giới hạn thời gian chạy của loại dịch vụ này. Khi hết giờ, app dừng và vẫn lưu trạng thái; mở app rồi bấm Tiếp tục.

## v0.6.1: tự động quét & tải đến hết

- Tab Tải nhiều → Từ trang danh mục có nút **"▶ Tự động quét & tải đến hết (từ trang X)"**. App tự lặp các bước sau:
  1. quét N trang (theo ô "Số trang tối đa");
  2. tự thêm các bài **chưa lưu** vào hàng đợi;
  3. tải hết các bài đó;
  4. quét tiếp N trang sau.
- App tự dừng khi gặp một trong các trường hợp sau:
  - **tới trang cuối**, báo "✅ Đã tới trang cuối — hoàn tất";
  - bạn bấm **Dừng tự động** (hoặc Dừng quét / Tạm dừng);
  - website giới hạn (403/429/503 hoặc trang xác minh);
  - gặp lỗi khi quét.
- Vẫn tải **tuần tự** và **nghỉ giữa các request** như khi tải bằng tay, không tải song song.
- Tiến độ quét được lưu sau mỗi trang. Dừng lúc nào thì bấm lại nút tự động là chạy tiếp từ đúng trang đó; bài đang chờ trong hàng đợi vẫn được giữ.
- **Giữ màn hình sáng** trong lúc quét, tải hoặc chạy tự động, ở bất kỳ tab nào, để Android không tạm dừng app. Nên cắm sạc.
- Danh sách xem trước được làm mới ở mỗi vòng, nhật ký chỉ giữ 60 dòng gần nhất, để app không nặng khi chạy lâu.

## v0.6.0: giao diện & icon mẫu D "Tối giản xanh"

- **Giao diện mẫu D**, thay cho mẫu B "Sân khấu đêm" của v0.5.0:
  - nền sáng `#F4F5FA` và thẻ trắng, chữ đậm `#151826`, một điểm nhấn **xanh chàm `#2B3CC2`**;
  - vẫn dùng font Be Vietnam Pro, tiêu đề ExtraBold;
  - thanh trạng thái và thanh điều hướng dùng nền sáng với biểu tượng tối;
  - nền cửa sổ sáng ngay từ khi mở app.
- **Icon mới:** miếng gảy đàn màu trắng có mũi tên tải xuống, nằm trên nền xanh chàm. Đây là icon thích ứng, có kèm bản một màu cho "Biểu tượng theo chủ đề" trên Android 13 trở lên.
- **Màu hợp âm cho nền sáng:**
  - 10 màu: Xanh chàm (mặc định), Xanh dương, Xanh ngọc, Xanh lá, Cam đất, Đỏ, Hồng, Tím, Nâu, Đen. Có test kiểm tra tất cả đạt tương phản ≥ 4.5:1 trên nền trắng;
  - màu đã chọn ở bản tối (như hổ phách) khó đọc trên nền trắng, nên app tự chuyển về xanh chàm. Màu nào vẫn đọc tốt thì được giữ.
- Mọi chức năng khác giữ nguyên.

## v0.5.1: khôi phục linh hoạt & nhớ tiến độ quét

- **Khôi phục** nhận đúng thư mục bất kể bạn chọn thư mục nào:
  - thư mục cha, ví dụ `Download/Hopamviet`;
  - chính thư mục `ChordOffline`;
  - thư mục đang chứa trực tiếp các thư mục bài (`W8IU…`).

  Trước đây app chỉ nhận thư mục cha.
- **Nhớ đã quét tới trang mấy** cho từng danh mục: sau mỗi trang quét xong, app lưu trang xa nhất, tổng số trang và trang để quét tiếp vào `files/scan_progress.tsv`. App cũng sao lưu thông tin này vào thư mục lưu file (`ChordOffline/_tien_do_quet.tsv`), nên tắt app hay cài lại rồi Khôi phục vẫn còn.
- Tab Tải nhiều có thêm khung **"Tiến độ các danh mục đã quét"**, mỗi dòng hiện dạng `category/2/nhac-tru-tinh — Đã quét tới trang 40/599 · quét tiếp từ trang 41`. Bấm vào một dòng để chọn danh mục đó, rồi bấm **Quét tiếp**.
- Nhật ký quét ghi thêm **số bài đã lưu** trên mỗi trang, ví dụ `Trang 5/599: 30 bài (30 mới, 30 đã lưu)`, để thấy ngay đã tải tới đâu.

## Tính năng v0.5.0: giao diện "Sân khấu đêm" & màu hợp âm

- **Giao diện mẫu B (đã chọn):**
  - nền tối `#0E0F12` / `#15171C`, chữ `#EDEAE3`, điểm nhấn hổ phách `#F2A33A`;
  - font **Be Vietnam Pro** đóng gói sẵn trong app, hỗ trợ đầy đủ tiếng Việt (giấy phép SIL OFL 1.1, xem `app/src/main/assets/licenses/OFL-BeVietnamPro.txt`);
  - tiêu đề dùng Be Vietnam Pro ExtraBold thay cho Lexend như trong bản thiết kế, vì Lexend chỉ có dạng font biến thể, dễ lỗi trên Android;
  - nền cửa sổ tối ngay từ khi mở app, không bị nháy trắng.
- **Icon mới:** cặp ngoặc `[ ]` màu hổ phách bao quanh thế bấm hợp âm. Đây là icon thích ứng (adaptive icon) nên tự khớp với hình dạng icon của máy, kèm bản một màu cho "Biểu tượng theo chủ đề" trên Android 13 trở lên.
- **Đổi màu hợp âm** (trình xem bản gọn → nút **● Màu**):
  - 10 màu có sẵn: Hổ phách, Vàng chanh, Cam đào, Đỏ san hô, Hồng, Tím, Xanh dương, Xanh ngọc, Xanh lá, Trắng. Có test kiểm tra tất cả đều đạt tương phản ≥ 4.5:1 trên nền tối;
  - nhập mã màu tuỳ chỉnh `#RRGGBB`; nếu màu quá tối, app cảnh báo tỉ lệ tương phản;
  - tuỳ chọn **tô nền mờ sau hợp âm**;
  - nút **Mặc định** để quay về màu hổ phách;
  - màu được lưu cho mọi bài, áp dụng cho chế độ Inline, Hợp âm phía trên và cả khi xem Nguyên trang. Chỉ đổi phần hiển thị, file đã lưu không bị sửa.

## Tính năng v0.4.0

### Chữ ký APK cố định (cài đè không mất dữ liệu)
- Android chỉ cho cài bản mới đè lên bản cũ khi **cùng chữ ký**. Trước v0.4.0, mỗi lần GitHub Actions build lại tạo một khoá debug ngẫu nhiên, nên muốn cài bản mới phải gỡ app, và dữ liệu bị mất.
- Từ v0.4.0, mọi bản build đều ký bằng `keystore/debug.keystore` đã commit trong repo:
  - alias `androiddebugkey`, mật khẩu chuẩn `android`;
  - SHA-256 `92:54:BD:BA:E6:B9:9D:F0:73:C9:59:56:1F:10:35:43:FD:9C:C2:BF:48:C5:14:14:E0:30:51:F3:04:78:B5:D2`.
- CI có bước **"Kiểm tra chữ ký cố định"**: build sẽ báo lỗi nếu APK không ký bằng khoá này.
- **Lần chuyển sang v0.4.0 vẫn phải gỡ bản cũ đúng một lần**, vì bản cũ ký bằng khoá khác. Từ v0.4.0 trở đi chỉ cần cài đè.
- Keystore nằm trong repo **private**. Không xoá hay đổi file này: nếu mất, các bản sau sẽ không cài đè lên được nữa. Nên giữ thêm một bản sao ở nơi khác.

### Tìm kiếm kiểu website (tab Đã lưu)
- Ô tìm kiếm có 🔍, nút 🎤 nói để tìm (dùng nhận giọng nói tiếng Việt của máy), ✕ để xoá, và nút **Tìm**.
- Khi gõ, app hiện tối đa **6 gợi ý**: 🎵 tên bài và ♪ ca sĩ (hoặc nhạc sĩ). Thứ tự ưu tiên: tên bài bắt đầu bằng từ đang gõ, rồi tên bài có chứa từ đó, rồi khớp ca sĩ hoặc nhạc sĩ. Không cần gõ dấu.
- Bấm vào một gợi ý để **mở bài ngay, offline**. Bấm **"Xem tất cả kết quả cho …"** (hoặc nút Tìm, phím tìm trên bàn phím) để lọc cả danh sách.
- Tìm kiếm chỉ chạy trên **các bài đã lưu trong máy**. App không gọi trang tìm kiếm của website, vì robots.txt của Hợp Âm Việt chặn `/chord/search`.

### Tone ca sĩ kèm video YouTube
- **Cách app đọc dữ liệu:** trên trang bài hát, mỗi ca sĩ là một mục `#song-item-N`, gồm tên, tone và `data-play-id`. Server chỉ render sẵn ảnh của **video đầu tiên** (`#main_player`). App tìm thêm mã video trong thuộc tính HTML và trong script gần `data-play-id`. Nếu video của ca sĩ nào chỉ được nạp bằng JavaScript, app hiển thị ca sĩ đó **không có ảnh**, không tự bịa ra video.
- **Khi lưu bài**, app tải thêm ảnh thu nhỏ khoảng 10 KB từ `img.youtube.com` và lưu vào `yt_<mã>.jpg`. Nhờ vậy khi **offline vẫn thấy ảnh**, để nhận biết ca sĩ nam hay nữ.
- **Trong trình xem bản gọn**, mục "Tone ca sĩ" có:
  - ảnh video, bấm vào để mở YouTube (**cần có mạng**);
  - tên và tone của ca sĩ;
  - nút **"Dùng tone này"**: chuyển hợp âm sang tone ca sĩ, ví dụ Am → Bm (+2), và vẫn hoàn tác được.
- Bài lưu bằng bản cũ vẫn hiện tên và tone ca sĩ, nhưng không có ảnh. Muốn có ảnh thì lưu lại bài đó.
- Tab Kiểm tra URL có thêm dòng **"Ca sĩ / video"**, cho biết số ca sĩ và số ca sĩ có video tìm được trong HTML server.

### Thư mục lưu file (tab Đã lưu → 📁)
- Chọn một thư mục, ví dụ `Download/HopAm`. App chép mỗi bài vào `<thư mục>/ChordOffline/<mã bài>/`, gồm `song.cho`, `page.html`, `info.properties`, các file trạng thái và ảnh `yt_*.jpg`.
- Các nút: **Sao lưu tất cả** (chỉ ghi file mới hoặc đã thay đổi), **Tự sao lưu sau mỗi lần lưu** (bật sẵn), **Khôi phục** (chép các bài từ thư mục vào app, giữ nguyên bài đang có).
- Bản sao trong thư mục này **vẫn còn khi gỡ app**: cài lại, chọn lại đúng thư mục rồi bấm Khôi phục. Khi khôi phục, app chỉ nhận đúng các tên file nó tự tạo và từ chối đường dẫn `../`.
- Đây là bản sao cá nhân trên máy bạn. Theo điều khoản của website, đừng chia sẻ thư mục này ra ngoài.

### Kiểm tra v0.4.0 trên điện thoại
1. **Lần này**: nếu còn bài muốn giữ, hãy chép lại danh sách link trước. Sau đó gỡ bản cũ và cài v0.4.0.
2. Vào tab Đã lưu → 📁 → **Chọn thư mục**, bật tự sao lưu. Lưu thử bài *Người dưng*, rồi mở trình quản lý file kiểm tra đã có `ChordOffline/W8IUDI0D/`.
3. Mở bản gọn của *Người dưng*: mục Tone ca sĩ phải có ảnh video của Jack - J97 và tone Bm. Bật chế độ máy bay, ảnh vẫn phải hiện. Bấm "Dùng tone này", tone phải hiện `Am → Bm (+2)`.
4. Gõ `nguoi` vào ô tìm kiếm: phải có gợi ý, bấm vào là mở được bài. Bấm "Xem tất cả kết quả" để lọc cả danh sách.
5. Cài đè một bản build mới hơn (lần push sau): **không cần gỡ app**, dữ liệu vẫn còn.

## Tính năng v0.3.0: lấy danh sách từ trang danh mục

### Cấu trúc HTML thực tế (kiểm tra ngày 16/10/2026)
Mình đã kiểm tra trang `https://hopamviet.vn/chord/category/3/nhac-tre.html` và file HTML thật đã lưu của một trang bài hát. Hai loại trang dùng chung khung giao diện:

| Phần | Cấu trúc | App xử lý |
|---|---|---|
| Danh sách chính | `div.lg:col-span-8` chứa các thẻ `a.song-card`, **30 bài/trang** | Chỉ lấy link `/chord/song/<slug>/<ID>.html` ở đây |
| Cột bên "Bài hát hot"/"Thịnh hành" | `aside.lg:col-span-4` (10 bài, có thể trùng với danh sách chính) | **Bỏ qua** |
| Menu, chân trang, hộp thoại | `header`, `nav`, `footer`, id chứa `modal` | **Bỏ qua** |
| Phân trang | `?page=N` đánh số **từ 0** (trang "2" là `?page=1`). Danh mục Nhạc Trẻ có 636 trang (khoảng 19.080 bài) | Chỉ theo link có `page` = trang hiện tại + 1 của **cùng danh sách** |
| Tên bài / ca sĩ | `.font-bold` / dòng `text-[13px]` → `span.truncate` (bỏ nhãn "HOT") | Hiển thị ở phần xem trước |

Nếu không tìm thấy cột chính, app chuyển sang chế độ dự phòng: lấy toàn trang nhưng vẫn loại `aside`, `nav`, `header` và `footer`, đồng thời ghi cảnh báo "⚠ không thấy cột chính" vào nhật ký quét.

### Trang được hỗ trợ
- **Được hỗ trợ:**
  - `/chord/category/…` (thể loại), `/chord/composer/…` (nhạc sĩ), `/chord/singer/…` (ca sĩ);
  - `/chord/rhythm/…` (điệu), `/chord/hot` (bài hot), `/chord/latest` (bài mới).
- **Không hỗ trợ trang tìm kiếm `/chord/search`**, vì robots.txt của Hợp Âm Việt chặn đường dẫn này. App báo rõ lý do và gợi ý dùng trang thể loại, nhạc sĩ hoặc ca sĩ thay thế.

### Cách dùng (tab Tải nhiều → "Từ trang danh mục")
1. Tick ô xác nhận quyền.
2. Dán link trang danh sách và chọn **số trang tối đa**: mặc định 2, tối đa 20, khoảng 30 bài mỗi trang.
3. Bấm **Quét danh sách**. App tải các trang **tuần tự**, nghỉ 3 giây giữa các trang, và ghi nhật ký cho từng trang, ví dụ `Trang 1: 30 bài (30 mới) · HTTP 200 · 450 ms · bỏ 10 link ngoài danh sách chính`. Có nút **Dừng quét**.
4. App **chỉ lấy link, chưa tải bài nào**. Phần **xem trước** liệt kê tên bài và ca sĩ, đánh dấu bài "✓ đã lưu" hoặc "trong hàng đợi"; các bài này được bỏ chọn sẵn. Có các nút **Chọn tất cả**, **Bỏ chọn** và **Chỉ bài chưa lưu**; chạm vào từng bài để chọn hoặc bỏ chọn.
5. Bấm **Xác nhận & tải N bài**. App thêm các bài đã chọn vào hàng đợi (bỏ link trùng) và bắt đầu tải.

### Quét tiếp & báo hết trang (v0.4.1)
- Mỗi lần quét được tối đa 20 trang. Khi dừng vì **đủ số trang**, app nhớ trang kế tiếp và hiện nút **"Quét tiếp 20 trang (trang 21–40/599)"**. Bấm nút để quét đợt sau mà không cần sửa URL.
- Danh sách xem trước và nhật ký của đợt trước **được giữ lại**, bài mới được nối thêm vào cuối. Bài đã lưu hoặc đã có trong hàng đợi vẫn được bỏ chọn sẵn.
- **Tổng số trang** được đọc từ thanh phân trang (ví dụ 599), nên nhật ký hiện dạng `Trang 21/599`.
- Nếu thanh phân trang không có link trực tiếp sang trang kế (ví dụ chỉ hiện `1 … 599`) nhưng vẫn còn trang phía sau, app tự tạo link `?page=N` theo đúng mẫu URL của website.
- Khi tới trang cuối, app báo **"✅ Đã tới trang cuối — hết trang"** và ẩn nút Quét tiếp.
- Nếu bấm **Dừng quét**, hoặc bị lỗi mạng hay bị website chặn giữa chừng, nút Quét tiếp sẽ bắt đầu lại từ đúng trang chưa quét xong.
- Đổi URL thì app quên điểm quét tiếp của danh sách cũ.
- Muốn bắt đầu từ một trang bất kỳ, vẫn có thể nhập URL dạng `…?page=20` (tức trang 21, vì số trang đếm từ 0).

### Giới hạn phạm vi và chống vòng lặp
App dừng quét khi gặp **một trong các trường hợp** sau, và ghi lý do vào nhật ký:
- đủ số trang đã chọn (tối đa 20 trang);
- đủ 600 bài trong một lần quét;
- hết trang, tức không còn link sang trang kế tiếp;
- trang vừa quét **không có bài mới**;
- link trang kế tiếp trỏ về **trang đã quét**: app nhận biết `nhac-tre.html`, `nhac-tre?page=0` và `www.` là cùng một trang.

App **không bao giờ đi theo link sang danh sách khác**, nên không có chuyện quét toàn bộ website.

### Khi bị chặn hoặc không lấy được link
- Website trả HTTP 403, 429, 503 hoặc trang xác minh chống bot: app **dừng quét ngay** và báo rõ, không thử vượt qua.
- Trang đầu không có link bài nào: app báo đây không phải trang danh sách, hoặc cấu trúc trang đã thay đổi.
- robots.txt không cho phép đường dẫn: app báo và dừng.

### Hàng đợi (cải tiến)
- **Mặc định lưu cả nguyên trang và bản gọn** cho mỗi bài. Vẫn chọn lại được ở mục "Kiểu lưu".
- **Không tải lại bài đã lưu thành công.** Trước khi gửi request, app kiểm tra bài đã có đủ các bản được chọn chưa. Nếu đủ rồi, bài được đánh dấu "Xong · Đã có trong máy — bỏ qua" và app **không gửi request nào**.
- Tiến độ hiện tổng số bài, số thành công, thất bại, đang chờ và đã huỷ. Các nút tạm dừng, tiếp tục, huỷ, thử lại lỗi giữ nguyên như v0.2.0. Trạng thái vẫn được lưu vào `files/batch_queue.tsv`.
- Chế độ **Dán link / TXT** giữ nguyên, và có thêm nút **Nhập file TXT**: mỗi dòng một link, tối đa 1 MB, dòng bắt đầu bằng `#` được bỏ qua.

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

### Kiểm tra v0.3.0 trên điện thoại
1. Ở tab Tải nhiều → "Từ trang danh mục", để URL mặc định (Nhạc Trẻ) và chọn 2 trang, rồi bấm **Quét danh sách**:
   - nhật ký phải có 2 dòng, mỗi trang khoảng 30 bài, và có dòng "bỏ … link ngoài danh sách chính";
   - danh sách xem trước khoảng 60 bài, **không có** các bài của cột "Bài hát hot".
2. Bấm **Bỏ chọn**, rồi chọn 2–3 bài và bấm **Xác nhận & tải**. Kiểm tra mỗi bài trong tab Đã lưu có đủ 2 bản.
3. Quét lại cùng trang: các bài vừa tải phải hiện "✓ đã lưu" và được bỏ chọn sẵn. Bấm "Chọn tất cả" rồi tải lại: các bài đó phải hiện "Đã có trong máy — bỏ qua".
4. Dán `https://hopamviet.vn/chord/search?q=abc`: app phải báo bị robots.txt chặn.
5. Nhập một file TXT có vài link bài hát.

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
- Quét danh mục phụ thuộc bố cục hiện tại của website (`lg:col-span-8`, `aside`, `?page=`). Nếu website đổi giao diện, nhật ký quét sẽ báo "không thấy cột chính" hoặc "không tìm thấy link".
- Không hỗ trợ trang tìm kiếm, vì robots.txt của website chặn.
