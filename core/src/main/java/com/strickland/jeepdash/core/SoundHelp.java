package com.strickland.jeepdash.core;

/**
 * What each card of the Sound hub does and how it works, in plain words, for the card's information button.
 * Kept in core beside the code it describes; the numbers it quotes are taken from that code where they can
 * be, so the words cannot drift from what the app does.
 */
public final class SoundHelp {
    public static final String PROFILES = "profiles", DSP = "dsp", RTA = "rta", CALIBRATE = "calibrate", ADAPTIVE = "adaptive",
            SCENES = "scenes", PLAYER = "player", HEAD_UNIT = "head_unit", DIAGNOSTICS = "diagnostics";
    public static final String[] TOPICS = {PROFILES, DSP, RTA, CALIBRATE, ADAPTIVE, SCENES, PLAYER, HEAD_UNIT, DIAGNOSTICS};

    private SoundHelp() { }

    public static String title(String topic) {
        switch (topic) {
            case PROFILES: return "Sound profile";
            case DSP: return "Tablet DSP";
            case RTA: return "EQ and live RTA";
            case CALIBRATE: return "Calibrate and room correction";
            case ADAPTIVE: return "Adaptive engine";
            case SCENES: return "Scenes";
            case PLAYER: return "Lossless player";
            case HEAD_UNIT: return "Head unit";
            case DIAGNOSTICS: return "Diagnostics";
            default: return "";
        }
    }

