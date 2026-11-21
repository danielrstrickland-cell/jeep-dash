package com.strickland.jeepdash.core;

/** One live value the dash can show, with how to read and decode it on each line. */
public final class Param {

    /** Converts the raw data bytes (A, and B for 2-byte values) to the displayed unit. */
    public interface Formula { double apply(int a, int b); }

    public final String id, name, unit;
    public final Integer pid;          // generic OBD2 Mode 01 PID, or null
    public final int[] sciIds;         // SCI F4 ids (1 or 2), or null
    public final double min, max;
    public final int weight;           // relative poll frequency
    public final boolean tablet;       // worked out from the tablet's own sensors; never asked of the Jeep
    private final Formula sciFormula, genericFormula;

    public Param(String id, String name, String unit, int[] sciIds, Formula sciFormula,
                 Integer pid, Formula genericFormula, double min, double max, int weight) {
        this(id, name, unit, sciIds, sciFormula, pid, genericFormula, min, max, weight, false);
    }

    private Param(String id, String name, String unit, int[] sciIds, Formula sciFormula,
                  Integer pid, Formula genericFormula, double min, double max, int weight, boolean tablet) {
        this.id = id; this.name = name; this.unit = unit;
        this.sciIds = sciIds; this.sciFormula = sciFormula;
        this.pid = pid; this.genericFormula = genericFormula;
        this.min = min; this.max = max; this.weight = weight;
        this.tablet = tablet;
    }

    /** A value from the tablet's sensors (GPS, accelerometer): nothing to request and nothing to decode. */
    public static Param tablet(String id, String name, String unit, double min, double max) {
        return new Param(id, name, unit, null, null, null, null, min, max, 1, true);
    }

    /** True when the value can only be read over generic OBD2 (polled in occasional blocks). */
    public boolean slow() { return sciIds == null && !tablet; }

    /** Decodes a reply's data bytes; null when the reply is not for this value or is too short. */
    public Double decode(Source s, byte[] reply) {
        if (reply == null || tablet) return null;
        if (s == Source.SCI) {
            if (sciIds == null) return null;
            if (sciIds.length == 1) {
                if (reply.length < 2 || (reply[0] & 0xff) != sciIds[0]) return null;
                return sciFormula.apply(reply[1] & 0xff, 0);
            }
            // two ids: reply is <hiValue> <loId> <loValue>
            if (reply.length < 3 || (reply[1] & 0xff) != sciIds[1]) return null;
            return sciFormula.apply(reply[0] & 0xff, reply[2] & 0xff);
        }
        // generic: 48 6b <ecu> 41 <pid> A [B] <checksum>
        if (pid == null || reply.length < 7) return null;
        if ((reply[3] & 0xff) != 0x41 || (reply[4] & 0xff) != pid) return null;
        int a = reply[5] & 0xff;
        int b = reply.length >= 8 ? reply[6] & 0xff : 0;
        return genericFormula.apply(a, b);
    }
}
