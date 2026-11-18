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
- **Quick Settings tile** — start and stop without opening the app, with a toast when the recording starts or is saved.
- **One tap to record (optional, Shizuku)** — Android asks for the screen-capture permission every time; with Shizuku running, DenRecorder presses that button itself as soon as the dialog is up, at the place it found the first time, so recording starts at once. Android 16 gives an app no way to skip the dialog completely, even with the app-op set; the dialog is gone in well under a second. The option can be turned off again in Settings.
- **Xiaomi Super Island (beta, Xiaomi only)** — the running recording shows up in HyperOS's island as a small icon with its timer, and a tap on it gives **pause, resume and stop** right on the bar (the stop button is a filled square, not a cross); it can be hidden entirely, and HyperOS hides it by itself whenever the status bar is pulled down (the notification there carries the same buttons). Needs Shizuku once to grant the island permission.
- **Game performance mode** — caps the recorder at 60 fps and runs the encoder in realtime priority, so a game next to it keeps its frame rate.
- **Recordings screen** — recent/all lists with real thumbnails, the video's own name and the folder it is saved in; tap to play, long-press for play/share/**rename**/delete, or tap **Select** to pick several videos and delete them at once.
- **Draw on screen** — a floating button while recording: pencil, rectangle or circle drawn straight onto the screen (they end up in the video), pause and stop from the same bar. A tool stays picked, so several shapes can be drawn one after another, and the bar carries the whole palette (12 colours): the colour can be changed in the middle of a recording, and every shape keeps the colour it was drawn with. The down-arrow hides the bar and keeps the tool on, and touching the screen beside the bar hides it and starts the shape there and then, so a tool, a colour and a stroke take one visit to the bar. The button's size is picked in settings.
- **Pause and resume** — one recording stays one file; the paused time is cut out of the video and the timer stops while paused.
- **Built-in player** — a recording opens in DenRecorder's own player (play/pause, seek, double-tap to jump 10 s back or forward, and a rotate button that turns the video even when the phone's rotation is locked), so the video you tap is the video you get, whatever gallery app is installed.
- **Choose where videos go** — `Movies/Screen Recorder` by default, five presets, the phone's own folder browser, or a typed path, including `Download` (videos land in the Downloads collection so MIUI keeps them there).
- **Recording rotation** — automatic, portrait or landscape: the screen is locked to that rotation for the whole recording (your phone's own rotation setting comes back afterwards), so the video never turns halfway through.
- **Long recordings** — the notification uses the system chronometer (no wake-ups every second), storage is checked every 15 s, recording stops safely before the card fills up.
- **Bilingual** — English and Vietnamese, English on first launch.
- **Shadcn-style UI** — dark, light or automatic (follows the phone), a floating bottom bar, screens that rise in part by part and short, subtle animations.
- **Android 5.1 (SDK 22) to Android 16 (SDK 36).**

### Install

1. Download `DenRecorder-1.9.4.apk` from the [Releases](../../releases) page.
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

### What's new

**1.9.4**

- Smoother while it records: as soon as another window is in front — a game, the notification shade, the capture dialog — the recording screen stops its once-a-second refresh and its pulsing red dot, both of which were being drawn behind the game, and the island's timer is built on a thread of its own instead of the one that draws the app. Pulling the quick settings down during a recording no longer stutters.
- Smoother in the app: the recordings list is read in the background and the rows are rebuilt only when the videos really changed, so switching tabs is instant instead of waiting on the gallery's database every time.
- The recording screen also returns to "recording" at once after the capture dialog, instead of up to a couple of seconds later.
- The app opens faster: Settings and Recordings are built the first time they are opened instead of at launch, together with the screen that opens first.

**1.9.3**

- Fix: with rotation set to **Auto**, recording a game that turns the screen sideways left the phone turned that way, so the gallery and other apps opened sideways even with the phone's rotation lock on. DenRecorder now keeps the rotation setting the phone had before the game and puts it back when the recording ends — with the "modify system settings" grant, or through Shizuku without it.
- A fixed recording rotation (Portrait/Landscape) also works through Shizuku, so the extra grant is only a fallback now.
- Recording a game in **Portrait** gives a portrait video with the game fitted inside, with black bars above and below.

**1.9.2**

- Fix: after a landscape recording the screen could stay turned that way, so the gallery opened sideways even with rotation lock on. The phone's own rotation setting is now restored even when closing the recording fails, when the app is closed while recording, and the next time the app is opened after an update or a force-stop. The rotation is only written back when it was really changed, and only once the write went through.

**1.9.1**

- The drawing bar keeps the tool and the colour you pick open in front of you, and the down-arrow only hides it: the tool stays on, so the next touch draws instead of tapping buttons.
- Touching the screen beside the bar hides it and draws right away, in the same movement: pick a tool, pick a colour, swipe on the screen, done.
- The drawing colour is picked only where it is used — in the bar, while recording — so its row is gone from Settings.
- **Start without asking** is much quicker: the first time it looks for Android's "share screen" button and remembers it; afterwards it taps that spot the moment the dialog is the resumed activity and holds the focus, so the dialog is gone in about half a second instead of several seconds.
- Android 16 has no way to skip the capture dialog for a normal app (the PROJECT_MEDIA app-op does not bypass it; tested), so the dialog still shows for a moment as the consent itself.

**1.9.0**

- The drawing bar now carries the colours: twelve dots under the tools, so the drawing colour is changed in the middle of a recording without leaving it. The picked dot is ringed, and the next shape uses the new colour straight away.
- The island's picture is much smaller: the same video glyph is drawn into a wide transparent box, so the pill shows a small icon instead of one that nearly fills it.
- Stopping looks like stopping: the notification's and the island's stop button is a filled square instead of a cross.
- More motion: the home, settings and recordings pages rise in part by part, values cross-fade when they change, the picked tab's icon pops, start and stop pulse, the status line fades in, recording rows come in one after another, the floating button pops in and the drawing bar scales in. Opening the tools no longer ends the tool you had picked.
- Fix: after the bar grew, a tap could land off its buttons; the floating window is now measured and given an exact size.

**1.8.0**

- New **Drawing colour** setting (Settings → General, under the button size): twelve colours; from then on rectangles, circles and pencil strokes are drawn in it, and a shape already on the screen keeps the colour it had.
- **Game performance** now also keeps the video at the screen's own size: a 2K or 4K preset is recorded at 1220 × 2712 instead of being upscaled, so the GPU and encoder do less while a heavy game runs. The row's value says so ("Smooth · 60 fps · screen size").
- The opened island no longer repeats the app name and the timer: it shows the state and one line, while the small pill carries the counter.
- Long recordings: the timer in the notification is counted by Android itself, and the island only refreshes its text; nothing builds up in memory over an hour.

**1.7.0**

- New setting **Start without asking** (Settings → General): with Shizuku running, DenRecorder presses Android's own "Share screen" button, so recording starts from a single tap on the start button or the Quick Settings tile.
- The capture dialog still appears for a moment and the permission stays Android's own; turn the option off to confirm every recording by hand.
- Super Island no longer repeats the app name and the timer: the opened island shows the state and one line, "DenRecorder · 00:22".

**1.6.0**

- Super Island is smaller: only the icon and the timer, no app name.
- Tap the island: it opens right there with **pause / resume** and **stop**.
- Settings → Super Island has a new **Show island** row: **Hidden** (default) or **Always visible**.
- HyperOS removes the island by itself while the status bar is pulled down, so the notification in the shade carries the same pause and stop buttons.

**1.5.0**

- Beta, Xiaomi only: **Super Island** in Settings shows the running recording in HyperOS's island (timer, app name, tap to open DenRecorder).
- The switch is only offered on Xiaomi phones and is turned on with Shizuku, which grants the island permission a normal install can not ask for.
- Shizuku is asked for nothing else; the app still works without it.

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

DenRecorder quay toàn bộ màn hình (kèm âm thanh thiết bị, micrô hoặc không âm thanh) bằng chính bộ mã hóa của Android. Ứng dụng viết bằng Java thuần trên Android framework nên APK rất nhẹ (khoảng 160 KB, phần lớn là thư viện Shizuku cho tính năng đảo beta).

### Tính năng

- **Đúng kích thước màn hình** — bản quay "Gốc" lưu đúng độ phân giải thật (ví dụ 1220 × 2712), không bị hạ xuống mốc chia hết cho 16.
- **Chọn chất lượng** — Gốc, 4K, 2K, Full HD, HD, SD, giữ đúng tỉ lệ màn hình.
- **15–50 Mbps**, **H.264 AVC** hoặc **H.265 HEVC**, tới **120 fps**.
- **Âm thanh** — âm thanh trong máy (Android 10 trở lên), micrô, hoặc không có.
- **Nút trên Trung tâm điều khiển** — bắt đầu và dừng quay mà không cần mở ứng dụng, có toast thông báo khi bắt đầu và khi đã lưu.
- **Một chạm là quay (tùy chọn, cần Shizuku)** — Android hỏi quyền quay màn hình mỗi lần; khi Shizuku đang chạy, DenRecorder tự bấm nút đó ngay khi hộp thoại vừa hiện, đúng chỗ tìm được lần đầu, nên ghi gần như tức thì. Android 16 không cho ứng dụng thường bỏ hẳn hộp thoại (đặt app-op PROJECT_MEDIA cũng không qua được, đã thử); hộp thoại chỉ loé lên dưới một giây. Có thể tắt lại trong Cài đặt.
- **Super Island của Xiaomi (beta, chỉ máy Xiaomi)** — bản ghi đang chạy hiện trên đảo của HyperOS dưới dạng một biểu tượng nhỏ kèm đồng hồ đếm, chạm vào đảo là **tạm dừng, tiếp tục và dừng** ngay trên thanh đó; có thể tắt hẳn, và HyperOS tự ẩn đảo khi bạn kéo thanh trạng thái xuống (thông báo ở đó có đúng các nút này). Cần Shizuku một lần để cấp quyền hiện đảo.
- **Chế độ hiệu năng khi chơi game** — giới hạn 60 fps và ưu tiên thời gian thực cho bộ mã hóa, để game đang chạy bên cạnh vẫn mượt.
- **Màn Bản ghi** — danh sách Gần đây/Tất cả kèm ảnh thu nhỏ thật, tên video và thư mục lưu; chạm để xem, giữ để xem/chia sẻ/**đổi tên**/xóa, hoặc bấm **Chọn** để chọn nhiều bản ghi và xóa một lần.
- **Vẽ trên màn hình** — nút nổi khi đang quay: bút chì, hình chữ nhật hoặc khoanh tròn vẽ thẳng lên màn hình (có trong video), tạm dừng và dừng quay ngay trên thanh đó. Chọn công cụ một lần là vẽ được nhiều hình liên tiếp, và thanh vẽ có sẵn đủ bảng màu (12 màu): đang quay vẫn đổi màu được, mỗi hình giữ đúng màu lúc vẽ. Mũi tên xuống chỉ ẩn thanh, công cụ vẫn bật; chạm ra ngoài thanh là thanh ẩn và vẽ ngay tại chỗ đó, nên chọn công cụ, chọn màu và vẽ chỉ trong một lần mở thanh. Kích thước nút nổi chỉnh trong Cài đặt.
- **Tạm dừng và tiếp tục** — một lần quay vẫn là một file, thời gian tạm dừng được cắt khỏi video và đồng hồ đếm dừng lại khi tạm dừng.
- **Trình phát sẵn trong app** — chạm vào bản ghi là phát ngay trong DenRecorder (phát/tạm dừng, tua, nhấn đúp để lùi hoặc tiến 10 giây, nút xoay màn hình hoạt động cả khi điện thoại đang khoá xoay), đúng video vừa chọn dù máy dùng ứng dụng thư viện nào.
- **Chọn thư mục lưu** — mặc định `Movies/Screen Recorder`, có 5 gợi ý sẵn, duyệt thư mục bằng trình chọn của điện thoại, hoặc tự nhập.
- **Hướng khi ghi hình** — tự động, dọc hoặc ngang: màn hình được khóa đúng hướng đó trong suốt lần quay (cài đặt xoay của máy được trả lại sau khi dừng), nên video không bị xoay giữa chừng.
- **Quay dài** — thông báo dùng đồng hồ đếm của hệ thống (không đánh thức mỗi giây), kiểm tra dung lượng mỗi 15 giây và tự dừng trước khi đầy bộ nhớ.
- **Song ngữ** — English và Tiếng Việt, lần đầu mở là tiếng Anh.
- **Giao diện tối kiểu Shadcn** với thanh điều hướng nổi và animation nhẹ.
- **Chạy trên Android 5.1 (SDK 22) đến Android 16 (SDK 36).**

### Cài đặt

1. Tải `DenRecorder-1.9.4.apk` ở mục [Releases](../../releases).
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

### Có gì mới

**1.9.4**

- Mượt hơn trong lúc quay: ngay khi có cửa sổ khác ở phía trước — game, thanh thông báo, hộp thoại quay màn hình — màn hình quay ngừng cập nhật mỗi giây và ngừng vẽ chấm đỏ nhấp nháy (hai thứ vẫn chạy phía sau game), còn đồng hồ trên Super Island được dựng ở luồng riêng thay vì luồng vẽ giao diện. Kéo thanh cài đặt nhanh trong lúc quay sẽ không còn giật.
- Mượt hơn trong app: danh sách bản ghi được đọc ở nền và chỉ dựng lại khi danh sách video thật sự thay đổi, nên chuyển tab là hiện ngay, không phải chờ truy vấn thư viện mỗi lần.
- Sau khi cấp quyền quay, màn hình cũng chuyển sang "Đang quay" ngay thay vì chờ vài giây.
- App mở nhanh hơn: Cài đặt và Bản ghi chỉ được dựng khi mở lần đầu, không dựng sẵn ngay lúc khởi động.

**1.9.3**

- Sửa lỗi: khi để hướng màn hình **Tự động**, quay game làm màn hình xoay ngang thì sau khi dừng, máy bị kẹt ngang nên thư viện và ứng dụng khác cũng mở ngang dù đã khóa xoay. DenRecorder giờ ghi nhớ hướng xoay của máy trước khi vào game và trả lại khi dừng quay — dùng quyền "Sửa cài đặt hệ thống" hoặc qua Shizuku mà không cần quyền đó.
- Khóa hướng ghi hình (Dọc/Ngang) cũng chạy được qua Shizuku, quyền hệ thống chỉ còn là phương án dự phòng.
- Quay game khi chọn **Dọc** sẽ ra video dọc, hình game nằm gọn bên trong với viền đen trên dưới.

**1.9.2**

- Sửa lỗi: quay xong video ngang, màn hình có thể bị giữ ngang luôn nên vào thư viện là bị xoay ngang dù điện thoại đang khoá xoay. Giờ cài đặt xoay của máy được trả lại cả khi đóng bản ghi bị lỗi, khi app bị tắt giữa lúc quay, và tự sửa lại ngay lần mở app sau đó (sau khi cập nhật hay bị buộc dừng). Chỉ ghi lại khi thật sự có thay đổi, và chỉ coi là xong khi đã ghi được.

**1.9.1**

- Thanh vẽ giữ nguyên công cụ và màu vừa chọn, mũi tên xuống chỉ ẩn thanh: công cụ vẫn bật, nên chạm tiếp là vẽ chứ không bấm nhầm nút.
- Chạm ra ngoài thanh là thanh ẩn và vẽ ngay trong cùng động tác đó: chọn công cụ, chọn màu, vuốt trên màn hình là xong.
- Màu vẽ chỉ chọn ở nơi dùng nó — trên thanh vẽ khi đang quay — nên dòng màu trong Cài đặt đã bỏ.
- **Quay không cần hỏi** nhanh hơn hẳn: lần đầu tìm nút "Chia sẻ màn hình" của Android rồi nhớ vị trí; các lần sau bấm đúng chỗ đó ngay khi hộp thoại thành activity đang chạy và nhận focus, nên hộp thoại biến mất trong khoảng nửa giây thay vì vài giây.
- Android 16 không có cách bỏ hẳn hộp thoại với ứng dụng thường (đặt app-op PROJECT_MEDIA cũng không qua được, đã thử), nên hộp thoại vẫn loé lên như chính sự đồng ý.

**1.9.0**

- Thanh vẽ có luôn bảng màu: mười hai chấm màu ngay dưới các công cụ, nên đang quay vẫn đổi màu vẽ được mà không phải thoát ra. Chấm đang chọn có viền, hình vẽ tiếp theo dùng màu mới ngay.
- Icon trong đảo nhỏ hẳn: cùng một icon camera được vẽ vào một khung trong suốt rộng, nên viên đảo hiện icon nhỏ xíu thay vì gần đầy khung.
- Dừng đúng nghĩa dừng: nút dừng trong thông báo và trong đảo là hình vuông đặc, không còn dấu X.
- Thêm chuyển động: trang Quay, Cài đặt và Bản ghi hiện lần lượt từng phần, giá trị đổi mờ dần, icon tab đang chọn nảy nhẹ, nút bắt đầu/dừng nhấp nháy, dòng trạng thái mờ dần vào, các dòng bản ghi hiện nối tiếp, nút nổi bật ra và thanh vẽ phóng nhẹ. Mở công cụ không còn làm mất công cụ đang chọn.
- Sửa lỗi: sau khi thanh vẽ rộng ra, bấm có thể lệch khỏi nút; cửa sổ nổi giờ được đo và đặt đúng kích thước.

**1.8.0**

- Mục mới **Màu vẽ** (Cài đặt → Chung, ngay dưới kích thước nút nổi): 12 màu; từ đó hình chữ nhật, vòng tròn và nét bút được vẽ bằng màu này, hình đã vẽ giữ nguyên màu cũ.
- **Hiệu năng khi chơi game** giờ còn giữ video đúng cỡ màn hình: chọn 2K hay 4K thì vẫn ghi ở 1220 × 2712 thay vì phóng to, nên GPU và bộ mã hóa làm việc ít hơn khi chơi game nặng. Dòng này ghi rõ "Mượt · 60 fps · cỡ màn hình".
- Mở đảo ra không còn lặp tên ứng dụng và đồng hồ: chỉ còn trạng thái và một dòng, còn đồng hồ đếm nằm ở viên đảo nhỏ.
- Ghi dài: đồng hồ trong thông báo do Android tự đếm, đảo chỉ cập nhật chữ; quay cả tiếng không bị dồn bộ nhớ.

**1.7.0**

- Mục mới **Quay không cần hỏi** (Cài đặt → Chung): khi Shizuku đang chạy, DenRecorder tự bấm nút "Chia sẻ màn hình" của Android, nên bấm nút quay hoặc nút Trung tâm điều khiển là ghi ngay.
- Hộp thoại của hệ thống vẫn hiện trong chốc lát và quyền vẫn là của Android; tắt mục này nếu muốn tự xác nhận mỗi lần quay.
- Super Island không lặp lại tên ứng dụng và đồng hồ đếm nữa: mở đảo ra chỉ còn trạng thái và một dòng "DenRecorder · 00:22".

**1.6.0**

- Super Island gọn hơn: chỉ còn biểu tượng và đồng hồ đếm, bỏ tên ứng dụng.
- Chạm vào đảo: mở ngay tại đó với **tạm dừng / tiếp tục** và **dừng**.
- Cài đặt → Super Island có thêm mục **Hiện đảo**: **Ẩn** (mặc định) hoặc **Luôn hiện**.
- HyperOS tự bỏ đảo khi kéo thanh trạng thái xuống, nên thông báo trong khay có đúng hai nút tạm dừng và dừng.

**1.5.0**

- Beta, chỉ trên máy Xiaomi: **Super Island** trong Cài đặt hiện bản ghi đang chạy trên đảo của HyperOS (đồng hồ đếm, tên app, chạm để mở DenRecorder).
- Mục này chỉ xuất hiện trên điện thoại Xiaomi và được bật bằng Shizuku — công cụ cấp quyền hiện đảo mà ứng dụng thường không xin được.
- Shizuku chỉ dùng cho đúng việc đó; không có nó ứng dụng vẫn chạy như cũ.

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