    public static String text(String topic) {
        int boost = (int) SoundAdapt.MAX_BOOST_DB;
        switch (topic) {
            case PROFILES: return "WHAT IT IS\n"
                    + "A profile is one place you listen: the Jeep, a room with the tablet's own speakers, a pair "
                    + "of headphones. Pick one from the list and the whole screen becomes that place's settings.\n\n"
                    + "WHAT A PROFILE KEEPS\n"
                    + "Its own Calibrate measurement, target curve and custom sliders, correction strength, the "
                    + "adaptive switches and noise strength, the While driving choice, Bass drop, the box tuning and "
                    + "the learned cabin noise.\n\n"
                    + "SAVING\n"
                    + "There is no save button. Whatever you change is kept in the profile you are on, and it is "
                    + "there when you come back to it. New makes a fresh profile with no measurement: run Calibrate "
                    + "in that place. Rename and Delete act on the profile you are on.\n\n"
                    + "SWITCHING BY ITSELF\n"
                    + "A profile can be set to come on when the tablet plays through its own speakers, or through "
                    + "Bluetooth. Then connecting to the stereo brings the Jeep profile up and disconnecting brings "
                    + "the room one back. It waits until the new way out has held for 15 seconds, and it never "
                    + "switches while this screen is open: here, the profile only changes when you change it. Any "
                    + "Bluetooth speaker or headphones count as Bluetooth, so pick by hand for those.\n\n"
                    + "THE SAME IN EVERY PROFILE\n"
                    + "The Tablet DSP switch, Music or Movie mode, the microphone choice and its calibration file, "
                    + "the three scenes and the stereo link.";
            case DSP: return "WHAT IT DOES\n"
                    + "An equaliser on everything the tablet plays (Spotify, YouTube, the lossless player) before the "
                    + "sound goes to the stereo or the tablet's own speakers. Everything else on this screen works "
                    + "through it.\n\n"
                    + "HOW IT WORKS\n"
                    + "Each setting here adds up to one gain for each of 31 bands from 20 Hz to 20 kHz. Before any "
                    + "boost, the whole signal is turned down by the size of the biggest boost, so a boost cannot "
                    + "clip. That is why it can sound a little quieter when it is on.\n\n"
                    + "It is off until you switch it on. If this tablet will not allow it, the line under the switch "
                    + "says so and the reason goes in Diagnostics.\n\n"
                    + "IN THE BACKGROUND\n"
                    + "While it is switched on it keeps working after you close the dash, over any other app, so a "
                    + "show or a video gets it too. The Jeep Dash notification stays up while it does; Stop on that "
                    + "notification ends it, and opening the dash starts it again. Two minutes after the dash is "
                    + "closed it stops looking for the dongle, so the adaptive parts rest until the dash is open.\n\n"
                    + "LISTENING TO\n"
                    + "Music: works in long blocks so bass is adjusted accurately. Sound comes out about 85 ms late, "
                    + "which music does not mind but video shows as lips slightly out of step.\n\n"
                    + "Movie or TV show: lifts voices by " + (int) SoundAdapt.VOICE_DB + " dB around 2.5 kHz so speech "
                    + "is easier to follow, and works in short blocks so the sound is only about 20 ms behind the "
                    + "picture. Bass adjustment is coarser in this mode.\n\n"
                    + "Even out loud and quiet (Movie or TV only): turns loud moments down and brings everything up "
                    + "by " + (int) SoundAdapt.LEVEL_MAKEUP_DB + " dB, so speech is easier to hear without the "
                    + "explosions being too loud. It is a compressor: leave it off for music.";
            case RTA: return "WHAT IT SHOWS\n"
                    + "Blue bars: what the microphone hears right now, in 31 bands, lined up on its own mid-range "
                    + "level.\n"
                    + "Yellow line: the target curve, the sound you are aiming for.\n"
                    + "Green line: the gains the Tablet DSP is applying at this moment.\n\n"
                    + "TARGET CURVE\n"
                    + "Harman: about 7 dB more bass under 60 Hz, easing to level by 200 Hz, level to 1 kHz, then a "
                    + "gentle fall to 6 dB down at 20 kHz. A well-liked shape for a car.\n"
                    + "Flat / reference: level at every pitch.\n"
                    + "Custom: the same shape with your own bass shelf and treble, set by the two sliders.\n\n"
                    + "HOW IT WORKS\n"
                    + "The target does nothing by itself. After Calibrate has measured the system, the DSP moves what "
                    + "was measured toward the target you pick. Change the target and the correction follows at once, "
                    + "with no need to measure again.";
            case CALIBRATE: return "WHAT IT DOES\n"
                    + "Measures how the system really sounds where the microphone is, and stores it. The Tablet DSP "
                    + "(and the lossless player) then correct the sound toward your target curve.\n\n"
                    + "HOW IT WORKS\n"
                    + "It listens to the quiet for a moment, plays about 9 seconds of pink noise, and records what "
                    + "the mic hears in 31 bands. It works out which bands the speakers actually play, sets the level "
                    + "from the mids inside that range, and then corrects in two ways. Up to about 200 Hz, where a "
                    + "cabin's booms and holes are real, each band is corrected in full: at most 6 dB up and 12 dB "
                    + "down. From about 500 Hz up, where a single mic position mostly hears reflections, only the "
                    + "broad shape is eased, by 3 dB at most. With the tablet's own mic nothing is corrected from "
                    + "8 kHz up, because that mic cannot be trusted there. Nothing is boosted where the speakers do "
                    + "not play.\n\n"
                    + "Correction strength sets how much of that correction is used: 100 % is all of it, 50 % half, "
                    + "0 % none. Turn it down if the corrected sound is too different from what you like.\n\n"
                    + "HOW TO USE IT\n"
                    + "Engine off or idling, windows up, tablet where your head is, a normal listening volume. If it "
                    + "says too quiet or overloaded, change the volume and run it again.\n\n"
                    + "MICROPHONE\n"
                    + "Built-in mic: trusted from 50 Hz to 10 kHz, so only that range is corrected.\n"
                    + "USB measurement mic: with its calibration file loaded (Load mic cal file, the text file that "
                    + "came with the mic), 25 Hz to 16 kHz.\n\n"
                    + "GOOD TO KNOW\n"
                    + "Each sound profile has its own measurement. A new Calibrate replaces the one in the profile "
                    + "you are on, so check the profile at the top before you measure. Clear removes it.";
            case ADAPTIVE: return "WHAT IT DOES\n"
                    + "Changes the sound by itself as the Jeep's state changes, using road speed, rpm and battery "
                    + "voltage from the dongle. Without a speed reading from the dongle, scene EQ and noise "
                    + "compensation rest.\n\n"
                    + "WHILE DRIVING\n"
                    + "Fully automatic: adjusts silently.\n"
                    + "Automatic, with a note: shows a short message when the scene changes.\n"
                    + "Parked only: scene EQ and noise compensation hold still while you are moving. The two "
                    + "protections below still work.\n\n"
                    + "SCENE-LINKED EQ\n"
                    + "Three scenes by speed: idle (under 5 mph), cruise, and highway (55 mph and up). At idle it "
                    + "cuts 3 dB around 50 Hz to take the boom out. Cruise and highway add nothing of their own yet.\n\n"
                    + "NOISE COMPENSATION\n"
                    + "Lifts the music as the cabin gets louder, by up to " + boost + " dB. The strength slider scales "
                    + "it. To begin with it uses a starting model: nothing under 20 mph, the most by 80 mph, mostly in "
                    + "the bass, plus a share for a revved engine.\n\n"
                    + "Learn cabin noise replaces that model with this Jeep's own noise. Pause the music, start "
                    + "parked with the engine running, tap it, and drive normally for a few minutes with this screen "
                    + "open. It files what the mic hears by speed, in steps of 10 mph, and afterwards gives half a dB "
                    + "of lift for each dB the noise rose above standing still, band by band. It needs standing "
                    + "still plus at least one speed. Another drive adds to it. Forget deletes it.\n\n"
                    + "VOLTAGE-SAFE SUB\n"
                    + "When battery voltage sags, turns the bass (120 Hz and under) down so the sub amp is not "
                    + "pushed into clipping: nothing at 13.2 V or more, 3 dB off at 11.8 V, 6 dB off under 11 V.\n\n"
                    + "PORT / UNLOADING GUARD\n"
                    + "Below a ported box's tuning the cone loses the air's grip and flaps. This cuts steeply below "
                    + "85 % of the tuning frequency. It needs that frequency: type it and tap Set, or tap Measure.\n\n"
                    + "Measure plays a " + (int) BoxTune.SWEEP_S + "-second sweep from " + (int) BoxTune.LO_HZ + " to "
                    + (int) BoxTune.HI_HZ + " Hz while you hold the mic a finger-width from the centre of the sub's "
                    + "cone, at low volume. At the box's tuning the port does the work and the cone almost stops, so "
                    + "the mic hears a sharp dip there. The bottom of the dip is the tuning.";
            case SCENES: return "WHAT THEY ARE\n"
                    + "Three saved snapshots of this screen's settings: Solo drive, Full car and Bass demo. Hold a "
                    + "button to save the settings as they are now. Tap it to bring them back.\n\n"
                    + "WHAT A SCENE KEEPS\n"
                    + "DSP on or off, Music or Movie mode, the target curve and custom sliders, the adaptive switches "
                    + "and noise strength, and Bass drop.\n\n"
                    + "WHAT IT DOES NOT KEEP\n"
                    + "The Calibrate measurement, correction strength, the box tuning, the learned cabin noise and "
                    + "the While driving choice. Those belong to the sound profile.\n\n"
                    + "BASS DROP\n"
                    + "A show-off lift: 6 dB more under 80 Hz, fading out by 160 Hz.\n\n"
                    + "LOUD-O-METER\n"
                    + "The level at the microphone now and the loudest it has seen, in dB below the mic's own "
                    + "maximum. It is for comparing one run with another on this tablet. It is not true SPL.";
            case PLAYER: return "WHAT IT DOES\n"
                    + "Plays your own files (FLAC, WAV, M4A, MP3, OGG) from a folder you pick, such as the USB stick, "
                    + "with the room correction applied in the most exact way this app can.\n\n"
                    + "HOW IT WORKS\n"
                    + "It keeps the music at the file's own sample rate and applies the correction from Calibrate as "
                    + "one long filter (convolution) with exactly the correction's gains at every pitch, where the "
                    + "Tablet DSP works in 31 bands. The filter acts the moment a note starts, so nothing rings ahead "
                    + "of the beat.\n\n"
                    + "While it is correcting, the Tablet DSP leaves its own copy of the correction out and keeps "
                    + "only the adaptive parts, so nothing is corrected twice. It works even with the Tablet DSP off "
                    + "or refused by the tablet. With no Calibrate yet, or with Room correction switched off, files "
                    + "play untouched.\n\n"
                    + "ON ITS SCREEN\n"
                    + "Bars: the music leaving the tablet. Green line: the correction in force. Tap a file to play "
                    + "it. Other music apps pause when it plays, and the dash's music button and the stereo's track "
                    + "buttons control it. It keeps playing when the screen is closed.";
            case HEAD_UNIT: return "WHAT IT DOES\n"
                    + "Talks straight to the Kenwood KMM-BT728HD over Bluetooth, on the same link the KENWOOD Remote "
                    + "app uses.\n\n"
                    + "CONNECTING\n"
                    + "First, on the stereo, press the volume knob for FUNCTION and go to REMOTE APP, SELECT, "
                    + "ANDROID, YES, and pick this tablet in ANDROID LIST; its STATUS line shows whether an app is "
                    + "connected. Force-stop the KENWOOD Remote app on the tablet: the stereo keeps one such link. "
                    + "Then tap Connect. It calls the stereo, as the stock app does. If the stereo takes the call and "
                    + "hangs up, it waits for the stereo to call the tablet instead; switching the stereo off and on "
                    + "makes it reconnect.\n\n"
                    + "THE CONTROLS\n"
                    + "Connect: pick the stereo from your paired devices once; it is remembered. Change stereo picks "
                    + "again.\n"
                    + "Mute: mutes and unmutes the stereo.\n"
                    + "Volume: 0 to " + KenwoodLink.VOLUME_MAX + ", the stereo's own scale. It sends when you let go "
                    + "of the slider. The first time, send a low number and check the stereo shows the same one.\n"
                    + "Knob keys: Vol -, Vol + and ATT press the stereo's own keys, one step each, the way a "
                    + "remote would.\n"
                    + "Preset EQ: picks one of the stereo's built-in EQ presets by its place in the stereo's list. "
                    + "The names are not known yet.\n\n"
                    + "HOW IT WORKS\n"
                    + "Each command is sent and the stereo's answer is shown beside the link's state: accepted, "
                    + "answered with a code, or no answer. Every command and answer also goes in Diagnostics. This "
                    + "link has not been proven on the stereo yet.\n\n"
                    + "STILL TO COME\n"
                    + "Source switching, custom EQ, filters, sub level and fader. They wait on the log from the "
                    + "Stereo probe (the dash's Diagnostics menu), which shows how the stereo lays those settings out.";
            case DIAGNOSTICS: return "WHAT IT IS\n"
                    + "A running record of what the sound side of the app did and anything that went wrong.\n\n"
                    + "WHAT GOES IN IT\n"
                    + "The Tablet DSP opening and the gains it applied, each Calibrate with its measurement and "
                    + "correction, box-tune results, cabin-noise learning, every command to the stereo and its "
                    + "answer, and any errors.\n\n"
                    + "HOW TO USE IT\n"
                    + "Open log shows it. Share sends it as text, which is the thing to send when something sounds "
                    + "wrong: it shows exactly what was measured and applied. Clear empties it. It is kept only "
                    + "until the app is closed.";
            default: return "";
        }
    }
}
