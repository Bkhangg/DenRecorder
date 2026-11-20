# DenRecorder

A small, fast screen recorder for Android — native MediaCodec recording, no ads, no tracking.

🇬🇧 English · [🇻🇳 Tiếng Việt](#tiếng-việt)

---

## English

DenRecorder records the whole screen (and the device audio, the microphone or nothing) straight from Android's own encoders. It is written in plain Java against the Android framework, so the APK stays small (about 160 KB, most of it the Shizuku client behind the beta island feature).

### Features

- **Exact screen size** — a native recording is saved at the real screen resolution (for example 1220 × 2712), not scaled down to the encoder's 16-pixel grid.
- **Quality presets** — Native, 4K, 2K, Full HD, HD and SD, keeping the screen's aspect ratio. Videos stay inside what phone decoders handle (3840 px on the long side, H.264 level 5.1 frame size), so a 4K recording plays in every gallery and player.
- **15–50 Mbps** variable bitrate, **H.264 AVC** or **H.265 HEVC**, up to **120 fps**.
- **Audio** — internal device audio (Android 10+), microphone, or silent.
- **Quick Settings tile** — tap to start and stop without opening the app (with a toast when the recording starts or is saved); press and hold the tile to open the app.
- **One tap to record (optional, Shizuku)** — Android asks for the screen-capture permission every time; with Shizuku running, DenRecorder presses that button itself as soon as the dialog is up, at the place it found the first time, so recording starts at once. Android 16 gives an app no way to skip the dialog completely, even with the app-op set; the dialog is gone in well under a second. The option can be turned off again in Settings.
- **Xiaomi Super Island (beta, Xiaomi only)** — the running recording shows up in HyperOS's island as a small icon with its timer, and a tap on it gives **pause, resume and stop** right on the bar (the stop button is a filled square, not a cross); it can be hidden entirely, and HyperOS hides it by itself whenever the status bar is pulled down (the notification there carries the same buttons). Needs Shizuku once to grant the island permission.
- **Game performance mode** — caps the recorder at 60 fps and runs the encoder in realtime priority, so a game next to it keeps its frame rate.
- **Recordings screen** — recent/all lists with real thumbnails, the video's own name and the folder it is saved in; tap to play, long-press for play/share/**rename**/delete, or tap **Select** to pick several videos and delete them at once.
- **Draw on screen** — a floating button while recording: pencil, rectangle or circle drawn straight onto the screen (they end up in the video), pause and stop from the same bar. A tool stays picked, so several shapes can be drawn one after another, and the bar carries the whole palette (12 colours): the colour can be changed in the middle of a recording, and every shape keeps the colour it was drawn with. The **✕** stops drawing: the tool goes off, the bar closes and the app under it takes taps again, while what was drawn stays on screen. The button carries a red ring while a tool is on, and touching the screen beside the bar hides it but keeps the tool, so a tool, a colour and a stroke take one visit to the bar. The button's size and whether it stays in sight are picked in settings.
- **Pause and resume** — one recording stays one file; the paused time is cut out of the video and the timer stops while paused.
- **The screen stays awake** — the display is kept on (dimmed) for as long as a recording runs, so a phone that would have napped mid-game does not put a black stretch in the video. If the screen is turned off by hand, Android ends the screen capture: the video up to that moment is saved and its card says why it stopped, instead of the recording failing.
- **Settings in tabs** — Capture, Audio, General, Beta and About; the tab picked is the only part on screen, and General carries a **Shizuku** row that says whether it is installed, running and allowed (shell or root).
- **The button stays in sight, the video stays clean** — the floating button is visible the whole time it records, and it follows the finger smoothly when dragged. Settings → Button while recording can fade it to a fifth instead (still in sight, out of the way), and while an area is recorded it steps just outside the saved part, so the video holds the drawing only. Starting, pausing, resuming and stopping are announced by the notification and a short vibration instead of toasts, so the app's own messages stay out of the recording. Android records the screen exactly as it is, so a whole-screen recording does hold the button; with an area picked, only that area is saved.
- **Built-in player** — a recording opens in DenRecorder's own player (play/pause, seek, double-tap to jump 10 s back or forward, and a rotate button that turns the video even when the phone's rotation is locked), so the video you tap is the video you get, whatever gallery app is installed.
- **Choose where videos go** — `Movies/Screen Recorder` by default, five presets, the phone's own folder browser, or a typed path, including `Download` (videos land in the Downloads collection so MIUI keeps them there).
- **Record a part of the screen** — Settings → Recording area → Choose an area… floats a frame over whatever app you are in. After Save the same ball becomes the record button: tap it to start and tap again to stop from inside the app or game, hold it to change the frame. While recording it steps just outside the frame, so the video holds only the area itself. When the recording ends the ball and its notification close themselves; **Hủy** in the frame closes everything, while **Ẩn khung** keeps the ball ready to record.
- **Recording rotation** — automatic, portrait or landscape: the screen is locked to that rotation for the whole recording (your phone's own rotation setting comes back afterwards), so the video never turns halfway through.
- **Long recordings** — the notification uses the system chronometer (no wake-ups every second), storage is checked every 15 s, recording stops safely before the card fills up.
- **Bilingual** — English and Vietnamese, English on first launch.
- **Shadcn-style UI** — dark, light or automatic (follows the phone), a floating bottom bar, screens that rise in part by part and short, subtle animations.
- **Android 5.1 (SDK 22) to Android 16 (SDK 36).**

### Install

1. Download `DenRecorder-1.10.4.apk` from the [Releases](../../releases) page.
2. Open it on the phone and allow installing apps from that source if asked.
3. Open DenRecorder, allow the permissions it asks for, and optionally let it add the Quick Settings tile.

### Permissions

| Permission | Why |
| --- | --- |
| Screen capture (asked by Android) | to record the display |
| Microphone | only if you pick the microphone as audio source |
| Notifications | the ongoing "recording" notification, Android 13+ |
| Storage (Android 9 and older) | to write the MP4 into the folder you picked |
| Ignore battery optimizations (optional) | keeps long recordings and the tile reliable |
| Modify system settings (optional) | only if you pick a fixed recording rotation, so the screen can be locked while it records; with Shizuku it is not needed |

### Build from source

Requirements: a JDK 17+, Android SDK build-tools (`aapt2`, `d8`, `apksigner`) and `platforms/android-36/android.jar`. Gradle is not needed.

```bash
export ANDROID_HOME=$HOME/android-sdk   # folder with android-36/android.jar and build-tools
./build.sh                              # writes build/app.apk
```

`app.properties` holds `versionCode`, `versionName` and `minSdk`. The first build creates a signing key in `~/.denrecorder-keys` and reuses it afterwards.

### Project layout

```
AndroidManifest.xml
app.properties          versionCode / versionName / minSdk
build.sh                build without Gradle
java/com/bkggds/denrecorder/
  MainActivity.java     screens, settings, recordings list, dialogs
  PlayerActivity.java   built-in video player for the recordings
  ScreenRecorder.java   MediaCodec + MediaMuxer pipeline
  RecorderService.java  foreground service, notification, toasts
  RotationLock.java     locks the screen rotation while recording
  QsTileService.java    Quick Settings tile
  CapturePromptActivity.java  transparent capture-consent trampoline
  Island.java           Xiaomi Super Island (beta) notification extras
  ShizukuBridge.java    Shizuku: one shell command, the island permission
  LocaleHelper.java     EN/VI switching
libs/                   Shizuku API (Apache-2.0, RikkaW) for the beta feature
res/                    strings (EN + VI), icons, launcher icons
```

### License

MIT — do what you like, keep the credit.

Made with ♥ by BKggDs

---

## Tiếng Việt

DenRecorder quay toàn bộ màn hình (kèm âm thanh thiết bị, micrô hoặc không âm thanh) bằng chính bộ mã hóa của Android. Ứng dụng viết bằng Java thuần trên Android framework nên APK rất nhẹ (khoảng 160 KB, phần lớn là thư viện Shizuku cho tính năng đảo beta).

### Tính năng

- **Đúng kích thước màn hình** — bản quay "Gốc" lưu đúng độ phân giải thật (ví dụ 1220 × 2712), không bị hạ xuống mốc chia hết cho 16.
- **Chọn chất lượng** — Gốc, 4K, 2K, Full HD, HD, SD, giữ đúng tỉ lệ màn hình.
- **15–50 Mbps**, **H.264 AVC** hoặc **H.265 HEVC**, tới **120 fps**.
- **Âm thanh** — âm thanh trong máy (Android 10 trở lên), micrô, hoặc không có.
- **Nút trên Trung tâm điều khiển** — chạm để bắt đầu và dừng quay mà không cần mở ứng dụng (có toast khi bắt đầu và khi đã lưu); nhấn giữ nút để mở ứng dụng.
- **Một chạm là quay (tùy chọn, cần Shizuku)** — Android hỏi quyền quay màn hình mỗi lần; khi Shizuku đang chạy, DenRecorder tự bấm nút đó ngay khi hộp thoại vừa hiện, đúng chỗ tìm được lần đầu, nên ghi gần như tức thì. Android 16 không cho ứng dụng thường bỏ hẳn hộp thoại (đặt app-op PROJECT_MEDIA cũng không qua được, đã thử); hộp thoại chỉ loé lên dưới một giây. Có thể tắt lại trong Cài đặt.
- **Super Island của Xiaomi (beta, chỉ máy Xiaomi)** — bản ghi đang chạy hiện trên đảo của HyperOS dưới dạng một biểu tượng nhỏ kèm đồng hồ đếm, chạm vào đảo là **tạm dừng, tiếp tục và dừng** ngay trên thanh đó; có thể tắt hẳn, và HyperOS tự ẩn đảo khi bạn kéo thanh trạng thái xuống (thông báo ở đó có đúng các nút này). Cần Shizuku một lần để cấp quyền hiện đảo.
- **Chế độ hiệu năng khi chơi game** — giới hạn 60 fps và ưu tiên thời gian thực cho bộ mã hóa, để game đang chạy bên cạnh vẫn mượt.
- **Màn Bản ghi** — danh sách Gần đây/Tất cả kèm ảnh thu nhỏ thật, tên video và thư mục lưu; chạm để xem, giữ để xem/chia sẻ/**đổi tên**/xóa, hoặc bấm **Chọn** để chọn nhiều bản ghi và xóa một lần.
- **Vẽ trên màn hình** — nút nổi khi đang quay: bút chì, hình chữ nhật hoặc khoanh tròn vẽ thẳng lên màn hình (có trong video), tạm dừng và dừng quay ngay trên thanh đó. Chọn công cụ một lần là vẽ được nhiều hình liên tiếp, và thanh vẽ có sẵn đủ bảng màu (12 màu): đang quay vẫn đổi màu được, mỗi hình giữ đúng màu lúc vẽ. Nút **✕** để dừng vẽ: công cụ tắt, thanh đóng lại và chạm được vào app bên dưới như bình thường, còn nét đã vẽ vẫn nằm trên màn hình. Bóng có viền đỏ khi đang bật công cụ; chạm ra ngoài thanh thì thanh ẩn mà công cụ vẫn bật, nên chọn công cụ, chọn màu và vẽ chỉ trong một lần mở thanh. Kích thước nút nổi và việc nút luôn hiện hay tự ẩn chỉnh trong Cài đặt.
- **Tạm dừng và tiếp tục** — một lần quay vẫn là một file, thời gian tạm dừng được cắt khỏi video và đồng hồ đếm dừng lại khi tạm dừng.
- **Màn hình luôn sáng khi quay** — màn hình được giữ sáng (ở mức mờ) suốt lúc quay, nên máy không tự ngủ giữa chừng và video không có đoạn đen. Nếu bạn tự tắt màn hình, Android sẽ dừng quay màn hình: video tới lúc đó vẫn được lưu, thẻ báo cho biết vì sao dừng, thay vì báo lỗi.
- **Cài đặt chia tab** — Chất lượng, Âm thanh, Chung, Beta và Thông tin; chỉ tab đang chọn hiện ra, và tab Chung có dòng **Shizuku** cho biết đã cài, đang chạy và đã cấp quyền chưa (shell hay root).
- **Nút luôn trong tầm tay, video vẫn sạch** — nút nổi hiện suốt lúc quay và chạy theo tay khi kéo, không còn khựng. Cài đặt → Nút vẽ khi quay có thể chọn tự mờ còn khoảng một phần năm (vẫn thấy, không vướng), và khi quay một vùng thì nút né ra ngoài phần đã chọn nên video chỉ có nét vẽ. Lúc bắt đầu, tạm dừng, tiếp tục hay dừng quay, app báo bằng thông báo và một nhịp rung ngắn thay vì toast trên màn hình. Android quay đúng màn hình, nên quay toàn màn hình thì nút vẫn nằm trong video; chọn quay một vùng thì chỉ phần đó được lưu.
- **Trình phát sẵn trong app** — chạm vào bản ghi là phát ngay trong DenRecorder (phát/tạm dừng, tua, nhấn đúp để lùi hoặc tiến 10 giây, nút xoay màn hình hoạt động cả khi điện thoại đang khoá xoay), đúng video vừa chọn dù máy dùng ứng dụng thư viện nào.
- **Chọn thư mục lưu** — mặc định `Movies/Screen Recorder`, có 5 gợi ý sẵn, duyệt thư mục bằng trình chọn của điện thoại, hoặc tự nhập.
- **Quay một vùng màn hình** — Cài đặt → Vùng quay → Chọn vùng quay… mở một khung nổi trên app bạn đang dùng. Bấm Lưu xong, chính bóng đó thành nút quay: chạm để bắt đầu, chạm lần nữa để dừng ngay trong app hay game, giữ bóng để đổi khung. Đang quay thì bóng né ra ngoài khung nên video chỉ có đúng phần đã chọn. Quay xong bóng và thông báo tự tắt; bấm **Hủy** trong khung cũng tắt hết, còn **Ẩn khung** thì giữ bóng lại để quay.
- **Hướng khi ghi hình** — tự động, dọc hoặc ngang: màn hình được khóa đúng hướng đó trong suốt lần quay (cài đặt xoay của máy được trả lại sau khi dừng), nên video không bị xoay giữa chừng.
- **Quay dài** — thông báo dùng đồng hồ đếm của hệ thống (không đánh thức mỗi giây), kiểm tra dung lượng mỗi 15 giây và tự dừng trước khi đầy bộ nhớ.
- **Song ngữ** — English và Tiếng Việt, lần đầu mở là tiếng Anh.
- **Giao diện tối kiểu Shadcn** với thanh điều hướng nổi và animation nhẹ.
- **Chạy trên Android 5.1 (SDK 22) đến Android 16 (SDK 36).**

### Cài đặt

1. Tải `DenRecorder-1.10.4.apk` ở mục [Releases](../../releases).
2. Mở file trên điện thoại và cho phép cài đặt từ nguồn đó nếu được hỏi.
3. Mở DenRecorder, cấp quyền khi được hỏi, có thể thêm nút vào Trung tâm điều khiển.

### Quyền ứng dụng

| Quyền | Dùng để |
| --- | --- |
| Quay màn hình (Android hỏi) | ghi lại màn hình |
| Micrô | chỉ khi chọn micrô làm nguồn âm thanh |
| Thông báo | thông báo "đang quay", Android 13 trở lên |
| Bộ nhớ (Android 9 trở xuống) | ghi file MP4 vào thư mục đã chọn |
| Bỏ tối ưu pin (tùy chọn) | giúp quay dài và nút Trung tâm điều khiển ổn định |
| Sửa cài đặt hệ thống (tùy chọn) | chỉ khi chọn khóa hướng ghi hình, để khóa màn hình trong lúc quay; có Shizuku thì không cần |

### Tự build

Cần JDK 17+, Android SDK build-tools (`aapt2`, `d8`, `apksigner`) và `platforms/android-36/android.jar`. Không cần Gradle.

```bash
export ANDROID_HOME=$HOME/android-sdk
./build.sh            # tạo build/app.apk
```

`app.properties` chứa `versionCode`, `versionName`, `minSdk`.

### Giấy phép

MIT — dùng thoải mái, giữ dòng ghi công.

Made with ♥ by BKggDs
