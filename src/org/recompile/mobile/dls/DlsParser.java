package org.recompile.mobile.dls;

import static org.recompile.mobile.dls.SynthesisSupport.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** DLS/RIFF parser and wave decoders. */
final class DlsParser {
    final byte[] data;
    final String sourceName;
    String formType;
    int declaredInstrumentCount = -1;
    int[] poolOffsets = new int[0];
    int wvplChunkData = -1;
    int wvplChunkSize = 0;
    int articulationChunkCount = 0;
    int articulationConnectionCount = 0;
    final List<Instrument> instruments = new ArrayList<Instrument>();
    final List<Wave> waves = new ArrayList<Wave>();
    int[] programAliases;
    int[] percussionKeyAliases;
    Map<Integer, Integer> programAliasSelectors;
    boolean selectorRawModeActive;
    boolean selectorImplicitModeSeen;

    DlsParser(byte[] data, String sourceName) {
        this.data = data;
        this.sourceName = sourceName;
    }

    static DlsBank parse(byte[] data, String sourceName) {
        DlsParser parser = new DlsParser(data, sourceName);
        parser.parseRoot();
        if (parser.declaredInstrumentCount >= 0 && parser.declaredInstrumentCount != parser.instruments.size()) {
            throw parser.error(0, "colh instrument count does not match parsed lins");
        }
        return new DlsBank(sourceName, parser.formType, parser.declaredInstrumentCount,
                parser.articulationChunkCount, parser.articulationConnectionCount,
                parser.instruments, parser.waves, parser.programAliases, parser.percussionKeyAliases,
                parser.programAliasSelectors);
    }

    void parseRoot() {
        require(0, 12);
        if (!fourcc(0).equals("RIFF")) {
            throw error(0, "DLS must start with RIFF");
        }
        int riffSize = u32(4);
        formType = fourcc(8);
        if (!formType.equals("DLS ") && !formType.equals("DLSM")) {
            throw error(8, "unsupported DLS form " + formType);
        }
        int end = rootRiffEnd(riffSize);
        if (!cdlPasses(12, end)) {
            throw error(12, "cdl condition failed");
        }
        for (int p = 12; p < end; p = nextChunk(p)) {
            String id = fourcc(p);
            int size = u32(p + 4);
            int body = p + 8;
            if (id.equals("colh")) {
                declaredInstrumentCount = u32(body);
            } else if (id.equals("ptbl")) {
                parsePoolTable(body, size);
            } else if (id.equals("LIST") && size >= 4 && fourcc(body).equals("lins")) {
                parseLins(body + 4, body + size);
            } else if (id.equals("LIST") && size >= 4 && fourcc(body).equals("wvpl")) {
                wvplChunkData = body;
                wvplChunkSize = size;
            } else if (id.equals("pgal")) {
                parsePgal(body, size);
            }
        }
        if (wvplChunkData >= 0) {
            parseWavePool();
        }
    }

    void parsePoolTable(int body, int size) {
        if (size < 8) {
            throw error(body, "short ptbl");
        }
        int count = u32(body + 4);
        if (count < 0 || 8 + count * 4L > size) {
            throw error(body, "bad ptbl count");
        }
        poolOffsets = new int[count];
        for (int i = 0; i < count; i++) {
            poolOffsets[i] = u32(body + 8 + i * 4);
        }
    }

