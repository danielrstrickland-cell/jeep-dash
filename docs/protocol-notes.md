# GearWrench GWSMARTBT (XTOOL OEM) — protocol notes from iPhone capture 2026-11-20

Device: GWBT-xxxxxxx, Bluetooth Classic, Realtek, EA protocol `com.xtooltech.btprotocol`.
SDP records: Apple iAP2 service (00000000-deca-fade-deca-deafdecacaff) on RFCOMM ch 3,
standard Serial Port 0x1101 ("serial port") on RFCOMM ch 4.
iPhone transport: RFCOMM ch 3 -> iAP2 (ff 5a) -> session 2 (External Accessory) -> XTOOL frames.
Android transport: expected Serial Port (ch 4) carrying the same XTOOL frames; unverified.
Chrome Web Serial on Android (Chrome 158, page opened from a file) listed no devices at all.

## Vehicle
1997 ZJ body, 1998 5.9 JTEC PCM.
- Generic OBD2 = ISO 9141-2 (5-baud init to 0x33, keywords 08 08, 10400 baud, sum checksum).
  The app tried J1850 VPW first (CRC 0x17) and got no answer.
- Enhanced = Chrysler SCI (7812.5 baud; dongle switches to 62500 before each F4 read).

## XTOOL frame
TX: `55 aa | seq(2) | len(2) | ~len(2) | payload | ck`   RX: same with `aa 55`.
ck = NOT(XOR of all bytes after the 2-byte magic).
Payload: `01 | innerLen(2) | inner`. RX ack `00 00 02 00 00`, then reply `01 | len(2) | data`; `01 ff ff` = no response.

## Commands (inner)
- `60 cb/c8/c1/d3/c0/c9` device info (c1 -> 16-byte id, d3 -> "a140", c0 -> 77 10 2e 80). No auth challenge seen.
- `60 05 xx 01` select line/protocol (02 = K-line 5-baud init, 01 = K-line, 19 = Chrysler SCI, 07 = CAN).
- `60 01 ..`, `60 02 00 00 <baud16> 00 08` (0x28a0 = 10400, 0x1e84 = 7812, 0xf424 = 62500), `60 03/04/06/08/0c` timing/filters/keep-alive.
- `61 05 1e 33 02 02 01 e0 00 00 00 05` = 5-baud init to 0x33 -> reply `.. 08 08 ..` (ISO 9141-2 keywords).
- `61 04 | len(2) | msg | flag` send vehicle message, get reply.

## Generic (ISO 9141-2) — what the dash sends (matches `Protocols.java`)

Set-up, in order: `60 05 02 01`, `60 01 09 c5 92`, `60 03 00 50 03 e8 00 37 00 05`, `61 06 01`, then a
5 s wait, then the 5-baud wake-up `61 05 1e 33 02 02 01 e0 00 00 00 05`. The wake-up answer comes back as
payload type `02` (`02 00 02 08 08 ..`), about 2.3 s later. First request `61 04 00 06 68 6a f1 01 00 c4 ff`
(supported PIDs; an answer means the line is up). Then `60 04 00 06 68 6a f1 01 00 c4 02 58 01`
(keep-alive message every 600 ms), `60 06 ff` (keep-alive on), `61 06 02 00 03 48 6b d1 00 00` (reply filter).

Read: `61 04 00 06 68 6a f1 01 <pid> <sum> 01`, answer `48 6b d1 41 <pid> A [B] <sum>`, about 290 ms each.
Supported PIDs on this Jeep: 01 03 04 05 06 07 0b 0c 0d 0e 0f 11 13 14 15 1c 1e.

Leaving: `60 06 00`, `60 0c 13 88 ff ff ff ff`, `60 06 00`. The keep-alive must be off before the Jeep
line is used, or it talks over it.

On the tablet the first wake-up after connecting sometimes gets no answer and the second works.

## Jeep line (Chrysler SCI, JTEC) — what the dash sends

Set-up: `60 05 19 01`, `60 01 49 c4 21`, `60 02 00 00 1e 84 00 08` (7812.5 baud),
`60 03 00 78 07 d0 00 14 00 05`, `61 04 00 01 10 01` (answer `10 05 96 7e 6d 6b 6a fe 69`).

Read: `60 02 00 00 f4 24 00 08 ff` (62500 baud, fast mode) then `61 04 00 0N f4 <id> [<id>] 01`.
A one-id answer is `<id> <value>`. A two-id answer is `<high value> <second id> <low value>`.
About 220 ms per read. `60 02 00 00 1e 84 00 08 ff` drops back to slow speed.

