# Messaging dependencies

Project-authored source retains Apache-2.0. Distribution of the combined Android app must also satisfy GPLv3 obligations for the Signal OMEMO components, including corresponding source and applicable notices. Do not describe the combined APK as exclusively Apache-2.0.

| Component | Pinned version | License | Source |
| --- | --- | --- | --- |
| Smack core, Android, TCP, extensions, experimental and OMEMO core | 4.5.0 | Apache-2.0 | https://github.com/igniterealtime/Smack/tree/4.5.0 |
| Smack OMEMO Signal adapter | 4.5.0 | GPL-3.0-or-later | https://github.com/igniterealtime/Smack/tree/4.5.0/smack-omemo-signal |
| libsignal-protocol-java | 2.8.1 | GPL-3.0 | https://github.com/signalapp/libsignal-protocol-java/tree/v2.8.1 |
| AndroidX Emoji2, Emoji Picker and bundled adapter | 1.5.0 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/emoji2 |
| Bundled Noto Color Emoji | Bundled font | SIL OFL 1.1 | https://github.com/googlefonts/noto-emoji |
| JourneyApps ZXing Android Embedded | 4.3.0 | Apache-2.0 | https://github.com/journeyapps/zxing-android-embedded |
| ZXing Core | 3.5.3 | Apache-2.0 | https://github.com/zxing/zxing |
| Manrope | Bundled font | SIL OFL 1.1 | https://github.com/google/fonts/tree/main/ofl/manrope |

Original notices are in `app/src/main/assets/licenses/` and the in-app Settings → Open source licenses screen. Kotlin, Compose and the other AndroidX dependencies retain their upstream notices. Conversations is a behavioral reference; its source has not been copied into this project.

## Host tests

The opt-in JVM peers use Bouncy Castle `bcprov-jdk18on` 1.80.2 to provide cryptographic algorithms required by the host laboratory. It is declared only in `testImplementation`; its original license is retained at `app/src/test/resources/licenses/BouncyCastle-LICENSE.txt`. Source: https://www.bouncycastle.org/. Test dependencies are not application capabilities.
