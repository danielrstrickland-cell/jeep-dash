package com.strickland.jeepdash;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Jeep Tune's "dongle busy" broadcast: busy=true when a read starts and every 30 s after, busy=false at the
 * end. Declared in the manifest, so it works whether or not the dash is running. Broadcasts without the
 * boolean extra are ignored.
 */
public class BusyReceiver extends BroadcastReceiver {
    static final String ACTION = "com.strickland.jeeptune.action.DONGLE_BUSY";
    static final String EXTRA_BUSY = "busy";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction()) || !intent.hasExtra(EXTRA_BUSY)) return;
        LinkService.onTuneBusy(intent.getBooleanExtra(EXTRA_BUSY, false));
    }
}
