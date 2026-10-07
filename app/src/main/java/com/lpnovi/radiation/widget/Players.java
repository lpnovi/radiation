package com.lpnovi.radiation.widget;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.net.Uri;

import androidx.annotation.Nullable;

import com.lpnovi.radiation.media.MediaListenerService;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Installed media players, for "Bind to player".
 *
 * Android has no "list all music players" API, so candidates are the union of signals players
 * reliably give, filtered to apps the user can launch:
 *  - a MediaBrowserService (how Android Auto and system media controls browse players),
 *  - a media-button receiver (how headsets and Bluetooth resume playback),
 *  - an activity that opens audio files (local/offline players),
 *  - a media session right now (any app currently playing, even if it shows none of the above).
 * The manifest declares matching {@code <queries>} so these apps are visible on Android 11+.
 */
public final class Players {

    public static final class Player {
        public final String packageName;
        public final CharSequence label;
        public final Drawable icon;
        public final boolean hasSession;

        Player(String packageName, CharSequence label, Drawable icon, boolean hasSession) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
            this.hasSession = hasSession;
        }
    }

    private Players() {}

    /** Media-capable, launchable apps, those with a session first, then alphabetical. Not on the main thread. */
    public static List<Player> find(Context context) {
        PackageManager pm = context.getPackageManager();
        Set<String> candidates = new LinkedHashSet<>();
        Set<String> withSession = new LinkedHashSet<>();
        for (MediaController c : sessions(context)) withSession.add(c.getPackageName());
        candidates.addAll(withSession);
        addPackages(candidates, pm.queryIntentServices(new Intent("android.media.browse.MediaBrowserService"), 0));
        addPackages(candidates, pm.queryBroadcastReceivers(new Intent(Intent.ACTION_MEDIA_BUTTON), 0));
        Intent openAudio = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///x.mp3"), "audio/*");
        addPackages(candidates, pm.queryIntentActivities(openAudio, 0));

        List<Player> players = new ArrayList<>();
        for (String pkg : candidates) {
            // Ourselves only when we actually have a session (the debug build's test player).
            if (pkg.equals(context.getPackageName()) && !withSession.contains(pkg)) continue;
            if (pm.getLaunchIntentForPackage(pkg) == null) continue; // not something a user opens
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                players.add(new Player(pkg, pm.getApplicationLabel(info), pm.getApplicationIcon(info),
                        withSession.contains(pkg)));
            } catch (PackageManager.NameNotFoundException ignored) {
                // Uninstalled between query and lookup.
            }
        }
        Collator collator = Collator.getInstance();
        Collections.sort(players, (a, b) -> a.hasSession != b.hasSession ? (a.hasSession ? -1 : 1)
                : collator.compare(a.label.toString(), b.label.toString()));
        return players;
    }

    public static boolean isInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** The app's current name; the remembered one if it's gone; the package as a last resort. */
    public static CharSequence label(Context context, String packageName, @Nullable String remembered) {
        PackageManager pm = context.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0));
        } catch (PackageManager.NameNotFoundException e) {
            return remembered != null ? remembered : packageName;
        }
    }

    private static List<MediaController> sessions(Context context) {
        try {
            return context.getSystemService(MediaSessionManager.class)
                    .getActiveSessions(new ComponentName(context, MediaListenerService.class));
        } catch (SecurityException e) {
            return Collections.emptyList();
        }
    }

    private static void addPackages(Set<String> into, List<ResolveInfo> found) {
        for (ResolveInfo r : found) {
            if (r.serviceInfo != null) into.add(r.serviceInfo.packageName);
            else if (r.activityInfo != null) into.add(r.activityInfo.packageName);
        }
    }
}
