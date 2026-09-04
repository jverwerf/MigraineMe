# Store release from the CLI

Both stores, no GUI. Keys: `~/keys/play-publisher.json` (Play SA), `~/dev/migraineme-ios/AuthKey_49GTV8CYDL.p8` (ASC, also copied to `~/.appstoreconnect/private_keys/`).

1. Bump `versionCode`/`versionName` in `app/build.gradle.kts` and `CURRENT_PROJECT_VERSION`/`MARKETING_VERSION` (6 each) in `migraineme-ios/MigraineMe.xcodeproj/project.pbxproj`. Commit both.
2. Write `release_notes_<ver>.json` (en-US, de-DE, es-ES, fr-FR, it-IT, nl-NL, pt-PT); the scripts alias pt-BR/es-419/es-MX/en-GB/it.
3. Android: `./gradlew bundleRelease` (signing from `local.properties` RELEASE_*), `jarsigner -verify`, then `python3 tools/release/play_release.py app/build/outputs/bundle/release/app-release.aab tools/release/release_notes_<ver>.json`.
4. iOS: `xcodebuild archive … -allowProvisioningUpdates -authenticationKey…` → `xcodebuild -exportArchive -exportOptionsPlist tools/release/ExportOptions.plist` → `xcrun altool --upload-app -f <ipa> -t ios --apiKey 49GTV8CYDL --apiIssuer c339f867-…` → `python3 tools/release/asc_release.py` (edit the version/build numbers at the top first).
5. If a permission, SDK or data flow changed since the last release, update Play Data safety and the iOS privacy label in the same release.
