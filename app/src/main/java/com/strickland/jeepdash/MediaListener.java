package com.strickland.jeepdash;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.service.notification.NotificationListenerService;

/**
 * Does nothing itself. Android only lets an app see and control another app's music (Spotify) when the owner
 * has given it notification access, and that access is given to a service of this kind. It is switched on only
 * while the music button is on, so with it off the app is exactly as it was.
 */
public class MediaListener extends NotificationListenerService {

    static ComponentName component(Context c) { return new ComponentName(c, MediaListener.class); }

    /** Switches the service on or off with the music button. */
    static void enable(Context c, boolean on) {
        try {
            c.getPackageManager().setComponentEnabledSetting(component(c),
                    on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    PackageManager.DONT_KILL_APP);
            if (on) requestRebind(component(c));      // access given before the button was last turned off
        } catch (RuntimeException ignored) {
            // the music window then simply says access is missing
        }
    }
}
