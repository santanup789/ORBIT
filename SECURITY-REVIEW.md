# ORBIT pre-publication security review

Date: 2026-10-23. Target: 0.6.1. Status: **focused pre-publication checks passed**. This is an engineering review, not an independent penetration test or certification; it does not establish freedom from all vulnerabilities.

## Verified

- Deployed Realtime Database rules match the local rules: root public reads/writes denied; location access requires authenticated ownership or the intended peer with fresh bilateral consent; plaintext extra fields and oversized ciphertext rejected.
- 24 synthetic local emulator rule assertions passed, including unauthenticated/root/third-party access denial, consent expiry/revocation, self-consent and unknown-path rejection.
- Firebase console reports Spark/no-cost plan. No billing upgrade was performed.
- Pairing signatures pin device identities. Automatic GPS upgrades require an existing trusted identity. Location encryption uses Android Keystore ECDH and AES-GCM with directional recipient/sender authenticated data.
- Location sharing stops on foreground exit, friend removal and session timeout. It is not enabled by pairing or a diagnostics check.

## Changes made

- Reject stale/future decrypted GPS samples before accepting them into peer state.
- Validate received device names and bound pairing packets before JSON processing.
- Explicitly disable cleartext network traffic.
- Exclude preferences, databases and files from cloud backup and device transfer, in addition to disabling backup.
- Add Play Integrity App Check provider initialization and fail-closed attestation in connectivity preflight. No debug-provider bypass token is embedded.
- Create a dedicated private release-signing key and non-debuggable release build configuration; version 0.6.1.
- With user approval, accept Google API terms and register the release certificate for App Check. Configure the documented outside-Play distribution settings: device integrity required; Play recognition/licensing not required.

## Final release verification

- Live release Play Integrity attestation passed on the connected device.
- App Check enforcement enabled for Realtime Database and, after explicit approval, Authentication. Live tests confirmed attested reads succeed and otherwise identical unattested reads are denied for both services. No physical coordinates were collected.
- Android client API key has Firebase's 25-API allowlist, including Authentication and App Check; it is not unrestricted and does not list the Gemini Developer API. No keys were rotated or deleted.
- Release build, release lint and 19 JVM tests passed. On-device cryptographic/pairing tests and notification tests passed using simulated pairing transport.
- Release certificate verified; non-debuggable manifest verified during packaging. Public source excludes client configuration, signing credentials, local test artifacts and tool caches.

## Residual risks and operational limits

- App Check reduces abuse but cannot prevent all abuse from legitimate devices/users. Anonymous accounts and client-controlled consent are not an operator-managed friendship registry.
- Static pairwise ECDH does not provide forward secrecy. The server sees account IDs and traffic metadata; map providers see requested tile areas.
- Location ciphertext may remain temporarily server-side after abrupt disconnection; consent expires and cleanup is attempted. Do not claim guaranteed immediate deletion or zero retention.
- A 15-minute session is a client behavior, not a server-enforced lifetime against modified clients. Rules limit fresh consent leases.
- No arbitrary background tracking. Coarse GPS/Bluetooth measurements must not be presented as precise ranging.
- A public source build is not automatically entitled to use the hosted test backend. Client Firebase config is not an admin secret; private signing keys, credentials, tokens and location artifacts must never be published.
- Release signing differs from the earlier debug APK. Android requires uninstall/reinstall for that transition, which loses local friends and keys. The user explicitly approved this only on the connected test phone.

## Local key custody

The release keystore and password are stored with restrictive permissions in the workspace's `work/private-release/` directory, outside the allowlisted publication repository. They must be backed up securely by the owner and never committed or attached to GitHub releases. Losing the key prevents compatible future release updates.

## References

- https://firebase.google.com/docs/projects/api-keys
- https://firebase.google.com/docs/app-check/android/play-integrity-provider
