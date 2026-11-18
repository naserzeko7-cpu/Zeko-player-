# Zeko WiFi Lab

An Android offline learning app for generating a limited set of sample password patterns and exploring password complexity.

## Safety and scope
- Generates candidates locally on the device.
- Does not scan, connect to, authenticate with, or send guesses to Wi-Fi networks.
- Intended for education and password-strength experimentation only.

## Build
Requires JDK 17 and Gradle 8.7:
`gradle :app:assembleDebug`

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.
