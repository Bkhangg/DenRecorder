# DenRecorder

A small, fast screen recorder for Android — native MediaCodec recording, no ads, no tracking.

🇬🇧 English · [🇻🇳 Tiếng Việt](#tiếng-việt)

---

## English

DenRecorder records the whole screen (and the device audio, the microphone or nothing) straight from Android's own encoders. It is written in plain Java against the Android framework, so the APK stays under 100 KB.

### Features

- **Exact screen size** — a native recording is saved at the real screen resolution (for example 1220 × 2712), not scaled down to the encoder's 16-pixel grid.
- **Quality presets** — Native, 4K, 2K, Full HD, HD and SD, keeping the screen's aspect ratio. Videos stay inside what phone decoders handle (3840 px on the long side, H.264 level 5.1 frame size), so a 4K recording plays in every gallery and player.
- **15–50 Mbps** variable bitrate, **H.264 AVC** or **H.265 HEVC**, up to **120 fps**.
- **Audio** — internal device audio (Android 10+), microphone, or silent.
- **Quick Settings tile** — start and stop without opening the app, with a toast when the recording starts or is saved.
- **Game performance mode** — caps the recorder at 60 fps and runs the encoder in realtime priority, so a game next to it keeps its frame rate.
- **Recordings screen** — recent/all lists with real thumbnails, the video's own name and the folder it is saved in; tap to play, long-press for play/share/**rename**/delete, or tap **Select** to pick several videos and delete them at once.
- **Draw on screen** — a floating button while recording: pencil, rectangle or circle drawn straight onto the screen (they end up in the video), pause and stop from the same bar. A tool stays picked, so several shapes can be drawn one after another, and the button's size can be changed in settings.
- **Pause and resume** — one recording stays one file; the paused time is cut out of the video and the timer stops while paused.
- **Built-in player** — a recording opens in DenRecorder's own player (play/pause, seek, double-tap to jump 10 s back or forward, and a rotate button that turns the video even when the phone's rotation is locked), so the video you tap is the video you get, whatever gallery app is installed.
- **Choose where videos go** — `Movies/Screen Recorder` by default, five presets, the phone's own folder browser, or a typed path, including `Download` (videos land in the Downloads collection so MIUI keeps them there).
- **Recording rotation** — automatic, portrait or landscape: the screen is locked to that rotation for the whole recording (your phone's own rotation setting comes back afterwards), so the video never turns halfway through.
- **Long recordings** — the notification uses the system chronometer (no wake-ups every second), storage is checked every 15 s, recording stops safely before the card fills up.
- **Bilingual** — English and Vietnamese, English on first launch.
- **Shadcn-style UI** — dark, light or automatic (follows the phone), a floating bottom bar and short, subtle animations.
- **Android 5.1 (SDK 22) to Android 16 (SDK 36).**

### Install

1. Download `DenRecorder-1.4.1.apk` from the [Releases](../../releases) page.
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
| Modify system settings (optional) | only if you pick a fixed recording rotation, so the screen can be locked while it records |

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
  LocaleHelper.java     EN/VI switching
res/                    strings (EN + VI), icons, launcher icons
```

### What's new

**1.4.1**

- The icon on the start/stop button sits right next to the label now.
- A video no longer appears twice in the list when it is saved in Download.
- Long-press a recording for play, share, rename or delete; **Select** still deletes several at once.

**1.4.0**

- Theme: dark, light or automatic (follows the phone's dark mode).
- Player: a rotate button turns the video to landscape even while the phone's auto-rotate is off.
- The end of a recording shows a "recording saved" notification that opens the new video; the stop toast stays.
- New Shadcn-style app icon (outlined screen with a record dot).
- **Browse folders…** in Save to: pick the folder with the phone's own folder picker.
- The recordings list shows each video's name, and long-press → Rename renames it.
- Start and stop buttons now carry a record and stop icon.

**1.3.1**

- Pencil tool: draw freehand on the screen while recording.
- A picked tool stays picked: draw as many rectangles or circles as you like without tapping the button again.
- Floating button size: small, medium, large or extra large, and the tool bar scales with it.
- The recording timer stops while paused, and the paused time is cut from the video.
- The recordings list shows the folder of every video.
- Select one or more recordings and delete them in one go.
- Double-tap the player's video to jump 10 seconds back (left side) or forward (right side), or to play/pause (middle).
- "Save to Download" really saves to Download (MediaStore's Downloads collection).

**1.2.0**

- Floating drawing button while recording: rectangle, circle, clear, pause/resume and stop.
- Pause and resume a recording without splitting the file.
- Recordings screen shows every DenRecorder video, even after changing the save folder.
- Long setting values (Vietnamese game performance label, folder paths) wrap instead of being cut off.

**1.1.1**

- Videos open in DenRecorder's own player, so tapping a recording always plays that recording.
- 4K and 2K recordings are kept inside what phone decoders handle (long side ≤ 3840 px, H.264 level 5.1 frame size), so they play in the gallery and in every player.
- Screen rotation can be locked while recording, with the permission asked once.

**1.0.1** — rotation lock while recording, permission flow.

**1.0.0** — first release: native-size recording, presets, device audio, Quick Settings tile, game performance mode, recordings screen, bilingual UI.

### License

MIT — do what you like, keep the credit.

Made with ♥ by BKggDs

---

## Tiếng Việt

DenRecorder quay toàn bộ màn hình (kèm âm thanh thiết bị, micrô hoặc không âm thanh) bằng chính bộ mã hóa của Android. Ứng dụng viết bằng Java thuần trên Android framework nên APK chưa tới 100 KB.

### Tính năng

- **Đúng kích thước màn hình** — bản quay "Gốc" lưu đúng độ phân giải thật (ví dụ 1220 × 2712), không bị hạ xuống mốc chia hết cho 16.
- **Chọn chất lượng** — Gốc, 4K, 2K, Full HD, HD, SD, giữ đúng tỉ lệ màn hình.
- **15–50 Mbps**, **H.264 AVC** hoặc **H.265 HEVC**, tới **120 fps**.
- **Âm thanh** — âm thanh trong máy (Android 10 trở lên), micrô, hoặc không có.
- **Nút trên Trung tâm điều khiển** — bắt đầu và dừng quay mà không cần mở ứng dụng, có toast thông báo khi bắt đầu và khi đã lưu.
- **Chế độ hiệu năng khi chơi game** — giới hạn 60 fps và ưu tiên thời gian thực cho bộ mã hóa, để game đang chạy bên cạnh vẫn mượt.
- **Màn Bản ghi** — danh sách Gần đây/Tất cả kèm ảnh thu nhỏ thật, tên video và thư mục lưu; chạm để xem, giữ để xem/chia sẻ/**đổi tên**/xóa, hoặc bấm **Chọn** để chọn nhiều bản ghi và xóa một lần.
- **Vẽ trên màn hình** — nút nổi khi đang quay: bút chì, hình chữ nhật hoặc khoanh tròn vẽ thẳng lên màn hình (có trong video), tạm dừng và dừng quay ngay trên thanh đó. Chọn công cụ một lần là vẽ được nhiều hình liên tiếp; kích thước nút nổi chỉnh được trong Cài đặt.
- **Tạm dừng và tiếp tục** — một lần quay vẫn là một file, thời gian tạm dừng được cắt khỏi video và đồng hồ đếm dừng lại khi tạm dừng.
- **Trình phát sẵn trong app** — chạm vào bản ghi là phát ngay trong DenRecorder (phát/tạm dừng, tua, nhấn đúp để lùi hoặc tiến 10 giây, nút xoay màn hình hoạt động cả khi điện thoại đang khoá xoay), đúng video vừa chọn dù máy dùng ứng dụng thư viện nào.
- **Chọn thư mục lưu** — mặc định `Movies/Screen Recorder`, có 5 gợi ý sẵn, duyệt thư mục bằng trình chọn của điện thoại, hoặc tự nhập.
- **Hướng khi ghi hình** — tự động, dọc hoặc ngang: màn hình được khóa đúng hướng đó trong suốt lần quay (cài đặt xoay của máy được trả lại sau khi dừng), nên video không bị xoay giữa chừng.
- **Quay dài** — thông báo dùng đồng hồ đếm của hệ thống (không đánh thức mỗi giây), kiểm tra dung lượng mỗi 15 giây và tự dừng trước khi đầy bộ nhớ.
- **Song ngữ** — English và Tiếng Việt, lần đầu mở là tiếng Anh.
- **Giao diện tối kiểu Shadcn** với thanh điều hướng nổi và animation nhẹ.
- **Chạy trên Android 5.1 (SDK 22) đến Android 16 (SDK 36).**

### Cài đặt

1. Tải `DenRecorder-1.4.1.apk` ở mục [Releases](../../releases).
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
| Sửa cài đặt hệ thống (tùy chọn) | chỉ khi chọn khóa hướng ghi hình, để khóa màn hình trong lúc quay |

### Tự build

Cần JDK 17+, Android SDK build-tools (`aapt2`, `d8`, `apksigner`) và `platforms/android-36/android.jar`. Không cần Gradle.

```bash
export ANDROID_HOME=$HOME/android-sdk
./build.sh            # tạo build/app.apk
```

`app.properties` chứa `versionCode`, `versionName`, `minSdk`.

### Có gì mới

**1.4.1**

- Icon trên nút Bắt đầu/Dừng quay nằm sát chữ, không còn bị đẩy ra xa.
- Video lưu trong Download không còn hiện hai lần trong danh sách.
- Giữ vào một bản ghi để xem/chia sẻ/đổi tên/xóa; nút **Chọn** vẫn dùng để xóa nhiều video.

**1.4.0**

- Giao diện: Tối, Sáng hoặc Tự động (theo chế độ tối của điện thoại).
- Trình phát: nút xoay màn hình đưa video sang ngang dù điện thoại đang tắt tự xoay.
- Khi dừng quay có thông báo "Đã lưu bản ghi" trên thanh thông báo, chạm vào mở ngay video vừa quay; toast vẫn giữ.
- Icon ứng dụng mới kiểu Shadcn (màn hình nét mảnh kèm chấm đỏ).
- Mục **Duyệt thư mục…** trong "Lưu vào": chọn thư mục bằng trình chọn thư mục của điện thoại.
- Danh sách bản ghi hiện tên từng video, giữ vào rồi chọn **Đổi tên** để đổi.
- Nút bắt đầu và dừng quay có thêm icon quay và icon dừng.

**1.3.1**

- Bút chì: vẽ tay lên màn hình khi đang quay.
- Chọn công cụ một lần là vẽ được nhiều hình chữ nhật hoặc nhiều vòng tròn liên tiếp, không phải bấm lại.
- Chỉnh kích thước nút nổi: Nhỏ, Vừa, Lớn, Rất lớn — thanh công cụ nhỏ lại theo.
- Đồng hồ đếm dừng khi tạm dừng, và thời gian tạm dừng được cắt khỏi video.
- Danh sách bản ghi hiện thư mục lưu của từng video.
- Chọn một hoặc nhiều bản ghi rồi xóa cùng lúc.
- Nhấn đúp vào video trong trình phát để lùi 10 giây (bên trái), tiến 10 giây (bên phải) hoặc phát/tạm dừng (ở giữa).
- "Lưu vào Download" giờ lưu đúng vào Download (dùng bộ sưu tập Downloads của MediaStore).

**1.2.0**

- Nút nổi vẽ trên màn hình khi đang quay: hình chữ nhật, khoanh tròn, xóa nét, tạm dừng/tiếp tục và dừng quay.
- Tạm dừng rồi tiếp tục quay mà vẫn giữ một file duy nhất.
- Màn Bản ghi hiện mọi video DenRecorder, kể cả sau khi đổi thư mục lưu.
- Giá trị dài trong Cài đặt (hiệu năng game, đường dẫn thư mục) xuống dòng thay vì bị cắt.

**1.1.1**

- Video mở bằng trình phát của chính DenRecorder, chạm bản ghi nào phát đúng bản ghi đó.
- Bản ghi 4K và 2K được giữ trong giới hạn bộ giải mã điện thoại (cạnh dài ≤ 3840 px, khung hình H.264 level 5.1) nên phát được trong thư viện và mọi trình phát.
- Có thể khóa hướng màn hình trong lúc ghi, chỉ xin quyền một lần.

**1.0.1** — khóa hướng khi ghi, xin quyền.

**1.0.0** — bản đầu tiên: quay đúng kích thước gốc, các mức phân giải, âm thanh trong máy, nút Trung tâm điều khiển, chế độ hiệu năng game, màn Bản ghi, song ngữ.

### Giấy phép

MIT — dùng thoải mái, giữ dòng ghi công.

Made with ♥ by BKggDs
