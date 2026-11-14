---
name: project-omadga-status
description: "OMADga fork progress as of 2026-11-14 - commits done, uncommitted settings UI, what is verified, next steps"
metadata:
  node_type: memory
  type: project
  originSessionId: 616adf7d-c019-4950-99e0-3b681ef1f99d
  modified: 2026-11-14T15:09:57.031Z
---

State when work moved off the ThinkPad (2026-11-14 ~15:10), branch `feature/download-reencode`, NOT pushed
(no upstream; origin is `git@github.com:lkkhoo/OMADga.git`).

Commits (all authored `lk <khooliankeat@gmail.com>`, tier 1 per AGENTS.md, no trailer; no git identity is configured
on the ThinkPad so they were made with `git -c user.name=lk -c user.email=...`):
- `a8c9d3d99` Change the application ID to `app.omadga`
- `60aa3e99b` Add OMADga project notes to CLAUDE.md
- `a6468e1ce` Add a WebP re-encode helper to ImageUtil (`ImageUtil.reencodeToWebp`, `fitWithin`, `sampleSizeFor`,
  plus `ImageUtilTest`, 3 tests pass)
- `2aa340184` Add download re-encode preferences (`reencodeDownloads` Boolean default false, `reencodeQuality` Int 75)
- `04f160fea` Re-encode downloaded pages as screen-sized WebP (hook in `Downloader.getOrDownloadImage`,
  `reencodeImage`, fixed box 1264x1680)

Verified with `./gradlew check` / module checks: everything above. Baseline `assembleDebug` built (16 min cold).
Not verified on a device: all image-processing behaviour (no device, no emulator).

Uncommitted and UNCHECKED (the ThinkPad `check` was killed after CPU overload): settings UI in
`SettingsDownloadScreen.kt` (switch "Shrink downloaded pages" + WebP quality slider 50..100 step 5, visible only when
the switch is on) and 3 strings in `i18n/.../strings.xml`. Diff saved in `omadga-settings-ui-uncommitted.patch` in this
memory dir. Re-apply with `git apply`, run `./gradlew :app:check :i18n:check`, then commit as
"Add download re-encode settings to the Downloads screen".

Design choices worth keeping: helper returns null (keep original) for animated images, any side >16383 px, decode
failure, and images taller than 2x the box height (webtoon strips; chunking is a later slice). Caller keeps the
original unless the WebP is smaller. Re-encode only runs on freshly fetched pages, so resume skips it. Install of the
Mihon fork alongside stock Mihon works via the new app ID.

Toolchain (ThinkPad): JDK 21, SDK at `~/Android`, `local.properties` has `sdk.dir`, packages installed:
`platforms;android-37.2`, `build-tools;37.0.0`, `platform-tools`, `ndk;29.0.14206865`, licences accepted.
`ANDROID_HOME` is not exported in the shell profile.

Next after the UI commit: update the CLAUDE.md "Status" section (still says nothing is built), push the branch, then
Phase 1 slices (grayscale, crop, spread split, webtoon chunking, device profiles).

**Why:** user is moving the work to a Windows desktop with nothing set up, expecting faster builds.
**How to apply:** on the desktop, clone the fork, check out `feature/download-reencode` (needs the branch pushed first,
ask the user before pushing), install JDK 21 plus the Android cmdline-tools/SDK/NDK listed above, and re-apply the patch.
See [[feedback-gradle-builds]].
