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
- **Recordings screen** — recent/all lists with real thumbnails, tap to play, long-press to play, share or delete.
- **Built-in player** — a recording opens in DenRecorder's own player (play/pause, seek), so the video you tap is the video you get, whatever gallery app is installed.
- **Choose where videos go** — `Movies/Screen Recorder` by default, five presets or any custom folder.
- **Recording rotation** — automatic, portrait or landscape: the screen is locked to that rotation for the whole recording (your phone's own rotation setting comes back afterwards), so the video never turns halfway through.
- **Long recordings** — the notification uses the system chronometer (no wake-ups every second), storage is checked every 15 s, recording stops safely before the card fills up.
- **Bilingual** — English and Vietnamese, English on first launch.
- **Shadcn-style dark UI** with a floating bottom bar and short, subtle animations.
- **Android 5.1 (SDK 22) to Android 16 (SDK 36).**

### Install

1. Download `DenRecorder-1.1.1.apk` from the [Releases](../../releases) page.
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
- **Màn Bản ghi** — danh sách Gần đây/Tất cả kèm ảnh thu nhỏ thật của video, chạm để xem, giữ để xem/chia sẻ/xóa.
- **Trình phát sẵn trong app** — chạm vào bản ghi là phát ngay trong DenRecorder (phát/tạm dừng, tua), đúng video vừa chọn dù máy dùng ứng dụng thư viện nào.
- **Chọn thư mục lưu** — mặc định `Movies/Screen Recorder`, có 5 gợi ý sẵn hoặc tự nhập.
- **Hướng khi ghi hình** — tự động, dọc hoặc ngang: màn hình được khóa đúng hướng đó trong suốt lần quay (cài đặt xoay của máy được trả lại sau khi dừng), nên video không bị xoay giữa chừng.
- **Quay dài** — thông báo dùng đồng hồ đếm của hệ thống (không đánh thức mỗi giây), kiểm tra dung lượng mỗi 15 giây và tự dừng trước khi đầy bộ nhớ.
- **Song ngữ** — English và Tiếng Việt, lần đầu mở là tiếng Anh.
- **Giao diện tối kiểu Shadcn** với thanh điều hướng nổi và animation nhẹ.
- **Chạy trên Android 5.1 (SDK 22) đến Android 16 (SDK 36).**

### Cài đặt

1. Tải `DenRecorder-1.1.1.apk` ở mục [Releases](../../releases).
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

**1.1.1**

- Video mở bằng trình phát của chính DenRecorder, chạm bản ghi nào phát đúng bản ghi đó.
- Bản ghi 4K và 2K được giữ trong giới hạn bộ giải mã điện thoại (cạnh dài ≤ 3840 px, khung hình H.264 level 5.1) nên phát được trong thư viện và mọi trình phát.
- Có thể khóa hướng màn hình trong lúc ghi, chỉ xin quyền một lần.

**1.0.1** — khóa hướng khi ghi, xin quyền.

**1.0.0** — bản đầu tiên: quay đúng kích thước gốc, các mức phân giải, âm thanh trong máy, nút Trung tâm điều khiển, chế độ hiệu năng game, màn Bản ghi, song ngữ.

### Giấy phép

MIT — dùng thoải mái, giữ dòng ghi công.

Made with ♥ by BKggDs
