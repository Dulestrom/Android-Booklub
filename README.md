# Booklub for Android

A private reading tracker for Android, and the phone companion to [Booklub for the Linux desktop](https://github.com/Dulestrom/Desktop-Booklub). Books are vertical tiles, categories are horizontal ones, and your library stays on your own devices.

There's no account, no server, and no cloud. Pair the phone with Booklub on your computer and the two keep one library in step, across your home network and nowhere else.

## Features

### Your library

- Browse Home, with Continue Reading and your categories, and Library, with status chips for To Read, Reading, and Finished
- Tap a book for a read-only preview, and long-press it to edit or delete
- Add books with title, author, category, sub-category, priority, status, pages, dates, rating, notes, ISBN, publisher, year, and language
- Give a book a background image from your photos or the clipboard
- Create, rename, and delete categories and sub-categories, and give a category built-in artwork or your own image
- Search, sort, and filter like the desktop app
- Follow the system's light or dark setting, or choose one in Settings

### Analytics and goals

- The desktop's Analytics in one column: books finished by month, average days to finish and rating, progress toward your goals, and how your reading splits across categories and priorities
- Set and change yearly and monthly goals

### Sync with your computer

- Pair by scanning the QR code Booklub shows on your computer, or by typing its address and 8-character code
- The phone takes your computer's whole library at its first sync, images included
- Changes on either side reach the other within seconds while Booklub is open on both, and a change on one phone reaches another through the computer
- Each field merges on its own, so a page count updated here and a title fixed on the computer both survive, and a deletion wins over an edit
- Sync happens only while Booklub is on screen, never in the background
- Traffic stays on your home network, encrypted with TLS 1.3, and the pairing code can't be guessed from what goes over the air
- Images arrive as thumbnails. Keep them in Booklub's own storage or on a memory card, and if the phone runs short of space, a sync stores nothing and says how much it needs

## Requirements

- Android 13 or newer, on a 64-bit phone
- To sync: [Booklub for the desktop](https://github.com/Dulestrom/Desktop-Booklub) 1.4.0 or newer, on the same home network, with sync turned on in Settings, then Devices. If the computer has a firewall, allow TCP port 47120.

## Permissions

| Permission | Why |
|---|---|
| Camera | Scanning the pairing code, and only then. Typing the code works without it. |
| Internet | Android needs it for any network connection. Booklub connects only to home-network addresses. |
| Network and Wi-Fi state, Wi-Fi multicast | Finding your computer on the home network when its address changes |

## Build it yourself

The app is Kotlin with Jetpack Compose. Every rule and all storage live in Rust, shared with the desktop app through [UniFFI](https://github.com/mozilla/uniffi-rs). The bridge in `rust/` takes the shared crates from the desktop repository, pinned to one commit.

You need:

- JDK 21
- The Android SDK with platform 36, build tools 36.0.0, and NDK r28c. On Ubuntu, the `google-android-*-installer` packages put these under `/usr/lib/android-sdk`.
- Rust from [rustup](https://rustup.rs), with the `aarch64-linux-android` and `x86_64-linux-android` targets, and `cargo install cargo-ndk --version 4.1.2`

`tools/env.sh` expects that Rust under `~/.local/share/booklub-android/`, kept off your `PATH`, so it never replaces a Rust your system already has. Set `RUSTUP_HOME`, `CARGO_HOME`, `ANDROID_HOME`, `ANDROID_NDK_HOME`, or `JAVA_HOME` there to use other places.

```bash
tools/build_rust.sh release
```

```bash
source tools/env.sh && ./gradlew assembleDebug
```

The first command builds the native library for phones and the emulator, generates the Kotlin bindings, and writes the licence notices. The APK lands in `app/build/outputs/apk/debug/`.

## Tests

```bash
source tools/env.sh && (cd rust && cargo test)
```

```bash
source tools/env.sh && ./gradlew testDebugUnitTest
```

The Rust tests pair the phone's bridge with a real sync server over loopback. The JVM tests call the Rust library through JNA, and `QrTest` decodes a desktop screenshot of the pairing code when `BOOKLUB_QR_PNG` points at one.

## Project layout

```
app/src/main/kotlin/     the screens, app state, and sync controller
app/src/main/res/        icons, fonts, and the adaptive launcher icon
app/src/main/assets/     built-in category artwork
rust/                    the bridge between the shared Rust core and Kotlin
tools/                   the native build, environment, icon conversion, and licence notices
```

## Licence

ISC, in [LICENSE](LICENSE). Manrope is under the SIL Open Font License, in `app/src/main/assets/MANROPE-LICENSE.txt`. The licences of every library compiled into the app are under Settings, then Third-party notices.
