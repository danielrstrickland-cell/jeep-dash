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
