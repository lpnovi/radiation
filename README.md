# Radiation

A clean, highly customizable **4×1 media widget** for Android.

```
[ art ] Title — Artist            ⏮  ⏯  ⏭
```

- Works with Spotify and any other player that publishes an Android media session. No Spotify login needed.
- Settings are per widget: place several Radiation widgets with different looks.
- **Album** style (default): the play button and controls take their color from the current artwork, with contrast guaranteed.
- AMOLED and Material You styles, background opacity that never fades text or controls, toggleable art and buttons.
- Tap the widget to open the current player (or always Spotify, or nothing).

## Privacy

No ads, analytics, tracking, accounts or internet permission.
Radiation asks for **notification access** only because Android exposes other apps' media sessions
through it. Notifications are never read or stored. Playback buttons work without it.

On Android 13+, if you sideload the APK the access switch may be greyed out: open Radiation's
*App info → ⋮ → Allow restricted settings* first.

## Building

Requires JDK 17 and the Android SDK (platform 36).

```powershell
.\gradlew.bat assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. CI builds every push to `main`.

See [docs/platform-notes.md](docs/platform-notes.md) for what Android widgets can and can't do.

## License

[MIT](LICENSE)
