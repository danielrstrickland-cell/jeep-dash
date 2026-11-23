package com.strickland.jeepdash.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The Porsche line. Replies below are from the capture of the GearWrench app on the Cayenne at idle. */
public class KwpTest {
    static final Map<Integer, String> IDLE = new HashMap<>();
    static {
        IDLE.put(0x0a, "9a f1 10 61 0a 01 c8 17 15 46 c3 07 7d 00 14 64 7e 05 32 68 19 12 07 05 32 82 21 85 1d cb");
        IDLE.put(0x0b, "9a f1 10 61 0b 21 ff 0a 05 0a 8c 05 0a 7c 1b 4b 92 14 40 80 21 ff 1c 3c 11 78 3e 64 01 c7");
        IDLE.put(0x14, "9a f1 10 61 14 36 00 00 22 64 7a 22 64 7b 14 19 80 14 19 81 14 19 82 25 00 00 25 00 00 9b");
        IDLE.put(0x16, "9a f1 10 61 16 0f 29 04 19 f9 06 01 32 5f 12 fa 26 12 7d c8 19 b6 03 1a 3c 61 21 85 1c cc");
        IDLE.put(0x3b, "9a f1 10 61 3b 21 85 96 21 43 4b 21 43 00 12 fa 64 21 ff 00 14 29 80 12 fa 62 21 85 ed d4");
    }
    static final String[] ALL = {"rpm", "speed", "coolant", "iat", "cam_r", "cam_l", "oil_temp", "boost_set", "boost"};

    /** Fake dongle on a Cayenne: acks everything, wakes up when the key is on, answers group reads. */
    static final class Cayenne implements ByteLink {
        DongleSession session;
        final List<String> sent = new ArrayList<>();
        final Map<Integer, String> replies = new HashMap<>(IDLE);
        boolean keyOn = true, dongleAnswers = true;
        String codes = "85 f1 10 58 01 15 25 34 4d";    // Read DTC as captured: one code, P1525
        String clearReply = "83 f1 10 54 ff 00 d7";     // Clear DTC as captured

        @Override public void write(byte[] f) {
            int seq = ((f[2] & 0xff) << 8) | (f[3] & 0xff);
            int innerLen = ((f[9] & 0xff) << 8) | (f[10] & 0xff);
            byte[] inner = Arrays.copyOfRange(f, 11, 11 + innerLen);
            String hex = Hex.str(inner);
            sent.add(hex);
            if (!dongleAnswers) return;
            reply(seq, "00 00 02 00 00");
            if (!Protocols.wantsReply(inner)) return;
            if (hex.startsWith("61 05")) { reply(seq, keyOn ? "01 00 02 ef 8f" : "01 ff ff"); return; }
            String r = null;
            if (!keyOn) r = null;
            else if (hex.startsWith("61 04 00 06 82 10 f1 21")) r = replies.get(inner[8] & 0xff);
            else if (hex.equals("61 04 00 08 84 10 f1 18 00 ff 00 9c ff")) r = codes;
            else if (hex.equals("61 04 00 07 83 10 f1 14 ff 00 97 01")) {
                r = clearReply;
                if (r != null && r.contains(" 54 ff 00")) codes = "82 f1 10 58 00 db";
            }
            reply(seq, r == null ? "01 ff ff" : String.format("01 00 %02x %s", Hex.bytes(r).length, r));
        }
        void reply(int seq, String payload) {
            byte[] r = DongleSessionTest.rx(seq, payload);
            session.onBytes(r, r.length);
        }
        @Override public void close() { }

        List<Integer> groupsRead() {
            List<Integer> g = new ArrayList<>();
            for (String s : sent) if (s.startsWith("61 04 00 06 82 10 f1 21")) g.add(Integer.parseInt(s.substring(24, 26), 16));
            return g;
        }
    }

    static final class Rig {
        final Cayenne car = new Cayenne();
        final LiveValues values = new LiveValues();
        final long[] now = {0};
        final KwpRunner runner;
        Rig(String... active) {
            PorscheCatalog.newDrive();                      // the held peak belongs to the app, not to one rig
            car.session = new DongleSession(car);
            runner = new KwpRunner(car.session, values, () -> now[0]);
            runner.setTimeouts(30, 30, 30);
            runner.setActive(Arrays.asList(active));
        }
        void steps(int n) throws IOException { for (int i = 0; i < n; i++) runner.step(); }
        double get(String id) { return values.get(id, now[0]); }
    }

