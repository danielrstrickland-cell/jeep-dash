package com.strickland.jeepdash.core;

/**
 * The Jeep's stock main spark table, read from the owner's own engine computer (part 56041519AE, the 1998 5.9;
 * flash read of 2026-11-24, table at 0x286B, 17 engine-speed rows by 9 manifold-pressure columns, degrees =
 * raw / 2). Checked against the logged drive of 2026-11-24 18:44: the timing the engine computer reported was a
 * median 0.2 degrees below this table, and 61% of warm readings above 900 rpm were within 2 degrees.
 *
 * The engine computer looks a column up the way it does itself: manifold pressure in its own counts
 * (kPa x 2.428), through its uneven pressure axis to a column number, then a straight-line blend between the
 * four nearest cells. Below and above each axis the edge cells hold.
 */
public final class SparkTable {
    private SparkTable() { }

    /** Engine speed of each row. */
    static final double[] RPM = {
            608, 704, 800, 896, 992, 1196, 1401, 1606, 1811, 2016, 2413, 2811, 3209, 3606, 4004, 4402, 4800};

    /** Manifold pressure counts per kPa (the dash's MAP reading is counts x 0.412). */
    static final double MAP_RAW_PER_KPA = 2.428;

    /** The pressure axis: counts against 32 x the column number (uneven below column 4). */
    private static final double[] AXIS_RAW = {36, 58, 76, 96, 128, 160, 192, 224, 255};
    private static final double[] AXIS_COL32 = {0, 32, 64, 96, 128, 160, 192, 224, 255};

    /** Degrees of advance, one row per engine speed above, one column per pressure step (low to high). */
    static final double[][] DEG = {
            {20, 20, 20, 20, 20, 24, 15, 4, 3},
            {20, 20, 20, 20, 20, 20, 16, 6, 5},
            {26, 26, 26, 26, 26, 26, 17, 8, 7},
            {30, 30, 30, 30, 30, 27, 17, 11, 10},
            {34, 34, 34, 34, 32, 27, 25, 20, 11},
            {36, 36, 36, 36, 39, 35, 24, 12, 11},
            {40, 40, 40, 40, 40, 36, 28, 20, 19},
            {40, 40, 40, 40, 40, 36, 30, 22, 21},
            {40, 40, 40, 40, 40, 38, 30, 22, 21},
            {40, 40, 40, 40, 40, 39, 38, 25, 24},
            {40, 40, 40, 40, 40, 39, 38, 33, 30},
            {40, 40, 40, 40, 40, 40, 36, 27, 22},
            {40, 40, 40, 40, 40, 38, 30, 29, 20},
            {40, 40, 40, 40, 39, 34, 31, 29, 22},
            {40, 40, 40, 40, 37, 35, 31, 30, 31},
            {40, 40, 40, 40, 40, 34, 33, 31, 31},
            {40, 40, 40, 40, 40, 37, 33, 34, 32}};

    /** The table's timing in degrees for this engine speed and manifold pressure. */
    public static double advance(double rpm, double mapKpa) {
        double r = row(rpm), c = column(mapKpa);
        int r0 = Math.min((int) r, RPM.length - 2), c0 = Math.min((int) c, DEG[0].length - 2);
        double fr = r - r0, fc = c - c0;
        double low = DEG[r0][c0] * (1 - fc) + DEG[r0][c0 + 1] * fc;
        double high = DEG[r0 + 1][c0] * (1 - fc) + DEG[r0 + 1][c0 + 1] * fc;
        return low * (1 - fr) + high * fr;
    }

    /** Row number, with the fraction toward the next row: 0 to 16. */
    static double row(double rpm) {
        if (rpm <= RPM[0]) return 0;
        for (int i = 1; i < RPM.length; i++)
            if (rpm <= RPM[i]) return i - 1 + (rpm - RPM[i - 1]) / (RPM[i] - RPM[i - 1]);
        return RPM.length - 1;
    }

    /** Column number, with the fraction toward the next column: 0 to just under 8, as the engine computer has it. */
    static double column(double mapKpa) {
        double raw = mapKpa * MAP_RAW_PER_KPA;
        double col32;
        if (raw <= AXIS_RAW[0]) col32 = AXIS_COL32[0];
        else if (raw >= AXIS_RAW[AXIS_RAW.length - 1]) col32 = AXIS_COL32[AXIS_COL32.length - 1];
        else {
            int i = 1;
            while (raw > AXIS_RAW[i]) i++;
            col32 = AXIS_COL32[i - 1] + (AXIS_COL32[i] - AXIS_COL32[i - 1]) * (raw - AXIS_RAW[i - 1]) / (AXIS_RAW[i] - AXIS_RAW[i - 1]);
        }
        return col32 / 32;
    }
}