    void parsePgal(int body, int size) {
        int versionMarker = size >= 4 ? u32(body) : -1;
        int version;
        int tableOffset;
        int countOffset;
        int recordOffset;
        if (versionMarker == 1 || versionMarker == 2) {
            version = versionMarker;
            tableOffset = body + 4;
            countOffset = body + 132;
            recordOffset = body + 136;
        } else if (versionMarker == 0x03020100) {
            version = 0;
            tableOffset = body;
            countOffset = body + 128;
            recordOffset = body + 132;
        } else {
            return;
        }
        if (recordOffset - body > size) {
            return;
        }
        int count = u32(countOffset);
        if (count < 0 || recordOffset - body + count * 8L != size) {
            return;
        }
        int[] keyAliases = new int[128];
        for (int i = 0; i < keyAliases.length; i++) {
            keyAliases[i] = data[tableOffset + i] & 0x7F;
        }
        int[] aliases = new int[128];
        Arrays.fill(aliases, -1);
        Map<Integer, Integer> aliasSelectors = new HashMap<Integer, Integer>();
        for (int i = 0; i < count; i++) {
            int p = recordOffset + i * 8;
            int fromBank = u16(p);
            int fromProgram = u16(p + 2) & 0x7F;
            int toBank = u16(p + 4);
            int toProgram = u16(p + 6) & 0x7F;
            if (version < 2) {
                fromBank = pgalLegacyBank(fromBank);
                toBank = pgalLegacyBank(toBank);
            }
            aliases[fromProgram] = toProgram;
            aliasSelectors.put(selector(pgalBankMsb(fromBank), pgalBankLsb(fromBank), fromProgram),
                    selector(pgalBankMsb(toBank), pgalBankLsb(toBank), toProgram));
        }
        percussionKeyAliases = keyAliases;
        programAliases = aliases;
        programAliasSelectors = aliasSelectors;
    }

    int pgalLegacyBank(int bank) {
        return (bank & 0x7F) | 0x3C80;
    }

    int pgalBankMsb(int bank) {
        return (bank >>> 7) & 0x7F;
    }

    int pgalBankLsb(int bank) {
        return bank & 0x7F;
    }

    void parseLins(int start, int end) {
        for (int p = start; p < end; p = nextChunk(p)) {
            String id = fourcc(p);
            int size = u32(p + 4);
            int body = p + 8;
            if (id.equals("LIST") && size >= 4 && fourcc(body).equals("ins ")) {
                instruments.add(parseInstrument(body + 4, body + size));
            }
        }
    }

    Instrument parseInstrument(int start, int end) {
        int rawBank = 0;
        int rawInstrument = 0;
        int declaredRegions = -1;
        boolean sawInsh = false;
        Articulation articulation = new Articulation();
        List<Region> regions = new ArrayList<Region>();
        for (int p = start; p < end; p = nextChunk(p)) {
            String id = fourcc(p);
            int size = u32(p + 4);
            int body = p + 8;
            if (id.equals("insh")) {
                if (size < 12) {
                    throw error(body, "short insh");
                }
                sawInsh = true;
                declaredRegions = u32(body);
                rawBank = u32(body + 4);
                rawInstrument = u32(body + 8);
            } else if (id.equals("LIST") && size >= 4 && fourcc(body).equals("lrgn")) {
                parseRegions(body + 4, body + size, articulation, regions);
            } else if (id.equals("LIST") && size >= 4
                    && (fourcc(body).equals("lart") || fourcc(body).equals("lar2"))) {
                parseArticulationList(body + 4, body + size, articulation);
            }
        }
        if (!sawInsh || declaredRegions <= 0 || regions.size() != declaredRegions) {
            throw error(start, "instrument region count mismatch");
        }
        articulation.addDefaultConnections();
        return new Instrument(rawBank, rawInstrument, selectorRawMode(rawBank, start), articulation, regions);
    }

    boolean selectorRawMode(int rawBank, int errorOffset) {
        if ("DLSM".equals(formType)) {
            return true;
        }
        int rawLsb = rawBank & 0x7F;
        int rawMsb = (rawBank >>> 8) & 0x7F;
        if (rawMsb == 120 || rawMsb == 121 || rawLsb != 0) {
            if (selectorImplicitModeSeen) {
                throw error(errorOffset, "mixed implicit and explicit DLS bank selectors");
            }
            selectorRawModeActive = true;
            return true;
        }
        if (selectorRawModeActive) {
            return true;
        }
        selectorImplicitModeSeen = true;
        return false;
    }

    void parseRegions(int start, int end, Articulation inheritedArticulation, List<Region> regions) {
        for (int p = start; p < end; p = nextChunk(p)) {
            String id = fourcc(p);
            int size = u32(p + 4);
            int body = p + 8;
            if (id.equals("LIST") && size >= 4) {
                String type = fourcc(body);
                if (type.equals("rgn ") || type.equals("rgn2")) {
                    if (!cdlPasses(body + 4, body + size)) {
                        continue;
                    }
                    Region region = parseRegion(body + 4, body + size, type.equals("rgn2"), inheritedArticulation);
                    region.index = regions.size();
                    regions.add(region);
                }
            }
        }
    }

