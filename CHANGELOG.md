# ORBIT 0.6.1

Release-signed Android prototype with consent-based nearby pairing and encrypted outdoor GPS sharing.

## Download and install

Download `ORBIT-latest.apk` from this release. Android 8+ for the app; Android 12+ and a device that passes Play Integrity device-integrity checks for online GPS. Install this version on both phones.

**Previous debug installs must be uninstalled first because the signing certificate changed. Uninstalling erases your profile and pairings.** Re-pair afterward. Future releases using this release key can update in place. Old debug builds no longer have online backend access because App Check is enforced.

## Changes

- Pair once with Connect and Accept; no repeated QR scan for GPS/map. Existing compatible pairings exchange GPS metadata automatically when both updated phones are nearby, open and online once.
- Play Integrity App Check integration and fail-closed GPS connectivity preflight.
- App Check enforced for Authentication and Realtime Database.
- Hardened pairing-input validation, decrypted GPS freshness checks, backup exclusions and cleartext-network policy.
- Dedicated private release signing; public APK is not debuggable.

## Verification

- Release build, release lint and 19 JVM tests passed.
- 24 synthetic Firebase security-rule assertions passed locally.
- Real-device cryptographic/protocol and notification tests passed; pairing transport was simulated.
- Live release attestation succeeded; attested reads allowed and unattested reads denied for both Authentication and Database. No physical locations collected.

Two-phone automatic-upgrade/map and outdoor accuracy/direction field tests remain pending. This is not a production-audited, emergency or safety-critical locator. Firebase is a test service with no availability guarantee. See the repository README and security review for setup and limits.

Release signing certificate SHA-256:
`6A:2F:10:C1:63:4B:4A:DF:C3:0C:A6:7F:CD:12:68:E8:E8:D2:61:96:8D:36:8F:38:A6:02:C9:6F:CA:65:00:68`
