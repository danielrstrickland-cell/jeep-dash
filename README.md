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

**After a crash.** The next start shows what went wrong, with Share to send it, then starts safely: the 3D
truck is off until the next update, and a dash left on Holo comes back in Jeep style (Switch look returns to
Holo, flat). **Last crash report (Menu > App)** shows the most recent crash Android remembers at any time.

**Visualizer glow and CRT filter.** The picture now runs through the tablet's graphics chip. Bright parts glow, and an optional old-TV filter adds curved glass and scanlines. Set both on the hidden menu's Effects tab. The glow needs Android 12, and the CRT filter needs Android 13.

**Visualizer menu.** The visualizer has one button, top left, set below the status bar. Tap it for the side menu: Dash, Look and Colors (with arrows), Bars, Tune this look, Effects and presets, Hide controls, and Music. The button fades after 10 seconds untouched; touch its corner to bring it back. A three-finger tap hides everything, and another three-finger tap (or holding the top-left corner) brings it back. The tune window has four tabs: Look tunes the look on screen, Colors picks a profile, Effects holds settings shared by every look, and Presets saves and loads 3 full setups. Turn on Dark menus in Effects for a dark style.

**Visualizer graphics log.** In Tune › Presets, Run graphics test plays every look for 4 seconds with effects on and then off (about 80 seconds), recording the frame rate, the slowest frames, the GPU's time per frame, the 3D detail each look settled at and the tablet's temperature status. While you use the visualizer, it also logs a line like that every 20 seconds. Share log sends it all as a text file (also saved to Downloads/JeepDash) so it can be used to tune the graphics for this tablet.

**Visualizer effects.** On the Effects tab: Glow, CRT filter, Blend between looks, Speaker delay (Bluetooth lags the sound; raise this until the beats land exactly with what you hear, about 0.2 s to start), G-meter tilt (how far the liquids and road lean when you turn and brake), Quality (Max · 30 draws the 3D looks at full resolution 30 times a second; Balanced · 60, the default, uses 85% detail; Smooth · 90 uses 60% detail at the screen's full 90. Detail also drops by itself if frames run slow), Show FPS (a tiny frame counter in the bottom-right corner), and Fade menu button.

**3D looks.** These need Android 13 or later. On older tablets they show smooth bars instead. Milk drop splashes a crown into a pool on every bass hit. Road drives at the Jeep's real speed past sidewalks and street lamps, between buildings that are the equalizer (bass on the left, treble on the right), and bends when you turn; if the bends go the wrong way, use Flip turns in its tune settings. Without the Jeep connected, it cruises with the music. Mandelbrot is an endless fractal dive whose speed follows engine rpm. Smooth now shows 3D bars; turn off 3D bars in its tune settings for the flat ones. Road has City buildings on by default: real 3D blocks (glass towers, brick mid-rises and offices) whose lit floors climb with the music, bass on the left and treble on the right, with shops at street level, stepped tops and antennas. Building height goes up to 4x and Building width sets how big the blocks are; turn City buildings off for the old bars. Street racer races an original tuner car down a neon track: underglow pulses with the bass, it bounces on hits, and its readout shows beats a minute, speed and rpm. Sub cam shows your 15-inch sub, built to the EVL-15's published sizes, in a carpeted ported box at an angle, so you can see the cone travel. The cone moves on the real driver's physics (its moving mass, suspension, motor, the box air and the port tuning), so it pushes, overshoots, rebounds and settles, and bottoms out past Xmax. Its tune settings: Motion (Slowed shows every push and rebound, Real speed is true speed with motion blur), Camera angle, Zoom, Excursion (how hard the amp drives it), Stiffness, Damping, Box tune (set to 34 Hz; replace it with your box-tune estimate), Show box (turn it off for a cutaway of the whole driver: spider, coil former, magnets and pole all moving), Excursion readout (live millimetres, red past Xmax), Port air, LED ring and Camera sway. Dolphins is a '90s head-unit display: pixel dolphins leap on bass hits over a dot-matrix spectrum, in green or your colour profile (it works on every tablet). Particle storm, on the Effects tab, throws sparks up on every bass hit over any look; set it to 0 to turn it off.
