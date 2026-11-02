# Midori

Midori is an Android app for private conversations, encrypted files and private groups. It uses Kotlin, Jetpack Compose and Smack, with mandatory OMEMO encryption and explicit device trust for private messages.

Android application ID: `org.astianspika.android`. Current debug version: `0.1.0-dev` (code 2). English, Spanish and Portuguese are supported. Communities, broadcast channels and calls remain planned; see the [messaging plan](docs/plan-mensajeria-2026-11-02.md).

## Build

Install JDK 17 and the Android SDK (compileSdk 35; Android 13/API 33 or newer to run). Set `sdk.dir` in your ignored `local.properties` if needed.

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew assembleDebugAndroidTest
./gradlew lintDebug
python3 scripts/verify-translations.py
```

`./scripts/setup-dev.sh` builds the debug APK directly. The build uses Gradle dependencies only. Install with `./gradlew installDebug`; the output is `app/build/outputs/apk/debug/app-debug.apk`.

Release tasks are `assembleRelease` and `bundleRelease`. For a signed release, configure `MIDORI_RELEASE_STORE_FILE`, `MIDORI_RELEASE_STORE_PASSWORD`, `MIDORI_RELEASE_KEY_ALIAS` and `MIDORI_RELEASE_KEY_PASSWORD` outside version control. A successful build is not a release decision.

## Messaging

The app offers sign-in and compatible-server registration, contact requests and QR/link review, private conversations and groups, encrypted attachments, voice/video files, delivery/read states, device approval, profiles and optional visible background connection. A phone number is not required. Publishing a profile is a separate, explicit consent action; the public copy is readable by the server.

`AppViewModel` and `MidoriApp` own the host and navigation. `ChatViewModel` and `ChatSession` own messaging and the connection service. The encrypted chat vault protects account credentials, identities, trust, ratchets and local history with Android Keystore. Backup and device transfer are disabled. Never clear app data to fix an unreadable saved session.

Private groups currently have a 200-member policy. Larger groups, push, linked devices, communities, calls and broader interoperability need separate implementation and acceptance. No universal client compatibility, security audit or guaranteed background delivery is claimed.

## Local acceptance

Use the visible Pixel 6 API 34 emulator and genuine local ejabberd accounts. Start the emulator with `-gpu swiftshader_indirect`, resolve its serial by AVD name and preserve existing app data. The local messaging port is 5222; 5280 is administration.

Optional pilot configuration uses `midori.chat.pilotHost`, `midori.chat.pilotDomain` and `midori.chat.pilotPort` in `local.properties`, or `MIDORI_CHAT_PILOT_HOST`, `MIDORI_CHAT_PILOT_DOMAIN` and `MIDORI_CHAT_PILOT_PORT`. Empty settings keep manual server setup. The debug-only `midori.chat.devCertificateSha256` must match the independently inspected local certificate; release has no local trust exception.

After installing the app and androidTest APKs, run `scripts/test-live-chat-ui.py --serial SERIAL --avd Pixel_6_API_34 --pin INSPECTED_SHA256 --workspace PRIVATE_DIRECTORY`. It retains its lab accounts and encrypted peer state for continuity. `scripts/test-chat-acceptance.py` provides additional opt-in scenarios, and `scripts/forward-local-upload.py` relays local TLS attachment traffic. Keep private workspaces and provisioning data out of source control.

The new application ID is a separate Android installation. It does not automatically copy private data from another application ID.

## Appearance and licenses

Chats and Settings include profiles, contact QR, notifications, app language, light/dark/system appearance, launcher choices and app/chat theme import/export. Themes contain portable appearance only, never private account data or local image paths. System typography and plain chat backgrounds are the defaults.

Project-authored source is Apache-2.0. The combined app includes GPLv3 Signal OMEMO components and has corresponding-source distribution obligations. See [third-party notices](THIRD_PARTY_NOTICES.md) and the in-app license screen.
