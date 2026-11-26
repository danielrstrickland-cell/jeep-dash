package com.strickland.jeepdash.core;

/** The words behind each card's information button in the Sound hub. */
public class SoundHelpTest {

    public static void everyCardHasItsOwnExplanation() {
        T.eq(8, SoundHelp.TOPICS.length, "one per card");
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String topic : SoundHelp.TOPICS) {
            T.ok(!SoundHelp.title(topic).isEmpty(), topic + " has a title");
            T.ok(SoundHelp.text(topic).length() > 200, topic + " says what it does and how it works");
            T.ok(seen.add(SoundHelp.text(topic)), topic + " has words of its own");
        }
        T.eq("", SoundHelp.text("no such card"), "an unknown card has nothing to say");
    }

    public static void theNumbersQuotedAreTheOnesTheCodeUses() {
        T.ok(SoundHelp.text(SoundHelp.HEAD_UNIT).contains("0 to " + KenwoodLink.VOLUME_MAX), "the stereo's volume range");
        T.ok(SoundHelp.text(SoundHelp.ADAPTIVE).contains((int) BoxTune.LO_HZ + " to " + (int) BoxTune.HI_HZ + " Hz"),
                "the box-tune sweep's range");
        T.ok(SoundHelp.text(SoundHelp.ADAPTIVE).contains("up to " + (int) SoundAdapt.MAX_BOOST_DB + " dB"),
                "the most noise compensation adds");
        T.ok(SoundHelp.text(SoundHelp.DSP).contains((int) SoundAdapt.VOICE_DB + " dB"), "the voice lift");
        T.ok(SoundHelp.text(SoundHelp.DSP).contains("Stop"), "how to switch the background sound off");
    }
}
