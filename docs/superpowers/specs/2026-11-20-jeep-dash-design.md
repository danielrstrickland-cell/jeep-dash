# Jeep Dash — design

Date: 2026-11-20 · Owner: Daniel Strickland · Status: approved by Daniel 2026-11-20

## Intent

A customizable digital dash for Daniel's 1997 Jeep Grand Cherokee (ZJ body, swapped 1998 5.9 Limited
engine and JTEC engine computer), running on his Lenovo Idea Tab Plus (Android, 12.1") as an extra screen
**next to** the factory gauges when the tablet is in the Jeep. It reads live engine data through the
GearWrench GWSMARTBT Bluetooth dongle he already owns (no new hardware), looks period-correct, and doubles
as a music visualizer for Spotify playing from the tablet into his Kenwood KMM-BT728HD.

Why: he wants to see what his swapped 5.9 is doing, in a look he chose, and later have the dash learn the
Jeep's normal behavior and warn him when something drifts before a code ever sets.

## Goals

1. Live gauges from the GearWrench dongle: generic OBD2 values and the 5.9's own (Chrysler SCI) values.
2. Two looks with a one-tap switch: **XP style** (gauges as XP-era windows) and **Classic Jeep** (black
   late-'90s cluster). Mockups approved: https://claude.ai/artifact/NE9gwCrkCpk2JQvnmtoeDz
3. **Free placement**: drag and resize any gauge anywhere, add/remove gauges, choose what each one shows.
   Each look keeps its own layout. In XP style gauges behave like movable windows.
4. Drive logging on the tablet, exportable as a file.
5. Visualizer screen with three styles, one-tap swap: **spectrum bars** (up to 32 bands, falling peak
   caps), **ambient swirls**, **scope** (true waveform with trigger, plus X-Y/Lissajous mode).
6. Works offline. The only online feature: checking for app updates.

## Non-goals

- Replacing the factory cluster (turn signals, high beams and other lamps are not on the engine computer).
- iPhone support, Play Store distribution, accounts, cloud sync.
- Tuning, flashing, launch control or 2-step. Those need the engine computer's own code (e.g. CalibraX
  with a J2534 cable) or an ignition box. Separate project.

## Anti-goals (failure even if it "works")

- **Never writes to the engine computer, with one exception** added on 2026-11-22 at the owner's request:
  clearing the Jeep's stored codes from the Codes screen, after a confirmation that lists them and with the
  engine stopped (docs/protocol-notes.md). No actuation tests, resets of learned values or anything else
  that changes how the engine runs.
- Not distracting: big readable values; layout editing only in an explicit edit mode, never by accident.
- Not fragile: dropping the Bluetooth link must not crash or freeze the screen; it reconnects on its own.

## Constraints (verified)

- **Dongle**: GearWrench GWSMARTBT is XTOOL hardware. Bluetooth Classic, standard Serial Port service.
  Protocol fully decoded and verified on the tablet by the Jeep Link Test app (2026-11-20): framing,
  checksum, setup sequences and replies. See `docs/protocol-notes.md`.
- **Vehicle protocols**: generic OBD2 is **ISO 9141-2** (not J1850 VPW). The 5.9's own data is
  **Chrysler SCI** (F4 parameter reads). Both are slow: ~3–4 reads per second in total, shared by every
  value on screen.
- **Confirmed SCI values** so far: RPM (F4 0A/0B, raw/8), coolant (F4 16, raw−64 °C), MAP volts
  (F4 17, raw/51), spark advance (F4 2F). The GearWrench app lists 95 Jeep values; the rest are not yet
  mapped (see Open items).
- **Browser route is dead**: Chrome's Web Serial on the tablet listed no Bluetooth devices. Native app only.
- **Build**: GitHub Actions in `danielrstrickland-cell/jeep-dash` builds the APK and publishes a GitHub
  Release. Claude can push code through the GitHub connector but **cannot change workflow files**
  (no "workflows" permission); Daniel commits those via a pre-filled link. Design the workflow to rarely change.
- **Tablet**: Lenovo TB361FU, Android 16 (SDK 36), 2560×1600 (≈1280×800 dp), landscape.
- **Audio**: Spotify plays on the tablet. Android's playback-capture API can read other apps' audio after
  the user approves a capture prompt; a recent report says Spotify currently allows it. Unverified on this tablet.

## Approaches considered

| Approach | Trade-off | Verdict |
|---|---|---|
| **Native Android app** (Java, Android framework only) | Direct Bluetooth serial, background service, real audio capture; must build on GitHub | **Chosen** |
| Web app in Chrome (Web Serial) | No install, instant updates; Chrome sees no Bluetooth serial devices on this tablet, and web pages can't hear Spotify | Ruled out by testing |
| Off-the-shelf (RealDash etc.) with an open ELM327 adapter | Polished, but needs new hardware and can't talk to the GearWrench dongle | Ruled out by Daniel (no new dongle) |

## The design

### Architecture (Claude's call)
One Android app, Java, no third-party libraries (same toolchain as the working test app).

- **Link service** (foreground service, "connected device" type): owns the Bluetooth connection, the XTOOL
  framing (reuse `XtoolLink` from the test app), protocol setup (ISO 9141-2 and SCI), and auto-reconnect.
  Keeps polling and logging while the screen is off.
- **Poll scheduler**: polls only values that are on screen or being logged. Round-robin with weights
  (RPM and speed polled more often than temperatures).
- **SCI is the primary source** (Claude's call): both buses share the dongle, and returning to generic
  OBD2 after SCI needs the ~2.5 s ISO 9141-2 wake-up, so alternating every cycle would wreck the update
  rate. Every value is read over SCI when the catalog has an SCI id for it; a generic-only value is polled
  in occasional blocks and marked "slow" in the gauge picker. Mapping more SCI values (Open items) shrinks
  the generic-only list.
- **Parameter catalog**: a data table (JSON in the app) of every value: id, source (generic PID or SCI id),
  name, unit, formula, sensible min/max. Adding a newly mapped Jeep value = one table entry.
- **Dash screen**: custom-drawn gauges (Canvas). Gauge types: analog dial, digital readout, bar.
  Each look is a theme that draws the same gauge types differently.
- **Visualizer screen**: audio from playback capture, real FFT for bars, raw samples for the scope,
  GPU drawing (OpenGL ES) for the swirls.
- **Storage**: layouts and settings as JSON in app storage; logs as CSV files.

### Dash
- Landscape only, screen kept on while the dash or visualizer is showing; US units (°F, mph).
- Switch button on screen flips XP ↔ Classic Jeep; the app remembers the last one.
- **Edit mode** (explicit button): drag to move, corner handle to resize, tap to pick the value and gauge
  type, add/remove gauges. Leaving edit mode saves. Optional snap-to-grid.
- XP style: windows with title bars, XP-era progress bars, a taskbar with Dash / Visualizer / Drive log
  and a connection light + clock. Color schemes Blue / Olive / Silver.
- Classic Jeep: black cluster, white/green/amber backlight choice, orange needles, green readout strip.
- Status always visible: connected / reconnecting / no data, and the live reads-per-second.
- **Per-gauge warning flash** (confirmed by Daniel): a setting on each gauge, on/off plus a limit he sets;
  the gauge flashes past it. Off by default.

### Drive logging
Starts with the connection, one CSV per drive (timestamp + every polled value), stored on the tablet,
shared out with Android's share button. Logs are also the input for the later "learns your Jeep" phase.

### Visualizer
- **Spectrum bars**: 8–32 bands (Daniel picks; default 32), log-spaced frequencies, falling peak caps.
- **Ambient swirls**: flowing color clouds/ribbons that pulse with bass and loudness.
- **Scope**: triggered waveform (stable trace), X-Y mode (left vs right), green-on-black phosphor look.
- One-tap style switch; **sync-delay slider** to line visuals up with Bluetooth audio lag.
- Full screen; tap to show the style switch and slider. Android asks permission to capture audio each
  time the visualizer starts (a system rule for screen/audio capture), so starting it is one extra tap.
- All visuals are original designs in the era's spirit, not copies of Microsoft's or anyone's artwork.
- Audio source: playback capture of Spotify. Fallback if Spotify blocks it: Android's older visualizer
  hook on Spotify's audio session (needs Spotify's "Device Broadcast Status" on, lower fidelity), then the
  tablet microphone as a last resort.

### Updates
On launch (when online), check this repo's latest GitHub Release; if newer, offer to download and install it.
Release APKs are signed with a fixed key committed to the repo, so updates install over the old version.

## Phases

1. **Core dash**: link service, scheduler, catalog (confirmed values + generic PIDs), both looks, edit mode,
   logging, update check.
2. **Visualizer**: starts with a capture spike on the tablet (does Spotify allow it?), then the three styles.
3. **"Learns your Jeep" alerts**: baseline of normal values by condition (warm idle, cruise…) from logs,
   warnings when a value drifts. Needs weeks of logs first.
- Separate project, not this app: launch control / 2-step.

### Porsche codes and more values (2026-11-22)

The Codes screen works on the Cayenne too: Read DTC and Clear DTC exactly as the GearWrench app sent them to
this car. The rule of one permitted write holds per car: on the Cayenne it is that single clear frame, only
from the Codes screen, only with the engine speed read as zero. Sixteen more live values were added, each
matched against the GearWrench screen (docs/protocol-notes.md). Both cars also have a gear gauge worked out
from engine speed against road speed.

### XP look at night (2026-11-22)

The XP look has a Brightness setting next to its window colour: Light (the original, and the default),
Dark, or Auto (dark from 7 pm to 7 am by the tablet's clock, rule in core `NightHours`). Dark keeps each
scheme's character but much darker: near-black desktop, dark grey windows with light text, dark dial faces
with light ticks, dimmed LCD and bar bodies, title bars and taskbar in darkened scheme colours. Warning
flashes stay plainly red. Every colour comes from one palette with a light and a dark set. The dash redraws
at least twice a minute even with nothing changing, so Auto switches on time.

### A unit for each gauge (2026-11-22)

Each gauge can be shown in another unit where its value has one (core `Units`): pressure psi / kPa / bar,
temperature °F / °C, speed mph / km/h, air flow lb/h / g/s / kg/h, fuel gal / L, economy mpg / km/L and fuel
flow gal/h / L/h. Sensor volts are not offered as a pressure. The choice is saved with the gauge as an
optional last field of its layout line, so older layouts load unchanged and a gauge left in its own unit is
saved exactly as before. Readings, warning limits and the drive log stay in the value's own unit; the gauge
converts the reading and its scale just before drawing, and the settings show and take the limit in the
chosen unit.

### Fuel economy and fuel used (2026-11-22)

Neither engine computer reports fuel use, so the screen works it out (core `FuelUse`, formulas in
docs/protocol-notes.md): MPG now, MPG this drive, fuel used this drive and fuel flow, on both cars. "This
drive" starts when the drive log would: the engine computer answering after 5 minutes or more of silence.
A gap of more than 2 s between readings adds nothing, so a dropped link cannot invent fuel. The figures carry
a per-car factor (default 1.0) corrected at fill-ups: the dash keeps an estimate of gallons used since the
last fill-up (saved across restarts), and "Fuel: calibrate at fill-up" compares it with what the pump put in.
It refuses under 2 gal counted, changes the factor by at most half or double at a time, and keeps it between
0.3 and 3. It is only right if the dash ran for every drive in between and the tank was filled to full both
times. The counting runs in the link service, so it goes on while the visualizer or another app is in
front; the dash's gauges read the same counter. The inputs are read only while the dash's layout has a fuel
gauge or a fill-up is being counted, added to whatever list the screen in front asks for. A total is never
shown low: if fuel readings were missing for more than a tenth of the moving time (and over 10 s), "fuel used"
and "MPG this drive" go blank, and an incomplete fill-up estimate cannot be calibrated from. The Jeep on the
generic line has no injector time, so there its totals stay blank. Fuel flow reads 0-30 gal/h on the Jeep
and 0-50 on the Cayenne.

### Music button, auto-start, volume follows speed, speed correction (2026-11-23)

The new settings are off (or 0) until changed in the dash's menu; with them off nothing is added to the
screens, the manifest's new service and receiver stay switched off, and nothing more is read from the car.

- **Menu**: a short top list of groups (Car and connection, Gauges, Look, Music, Fuel, Diagnostics, App), each
  opening its own entries with the current setting beside them. Entries for one car, one look or needing the
  link are left out as before (core `DashMenu`).
- **Music button** (off / on; an old "now playing bar" setting that was on turns it on): a music-note button,
  in the XP taskbar tray beside the clock, in the button row on the other looks, and a plain MUSIC button on
  the visualizer. It opens a window with the track, the artist and previous / play-pause / next, and a close
  box; the button closes it too. One layout for every look (core `MusicLayout`), dressed by the look showing:
  XP a real window of the chosen scheme that follows Light / Dark / Auto, 80s digital its glow colour and
  lettering, Jeep and GT their own panel colours and typeface. A tap outside the window goes to the dash as
  usual, so it can stay open. It follows the music app's media session, which Android only shows to an app with
  notification access: turning the button on switches on an empty notification listener (`MediaListener`) and,
  when access is missing, says why and opens the system screen for it. It follows the playing session that
  changed most lately; with none playing, the system's first one, so a paused song still shows. No access or
  no music app shows a short message, never old text (core `NowPlaying`). With no music app open, play sends a
  media play key, which wakes the app that played last.
- **Auto-start with Bluetooth device**: the owner picks one paired device (the stereo); the dongle the link
  remembers is never offered. When it connects and no Jeep Dash screen is on view, the app opens if it may
  display over other apps, which Android requires for opening a screen from the background; otherwise it posts
  "Tap to open the dash" (core `AutoStart`). The chosen device is kept only in the app's own preferences.
- **Volume follows speed (experimental)** (off / low / medium / high): runs in the link service, so it works
  whichever screen is in front, and adds road speed to the read list while on. From 20 mph one media-volume
  step, then one more every 30 / 20 / 12 mph, at most 4 / 5 / 6 and never past full; a step comes off only 4
  mph under where it went on. A missing speed holds the level, and after 5 s goes back to the owner's level, as
  does a dropped link or turning it off. A volume the owner sets by hand stands exactly at the speed he set it;
  only later speed changes add or take off steps from there, never past full or under silent, and turned to 0
  it stays silent until he raises it (core `SpeedVolume`). Media volume steps are coarse, and whether the
  Kenwood follows the tablet's volume over Bluetooth depends on the stereo; to be tried in the Jeep.
- **Speed correction** (per car, -15% to +15%, default 0): the Jeep's speed reads about 3.6% under GPS on its
  bigger tyres. The correction is applied where readings enter `LiveValues` (a per-value scale; the runners are
  unchanged), so the speed gauge, MPG miles, the gear, the 0-60 timer, pull/braking, volume follows speed and
  the drive log's speed column all get the corrected figure. Speedo vs GPS alone keeps comparing the raw
  reading, so the speedometer's own error stays visible. The gear's learned rpm per mph is rescaled when the
  correction changes. "Set from GPS" fills in the measured difference once the tablet has a minute of GPS above
  40 mph against the car's raw speed (fixes no more than 2 s apart, the two within a quarter of each other),
  and says so plainly when it has not, or when the figure is outside the range (core `SpeedCorrection`).
- **Gear, pull/braking and 0-60 behind other screens**: these come from the car's readings alone, so the link
  service works them out (as it does fuel) and reads speed and rpm for them whenever they are on the dash's
  layout, whichever screen is in front; the gear's learned figure is still saved. While the dash is showing,
  its sensors still give them the first shove of a launch and GPS speed standing in. GPS speed, G-force and
  Speedo vs GPS need the tablet's sensors and stay with the screen; there is no background location.
- **G-force without a gyroscope**: when the tablet has no linear-acceleration sensor, the plain accelerometer is
  used and gravity taken out in software (a 2 s moving average, core `Gravity`). Which sensor is in use, or that
  none was found, is a line in the link log.
- **Visualizer**: looks change only with the LOOK button; a tap or swipe on the picture does nothing. The boom
  meters look is gone; a saved choice of it becomes Smooth (core `VizTune.CHOICES`).

### Learning alerts (2026-11-25)

The dash learns what normal looks like for each car and says so when a reading drifts away from it, trouble
code or not (core `NormalReadings`, `WarmIdle`; kept by `NormalStore`, fed by the link service once a second
whichever screen is in front).

- **What it watches, first version.** Warm idle only: stopped (speed reads 0), 450 to 1200 rpm, coolant at
  least 180 °F on the Jeep and 80 °C on the Porsche, all of it for 10 s. Jeep: manifold pressure, idle speed,
  idle air motor steps, both fuel trims, injector time, battery volts, and the front O2 as the share of
  readings that are rich (over 0.45 V). The O2 is read only every few seconds among the others, far too slowly
  to count its switching, but a slow random sample still gives the share of time rich fairly; a lean leak pulls
  it down, a rich fault up. Porsche: idle speed, battery volts, air flow, both fuel trims, injection time, load.
  Both: the coolant once settled on the move (warm for 5 minutes, 10 mph or more).
- **Reading list.** It adds engine speed to whatever the screen reads (nothing when the screen reads nothing),
  road speed and coolant only while the engine speed has been in the idle band in the last 10 s, and the idle
  values only while stopped and warm, so gauges on the move are not slowed. The coolant on the move is learned
  from what the screen reads (the dash's coolant gauge), never added for it.
- **Learning.** Silent until 7 days have passed since its first reading and 5 drives each had at least 30 s of
  warm idle. Progress shows beside the menu entry: "Normal readings (learning: drive 3 of 5, day 4 of 7)".
  Each drive's figure is the median of its readings (at least 5; 10 for the O2 share, 20 for the coolant).
  At the start of the next drive after learning, each reading's normal is frozen: the median of the drives'
  figures, and their spread (the median distance from it x 1.4826, which matches a standard deviation), so one
  odd drive barely counts. A reading with fewer than 3 drives keeps learning and freezes once it has them.
  Frozen means a slow drift is caught rather than taken in.
- **Flagging.** Outside normal means further from the median than both 3 spreads and the reading's minimum:
  manifold pressure 3 kPa, idle speed 50 rpm, idle air motor 10 steps, fuel trims 3% (each bank on the
  Porsche), injector or injection time 0.3 ms, battery 0.3 V, O2 time rich 15 points, air flow 10 lb/h, load 5%,
  coolant 8 °F. A reading is flagged when two drives in a row that had it were outside on the same side; the
  drive under way counts once it has its half minute of idle. A drive without the reading neither confirms nor
  clears; a normal drive between clears.
- **Notice.** One line where the look shows its status message (only when there is none), in the look's own
  attention colour: "Idle manifold pressure is higher than usual: 58 kPa now, normally 52", plus "(and 1 more
  in Normal readings)". Figures follow the unit the dash's gauge for that value is set to (kPa to psi, °F to
  °C, lb/h to g/s). Tapping it offers Dismiss (until the next drive) or the list.
- **Normal readings** (Diagnostics): each reading's normal range, latest figure and status; tapping one offers
  "Accept current as normal" (its latest figure becomes the median, spread kept). "Start learning again" asks
  first.
- **Memory.** One text entry per car in the app's own preferences on the tablet ("normal_readings",
  "normal_readings_p"), tagged with the car so one is never read as the other; saved at most once a minute and
  when the service closes. A restart in the middle of a drive counts the rest of it as a new drive.

