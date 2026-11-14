# Release checklist

This checklist does not certify the release. Review the exact source and assets before committing; keep private data, test fixtures and signing credentials out of Git. The local development reference image is not part of the distribution.

## Validation

- Run unit tests, Android lint and instrumentation on a dedicated emulator. Record device and provider versions, skipped tests and results.
- Test the signed release on a physical device: fresh installation, permissions, provider connection, task completion and cancellation, recurring tasks, widget resize, themes and date changes. Check multiple widgets, larger text and accessibility.
- Review the README, third-party dependencies and licenses, release notes and the exact files to publish.
- Increment `versionCode` for each distributed Android release and align `versionName` with the release tag.

## Validation record: 2026-11-14

- The maintainer reported that the physical-device acceptance test passed for the debug-key `performance` APK, version `1.0.0`, `versionCode` 25, SHA-256 `1a147a2500e08ef92b1771e9b839ac187795a7be86397bb8689f8c6528f87cbe`. Device model, Android version and individual test cases were not recorded. This result does not certify a future release-signed APK.
- In the development container, 16 unit tests and the 9 targeted weather/widget instrumentation tests passed on the dedicated Android 16/API 36 emulator, including 744 widget layout combinations. The targeted run followed removal of attribution from the widget; source credits and links remain in the app. An earlier 30-test instrumentation run passed before that layout change. Providers were Tasks.org 15.12 and Breezy Weather 6.2.2.
- Debug and performance builds and their lint checks passed. The performance APK signature, signing-certificate continuity, alignment and exported copy checksum were verified. The explicit cloud-backup and device-transfer exclusions were also checked in a compiled APK; a real transfer between devices was not exercised.
- The runtime dependency metadata contains 126 resolved coordinates, including platform/metadata modules. Published POMs declare Apache 2.0 for 124 entries and BSD-3-Clause for one; the remaining entry, Guava ListenableFuture 1.0, identifies Apache 2.0 in its published source archive. No standalone NOTICE files were found in the reviewed binary archives. The repackaged Protobuf runtime identifies itself as 4.28.2; its complete upstream copyright and BSD license are retained in [the third-party notices](../app/src/main/assets/THIRD_PARTY_NOTICES.txt), alongside the dependency inventory and Apache 2.0 license.
- After acceptance of APK 25, the notices were added as an asset and the documentation was updated. A subsequent unsigned release build succeeded, including release vital lint. Its packaged notice file matches the source exactly and covers all 126 resolved release coordinates. This packaging check does not replace physical-device acceptance of the final release-signed APK.
- Public GitHub [Android CI run 43618445956](https://github.com/DomResc/monthlane/actions/runs/43618445956) passed for source commit `e192449750be960383a0a096890cd4f34e0e48cc` on Ubuntu 24.04 with JDK 21. It built debug and release APKs, ran unit tests and debug lint, and compiled the instrumentation APK. It did not execute instrumentation tests on GitHub. The SDK bootstrap now uses the explicit SDK manager path in both workflows after the first run failed to find the command through `PATH`.

The public-file review included the working-tree fixes, README screenshot and third-party notices. The application sources have been committed and pushed.

- Release candidate `1.0.0` (26) was built and signed locally with a dedicated RSA-4096 key. Release lint and all 16 unit tests passed. APK SHA-256: `b8e112fbc63ffa8dd5f30d7b2702572fe9af7d0ebe7b3dd4e4ae4b1be7c70090`. Signing certificate SHA-256: `43da9e18bacaad9930ce9fed45ba4d3b13849f801c0d9e8595d17a143356deff`. Signature verification, 16 KiB alignment, package/version identity, non-debuggable status and the packaged third-party notices were checked. The shared test copy has the same checksum. Physical-device acceptance of this exact artifact is pending.
- The encrypted keystore is held separately from the repository; its password is held in Bitwarden. A local recovery copy was reopened and its exported certificate matched the expected fingerprint. Remote backup synchronization has not been verified. The temporary system-keyring password copy was removed after signing.
- On the dedicated Android 16/API 36 emulator, the signed candidate installed successfully after removing the debug-key test app, and its settings activity launched successfully. Reinstalling the same APK with the same key also succeeded. This is an installation smoke check, not a forward-version update or a full functional acceptance test. Release lint reported 0 errors and 54 warnings.

## Signing and GitHub release

Keep the dedicated Android release keystore outside the repository. Store the keystore and passwords securely, with a tested recovery procedure. Losing the signing key prevents normal updates; never commit or send it through the repository.

For a locally signed release, build the reviewed source, align the unsigned APK before signing, then verify the signature against the expected certificate, package/version identity, alignment and checksum. Test that exact APK on a physical device and verify recoverability of the signing key before publication. Attach the signed APK and its SHA-256 file to a draft GitHub release for the matching source tag; inspect the assets before publishing. This route does not require uploading signing credentials to GitHub.

The optional GitHub signing workflow has not been configured or exercised. Before using it, explicitly arrange custody of signing credentials on GitHub and configure these repository secrets:

- `ANDROID_KEYSTORE_BASE64`: base64 encoding of the complete keystore.
- `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`: its credentials.

Set repository variable `ANDROID_CERT_SHA256` to the expected signing certificate SHA-256 fingerprint, as 64 hexadecimal characters without colons. Obtain and verify it independently from the protected keystore. Restrict who can change release workflows, tags, secrets and this variable.

After the release commit and matching `vMAJOR.MINOR.PATCH` tag have been reviewed and pushed, create a **draft** GitHub release for the tag. Manually start `Prepare release APK` on that tag. The workflow checks that the release is a draft and its tag matches the APK version, builds and runs unit tests and lint, signs with the configured key, verifies the signature and expected certificate, and uploads the APK and SHA-256 file to the draft. It does not publish the draft or overwrite existing assets. Inspect the workflow result and independently test the exact signed APK before publishing the release. The GitHub-hosted run must be verified on the repository; local checks cannot prove that its permissions and secrets are configured correctly.

The existing test APKs use a debug certificate. A release signed with a different certificate cannot update them; uninstalling the test app removes its local settings and cached data because backup is disabled. Make the transition explicit to testers.
