package com.strickland.jeepdash.core;

import java.io.IOException;
import java.util.Collection;

/** Whatever reads a vehicle through the dongle: the Jeep's runner or the Porsche's. */
public interface Poller {
    /** Chooses which values are read. */
    void setActive(Collection<String> paramIds);

    /** Does the next piece of work; false when there was nothing to do right now. */
    boolean poll() throws IOException;

    /** Total readings so far. */
    int goodReads();

    /** False once the engine computer has stopped answering (key off?). */
    boolean vehicleAnswering();

    /** True while the dongle is sending a keep-alive that leave() should get the time to stop. */
    boolean keepAliveRunning();

    /** Orderly stop, best effort. */
    void leave();

    /** Records every exchange (optional). */
    void setTrace(Trace t);
}
