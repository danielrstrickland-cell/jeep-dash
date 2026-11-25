# Tuning tones

`tuning-tones.zip` (7 MB, 7 files): 44.1 kHz, 16-bit WAV, stereo (same signal in both channels).
Sine 40 Hz, 50 Hz and 1 kHz, 30 s each, at 0 dBFS and at -10 dBFS; pink noise, 60 s, with its peak at -10 dBFS.
Unzip and copy the WAV files to a USB stick. The unzipped files are ~41 MB, so only the zip is committed;
`python3 make_tones.py OUTPUT_DIR` (needs numpy) regenerates them.
