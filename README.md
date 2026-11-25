# jeep-dash

Copyright (c) 2026 Daniel Strickland. All rights reserved. See [LICENSE](LICENSE).

Digital dash for a 1997 Jeep Grand Cherokee (ZJ) running a 1998 5.9 JTEC engine computer,
reading live data through a GearWrench GWSMARTBT Bluetooth dongle.

**Current stage: link test.** Nothing here is the finished dash yet.

- `app/`: *Jeep Link Test*, a throwaway Android app that connects straight to the dongle
  and reads RPM and coolant (generic OBD2 over ISO 9141-2, and Chrysler SCI).
  GitHub Actions builds it on every push; grab `jeep-link-test.apk` from the newest Release.
- `docs/`: the same test as a web page (Chrome Web Serial), plus protocol notes.

## Live position for Jeep Tune's table viewer

While a Tune log is running, the dash sends the engine speed and MAP it already polled to Jeep Tune (at most 5 per second):

- Action: `com.strickland.jeepdash.action.LIVE`, sent with `setPackage("com.strickland.jeeptune")`.
- Extras: float `rpm`, float `map_kpa`, long `t` (dash clock, ms).
- Sent only while a log that reads both values is running (not Cold start, which has no MAP). Nothing new is sent to the engine computer.

## GPS speedometer check (Cruise log)

The Cruise tune log also records `GPS speed` and `GPS accuracy` from the tablet's GPS (platform LocationManager, about 1 Hz; location is asked for only when a log that needs it starts, and the channels are listed in `# missing=` if it is refused). From pairs of the engine computer's raw speed and GPS speed (GPS accuracy 10 m or better, both at least 25 mph, steady within 1 mph over 3 s) it takes the median of gps/pcm. When the log stops, the Tune log screen shows e.g. "Speedometer reads 3.6% low, 57 samples, suggested constant 0x...", and the CSV ends with `# speed_ratio=`, `# speed_samples=`, `# speed_spread=` and `# suggested_constant=` lines. The suggested constant is 28800000 x ratio (stock constant, flash 0x045A); it is only shown, never written.

## Auto brightness

The XP look's Brightness setting is Auto (default), Always light or Always dark. Auto uses the ambient light sensor: dark after 10 s under 15 lux, light after 10 s over 60 lux. Without a light sensor it uses sunset and sunrise at the tablet's last known GPS position; with neither it leaves the screen as it is.

## Help

**Pause for Jeep Tune.** When the Jeep Tune app is reading the engine computer, Jeep Dash lets go of the dongle so both apps don't fight over it. The dash shows "Paused — Jeep Tune reading". It reconnects by itself when the read ends, or after 90 seconds of silence, so you don't need to do anything.

**Tune log (Diagnostics menu).** Pick one of four profiles: Idle, Cruise, Cold start, or Pull. Follow the on-screen steps, tap Next step as you go, then tap Stop and save. To share the file, tap Send to Claude, which opens the share sheet. Pick Google Drive and the tuning folder.

**Speedometer check.** After a Cruise log, a card shows how far off the speedometer is compared with GPS, along with a suggested correction. The card is for information only. Nothing is written to the Jeep.

**Misfire catcher.** When you read codes and a misfire code is present, the app saves the 30 seconds before and after the event as a CSV file. You don't need to start anything.

**Auto night mode.** In the Brightness setting, choose Auto, Always light, or Always dark. Auto uses the light sensor if the tablet has one. Otherwise it uses sunset time.

**Holo look (Switch look, or Setup > Look).** The hologram face from the mockup: rpm and speed arcs round a 3D
truck, slim panels down both sides. Tap the truck: it spins round once. Hold it: the hood (or the liftgate)
opens; hold again or drive off to close it. The truck leans and pitches with the Jeep: from the tablet's motion
sensor once it sits steady in its mount for a few seconds, otherwise from GPS. Everything is editable as in the
other looks: Edit, then drag, resize or tap a panel to choose its reading, style (panel, big readout, ring,
hexagon, arc left or right of the truck) and depth (behind the truck, level, or in front). Hide gauge removes
one; + Gauge or "Put the 3D truck back" brings things back; Reset puts the mockup's layout back.

**Load truck model (Setup > Look > 3D truck).** Pick your truck's .glb file on the tablet; it is copied into the
app, never into the app's public files. Load an optional lite model too for Power saver; with only one, it is used
everywhere. Parts named hood, liftgate (or gate) and wheel FL/FR/RL/RR move. "Use the built-in stand-in" goes
back to the bundled truck. The same screen sets how hard the body leans (realistic to exaggerated) and the
lighting.

**Quality mode (Setup > Look).** Ultra: 30 frames a second, sharpest picture, soft shadow under the truck,
strongest glow. High (normal): 60 frames a second, dropping to 30 when nothing moves or changes. Power saver: 30
frames a second (about 12 when still), lighter picture, no glow; cooler and easier on the battery.

**Hologram effects (Setup > Look).** Off for a clean look: no scanlines, flicker, colour fringe or glow, and
panels appear without the animation.

**Calm while moving (Setup > Look, on by default).** Above 5 mph the flicker, the arc sweep and the panel
animations stop so nothing distracts; the readings keep updating. Back below 3 mph it livens up again.

**Tablet heat and battery.** The dash watches the tablet's battery temperature, charge and Android's heat status
all the time, behind any screen. Gauges: Tablet temp, Tablet battery, Tablet heat, Tablet current (blank if the
tablet does not report it); they are in the drive log too. A small thermometer shows in the top-left corner when
the tablet runs warm; tap it for the figures. Warnings: battery at 45 °C (113 °F), "Tablet overheating, shade or
cool it" at 50 °C (122 °F), and battery at 15% when not charging; when the dash is not in front they come as a
notification.

**Heat protection (Setup > Look, on by default).** When the tablet gets warm, Ultra drops to High; hot, any mode
drops to Power saver; very hot, the 3D truck pauses and the face shows plain gauges. Your mode comes back after
the tablet has been cool for 2 minutes.
