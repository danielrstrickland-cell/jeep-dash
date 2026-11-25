package com.strickland.jeepdash;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import com.strickland.jeepdash.core.LiveValues;
import com.strickland.jeepdash.core.TabletWatch;

import java.util.Map;

/**
 * The tablet's own heat and battery, watched by the link service behind every screen (TabletWatch does the
 * thinking): the battery broadcast (charge, charging, battery temperature), the charge current where the tablet
 * reports it, and Android's thermal status and headroom. Every two seconds the readings go in with the car's
 * (gauges and drive log), the heat protection is updated, and a new alert becomes a notification when the dash
 * is not in front (the dash shows it itself when it is). Main thread.
 */
final class TabletHealth {
    static final String PREF_PROTECT = "holo_heat";
    private static final String CHANNEL = "tablet";
    private static final int NOTE_ID = 77;
    private static final long TICK_MS = 2000;

    final TabletWatch watch = new TabletWatch();
    private final Context context;
    private final LiveValues values;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::tick;
    private boolean running, dashInFront;
    private int notified;
    private Object thermalListener;

    private final BroadcastReceiver battery = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) { read(i); }
    };

    TabletHealth(Context c, LiveValues values) {
        this.context = c;
        this.values = values;
    }

    void start() {
        if (running) return;
        running = true;
        try {
            read(context.registerReceiver(battery, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
        } catch (RuntimeException ignored) {
            // no battery broadcast: the battery gauges stay blank
        }
        PowerManager pm = context.getSystemService(PowerManager.class);
        if (pm != null && Build.VERSION.SDK_INT >= 29) {
            try {
                PowerManager.OnThermalStatusChangedListener l = s -> watch.thermal(s, LinkService.now());
                pm.addThermalStatusListener(l);
                thermalListener = l;
                watch.thermal(pm.getCurrentThermalStatus(), LinkService.now());
            } catch (RuntimeException ignored) {
                // no thermal service
            }
        }
        main.post(tick);
    }

    void stop() {
        if (!running) return;
        running = false;
        main.removeCallbacks(tick);
        try {
            context.unregisterReceiver(battery);
        } catch (RuntimeException ignored) {
            // was not registered
        }
        PowerManager pm = context.getSystemService(PowerManager.class);
        if (pm != null && Build.VERSION.SDK_INT >= 29 && thermalListener != null) {
            try {
                pm.removeThermalStatusListener((PowerManager.OnThermalStatusChangedListener) thermalListener);
            } catch (RuntimeException ignored) {
                // gone already
            }
        }
        thermalListener = null;
    }

    /** The dash is (or is no longer) in front: it shows alerts itself while it is. */
    void setDashInFront(boolean front) { dashInFront = front; }

    private void read(Intent i) {
        if (i == null) return;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        int pct = level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
        watch.battery(temp == Integer.MIN_VALUE ? Double.NaN : temp / 10.0, pct, charging, LinkService.now());
    }

    private void tick() {
        if (!running) return;
        main.postDelayed(tick, TICK_MS);
        long now = LinkService.now();
        BatteryManager bm = context.getSystemService(BatteryManager.class);
        if (bm != null) {
            long ua = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            watch.current(ua == Long.MIN_VALUE || ua == Integer.MIN_VALUE ? 0 : ua);
        }
        PowerManager pm = context.getSystemService(PowerManager.class);
        if (pm != null && Build.VERSION.SDK_INT >= 30) {
            try {
                watch.headroom(pm.getThermalHeadroom(10));
            } catch (RuntimeException ignored) {
                // not offered
            }
        }
        for (Map.Entry<String, Double> e : watch.values().entrySet()) values.put(e.getKey(), e.getValue(), now);
        boolean protect = context.getSharedPreferences("dash", Context.MODE_PRIVATE).getBoolean(PREF_PROTECT, true);
        watch.guard.update(watch.heat(), now, protect);
        int alert = watch.alert();
        if (alert > notified && !dashInFront) notifyAlert();
        if (alert == TabletWatch.OK) cancelNote();
        notified = alert;
    }

    private void notifyAlert() {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        try {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Tablet heat and battery", NotificationManager.IMPORTANCE_DEFAULT));
            Intent open = new Intent(context, DashActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(context, 7, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            String unit = context.getSharedPreferences("dash", Context.MODE_PRIVATE).getString("temp_unit", "°F");
            Notification n = new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle("Jeep Dash: tablet")
                    .setContentText(watch.message(unit))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(NOTE_ID, n);
        } catch (RuntimeException ignored) {
            // notifications not allowed
        }
    }

    private void cancelNote() {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null && notified != TabletWatch.OK) nm.cancel(NOTE_ID);
    }
}
