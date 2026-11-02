# Midori Wallet

Midori Wallet is an Android app for **private conversations and a self-custodial wallet**, in development. Chat and wallet are the two product pillars, with slightly more priority for everyday messaging and ease of use. People can start chatting without creating a wallet. **USDC and USDT on Base are the primary financial assets**, followed by Bitcoin and other assets. Payments to contacts from within a chat remain planned.

> **Status: v0.1 in development.** Closing this version requires both a usable conversation pilot and funded wallet acceptance. The active financial configuration is **Base mainnet (8453), USDC and USDT only**; transfers use real assets. Existing messaging and transaction review provide a base, while account/invitation usability, uncertain message retries, durable financial history and mobile reception still need work according to the release plan.

## What works today

| Area | Current state |
| --- | --- |
| Wallet | Create a 12-word recovery phrase, import an existing one, and restore a wallet saved on the device. |
| Balances | One nominal digital-dollar account backed by separate USDC and bridged USDT balances on Base. |
| Send and receive | Review USDC and USDT transfers (fee paid in stablecoin, no ETH); receive QR codes contain only the address, with asset/network shown alongside. The app displays a transaction hash after broadcast but does not yet reconcile normal transfers or verify their confirmation. |
| Tools | Sign and verify messages; open broadcast transactions in the explorer for the corresponding network. |
| Interface | Chats-first navigation with Wallet, Markets and More; introduction with native vector icons, MW branding, light/dark/system themes, and English, Spanish and Portuguese. |
| Messaging | Sign-in and basic registration on compatible servers, mandatory OMEMO text and encrypted files, voice notes, private groups, device approval, contact requests, blocking, local encrypted history, archive catch-up, 1:1 delivery/read states and private notifications. Initial local ejabberd integration; see limitations below. |

**Still needed to complete v0.1:** two new users must complete guided access, invitation/contact approval, device trust, a reciprocal conversation, a simple reply and voice/photo exchange, then return from a notification. Interrupted sends must preserve text and identity without treating uncertain delivery as a definite failure. Wallet acceptance requires authorized, small mainnet transfers between controlled installations, covering USDC and USDT (fee paid in stablecoin), and restoration through a provider failure. Provider-failure recovery, in-place update and a USDC send were accepted on the Pixel 6 on 2026-10-22/23; USDT receive/send is pending funds. Both pillars need acceptance on the final APK; builds and fixtures alone do not close either one.

Use **Theme** on the welcome screen or **More → Appearance** to choose **Light**, **Dark** or **System**. System mode is the default and follows Android changes automatically. The preference is stored separately from wallet credentials and survives app restarts. App content, loading/errors, status/navigation bars and the MW logo adapt to the selected appearance; receiving QR codes retain black-on-white contrast. The native Android launch screen follows the device theme, while the in-app launch screen uses the saved preference. See the [appearance verification record](docs/verification-v0.1-appearance-2026-10-06.json) for that change's two-device checks and APK hash.

The app supports **English, Spanish and Portuguese**, including appearance controls, validation, operation statuses and accessibility labels. It follows the device language by default, with English as the fallback. On Android 13+, select an app language in **Settings → Apps → MiWallet → Language** (the path can vary by device); choosing the system default restores automatic selection. Regional Spanish and Portuguese locales use the corresponding shared catalog. Android saves the language preference. Recovery words, asset symbols, addresses, QR payloads and messages entered for signing are never translated.

Text lives in `app/src/main/res/values/strings.xml`, `values-es/strings.xml` and `values-pt/strings.xml`. Add new text to all three files, preserving numbered format arguments such as `%1$s`. ViewModel messages use `UiText` and resolve when rendered, so an open screen and its status messages follow a language change. Amount entry and on-chain conversions retain their existing exact decimal representation.

The welcome carousel presents the wallet, USDC/USDT on Base, recovery and XMPP messaging. **Get started** opens **Chats** without creating a wallet. This choice is saved separately from wallet credentials. An existing wallet also opens Chats after restoration. The main navigation is **Chats → Wallet → Markets → More**. Chats displays real contacts and conversations, search, all/unread/group filters, contact requests, unread counts and timestamps. The shared interface uses system typography by default, neutral white/charcoal surfaces and a restrained green accent. Saved font and chat wallpaper preferences remain available. Compact headers, flat lists and 48 dp actions prioritize conversations and financial information. Wallet balances can be hidden; More exposes system notification controls. The conversation screen has message bubbles, device verification, persistent drafts and a rounded composer that switches to Send while typing. Its attachment panel opens photos/videos, camera, files, chat contacts, location and video notes; voice recording has a direct control. Attachments require a preview or confirmation before sending. Private groups support invitations, members, device verification, administrator removal and deletion. Wallet keeps create/import, balances, send and receive. Markets offers asset search and network/contract details while live quotes remain unconnected. More groups account/privacy, appearance, language, help, licenses and wallet management. XMPP is mentioned in onboarding; everyday chat screens use plain product language.

