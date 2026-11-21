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

## Read-only rule

`Protocols.allowed()` is the complete list of what the dash may send: the set-up commands above, generic
mode 01 reads and Jeep-line F4 reads. `PollRunner` refuses anything else.

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
