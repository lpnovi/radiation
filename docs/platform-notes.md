# Platform notes

What Android's AppWidget / RemoteViews model allows, and what Radiation does about it.

## Media

| Topic | Reality | Radiation |
|---|---|---|
| Reading the active player | `MediaSessionManager.getActiveSessions()` requires an enabled `NotificationListenerService`. | Asks for notification access with an in-app explanation. Notifications themselves are ignored. |
| Controls without access | `AudioManager.dispatchMediaKeyEvent` reaches the last active player with no permission. | Used as the fallback. Metadata can't be shown without access. |
| Sideloaded installs (Android 13+) | Notification access is a "restricted setting" for apps not installed from a store. | The studio explains *App info → ⋮ → Allow restricted settings*. |
| Artwork timing | Players (notably Spotify) publish a new track's text first and its bitmap in a later update, and sometimes republish a track without its bitmap. | `NowPlayingTracker`: keeps a track's art across bitmap-less republishes; on a new track without art, holds the previous visuals up to 1.2s so text, art and colors switch together; falls back only after that, with one recheck at 3s. |
| Artwork sources | `METADATA_KEY_ALBUM_ART`, `ART`, `DISPLAY_ICON` bitmaps, or `*_URI` keys. | All six are checked in that order. URIs are read only if local (`content://`, `android.resource://`, `file://`); Radiation has no INTERNET permission, so `https://` art URIs are skipped. |
| Binder size | RemoteViews bitmaps travel over Binder (~1 MB transaction limit). | Art is center-cropped to ≤256 px once per track, then rounded at the exact display size (~190 px). |

## Rendering

| Topic | Reality | Radiation |
|---|---|---|
| Reapply | Hosts may apply an update onto the *previous* view tree, so any property not set again persists. | Every dynamic property is set on every render; artwork and the fallback tile are separate views so tints can't leak. |
| Sizing | Views can only be resized at runtime on Android 12+ (`setViewLayoutWidth/Height`). | 12+: art, disc and buttons are sized from the real cell height. Older: fixed XML sizes that fit one row. |
| Background opacity | `setImageAlpha` on one ImageView is precise; alpha on a parent would fade everything. | Opacity applies to the surface ImageView only. |
| Contrast under translucency | Android exposes the wallpaper's overall colors (`WallpaperColors`), not the pixels behind the widget. | Foreground colors are computed against the surface blended over the wallpaper's primary color. Below 60% opacity, secondary text uses full strength because local wallpaper detail is unknown. |
| Corner radius | `system_app_widget_background_radius` exists on 12+. | Used on 12+ so Radiation matches One UI/Pixel widgets; 28dp before. |
| Marquee | Needs focus/selection that RemoteViews can't reliably give. | Ellipsize at end. |

## Interaction

| Topic | Reality | Radiation |
|---|---|---|
| Press animation | RemoteViews can't run view animations (no scale/translate/animator). Drawable **state** changes do work, including `StateListDrawable` enter/exit fades; ripples run in the host. | Bare glyphs (no resting background). On press: a circular ripple in a color matched to the background, and the glyph shrinks via a pressed-state inset, easing back over 160ms on release. Play/pause is ~1.4x the size of prev/next. |
| Immediate response | A tap reaches the app as a broadcast; the player's new state comes back later. | Play/pause and shuffle render the expected state immediately from the last snapshot (glyph, visualizer, shuffle dot); the player's real state then confirms or corrects it. |
| Haptics | Widgets can't request haptics. Vibrating from the click broadcast needs `VIBRATE` and lands after the broadcast round-trip; most launchers already give their own tap haptic. | Not implemented: it would feel late. |
| Live progress | A looping drawable can't know the playback position; showing real progress means pushing a widget update per step (IPC + battery). | Deferred. |
| Now-playing animation | Hosts start `Animatable` indeterminate drawables of a `ProgressBar` and stretch an `AnimationDrawable` to the view's bounds, so a looping full-width strip runs inside the launcher with no app work or widget updates. Tinting it from RemoteViews needs Android 12. | Off / Bars (default) / Wave along the bottom. Bars: 44 bars, each with its own phase and an integer speed, so they move independently yet loop seamlessly. Wave: three gentle crests drifting with a slow swell. Both 16 frames at 12.5 fps, one ProgressBar per style. Accent-tinted at ~72%, faded at both ends, inset from the corners. `GONE` unless playing; the launcher stops drawing it off-screen. A still frame with "Remove animations" and below Android 12. |
| Audio-reactive visualizer | The only way for a non-player app to read audio is `android.media.audiofx.Visualizer` on the global output mix: it needs `RECORD_AUDIO` (a microphone-class permission prompt), that capture mode is deprecated, and Spotify's own audio session isn't accessible. Even with data, a widget can only change through cross-process updates, so following the music at 15-30 fps means that many full widget updates per second: battery-heavy and throttled by launchers. | Not implemented. The bars are decorative and say so in the Studio. |

## Backgrounds and shuffle