Messaging uses **Smack 4.5.0 with mandatory OMEMO** and an independent Android Keystore vault. There is no plaintext fallback. Device fingerprints require explicit approval; read receipts are enabled by default, with an explicit opt-out that survives restarts. Notifications hide sender and message content, group conversations, count new arrivals and use peer-specific navigation intents. A foreground service maintains the user-enabled connection and requests restart after system process termination; force-stop and Android power restrictions still apply. Private groups and encrypted HTTP uploads are tested against local ejabberd. Files are limited to 20 MiB and groups to 200 members, including the creator (the inspected local ejabberd MUC ceiling). Push/Doze reliability, calls, public account registration and Conversations interoperability remain pending. See the [integration and XEP matrix](docs/messaging-integration.md) for local TLS setup, test commands and exact scope. The [chat media UX verification record](docs/verification-v0.1-chat-media-ux-2026-10-07.json), the [initial messaging verification record](docs/verification-v0.1-messaging-2026-10-06.json) and the [media/group verification record](docs/verification-v0.1-media-groups-2026-10-07.json) distinguish automated checks, device checks and remaining acceptance work. Chat media includes an inline audio player, an internal photo viewer and crop/text/drawing before sending. The current chat UX work adds inline voice recording with pause/resume, standard/HD photos, original documents, video notes, a category-based emoji picker, real contact selection and reviewed QR/links. Profiles remain local unless explicitly published through vCard; optional contact phone numbers stay local and are never registration requirements. See the [video analysis and acceptance scope](docs/chat-ux-video-2026-10-21.md). Long-press a conversation to pin, mute or delete its local history; server history and group membership are retained. The selected Signal adapter has GPL obligations for the combined APK; the project-authored source keeps Apache-2.0. See [third-party notices](THIRD_PARTY_NOTICES.md).


The [200-member acceptance record](docs/verification-v0.1-groups-200-2026-10-07.json) covers 200 JVM peers and an Android API 34 sender with real isolated encrypted storage. Android delivery p95/max was 14.956 seconds against a 15-second laboratory budget, a narrow margin that does not establish production latency. The [group capacity notes](docs/grupos-cifrados-a-escala.md) describe local server quotas, chat vault migration and the still-disabled 200,000-member target.

The welcome carousel now uses native vector icons. The former four transparent illustrations remain bundled as `drawable-nodpi/onboarding_*.png` but are not displayed by onboarding. Their [generation prompts](docs/onboarding-image-prompts.json) and [artwork revision record](docs/verification-v0.1-artwork-2026-10-06.json) describe historical assets; the [main navigation record](docs/verification-v0.1-chats-2026-10-06.json) likewise documents the initial navigation shell, not acceptance of the current interface.

The 2026-10-21 follow-up corrects a chat-list layout defect where an unbounded avatar could displace the conversation text. Conversation rows now keep the avatar, name, preview, timestamp and real message states within a compact layout; media previews use readable labels instead of internal filenames. Video notes appear as 216 dp circles in the conversation, with a progress ring, remaining duration and tap-to-play/pause controls. Ordinary videos retain a rectangular player. The [local acceptance run](docs/evidence/2026-10-21-chat-list-video/verification.json) passed on the visible Pixel 6 with real ejabberd accounts: text/receipts, inline voice pause/resume/discard, encrypted audio/video/HD-photo delivery and companion decryption, circular video playback/pause, existing-contact selection, remote profile reading, own QR and a reviewed contact-link proposal. Build, mainnet checks, translations and 149 unit tests passed; one opt-in unit test was skipped. Thirty screenshots cover EN/ES/PT light, ES dark and ES at 130% text size. Emoji checks covered opening/closing and visual presentation. Own-profile publishing/withdrawal, address-book import, physical media quality and cross-client acceptance remain pending. Wallet navigation was read-only and its encrypted file stayed unchanged. The [video analysis](docs/chat-ux-video-2026-10-21.md) records screenshots and limits; v0.1 remains open.

## What we want to build

