package org.recompile.mobile.dls;

/** Loop, tuning, and attenuation metadata for a region or wave. */
public final class SampleInfo {
    public boolean present;
    public int loopMode = SynthesisSupport.LOOP_NONE;
    public int unityNote = 60;
    public int fineTuneCents = 0;
    public int attenuation = 0;
    public int loopStart = 0;
    public int loopEndInclusive = -1;

    SampleInfo effectiveWith(SampleInfo fallback) {
        if (present) {
            return this;
        }
        return fallback == null ? this : fallback;
    }
}



