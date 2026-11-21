package com.strickland.jeepdash;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Toast;

import com.strickland.jeepdash.core.Catalog;
import com.strickland.jeepdash.core.GaugeSpec;
import com.strickland.jeepdash.core.GaugeType;
import com.strickland.jeepdash.core.Layout;
import com.strickland.jeepdash.core.LayoutCodec;
import com.strickland.jeepdash.core.LiveValues;
import com.strickland.jeepdash.core.Param;
import com.strickland.jeepdash.theme.JeepTheme;
import com.strickland.jeepdash.theme.Theme;
import com.strickland.jeepdash.theme.XpTheme;

import java.util.ArrayList;
import java.util.List;

/** The dash screen: binds the link service, owns the look and its saved layout. */
public class DashActivity extends Activity implements LinkService.Listener, ServiceConnection, DashView.Host {

    private SharedPreferences prefs;
    private DashView view;
    private XpTheme xp;
    private JeepTheme jeep;
    private String look;
    private LinkService service;
    private boolean bound;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("dash", MODE_PRIVATE);
        look = "jeep".equals(prefs.getString("look", "xp")) ? "jeep" : "xp";
        xp = new XpTheme(prefs.getString("xp_scheme", "Blue"));
        jeep = new JeepTheme(prefs.getString("jeep_backlight", "White"));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new DashView(this, this);
        view.chrome.canEdit = true;
        view.chrome.snap = prefs.getBoolean("snap", true);
        view.setTheme(theme());
        setContentView(view);
        askPermissions();
        if (b == null) UpdateChecker.check(this, buildNumber(), false);
    }

    private Theme theme() { return "jeep".equals(look) ? jeep : xp; }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController ic = getWindow().getInsetsController();
            if (ic != null) {
                ic.hide(WindowInsets.Type.systemBars());
                ic.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    // ---------------- permissions and the link ----------------

    private boolean hasBluetooth() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        if (!hasBluetooth()) need.add(Manifest.permission.BLUETOOTH_CONNECT);
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
            // not allowed while in the background; onStart tries again
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
        pushActiveParams();
        service.addListener(this);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) { service = null; }

    @Override
    public void onStatus(LinkService.Status s, String detail) {
        view.chrome.status = s;
        view.chrome.detail = detail;
        view.invalidate();
    }

    @Override
    public void onValues(LiveValues v) {
        if (service != null) view.chrome.readsPerSec = service.readsPerSecond();
        view.setValues(v);
    }

    private void pushActiveParams() {
        Layout l = view.layout();
        if (service != null && l != null) service.setActiveParams(l.activeParamIds());
    }

    // ---------------- look and layout ----------------

    @Override
    public void onDashSize(float wDp, float hDp) { loadLayout(); }

    private void loadLayout() {
        if (view.widthDp() <= 0) return;
        view.setLayout(LayoutCodec.decode(prefs.getString("layout_" + look, null), look, view.widthDp(), view.heightDp()));
        pushActiveParams();
    }

    private void saveLayout() {
        Layout l = view.layout();
        if (l == null) return;
        prefs.edit().putString("layout_" + look, LayoutCodec.encode(l)).apply();
        pushActiveParams();
        view.invalidate();
    }

    private void switchLook() {
        view.chrome.editing = false;
        look = "jeep".equals(look) ? "xp" : "jeep";
        prefs.edit().putString("look", look).apply();
        view.chrome.tab = "gauges";
        view.setTheme(theme());
        loadLayout();
    }

    @Override
    public void onDashAction(String action) {
        switch (action) {
            case "switch": switchLook(); break;
            case "tab:gauges": view.chrome.tab = "gauges"; view.invalidate(); break;
            case "tab:viz": view.chrome.tab = "viz"; view.invalidate(); break;
            case "logs": showLogs(); break;
            case "edit":
                view.chrome.tab = "gauges";
                view.chrome.editing = true;
                view.invalidate();
                Toast.makeText(this, "Drag a gauge to move it. Drag its corner to resize. Tap it for settings.", Toast.LENGTH_LONG).show();
                break;
            case "done":
                view.chrome.editing = false;
                saveLayout();
                break;
            case "snap":
                view.chrome.snap = !view.chrome.snap;
                prefs.edit().putBoolean("snap", view.chrome.snap).apply();
                view.invalidate();
                break;
            case "add": addGauge(); break;
            case "menu": showMenu(); break;
            case "status": showStatus(); break;
            default: break;
        }
    }

    @Override
    public void onGaugeTap(String key) {
        Layout l = view.layout();
        GaugeSpec g = l == null ? null : l.find(key);
        if (g != null) GaugeSettingsDialog.show(this, l, g, this::saveLayout);
    }

    @Override
    public void onLayoutEdited() { saveLayout(); }

    private void addGauge() {
        Layout l = view.layout();
        if (l == null) return;
        int n = 1;
        while (l.find("g" + n) != null) n++;
        String param = "rpm";
        for (Param p : Catalog.all()) {
            if (!l.activeParamIds().contains(p.id)) { param = p.id; break; }
        }
        GaugeSpec g = new GaugeSpec("g" + n, param, GaugeType.DIAL,
                view.widthDp() / 2 - 130, view.heightDp() / 2 - 130, 260, 260);
        l.add(g);
        saveLayout();
        GaugeSettingsDialog.show(this, l, g, this::saveLayout);
    }

    private void showLogs() {
        final List<Uri> logs = DriveLogStore.list(this);
        if (logs.isEmpty()) {
            showMessage("Drive logs", "No drives saved yet. A log starts by itself when the engine computer answers, and is kept in Downloads/JeepDash on this tablet.");
            return;
        }
        String[] names = new String[logs.size()];
        for (int i = 0; i < names.length; i++) names[i] = DriveLogStore.label(this, logs.get(i));
        new AlertDialog.Builder(this)
                .setTitle("Drive logs: tap one to share")
                .setItems(names, (dlg, which) -> {
                    try {
                        DriveLogStore.share(this, logs.get(which));
                    } catch (RuntimeException e) {
                        showMessage("Drive logs", "Could not open the share sheet. The file is in Downloads/JeepDash.");
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showMessage(String title, String message) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show();
    }

    private void showStatus() {
        if (view.chrome.status == LinkService.Status.NO_PERMISSION) {
            if (!hasBluetooth() && shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)) {
                askPermissions();
            } else {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                } catch (RuntimeException ignored) { }
            }
            return;
        }
        String d = view.chrome.detail;
        String line = LinkService.statusLine(view.chrome.status);
        showMessage("Dongle link", d.isEmpty() ? line + ". About " + view.chrome.readsPerSec + " readings per second." : d);
    }

    private void showMenu() {
        final boolean isXp = "xp".equals(look);
        final String[] choices = isXp ? XpTheme.SCHEMES : JeepTheme.BACKLIGHTS;
        final int n = choices.length;
        String[] items = new String[n + 3];
        for (int i = 0; i < n; i++) items[i] = (isXp ? "Window colour: " : "Backlight: ") + choices[i];
        items[n] = "Check for update";
        items[n + 1] = "Put this look's gauges back to the original layout";
        items[n + 2] = "Share link log (for troubleshooting)";
        new AlertDialog.Builder(this)
                .setTitle("Jeep Dash  (build " + buildNumber() + ")")
                .setItems(items, (dlg, which) -> {
                    if (which == n) {
                        UpdateChecker.check(this, buildNumber(), true);
                        return;
                    }
                    if (which == n + 1) {
                        confirmReset();
                        return;
                    }
                    if (which == n + 2) {
                        shareLinkLog();
                        return;
                    }
                    if (isXp) {
                        prefs.edit().putString("xp_scheme", choices[which]).apply();
                        xp.setScheme(choices[which]);
                    } else {
                        prefs.edit().putString("jeep_backlight", choices[which]).apply();
                        jeep.setBacklight(choices[which]);
                    }
                    view.invalidate();
                })
                .show();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("Original layout")
                .setMessage("Put the " + ("xp".equals(look) ? "XP" : "Jeep") + " look's gauges back where they started? Your own arrangement for this look is replaced.")
                .setPositiveButton("Reset", (d, w) -> {
                    prefs.edit().remove("layout_" + look).apply();
                    view.chrome.editing = false;
                    loadLayout();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void shareLinkLog() {
        if (service == null) return;
        String text = "Build " + buildNumber() + "\n" + service.linkLog();
        String name = "link-log-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date()) + ".txt";
        Uri uri = DriveLogStore.saveText(this, name, text);
        try {
            if (uri != null) {
                DriveLogStore.shareText(this, uri, "Jeep Dash link log");
            } else {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text.length() > 100000 ? text.substring(text.length() - 100000) : text);
                startActivity(Intent.createChooser(i, "Jeep Dash link log"));
            }
        } catch (RuntimeException e) {
            showMessage("Link log", "Could not open the share sheet. The log is saved in Downloads/JeepDash as " + name + ".");
        }
    }

    @SuppressWarnings("deprecation")
    long buildNumber() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }
}