The GearWrench app itself reads two-id answers wrongly: it takes the echoed second id as the high byte.
That is why it shows engine speed stuck at 352-383 rpm, 53 mph while parked and a 1728 rpm target idle.

## Jeep-line live data (table F4)

Ids and scalings: second capture of the GearWrench app (engine running, whole list scrolled), checked
against the generic line on the same evening and against the open-source ChryslerScanner project's JTEC
table. "A" is the value byte, "AB" a two-id value (A high, B low).

| id | value | scaling | in the dash |
|----|-------|---------|-------------|
| 0a 0b | engine speed | AB / 8 rpm | rpm |
| 0c 0d | vehicle speed | AB / 64 mph | speed |
| 0f | battery voltage | A x 0.0625 V | batt_v |
| 10 | battery/ambient temp sensor | A x 5/255 V | |
| 11 | battery/ambient temperature | A - 64 degC | batt_temp |
| 12 | throttle sensor | A x 5/255 V; A x 100/255 % equals generic PID 11 | throttle |
| 13, 14 | minimum / calculated throttle volts | A x 5/255 V | |
| 15 | coolant sensor volts | A x 5/255 V | |
| 16 | coolant temperature | A - 64 degC | coolant |
| 17 | MAP sensor volts | A x 5/255 V | map_v |
| 18 | manifold pressure | A x 0.412 kPa | map_kpa |
| 19 | barometric pressure | A x 0.412 kPa | baro |
| 1b | upstream O2 volts | A x 5/255 V | o2_front |
| 1d | intake air sensor volts | A x 5/255 V | |
| 1e | intake air temperature | A - 64 degC | iat |
| 1f | knock sensor volts | A x 5/255 V | knock_v |
| 21 | cruise switch volts | A x 5/255 V | |
| 27 28 | injector pulse width | AB / 256 ms | inj_ms |
| 2b | long-term fuel trim | signed A x 0.196 % | ltft |
| 2f | spark advance | signed A x 0.5 deg | spark |
| 35 36 | target idle | AB / 8 rpm | target_idle |
| 37, 3e | target / actual idle motor steps | A | iac (3e) |
| 3a | charging voltage target | A x 0.0625 V | charge_v |
| 3b | cruise set speed | A x 0.5 mph | |
| 43 | adaptive cell id | A | |
| 44 | short-term fuel trim | signed A x 0.196 % | stft |
| 48 | downstream O2 volts | A x 5/255 V | o2_rear |
| 4a | closed-loop timer | A x 0.0535 min | |
| 4b 4c | time from start | AB x 0.000209 min | |
| de | fuel level sender volts | A x 5/255 V | fuel_v |
| e3 | engine load | A x 100/255 % | load |

Seen but not used yet: switch and relay state bytes (0e, 3c, 3d, 3f, 40, 41, 42, 4e, 4f, 60, 7a, 7b, 8e,
ee), fuel level and fuel used (df, e0), transmission temperature (9b), total knock retard (30; this
engine computer answers f0/0f alternately, so it is not real data here).

The app also uses slow-speed requests (`14 46` throttle percent, `14 42`, `1a 03`, `1a 04`) and table
`f3` (purge readings). The dash does not send those.

## Jeep line: stored codes (captures of 2026-11-20 and 2026-11-22)

All at slow speed: the app sends `60 02 00 00 1e 84 00 08 ff` before each request.

| Request | Answer | Meaning |
|---|---|---|
| `61 04 00 01 10 01` | `10 <one byte per code> fe <sum>` | Stored-code list; `fe` ends it, `fd` is not a code, the last byte is the sum of the rest. `10 fe 0e` is an empty list. |
| `61 04 00 01 17 01` | `17 e0 e0 e0` | Erase stored codes. `17 00` means refused (engine running). |
| `61 04 00 02 23 01 01` | `01 01 f0` | Erase all fault data; the app sends it straight after the erase. |

With codes stored the Jeep answered `10 05 96 7e 6d 6b 6a fe 69`. The GearWrench app's Read DTC screen
showed four of them as P1682 (`05`), P0463 (`96`), P0138 (`7e`) and P0303 (`6d`); `6a` to `6e` are the
misfire codes P0300 to P0304 and `ae` to `b1` are P0305 to P0308. The names of the other code numbers
(`SciCodeNames`) are cross-checked against the open-source ChryslerScanner project's list for this family of
engine computers. The dash shows a P number only where it is confirmed, and the Chrysler number otherwise.