    static String withSum(String body) {
        int sum = 0;
        for (byte b : Hex.bytes(body)) sum += b & 0xff;
        return body + String.format(" %02x", sum & 0xff);
    }

    public static void aGroupRequestCarriesItsChecksum() {
        T.eq("61 04 00 06 82 10 f1 21 0a ae 01", Hex.str(KwpBlocks.request(0x0a)), "as the vendor app sent it");
        T.eq("61 04 00 06 82 10 f1 21 3b df 01", Hex.str(KwpBlocks.request(0x3b)), "and another");
    }

    public static void aCapturedReplyDecodes() {
        KwpBlocks.Block b = KwpBlocks.parse(0x0a, Hex.bytes(IDLE.get(0x0a)));
        T.ok(b != null, "understood");
        T.near(920, b.v(0, 1), 1e-6, "engine speed, as the vendor app showed");
        T.near(13.65, b.v(1, 21), 1e-6, "battery volts");
        T.ok(Double.isNaN(b.v(0, 5)), "asking for the wrong kind of value gives nothing");
        T.ok(Double.isNaN(b.v(9, 1)), "nor does a slot that is not there");
    }

    public static void damagedOrForeignRepliesAreRefused() {
        byte[] good = Hex.bytes(IDLE.get(0x0a));
        byte[] bad = good.clone();
        bad[7] ^= 1;
        T.ok(KwpBlocks.parse(0x0a, bad) == null, "checksum does not add up");
        T.ok(KwpBlocks.parse(0x0b, good) == null, "an answer for another group");
        T.ok(KwpBlocks.parse(0x0a, Hex.bytes(withSum("83 f1 10 7f 21 12"))) == null, "the computer said no");
        T.ok(KwpBlocks.parse(0x0a, null) == null && KwpBlocks.parse(0x0a, new byte[3]) == null, "nothing useful at all");
    }

    public static void theCapturedIdleMatchesTheVendorApp() throws Exception {
        Rig r = new Rig(ALL);
        r.steps(1 + 9);
        T.near(920, r.get("rpm"), 1e-6, "Engine Speed 920.0");
        T.near(0, r.get("speed"), 1e-6, "Speed 0.00");
        T.near(104.0, r.get("coolant"), 1e-6, "Engine Temperature 104.00");
        T.near(75.2, r.get("iat"), 1e-6, "Intake Air Temperature 75.20");
        T.near(-6, r.get("cam_r"), 1e-6, "Camshaft, right -6.00");
        T.near(-5, r.get("cam_l"), 1e-6, "Camshaft, left -5.00");
        T.near(98.6, r.get("oil_temp"), 1e-6, "Engine Oil Temperature 98.60");
        T.near(14.50, r.get("boost_set"), 0.01, "Standard Boost Pressure 14.50");
        T.near((380 - 1000) * 0.0145038, r.get("boost"), 0.01, "manifold below outside air at idle: vacuum");
        Rig more = new Rig("boost_act", "batt_v");
        more.steps(3);
        T.near(980 * 0.0145038, more.get("boost_act"), 0.01, "the boost sensor reads outside air at idle");
        T.near(13.65, more.get("batt_v"), 1e-6, "charging");
    }

    public static void aValueOfAnUnexpectedKindStaysBlank() throws Exception {
        Rig r = new Rig("rpm", "speed");
        // the computer reports slot 0 as a temperature instead of an engine speed
        r.car.replies.put(0x0a, withSum("9a f1 10 61 0a 05 c8 17 15 46 c3 07 7d 00 14 64 7e 05 32 68 19 12 07 05 32 82 21 85 1d"));
        r.steps(3);
        T.ok(r.values.get("rpm", 0) == null, "never a wrong number");
        T.near(0, r.get("speed"), 1e-6, "the rest of the group still reads");
    }

