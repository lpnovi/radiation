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
| Press animation | RemoteViews can't run view animations (no scale/translate/animator). Drawable **state** changes do work, including `StateListDrawable` enter/exit fades. | Play/pause disc shrinks 3dp and dims while pressed, glyphs shrink via a pressed-state inset, everything eases back over 160ms. Prev/next also get a native ripple whose color matches the background. |
| Immediate response | A tap reaches the app as a broadcast; the player's new state comes back later. | Play/pause flips its glyph immediately (`partiallyUpdateAppWidget`); the player's real state then confirms or corrects it. |
| Haptics | Widgets can't request haptics. Vibrating from the click broadcast needs `VIBRATE` and lands after the broadcast round-trip; most launchers already give their own tap haptic. | Not implemented: it would feel late. |
| Live progress | No continuous animation; each position update is a full widget update (IPC + battery). | Deferred. |
