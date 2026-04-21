# Video Converter – Android App

A clean, dark-themed Android application that converts video files between popular formats (`.mkv`, `.avi`, `.mp4`, `.mov`, `.webm`, `.flv`, `.wmv`, `.mpeg`).

Built with **Kotlin**, **Android Jetpack**, and [FFmpeg Kit](https://github.com/jamaismagic/ffmpeg-kit) (community fork) for reliable on-device transcoding.

---

## Features

| Feature | Description |
|---|---|
| **Multi-file selection** | Pick any number of videos from your device at once |
| **Format conversion** | Convert between MP4, MKV, AVI, MOV, WebM, FLV, WMV, MPEG |
| **Preserves file name** | Output keeps the same base name. Saved next to the original when possible, otherwise in `Movies/VideoConverter/` or `Downloads/` |
| **Preserves timestamps** | The original file's last-modified date is kept on the converted file |
| **Delete originals prompt** | After conversion finishes, choose whether to delete the input files |
| **Dark UI** | Dim, eye-friendly colour palette that isn't harsh on your eyes |
| **Progress tracking** | Per-file progress bar and file counter during conversion |

---

## Screenshots

<img width="200" alt="VideoConverter_screenshot" src="https://github.com/user-attachments/assets/49e1be5e-cbb3-43da-929e-e4f496d451b2" />

---

## Requirements

- Android **7.0 (API 24)** or higher
- ~80 MB free storage for the app (FFmpeg libraries are bundled)

---

## Installation (for users)

1. Download the latest APK from the [Releases](../../releases) page.
2. On your Android device go to **Settings → Security → Install unknown apps** and allow your browser / file manager.
3. Open the downloaded `.apk` and tap **Install**.
4. Launch **Video Converter** from your app drawer.

---

## Building from source

> For full Kotlin / Android Studio development setup instructions see  
> **[app/DEVELOPMENT.md](app/DEVELOPMENT.md)**.

### Quick build (command line)

```bash
# Clone the repo
git clone https://github.com/<your-user>/Video-to-mp4.git
cd Video-to-mp4

# Build a debug APK
./gradlew assembleDebug        # Linux / macOS
gradlew.bat assembleDebug      # Windows

# The APK will be at:
#   app/build/outputs/apk/debug/app-debug.apk
```

### Install on a connected device

```bash
./gradlew installDebug
```

---

## How to use

1. **Open the app** – you'll see the main screen with three numbered sections.

2. **Select Videos**  
   Tap the **Select Videos** button. A system file picker opens where you can choose one or many video files.  
   - Selected files appear in a scrollable list showing the file name and size.  
   - Tap the **✕** icon on any file to remove it from the list, or tap **Clear All** to start over.

3. **Choose Formats**  
   - **Input Format** dropdown: pick the format of your source files (informational, helps you stay organised).  
   - **Output Format** dropdown: pick the target format you want to convert **to** (e.g. `mp4`).

4. **Convert**  
   Tap the big **Convert** button.  
   - A progress bar and file counter appear while the conversion runs.  
- Converted files keep the same base name (e.g. `holiday.mkv` → `holiday.mp4`).
   - When possible they are saved next to the original; otherwise they go to `Movies/VideoConverter/` or `Downloads/`.
   - The original timestamp of each file is preserved.

5. **Delete originals?**  
   Once all files are converted a dialog asks:  
   - **Delete** – removes the original input videos.  
   - **Keep** – leaves everything as-is.

---

## Permissions

| Permission | Why |
|---|---|
| `READ_EXTERNAL_STORAGE` / `READ_MEDIA_VIDEO` | Read video files from your device |
| `MANAGE_EXTERNAL_STORAGE` (Android 11+) | Write converted files next to the originals on shared storage |
| `FOREGROUND_SERVICE` | Keep the conversion alive when the app is in the background |
| `POST_NOTIFICATIONS` | Show a notification during conversion |

---

## Tech Stack

- **Language:** Kotlin 2.1
- **Min SDK:** 24 (Android 7.0)
- **UI:** XML layouts + Material Components
- **Architecture:** MVVM (ViewModel + LiveData)
- **Video engine:** [FFmpeg Kit 6.1 LTS](https://github.com/jamaismagic/ffmpeg-kit) (community fork, 16 KB page-size build)
- **Build system:** Gradle 8.13 + AGP 8.13.2

---

## License

This project is provided as-is for personal use. FFmpeg Kit is licensed under LGPL v3 / GPL v3 – see [FFmpeg Kit License](https://github.com/jamaismagic/ffmpeg-kit/blob/main/LICENSE) for details.
