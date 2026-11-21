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

## Generic (ISO 9141-2) setup replay
60 05 02 01 / 60 01 09 c5 92 / 60 03 00 50 03 e8 00 37 00 05 / 61 06 01 / 61 05 1e 33 02 02 01 e0 00 00 00 05 (init) /
61 04 00 06 68 6a f1 01 00 c4 ff / 60 04 00 06 68 6a f1 01 00 c4 02 58 01 / 60 06 ff / 60 05 01 01 / 60 01 09 c5 92 /
60 02 00 00 28 a0 00 08 / 60 03 00 c8 0b b8 00 37 00 05 / 61 06 01 / 60 c3
Request: `61 04 00 06 68 6a f1 01 <PID> <sum> 01` -> reply `48 6b d1 41 <PID> data.. sum`.
Polled: 0C RPM (/4), 0D speed, 0E timing, 0F IAT, 11 TPS. Verified vs screenshot (720 rpm, 0 mph, 127.4 F, 13.7 %).

## SCI (Chrysler JTEC) setup replay
60 05 19 01 / 60 01 49 c4 21 / 60 02 00 00 1e 84 00 08 / 60 03 00 78 07 d0 00 14 00 05 / 61 04 00 01 10 01
Before each read: `60 02 00 00 f4 24 00 08 ff` (62500).
Request: `61 04 00 02 f4 <id> 01` -> reply `<id> <val>`; 2-byte: `f4 <hi> <lo>` -> `<hiVal> <lo> <loVal>`.
- F4 0A/0B RPM = raw16 / 8 (idle ~730, matches generic)
- F4 16 coolant: (raw - 64) C  (0x91 -> 177.8 F; screenshot 177)
- F4 17 MAP volts = raw / 51 (0x69 -> 2.06 V; screenshot 2.060)
- F4 2F spark advance = raw deg (07/08; screenshot 7)
Rates seen: generic ~3.3 reads/s total, SCI ~4 reads/s total.
