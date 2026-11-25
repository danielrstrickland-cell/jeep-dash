package com.strickland.jeepdash;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
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

import com.strickland.jeepdash.core.AutoStart;
import com.strickland.jeepdash.core.Catalog;
import com.strickland.jeepdash.core.DashMenu;
import com.strickland.jeepdash.core.FuelUse;
import com.strickland.jeepdash.core.NightHours;
import com.strickland.jeepdash.core.PollRunner;
import com.strickland.jeepdash.core.GaugeSpec;
import com.strickland.jeepdash.core.GaugeType;
import com.strickland.jeepdash.core.Layout;
import com.strickland.jeepdash.core.LayoutCodec;
import com.strickland.jeepdash.core.LiveValues;
import com.strickland.jeepdash.core.Motion;
import com.strickland.jeepdash.core.NowPlaying;
import com.strickland.jeepdash.core.Param;
import com.strickland.jeepdash.core.SpeedCorrection;
import com.strickland.jeepdash.core.SpeedVolume;
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
    private final NightSensor night = new NightSensor(this::onNightChanged);
    private boolean asking;                            // Android drops a permission request made during another
    private boolean askedToLook;                       // asked once for leave to search for an unpaired dongle
    private static final String[] MODE_LABELS = {"Auto", "Always light", "Always dark"};
    private boolean askedLocation;                     // asked once; a refusal is not argued with
    private MusicSession music;                        // what the music app plays, for the music button's window

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("dash", MODE_PRIVATE);
        looks = Catalog.porsche() ? new String[]{"p_retro", "p_gt"} : new String[]{"xp", "jeep", "retro"};
        look = prefs.getString(lookKey(), looks[0]);
        if (!Arrays.asList(looks).contains(look)) look = looks[0];
        xp = new XpTheme(prefs.getString("xp_scheme", "Blue"));
        xp.setMode(prefs.getString("xp_mode", NightHours.AUTO));
        jeep = new JeepTheme(prefs.getString("jeep_backlight", "White"));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new DashView(this, this);
        view.chrome.canEdit = true;
        view.chrome.snap = prefs.getBoolean("snap", true);
        applyLook();
        setContentView(view);
        syncNight();
        music = new MusicSession(this, this::onMusic);
        view.chrome.music = MusicSession.buttonOn(this);
        askPermissions();
        sweepPending = b == null && getIntent().getBooleanExtra("sweep", false);
        if (b == null) UpdateChecker.check(this, buildNumber(), false);
    }

    /** Auto brightness: the light sensor (or sunset) runs only while the XP look is in front and set to Auto. */
    private void syncNight() {
        if ("xp".equals(look) && NightHours.AUTO.equals(prefs.getString("xp_mode", NightHours.AUTO))) night.start(this);
        else night.stop();
    }

    private void onNightChanged() {
        xp.setAutoDark(night.dark());
        view.invalidate();
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
        syncNight();
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
        syncNight();
        startLink();
        bound = bindService(new Intent(this, LinkService.class), this, Context.BIND_AUTO_CREATE);
        if (view.chrome.music) {
            music.start();
            // Ask for notification access whenever the button is on without it (once per screen visit).
            if (!MusicSession.hasAccess(this)) view.post(() -> { if (!isFinishing() && !music.access()) MusicSession.promptAccess(this); });
        }
    }

    @Override
    protected void onStop() {
        music.stop();
        tablet.stop();
        night.stop();
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
            if (Motion.needsRpm(ids)) ids.add("rpm");            // the gear indicator
            // the service adds what the fuel counter needs, and keeps it read while another screen is in front
            service.setDashGauges(l.activeParamIds());
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
            if (p != null && p.tablet && !FuelUse.GAUGES.contains(id)) any = true;      // fuel: LinkService
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
        tablet.setLink(service);
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
            case "music":
                view.chrome.musicOpen = !view.chrome.musicOpen;
                view.invalidate();
                break;
            case "music:prev": music.transport(-1); break;
            case "music:play": music.transport(0); break;
            case "music:next": music.transport(1); break;
            case "music:window": music.windowTap(); break;
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

    /** The music app changed (or access did): the window's lines and play/pause follow. */
    private void onMusic() {
        boolean a = music.access(), on = music.hasSession();
        String t = music.title(), r = music.artist();
        view.chrome.musicTitle = NowPlaying.titleLine(a, on, t, r, 60);
        view.chrome.musicArtist = NowPlaying.artistLine(a, on, t, r, 60);
        view.chrome.musicPlaying = music.playing();
        view.invalidate();
    }

    // ---------------- the menu: groups, then their entries (core DashMenu) ----------------

    private DashMenu.State menuState() {
        DashMenu.State s = new DashMenu.State();
        s.porsche = Catalog.porsche();
        s.look = look;
        s.nextLook = lookName(nextLook());
        s.linked = service != null;
        s.codes = service != null && service.codesAvailable();
        s.fastAvailable = service != null && service.fastRefreshAvailable();
        if (s.fastAvailable) {
            PollRunner.Fast f = service.fastRefresh();
            s.fast = f == PollRunner.Fast.ON ? "on" : f == PollRunner.Fast.OFF ? "off" : "backed_off";
        }
        s.xpScheme = prefs.getString("xp_scheme", "Blue");
        s.xpMode = prefs.getString("xp_mode", NightHours.AUTO);
        s.jeepBacklight = prefs.getString("jeep_backlight", "White");
        s.musicButton = view.chrome.music;
        if (service != null) s.volume = service.volumeFollowsSpeed().name();
        s.autoStart = prefs.getString(AutoStartReceiver.DEVICE, null) == null ? null
                : prefs.getString(AutoStartReceiver.NAME, "on");
        s.speedCorrectionPct = service != null ? service.speedCorrection()
                : prefs.getFloat(Catalog.porsche() ? "speed_corr_p" : "speed_corr", 0);
        s.canEdit = view.chrome.canEdit;
        s.snap = view.chrome.snap;
        return s;
    }

    private void showMenu() {
        final List<DashMenu.Group> groups = DashMenu.build(menuState());
        String[] titles = new String[groups.size()];
        for (int i = 0; i < titles.length; i++) titles[i] = groups.get(i).title;
        new AlertDialog.Builder(this)
                .setTitle((Catalog.porsche() ? "Cayenne Dash" : "Jeep Dash") + "  (build " + buildNumber() + ")")
                .setItems(titles, (dlg, which) -> showGroup(groups.get(which)))
                .setNegativeButton("Close", null)
                .show();
    }

    private void showGroup(DashMenu.Group g) {
        String[] labels = new String[g.entries.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = g.entries.get(i).label;
        new AlertDialog.Builder(this)
                .setTitle(g.title)
                .setItems(labels, (dlg, which) -> runEntry(g.entries.get(which).id))
                .setNegativeButton("Back", (dlg, which) -> showMenu())
                .show();
    }

    private void runEntry(String id) {
        switch (id) {
            case DashMenu.VEHICLE: confirmVehicle(); break;
            case DashMenu.FAST:
                if (service != null) service.setFastRefresh(service.fastRefresh() != PollRunner.Fast.ON);
                break;
            case DashMenu.SPEED_CORRECTION: chooseSpeedCorrection(); break;
            case DashMenu.BLUETOOTH: openBluetoothSettings(); break;
            case DashMenu.LINK_LOG: shareLinkLog(); break;
            case DashMenu.EDIT: onDashAction("edit"); break;
            case DashMenu.ADD: addGauge(); break;
            case DashMenu.SNAP: onDashAction("snap"); break;
            case DashMenu.RESET: confirmReset(); break;
            case DashMenu.SWITCH_LOOK: switchLook(); break;
            case DashMenu.XP_SCHEME:
                pick("Window colour", XpTheme.SCHEMES, prefs.getString("xp_scheme", "Blue"), v -> {
                    prefs.edit().putString("xp_scheme", v).apply();
                    xp.setScheme(v);
                });
                break;
            case DashMenu.XP_MODE:
                pick("Brightness (Auto: light sensor, else sunset and sunrise)", MODE_LABELS,
                        NightHours.label(prefs.getString("xp_mode", NightHours.AUTO)), v -> {
                    String m = NightHours.fromLabel(v);
                    if (m == null) return;
                    prefs.edit().putString("xp_mode", m).apply();
                    xp.setMode(m);
                    syncNight();
                });
                break;
            case DashMenu.JEEP_BACKLIGHT:
                pick("Backlight", JeepTheme.BACKLIGHTS, prefs.getString("jeep_backlight", "White"), v -> {
                    prefs.edit().putString("jeep_backlight", v).apply();
                    jeep.setBacklight(v);
                });
                break;
            case DashMenu.MUSIC_BUTTON: chooseMusicButton(); break;
            case DashMenu.VOLUME: chooseVolume(); break;
            case DashMenu.VISUALIZER: openVisualizer(); break;
            case DashMenu.FUEL: FuelDialog.show(this, this::pushActiveParams); break;
            case DashMenu.CODES: if (service != null) CodesDialog.show(this, service); break;
            case DashMenu.LOGS: showLogs(); break;
            case DashMenu.LISTEN: startActivity(new Intent(this, ListenActivity.class)); break;
            case DashMenu.TUNE_LOG:
                if (LinkService.tunePaused()) showMessage("Tune log", LinkService.PAUSED_DETAIL);
                else startActivity(new Intent(this, TuneLogActivity.class));
                break;
            case DashMenu.AUTO_START: chooseAutoStart(); break;
            case DashMenu.UPDATE: UpdateChecker.check(this, buildNumber(), true); break;
            default: break;
        }
    }

    /** One of a few named choices, the current one marked; the dash redraws after. */
    private void pick(String title, String[] choices, String now, java.util.function.Consumer<String> then) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(choices, Arrays.asList(choices).indexOf(now), (dlg, which) -> {
                    dlg.dismiss();
                    then.accept(choices[which]);
                    view.invalidate();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------------- now playing, auto-start, volume follows speed ----------------

    private void chooseMusicButton() {
        pick("Music button", new String[]{"Off", "On"}, view.chrome.music ? "On" : "Off", v -> {
            boolean on = "On".equals(v);
            MusicSession.setButtonOn(this, on);
            view.chrome.music = on;
            view.chrome.musicOpen = false;
            if (on) music.start();
            else music.stop();
            if (on && !MusicSession.hasAccess(this)) {
                MusicSession.promptAccess(this);
            }
        });
    }

    /**
     * Speed correction for this car, -15% to +15% in tenths. "Set from GPS" fills in what the tablet's GPS has
     * measured, when it has a minute above 40 mph (SpeedCorrection.Gps), and says so plainly when it has not.
     */
    private void chooseSpeedCorrection() {
        if (service == null) {
            showMessage("Speed correction", "Wait for the dash to finish starting, then try again.");
            return;
        }
        final android.widget.TextView shown = new android.widget.TextView(this);
        final android.widget.SeekBar bar = new android.widget.SeekBar(this);
        bar.setMax(300);                                       // -15.0 .. +15.0 in tenths
        bar.setProgress((int) Math.round(service.speedCorrection() * 10) + 150);
        shown.setTextSize(20);
        shown.setText(DashMenu.correctionLabel((bar.getProgress() - 150) / 10.0));
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar b, int p, boolean user) {
                shown.setText(DashMenu.correctionLabel((p - 150) / 10.0));
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar b) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar b) { }
        });
        android.widget.TextView note = new android.widget.TextView(this);
        note.setText("Raises or lowers the car's speed reading for bigger or smaller tyres. Everything made from speed uses it; Speedo vs GPS still shows the reading's own error.");
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(shown);
        box.addView(bar);
        box.addView(note);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Speed correction (this car)")
                .setView(box)
                .setPositiveButton("Save", (dlg, w) -> {
                    if (service != null) service.setSpeedCorrection((bar.getProgress() - 150) / 10.0);
                })
                .setNeutralButton("Set from GPS", null)
                .setNegativeButton("Cancel", null)
                .show();
        // kept open, so the owner sees the figure before saving it
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            if (service == null) return;
            SpeedCorrection.Gps g = service.speedGps();
            Double pct = g.measuredPct();
            if (pct == null) {
                showMessage("Set from GPS", "Not enough GPS yet: it needs a minute of driving above 40 mph with the GPS on (so far "
                        + g.countedMs() / 1000 + " s). The GPS runs while a GPS speed or Speedo vs GPS gauge is on the dash.");
            } else if (!SpeedCorrection.usable(pct)) {
                showMessage("Set from GPS", String.format(java.util.Locale.US,
                        "GPS measured %+.1f%%, outside the -15%% to +15%% this allows, so it was not used.", pct));
            } else {
                bar.setProgress((int) Math.round(pct * 10) + 150);
            }
        });
    }

    /** Paired devices by name, never the dongle the link remembers, plus Off. */
    private void chooseAutoStart() {
        final List<String> names = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
        names.add("Off");
        keys.add(null);
        String dongle = getSharedPreferences("link", MODE_PRIVATE).getString("address", null);
        try {
            BluetoothManager bm = getSystemService(BluetoothManager.class);
            BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            if (ad != null) {
                for (BluetoothDevice d : ad.getBondedDevices()) {
                    String key = d.getAddress();
                    if (!AutoStart.offer(key, dongle)) continue;
                    String name = d.getName();
                    names.add(name == null || name.isEmpty() ? "Unnamed device" : name);
                    keys.add(key);
                }
            }
        } catch (SecurityException e) {
            showMessage("Auto-start", "Allow Nearby devices for Jeep Dash first, so it can list the paired Bluetooth devices.");
            return;
        }
        String chosen = prefs.getString(AutoStartReceiver.DEVICE, null);
        int at = 0;
        for (int i = 1; i < keys.size(); i++) if (keys.get(i).equalsIgnoreCase(chosen == null ? "" : chosen)) at = i;
        new AlertDialog.Builder(this)
                .setTitle("Open the dash when the tablet connects to")
                .setSingleChoiceItems(names.toArray(new String[0]), at, (dlg, which) -> {
                    dlg.dismiss();
                    String key = keys.get(which);
                    if (key == null) {
                        prefs.edit().remove(AutoStartReceiver.DEVICE).remove(AutoStartReceiver.NAME).apply();
                        AutoStartReceiver.enable(this, false);
                        return;
                    }
                    prefs.edit().putString(AutoStartReceiver.DEVICE, key).putString(AutoStartReceiver.NAME, names.get(which)).apply();
                    AutoStartReceiver.enable(this, true);
                    autoStartAllowances();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** The notification is the fallback, so ask for it; then offer "display over other apps". */
    private void autoStartAllowances() {
        if (Build.VERSION.SDK_INT >= 33 && !asking
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            asking = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4);
        }
        if (Settings.canDrawOverlays(this)) return;
        new AlertDialog.Builder(this)
                .setTitle("Open by itself")
                .setMessage("Android only lets the dash open by itself when Jeep Dash may display over other apps; without that you get a \"Tap to open the dash\" notification instead.")
                .setPositiveButton("Open settings", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                    } catch (RuntimeException e) {
                        showMessage("Display over other apps", "This device has no screen for that setting.");
                    }
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    private void chooseVolume() {
        if (service == null) return;
        final SpeedVolume.Strength[] all = SpeedVolume.Strength.values();
        new AlertDialog.Builder(this)
                .setTitle("Volume follows speed (experimental)")
                .setSingleChoiceItems(new String[]{"Off", "Low (up to 4 steps)", "Medium (up to 5 steps)", "High (up to 6 steps)"},
                        service.volumeFollowsSpeed().ordinal(), (dlg, which) -> {
                    dlg.dismiss();
                    if (service == null) return;
                    boolean wasOff = service.volumeFollowsSpeed() == SpeedVolume.Strength.OFF;
                    service.setVolumeFollowsSpeed(all[which]);      // it adds speed to what is read itself
                    if (wasOff && all[which] != SpeedVolume.Strength.OFF)
                        showMessage("Volume follows speed", "From 20 mph it raises the tablet's media volume a few of its coarse steps (often 15 from silent to full), and whether the stereo follows them over Bluetooth depends on the stereo.");
                })
                .setNegativeButton("Cancel", null)
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
