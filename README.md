<p align="center">
    <img alt="Logo" src="fastlane/metadata/android/en-US/images/icon.png" width="100">
    <br>
    <b>Stromkreis client for Android</b>
</p>

## Introduction

This is the native Android app for [Stromkreis](https://stromkreis.net), the open platform by energy communities
for energy communities. It connects members of an Austrian energy community (EEG) to their Stromkreis gateway at home
and, from anywhere, to the Stromkreis Cloud (`https://hac.stromkreis.net`).

The Stromkreis gateway runs on [openHAB](https://www.openhab.org); this app is a fork of the
[openHAB Android client](https://github.com/openhab/openhab-android) (EPL-2.0). Throughout the rest of this document
"openHAB server" refers to the openHAB instance on your Stromkreis gateway. The iOS counterpart lives at
[maigner/stromkreis-openhab-ios](https://github.com/maigner/stromkreis-openhab-ios).

The app is German-only (`de` is the default and only resource locale).

## Setup for members

There is no demo mode. Until the active server has a Stromkreis Cloud login the app shows the onboarding screen:
scan the one-time QR code from your Stromkreis page, paste the setup link, or simply open the
`https://stromkreis.net/app/setup/<token>` link on the phone. The setup can be repeated later via
*Settings → Stromkreis-Einrichtungscode scannen*. See [docs/stromkreis-onboarding.md](docs/stromkreis-onboarding.md)
for the accepted link formats and the server contract.

## Features
* Control your Stromkreis gateway locally and through the Stromkreis Cloud (an [openHAB Cloud](https://github.com/openhab/openhab-cloud) instance)
* Receive notifications through the Stromkreis Cloud connection
* Change items via NFC tags
* Send voice commands
* Send alarm clock time and other device information to the gateway
* Supports wall mounted tablets
* [Tasker](https://play.google.com/store/apps/details?id=net.dinglisch.android.taskerm) action plugin included

## Setting up development environment

The app is developed using Android Studio.

- Download and install [Android Studio](https://developer.android.com/studio)
- Check out the code from GitHub via Android Studio
- Install SDKs and Gradle if you get asked
- Click on "Build Variants" on the left side and change the build variant of the module "mobile" to "fullStableDebug".

Command line: `./gradlew :mobile:assembleFullStableDebug` and `./gradlew :mobile:testFullStableDebugUnitTest`
(JDK 17 and an Android SDK with platform 35 are required).

Before producing any amount of code please have a look at [contribution guidelines](CONTRIBUTING.md)

## Build flavors

An optional build flavor "foss" is available for distribution without Google services. This build has FCM and crash
reporting removed and will not be able to receive push notifications from the Stromkreis Cloud; notifications are
polled instead.

Application IDs: `net.stromkreis.app` (stable) and `net.stromkreis.app.beta` (beta). Push notifications require a
Firebase project that contains these application IDs; `mobile/google-services.json` has to be replaced accordingly.

For using map view support in the "full" build flavor, you need to visit the [Maps API page](https://developers.google.com/maps/android) and generate an API key via the 'Get a key' button at the top. Then add a line in the following format to the 'gradle.properties' file (either in the same directory as this readme file, or in $HOME/.gradle): `mapsApiKey=<key>`, replacing `<key>` with the API key you just obtained.

## Trademark Disclaimer

openHAB is a trademark of the openHAB Foundation e.V.; Stromkreis is not affiliated with or endorsed by it.
Product names, logos, brands and other trademarks referred to within this repository are the property of their
respective trademark holders. Google Play and the Google Play logo are trademarks of Google Inc.
