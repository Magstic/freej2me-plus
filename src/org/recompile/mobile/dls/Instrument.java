package org.recompile.mobile.dls;

import java.util.Collections;
import java.util.List;

/** DLS instrument with bank selector, articulation, and regions. */
public final class Instrument extends SynthesisSupport {
    public final int rawBank;
    public final int rawInstrument;
    public final int bankMsb;
    public final int bankLsb;
    public final int program;
    public final boolean drum;
    public final Articulation articulation;
    public final List<Region> regions;

    Instrument(int rawBank, int rawInstrument, String formType,
                       Articulation articulation, List<Region> regions) {
        this(rawBank, rawInstrument, "DLSM".equals(formType)
                        || ((rawBank >>> 8) & 0x7F) != 0
                        || (rawBank & 0x7F) != 0,
                articulation, regions);
    }

    Instrument(int rawBank, int rawInstrument, boolean rawMode,
                       Articulation articulation, List<Region> regions) {
        int rawLsb = rawBank & 0x7F;
        int rawMsb = (rawBank >>> 8) & 0x7F;
        this.rawBank = rawBank;
        this.rawInstrument = rawInstrument;
        this.program = rawInstrument & 0x7F;
        this.drum = (rawBank & 0x80000000) != 0;
        this.bankMsb = rawMode ? rawMsb : (drum ? 120 : 121);
        this.bankLsb = rawMode ? rawLsb : rawMsb;
        this.articulation = articulation;
        this.regions = Collections.unmodifiableList(regions);
    }

    public Region regionFor(int key, int velocity) {
        for (Region region : regions) {
            if (region.contains(key, velocity)) {
                return region;
            }
        }
        return null;
    }
}