    public static void wakeUpFollowsTheCapturedSequence() throws Exception {
        Rig r = new Rig("rpm");
        T.eq(KwpRunner.Kind.SETUP, r.runner.step(), "first the line is set up");
        List<String> want = new ArrayList<>();
        want.add(Protocols.STOP_KEEPALIVE);
        want.addAll(Arrays.asList(Protocols.KWP_SETUP_PRE));
        want.add(Protocols.KWP_INIT);
        want.addAll(Arrays.asList(Protocols.KWP_SETUP_POST));
        T.eq(want, r.car.sent, "stop a leftover keep-alive, line setup, wake-up, keep-alive on");
        r.car.sent.clear();
        T.eq(KwpRunner.Kind.READ, r.runner.step(), "then reads");
        T.eq(Arrays.asList("61 04 00 06 82 10 f1 21 0a ae 01"), r.car.sent, "the group holding engine speed");
        T.eq(1, r.runner.goodReads(), "one reading counted");
        T.ok(r.runner.vehicleAnswering(), "the Porsche is answering");
    }

    public static void onlyTheGroupsOnScreenAreRead() throws Exception {
        Rig r = new Rig("coolant", "oil_temp");
        r.steps(7);
        T.eq(Arrays.asList(0x0b, 0x16, 0x0b, 0x16, 0x0b, 0x16), r.car.groupsRead(), "two groups, turn about");
        T.ok(r.values.get("rpm", 0) == null, "values nobody asked for are not published");
    }

    public static void engineSpeedAndBoostAreReadMostOften() throws Exception {
        Rig r = new Rig(ALL);
        r.steps(1 + 22);
        List<Integer> read = r.car.groupsRead();
        int[] count = new int[256];
        for (int g : read) count[g]++;
        T.eq(8, count[0x0a], "engine speed four times a cycle");
        T.eq(8, count[0x16], "boost four times a cycle");
        T.eq(2, count[0x0b], "temperatures once");
        T.eq(2, count[0x14], "camshaft angles once");
        T.eq(2, count[0x3b], "boost target once");
        for (int i = 1; i < read.size(); i++) T.ok(!read.get(i).equals(read.get(i - 1)), "never the same group twice running: " + read);
    }

    public static void keyOffBlanksTheScreenAndKeepsTrying() throws Exception {
        Rig r = new Rig("rpm");
        r.steps(2);
        T.near(920, r.get("rpm"), 1e-6, "live");
        r.car.keyOn = false;
        r.steps(3);
        T.ok(!r.runner.vehicleAnswering(), "three reads with no answer: key off");
        T.ok(r.values.get("rpm", r.now[0]) == null, "old readings come off the screen");
        T.eq(KwpRunner.Kind.SETUP, r.runner.step(), "tries to wake the line again");
        T.eq(KwpRunner.Kind.IDLE, r.runner.step(), "then waits before the next try");
        r.car.keyOn = true;
        r.now[0] += KwpRunner.RETRY_WAKE_MS;
        T.eq(KwpRunner.Kind.SETUP, r.runner.step(), "key back on: wakes up");
        T.eq(KwpRunner.Kind.READ, r.runner.step(), "and reads");
        T.ok(r.runner.vehicleAnswering(), "answering again");
        T.near(920, r.get("rpm"), 1e-6, "readings are back");
    }

    public static void nothingIsReadWithAnEmptyScreen() throws Exception {
        Rig r = new Rig();
        T.eq(KwpRunner.Kind.IDLE, r.runner.step(), "no gauges, no traffic");
        T.ok(r.car.sent.isEmpty(), "not even a wake-up");
    }

    public static void aDeadDongleIsReportedAsALostLink() {
        Rig r = new Rig("rpm");
        r.car.dongleAnswers = false;
        boolean lost = false;
        try {
            r.steps(4);
        } catch (IOException e) {
            lost = true;
        }
        T.ok(lost, "the service can reconnect");
    }

    public static void everythingSentIsOnTheReadOnlyList() throws Exception {
        Rig r = new Rig(ALL);
        r.steps(12);
        r.car.keyOn = false;
        r.steps(6);
        r.runner.leave();
        for (String s : r.car.sent) T.ok(Protocols.allowed(Hex.bytes(s)), "allowed: " + s);
        T.eq(Protocols.STOP_KEEPALIVE, r.car.sent.get(r.car.sent.size() - 1), "leaving turns the keep-alive off");
    }

