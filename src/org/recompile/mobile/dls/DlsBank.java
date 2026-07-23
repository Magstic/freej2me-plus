package org.recompile.mobile.dls;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Parsed DLS bank aggregate and instrument lookup rules. */
public final class DlsBank {
    public final String sourceName;
    public final String formType;
    public final int declaredInstrumentCount;
    public final int articulationChunkCount;
    public final int articulationConnectionCount;
    public final List<Instrument> instruments;
    public final List<Wave> waves;
    final Map<Integer, Instrument> bySelector;
    final int[] percussionKeyAliases;
    final Map<Integer, Integer> programAliasSelectors;

    DlsBank(String sourceName, String formType, int declaredInstrumentCount,
                    int articulationChunkCount, int articulationConnectionCount,
                    List<Instrument> instruments, List<Wave> waves) {
        this(sourceName, formType, declaredInstrumentCount, articulationChunkCount, articulationConnectionCount,
                instruments, waves, null, null);
    }

    DlsBank(String sourceName, String formType, int declaredInstrumentCount,
                    int articulationChunkCount, int articulationConnectionCount,
                    List<Instrument> instruments, List<Wave> waves, int[] percussionKeyAliases,
                    Map<Integer, Integer> programAliasSelectors) {
        this.sourceName = sourceName;
        this.formType = formType;
        this.declaredInstrumentCount = declaredInstrumentCount;
        this.articulationChunkCount = articulationChunkCount;
        this.articulationConnectionCount = articulationConnectionCount;
        this.instruments = Collections.unmodifiableList(new ArrayList<Instrument>(instruments));
        this.waves = Collections.unmodifiableList(new ArrayList<Wave>(waves));
        this.percussionKeyAliases = percussionKeyAliases == null ? null : percussionKeyAliases.clone();
        Map<Integer, Integer> aliasSelectors = new HashMap<Integer, Integer>();
        if (programAliasSelectors != null) {
            aliasSelectors.putAll(programAliasSelectors);
        }
        this.programAliasSelectors = Collections.unmodifiableMap(aliasSelectors);
        this.bySelector = new HashMap<Integer, Instrument>();
        for (Instrument instrument : this.instruments) {
            int selector = SynthesisSupport.selector(instrument.bankMsb, instrument.bankLsb, instrument.program);
            if (bySelector.put(selector, instrument) != null) {
                throw new IllegalArgumentException(sourceName + ": duplicate instrument selector 0x"
                        + Integer.toHexString(selector));
            }
            for (Region region : instrument.regions) {
                if (region.tableIndex < 0 || region.tableIndex >= this.waves.size()) {
                    throw new IllegalArgumentException(sourceName + ": region references missing wave "
                            + region.tableIndex);
                }
                Wave wave = this.waves.get(region.tableIndex);
                if (wave.channels != 1 && wave.channels != 2) {
                    throw new IllegalArgumentException(sourceName + ": region references unsupported "
                            + wave.channels + "-channel wave " + region.tableIndex);
                }
                if (!region.sample.present) {
                    region.sample = wave.sample;
                }
            }
        }
    }

    public int regionCount() {
        int count = 0;
        for (Instrument instrument : instruments) {
            count += instrument.regions.size();
        }
        return count;
    }

    public int waveCountByFormatTag(int tag) {
        int count = 0;
        for (Wave wave : waves) {
            if (wave.formatTag == tag) {
                count++;
            }
        }
        return count;
    }

    public int nonZeroAttenuationCount() {
        int count = 0;
        for (Instrument instrument : instruments) {
            for (Region region : instrument.regions) {
                if (region.sample.attenuation != 0) {
                    count++;
                }
            }
        }
        for (Wave wave : waves) {
            if (wave.sample.attenuation != 0) {
                count++;
            }
        }
        return count;
    }

    public Instrument instrumentFor(int bankMsb, int bankLsb, int program) {
        return bySelector.get(SynthesisSupport.selector(bankMsb, bankLsb, program));
    }

    public int programAliasFor(int program) {
        int sourceProgram = program & 0x7F;
        Integer target = programAliasSelectors.get(SynthesisSupport.selector(121, 0, sourceProgram));
        return target == null ? sourceProgram : target & 0x7F;
    }

    public int percussionKeyAliasFor(int bankSelector, int key) {
        if ((bankSelector & 0x3F80) != (120 << 7) || percussionKeyAliases == null) {
            return key & 0x7F;
        }
        return percussionKeyAliases[key & 0x7F] & 0x7F;
    }

    boolean hasPercussionKeyAliasesFor(int bankSelector) {
        return (bankSelector & 0x3F80) == (120 << 7) && percussionKeyAliases != null;
    }

    Instrument midiInstrument(int bankSelector, int program) {
        int bankMsb = (bankSelector >> 7) & 0x7F;
        int bankLsb = bankSelector & 0x7F;
        int sourceSelector = SynthesisSupport.selector(bankMsb, bankLsb, program);
        Instrument instrument = bySelector.get(sourceSelector);
        if (instrument != null) {
            return instrument;
        }
        Integer aliasSelector = programAliasSelectors.get(sourceSelector);
        if (aliasSelector != null) {
            instrument = bySelector.get(aliasSelector);
            if (instrument != null) {
                return instrument;
            }
        }
        if ((bankSelector & 0x3F80) == (121 << 7)) {
            if (bankLsb == 0) {
                return null;
            }
            int defaultSelector = SynthesisSupport.selector(121, 0, program);
            instrument = bySelector.get(defaultSelector);
            if (instrument != null) {
                return instrument;
            }
            aliasSelector = programAliasSelectors.get(defaultSelector);
            return aliasSelector == null ? null : bySelector.get(aliasSelector);
        }
        return bankMsb == 120 && bankLsb == 0 && (program & 0x7F) == 0
                ? null : instrumentFor(120, 0, 0);
    }
}