The app's Read DTC also sends `61 04 00 01 32 01`; this Jeep just echoes `32`. In the ChryslerScanner notes
that request is the diesel computers' version of the same list. The dash does not send it.

Other requests in the same family that the dash does not send: `23 02` (reset learned fuel trim), `23 03`
(reset idle-air counter), `13` (actuator tests), `19` (set idle speed), `1c` (write memory).

## Fast refresh (experimental, Jeep line)

In every capture a Jeep-line read comes back about 183 ms after the request (the GearWrench app manages one
read per 250 ms, the dash one per 225 ms), although the bytes take about 2 ms on the wire. That fits the
dongle's timing command `60 03 00 78 07 d0 00 14 00 05`: 20 ms before sending, then 120 ms (`00 78`) of
silence before the answer counts as complete. With Fast refresh on, the dash sends the same command with
`00 1e` (30 ms). The GearWrench app never does, so this is a guess to be tried on the Jeep: `PollRunner`
starts it only after three good reads, goes back to the captured value after two misses in a row or one
miss in ten, leaves it off if readings then return, and always reads and clears codes at the captured value.
It is a setting in the dongle; nothing extra is sent to the engine computer.

## What the dash may send

Reading: `Protocols.allowed()` is the complete list of what live data may send: the set-up commands above,
generic mode 01 reads, Jeep-line F4 reads, the stored-code request and the Porsche's measured-value reads.
`PollRunner` and `KwpRunner` refuse anything else.

Writing: the dash was read-only until 2026-11-22, when the owner asked for clearing codes. That is the one
write. `Protocols.allowedWrite()` passes exactly the two erase frames in the table above, and only
`PollRunner.clearCodes()` asks it. A clear is sent only from the Codes screen after the owner confirms a box
listing the codes, and only after the engine speed has been read as zero; the engine computer also refuses
on its own while the engine runs. Still out: actuator tests, resets of learned values, writing memory.

## Porsche Cayenne Turbo (2003): KWP2000 on the K-line

From a capture of the GearWrench app on the car (engine off, then idling and revved). The app first tries
CAN and gets no answer; the engine computer answers on the K-line. Read-only, like the Jeep: the dash
replays the line setup and sends only "read measured values" requests.

Setup, in order (dongle configuration unless noted):

    60 06 00                               stop any keep-alive left running
    60 05 01 01
    60 01 09 c5 02
    60 02 00 00 28 b0 00 08                10400 baud
    60 03 00 64 01 f4 00 37 00 05
    60 03 03 e8 03 e8 00 37 00 05
    61 05 1e 01 02 02 00 41 00 00 00 05    5-baud wake-up of address 01 (engine); answers 01 00 02 ef 8f
    60 04 00 05 81 10 f1 3e c0 01 f4 01    the dongle sends "tester present" every 500 ms from here on
    60 06 ff                               keep-alive on
    60 03 00 32 01 f4 00 37 00 05

Read one group (service 21, local identifier = group number), about 185 ms per read, 3005 reads in the
capture with one unanswered (the last, as the link dropped):

    61 04 00 06 82 10 f1 21 <group> <sum> 01
    -> 9a f1 10 61 <group> <8 x (kind a b)> <sum>

Each value is three bytes. The kind byte says how a and b become a reading, so the computer states its own
units. Kinds the dash uses, all but volts checked against the GearWrench app's screen at idle:

| Kind | Reading |
|------|---------|
| 01 | engine speed, a x b x 0.2 rpm |
| 05 | temperature, a x (b - 100) x 0.1 degC |
| 07 | road speed, a x b x 0.01 km/h |
| 12 | pressure, a x b x 0.04 mbar |
| 15 | volts, a x b x 0.001 |
| 1a | temperature, b - a degC |
| 22 | angle, (b - 128) x 0.01 x a degrees |

Where the dash's values sit (group, slot from 0):

| Value | Group | Slot | Checked against the vendor app |
|-------|-------|------|--------------------------------|
| Engine speed | 0a | 0 | 920 rpm |
| Battery volts | 0a | 1 | not shown there; 13.6 to 13.8 V running |
| Road speed | 0a | 2 | 0 mph (stationary only) |
| Coolant | 0b | 1 | 104.0 F |
| Intake air | 0b | 2 | 75.2 F |
| Camshaft right, left | 14 | 1, 2 | -6.00, -5.00 |
| Manifold pressure | 16 | 3 | not shown; 1000 mbar engine off, 260 to 380 at idle |
| Outside air pressure | 16 | 4 | not shown; steady 1000 mbar |
| Oil temperature | 16 | 6 | 98.6 F |
| Boost target ("Standard Boost Pressure") | 3b | 3 | 14.50 psi |
| Boost sensor | 3b | 6 | not shown; 970 to 1010 mbar at idle |

