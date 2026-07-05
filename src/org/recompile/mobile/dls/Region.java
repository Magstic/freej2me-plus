package org.recompile.mobile.dls;

/** Playable key and velocity region mapped to a wave sample. */
public final class Region extends SynthesisSupport {
    public final boolean level2;
    public int keyLow = 0;
    public int keyHigh = 127;
    public int velocityLow = 0;
    public int velocityHigh = 127;
    public int options = 0;
    public int keyGroup = 0;
    public int channel = 0;
    public int tableIndex = -1;
    public int index = 0;
    public Articulation articulation;
    public final SampleInfo sample = new SampleInfo();
    boolean ownsArticulation;

    Region(boolean level2, Articulation inheritedArticulation) {
        this.level2 = level2;
        this.articulation = inheritedArticulation;
    }

    public boolean contains(int key, int velocity) {
        return key >= keyLow && key <= keyHigh && velocity >= velocityLow && velocity <= velocityHigh;
    }
}