    public static void theReadOnlyListRefusesPorscheWrites() {
        T.ok(Protocols.allowed(KwpBlocks.request(0x0a)), "a group read is fine");
        String[] refused = {
                withSum("61 04 00 07 83 10 f1 14 ff 00") + " 01",      // clear fault codes
                withSum("61 04 00 07 83 10 f1 30 01 07") + " 01",      // drive an output
                withSum("61 04 00 06 82 10 f1 31 01") + " 01",         // start a routine
                withSum("61 04 00 07 83 10 f1 3b 0a 00") + " 01",      // write a value
                withSum("61 04 00 06 82 10 f1 10 89") + " 01",         // open another diagnostic session
                withSum("61 04 00 06 82 11 f1 21 0a") + " 01",         // a different computer
                "61 04 00 06 82 10 f1 21 0a 00 01",                    // wrong checksum
        };
        for (String s : refused) T.ok(!Protocols.allowed(Hex.bytes(s)), "refused: " + s);
    }

    public static void everyPorscheValueHasAGaugeRange() {
        T.eq(38, PorscheCatalog.all().size(), "the nine recorded values, the boost sensor, battery volts, peak boost,"
                + " the sixteen from the warm-idle capture, the six worked out by the screen itself and the four fuel gauges");
        for (Param p : PorscheCatalog.all()) T.ok(!p.unit.isEmpty(), p.id + " has a unit to show in the gauge picker");
        for (Param p : PorscheCatalog.all()) {
            T.ok(p.max > p.min, p.id + " has a scale");
            T.ok(PorscheCatalog.byId(p.id) == p, p.id + " can be looked up");
        }
        T.ok(PorscheCatalog.byId("map_v") == null, "Jeep-only values are not offered");
    }

    /** Group 16 as captured at idle, with the manifold pressure changed (250 x b x 0.04 mbar). */
    static String group16(int b) {
        return withSum(String.format("9a f1 10 61 16 0f 29 04 19 f9 06 01 32 5f 12 fa %02x 12 7d c8 19 b6 03 1a 3c 61 21 85 1c", b));
    }

    public static void peakBoostHoldsTheHighestBoostOfTheDrive() throws Exception {
        Rig r = new Rig("boost_peak");
        r.steps(2);
        T.near(0, r.get("boost_peak"), 1e-9, "vacuum at idle is not boost: the peak starts at zero");
        T.eq(Arrays.asList(0x16), r.car.groupsRead(), "its group is read without the boost gauge on screen");
        r.car.replies.put(0x16, group16(180));              // 1800 mbar in the manifold, 1000 outside
        r.steps(1);
        T.near(800 * 0.0145038, r.get("boost_peak"), 1e-6, "11.6 psi of boost");
        r.car.replies.put(0x16, group16(150));              // easing off: 1500 mbar
        r.steps(1);
        T.near(800 * 0.0145038, r.get("boost_peak"), 1e-6, "the peak is held");
        r.car.replies.put(0x16, group16(190));
        r.steps(1);
        T.near(900 * 0.0145038, r.get("boost_peak"), 1e-6, "a higher one replaces it");
    }

    public static void peakBoostStartsAgainAfterTheKeyIsTurnedOff() throws Exception {
        Rig r = new Rig("boost_peak");
        r.car.replies.put(0x16, group16(180));
        r.steps(2);
        T.near(800 * 0.0145038, r.get("boost_peak"), 1e-6, "a peak from this drive");
        r.car.keyOn = false;
        r.steps(4);                                          // three misses, then a wake-up that fails
        r.car.keyOn = true;
        r.car.replies.put(0x16, group16(38));               // idle again
        r.now[0] += KwpRunner.RETRY_WAKE_MS;
        r.steps(2);
        T.near(0, r.get("boost_peak"), 1e-9, "a new drive starts from zero");
    }

