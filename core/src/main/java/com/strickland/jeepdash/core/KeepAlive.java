package com.strickland.jeepdash.core;

/**
 * What the link service should do once no screen is open and the Jeep has sent nothing for a while. Without
 * sound to look after it stops, as it always has. With the Tablet DSP on (or the lossless player playing) it
 * lets go of the dongle but stays alive, so the DSP carries on over whatever else the tablet plays; and it
 * stops later, when that sound is no longer wanted.
 */
public final class KeepAlive {
    public enum Action { KEEP, SOUND_ONLY, STOP }

    private KeepAlive() { }

    /**
     * @param inUse       a screen is open, or the Jeep sent data lately
     * @param idleMs      how long it has been since that was last true
     * @param dongleOn    the dongle link is being kept up
     * @param soundOnly   already staying alive only for the sound
     * @param soundWanted the Tablet DSP is on, or the player is playing
     */
    public static Action decide(boolean inUse, long idleMs, long limitMs, boolean dongleOn, boolean soundOnly,
                                boolean soundWanted) {
        if (inUse || idleMs <= limitMs) return Action.KEEP;
        if (soundWanted) return dongleOn ? Action.SOUND_ONLY : Action.KEEP;
        return dongleOn || soundOnly ? Action.STOP : Action.KEEP;
    }
}
