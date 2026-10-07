# Radiation

A clean, highly customizable **4×1 media widget** for Android.

```
[ art ] Title — Artist            ⏮  ⏯  ⏭
```

- Works with Spotify and any other player that publishes an Android media session. No Spotify login needed.
- Settings are per widget: place several Radiation widgets with different looks, each following the active player or bound to a specific app.
- Controls take their color from the current artwork (rich or pastel), Material You, or mono, with contrast guaranteed. Four icon sets: Rounded, Sharp, Line, Bold.
- Backgrounds: album tint (default), album glow, AMOLED, Material You, glass, or a custom color; opacity that never fades text or controls; optional hairline border.
- Optional subtle bars or wave along the bottom while music plays, drawn by the launcher itself (decorative; see the platform notes on why not audio-reactive).
- Optional corner shuffle button for players that support it (Spotify included).
- Optional per-widget Idle mode: when nothing plays, show Resume, Quick Launch, a Clock or a Minimal view.
- Uses your phone's system font, with adjustable title/artist size, weight and 1-2 title lines. Toggle art (rounded or circle), artist, previous, next, shuffle. Tap opens the current player (or always Spotify, or nothing).

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
