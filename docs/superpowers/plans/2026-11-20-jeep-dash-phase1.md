# Jeep Dash Phase 1 (core dash) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Jeep Link Test app with the Phase 1 Jeep Dash: live gauges from the GearWrench dongle in two switchable looks, free-placement editing, per-gauge warning flash, drive logging and an update check.

**Architecture:** A plain-Java `core` module (no Android imports) holds everything testable: XTOOL framing, request/response session, vehicle protocols, parameter catalog, poll scheduler, layout model and codec, CSV log writer. The Android `app` module is a thin shell: a foreground `LinkService` that runs the core against a Bluetooth socket, and a `DashActivity` whose `DashView` draws gauges through a `Theme` (XP or Jeep) and handles edit mode.

**Tech Stack:** Java 17, Android framework only (no AndroidX, no third-party libraries), Gradle 8.11.1 + AGP 8.7.3, GitHub Actions for builds. Core tests use an in-repo assert harness so they run locally with plain `javac`/`java` (this workspace cannot download Android or Maven artifacts).

**Spec:** `docs/superpowers/specs/2026-11-20-jeep-dash-design.md` (protocol facts in `docs/protocol-notes.md`)

## Global Constraints

- The dash **never writes to the engine computer**: only the dongle setup commands (`60 xx`, `61 05`, `61 06`) and read requests (`61 04` carrying generic Mode 01 or SCI `F4`/`10 01`) listed in `protocol-notes.md`. No actuation, reset or code-clearing messages anywhere.
- `core` imports nothing from `android.*`. `app` uses only the Android framework.
- Package `com.strickland.jeepdash` (app) and `com.strickland.jeepdash.core` (core); applicationId `com.strickland.jeepdash` (the Link Test app keeps its own id and stays installable).
- minSdk 26, compileSdk/targetSdk 35. Tablet: Lenovo TB361FU, Android 16, ≈1280×800 dp, landscape only, screen kept on while the dash shows. US units (°F, mph).
- SCI is the primary source; a generic-only value is polled in occasional blocks and marked "slow" in the gauge picker.
- Workflow files (`.github/workflows/*`) cannot be pushed by Claude's GitHub connector; workflow changes go to Daniel as one pre-filled GitHub link he commits.
- Release tag `dash-<run_number>`, asset `jeep-dash.apk`, signed with the committed key `app/jeepdash.keystore` so updates install over older builds.
- Visuals are original designs in the era's spirit (no Microsoft wallpaper/logos, no Jeep logo artwork). Approved mockups: https://claude.ai/artifact/NE9gwCrkCpk2JQvnmtoeDz

## Review Focus

1. **Dongle drops mid-drive** (engine off, tablet carried away, Bluetooth hiccup): pending requests fail fast, the screen shows "Reconnecting", nothing freezes, and it reconnects on its own when the dongle is back. Pinned in Task 2 (`closeFailsPending`) and Task 7 (manual).
2. **Key on, engine computer silent** (replies `01 ff ff`, or nothing): gauges show "—" after 3 s instead of a frozen last value. Pinned in Task 3 (`staleAfterThreeSeconds`).
3. **Dongle busy** (GearWrench app or the iPhone holding it): connect fails with a plain message naming both, and retries. Pinned in Task 7 (manual).
4. **Layout off-screen or tiny** (saved on another size, dragged past the edge): clamped back on load and during edits. Pinned in Task 5 (`decodeClampsOutOfBounds`, `resizeRespectsMinimum`).
5. **Corrupt or old layout text**: falls back to the look's default layout, never crashes. Pinned in Task 5 (`decodeGarbageGivesDefault`).

---

## File Structure

