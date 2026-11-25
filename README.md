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
