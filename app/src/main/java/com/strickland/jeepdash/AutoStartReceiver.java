package com.strickland.jeepdash;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

import com.strickland.jeepdash.core.AutoStart;

/**
 * Opens the dash when the tablet connects to the Bluetooth device the owner chose (the stereo). Switched on
 * only while that is set (see enable), so with the feature off Android never wakes the app for it. Android
 * only lets an app open a screen from the background when it may display over other apps; without that a
 * notification to tap is posted instead. The rules are in AutoStart.
 */
public class AutoStartReceiver extends BroadcastReceiver {
    static final String DEVICE = "autostart_device", NAME = "autostart_name";
    private static final String CHANNEL = "autostart";
    private static final int NOTIFICATION_ID = 2;

    /** Switches the receiver on or off with the setting. */
    static void enable(Context c, boolean on) {
        try {
            c.getPackageManager().setComponentEnabledSetting(new ComponentName(c, AutoStartReceiver.class),
                    on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    PackageManager.DONT_KILL_APP);
        } catch (RuntimeException ignored) { }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context c, Intent intent) {
        if (!BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction())) return;
        String chosen = c.getSharedPreferences(DashApp.PREFS, Context.MODE_PRIVATE).getString(DEVICE, null);
        if (chosen == null) return;
        BluetoothDevice dev = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
                : intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        String key;
        try {
            key = dev == null ? null : dev.getAddress();
        } catch (SecurityException e) {
            key = null;
        }
        switch (AutoStart.decide(chosen, key, DashApp.inFront(), Settings.canDrawOverlays(c))) {
            case OPEN:
                try {
                    c.startActivity(new Intent(c, SplashActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException e) {
                    notifyToOpen(c);              // refused after all: the owner can still tap
                }
                break;
            case NOTIFY:
                notifyToOpen(c);
                break;
            default:
                break;
        }
    }

    private static void notifyToOpen(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        try {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Open the dash", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent open = PendingIntent.getActivity(c, 2,
                    new Intent(c, SplashActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
            nm.notify(NOTIFICATION_ID, new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle("Jeep Dash")
                    .setContentText("Tap to open the dash")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build());
        } catch (RuntimeException ignored) {
            // notifications not allowed
        }
    }
}