    Region parseRegion(int start, int end, boolean level2, Articulation inheritedArticulation) {
        Region region = new Region(level2, inheritedArticulation);
        boolean sawRgnh = false;
        for (int p = start; p < end; p = nextChunk(p)) {
            String id = fourcc(p);
            int size = u32(p + 4);
            int body = p + 8;
            if (id.equals("rgnh")) {
                if (size < 12) {
                    throw error(body, "short rgnh");
                }
                sawRgnh = true;
                region.keyLow = u16(body);
                region.keyHigh = u16(body + 2);
                region.velocityLow = u16(body + 4);
                region.velocityHigh = u16(body + 6);
                region.options = u16(body + 8);
                region.keyGroup = u16(body + 10);
            } else if (id.equals("wlnk")) {
                if (size < 12) {
                    throw error(body, "short wlnk");
                }
                region.channel = u32(body + 4);
                region.tableIndex = u32(body + 8);
            } else if (id.equals("wsmp")) {
                parseWsmp(body, size, region.sample);
            } else if (id.equals("LIST") && size >= 4
                    && (fourcc(body).equals("lart") || fourcc(body).equals("lar2"))) {
                if (!cdlPasses(body + 4, body + size)) {
                    continue;
                }
                if (!region.ownsArticulation) {
                    region.articulation = new Articulation();
                    region.ownsArticulation = true;
                }
                parseArticulationList(body + 4, body + size, region.articulation);
            }
        }
        if (!sawRgnh) {
            throw error(start, "region missing rgnh");
        }
        if (region.ownsArticulation) {
            region.articulation.addDefaultConnections();
        }
        return region;
    }

    void parseArticulationList(int start, int end, Articulation articulation) {
        if (!cdlPasses(start, end)) {
            return;
        }
        for (int p = start; p < end; p = nextChunk(p)) {
            String id = fourcc(p);
            int size = u32(p + 4);
            if (id.equals("art1") || id.equals("art2")) {
                parseArticulationChunk(p + 8, size, articulation);
            }
        }
    }

    void parseArticulationChunk(int body, int size, Articulation articulation) {
        articulationChunkCount++;
        if (size < 8) {
            throw error(body, "short art chunk");
        }
        int count = u32(body + 4);
        if (8 + count * 12L > size) {
            throw error(body, "bad art connection count");
        }
        articulationConnectionCount += count;
        for (int i = 0; i < count; i++) {
            int p = body + 8 + i * 12;
            Connection connection = new Connection(u16(p), u16(p + 2), u16(p + 4), u16(p + 6), i32(p + 8));
            if (!isAllowedConnection(connection)) {
                throw error(p, "unsupported articulation connection");
            }
            articulation.apply(connection);
        }
    }

    boolean isAllowedConnection(Connection c) {
        boolean source = c.source == 0 || (c.source >= 1 && c.source <= 10)
                || c.source == 0x81 || c.source == 0x87 || c.source == 0x8A || c.source == 0x8B
                || c.source == 0xDB || c.source == 0xDD
                || c.source == 0x101 || c.source == 0x102
                || (c.source >= 0xC6 && c.source <= 0xCF);
        boolean control = c.control == 0 || (c.control >= 7 && c.control <= 10)
                || c.control == 0x81 || (c.control >= 0x100 && c.control <= 0x102);
        boolean destination = (c.destination >= 0 && c.destination <= 5)
                || c.destination == 0x80 || c.destination == 0x81
                || c.destination == 0x104 || c.destination == 0x105
                || c.destination == 0x114 || c.destination == 0x115 || c.destination == 0x116
                || (c.destination >= 0x206 && c.destination <= 0x20D)
                || (c.destination >= 0x30A && c.destination <= 0x310)
                || c.destination == 0x500 || c.destination == 0x501;
        return source && control && destination;
    }

    boolean cdlPasses(int start, int end) {
        for (int p = start; p < end; p = nextChunk(p)) {
            String id = fourcc(p);
            int size = u32(p + 4);
            int body = p + 8;
            if (id.equals("cdl ")) {
                require(body, size);
                return evalCdl(body, size) != 0;
            }
        }
        return true;
    }

