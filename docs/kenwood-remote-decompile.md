# KENWOOD Remote 2.0.5: what its code does (decompiled 2026-11-26)

From the APK (KENWOOD+Remote_2.0.5_APKPure.apk), read with androguard. Notes only: nothing here is confirmed on
the stereo unless the protocol notes say so.

## Matches what jeep-dash already sends
- Frame layout, byte stuffing, CRC and one-message-at-a-time sending (the app waits up to 50 ms for the send gate).
  Plain messages go out with type 0.
- Start-up sends: `5B/02`, `3B/01`, then `3B/34` (feature request), as in the protocol notes.
- Control commands go to address `50`, category `11`, payload one byte each:
  `01` source (2 bytes), `02` volume, `03` mute, `05` remote key.
- Remote-key codes: remote-key version 2 = volume up 8, down 7; version 4 = up 1, down 2 (`Status.Remo_Version`,
  set from the stereo's `5B/36` answer). Digit keys in Passenger Mode are 20 to 24 and up.

## How the app uses volume (differs from jeep-dash)
- It never sets an arbitrary level. It sends the stereo's last reported volume plus or minus one, clamped 0 to 35
  (`VolumeControlActivity.volumeUp/volumeDown`), and does not send the next step until the stereo has reported the
  new level; after 5 unanswered tries it sends again. So it depends on the stereo reporting its volume.
- The app learns the volume from an incoming message (category `12`, handled in `CommandControl.AnalyzeCMDrecv`).
  Whether the stereo only sends that after some request is not yet known.

## Not the cause (checked)
- `3B/33` with 1 byte (1, then 0) is the audio-switch message. The app sends it on a timer when it loses Android
  audio focus (`JK_BKService.StartAudioSwitchTimer`, `HomeLauncher*.setCR_AudioSW`) and `0` when done. It is about
  switching away from the Bluetooth audio source, not about volume or keys.
- Addresses `57`, `81`, `9C`, `9D`, `BB` with category `11` commands `80`, `82`, `90`, `91`, `A2`, `A3`, `B7`, `B8`
  are the voice-control paths for other modules. The normal volume, mute and key path is address `50`.

## Other commands the app sends to the main unit
`3B/12` (when its player screen opens), `3B/32` (mood), `3B/33` (audio switch), `11/04` (from gestures).

## Still open
- Why `11/02`, `11/03` and `11/05` are acknowledged but ignored. Nothing in the app's own sending explains it. Next
  things to check: what the stereo reports for volume after start-up in the probe log, the source-list and
  current-source set-up exchange (`lst_src_init`, `cur_src_init` in `CommandControl`), and whether the stereo
  accepts these only for some sources.
