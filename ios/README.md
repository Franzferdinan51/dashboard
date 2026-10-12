# Dashboard for iOS

Native SwiftUI port of the Android dashboard app — same bridge API, same
8 tabs (Overview / System / Darkbloom / Mining / Earnings / Actions /
Windows / Settings), same 5-second poll, same actions with the same
confirmation dialogs.

## Build

Requires Xcode + [XcodeGen](https://github.com/yonaskolb/XcodeGen)
(`brew install xcodegen`):

```sh
cd ios
xcodegen generate          # writes Dashboard.xcodeproj + Dashboard/Info.plist
xcodebuild -project Dashboard.xcodeproj -scheme Dashboard \
  -configuration Release -destination 'generic/platform=iOS' \
  CODE_SIGNING_ALLOWED=NO build
```

Package an unsigned IPA for sideloading (SideStore/AltStore sign it with
your own Apple ID at install time):

```sh
APP=$(find . -name Dashboard.app -type d | head -1)
mkdir Payload && cp -r "$APP" Payload && zip -r Dashboard.ipa Payload
```

## Notes

- Bundle ID `com.duckets.dashboard`, iOS 17+.
- Default bridge URL is the Mac's tailnet address
  (`http://100.68.208.113:8901`); change it in the Settings tab (the LAN
  address works at home). Info.plist allows cleartext HTTP for this —
  the bridge is the owner's own server on his own networks.
- Sideloaded with a free Apple ID the signature lasts 7 days;
  SideStore's background auto-refresh renews it automatically over WiFi.
