# ORBIT

Native Android friend-finding prototype built with Kotlin and Jetpack Compose. Pair with a friend's consent, find them nearby using Bluetooth signal strength, or share encrypted locations over the internet for an outdoor map and direction guide.

## Download

**[Download the latest Android APK](https://github.com/santanup789/ORBIT/releases/latest/download/ORBIT-latest.apk)**

The current build is **0.6.1**, a release-signed, non-debuggable **prototype**, not a Play Store release or a certified safety product. Install the same version on both phones. Android 8+ is required for the app; encrypted GPS sharing requires Android 12+ and a device that passes Play Integrity device-integrity checks. Android may ask you to allow installation from your browser. Only install from a source you trust.

**Upgrading from a debug APK:** this release uses a different signing certificate. Android requires uninstalling the old debug build first, which erases your profile, paired friends and keys. Pair again afterward. Do not uninstall unless you accept that loss. Later releases signed with this release key can update in place.

The downloadable APK uses the ORBIT test Firebase backend with App Check enforced for Authentication and Realtime Database. Older debug builds cannot use that online backend. This is a prototype service with no availability guarantee. The source repository excludes its Android Firebase configuration and all signing keys. See [security review](SECURITY-REVIEW.md).

## What it does

- Shows unpaired ORBIT devices under **Nearby devices**, with **Connect** and an incoming **Accept / Decline** request.
- Keeps paired contacts under **Your Friends**, without duplicating them in Nearby.
- Uses Bluetooth proximity when offline. Arbitrary Bluetooth devices without ORBIT are not discoverable friends.
- Shares encrypted coordinates through Firebase when both friends explicitly start a GPS session.
- Displays an OpenStreetMap map, estimated straight-line distance, accuracy information and direction when measurements support it.
- Supports device-name customization, optional QR pairing, haptic guidance and diagnostics.
- Checks online connectivity when GPS starts and offers simple errors with Retry.

## Pair once, then find

1. Install ORBIT on both phones. Complete the profile and grant the requested Nearby Devices permissions. Turn Bluetooth on.
2. Keep ORBIT open on both phones. Under Nearby devices, tap **Connect**; the other person taps **Accept**.
3. Your friend appears under **Your Friends**. No repeated QR scan is required to open GPS or the map.
4. For outdoor GPS, both phones need internet access, location permission and location services. Both people must start sharing using the consent controls.
5. After the secure GPS link has been established, sharing can work outside Bluetooth range. Stop sharing when finished.

### Already paired friends

Existing friendships that have not exchanged GPS identity information are upgraded automatically. Update **both phones**, keep ORBIT open, nearby and online once. The upgrade uses the existing trusted identity; it does not request another QR scan or pairing acceptance. An offline-created friendship needs the same one-time online setup. If the other phone is absent or not updated, setup remains pending; an unknown remote identity cannot be reconstructed from an old local pairing alone.

## Important limits

- **Bluetooth RSSI is proximity, not a reliable meter reading or direction sensor.** Walls, bodies and radio conditions affect it.
- GPS distance is straight-line distance, not walking directions. Indoor/coarse fixes can be inaccurate; the UI must not imply precision beyond reported accuracy.
- Direction depends on valid location and heading measurements. These phones do not gain UWB or Wi-Fi RTT capabilities through software. Precision UWB ranging is not implemented.
- GPS sessions are foreground-only and time-limited to 15 minutes. This is not an always-on background tracking service.
- Pairing notifications are local Android notifications, not Firebase Cloud Messaging push notifications.
- Internet is required for Firebase sharing and uncached map tiles. Offline Bluetooth remains limited by local radio range.
- This is a prototype, not an emergency, safety-critical or production-audited locator.

## Build from source

Requirements: JDK 17, Android SDK 36, Android SDK platform tools, and network access to the Gradle/Google/Maven dependency repositories. The Gradle wrapper uses 8.11.1.

```bash
git clone https://github.com/santanup789/ORBIT.git
cd ORBIT
chmod +x gradlew
# Configure ANDROID_HOME, or set sdk.dir in an untracked local.properties.
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

Without `app/google-services.json`, the app still builds for Bluetooth use; online GPS reports missing configuration. The source build does not silently connect to the test backend.

### Configure your own Firebase backend

1. Create a Firebase project and register Android package `app.orbit.finder`.
2. Enable **Authentication → Anonymous** sign-in and create a Realtime Database.
3. Download your Android `google-services.json` into `app/`. It is intentionally ignored by Git.
4. In `app/src/main/java/app/orbit/finder/online/FirebaseConnection.kt`, replace the expected project ID (`orbit-945f6`) and `DATABASE_URL` with your own project and database URL.
5. Review and deploy `firebase/database.rules.json` to that database. Do not enable public test-mode access.
6. Register your signing certificate with Firebase App Check's Play Integrity provider. For outside-Play distribution, require device integrity without requiring Play recognition/licensing. Follow Google's current setup instructions, verify legitimate tokens, then enforce App Check for Authentication and Realtime Database.
7. Rebuild and install the same configured build on both phones. A source/debug build is not automatically authorized to use ORBIT's hosted backend. Connectivity checks fail closed if attestation fails; do not embed debug bypass tokens in distributed APKs.

The Firebase Android client configuration is not an administrator credential, but it is excluded here to keep deployments explicit. Never commit service-account keys, signing keys or authentication tokens. Review provider quotas, abuse controls and billing before distributing your own build widely.

## Privacy and security model

Pairing pins a device's signing identity. GPS metadata is exchanged in signed messages, and automatic upgrades are limited to already trusted identities. Coordinates are encrypted on-device before relay storage. Database rules deny public access and require bilateral, fresh consent for location access. Sharing does not start merely because setup or connectivity checks run.

The relay still sees authentication identifiers and traffic metadata. Map tile requests reveal the viewed map area to the tile provider, and cached map tiles can retain that area locally. App Check and rules reduce abuse but are not a substitute for independent security review, rate limiting and operational monitoring. Static pairwise ECDH does not provide forward secrecy. Losing app data/keys requires re-pairing. See [security review](SECURITY-REVIEW.md) for residual risks.

### Build a signed release

Create and securely back up your own keystore with alias `orbit-release`; never commit it. Set `ORBIT_KEYSTORE_PATH` and `ORBIT_KEYSTORE_PASSWORD` in your local environment, then run `./gradlew assembleRelease`. Without these environment variables the release is unsigned. The project owner's signing key is deliberately not in this repository. For release instrumentation, also pass `-PorbitTestBuildType=release` and build `assembleReleaseAndroidTest`.

## Tests and verification

```bash
./gradlew testDebugUnitTest lintDebug assembleDebugAndroidTest
# Install app and test APKs on an authorized test phone, then run:
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w app.orbit.finder.test/app.orbit.finder.PairingInstrumentation
```

For 0.6.1, 19 JVM tests, release lint and APK builds passed. On one physical Android phone, instrumentation verified real Android Keystore signatures, ECDH/AES-GCM with tamper and wrong-recipient rejection, signed GPS metadata exchange, trusted automatic upgrades, rejection of untrusted upgrades, framing, acceptance/decline and notification delivery. **Pairing transport in those protocol tests was simulated**, so this does not establish two-phone radio behavior.

Live release tests verified Play Integrity attestation, successful attested Authentication/Database reads and rejection of identical unattested reads. No physical coordinates were collected. The local database emulator passed 24 rule assertions. The `appCheckChecks` instrumentation option performs read-only live checks; review it before running it against your own configured backend.

The current two-phone automatic-upgrade/map test and outdoor distance/heading field test remain pending. Earlier versions received two-phone testing, but that does not replace verification of this version. Some instrumentation classes exercise live services; review them before running.

Firebase rules can be tested with synthetic data against the local emulator (Node.js and a compatible Java runtime required):

```bash
npm ci --prefix tests/firebase
tests/firebase/node_modules/.bin/firebase emulators:exec \
  --only database --project demo-orbit-gps \
  'node tests/firebase/rules.test.mjs'
```

## Project layout

```text
app/src/main/java/app/orbit/finder/
  core/       Identity, friends and measurement models
  radio/      BLE discovery, signed pairing and message framing
  online/     Firebase relay, encryption, GPS sensors and map UI
  qr/         Optional QR pairing
  haptics/    Feedback
  ranging/    Ranging interfaces and proximity behavior
app/src/test/          JVM tests
app/src/androidTest/   Device instrumentation
firebase/             Restricted Realtime Database rules
tests/firebase/       Local emulator rule tests
```

Map rendering uses osmdroid and OpenStreetMap data. Review the relevant library licenses and tile-service policies before production distribution. No open-source license is granted by this repository merely being public; select an appropriate license before inviting redistribution.
