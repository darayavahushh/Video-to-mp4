# Development Setup – Kotlin / Android

This guide walks you through setting up the development environment to build and modify the **Video Converter** Android app.

---

## Prerequisites

### 1. Java Development Kit (JDK)

Android Gradle Plugin 8.13.2 requires **JDK 17** or newer.

| Platform | Install command |
|---|---|
| **Windows** | Download from [Adoptium](https://adoptium.net/) or run `winget install EclipseAdoptium.Temurin.17.JDK` |
| **macOS** | `brew install --cask temurin17` |
| **Linux (Debian/Ubuntu)** | `sudo apt install openjdk-17-jdk` |

Verify:

```bash
java -version
# → openjdk version "17.x.x" …
```

Set `JAVA_HOME` if it is not automatically detected:

```bash
# Linux / macOS – add to ~/.bashrc or ~/.zshrc
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# Windows – System Properties → Environment Variables
JAVA_HOME = C:\Program Files\Eclipse Adoptium\jdk-17.x.x-hotspot
```

### 2. Android Studio

Download and install **Android Studio Meerkat (2025.3.3)** or later from:  
<https://developer.android.com/studio>

During setup, make sure the following SDK components are installed (via **SDK Manager**):

| Component | Version |
|---|---|
| Android SDK Platform | **34** (Android 14) |
| Android SDK Build-Tools | **34.0.0** |
| Android SDK Command-line Tools | latest |
| Android SDK Platform-Tools | latest |
| Android Emulator | latest (if testing on emulator) |

### 3. Android SDK path

Make sure `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) is set.

If you installed Android Studio to the default location:

| OS | Typical path |
|---|---|
| Windows | `C:\Users\<you>\AppData\Local\Android\Sdk` |
| macOS | `~/Library/Android/sdk` |
| Linux | `~/Android/Sdk` |

A `local.properties` file in the project root is **not committed** (it's in `.gitignore`).  
Android Studio creates it automatically, or you can create it manually:

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

---

## Clone & Open

```bash
git clone https://github.com/<your-user>/Video-to-mp4.git
cd Video-to-mp4
```

Then open the folder in Android Studio:

1. **File → Open…** → select the `Video-to-mp4` root directory.
2. Wait for Gradle sync to finish (it downloads dependencies automatically).
3. If prompted to update Gradle or AGP, accept the defaults.

---

## Gradle Wrapper

The project ships a `gradlew.bat` (Windows) wrapper script and `gradle/wrapper/gradle-wrapper.properties`.  
The wrapper downloads Gradle **8.13** automatically on first run.

> **Note:** The `gradle-wrapper.jar` binary is not included in the repo. Android Studio will generate it on first sync, or you can run:
>
> ```bash
> gradle wrapper --gradle-version 8.13
> ```
>
> (Requires a system-wide Gradle install just for this one command.)

---

## Building

### From Android Studio

- Select the **app** module and the **debug** build variant.
- Click the green **Run ▶** button (or `Shift+F10`).
- Choose a connected device or emulator.

### From the command line

```bash
# Debug APK
./gradlew assembleDebug          # Linux / macOS
gradlew.bat assembleDebug        # Windows

# Release APK (requires signing config)
./gradlew assembleRelease

# Install directly on a connected device
./gradlew installDebug
```

Output APK location:

```
app/build/outputs/apk/debug/app-debug.apk
```

---

## Project Structure

```
Video-to-mp4/
├── app/
│   ├── build.gradle.kts            ← App-level Gradle config
│   ├── proguard-rules.pro          ← ProGuard / R8 rules
│   ├── DEVELOPMENT.md              ← This file
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/videoconverter/app/
│       │   ├── MainActivity.kt      ← Main UI, file picker, permissions
│       │   ├── MainViewModel.kt     ← Business logic, FFmpeg calls
│       │   ├── VideoFile.kt         ← Data class for a selected video
│       │   ├── VideoFileAdapter.kt  ← RecyclerView adapter
│       │   └── ConversionService.kt ← Foreground service for long conversions
│       └── res/
│           ├── layout/              ← XML layouts
│           ├── values/              ← Colors, strings, themes
│           └── drawable/            ← Button & card backgrounds
├── build.gradle.kts                 ← Root Gradle config
├── settings.gradle.kts
├── gradle.properties
├── gradlew.bat                      ← Gradle wrapper (Windows)
└── README.md
```

---

## Key Dependencies

| Library | Purpose |
|---|---|
| `io.github.jamaismagic.ffmpeg:ffmpeg-kit-lts-16kb:6.1.7` | FFmpeg engine for video conversion (community fork, LTS build) |
| `material:1.11.0` | Material Design components |
| `lifecycle-viewmodel-ktx` | MVVM architecture |
| `activity-ktx` | `viewModels()` delegate |
| `documentfile` | SAF-based file access helpers |

Dependencies are declared in [app/build.gradle.kts](build.gradle.kts).

---

## Signing a Release APK

1. Generate a keystore:

   ```bash
   keytool -genkey -v -keystore release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias release
   ```

2. Create `keystore.properties` in the project root (this file is git-ignored):

   ```properties
   storeFile=../release-key.jks
   storePassword=yourpassword
   keyAlias=release
   keyPassword=yourpassword
   ```

3. Add a signing config in `app/build.gradle.kts` and build:

   ```bash
   ./gradlew assembleRelease
   ```

---

## Troubleshooting

| Problem | Solution |
|---|---|
| `SDK location not found` | Create `local.properties` with your `sdk.dir` path |
| Gradle sync fails | Make sure JDK 17+ is installed and `JAVA_HOME` is set |
| Emulator too slow for FFmpeg | Test on a physical device – FFmpeg is CPU-intensive |
| `MANAGE_EXTERNAL_STORAGE` denied | The app falls back to saving in `Movies/VideoConverter/` or `Downloads/` instead of next to the originals |

---

## Contributing

1. Fork the repo.
2. Create a feature branch (`git checkout -b feature/my-change`).
3. Commit your changes and push.
4. Open a Pull Request with a clear description.

Please keep the code style consistent with the existing Kotlin conventions used in the project.
