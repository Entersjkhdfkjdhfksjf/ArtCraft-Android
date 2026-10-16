# Artcraft Launcher (Android)

An Android app that launches ArtCraft apps **inside itself**. SoundCraft, DeckCraft and WordCraft (0.5.0) are
built in. More ArtCraft apps (or any compatible web build) can be installed from the **Get more** tab.

## Build

Requirements: Android Studio (Ladybug or newer) or JDK 17 + Android SDK 35.

    ./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
    adb install -r app/build/outputs/apk/debug/app-debug.apk

Or just open this folder in Android Studio and press Run. The project has **no third-party dependencies**.
minSdk 26 (Android 8), targetSdk 35. Needs a reasonably current Android System WebView (Chrome 110+).

## How it works

* The ArtCraft apps are Rust compiled to WebAssembly and drawn onto a `<canvas>`. Android has no wasm runtime
  of its own, so each app runs in a WebView; the launcher is the host.
* `AppServer` answers every request from the WebViews itself (no web server, works offline):
  `https://<id>.appassets.androidplatform.net/` -> the app's files. This gives correct `application/wasm`,
  an https secure context, and **separate storage per app**.
* One WebView per opened app is kept alive, so Back/“Launcher” returns to the grid without losing work.
  Running apps show a green dot and can be closed from the grid.
* Apps are sandboxed offline: requests to any other host are blocked; links open in your browser.
* Downloads from apps (Export/Save as…) are routed to Android's **Save as** dialog (`assets/shim/save-shim.js`),
  file inputs (Open…) use the system picker.
* If the system kills the WebView renderer (low memory), the affected app is closed with a message instead of crashing the launcher.

## Installing apps

Get more -> **Install / Update** downloads `<name>-web-<version>.zip` from the latest GitHub release of the
repo (`storytold/<name>`), unpacks it into app storage and replaces the previous copy. Also available:
* a GitHub repo (`owner/name`) or a direct https link to a web-build zip,
* **Choose a .zip file…** for a zip you already have.

Installed copies override built-in ones; “Revert to built-in” removes the override. Updating an app that is
currently open is refused (close it first).

A web build is any zip with an `index.html` (optionally inside one top-level folder), named
`<id>-web-<version>.zip` so the id/version can be detected.

## Bundling more apps into the APK

    python3 tools/bundle_app.py ~/Downloads/photocraft-web-0.8.2.zip

then rebuild. To add an entry to the store list edit `app/src/main/assets/shell/catalog.json`.

## Layout

    app/src/main/java/app/artcraft/launcher/
      MainActivity.kt   WebView host, sessions, back/fullscreen, file chooser, Save-as, mic/camera permission
      AppServer.kt      request interception: shell, bundled + installed apps, shim injection
      AppRegistry.kt    bundled (assets/apps) + installed (filesDir/apps) app discovery
      Installer.kt      release lookup, download, safe unzip, atomic replace
    app/src/main/assets/shell/    launcher UI (HTML/CSS/JS) + catalog.json
    app/src/main/assets/shim/     in-page download shim
    app/src/main/assets/apps/     bundled apps

## Known limits

* Each app compiles a ~12-35 MB wasm module on every open; first open takes a few seconds.
* GPU path: apps use WebGPU if the device's WebView exposes it, otherwise WebGL2.
* Soft keyboard on canvas text fields depends on the app focusing a text element; a hardware keyboard always works.
* Apps' own clipboard access depends on WebView permission handling; copy/paste inside an app works, paste from other apps may not.
* Audio stops when an app goes to the background (the WebView is paused).

Bundled apps keep their own licenses (see each `assets/apps/<id>/LICENSE-*`, `NOTICE`, `OFL-*`).