The complete experience will let people find their contacts, exchange messages and media, organize conversations, coordinate groups and make calls with clear, familiar controls. Wallet creation/import is optional for chatting. People who activate it can receive, send and view movements, and later pay a verified contact without manually copying addresses. Every payment requires an explicit review of the recipient, network, asset, amount, fee and status. Financial confirmation comes from checking the blockchain.

Social accounts and wallets will have separate responsibilities. An account may restore access to contacts and conversations under the service's rules, but it **will not recover the seed phrase or hold funds**. The wallet will continue to show its local balances and addresses even if the social session expires.

### Roadmap

Versions describe a sequence of work, not promised dates. The [detailed roadmap](docs/roadmap-v0.1-v2.0.md) contains acceptance criteria, risks, and technical decisions.

| Stage | Chat outcome | Wallet outcome | Usability outcome |
| --- | --- | --- | --- |
| **v0.1 · Converse and transact** | Guided account/invitation, device trust, simple 1:1 replies and acceptance of existing messages/media. | Funded mainnet acceptance and restoration through failure. | Two new users can converse; wallet activation remains optional. |
| **v0.2 · Reliable daily MVP** | Durable outbox, history pagination, mobile reception, search, reactions and archive. | Durable movements, reconciliation, local authentication and backup checks. | Resume interrupted tasks and understand their actual state. |
| **v0.3 · Comfortable interactions** | Edit/delete/forward, saved messages, easier voice recording and shared profiles. | Address scanner, local financial contacts, history filters and tested maximum amounts. | Reduce manual entry and make frequent actions easy to find. |
| **v0.4 · Groups and first fiat corridor** | Mentions, pinned messages, permissions, polls and verified group capacity. | Accept one provider/country/direction/method through settlement. | Coordinate a group and understand a purchase or withdrawal. |
| **v0.5 · Media, privacy and regional reach** | Media gallery, resumable attachments, temporary messages, stickers and organization. | Expand toward BR/CO/MX/VE/PE only through individual corridor acceptance. | Control storage/data use and see actual financial availability. |
| **v0.6 · Voice calls and recovery** | 1:1 voice calling, mobile delivery and battery improvements. | History migration/recovery, pending-order continuity and provider resilience. | Clear call states and recovery instructions. |
| **v0.7 · Video and Base conversion** | 1:1 video calling on the accepted voice foundation. | One executable USDC/USDT route, only if the user decides to add conversion again. | Simple camera controls and comprehensible conversion costs. |
| **v0.8 · Continuity and verified identity** | Linked devices, revocation and protected synchronization. | Versioned, revocable proofs binding contacts to receiving addresses. | Distinguish chat account, device and financial identity. |
| **v0.9 · Converse and pay** | Payment requests/cards and their conversation context. | Reviewed USDC/Base payments to a current verified binding. | Request, review, pay and verify within a conversation. |
| **v1.0 · Stable chat and wallet** | Sustained acceptance of published messaging functions. | Sustained acceptance of published assets and services. | Accessible onboarding, signed distribution, recovery and support. |

Later milestones are revisable; chat and wallet remain required pillars in each release. Advanced integrations have their own acceptance gates and can be explicitly rescheduled. Group calls, desktop/web, public channels, much larger groups and additional networks are considered after v1.0. Existing implementations do not mean that their releases have started or passed acceptance; the APK remains `0.1.0-dev` / versionCode 2 until a release decision.

### Product principles

- Every version improves both conversation and financial tasks, with a slight planning preference for chat.
- Starting a conversation must not require wallet creation; chat and wallet keep independent credentials.
- Familiar message actions must remain visible, accessible and understandable in EN/ES/PT.
- Measure reciprocal conversation, voluntary return and delivery reliability; chat retention is a hypothesis to test.
- Preserve drafts, messages and financial intent through errors; explain uncertain outcomes before retrying.
- Users control their recovery phrases and authorize every transfer.
- Payment addresses must be verified; a contact name or message alone cannot establish the recipient.
- Amounts must be calculated precisely, without simulated fiat balances or promises of returns.
- Broadcast, pending, confirmed, and unknown transaction outcomes must be shown accurately.
- The app must explain the asset, network, costs, and errors before an irreversible action.
- Social features must never expose the seed phrase or let a server sign payments.

**Outside the current scope:** executable investments or swaps, server custody, cross-chain bridges, bots, and public channels. Fiat buy/sell integration is in development, with production checkout acceptance pending as detailed below. USDT uses the pinned bridged Base contract; the app does not execute bridging operations. Other assets follow after the Base flows.

