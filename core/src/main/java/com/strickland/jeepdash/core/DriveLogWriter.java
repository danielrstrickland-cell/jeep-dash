package com.strickland.jeepdash.core;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/** One CSV per drive: time_ms, then one column per logged value (empty when there's no fresh reading). */
public final class DriveLogWriter {
    private final Writer out;
    private final List<String> ids;

    public DriveLogWriter(Writer out, List<String> paramIds) throws IOException {
        this.out = out;
        this.ids = new ArrayList<>(paramIds);
        StringBuilder sb = new StringBuilder("time_ms");
        for (String id : ids) sb.append(',').append(id);
        out.write(sb.append('\n').toString());
    }

    public void row(long timeMs, LiveValues v) throws IOException {
        StringBuilder sb = new StringBuilder().append(timeMs);
        for (String id : ids) {
            Double d = v.get(id, timeMs);
            sb.append(',');
            if (d != null) sb.append(d);
        }
        out.write(sb.append('\n').toString());
    }

    public void flush() throws IOException { out.flush(); }

    public void close() throws IOException { out.close(); }
}
