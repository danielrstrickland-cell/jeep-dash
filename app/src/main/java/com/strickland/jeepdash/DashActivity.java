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
import com.strickland.jeepdash.core.PollRunner;
import com.strickland.jeepdash.core.GaugeSpec;
import com.strickland.jeepdash.core.GaugeType;
import com.strickland.jeepdash.core.Layout;
import com.strickland.jeepdash.core.LayoutCodec;
import com.strickland.jeepdash.core.LiveValues;
import com.strickland.jeepdash.core.Motion;
import com.strickland.jeepdash.core.Param;
import com.strickland.jeepdash.theme.GtTheme;
import com.strickland.jeepdash.theme.JeepTheme;
import com.strickland.jeepdash.theme.RetroTheme;
import com.strickland.jeepdash.theme.Theme;
import com.strickland.jeepdash.theme.XpTheme;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** The dash screen: binds the link service, owns the look and its saved layout. */
public class DashActivity extends Activity implements LinkService.Listener, ServiceConnection, DashView.Host {

    private SharedPreferences prefs;
    private DashView view;
    private XpTheme xp;
    private JeepTheme jeep;
    private String look;
    private String[] looks;                            // the looks this car has, in the order SWITCH steps through
    private LinkService service;
    private boolean bound;
    private boolean sweepPending;                      // start-up needle sweep, once, after the intro
    private long sweepStart;
    private final LiveValues sweepValues = new LiveValues();
    private final TabletSensors tablet = new TabletSensors(this::onTabletValues);
    private boolean asking;                            // Android drops a permission request made during another
    private boolean askedToLook;                       // asked once for leave to search for an unpaired dongle
    private boolean askedLocation;                     // asked once; a refusal is not argued with

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("dash", MODE_PRIVATE);
        looks = Catalog.porsche() ? new String[]{"p_retro", "p_gt"} : new String[]{"xp", "jeep", "retro"};
        look = prefs.getString(lookKey(), looks[0]);
        if (!Arrays.asList(looks).contains(look)) look = looks[0];
        xp = new XpTheme(prefs.getString("xp_scheme", "Blue"));
        jeep = new JeepTheme(prefs.getString("jeep_backlight", "White"));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new DashView(this, this);
        view.chrome.canEdit = true;
        view.chrome.snap = prefs.getBoolean("snap", true);
        applyLook();
        setContentView(view);
        askPermissions();
        sweepPending = b == null && getIntent().getBooleanExtra("sweep", false);
        if (b == null) UpdateChecker.check(this, buildNumber(), false);
    }

    /** Each car remembers its own look. */
    private static String lookKey() { return Catalog.porsche() ? "look_p" : "look"; }

    private Theme theme() {
        switch (look) {
            case "jeep": return jeep;
            case "retro": case "p_retro": return new RetroTheme(look);
            case "p_gt": return new GtTheme();
            default: return xp;
        }
    }

    private static String lookName(String id) {
        switch (id) {
            case "jeep": return "JEEP STYLE";
            case "retro": case "p_retro": return "80s DIGITAL";
            case "p_gt": return "GT STYLE";
            default: return "XP STYLE";
        }
    }

    private String nextLook() { return looks[(Arrays.asList(looks).indexOf(look) + 1) % looks.length]; }

    /** Puts the current look on screen and names the next one on its switch button. */
    private void applyLook() {
        String next = lookName(nextLook());
        view.chrome.nextLook = "xp".equals(look) ? next.charAt(0) + next.substring(1).toLowerCase(java.util.Locale.US) : next;
        view.setTheme(theme());
    }

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
        if (need.isEmpty()) return;
        asking = true;
        requestPermissions(need.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        asking = false;
        startLink();
        syncTabletSensors();       // location may just have been allowed, or it is now its turn to be asked
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
        tablet.stop();
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
        // The dongle is not paired and the app may not search for it yet: ask, once.
        if (LinkService.NEED_SCAN_DETAIL.equals(detail) && !askedToLook && !asking) {
            askedToLook = true;
            asking = true;
            requestPermissions(Build.VERSION.SDK_INT >= 31 ? new String[]{Manifest.permission.BLUETOOTH_SCAN}
                    : new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 3);
        }
    }

    @Override
    public void onValues(LiveValues v) {
        if (service != null) view.chrome.readsPerSec = service.readsPerSecond();
        if (sweepStart == 0) view.setValues(v);
    }

    private void pushActiveParams() {
        Layout l = view.layout();
        if (service != null && l != null) {
            Set<String> ids = l.activeParamIds();
            // Pull, 0-60 and the speedo check are worked out from the Jeep's speed, speed gauge or not.
            if (Motion.needsJeepSpeed(ids)) ids.add("speed");
            service.setActiveParams(ids);
        }
        syncTabletSensors();
    }

    // ---------------- the tablet's own sensors ----------------

    /**
     * The sensors run only while the dash is on screen and shows a value that needs them. Location is asked
     * for only when a GPS gauge is there, and only once.
     */
    private void syncTabletSensors() {
        Layout l = view.layout();
        if (service == null || l == null) return;
        Set<String> ids = l.activeParamIds();
        boolean any = false;
        for (String id : ids) {
            Param p = Catalog.byId(id);
            if (p != null && p.tablet) any = true;
        }
        if (!any) {
            tablet.stop();
            return;
        }
        boolean gps = Motion.needsGps(ids);
        if (gps && !asking && !askedLocation
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            askedLocation = true;
            asking = true;
            // Android 12 and later only offer precise location when approximate is asked for with it.
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 2);
        }
        tablet.setValues(service.values());
        tablet.start(this, gps);
    }

    /** Tablet values do not wait for the dongle: they go to the screen as they are worked out. */
    private void onTabletValues() {
        if (service != null && sweepStart == 0) view.setValues(service.values());
    }

    // ---------------- look and layout ----------------

    @Override
    public void onDashSize(float wDp, float hDp) {
        loadLayout();
        if (sweepPending) {
            sweepPending = false;
            sweepStart = LinkService.now();
            view.postOnAnimation(this::sweepFrame);
        }
    }

    /** Start-up self-test: every gauge runs to the top of its scale and back, then real readings take over. */
    private void sweepFrame() {
        long now = LinkService.now();
        float t = (now - sweepStart) / 1600f;
        if (t >= 1) {
            sweepStart = 0;
            return;
        }
        double up = Math.sin(Math.PI * t);
        for (Param p : Catalog.all()) sweepValues.put(p.id, p.min + (p.max - p.min) * up, now);
        view.setValues(sweepValues);
        view.postOnAnimation(this::sweepFrame);
    }

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
        look = nextLook();
        prefs.edit().putString(lookKey(), look).apply();
        view.chrome.tab = "gauges";
        applyLook();
        loadLayout();
    }

    @Override
    public void onDashAction(String action) {
        switch (action) {
            case "switch": switchLook(); break;
            case "tab:gauges": view.chrome.tab = "gauges"; view.invalidate(); break;
            case "tab:viz": openVisualizer(); break;
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

    /** The visualizer is its own screen with its own app icon; this brings it up from the dash. */
    private void openVisualizer() {
        startActivity(new Intent(this, VisualizerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
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
        final boolean porsche = Catalog.porsche();
        final List<String> items = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        if ("xp".equals(look)) {
            for (String scheme : XpTheme.SCHEMES) {
                items.add("Window colour: " + scheme);
                actions.add(() -> {
                    prefs.edit().putString("xp_scheme", scheme).apply();
                    xp.setScheme(scheme);
                    view.invalidate();
                });
            }
        } else if ("jeep".equals(look)) {
            for (String light : JeepTheme.BACKLIGHTS) {
                items.add("Backlight: " + light);
                actions.add(() -> {
                    prefs.edit().putString("jeep_backlight", light).apply();
                    jeep.setBacklight(light);
                    view.invalidate();
                });
            }
        }
        if (service != null && service.codesAvailable()) {
            items.add("Trouble codes (read and clear)");
            actions.add(() -> { if (service != null) CodesDialog.show(this, service); });
        }
        if (service != null && service.fastRefreshAvailable()) {
            final PollRunner.Fast fast = service.fastRefresh();
            items.add(fast == PollRunner.Fast.OFF ? "Fast refresh (experimental): off"
                    : fast == PollRunner.Fast.ON ? "Fast refresh (experimental): ON"
                    : "Fast refresh: turned itself off (readings went missing). Tap to try again");
            actions.add(() -> { if (service != null) service.setFastRefresh(fast != PollRunner.Fast.ON); });
        }
        items.add("Check for update");
        actions.add(() -> UpdateChecker.check(this, buildNumber(), true));
        items.add("Put this look's gauges back to the original layout");
        actions.add(this::confirmReset);
        items.add("Share link log (for troubleshooting)");
        actions.add(this::shareLinkLog);
        if (!porsche) {
            items.add("Music visualizer");
            actions.add(this::openVisualizer);
        }
        items.add("Android Bluetooth settings (to pair the dongle)");
        actions.add(this::openBluetoothSettings);
        items.add(porsche ? "Vehicle: switch to Jeep Grand Cherokee" : "Vehicle: switch to Porsche Cayenne");
        actions.add(this::confirmVehicle);
        new AlertDialog.Builder(this)
                .setTitle((porsche ? "Cayenne Dash" : "Jeep Dash") + "  (build " + buildNumber() + ")")
                .setItems(items.toArray(new String[0]), (dlg, which) -> actions.get(which).run())
                .show();
    }

    /**
     * Some car radios hide Android's own Bluetooth screen behind one that only pairs phones. This asks for
     * the real one, where the dongle can be paired like on any tablet.
     */
    private void openBluetoothSettings() {
        Intent[] ways = {
                new Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
                new Intent().setClassName("com.android.settings", "com.android.settings.Settings$BluetoothSettingsActivity"),
                new Intent().setClassName("com.android.settings", "com.android.settings.bluetooth.BluetoothSettings"),
        };
        for (Intent way : ways) {
            try {
                startActivity(way);
                return;
            } catch (RuntimeException ignored) {
                // try the next way in
            }
        }
        showMessage("Bluetooth settings", "This radio has no Android Bluetooth settings screen to open.");
    }

    /** The other car speaks a different language to the dongle, so the app starts again set up for it. */
    private void confirmVehicle() {
        final boolean toPorsche = !Catalog.porsche();
        new AlertDialog.Builder(this)
                .setTitle(toPorsche ? "Switch to the Porsche Cayenne?" : "Switch to the Jeep Grand Cherokee?")
                .setMessage("The app restarts set up for that vehicle. Each vehicle keeps its own looks and gauge layouts.")
                .setPositiveButton("Switch", (d, w) -> DashApp.switchVehicle(this, toPorsche ? DashApp.PORSCHE : DashApp.JEEP))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("Original layout")
                .setMessage("Put this look's gauges back where they started? Your own arrangement for this look is replaced.")
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
