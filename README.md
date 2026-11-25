# jeep-dash

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


**Visualizer glow and CRT filter.** The picture now runs through the tablet's graphics chip. Bright parts glow, and an optional old-TV filter adds curved glass and scanlines. Set both on the hidden menu's Effects tab. The glow needs Android 12, and the CRT filter needs Android 13.

**Visualizer menu.** The visualizer has one button, top left, set below the status bar. Tap it for the side menu: Dash, Look and Colors (with arrows), Bars, Tune this look, Effects and presets, Hide controls, and Music. The button fades after 10 seconds untouched; touch its corner to bring it back. A three-finger tap hides everything, and another three-finger tap (or holding the top-left corner) brings it back. The tune window has four tabs: Look tunes the look on screen, Colors picks a profile, Effects holds settings shared by every look, and Presets saves and loads 3 full setups. Turn on Dark menus in Effects for a dark style.

**Visualizer effects.** On the Effects tab: Glow, CRT filter, Blend between looks, Speaker delay (Bluetooth lags the sound; raise this until the beats land exactly with what you hear, about 0.2 s to start), G-meter tilt (how far the liquids and road lean when you turn and brake), 3D detail (starts at 85%; lower it if the 3D looks stutter, and it also drops by itself when needed), Cap at 60 fps (on by default: steadier motion, sharper 3D looks and a cooler tablet), and Fade menu button.

**3D looks.** These need Android 13 or later. On older tablets they show smooth bars instead. Ferrofluid is liquid metal that spikes with the music, throws droplets on bass hits and sloshes when you turn or brake. Milk drop splashes a crown into a pool on every bass hit. Road drives at the Jeep's real speed and bends when you turn; if the bends go the wrong way, use Flip turns in its tune settings. Without the Jeep connected, it cruises with the music. Lava lamp has waxy blobs that rise, merge and swell with the bass. Mandelbrot is an endless fractal dive whose speed follows engine rpm. Smooth now shows 3D bars; turn off 3D bars in its tune settings for the flat ones. Particle storm, on the Effects tab, throws sparks up on every bass hit over any look; set it to 0 to turn it off.