### Scan all values (2026-11-25)

Diagnostics, Jeep only, with the link up: run with the engine idling warm in Park. It asks the engine computer
for every id of its F4 live-data table, 00 to ef, one at a time, three passes (core `ValueScan`, through
`PollRunner.scanRead`). Each read is exactly what live data sends for one value: `60 02 00 00 f4 24 00 08 ff`
then `61 04 00 02 f4 <id> 01`, at the captured dongle timing (fast refresh is set aside for it) and the
runner's own 1 s timeout. `Protocols.allowedScan` passes only that frame with an id from 00 to ef; f0 to ff
pick other tables in the engine computer's fast mode (fe drops it to slow speed), so `Protocols.allowed` now
refuses them in any F4 read too. Live data waits while it runs (the gauges are blank; learning pauses) and
picks up again after. An id with no answer is written "no answer" and the scan moves on; 24 in a row means the
key went off and it stops. Progress shows in its window and the status line; Cancel stops it and keeps what was
read. The CSV (`value-scan-YYYYMMDD-HHMMSS.csv` in Downloads/JeepDash, shared from the window) has one row per id:
`id,known_as,pass_1,pass_2,pass_3`, the id as `0x18`, what the dash calls it (`map_kpa`, `rpm (high byte)`),
and each pass's raw answer bytes as sent back (`18 7e`). 720 reads take about three minutes.

