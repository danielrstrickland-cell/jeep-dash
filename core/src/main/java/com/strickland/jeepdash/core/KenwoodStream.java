package com.strickland.jeepdash.core;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Cuts the raw byte stream from the stereo's serial link into whole {@link KenwoodFrame}s. Bytes arrive in
 * whatever chunks Bluetooth delivers, so a frame can be split across reads or several can land at once; this
 * holds the unfinished part between calls. A frame runs from a 0x7E to the next 0x7C — neither byte can occur
 * inside one, because the sender stuffs them. Bytes outside a frame are counted and dropped. One reader thread.
 */
public final class KenwoodStream {
    private static final int MAX = 4096;
    private final ByteArrayOutputStream cur = new ByteArrayOutputStream();
    private boolean in;
    private int stray;

    /** Feeds the next chunk; returns every frame it completed, oldest first (often none). */
    public List<byte[]> feed(byte[] chunk, int len) {
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < len; i++) {
            int b = chunk[i] & 0xFF;
            if (b == 0x7E) {                       // a start always restarts, so a lost end cannot jam the reader
                cur.reset();
                cur.write(b);
                in = true;
            } else if (!in) {
                stray++;
            } else {
                cur.write(b);
                if (b == 0x7C) {
                    out.add(cur.toByteArray());
                    cur.reset();
                    in = false;
                } else if (cur.size() > MAX) {
                    stray += cur.size();
                    cur.reset();
                    in = false;
                }
            }
        }
        return out;
    }

    /** Bytes seen outside any frame, or thrown away as an overlong one: a sign of a different protocol. */
    public int strayBytes() { return stray; }
}
