# Stromkreis app onboarding (Android)

The app is a thin client for the Stromkreis Cloud (`https://hac.stromkreis.net`): after setup it
opens the member's openHAB Main UI through the cloud. There is no demo mode. Until the active server
has a cloud login (URL + username + password) the app shows the onboarding screen
(`OnboardingActivity`), which cannot be dismissed.

This mirrors the iOS app (`stromkreis-openhab-ios`, `docs/stromkreis-onboarding.md`); both apps accept
the same links and QR codes and talk to the same platform endpoint.

## Member flow

1. The Stromkreis admin creates the openHABian SD card for a new member (`/intern` → *Anlage anlegen*
   → *Image bauen*). On first boot the gateway provisions itself and the platform creates the
   member's cloud account (`battery_site.cloud_username` / `cloud_password`).
2. The member installs the Stromkreis app and receives a link to their Stromkreis page that shows a
   **one-time QR code**, or receives the **setup link** directly on the phone.
3. Either:
   - the member scans the QR code with the app (camera; or pastes the link), or
   - the member taps the link on the phone → Android opens the app (app link, or the
     `stromkreis://` button on the fallback page) → the app configures the cloud connection
     automatically.

## Link / QR payload accepted by the app

| Form | Example |
|---|---|
| App link (preferred; also the QR content) | `https://stromkreis.net/app/setup/<token>` or `https://stromkreis.net/app/setup?token=<token>` |
| Custom scheme fallback | `stromkreis://setup?token=<token>[&origin=https://stromkreis.net]` |
| Inline credentials (offline QR) | `stromkreis://setup?username=…&password=…[&cloudUrl=…][&siteName=…]` |
| Inline credentials (JSON QR) | `{"v":1,"username":"…","password":"…","cloudUrl":"https://hac.stromkreis.net","siteName":"…"}` |

Any `https` host is accepted for the `/app/setup/…` form (self-hosted platforms); the origin of the
link is used to redeem the token. Parsing lives in
`mobile/src/main/java/org/openhab/habdroid/core/StromkreisSetup.kt` (unit tests in
`mobile/src/test/java/org/openhab/habdroid/core/StromkreisSetupTest.kt`).

## Server contract (implemented in the `stromkreis` platform)

Token redemption - unauthenticated, the token is the credential:

```
POST /api/app/setup/v1
Content-Type: application/json

{"token": "<one-time token>"}
```

Success `200`:

```json
{
  "cloudUrl": "https://hac.stromkreis.net",   // optional, defaults to hac.stromkreis.net
  "username": "anlage-7@stromkreis.net",      // battery_site.cloud_username
  "password": "abcdefghi123",                 // decrypted battery_site.cloud_password
  "siteName": "Haus Mustermann"               // optional, becomes the server name in the app
}
```

Failure: any non-2xx; optional `{"error": "human readable reason"}` is shown verbatim. The app treats
401/403/404/410 without a message as "invalid or already used".

### Android App Links

For `https://stromkreis.net/app/setup/…` links to open the app directly (without the browser chooser),
the platform serves `https://stromkreis.net/.well-known/assetlinks.json` (route
`platform/src/routes/.well-known/assetlinks.json/+server.js`) as `application/json` without redirect:

```json
[{
  "relation": ["delegate_permission/common.handle_all_urls"],
  "target": {
    "namespace": "android_app",
    "package_name": "net.stromkreis.app",
    "sha256_cert_fingerprints": ["AA:BB:…"]
  }
}, { "...": "same for net.stromkreis.app.beta" }]
```

The fingerprints come from the platform's `ANDROID_APP_CERT_SHA256` environment variable
(comma-separated SHA-256 fingerprints of the signing certificates, e.g. from
`keytool -list -v -keystore release.jks` or the app-signing certificate in the Play Console). The
manifest declares the `https://stromkreis.net` / `https://www.stromkreis.net` `/app/setup` filter with
`android:autoVerify="true"`; verification only succeeds once the file is live with the right
fingerprints. `stromkreis://` links work immediately without server support, which is what the
"In der App öffnen" button on the fallback page uses.

## In-app

- Onboarding UI: `mobile/src/main/java/org/openhab/habdroid/ui/OnboardingActivity.kt` with
  `QrCodeAnalyzer.kt` (CameraX + ZXing, no Google services) and
  `mobile/src/main/res/layout/activity_onboarding.xml`.
- `MainActivity` starts the onboarding when the active server has no cloud login; the "server not
  found" screen offers *Einrichtungscode scannen*.
- Re-run setup later: Settings → *Stromkreis-Einrichtungscode scannen*.
- Credentials are written to the active server's remote connection (username/password in the
  encrypted preferences); the server is created if none exists, named after `siteName`. The start
  page is set to Main UI. The remote URL defaults to `https://hac.stromkreis.net` in the settings UI.

## Notifications

The app contains no Google services, so there is no push. The current data is fetched from the
Stromkreis Cloud whenever the app is opened; cloud notifications can optionally be polled
periodically (Settings → Benachrichtigungen, off by default).
