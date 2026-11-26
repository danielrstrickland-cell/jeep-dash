package com.strickland.jeepdash.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** A sound profile's settings packed into one line of text and back, whatever they hold. */
public class SettingsPackTest {

    public static void everyKindOfSettingSurvives() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("noise_comp", true);
        in.put("volt_safe", false);
        in.put("custom_bass", 7);
        in.put("valid_lo", -3);
        in.put("tune_hz", 34.5f);
        in.put("target", "HARMAN");
        Map<String, Object> out = SettingsPack.unpack(SettingsPack.pack(in));
        T.eq(in, out, "the same settings, with the same types");
        T.ok(out.get("tune_hz") instanceof Float, "a float comes back a float, so it reads back as one");
        T.ok(out.get("custom_bass") instanceof Integer, "and a whole number a whole number");
    }

    public static void awkwardTextSurvives() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("measured", "-86.15,-63.29,0.0");
        in.put("noise_profile", "N1;24:-60.0,-59.5;0;30:-48.0");      // has the separators inside it
        in.put("odd", "a=b;c%3Bd 100%");
        T.eq(in, SettingsPack.unpack(SettingsPack.pack(in)), "separators and percent signs inside a value come back whole");
    }

    public static void nothingAndJunkAreEmpty() {
        T.eq(0, SettingsPack.unpack(null).size(), "nothing stored");
        T.eq(0, SettingsPack.unpack("").size(), "an empty profile");
        T.eq(0, SettingsPack.unpack("no equals here").size(), "junk");
    }

    public static void oneBadEntryDoesNotLoseTheRest() {
        Map<String, Object> out = SettingsPack.unpack("a=i12;b=inot-a-number;c=q?;d=btrue");
        T.eq(2, out.size(), "the two good ones");
        T.eq(12, out.get("a"), "the number");
        T.eq(true, out.get("d"), "and the switch");
    }
}