    int evalCdl(int body, int size) {
        int acc = 0;
        int p = body;
        int end = body + size;
        while (p + 2 <= end) {
            int op = u16(p);
            p += 2;
            if (op == 0x03 || op == 0x05 || op == 0x0A || op == 0x0C) {
                acc = 0;
            } else if (op == 0x04) {
                acc *= 2;
            } else if (op == 0x06) {
                acc *= acc;
            } else if (op == 0x07 || op == 0x08 || op == 0x09) {
                acc = acc != 0 ? 1 : 0;
            } else if (op == 0x0B || op == 0x0D || op == 0x0E) {
                acc = 1;
            } else if (op == 0x0F) {
                acc = acc == 0 ? 1 : 0;
            } else if (op == 0x10) {
                if (p + 4 > end) {
                    return 0;
                }
                acc = i32(p);
                p += 4;
            } else if (op == 0x11 || op == 0x12) {
                if (p + 16 > end) {
                    return 0;
                }
                acc = cdlQueryValue(p);
                p += 16;
            }
        }
        return acc;
    }

    int cdlQueryValue(int p) {
        for (int i = 0; i < CDL_QUERY_GUIDS.length; i++) {
            int[] guid = CDL_QUERY_GUIDS[i];
            boolean same = true;
            for (int j = 0; j < guid.length; j++) {
                if ((data[p + j] & 0xFF) != guid[j]) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return CDL_QUERY_VALUES[i];
            }
        }
        return 0;
    }

    static final int[][] CDL_QUERY_GUIDS = {
            {0x24, 0x2F, 0x8F, 0x17, 0x64, 0xC3, 0xD1, 0x11,
                    0xA7, 0x60, 0x00, 0x00, 0xF8, 0x75, 0xAC, 0x12},
            {0x25, 0x2F, 0x8F, 0x17, 0x64, 0xC3, 0xD1, 0x11,
                    0xA7, 0x60, 0x00, 0x00, 0xF8, 0x75, 0xAC, 0x12},
            {0x26, 0x2F, 0x8F, 0x17, 0x64, 0xC3, 0xD1, 0x11,
                    0xA7, 0x60, 0x00, 0x00, 0xF8, 0x75, 0xAC, 0x12},
            {0x27, 0x2F, 0x8F, 0x17, 0x64, 0xC3, 0xD1, 0x11,
                    0xA7, 0x60, 0x00, 0x00, 0xF8, 0x75, 0xAC, 0x12},
            {0xE5, 0x99, 0x45, 0xF1, 0x89, 0x46, 0xD2, 0x11,
                    0xAF, 0xA6, 0x00, 0xAA, 0x00, 0x24, 0xD8, 0xB6},
            {0x8D, 0x08, 0x04, 0x31, 0xC6, 0x2F, 0x6A, 0x4A,
                    0xA6, 0xC7, 0x8D, 0x53, 0x03, 0x5A, 0xFE, 0x1A},
            {0x37, 0x8A, 0x8E, 0xED, 0xF9, 0x97, 0xCD, 0x46,
                    0x92, 0x2F, 0x6D, 0xD4, 0x36, 0xF8, 0xDB, 0x8D},
            {0x28, 0x2F, 0x8F, 0x17, 0x64, 0xC3, 0xD1, 0x11,
                    0xA7, 0x60, 0x00, 0x00, 0xF8, 0x75, 0xAC, 0x12},
            {0x81, 0x11, 0x3E, 0xB0, 0x95, 0x80, 0xD2, 0x11,
                    0xA1, 0xEF, 0x00, 0x60, 0x08, 0x33, 0xDB, 0xD8},
            {0x82, 0x11, 0x3E, 0xB0, 0x95, 0x80, 0xD2, 0x11,
                    0xA1, 0xEF, 0x00, 0x60, 0x08, 0x33, 0xDB, 0xD8},
            {0x13, 0xF7, 0x91, 0x2A, 0xBF, 0xA4, 0xD2, 0x11,
                    0xBB, 0xDF, 0x00, 0x60, 0x08, 0x33, 0xDB, 0xD8}
    };

    static final int[] CDL_QUERY_VALUES = {
            0, 0, 0, 1, 0, 1, 0, 100000, 269, 1, 44100
    };

