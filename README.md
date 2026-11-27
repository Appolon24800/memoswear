<img src="assets/icon.svg" width="64" height="64" alt="">

# MemosWear

MemosWear is a standalone Wear OS app for a self-hosted [Memos](https://github.com/usememos/memos)
server. Its design follows Google Keep for Wear OS. It is written in Kotlin with Compose for Wear OS
Material 3.

## What it does

- Shows the newest notes, pinned ones first, with tags, tasks, code, links and pictures.
- Saves a spoken note in two taps, or a typed one. Notes save while you type.
- Ticks tasks, edits, pins, changes who can read a note, archives, deletes, and comments.
- Searches by text and by tag.
- Reads and writes offline. Changes made without a connection are sent when one returns.
- Offers three tiles (recent notes, pinned note, new note) and a "new note" complication.
- Follows the colors of the watch face on Wear OS 6, and uses its own colors elsewhere.

## Requirements

- Wear OS 3 or later.
- A Memos server with the current API, release 26.10 or later. That API has no `v1` in its paths.

## Sign in

Open the app and enter the server address. The next step depends on the server:

| Server | How to sign in |
| --- | --- |
| An OAuth provider such as Pocket ID | The provider's page opens inside the app. The watch needs a WebView for this. Without one, the app says so and offers an access token instead. |
| Password sign-in allowed | Username and password. |
| Any | A personal access token. In Memos on another device: Settings, Access tokens. |

The session lasts as long as the server's refresh cookie, which Memos renews for 30 days at each use.

## Build

You need JDK 17 and an Android SDK with platform 37.

```sh
./gradlew :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

The debug build is signed with the debug key and accepts plain HTTP servers, which makes it
usable on an emulator.

### Release

A release build accepts HTTPS servers only. It is signed when `keystore/signing.properties` defines
`ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`,
or when the same names are set as environment variables. Without them the build produces an
unsigned APK.

```sh
./gradlew :app:assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

### Try it on a watch emulator

1. Create an AVD from the image `system-images;android-36;android-wear-signed;x86_64`.
2. Install the debug build with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
3. The emulator reaches the computer as `10.0.2.2`. Start Memos with
   `--instance-url http://10.0.2.2:<port>`, then enter that address in the app.

The emulator image has Google's speech recognizer, so voice capture can be exercised there. It has
no WebView, so the OAuth sign-in page cannot be shown on it.

## Checks

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
```

The unit tests cover the Markdown reader, the API client, the session and its token refresh, the
offline queue, and the content of each tile.

## Limits

- Tiles are declared and their layouts are tested, but they have not been seen in the system tile
  carousel: the emulator offered no way to add a tile.
- Signing in through an OAuth provider has not been exercised on a watch.
- The app has not run on a physical watch.
