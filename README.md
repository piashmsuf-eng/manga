# Manga — AI Auto-Translation Reader

A self-contained Android app that reads manga from local folders / CBZ archives **and**
runs an on-device floating "translation pill" that captures any screen, OCRs the
text and shows the translation — fully offline once the language packs are
downloaded.

| | |
| --- | --- |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 34 (Android 14) |
| Language | Kotlin |
| OCR | Google ML Kit (Latin / Japanese / Korean / Chinese, on-device) |
| Translation | Google ML Kit Translate (50+ languages, on-device) |

## Features

- **Library** — point the app at one or more folders (Storage Access Framework)
  and it will index image folders + `.cbz` / `.zip` archives.
- **Reader** — full-screen swipeable viewer. Auto-translate while reading is on
  by default; each page's text is OCR'd and rendered as captions.
- **Floating translation pill** — a draggable overlay that lives on top of any
  app. Tap it to capture the current screen, run OCR + translation and show the
  result in a panel that you can copy.
- **Settings** — pick OCR script (auto / JA / KO / ZH / Latin), source language
  override, target language, and toggle reader auto-translate.
- **Privacy** — every model runs on-device. No analytics, no network calls
  except to download ML Kit language packs the first time you use them.

## Architecture

```
app/
└── src/main/java/com/piashmsuf/manga
    ├── MainActivity.kt                 — library + entry-points
    ├── MangaApp.kt                     — notification channels / dynamic colors
    ├── library/Library.kt              — SAF folder scanner
    ├── library/LibraryAdapter.kt       — grid of MangaItem cards
    ├── reader/MangaSource.kt           — Folder + CBZ/ZIP loaders
    ├── reader/PageAdapter.kt           — viewpager2 page binder
    ├── reader/ReaderActivity.kt        — full-screen reader + auto-translate
    ├── overlay/OverlayService.kt       — the floating "pill" service
    ├── overlay/ScreenCaptureService.kt — single-frame MediaProjection capture
    ├── overlay/MediaProjectionRequestActivity.kt
    ├── translate/OcrEngine.kt          — script-aware ML Kit OCR wrapper
    ├── translate/TranslatorEngine.kt   — ML Kit translation + lang-id
    ├── translate/TranslationPipeline.kt — OCR → translate → block list
    ├── settings/SettingsActivity.kt    — preference screen
    ├── util/Permissions.kt             — overlay / notif helpers
    └── util/Prefs.kt                   — typed SharedPreferences wrapper
```

The translation pipeline is shared between the reader and the floating pill, so
both code paths produce the same result objects.

## Building locally

```bash
git clone https://github.com/piashmsuf-eng/manga.git
cd manga
./gradlew :app:assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

Requirements:

- JDK 17
- Android SDK 34 + build-tools 34.0.0
- Gradle 8.7 (the wrapper handles this automatically)

A signed release build can be produced with `./gradlew :app:assembleRelease`.
The release variant is signed with the debug key by default — override
`signingConfigs.release` in `app/build.gradle.kts` if you have a keystore.

## CI

`.github/workflows/android.yml` builds both debug and release APKs on every
push and uploads them as workflow artifacts. The workflow name `Android` is
used so badges and `git pr_checks` can find it.

## Permissions used

| Permission | Why |
| --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Download ML Kit language packs on first use. |
| `SYSTEM_ALERT_WINDOW` | Render the floating translation pill. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the pill / one-shot screen capture alive. |
| `POST_NOTIFICATIONS` | Show the foreground-service notifications. |
| `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE` | Read manga images on older Android versions. |

The app **never** captures the screen automatically — every translation tap
pops the standard MediaProjection consent dialog the first time, so the user
always knows when capture is in progress.

## License

Released under the MIT License — see [LICENSE](LICENSE).