    void parseWavePool() {
        waves.clear();
        if (poolOffsets.length > 0) {
            int baseA = wvplChunkData + 4;
            int baseB = wvplChunkData;
            for (int i = 0; i < poolOffsets.length; i++) {
                int p = baseA + poolOffsets[i];
                if (!looksLikeWaveChunk(p)) {
                    p = baseB + poolOffsets[i];
                }
                if (!looksLikeWaveChunk(p)) {
                    throw error(wvplChunkData, "ptbl offset does not point to wave chunk");
                }
                waves.add(parseWave(i, p));
            }
            return;
        }
        int start = wvplChunkData + 4;
        int end = wvplChunkData + wvplChunkSize;
        int index = 0;
        for (int p = start; p < end; p = nextChunk(p)) {
            if (looksLikeWaveChunk(p)) {
                waves.add(parseWave(index++, p));
            }
        }
    }

    boolean looksLikeWaveChunk(int p) {
        if (p < 0 || p + 12 > data.length) {
            return false;
        }
        String id = fourcc(p);
        String type = fourcc(p + 8);
        return (id.equals("RIFF") && type.equals("WAVE")) || (id.equals("LIST") && type.equals("wave"));
    }

    Wave parseWave(int index, int p) {
        String type = fourcc(p + 8);
        int size = u32(p + 4);
        int start = p + 12;
        int end = checkedEnd(p + 8, size);
        if (!type.equals("WAVE") && !type.equals("wave")) {
            throw error(p, "not a WAVE resource");
        }
        Fmt fmt = null;
        byte[] pcmData = null;
        int factFrames = -1;
        SampleInfo sample = new SampleInfo();
        for (int q = start; q < end; q = nextChunk(q)) {
            String id = fourcc(q);
            int chunkSize = u32(q + 4);
            int body = q + 8;
            if (id.equals("fmt ")) {
                fmt = parseFmt(body, chunkSize);
            } else if (id.equals("data")) {
                if (chunkSize == 0) {
                    throw error(body, "empty data chunk");
                }
                require(body, chunkSize);
                pcmData = Arrays.copyOfRange(data, body, body + chunkSize);
            } else if (id.equals("fact") && chunkSize >= 4) {
                factFrames = u32(body);
            } else if (id.equals("wsmp")) {
                parseWsmp(body, chunkSize, sample);
            } else if (id.equals("smpl")) {
                parseSmpl(body, chunkSize, sample);
            } else if (id.equals("inst")) {
                parseInst(body, chunkSize, sample);
            }
        }
        if (fmt == null || pcmData == null) {
            throw error(p, "wave missing fmt or data");
        }
        Decoded decoded = decodeWave(fmt, pcmData, factFrames, p);
        return new Wave(index, fmt.tag, fmt.channels, fmt.sampleRate, fmt.bitsPerSample,
                decoded.frames, factFrames, decoded.pcm, sample);
    }

    Fmt parseFmt(int body, int size) {
        if (size < 16) {
            throw error(body, "short fmt");
        }
        Fmt fmt = new Fmt();
        fmt.tag = u16(body);
        fmt.channels = u16(body + 2);
        fmt.sampleRate = u32(body + 4);
        fmt.blockAlign = u16(body + 12);
        fmt.bitsPerSample = u16(body + 14);
        if (fmt.channels <= 0 || fmt.channels > 255 || fmt.sampleRate <= 0) {
            throw error(body, "bad fmt");
        }
        if (fmt.tag == 0xFFFE) {
            if (size < 40 || u16(body + 16) != 22) {
                throw error(body, "bad extensible fmt");
            }
            if (!isExtensiblePcmGuid(body + 24)) {
                throw error(body, "unsupported extensible subformat");
            }
            fmt.tag = 1;
        }
        return fmt;
    }

    boolean isExtensiblePcmGuid(int p) {
        int[] guid = {1, 0, 0, 0, 0, 0, 0x10, 0, 0x80, 0, 0, 0xAA, 0, 0x38, 0x9B, 0x71};
        for (int i = 0; i < guid.length; i++) {
            if ((data[p + i] & 0xFF) != guid[i]) {
                return false;
            }
        }
        return true;
    }

