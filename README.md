# Matte Launcher

Matte is an Android launcher with resizable tiles, live folder previews, and a staggered tile turn when returning Home. It supports folder paging, drag to arrange tiles, optional notification counts, color themes, and JSON layout import and export.

## Build

Use JDK 17 or newer and install Android SDK Platform 35. Open this folder in Android Studio, or build from its root.

**Windows**

```powershell
.\gradlew.bat :app:assembleDebug
```

**Linux or macOS**

```sh
sh ./gradlew :app:assembleDebug
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. Android signs it with the machine's debug key. Its package ID is `com.matte.launcher.live.dev`, so it can install alongside the regular app. The release variant uses `com.matte.launcher.live`. To distribute a signed release, use a privately held signing key.

After installing, open Android's default app settings and select Matte as your Home app.

## Using Matte

- Long-press the Home screen to add tiles or folders, change motion or colors, and import or export your layout.
- **Add apps** and **add to folder** let you tick several apps at once. In All apps, long-press an app and choose *select multiple* to send a batch to Home or a folder.
- The All apps keyboard opens only when you tap the search field.
- **Colors → background color → device wallpaper** (default) shows your phone's wallpaper behind Home.
- **Custom tile style** (Home menu) lets you give a tile its own color or image. While it is on, tile move and resize are off; switch it off to rearrange tiles.
- The Home tile turn also plays when you unlock the device.
- Swipe between open folders. When dragging a tile, hold it near an edge to move to an adjacent folder.
- Select **Motion → Preview lock animation** to see the effect. On a real power-button press, visible animation depends on when Android turns off the display.
- Enable Notification access if you want badge counts. Matte counts active notifications for badges. Weather uses Open-Meteo with approximate location when available and permitted.

## Third-party files

The included Gradle wrapper is distributed under the Apache License 2.0. See [Gradle's license and notices](third-party/gradle). App tiles use artwork supplied by installed apps where available. This source package excludes the previous bundled third-party app logos.

## Credits

Based on [Matte Launcher](https://github.com/Steff-LEB/MatteLauncher) by Steff-LEB, used under the MIT License. The original `LICENSE` is kept unchanged.