## Active mainnets

| Asset | Network | Contract / role |
| --- | --- | --- |
| USDC | Base (8453) | Native Circle USDC, `0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913`, 6 decimals. |
| USDT | Base (8453) | Bridged USDT, `0xfde4C96c8593536E31F229EA8f37b2ADa2699bb2`, 6 decimals. |

The contracts come from [Circle](https://developers.circle.com/stablecoins/usdc-contract-addresses) and the [Superchain USDT registry](https://github.com/ethereum-optimism/ethereum-optimism.github.io/blob/master/data/USDT/data.json). USDT on Base is bridged from Ethereum USDT (`0xdac17f958d2ee523a2206206994597c13d831ec7`) through the Base Standard Bridge (`0x4200000000000000000000000000000000000010`). The verifier checks those on-chain getters; it does not establish bridge solvency. This token is not labeled native Tether issuance or USDT0. [Tether protocols](https://tether.to/en/supported-protocols/).

Runtime providers are [PublicNode Base RPC](https://base.publicnode.com/) and `https://blockbook.btc.zelcore.io/api`. Explorers are `https://basescan.org` and `https://blockbook.btc.zelcore.io`. Providers are public and rate limited; the app has no automatic fallback to another chain. [Base network parameters](https://docs.base.org/get-started/connect-to-base).

To receive, use the QR or address. USDC and USDT share an address on Base; the sender must select the correct **asset and Base network**. Receive QR codes contain only the wallet address for exchange compatibility. A bare EVM address does not identify a chain; choose Base in the sending app. Sending USDC or USDT does not require ETH: the fee is paid in stablecoin.

### Updating from the former testnet build

The application ID and signing identity are unchanged, so `adb install -r` preserves local data. Mainnet opens with create/import instead of automatically reusing the old test wallet. Its credentials are kept separately under Android Keystore encryption. The legacy `wdk_wallet` file is left untouched and is not read by mainnet. Old test assets remain on their original chains; an update never converts or transfers them. Use a new recovery phrase for a new mainnet wallet; importing an existing phrase is an explicit user action. Keep recovery phrases outside logs, screenshots and source control.

## Local development

### Requirements

- Android Studio and Android SDK 35.
- JDK 17.
- Node.js 20.19 or later.
- An Android emulator or device running API 33 or later.
- Enough disk space and connectivity to download dependencies and native resources during the first build.

### Set up, build, and run

```bash
./scripts/setup-dev.sh
./gradlew assembleMainnetDebug
./gradlew installMainnetDebug
```

Open this directory in Android Studio to work with Jetpack Compose. The first build may take longer and download several hundred megabytes. The debug APK is written to `app/build/outputs/apk/mainnet/debug/app-mainnet-debug.apk`.

To launch the app on a connected device:

```bash
adb shell monkey -p org.astianx.midori.one -c android.intent.category.LAUNCHER 1
```

To run the existing unit tests:

```bash
./gradlew testMainnetDebugUnitTest lintMainnetDebug
python3 scripts/verify-translations.py
python3 scripts/verify-mainnet-networks.py
```

The read-only network check verifies Base chain 8453, both contracts' code/symbol/6 decimals and balance reads, USDT origin/bridge getters, and the fee oracle. It does not send funds. The `--base-rpc` override helps check alternate providers; testnet endpoints must fail verification. The [mainnet verification record](docs/verification-v0.1-mainnet-2026-10-06.json) records the scope and limitations.

For storage-constrained emulators, build a smaller debug APK for the device ABI:

```bash
adb -s <serial> shell getprop ro.product.cpu.abi
./gradlew assembleMainnetDebug testMainnetDebugUnitTest -PmidoriDebugAbi=x86_64
adb -s <serial> install -r app/build/outputs/apk/mainnet/debug/app-mainnet-debug.apk
```

Use `arm64-v8a` for a matching physical device. The default debug APK and release builds remain universal; this optional filter applies only to debug. Keep the same flag when rebuilding that device's APK. The illustrated x86_64 debug APK measured about 81 MiB and updated the existing emulator without deleting its wallet.

The APK packages native libraries for compatibility with devices using 16 KB memory pages. Installation and runtime behavior on a 16 KB device still need a dedicated test before release.

### Mainnet release build

The `mainnet` flavor is the only active flavor. `mainnetDebug` uses version `0.1.0-dev`, and the non-debuggable `mainnetRelease` uses `0.1.0` (version code 2). Both connect to mainnet; the debug suffix does not imply test funds.

```bash
./gradlew assembleMainnetRelease
./gradlew bundleMainnetRelease
```

Gradle signs these artifacts when all four environment variables are set:

| Variable | Purpose |
| --- | --- |
| `MIDORI_RELEASE_STORE_FILE` | Absolute path to the release keystore, kept outside the repository. |
| `MIDORI_RELEASE_STORE_PASSWORD` | Keystore password. |
| `MIDORI_RELEASE_KEY_ALIAS` | Alias of the signing key. |
| `MIDORI_RELEASE_KEY_PASSWORD` | Signing key password. |

Without these variables, the release APK and bundle are **unsigned** and cannot be installed or uploaded as release artifacts. Keep the keystore and passwords in a secret manager; never commit them. Android also requires the same signing identity for updates, so an existing debug installation may need to be removed before installing a release build. Both build types exclude wallet data from Android cloud backup and device transfer.

## Architecture

Today the app uses **Kotlin, Jetpack Compose, Material 3, and MVVM**. `wallet/WalletViewModel.kt` owns financial state and operations. The `wallet` package contains wallet storage, networks, SDK adapters and financial screens. The `chat` package contains independent messaging state, encryption, transport, media, notifications and screens; its UI receives navigation callbacks instead of a wallet ViewModel. Each feature has a `Screens.kt` and `Components.kt`. The root package owns the app host/navigation, shared UI, `UiText` and `Theme.kt`. `KeystoreWalletStore` protects mainnet credentials; `WalletNetwork` defines the active chains/contracts, and the Base fee and Bitcoin network adapters validate preparation before broadcast.

The visual direction uses [WhatsApp chat themes](https://blog.whatsapp.com/chat-themes-to-reflect-your-style), [Telegram day/night chat patterns](https://telegram.org/blog/chat-themes-interactive-emoji-read-receipts) and [Mixin’s messenger/wallet layout](https://messenger.mixin.one/) as references. The current redesign uses neutral surfaces, compact navigation and plain backgrounds, with a green accent. System typography is the default and bundled Manrope remains an option. Appearance has no runtime asset downloads. See the [2026-10-21 UI/UX review](docs/rediseno-ui-ux-2026-10-21.md) for current implementation and device evidence; earlier verification records describe their historical builds.

The planned architecture separates use cases, encrypted local storage, and network connections. **Authentik (OIDC), ejabberd (XMPP), and PostgreSQL** will be evaluated for identity and messaging. Media and calls will require controlled storage, **WebRTC**, and **STUN/TURN** services. The local ejabberd server is now connected through the Android messaging client. Encrypted media now use the local ejabberd HTTP upload service; start the loopback-only relay described in the integration guide. Authentik/OIDC, PostgreSQL migration, production media retention and calls remain planned.

The [identity and package verification record](docs/verification-v0.1-identity-2026-10-07.json) records the mainnet debug build, unit and Android tests, language checks and two-emulator UI review for this redesign.

## Security and known limitations

Mainnet credentials are encrypted together with AES-256-GCM using a non-exportable Android Keystore key; preferences contain only the versioned authenticated ciphertext. Cloud backup and device transfer are excluded in debug and release. A missing or invalid local key shows recovery/retry without clearing credentials. Keystore protection does not add biometric authorization and does not protect secrets while a compromised app process is using them. Hardware-backed storage is device-dependent; recovery after uninstall/device loss requires the user's phrase. The legacy testnet file is not migrated or deleted automatically.

Transaction history is not yet persistent, and the status shown after a transfer does not verify confirmations. A lost connection after broadcast may leave an uncertain outcome; check the blockchain before trying again. The app combines WDK execution fees with a conservative Base oracle estimate for L1 publication and operator fees, verifies the chain ID, and checks fresh ETH balance before broadcast. A missing fee component stops preparation. This estimate still needs comparison against funded transaction receipts; it is not a signed fee cap or a guarantee against changing fees. Maximum spendable amounts also need network-specific validation.

The wallet and navigation unit tests cover token separation, wrong-chain rejection, fee failures, authenticated encryption, recovery failures, a failed deletion that preserves the wallet, entry without a wallet, financial navigation guards and nested return routes. Resource validation checks matching English, Spanish and Portuguese keys and format arguments. Messaging adds tests for failed encryption, duplicate history, account isolation, restore failures, receipt identity and blocked recipients; its opt-in Android integration test exercises the real local server. The onboarding APK was installed on two API 34 emulators: carousel navigation, messaging preview, English/Spanish/Portuguese text, light/dark themes and enlarged text were checked. Lint has no errors and four existing warnings; incompatible Compose custom lint checks were skipped. Current navigation, restart and language coverage is recorded in the [main navigation verification](docs/verification-v0.1-chats-2026-10-06.json). See the earlier [onboarding verification record](docs/verification-v0.1-onboarding-2026-10-06.json) for exact coverage, the final APK hash and the distinction between full-flow checks and the final arrow-contrast polish. Earlier signing, language changes and restart checks remain in the [localization verification record](docs/verification-v0.1-localization-2026-10-06.json). A device matrix, funded transfer tests, restoration under simulated failures, and a security review are still needed. Progress and exit criteria are tracked in the [roadmap](docs/roadmap-v0.1-v2.0.md).

## Digital-dollar account

The wallet presents one nominal USDC + bridged-USDT account on Base, with an optional breakdown and a shared receive address/QR. Payments require a short-lived review and fresh balance, network and fee checks. ETH, BTC and the swap-quote screen were removed on 2026-10-23; conversion, durable transaction history and payment tags remain pending.

Home provides Send, Deposit and Withdraw. The funding screen groups wallet QR transfers, local P2P transfers and fiat providers. The server pins P2P.me SDK 1.3.7 for public USDC/Base price and fee references across its 13 active country markets; unsigned order preparation, GraphQL routing/history and local WDK signing are implemented behind index/account readiness checks. Android needs a configured HTTPS backend origin for both P2P references and fiat checkout. Account onboarding/verification and funded settlement acceptance remain pending; orders use an independent encrypted local journal; see [coverage and activation steps](docs/p2p-depositos-retiros.md).

The [native P2P operation guide](docs/p2p-operaciones.md) separates implemented native order steps from outstanding live acceptance. See the [current protocol and recovery guide](docs/p2p-flujo-nativo.md). The official Base indexer is deployed as `midori-wallet` 0.0.1 in The Graph Studio, with [persistent sources and reproducible checks](tools/p2p-subgraph/README.md). The index now passes synchronization, CID and canonical-block checks, and the Worker reports order preparation ready. Android reads account-specific buy/sell limits and USDC balance before quoting, and reconciles known orders on re-entry. Unsigned SELL quotes and read-only simulations passed for a public fixture address in Colombia and Brazil. Placement-limit schema compatibility, production query access, account verification and first-purchase gas sponsorship remain pending. No real wallet was signed or funded settlement accepted; see [the latest evidence](docs/verification-v0.1-p2p-orders-ready-2026-10-09.json).

## License

Project-authored source: Apache 2.0 ([LICENSE](LICENSE)). The combined APK includes the GPLv3 Signal OMEMO dependency and the OFL-licensed Manrope font. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for distribution obligations.

### Personalización

Temas, iconos, apariencia por conversación, perfiles locales y preferencias de wallet: [opciones y alcance](docs/personalizacion.md).

## Buy and sell provider selection

The backend runs on Cloudflare Workers with SQLite-backed Durable Objects for expiring offers, challenges and one-use launch tickets. See [deployment and Android configuration](docs/cloudflare-workers.md). The public origin is configured in the local APK; P2P catalog/rate reads passed on Pixel 6. Transak remains disabled pending an explicitly configured egress binding with allowlisted source IPs and Production checkout acceptance.

Wallet → Buy/Sell uses neutral actions: View options → select an estimate → Continue. The backend compares configured providers by estimated received amount, showing quoted fees and payment methods. Transak through the official WDK fiat module is the only implemented production adapter; additional providers need their own integration. Partner credentials remain server-side. Live authentication, catalog and a quote succeeded, but widget creation returned `401 invalid_api_key`; no live checkout has been verified. The local APK has the Workers URL configured, while the deployed fiat adapter remains disabled. See [setup, Pixel 6 tests and remaining requirements](docs/transak-integration.md).

## Roadmap status

The [reorganized v0.1–v2.0 roadmap](docs/roadmap-v0.1-v2.0.md) marks completed `[X]`, partial `[-]` and missing `[]` work against the current code. The [project audit](docs/analisis-proyecto-2026-10-07.md) records historical implementation evidence and acceptance gaps. v0.1 remains open for both usable chat and funded wallet acceptance; implemented chat, groups and customization do not imply a later accepted release. Pixel 6 is the primary test device. This work does not change the APK version.