    void parseWsmp(int body, int size, SampleInfo sample) {
        if (size < 20) {
            throw error(body, "short wsmp");
        }
        sample.present = true;
        sample.unityNote = u16(body + 4);
        sample.fineTuneCents = s16(body + 6);
        sample.attenuation = i32(body + 8);
        int loopCount = u32(body + 16);
        sample.loopMode = LOOP_NONE;
        sample.loopStart = 0;
        sample.loopEndInclusive = -1;
        if (loopCount == 1 && size >= 36) {
            int loopStart = u32(body + 28);
            int loopLength = u32(body + 32);
            if (loopLength != 0) {
                sample.loopMode = LOOP_FORWARD;
                sample.loopStart = loopStart;
                sample.loopEndInclusive = loopStart + loopLength - 1;
            }
        }
    }

    void parseSmpl(int body, int size, SampleInfo sample) {
        if (size < 36) {
            return;
        }
        int loopCount = u32(body + 28);
        sample.present = true;
        sample.unityNote = u32(body + 12) & 0xFF;
        if (loopCount > 0 && size >= 60) {
            int type = u32(body + 40);
            int start = u32(body + 44);
            int end = u32(body + 48);
            if (end >= start) {
                sample.loopMode = type == 0 ? LOOP_FORWARD : LOOP_NONE;
                sample.loopStart = start;
                sample.loopEndInclusive = end;
            }
        }
    }

    void parseInst(int body, int size, SampleInfo sample) {
        if (size < 7) {
            return;
        }
        sample.present = true;
        sample.unityNote = data[body] & 0xFF;
        sample.fineTuneCents = (byte) data[body + 1];
        sample.attenuation = ((byte) data[body + 2]) * 655360;
    }

    Decoded decodeWave(Fmt fmt, byte[] bytes, int factFrames, int at) {
        if (fmt.tag == 1) {
            if (fmt.bitsPerSample == 8) {
                int frames = bytes.length / fmt.channels;
                short[] out = new short[(frames + 1) * fmt.channels];
                for (int i = 0; i < frames * fmt.channels; i++) {
                    out[i] = (short) (((bytes[i] & 0xFF) - 128) << 8);
                }
                return new Decoded(out, frames);
            }
            if (fmt.bitsPerSample == 16) {
                int frames = bytes.length / (2 * fmt.channels);
                short[] out = new short[(frames + 1) * fmt.channels];
                for (int i = 0, p = 0; i < frames * fmt.channels; i++, p += 2) {
                    out[i] = (short) ((bytes[p] & 0xFF) | (bytes[p + 1] << 8));
                }
                return new Decoded(out, frames);
            }
            throw error(at, "unsupported PCM bits " + fmt.bitsPerSample);
        }
        if (fmt.tag == 6 || fmt.tag == 7) {
            if (factFrames < 0) {
                throw error(at, "compressed wave missing fact");
            }
            int frames = bytes.length / fmt.channels;
            short[] out = new short[(frames + 1) * fmt.channels];
            for (int i = 0; i < frames * fmt.channels; i++) {
                out[i] = fmt.tag == 6 ? decodeALaw(bytes[i]) : decodeMuLaw(bytes[i]);
            }
            return new Decoded(out, frames);
        }
        if (fmt.tag == 17) {
            return decodeImaWav(fmt, bytes, factFrames, at);
        }
        throw error(at, "unsupported WAVE format tag " + fmt.tag);
    }

