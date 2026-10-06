# Platform notes

What Android's AppWidget / RemoteViews model allows, and what Radiation does about it.

| Want | Reality | Radiation |
|---|---|---|
| Read the active player | `MediaSessionManager.getActiveSessions()` requires an enabled `NotificationListenerService`. | Asks for notification access with an in-app explanation. Notifications themselves are ignored. |
| Controls without that access | `AudioManager.dispatchMediaKeyEvent` reaches the last active player, no permission needed. | Used as a fallback. Metadata can't be shown without access. |
| Sideloaded installs (Android 13+) | Notification access is a "restricted setting" for apps not installed from an app store. | User must enable "Allow restricted settings" in App info first; the studio says so. |
| Press feedback | Drawables (incl. `<ripple>`) work. View animations / scale do not run in RemoteViews. | Native ripple on every control. No fake animations. |
| Haptics | A widget cannot request haptics. Vibrating from the click broadcast needs `VIBRATE` and lands after an IPC round trip; most launchers already give tap haptics. | Not implemented. Revisit only if it can feel immediate. |
| Live progress | No continuous animation; every position update is a full widget update (IPC + battery). | Deferred. Candidate: update only while playing, coarse interval. |
| Marquee | Needs focus/selection, which RemoteViews can't reliably give. | Ellipsize at end. |
| Runtime corner radius | `setViewOutlinePreferredRadius` is API 31+. | Fixed radius for now. |
| Rounded artwork | ImageView clipping isn't available. | Artwork is scaled and rounded into a small bitmap once per track. |
| Material You | System palette resources (`system_accent1_*` …) are API 31+. | Resolved at render; re-rendered on configuration change. Neutral dark fallback below 31. |
