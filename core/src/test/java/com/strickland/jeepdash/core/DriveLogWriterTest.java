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
}