```
settings.gradle                      include ':core', ':app'
core/build.gradle                    java-library; task runCoreTests (JavaExec, main TestMain)
core/src/main/java/com/strickland/jeepdash/core/
  Hex.java                           hex <-> bytes
  XtoolFrame.java                    build(seq, inner) + Parser (from XtoolLink)
  ByteLink.java                      interface: write(byte[]), close()
  DongleSession.java                 seq/ack/reply matching, timeouts, onBytes()
  Protocols.java                     setup sequences + request builders (ISO 9141-2, SCI)
  Param.java, Catalog.java           parameter table + decoding
  LiveValues.java                    latest value + timestamp per param, staleness
  PollScheduler.java                 which request next (SCI primary, generic blocks)
  GaugeSpec.java, Layout.java        free-placement model, edit ops, warning rule
  LayoutCodec.java                   layout <-> text, defaults per look
  DriveLogWriter.java                CSV rows to a java.io.Writer
  ReleaseTag.java                    "dash-12" -> 12, newer-than check
core/src/test/java/com/strickland/jeepdash/core/
  T.java                             tiny assert harness (assertEq, assertNear, assertTrue, run)
  TestMain.java                      runs every *Test class, exits 1 on failure
  <Name>Test.java                    one per core class
app/build.gradle                     depends on :core; signing; versionCode from GITHUB_RUN_NUMBER
app/jeepdash.keystore                committed signing key (password in build.gradle)
app/src/main/AndroidManifest.xml
app/src/main/java/com/strickland/jeepdash/
  LinkService.java                   foreground service: socket, session, poll loop, log, reconnect
  AndroidByteLink.java               ByteLink over BluetoothSocket
  DashActivity.java                  screen shell: DashView, look switch, edit, status, nav
  DashView.java                      draws layout via Theme; edit-mode gestures
  theme/Theme.java                   interface
  theme/XpTheme.java, theme/JeepTheme.java
  GaugeSettingsDialog.java           value picker (slow marked), type, warning, delete
  DriveLogStore.java                 MediaStore Downloads/JeepDash CSV files + share
  UpdateChecker.java                 GitHub latest release -> offer download
```

Delete `app/src/main/java/com/strickland/jeepdash/linktest/` once Task 7 runs on the tablet (its logic moves into core).

**Running core tests locally (every core task):**
`cd <repo> && rm -rf /tmp/core && javac -d /tmp/core $(find core/src -name '*.java') && java -cp /tmp/core com.strickland.jeepdash.core.TestMain` → prints `N passed, 0 failed`.

**Pushing:** `git commit` locally, then push the changed files with the GitHub connector's `push_files` to `main`. Every push triggers a CI build; check the Releases page (or, after Task 6, a failure issue) for the result.

---

### Task 1: Core module, test harness, XtoolFrame

**Files:**
- Create: `core/build.gradle`, `core/src/main/java/.../Hex.java`, `XtoolFrame.java`, `core/src/test/java/.../T.java`, `TestMain.java`, `XtoolFrameTest.java`
- Modify: `settings.gradle` (include `:core`)

**Interfaces:**
- Produces: `Hex.bytes(String hex) -> byte[]`, `Hex.str(byte[] b) -> String` (lowercase, space-separated); `XtoolFrame.build(int seq, byte[] inner) -> byte[]`; `XtoolFrame.Parser(Listener l)` with `push(byte[] data, int n)`; `interface Listener { void onFrame(int seq, byte[] payload, boolean checksumOk); }`; harness `T.eq(Object exp, Object act, String msg)`, `T.near(double exp, double act, double tol, String msg)`, `T.ok(boolean c, String msg)`.

- [ ] **Step 1: Write the failing tests** in `XtoolFrameTest`:
  - `buildMatchesCapture`: `Hex.str(XtoolFrame.build(1, Hex.bytes("60 cb")))` equals `"55 aa 00 01 00 05 ff fa 01 00 02 60 cb 56"`; seq 31 with inner `61 04 00 0b 08 07 df 02 01 00 00 00 00 00 00 14` equals `"55 aa 00 1f 00 13 ff ec 01 00 10 61 04 00 0b 08 07 df 02 01 00 00 00 00 00 00 14 58"`.
  - `parsesSplitAndJoinedFrames`: push `aa 55 00 2f 00 03 ff fc 01 ff ff d1` one byte at a time → one frame, seq 47, payload `01 ff ff`, checksum ok; push two frames concatenated with 3 junk bytes before them → two frames in order.
  - `flagsBadChecksum`: same frame with last byte `d2` → `checksumOk == false`.