### Timing pulled (2026-11-25, Jeep only)

A gauge, "Timing pulled", 0 to 15 degrees: the stock main spark table's timing for the engine speed and
manifold pressure now, minus the actual timing, never below 0 (core `SparkTable`, `TimingPulled`). The table
is from the owner's own engine computer (docs/protocol-notes.md). It reads only where that table is what the
engine computer uses, and is blank otherwise:

- coolant at least 180 °F; 1100 to 4800 rpm (below is idle and closed-throttle coasting, which run their own
  spark, a flat 19° around 1000 rpm in the logs);
- not at closed throttle (manifold under 25 kPa) and not while engine speed falls faster than 250 rpm a second
  (a lift-off or an upshift), nor for 2 s after either;
- only after those have held for 2 s (pulling away from a stop the timing is still on idle logic);
- a timing reading is compared only with engine speed and pressure read within 1.5 s of it;
- smoothing: the middle of the last three readings (none older than 3.5 s), so one reading taken mid-change
  does not move it but two in a row do.

Tuned on the owner's drive logs (one row a second; in them a value is often a few seconds old, so the gauge,
which compares only readings taken together, should do at least as well). Share of the readings shown that
read 0 / 0-2 / over 2 degrees: d1844 71% / 19% / 10% (shown for 15% of the drive, which was mostly town and
idle); d1901 84% / 13% / 3% (shown 54%); d2149 89% / 8% / 3% (shown 25%). d1124 logged no manifold pressure or
timing, so it cannot be used. Its inputs (rpm, manifold
pressure, timing, coolant) are read only while the gauge is on the dash's layout or the screen in front, and it
is worked out in the link service like fuel. It is also a column of the drive log.

## Open items

- **Map more Jeep values** (Daniel's help needed later): another capture of the GearWrench app showing its
  full Jeep live-data list with values on screen lets Claude match each name to its SCI id.
  The open-source ChryslerScanner project is a cross-check.
- **Spotify capture** on this tablet is unverified until the Phase 2 spike.
- Generic OBD2 path verified from the iPhone capture only; the tablet test exercised SCI. The next
  Link Test run should include Generic RPM.

## Left to the builder

Gauge drawing details and exact styling beyond the mockups, scheduler weights, CSV columns, FFT size,
swirl shader specifics, edit-mode gestures, how the warning limit is set.
