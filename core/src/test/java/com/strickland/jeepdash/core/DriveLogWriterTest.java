package com.strickland.jeepdash.core;

import java.io.StringWriter;
import java.util.Arrays;

public class DriveLogWriterTest {
    public static void csvHeaderAndRows() throws Exception {
        StringWriter out = new StringWriter();
        DriveLogWriter w = new DriveLogWriter(out, Arrays.asList("rpm", "coolant"));
        LiveValues v = new LiveValues();
        v.put("rpm", 720, 1000);
        w.row(1000, v);
        w.flush();
        T.eq("time_ms,rpm,coolant\n1000,720.0,\n", out.toString(), "header plus one row, empty cell when missing");
    }

    public static void rowTimeCanDifferFromTheClockUsedForFreshness() throws Exception {
        java.io.StringWriter w = new java.io.StringWriter();
        DriveLogWriter log = new DriveLogWriter(w, java.util.Arrays.asList("rpm"));
        LiveValues v = new LiveValues();
        v.put("rpm", 720, 500000);
        log.row(2000, 501000, v);          // 2 s into the drive; tablet clock at 501 s
        log.row(9000, 508000, v);          // reading is 8 s old by now
        log.flush();
        T.eq("time_ms,rpm\n2000,720.0\n9000,\n", w.toString(), "drive time in the column, tablet clock for freshness");
    }
}
