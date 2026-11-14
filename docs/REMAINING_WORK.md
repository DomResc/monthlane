# Remaining release work

- The maintainer's physical-device acceptance test passed for performance build `1.0.0` (25). See the dated [validation record](RELEASE.md#validation-record-2026-11-14) for the exact artifact and automated checks; this is a debug-key test build.
- Automated tests cover pending-work recovery, interrupted-write protection, recurring tasks and stale actions. Complete any remaining physical-device process-termination, provider-failure and permission-change scenarios without assuming the general acceptance report covered each one.
- Exercise a signed release APK on physical devices, including a fresh installation and a subsequent same-key update. Verify multiple widgets, resizing, large text, accessibility, date and time-zone changes and launcher behavior.
- Keep the reviewed dependency inventory and third-party notices aligned with the final APK. Review and stage the complete public file list, including the README screenshot and notices; exclude local reference images, generated builds and workstation-specific files.
- Keep GitHub CI passing for the source commit selected for the public release; the first public source build has passed.
- Configure and securely back up a release signing key and the GitHub workflow secrets. Verify the workflow on a draft release and inspect the actual artifact before publication.
- Measure and document any remaining behavior limits without treating emulator results as proof of performance on physical devices.
