package org.recompile.mobile.dls;

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
    final int[] programAliases;
    final int[] percussionKeyAliases;
    final Map<Integer, Integer> programAliasSelectors;

    DlsBank(String sourceName, String formType, int declaredInstrumentCount,
                    int articulationChunkCount, int articulationConnectionCount,
                    List<Instrument> instruments, List<Wave> waves) {
        this(sourceName, formType, declaredInstrumentCount, articulationChunkCount, articulationConnectionCount,
                instruments, waves, null, null, null);
    }

    DlsBank(String sourceName, String formType, int declaredInstrumentCount,
                    int articulationChunkCount, int articulationConnectionCount,
                    List<Instrument> instruments, List<Wave> waves, int[] programAliases) {
        this(sourceName, formType, declaredInstrumentCount, articulationChunkCount, articulationConnectionCount,
                instruments, waves, programAliases, null, null);
    }

    DlsBank(String sourceName, String formType, int declaredInstrumentCount,
                    int articulationChunkCount, int articulationConnectionCount,
                    List<Instrument> instruments, List<Wave> waves, int[] programAliases,
                    Map<Integer, Integer> programAliasSelectors) {
        this(sourceName, formType, declaredInstrumentCount, articulationChunkCount, articulationConnectionCount,
                instruments, waves, programAliases, null, programAliasSelectors);
    }

    DlsBank(String sourceName, String formType, int declaredInstrumentCount,
                    int articulationChunkCount, int articulationConnectionCount,
                    List<Instrument> instruments, List<Wave> waves, int[] programAliases, int[] percussionKeyAliases,
                    Map<Integer, Integer> programAliasSelectors) {
        this.sourceName = sourceName;
        this.formType = formType;
        this.declaredInstrumentCount = declaredInstrumentCount;
        this.articulationChunkCount = articulationChunkCount;
        this.articulationConnectionCount = articulationConnectionCount;
        this.instruments = Collections.unmodifiableList(instruments);
        this.waves = Collections.unmodifiableList(waves);
        this.programAliases = programAliases == null ? null : programAliases.clone();
        this.percussionKeyAliases = percussionKeyAliases == null ? null : percussionKeyAliases.clone();
        Map<Integer, Integer> aliasSelectors = new HashMap<Integer, Integer>();
        if (programAliasSelectors != null) {
            aliasSelectors.putAll(programAliasSelectors);
        } else if (programAliases != null) {
            for (int i = 0; i < programAliases.length; i++) {
                if (programAliases[i] >= 0) {
                    aliasSelectors.put(SynthesisSupport.selector(121, 0, i),
                            SynthesisSupport.selector(121, 0, programAliases[i]));
                }
            }
        }
        this.programAliasSelectors = Collections.unmodifiableMap(aliasSelectors);
        this.bySelector = new HashMap<Integer, Instrument>();
        for (Instrument instrument : instruments) {
            int selector = SynthesisSupport.selector(instrument.bankMsb, instrument.bankLsb, instrument.program);
            if (bySelector.put(selector, instrument) != null) {
                throw new IllegalArgumentException(sourceName + ": duplicate instrument selector 0x"
                        + Integer.toHexString(selector));
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
        if (programAliases == null || program < 0 || program >= programAliases.length
                || programAliases[program] < 0) {
            return program & 0x7F;
        }
        return programAliases[program] & 0x7F;
    }

    public int percussionKeyAliasFor(int bankSelector, int key) {
        if ((bankSelector & 0x3F80) != (120 << 7) || percussionKeyAliases == null) {
            return key & 0x7F;
        }
        return percussionKeyAliases[key & 0x7F] & 0x7F;
    }

    Instrument midiInstrument(int bankSelector, int program) {
        int bankMsb = (bankSelector >> 7) & 0x7F;
        int bankLsb = bankSelector & 0x7F;
        Instrument exact = instrumentFor(bankMsb, bankLsb, program);
        if (exact != null) {
            return exact;
        }
        Instrument alias = aliasInstrumentFor(bankMsb, bankLsb, program);
        if (alias != null) {
            return alias;
        }
        if ((bankSelector & 0x3F80) == (121 << 7)) {
            return bankLsb == 0 ? null : aliasOrInstrumentFor(121, 0, program);
        }
        return bankMsb == 120 && bankLsb == 0 && (program & 0x7F) == 0
                ? null : instrumentFor(120, 0, 0);
    }

    Instrument aliasOrInstrumentFor(int bankMsb, int bankLsb, int program) {
        Instrument instrument = instrumentFor(bankMsb, bankLsb, program);
        return instrument != null ? instrument : aliasInstrumentFor(bankMsb, bankLsb, program);
    }

    Instrument aliasInstrumentFor(int bankMsb, int bankLsb, int program) {
        Integer targetSelector = programAliasSelectors.get(SynthesisSupport.selector(bankMsb, bankLsb, program));
        return targetSelector == null ? null : bySelector.get(targetSelector);
    }
}



