# Unit

Unit is a lightweight Android browser built on the system WebView, supporting Android 9 and newer.

It offers features such as incognito mode, proxy support, reader mode, QR scanning, and built-in page debugging tools for running JavaScript snippets.

## Build

Building the app requires JDK 17 and Android SDK Platform 36. Open the repository root in Android Studio or build from the command line.

For command-line builds, set `ANDROID_HOME` to your SDK path or specify the path in `local.properties`:

```properties
sdk.dir=/your/path/to/android-sdk
```

Build and check with the Gradle Wrapper (`gradlew.bat` on Windows):

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:lintDebug :app:lintRelease
```

APKs are written to `app/build/outputs/apk/`. Sign release APKs before distribution.

## Tests

Running the tests requires Python 3.9 or newer and Android SDK `platform-tools` on `PATH`. Connect a dedicated device or emulator that runs Android 9 or newer and allows `run-as` to access the Debug app's data:

```sh
python3 tests/run.py
```

The script builds and installs the Debug app and test APK, runs tests, and restores the Debug profile afterward.

## Third-party components

Android dependencies are listed in [app/build.gradle.kts](app/build.gradle.kts). Reader mode bundles Mozilla Readability and DOMPurify with their license notices: [Readability](app/src/main/assets/reader/readability-LICENSE), [DOMPurify](app/src/main/assets/reader/dompurify-LICENSE).
