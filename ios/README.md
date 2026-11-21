# AmorX for iPhone and iPad

Native SwiftUI client, minimum iOS 15.0, arm64. Version 5.5.499.1327.
Uses the existing AmorX Supabase account and music backup contracts; no new SQL is required.

The UI follows Android's Expressive player: blue primary color, pill navigation,
separate profile button, Material Symbols and Roboto fonts, Android's actual Bun/Ghostish
cubic artwork outlines, and Circle → Cookie9Sided play-button morph with a 15-second rotation.
Animations respect Reduce Motion. System media picking, sharing and file selection use iOS dialogs.

Implemented source flows include email/password and email-code signup, account-isolated
profile/library state, music search and audio playback, background audio and lock-screen
controls, shuffle without repeats per cycle, downloads, the single AmorX playlist,
playlist sharing/import, compatible cloud music backup/restore with download consent,
user feed, photos/videos, follows/friends, likes/comments, messaging, uploaded story clips,
and story viewers. First device build and real iPhone integration/visual checks are pending;
this is not a claim of complete Android feature parity or a verified production release.

## Build

Open `AmorX.xcodeproj` in Xcode 16.4 or run:

```sh
python3 ios/Tools/generate_project.py
swift test --package-path ios
xcodebuild -project ios/AmorX.xcodeproj -scheme AmorX -configuration Release \
  -destination 'generic/platform=iOS' -derivedDataPath ios/DerivedData \
  CODE_SIGNING_ALLOWED=NO build
python3 ios/Tools/package_ipa.py
```

The GitHub `AmorX iOS IPA` workflow builds a device Release, verifies minimum OS,
arm64 architecture and version, packages an unsigned IPA, then compiles and launches
the simulator app and saves a startup screenshot. A passing launch check verifies startup;
it does not verify account login, the music service or all UI screens.

## Installation

The resulting `AmorX-iOS-5.5.499.1327.ipa` is **unsigned**. Import it into an appropriate
sideload installer and sign it for your own device. Installer support, signing expiry,
and device/OS restrictions belong to the chosen installation method. An IPA alone does
not make TrollStore compatible with every iOS version.

Keep the same signed bundle identity/team when updating to preserve the app's container
and Keychain session. Do not uninstall the old app before updating. Cloud playlist data
remains in the AmorX account independently of device installation.

No signing certificate, Apple account, service-role secret or user session is stored
in this repository. The Supabase key is the same public client key used by the other clients;
backend access remains governed by authentication and RLS.

## Source and dependencies

App source: GPL-3.0-or-later (repository LICENSE). YouTubeKit is pinned to 0.4.10.
See `THIRD_PARTY.md` and the bundled license notices. `Tools/ExportShapes.java` documents
how the shared Android geometry was exported; the generated Swift curves are checked in.
