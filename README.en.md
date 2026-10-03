# Axonapp Android

Axonapp Android is an independent third-party AxonHub administration client, ported from [Axonapp-iOS](https://github.com/Likhixang/Axonapp-iOS) with closely aligned functionality and UX. It is not affiliated with or endorsed by AxonHub. It is built with Kotlin and custom Apple-style Jetpack Compose Foundation controls; it is not a WebView wrapper.

## Highlights

- Multiple admin-login and API-key instances with encrypted credentials, switching, editing, relogin, and removal.
- Real dashboard data and native management for gateway, keys, projects, users, roles, prompts, storage, system settings, and every imported safe schema operation.
- Channel/model create, edit, status, delete, connectivity test, upstream model discovery/sync, OAuth provider flows, and recursive routing forms.
- Paginated request, trace, thread, and usage inspection with secret redaction.
- Streaming playground support for the admin protocol, OpenAI Chat Completions, OpenAI Responses, Anthropic Messages, and Gemini.
- JSON backup export and validated GraphQL multipart restore.
- Responsive phone/tablet navigation, theme, accent, and locale settings.

See [docs/PARITY.md](docs/PARITY.md) for the detailed mapping and [SECURITY.md](SECURITY.md) for the threat model.

## Build and install

Delivery builds run exclusively in GitHub Actions (unit tests, lint, APK assembly and signature/alignment checks), without an emulator. Download the `axonapp-debug-apk` artifact after a successful run. Stable debug signing is restored from the `ANDROID_DEBUG_KEYSTORE_BASE64` GitHub Secret. The commands below document the Actions build; no local build or emulator execution is required.

Install JDK 17, Android SDK Platform 35, and Build Tools 35.x, then run:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The repository includes the genuine Gradle 8.11.1 wrapper. ARM64 Linux hosts may need binfmt/QEMU for the Android SDK's x86_64 AAPT2 binary.

No production signing key is embedded. Release signing is enabled only when all `AXONHUB_SIGNING_*` environment variables documented in the Chinese README are supplied.

## Provenance

The port tracks iOS reference revision `42e262e3448aa63ca7c18aec12ad65c27d62c11f`. Imported contracts and asset provenance are recorded under `app/src/main/assets/`; third-party icon licenses are preserved there. No new repository-wide license is asserted.