The dash's boost gauge is manifold pressure minus outside air pressure (both in group 16). It has not been
seen under boost yet.

### Stored codes on the Cayenne (capture of 2026-11-22)

The GearWrench app's Read DTC and Clear DTC on the engine computer, on the same line and session as live data:

| What | Sent (inside the dongle frame) | Answer |
|---|---|---|
| Read DTC | `61 04 00 08 84 10 f1 18 00 ff 00 9c ff` | `85 f1 10 58 01 15 25 34 4d`: 58, one code, `15 25` = P1525, status `34` |
| Clear DTC | `61 04 00 07 83 10 f1 14 ff 00 97 01` | `83 f1 10 54 ff 00 d7` after about 0.9 s |

The app showed P152500 "Cylinder bank 1, camshaft adjustment, electrical circuit fault", then "Clear Code
Successfully". Its first tries got no answer because the session had lapsed; it woke the line again (the same
5-baud wake-up and keep-alive the dash uses) and the read then answered in 0.2 s.

The dash sends these two frames and nothing else for codes. The clear is the one permitted write on this car:
only from the Codes screen, only after engine speed (group 0a) reads zero, followed by a fresh read of the list.
The status byte is not interpreted.

### More Cayenne values (capture of 2026-11-22, warm idle)

Each was matched to the figure on the GearWrench screen at that moment. Kinds: 21 = 100 x b / a percent,
14 = a x (b - 128) / 128 percent, 1b = (b - 128) x a / 100 degrees, 0b = 1 + a x (b - 128) / 10000,
0f = a x b / 100 ms, 19 = (256 x b + a) x 0.044 lb/h (the one scale that fits both air-flow lines: 68.64 and
56.98), 36 = a x 256 + b.

| Value | Group/slot | Vendor screen |
|---|---|---|
| Exhaust after / before cat | 0a/4, 0a/6 (kind 05) | 716.00 / 662.00 F |
| Engine load | 0a/7 | 21.05 % |
| Throttle | 0b/0 | 1.96 % |
| Ignition angle | 0b/3 | 2.25 deg |
| Engine torque | 0b/5 | 8.63 % |
| Pedal value | 0e/1 | 4.31 % |
| Lambda right, fuel trim bank 1 | 0f/0, 0f/3 | 1.000, -3.12 % |
| Lambda left, fuel trim bank 2 | 12/0, 12/3 | 1.000, 0.00 % |
| Injection time, total air flow | 16/0, 16/1 | 1.23 ms, 68.64 lb/h |
| Ambient temperature | 17/4 | 71.60 F |
| Brake booster pressure | 34/2 (kind 12) | 7.03 psi |
| Selected gear | 14/0 | 0 in park; 1 and 7 seen while the lever was moved |

Seen but not used, because nothing but zero was on screen to check the scale against: boost control deviation
(1b/7, kind 32), per-cylinder ignition retard and the misfire counters.

## Fuel use, worked out by the screen (both cars)

Neither engine computer reports fuel use; the dash works it out from values it already reads (core `FuelUse`).

- Cayenne: fuel lb/h = air flow (`maf`, lb/h) / (14.7 x lambda). Lambda is the average of `lambda_r` and
  `lambda_l`, either one alone if only one is fresh, 1.0 when both are missing.
- Jeep (no air-flow sensor): fuel lb/h = 8 injectors x 24 lb/h x (`inj_ms` / 1000) x (`rpm` / 120), one
  injection per cylinder every two turns. 24 lb/h an injector is a starting figure; the fill-up calibration
  corrects it.
- gal/h = lb/h / 6.17, times the car's calibration factor (default 1.0).
- MPG now = mph / gal/h: 0 below 1 mph, at most 99, blank when its inputs are stale (older than 2 s).
- Gallons and miles add up between readings (average of the two ends x time); a gap over 2 s adds nothing.
  MPG this drive = miles / gallons once 0.01 gal has been used.
- Calibration at a fill-up: new factor = factor x pumped / counted, refused under 2 gal counted, one step
  limited to 0.5x..2x, factor kept within 0.3..3.
- Incomplete: while moving (the last speed reading at 1 mph or more, trusted for up to 60 s when readings
  stop), time with no fresh fuel reading is counted. Over a tenth of the moving time and over 10 s, the drive's
  totals go blank and the fill-up estimate is refused for calibration.