| Topic | Reality | Radiation |
|---|---|---|
| Blur ("glass") | Widgets can't blur what's behind them; RemoteViews has no access to blur/RenderEffect and the wallpaper's pixels are private. | Glass is a light haze of the wallpaper's own color, translucent (40% when picked from opaque), with a hairline edge. |
| Gradients | Dark, shallow gradients span only a few 8-bit levels, so they band; multi-stop gradients show a "knee" at each stop; a gradient layered over the surface antialiases the rounded edge twice. | Album glow is one bitmap (1px per dp, ~140 KB): a single Gaussian falloff from behind the art, dithered with triangular noise of about one level, clipped to the widget's rounded outline (`clipToOutline`). Contrast is checked against both ends. |
| Border | Tinting a stroke-only shape with a color filter also paints its interior, washing the whole surface. | Two pre-colored 0.6dp hairlines (light/dark), only their opacity set at runtime: ~11% over an opaque surface, ~22% when translucent (where it is the only edge). |
| Shuffle | The framework media API has no shuffle. It exists in the AndroidX media-compat session protocol (MediaSessionCompat / Media3 players, including Spotify), readable only after an async handshake with the session. | Optional button in the top-right corner, outside the control row, centered over the "next" column. The visible glyph stays 15-20dp; the tap target is 56dp wide and fills the free space above "next" (28-40dp tall), stopping just above the next glyph so taps on it still skip. On = accent plus a dot, off = muted. See *Shuffle strategies* below. No shuffle without notification access (there's no media key for it). Uses `androidx.media` 1.7 (1.8 deprecates it in favor of Media3, which would add Guava and several MB). |

## Shuffle strategies

Players expose shuffle in different ways, so `Shuffle.choose` (pure, tested) picks one per session from what that session advertises. Capability and state are kept apart.

| Order | Session exposes | Command | State shown |
|---|---|---|---|
| 1 | `ACTION_SET_SHUFFLE_MODE` in its playback actions | compat `setShuffleMode` (works before the compat handshake) | Known on/off once the compat mode is readable; otherwise *unknown* (`getShuffleMode() == INVALID` is unknown, not unsupported) |
| 2 | A playback custom action whose id or name says shuffle | `sendCustomAction` with that session's own action id | Unknown |
| 3 | A shuffle button on the player's own media notification (`Notification.actions` of the notification naming that session) | the button's `PendingIntent`, exactly what tapping it in the shade does | Unknown |
| 4 | A readable compat mode, without advertising the action (e.g. Spotify) | compat `setShuffleMode` | Known |
| 5 | Only the deprecated `ACTION_SET_SHUFFLE_MODE_ENABLED` | the support library's legacy custom action | Unknown |
| 6 | Nothing | none | Unsupported: the button is hidden |

"Says shuffle" is a substring match on a list of words in about 20 languages, since labels follow the phone's language. Every `MediaSessionCompat` reports NONE by default even without shuffle, so a standard command whose mode is readable but hasn't changed 2s later counts as ignored, and the session falls through to the next route (until it reports a change).

Visibility (`Shuffle.visible`, tested): the button shows when the user's *Show shuffle* is on and the current player offers shuffle; with a player that doesn't, it is hidden for as long as that player is active and returns with one that does. The saved setting is never changed. Because the corner shuffle is an overlay, hiding it moves nothing else. A player seen ignoring the command (and not advertising shuffle) is remembered per app, so its button doesn't reappear on its next session; it is forgotten if that player ever reports a shuffle change. Without a live session the button follows the setting, except for players remembered this way. The Studio explains when a bound player lacks shuffle; follow-active keeps the generic hint since the player varies. Confirmed case: Lark Player (`com.dywx.larkplayer`) advertises neither shuffle action and only `LIKE`/`Stop` custom actions, and ignores `setShuffleMode`.

Unknown looks like "off", slightly softened, with its own content description; the widget never flips it optimistically, so it never shows a guessed on/off. No accessibility automation, taps, root or private broadcasts are used: only what the session itself publishes.

Diagnostics: *Studio > Advanced > Copy diagnostic report* puts a plain-text report on the clipboard: app/Android version, device, language, widgets (binding, shuffle on/off), each active session (package, action bitmask, both shuffle actions, compat readiness/mode, custom action ids and names, notification button titles, extras keys but not values, chosen strategy) and the last 60 shuffle events (capability changes, commands sent, ignored commands). In memory only, so it covers the current process; no track metadata. Debug builds also log the events under `RadiationShuffle`. The debug player has `--es shuffle standard|custom|deaf|notify` and `--es cmd report` (logs the report under `RadiationReport`).

## Text

| Topic | Reality | Radiation |
|---|---|---|
| System font | A view that sets no font family uses the device font, including Samsung font styles. Named families like `sans-serif-medium` are separate aliases that custom device fonts may not replace. Material 3 uses `sans-serif-medium` for titles, labels, buttons and chips. | No hard-coded family anywhere. The widget title is the default family at weight 500; in the Studio, Material's title/label styles are overridden to the default family at weight 500. Weights need Android 9+ (bold before). |
| Weights of custom fonts | A device font that ships only regular and bold has no true 500 weight. | Android picks the nearest weight it has, so on such fonts the title may render regular (hierarchy then comes from size and color). |

## Color tones and icon sets

| Topic | Approach |
|---|---|
| Pastel album colors | Same album seed and hue as Rich, placed in fixed soft bands instead of being darkened: surface lightness 0.90 (saturation 0.20-0.40), glow 0.79 (0.30-0.55), accent 0.78 (0.30-0.58). The saturation floor prevents muddy grays, the ceiling prevents candy colors, and fixed lightness makes very dark, bright and saturated art land in the same range. Monochrome art stays neutral; no artwork gives a clean warm-neutral light surface. Contrast rules then pick dark text and deepen the controls (same hue) on these light surfaces. |
| Gradient readability | If a gradient's two ends straddle the mid-tones (e.g. a pastel glow made translucent over a dark wallpaper), no text color reads on both; the glow is pulled toward the surface until black or white text does. |
| Icon sets | Rounded (default, Material Symbols Rounded), Sharp (classic Material), Line (1.75 strokes, round caps/joins), Bold (filled + stroked shapes, thick round bars). Each set is one drawable family covering play, pause, previous, next and shuffle off/on (with a dot in that set's style), all with the same pressed-state shrink. Line and Bold are stroke-built vectors; tinting them with a color filter is safe (unlike a stroke-only `GradientDrawable`, a vector path without a fill paints no interior). |

## Player binding

| Topic | Reality | Radiation |
|---|---|---|
| Discovering players | Android has no "list music players" API. | Union of: apps with a MediaBrowserService, a media-button receiver, an activity that opens audio files, or a media session right now; filtered to launchable apps. Matching `<queries>` make them visible on Android 11+. Apps currently playing are listed first. |
| Bound vs follow active | Sessions are listed per package. | A bound widget only considers its package's sessions (the playing one first if it has several). Another app starting playback never takes it over. Each binding in use is resolved once per render, with its own artwork tracker. |
| Controls | Without a session there is nothing to address; a media key goes wherever the system decides. | Controls carry their widget id and target that widget's player. A bound player with no session: play opens the app (reliable), skip/shuffle are dimmed and inert; never a media key. |
| No session / closed / uninstalled | | Last-known track (paused) if seen, otherwise the app's name with "Not playing" / "Not installed" (remembered name). Binding survives updates/reinstalls (same package). Without notification access no session is readable, so bound widgets show "Tap to connect". |
| Players without sessions | Some apps play audio without publishing a media session (e.g. YouTube Music's local-file preview). | Nothing to follow or control; shown as not playing. |

## Typography

| Topic | Radiation |
|---|---|
| Weight | RemoteViews can't change a typeface at runtime, so the layout has one title view per weight (regular/medium/bold) and two for the artist; the renderer shows one. Weights use the system font (`textFontWeight`, Android 9+; bold below). |
| Size and lines | Set at runtime (`setTextViewTextSize`, `setMaxLines`). The text block is fitted to the row height (rebalancing sizes, then dropping the artist, then the second line), so text never overlaps controls, shuffle or visualizer. |
| Long titles | Measured in the system font; a title that doesn't fit shrinks by at most 2.5sp (never below 11sp), keeping the artist at least 1.5sp smaller, then ellipsizes. Controls are a little narrower than tall (44/52dp wide, full-height targets) to give the title more room. |

## Idle mode

| Topic | Radiation |
|---|---|
| Opt-in | Per widget, off by default. Off means the original "Nothing playing" state, unchanged. Turning it off keeps the idle settings. |
| When idle | `IdlePolicy` (pure, tested): playing (incl. buffering/skipping) is never idle; a stopped/closed player is idle after a 4s grace (absorbs track changes and restarts); a paused one after 1 or 5 minutes, or never. The stamp is taken while playing and at the moment playback stops, so the grace counts from the real stop. During the grace the last track is shown paused, so there's no "Nothing playing" flash. |
| No timers | The next idle switch is one delayed task on the existing render thread (alive while the listener service is bound). No alarms, no polling. |
| Resume | The track being shown (live paused session, else the remembered last track) marked "Paused · App". Play continues that player's session if it still has one, otherwise opens the player. Bound widgets only ever use their own app; follow-active never borrows another app's empty session. |
| Last played | Remembered per binding (text in preferences, artwork as a small PNG), written only when the track changes, so it survives process restarts. Idle widgets keep the last artwork's colors. |
| Clock | `TextClock`, ticked by the launcher once a minute: zero updates from Radiation. Follows the system 12/24-hour setting and locale. Tapping opens the clock app. |
| Quick Launch | Up to four apps, chosen from launchable apps (`<queries>` MAIN/LAUNCHER). Icons are cached bitmaps; nothing runs until a tap. |
| Minimal | One resume glyph in the accent, optionally the player's name. |

## Studio preview

| Topic | Radiation |
|---|---|
| Inert taps | The preview is rendered non-interactive: no PendingIntents are created at all, and its container (`PreviewFrame`) consumes every touch. It can't open apps, trigger controls or launch another Studio. (Previously, with nothing playing, the body tap was the production "open Studio" intent.) |
| Playing / Idle | A Studio-only toggle under the preview shows either state without touching playback. |
