package com.strickland.jeepdash;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.WindowManager;
import android.widget.TextView;

import com.strickland.jeepdash.core.Catalog;
import com.strickland.jeepdash.core.LiveValues;
import com.strickland.jeepdash.core.Param;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Temporary link check screen: lists every live value as text. The real dash replaces it in the next build. */
public class DashActivity extends Activity implements LinkService.Listener, ServiceConnection {

    private TextView text;
    private LinkService service;
    private boolean bound;
    private LinkService.Status status = LinkService.Status.SEARCHING;
    private String detail = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        text = new TextView(this);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(22);
        text.setTextColor(Color.WHITE);
        text.setBackgroundColor(Color.BLACK);
        text.setPadding(40, 40, 40, 40);
        setContentView(text);
        askPermissions();
    }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        startLink();
    }

    private void startLink() {
        try {
            startService(new Intent(this, LinkService.class));
        } catch (RuntimeException ignored) {
            // not allowed while in the background; onStart will try again
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        startLink();
        bound = bindService(new Intent(this, LinkService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        if (service != null) service.removeListener(this);
        service = null;
        if (bound) {
            unbindService(this);
            bound = false;
        }
        super.onStop();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((LinkService.LocalBinder) binder).service();
        Set<String> all = new LinkedHashSet<>();
        for (Param p : Catalog.all()) all.add(p.id);
        service.setActiveParams(all);
        service.addListener(this);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) { service = null; }

    @Override
    public void onStatus(LinkService.Status s, String d) {
        status = s;
        detail = d;
        render(null);
    }

    @Override
    public void onValues(LiveValues v) { render(v); }

    private void render(LiveValues v) {
        StringBuilder sb = new StringBuilder();
        sb.append("JEEP DASH  link check\n\n");
        sb.append("Status: ").append(status);
        if (!detail.isEmpty()) sb.append("\n").append(detail);
        sb.append("\nReads per second: ").append(service == null ? 0 : service.readsPerSecond()).append("\n\n");
        long now = LinkService.now();
        for (Param p : Catalog.all()) {
            Double d = v == null ? null : v.get(p.id, now);
            sb.append(String.format(Locale.US, "%-16s %10s %-4s %s\n", p.name,
                    d == null ? "—" : String.format(Locale.US, "%.1f", d), p.unit, p.slow() ? "(slow)" : ""));
        }
        sb.append("\nDrive logs saved: ").append(v == null ? "" : String.valueOf(logCount()));
        text.setText(sb);
    }

    private int logCount;
    private long logCountAt;

    private int logCount() {
        long t = LinkService.now();
        if (t - logCountAt > 5000) {
            logCountAt = t;
            logCount = DriveLogStore.list(this).size();
        }
        return logCount;
    }
}