    Decoded decodeImaWav(Fmt fmt, byte[] bytes, int factFrames, int at) {
        if (factFrames < 0) {
            throw error(at, "compressed wave missing fact");
        }
        if ((fmt.channels != 1 && fmt.channels != 2) || fmt.blockAlign <= 0
                || fmt.blockAlign % fmt.channels != 0) {
            throw error(at, "bad IMA fmt");
        }
        int bytesPerChannel = fmt.blockAlign / fmt.channels;
        int framesPerBlock = 2 * bytesPerChannel - 7;
        if (framesPerBlock <= 0) {
            throw error(at, "bad IMA block");
        }
        int dataBytesPerChannel = bytesPerChannel - 4;
        if (fmt.channels == 2 && (dataBytesPerChannel & 3) != 0) {
            throw error(at, "bad IMA stereo block");
        }
        int blocks = bytes.length / fmt.blockAlign;
        int decodedFrames = blocks * framesPerBlock;
        int frames = Math.min(factFrames, decodedFrames);
        short[] out = new short[(frames + 1) * fmt.channels];
        for (int block = 0; block < blocks; block++) {
            int blockBase = block * fmt.blockAlign;
            int frameBase = block * framesPerBlock;
            int[] predictor = new int[fmt.channels];
            int[] index = new int[fmt.channels];
            for (int ch = 0; ch < fmt.channels; ch++) {
                int header = blockBase + ch * 4;
                predictor[ch] = (short) ((bytes[header] & 0xFF) | (bytes[header + 1] << 8));
                index[ch] = bytes[header + 2] & 0xFF;
                if (index[ch] > 88) {
                    throw error(at, "bad IMA step index");
                }
                if (frameBase < frames) {
                    out[frameBase * fmt.channels + ch] = (short) predictor[ch];
                }
            }
            for (int ch = 0; ch < fmt.channels; ch++) {
                int sampleFrame = frameBase + 1;
                for (int e = 0; e < dataBytesPerChannel && sampleFrame < frameBase + framesPerBlock; e++) {
                    int src = blockBase + 4 * fmt.channels + (e / 4) * fmt.channels * 4 + ch * 4 + (e & 3);
                    int packed = bytes[src] & 0xFF;
                    for (int half = 0; half < 2 && sampleFrame < frameBase + framesPerBlock; half++) {
                        int nibble = (packed >> (half * 4)) & 0x0F;
                        predictor[ch] = decodeImaNibble(predictor[ch], index[ch], nibble);
                        index[ch] = clamp(index[ch] + IMA_INDEX_DELTA[nibble], 0, 88);
                        if (sampleFrame < frames) {
                            out[sampleFrame * fmt.channels + ch] = (short) predictor[ch];
                        }
                        sampleFrame++;
                    }
                }
            }
        }
        return new Decoded(out, frames);
    }

    int u16(int p) {
        require(p, 2);
        return (data[p] & 0xFF) | ((data[p + 1] & 0xFF) << 8);
    }

    int s16(int p) {
        return (short) u16(p);
    }

    int u32(int p) {
        require(p, 4);
        return (data[p] & 0xFF) | ((data[p + 1] & 0xFF) << 8)
                | ((data[p + 2] & 0xFF) << 16) | ((data[p + 3] & 0xFF) << 24);
    }

    int i32(int p) {
        return u32(p);
    }

    String fourcc(int p) {
        require(p, 4);
        return new String(new char[]{(char) data[p], (char) data[p + 1], (char) data[p + 2], (char) data[p + 3]});
    }

    int checkedEnd(int sizeFieldOffset, int size) {
        long end = (long) sizeFieldOffset + size;
        if (end < sizeFieldOffset || end > data.length) {
            throw error(sizeFieldOffset, "chunk exceeds file");
        }
        return (int) end;
    }

    int rootRiffEnd(int size) {
        long strict = 8L + size;
        if (strict <= data.length) {
            return (int) strict;
        }
        if (size == data.length) {
            return data.length;
        }
        throw error(4, "RIFF root exceeds file");
    }

    int nextChunk(int p) {
        require(p, 8);
        int size = u32(p + 4);
        long next = (long) p + 8 + size + (size & 1);
        if (next < p || next > data.length) {
            throw error(p, "chunk exceeds file");
        }
        return (int) next;
    }

    void require(int p, int len) {
        if (p < 0 || len < 0 || p + len < p || p + len > data.length) {
            throw error(p, "unexpected EOF");
        }
    }

    IllegalArgumentException error(int p, String message) {
        return new IllegalArgumentException(sourceName + " @0x" + Integer.toHexString(Math.max(0, p)) + ": " + message);
    }
}
final class Fmt {
    int tag;
    int channels;
    int sampleRate;
    int blockAlign;
    int bitsPerSample;
}
final class Decoded {
    final short[] pcm;
    final int frames;

    Decoded(short[] pcm, int frames) {
        this.pcm = pcm;
        this.frames = frames;
    }
}