    public static void aPeakIsNotInventedFromADamagedReply() throws Exception {
        Rig r = new Rig("boost_peak");
        r.car.replies.put(0x16, withSum("9a f1 10 61 16 0f 29 04 19 f9 06 01 32 5f 05 fa 26 12 7d c8 19 b6 03 1a 3c 61 21 85 1c"));
        r.steps(2);
        T.ok(r.values.get("boost_peak", r.now[0]) == null, "manifold slot is not a pressure: nothing shown");
    }

    public static void theScreensOwnValuesAreOfferedAndNeverAskedOfTheCar() throws Exception {
        for (String id : new String[]{"zero_sixty", "g_accel", "gps_mph", "g_force", "speedo_err"}) {
            Param p = PorscheCatalog.byId(id);
            T.ok(p != null && p.tablet, id + " is offered and comes from the screen itself");
            T.ok(PorscheCatalog.value(id) == null, id + " has no group to read");
        }
        T.ok(!PorscheCatalog.byId("boost_peak").tablet && PorscheCatalog.value("boost_peak").group == 0x16, "peak boost is read from the car");
        Rig r = new Rig("zero_sixty", "g_accel", "gps_mph");
        T.eq(KwpRunner.Kind.IDLE, r.runner.step(), "nothing to ask the car for");
        T.eq(0, r.car.sent.size(), "nothing sent");
        Rig withSpeed = new Rig("zero_sixty", "speed");
        withSpeed.steps(2);
        T.eq(Arrays.asList(0x0a), withSpeed.car.groupsRead(), "only the road speed's group is read for the timer");
    }

    public static void choosingThePorscheSwapsTheWholeCatalog() {
        try {
            Catalog.useVehicle("porsche");
            T.ok(Catalog.porsche(), "set");
            T.ok(Catalog.byId("oil_temp") != null && Catalog.byId("map_v") == null, "Porsche values only");
            T.eq(PorscheCatalog.all().size(), Catalog.all().size(), "the whole list");
        } finally {
            Catalog.useVehicle("jeep");
        }
        T.ok(!Catalog.porsche() && Catalog.byId("map_v") != null && Catalog.byId("oil_temp") == null, "and back to the Jeep");
    }

    public static void thePorscheScreenStartsWithTheRecordedGauges() {
        java.util.Set<String> want = new java.util.HashSet<>(Arrays.asList(
                "boost", "rpm", "speed", "coolant", "oil_temp", "iat", "cam_r", "cam_l", "boost_set"));
        for (String look : new String[]{"p_retro", "p_gt"}) {
            Layout l = LayoutCodec.defaultFor(look, 1024, 600);
            T.eq(want, new java.util.HashSet<>(l.activeParamIds()), look + ": the eight recorded values plus real boost");
            T.eq(9, l.gauges.size(), look + ": nine gauges");
            for (GaugeSpec g : l.gauges)
                T.ok(g.x >= 0 && g.y >= 0 && g.x + g.w <= 1024 && g.y + g.h <= 600, look + " " + g.key + " fits the head unit");
        }
    }

    /** Replies from the second capture (2026-11-22, warm idle), each one where the vendor app's screen showed the value named. */
    static final Map<Integer, String> WARM = new HashMap<>();
    static {
        WARM.put(0x0a, "9a f1 10 61 0a 01 c8 11 15 46 c2 07 7d 00 14 64 7e 05 32 b0 19 0a 05 05 32 aa 21 85 1c 29");
        WARM.put(0x0b, "9a f1 10 61 0b 21 ff 05 05 0a b4 05 0a 7d 1b 4b 83 14 40 85 21 ff 16 3c ff ff 3e 64 1d 6c");
        WARM.put(0x0e, "9a f1 10 61 0e 21 ff 0b 21 ff 0b 21 ff 0c 21 ff 00 21 ff 00 25 00 00 25 00 00 25 00 00 3b");
        WARM.put(0x0f, "9a f1 10 61 0f 0b 4e 80 58 0a 01 3d ff 7f 14 64 7c 0b 4e c0 0b 4e 80 36 00 00 21 ff 00 3e");
        WARM.put(0x12, "9a f1 10 61 12 0b 4e 80 58 0a 01 3d ff 80 14 64 80 0b 4e c0 0b 4e 80 36 00 00 25 00 00 4b");
        WARM.put(0x14, "9a f1 10 61 14 36 00 07 22 64 7a 22 64 7a 14 19 7e 14 19 88 14 19 7e 25 00 00 25 00 00 a2");
        WARM.put(0x16, "9a f1 10 61 16 0f 29 03 19 18 06 01 32 59 12 fa 25 12 7d c8 19 11 03 1a 3c 88 21 85 1a 63");
        WARM.put(0x17, "9a f1 10 61 17 21 ff 05 22 64 80 22 64 80 36 00 80 05 0a 7a 36 01 34 36 01 e8 36 02 9a df");
        WARM.put(0x34, "9a f1 10 61 34 25 00 c1 25 00 70 12 7d 61 25 00 e4 36 26 84 36 00 05 12 7d 00 12 19 03 7c");
    }
    static final String[] MORE = {"egt_pre", "egt_post", "load", "throttle", "timing", "torque", "pedal", "lambda_r",
            "lambda_l", "trim_1", "trim_2", "inj_ms", "maf", "outside_temp", "brake_vac", "gear_ecu"};

