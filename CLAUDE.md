@AGENTS.md

# OMADga

Personal fork of Mihon (`mihonapp/mihon`, Apache 2.0) tuned for e-readers: small downloads and e-ink-friendly
reading. Remote `origin` is the fork, `upstream` is Mihon. Keep patches small and isolated so rebasing on upstream
stays cheap. Feature work goes on `feature/*` branches, not `main`.

## Status (update as work lands)

- On `feature/download-reencode`: application ID `app.omadga`, app name OMADga; `ImageUtil.reencodeToWebp` and a
  hook in `Downloader.getOrDownloadImage`. Settings under Downloads, shown when "Shrink downloaded pages" is on:
  quality (50..100, default 75), target device (Musnap Ocean C, Boox Page, both 1264x1680; Boox Page forces
  grayscale), grayscale, crop white margins, sharpen and boost contrast, smooth downscaling (all off by default).
  Also a "download all chapters" action in the shared download menu. Verified with `check`; image behaviour is
  only unit-tested for the pure helpers.
- Measured on a Boox Page with Ao Ashi (411 chapters, grayscale PNG source from Komikku, 2.47 GB): q75 gave
  about 74% of the source, q50 with all switches gave 60%. Quality is the main lever; grayscale and crop saved
  about nothing there. Not yet tried on a device: sharpen, contrast, smooth downscaling.
- Not done yet: spread split, webtoon strip chunking (strips taller than 2x the box are left unprocessed), a tool
  to re-encode existing downloads. The renamed app only changed `app_name`; see Fork rules.
- Toolchain verified: JDK 21, SDK, NDK 29, `assembleDebug` and `check` all build.
- Devices: a Boox Page is available for testing over adb (internal storage 23 GB, plus a microSD). The Musnap
  Ocean C is not bought yet. Flag anything that needs hardware as untested.
- The design notes below come from a read of the source, not from running it. Re-read `Downloader.kt` and
  `ImageUtil.kt` before coding, since upstream may have moved.

## Why this fork exists

- User reads manga through Mihon/Komikku extensions and wants lots of it on a 64 GB e-reader with no SD slot.
  Downloads are stored as unprocessed source images, so there's no way to shrink them; no existing fork does it.
- The user chose to fork Mihon and add download-time compression rather than rely on a server-side KCC/`cwebp`
  pipeline on their home server (lkserver2). That pipeline, plus delete-after-read, remains the fallback.
- Atahon (another reader) compresses to WebP at download time, but whether it supports Mihon extensions is
  unverified, so it is not a substitute.
- The user supplied the cause (storage) and the approach (compress at download time, e-reader tuning); apply
  AGENTS.md's attribution rules with that in mind.

## Goals

- Cut on-device storage for downloaded manga without changing sources or extensions.
- Optimise download output and reading for e-ink panels (target device: Musnap Ocean C, 7" Kaleido 3,
  1264x1680, 64 GB, no SD slot).

## Fork rules

- Change the application ID (currently `app.mihon`) so OMADga installs alongside stock Mihon. That touches
  `app/build.gradle.kts`; AGENTS.md's "don't touch `gradle/`" does not cover it.
- Keep the extension API untouched so existing Mihon extensions keep working.
- Rebranding stops at the user-visible app name (`app_name`). Kotlin packages (`mihon.*`), Gradle plugin ids, the
  `mihon://` deep-link scheme (tracker logins), tracker User-Agents, licence notices and the support/donation
  texts stay as upstream so rebasing stays cheap and nothing claims to be OMADga's donation campaign.
- Don't restyle beyond what the e-ink work needs.

## Design notes (from reading the source, not yet implemented)

- Download pipeline: `app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt`. Each page ends up as a
  file in `tmpDir` via `copyImageFromCache` or `downloadImage`, called from `getOrDownloadImage`. Hook the
  processing step right after that. `archiveChapter` zips `tmpDir` as CBZ, so it needs no change.
- Update `isDownloadSuccessful`: it counts files and special-cases the `__001.jpg` split-page suffix, so a changed
  extension or new split naming must be handled there. `isDownloadedPageImage` matches on filename prefix only,
  so resume should still work.
- Preferences: `domain/src/main/java/tachiyomi/domain/download/service/DownloadPreferences.kt`, plus a Downloads
  settings entry (settings UI not yet read).
- `core/common/.../util/system/ImageUtil.kt` decodes to `Bitmap` and compresses only as JPEG q100. There is no
  WebP encode or downscale helper yet; add them there.
- Komikku calls `splitTallImageIfNeeded(page, tmpDir)` in its `Downloader.kt` behind a download preference. Use it
  as the pattern for a gated processing step.
- Neither Mihon, Komikku nor TachiyomiSY has any download re-encode or downscale feature (checked in source).
- Known risks: WebP max dimension is 16383 px per side (tall webtoon pages), memory use when decoding large
  images (use `inSampleSize` or region decoding), skip animated images, not retroactive for existing downloads,
  KOReader and other readers may not read WebP inside CBZ.

## Plan

Phase 1: download-time pipeline (build first)
- Device profiles: target resolution presets (Kaleido 3 7" 1264x1680, mono 1072x1448, custom). Scale to fit the
  screen, never upscale.
- Grayscale toggle and optional quantisation to 16 levels for mono panels.
- Colour boost (saturation/contrast curve) for Kaleido; subjective, needs on-device testing.
- KCC-style cleanup: auto-crop white margins, gamma/levels, mild sharpen after downscale.
- Split double-page spreads into two pages at download time.
- Chunk long webtoon strips into screen-height pages (also avoids the WebP size limit).
- Format setting, WebP vs JPEG: test decode speed on the device, since slow e-ink SoCs may page-turn faster on JPEG.

Phase 2: reader behaviour
- Paged mode default, no page-turn animations or crossfades.
- Periodic full-refresh flash every N pages as a generic ghosting fallback; Musnap refresh API is unknown.
- Hardware page-button mapping; pre-decode the next pages.

Phase 3: UI and background
- Pure black/white e-ink theme, no gradients/shadows/ripples, larger touch targets.
- Fewer redraws during downloads (progress notifications), reduced background work and library update frequency.

Open question: default device profile (Musnap resolution vs generic with custom option). Image processing beyond
`Bitmap` APIs (sharpen, 16-level quantisation) may need custom code or a library; this is the main effort unknown.

## Build

- JDK 21 (CI uses `.github/.java-version`), Java 17 target, Gradle 9.9.1 via the wrapper, compileSdk 37.2,
  targetSdk 36, minSdk 26, NDK 29.0.14206865.
- Android SDK via `ANDROID_HOME` (command-line tools only); `local.properties` holds `sdk.dir`.
- `./gradlew assembleDebug` to build; `./gradlew check` before committing (see AGENTS.md).
- `sudo` fails from the Bash tool on this machine; give the user the command to run instead.