- [ ] **Step 2: Run core tests** — expect compile failure (classes missing).
- [ ] **Step 3: Implement** `Hex`, `XtoolFrame` (port `buildFrame`/`push`/`drop` from `linktest/XtoolLink.java`), `T`, `TestMain` (explicit list of test classes; reflection over `public static void` methods whose names don't start with `_`), `core/build.gradle` (`plugins { id 'java-library' }`, Java 17, `runCoreTests` JavaExec over the test source set).
- [ ] **Step 4: Run core tests** — expect `3 passed, 0 failed`.
- [ ] **Step 5: Commit** `feat(core): xtool framing with local test harness`.

### Task 2: DongleSession (request/response)

**Files:** Create `ByteLink.java`, `DongleSession.java`, `DongleSessionTest.java` (with an in-test `FakeLink` that records writes and lets the test feed bytes back).

**Interfaces:**
- Consumes: Task 1 `XtoolFrame`.
- Produces: `interface ByteLink { void write(byte[] b) throws IOException; void close(); }`; `DongleSession(ByteLink link)`; `Result send(byte[] inner, boolean wantReply, int timeoutMs) throws IOException`; `void onBytes(byte[] b, int n)` (called by the reader thread); `void close()`; `class Result { boolean acked, timedOut, noResponse, closed; byte[] data; }`.

- [ ] **Step 1: Write the failing tests** (the fake answers from a separate thread after the write):
  - `ackOnlyCompletes`: send `60 05 19 01`, wantReply false; fake answers ack `00 00 02 00 00` with the same seq → `acked`, not `timedOut`, `data == null`.
  - `replyCarriesData`: send `61 04 00 02 f4 16 01`, wantReply true; fake answers ack then `01 00 02 16 91` → `data` equals `16 91`.
  - `noResponseFlagged`: reply `01 ff ff` → `noResponse`, `data == null`.
  - `timeoutWithoutAnswer`: no answer, 200 ms timeout → `timedOut`, returns within 400 ms.
  - `closeFailsPending`: a send waiting 5 s returns within 300 ms with `closed == true` when `close()` is called.
  - `seqIncrementsAndWraps`: seq starts at 1; after 0xffff sends the next frame uses seq 1.
- [ ] **Step 2: Run core tests** — new tests fail.
- [ ] **Step 3: Implement** `DongleSession` (port `send`/`onFrame` logic from `XtoolLink`; `close()` counts down every pending latch with `closed = true`).
- [ ] **Step 4: Run core tests** — all pass.
- [ ] **Step 5: Commit** `feat(core): dongle request/response session`.

### Task 3: Protocols, catalog, live values

**Files:** Create `Protocols.java`, `Param.java`, `Catalog.java`, `LiveValues.java`, `ProtocolsTest.java`, `CatalogTest.java`, `LiveValuesTest.java`.

**Interfaces:**
- Produces:
  - `Protocols.GENERIC_SETUP_PRE`, `GENERIC_INIT` (`61 05 1e 33 02 02 01 e0 00 00 00 05`), `GENERIC_SETUP_POST`, `SCI_SETUP`, `SCI_FAST` (`60 02 00 00 f4 24 00 08 ff`) as `String[]`/`String` exactly as in `protocol-notes.md`; `Protocols.genericRequest(int pid) -> byte[]` (inner `61 04 00 06 68 6a f1 01 <pid> <sum> 01`); `Protocols.sciRequest(int... ids) -> byte[]` (inner `61 04 00 <n+1> f4 <ids> 01`).
  - `enum Source { SCI, GENERIC }`; `Param` fields `String id, name, unit; Integer pid; int[] sciIds; double min, max; int weight` and `Double decode(Source s, byte[] reply)` (null on a malformed reply).
  - `Catalog.all() -> List<Param>`, `Catalog.byId(String) -> Param`, `Param.slow()` true when `sciIds == null`.
  - `LiveValues.put(String id, double v, long nowMs)`, `Double get(String id, long nowMs)` (null when older than 3000 ms).
- Catalog rows (id, SCI ids, generic PID, formula → °F/mph/etc., min–max, weight):
  `rpm` SCI 0A,0B raw16/8 · PID 0C (A·256+B)/4 · 0–6000 · 3;
  `coolant` SCI 16 (raw−64)°C→°F · PID 05 (A−40)°C→°F · 100–260 · 1;
  `map_v` SCI 17 raw/51 V · — · 0–5 · 2;
  `spark` SCI 2F raw ° · PID 0E A/2−64 ° · −10–40 · 2;
  `speed` — · PID 0D A km/h→mph · 0–110 · 3;
  `iat` — · PID 0F (A−40)°C→°F · 0–200 · 1;
  `throttle` — · PID 11 A·100/255 % · 0–100 · 3;
  `map_kpa` — · PID 0B A kPa · 0–105 · 2.

- [ ] **Step 1: Write the failing tests** with captured replies:
  - `genericRequestMatchesCapture`: `Hex.str(Protocols.genericRequest(0x0c))` = `"61 04 00 06 68 6a f1 01 0c d0 01"`.
  - `sciRequestMatchesCapture`: `sciRequest(0x0a, 0x0b)` = `"61 04 00 03 f4 0a 0b 01"`; `sciRequest(0x16)` = `"61 04 00 02 f4 16 01"`.
  - `decodesSci`: rpm `16 0b ce` → 729.75; coolant `16 91` → 177.8 (±0.1); map_v `17 69` → 2.06 (±0.01); spark `2f 07` → 7.
  - `decodesGeneric`: rpm `48 6b d1 41 0c 0b 40 00` → 720; throttle `48 6b d1 41 11 23 00` → 13.7 (±0.1); iat `48 6b d1 41 0f 5d 00` → 127.4 (±0.1); speed `48 6b d1 41 0d 00 00` → 0 (the trailing checksum byte is not checked).
  - `malformedReplyIsNull`: rpm SCI decode of `16` (too short) → null; generic reply whose PID byte differs → null.
  - `staleAfterThreeSeconds`: put at t=0, get at 2999 → value; at 3001 → null.
- [ ] **Step 2: Run core tests** — new tests fail.
- [ ] **Step 3: Implement** the four classes.
- [ ] **Step 4: Run core tests** — all pass.
- [ ] **Step 5: Commit** `feat(core): protocols, parameter catalog, live values`.

### Task 4: PollScheduler

**Files:** Create `PollScheduler.java`, `PollSchedulerTest.java`.

**Interfaces:**
- Consumes: `Catalog`, `Param.weight`, `Param.slow()`.
- Produces: `PollScheduler(long genericEveryMs)` (default 10000); `setActive(Collection<String> paramIds)`; `Step next(long nowMs)`; `class Step { Kind kind; Param param; }` with `enum Kind { SWITCH_TO_SCI, SWITCH_TO_GENERIC, READ_SCI, READ_GENERIC, IDLE }`; `currentBus() -> Source` (null before the first switch).
- Rules: active params with SCI ids are read over SCI in a weighted round-robin (weight 3 is read three times per cycle for every once of weight 1). If any active param is generic-only, every `genericEveryMs` the scheduler emits `SWITCH_TO_GENERIC`, one `READ_GENERIC` per generic-only param, then `SWITCH_TO_SCI`. If no active param has SCI ids, it stays on generic and reads only generic. Nothing active → `IDLE`.

- [ ] **Step 1: Write the failing tests:**
  - `sciOnlyRoundRobinByWeight`: active {rpm, coolant}; first step `SWITCH_TO_SCI`; the next 8 READ_SCI steps contain rpm 6 times and coolant 2 times.
  - `genericBlockEveryInterval`: active {rpm, speed}; first `SWITCH_TO_SCI`, then only SCI reads of rpm until t=9999; at t=10000 → `SWITCH_TO_GENERIC`, `READ_GENERIC speed`, `SWITCH_TO_SCI`, then SCI reads resume.
  - `allGenericStaysGeneric`: active {speed, throttle} → `SWITCH_TO_GENERIC` once, then alternating generic reads, never a switch back.
  - `emptyIsIdle`: no active → `IDLE`.
- [ ] **Step 2: Run core tests** — new tests fail.
- [ ] **Step 3: Implement** `PollScheduler`.
- [ ] **Step 4: Run core tests** — all pass.
- [ ] **Step 5: Commit** `feat(core): poll scheduler (SCI primary, generic blocks)`.

### Task 5: Layout model, codec, warning rule

**Files:** Create `GaugeSpec.java`, `Layout.java`, `LayoutCodec.java`, `LayoutTest.java`, `LayoutCodecTest.java`.

**Interfaces:**
- Produces: `enum GaugeType { DIAL, DIGITAL, BAR }`; `GaugeSpec` fields `String key, paramId; GaugeType type; float x, y, w, h; boolean warnOn, warnAbove; double warnLimit`; `Layout(float screenW, float screenH)` with `List<GaugeSpec> gauges`, `add(GaugeSpec)`, `remove(String key)`, `move(String key, float dx, float dy)`, `resize(String key, float dw, float dh)`, `snap(float grid)`, `clampAll()`, `activeParamIds() -> Set<String>`; `Layout.MIN_SIZE = 96`; `static boolean shouldFlash(GaugeSpec g, Double value)`; `LayoutCodec.encode(Layout) -> String` (one gauge per line: `key|paramId|TYPE|x|y|w|h|warnOn|warnAbove|warnLimit`, first line `v1`), `LayoutCodec.decode(String text, String look, float w, float h) -> Layout` (garbage → `defaultFor(look, w, h)`), `LayoutCodec.defaultFor(String look, float w, float h) -> Layout` for looks `"xp"` and `"jeep"`.
- Default layouts follow the mockups at 1280×800: XP = rpm DIAL (16,16,744,420), speed DIGITAL (776,16,488,420), coolant/iat/spark/throttle/map_v BAR across the bottom row (y 452, h 290, 5 equal columns, 16 gaps). Jeep = speed DIAL (80,80,380,380), rpm DIAL (820,80,380,380), coolant/iat/spark/map_v DIAL 150×150 in a 2×2 grid between them, throttle DIGITAL strip (330,620,620,78).

- [ ] **Step 1: Write the failing tests:**
  - `moveClampsToScreen`: gauge at (10,10,200,200) moved by (−50, 5000) → (0, 600) on 1280×800.
  - `resizeRespectsMinimum`: resize by (−500,−500) → w = h = 96; resize past the right edge → x + w = 1280.
  - `snapRoundsToGrid`: x 13 with grid 8 → 16.
  - `warningRule`: warnOn, above, limit 230: value 231 → true, 229 → false, null → false; warnOn false → false; below-limit mode flips.
  - `codecRoundTrip`: encode the default XP layout, decode → equal gauge list.
  - `decodeGarbageGivesDefault`: decode `"hello"` for `"jeep"` → equals `defaultFor("jeep", ...)`.
  - `decodeClampsOutOfBounds`: a line with x 5000 → clamped inside the screen.
  - `activeParamIds`: default XP layout → {rpm, speed, coolant, iat, spark, throttle, map_v}.
- [ ] **Step 2: Run core tests** — new tests fail.
- [ ] **Step 3: Implement** the three classes.
- [ ] **Step 4: Run core tests** — all pass.
- [ ] **Step 5: Commit** `feat(core): free-placement layout, codec, warning rule`.

### Task 6: Drive log writer, release tag, build and CI

**Files:**
- Create: `DriveLogWriter.java`, `ReleaseTag.java`, `DriveLogWriterTest.java`, `ReleaseTagTest.java`, `app/jeepdash.keystore`
- Modify: `app/build.gradle`, `.github/workflows/build-test-app.yml` (by Daniel, via link)

**Interfaces:**
- Produces: `DriveLogWriter(Writer out, List<String> paramIds)` writes header `time_ms,<ids...>` on construction; `row(long timeMs, LiveValues v)` writes one line, empty cell for null; `flush()`. `ReleaseTag.build(String tag) -> int` (`"dash-12"` → 12, anything else → −1); `ReleaseTag.isNewer(String latestTag, int myVersionCode) -> boolean`.

- [ ] **Step 1: Write the failing tests:** `csvHeaderAndRows` (ids [rpm, coolant]; row at 1000 with rpm 720 and coolant missing → `"1000,720.0,"`); `releaseTagParsing` (`dash-12`→12, `link-test-3`→−1, `isNewer("dash-12", 11)` true, `isNewer("dash-12", 12)` false).
- [ ] **Step 2: Run core tests** — fail; **Step 3: implement**; **Step 4: run** — all pass.
- [ ] **Step 5: Signing and versioning.** Generate the key locally: `keytool -genkeypair -keystore app/jeepdash.keystore -alias jeepdash -storepass jeepdash -keypass jeepdash -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=Jeep Dash"`. In `app/build.gradle`: applicationId/namespace `com.strickland.jeepdash`, `versionCode (System.getenv("GITHUB_RUN_NUMBER") ?: "1") as int`, `versionName "1.0.${versionCode}"`, a `signingConfigs.jeepdash` used by `debug`, `implementation project(':core')`.
- [ ] **Step 6: Workflow.** Write the new workflow locally and send Daniel one pre-filled edit link for `.github/workflows/build-test-app.yml`. It must: run `gradle :core:runCoreTests :app:assembleDebug`; copy the APK to `jeep-dash.apk`; publish release `dash-${{ github.run_number }}` named `Jeep Dash (build N)` (not prerelease) with that asset; and on failure (`if: failure()`, with `permissions: issues: write`) open an issue titled `Build N failed` whose body is the last 150 lines of the Gradle output (tee the build to a file). Claude reads failures through the connector's issue tools.
- [ ] **Step 7: Commit and push**; after Daniel commits the workflow, confirm release `dash-N` appears (the app is still the link test UI at this point; that's fine).

### Task 7: LinkService (Android)

**Files:** Create `AndroidByteLink.java`, `LinkService.java`, `DriveLogStore.java`; modify `AndroidManifest.xml`.

**Interfaces:**
- Consumes: `DongleSession`, `Protocols`, `PollScheduler`, `Catalog`, `LiveValues`, `DriveLogWriter`.
- Produces: `LinkService` (bound + foreground, type `connectedDevice`) with `interface Listener { void onValues(LiveValues v); void onStatus(Status s, String detail); }`, `enum Status { SEARCHING, CONNECTING, SETTING_UP, LIVE, RECONNECTING, BUSY, NO_PERMISSION }`, `addListener/removeListener`, `setActiveParams(Set<String>)`, `readsPerSecond() -> int`. `DriveLogStore.open(Context, List<String> ids) -> DriveLogWriter` (MediaStore Downloads/JeepDash/`drive-YYYYMMDD-HHMM.csv`), `DriveLogStore.list(Context) -> List<Uri>`, `share(Activity, Uri)`.
- Behavior: picks the bonded device whose name starts with `GWBT-` (else the last one chosen); connects with the secure Serial Port UUID, then insecure, then channel 4 (as the Link Test did); a reader thread feeds `DongleSession.onBytes`; the poll thread executes `PollScheduler` steps (switch steps send the setup arrays from `Protocols`; SCI reads are preceded by `SCI_FAST`); decoded values go to `LiveValues` and listeners at most every 100 ms; one log row per second while LIVE. On socket failure: status `RECONNECTING`, close the session, retry every 3 s. Connect refused while another app holds the dongle → `BUSY` with detail "Close the GearWrench app and turn off your iPhone's Bluetooth".
- Manifest: `BLUETOOTH_CONNECT`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`, `INTERNET`; service `foregroundServiceType="connectedDevice"`; activity `screenOrientation="sensorLandscape"`.

- [ ] **Step 1: Implement** the three classes and manifest entries.
- [ ] **Step 2: Push**; wait for release `dash-N` (or read the failure issue and fix).
- [ ] **Step 3: Tablet check (Daniel)** with a temporary debug text view listing live values: engine idling → rpm ≈ 700–750, coolant plausible, reads/s ≈ 3–4; unplug the dongle → `RECONNECTING` within 5 s, plug back → `LIVE` without touching the app; GearWrench app open and connected → `BUSY` message; Drive log CSV appears in Downloads/JeepDash.
- [ ] **Step 4: Commit** `feat(app): link service with reconnect and drive logging`.

### Task 8: Themes and DashView (display)

**Files:** Create `theme/Theme.java`, `theme/XpTheme.java`, `theme/JeepTheme.java`, `DashView.java`, `DashActivity.java`; delete `linktest/`.

**Interfaces:**
- Consumes: `Layout`, `GaugeSpec`, `Catalog`, `LiveValues`, `Layout.shouldFlash`, `LinkService.Listener`.
- Produces: `interface Theme { void drawBackground(Canvas c, int w, int h); void drawGauge(Canvas c, GaugeSpec g, Param p, Double value, boolean flashOn, boolean editing); void drawChrome(Canvas c, int w, int h, Status s, int readsPerSec); }`; `DashView.setLayout(Layout)`, `setTheme(Theme)`, `setValues(LiveValues)`, `setEditing(boolean)`; `DashActivity` stores the current look (`"xp"`/`"jeep"`) and each look's layout text in `SharedPreferences` `dash`.
- Drawing follows the mockups: XP = windows with title bars (scheme Blue default; Olive/Silver choice in a settings menu), XP-era segmented green bars, taskbar with **Dash**, Gauges/Visualizer/Drive log, connection light, clock and a **Jeep style** switch; Jeep = black cluster, backlight white/green/amber (setting), orange needles, green readout strip, **XP STYLE** switch. A null value draws "—". Warning flash toggles the gauge's highlight at 2 Hz. The Visualizer tab shows "Coming in the next update".

- [ ] **Step 1: Implement** the theme interface, both themes, `DashView` (redraw on new values, max 30 fps) and `DashActivity` (binds `LinkService`, look switch persists, keeps screen on).
- [ ] **Step 2: Push**; wait for the release.
- [ ] **Step 3: Tablet check (Daniel):** both looks match the mockups closely enough; the switch flips instantly and survives an app restart; live needles move with revs; dongle unplugged → "Reconnecting" in the chrome, values go to "—" within 3 s.
- [ ] **Step 4: Commit** `feat(app): XP and classic Jeep dash looks`.

### Task 9: Edit mode, gauge settings, logs screen, update check

**Files:** Create `GaugeSettingsDialog.java`, `UpdateChecker.java`; modify `DashView.java`, `DashActivity.java`.

**Interfaces:**
- Consumes: `Layout.move/resize/snap/add/remove`, `LayoutCodec`, `Catalog`, `DriveLogStore`, `ReleaseTag`.
- Produces: edit mode entered only by the **Edit** button (XP: taskbar; Jeep: a small pencil next to the style switch); while editing, drag a gauge body to move, drag its corner handle to resize, tap to open `GaugeSettingsDialog` (value list with "(slow)" after generic-only params, type DIAL/DIGITAL/BAR, warning on/off + limit + above/below, Delete), **+ Add gauge**, **Snap** toggle (grid 16), **Done** saves via `LayoutCodec` and pushes `activeParamIds()` to `LinkService.setActiveParams`. Drive log tab lists CSVs (newest first) with a Share button each. `UpdateChecker.check(Activity)` on launch when online: GET `https://api.github.com/repos/danielrstrickland-cell/jeep-dash/releases/latest`, if `ReleaseTag.isNewer(tag, BuildConfig.VERSION_CODE)` show "Update available (build N)" → opens the `jeep-dash.apk` asset URL in the browser. Offline or any error → silent.

- [ ] **Step 1: Implement** edit gestures, dialog, logs tab and update check.
- [ ] **Step 2: Push**; wait for the release.
- [ ] **Step 3: Tablet check (Daniel):** move/resize/add/delete gauges in both looks; layouts are separate per look and survive restarts; a gauge can't be dragged off screen or shrunk below a thumb's width; set coolant warning above 180 °F and watch it flash once warm; share a drive log; install the next build over this one via the update prompt (proves the fixed signing key).
- [ ] **Step 4: Commit** `feat(app): free-placement editing, logs, update check`.

### Task 10 (blocked on Daniel's capture): Map more Jeep values

**Files:** Modify `Catalog.java`, `CatalogTest.java`.

- [ ] **Step 1 (Daniel):** iPhone Bluetooth capture as before, with the GearWrench app on the Jeep live-data list, scrolling through all 95 values with a screenshot of each page.
- [ ] **Step 2:** Match each on-screen value to its `F4` id and formula from the capture; add rows (speed, throttle and intake air first, so they stop being "slow"); add a decode test per new row with the captured bytes.
- [ ] **Step 3: Run core tests**, push, tablet check that the new gauges read like the GearWrench app, commit `feat(core): map more SCI values`.