    public static void theWarmIdleCaptureMatchesTheVendorApp() throws Exception {
        Rig r = new Rig(MORE);
        r.car.replies.putAll(WARM);
        r.steps(1 + 40);
        T.near(662, r.get("egt_pre"), 1e-6, "Exhaust Gas Temperature Before Catalytic Converter 662.00");
        T.near(716, r.get("egt_post"), 1e-6, "Exhaust Temperature After Three-Way Catalytic Converter 716.00");
        T.near(21.05, r.get("load"), 0.005, "Engine Load 21.05");
        T.near(1.96, r.get("throttle"), 0.005, "Throttle Angle, Exact Value 1.96");
        T.near(2.25, r.get("timing"), 1e-6, "Ignition Angle 2.25");
        T.near(8.63, r.get("torque"), 0.005, "Actual Engine Torque 8.63");
        T.near(4.31, r.get("pedal"), 0.005, "Pedal Value 4.31");
        T.near(1.000, r.get("lambda_r"), 1e-6, "Actual Lambda Value Right 1.000");
        T.near(1.000, r.get("lambda_l"), 1e-6, "Actual Lambda Value Left 1.000");
        T.near(-3.12, r.get("trim_1"), 0.006, "Fuel Trimmed Average Bank 1 -3.12");
        T.near(0, r.get("trim_2"), 1e-6, "Fuel Trimmed Average Bank 2 0.00");
        T.near(1.23, r.get("inj_ms"), 1e-6, "Injection Time 1.23");
        T.near(68.64, r.get("maf"), 1e-6, "Total Mass Air Flow (MAF) 68.64 lb/h");
        T.near(71.6, r.get("outside_temp"), 1e-6, "Ambient Temperature 71.60");
        T.near(7.03, r.get("brake_vac"), 0.005, "Pressure, Brake Booster Pressure Sensor 7.03 psi");
        T.near(7, r.get("gear_ecu"), 1e-9, "Selected Gear, as the computer numbers it (0 in park, as on the vendor screen)");
    }

    public static void theOtherAirFlowReadingUsesTheSameScale() {
        // "Mass Air Flow (Hot Mode MAF And Tank Vents) 56.98 lb/h" in group 0a: the one scale that fits both screens
        KwpBlocks.Block b = KwpBlocks.parse(0x0a, Hex.bytes("9a f1 10 61 0a 01 c8 10 15 46 c2 07 7d 00 14 64 7e 05 32 ae 19 0f 05 05 32 a8 21 85 1d 2a"));
        T.near(56.98, b.v(5, KwpBlocks.AIR_LB_H), 1e-6, "1295 counts");
    }

    public static void aShareOfNothingStaysBlank() {
        KwpBlocks.Block b = KwpBlocks.parse(0x0e, Hex.bytes(withSum("9a f1 10 61 0e 21 00 0b 21 ff 0b 21 ff 0c 21 ff 00 21 ff 00 25 00 00 25 00 00 25 00 00")));
        T.ok(Double.isNaN(b.v(0, KwpBlocks.SHARE)), "no full-scale figure: no reading, not a division by zero");
        T.near(4.31, b.v(1, KwpBlocks.SHARE), 0.005, "the next slot still reads");
    }
}
