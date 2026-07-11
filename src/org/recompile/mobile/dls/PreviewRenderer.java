package org.recompile.mobile.dls;

import static org.recompile.mobile.dls.SynthesisSupport.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** MIDI event interpreter, voice allocation, and dry/effect send mixing. */
final class PreviewRenderer {
    static final int[] VOICE_STEAL_ORDER = {15, 14, 13, 12, 11, 10, 8, 7, 6, 5, 4, 3, 2, 1, 0, 9};

    final DlsBank bank;
    final int sampleRate;
    final int ordinaryVoiceLimit;
    final boolean reverbEnabled;
    final boolean chorusEnabled;
    final boolean filterVibration;
    final ChannelState[] channels = new ChannelState[16];
    final List<Voice> voices = new ArrayList<Voice>();
    int[] reverbBus;
    int[] chorusBus;
    boolean currentBlockReverbActive;
    boolean currentBlockChorusActive;
    final int[] mipChannels = new int[16];
    final int[] mipThresholds = new int[16];
    int activeMask = 0xFFFF;
    int masterVolumeRaw = 0x3FFF;
    int masterVolumeQ16 = 0x10000;
    int globalFineRaw = 0x2000;
    int globalCoarseRaw = 0x2000;
    int systemMode;
    int childTailGainQ16 = 0x10000;
    final MixDynamics childDynamics = new MixDynamics();
    final MixDynamics mixDynamics = new MixDynamics(true);
    long nextVoiceSerial;

    PreviewRenderer(DlsBank bank, int sampleRate, boolean reverbEnabled, boolean chorusEnabled,
                            int ordinaryVoiceLimit, boolean filterVibration) {
        this.bank = bank;
        this.sampleRate = sampleRate;
        this.ordinaryVoiceLimit = ordinaryVoiceLimit;
        this.reverbEnabled = reverbEnabled;
        this.chorusEnabled = chorusEnabled;
        this.filterVibration = filterVibration;
        childDynamics.dynamicEnabled = true;
        for (int i = 0; i < channels.length; i++) {
            channels[i] = new ChannelState(this, i);
        }
    }

    int mixUntil(int[] mix, int from, int to) {
        for (int s = from; s < to; s++) {
            int left = 0;
            int right = 0;
            int reverb = 0;
            int chorus = 0;
            boolean reverbActive = false;
            boolean chorusActive = false;
            for (int i = voices.size() - 1; i >= 0; i--) {
                Voice voice = voices.get(i);
                if (!voice.active) {
                    voices.remove(i);
                    continue;
                }
                int sample = voice.next();
                if (!voice.active) {
                    voices.remove(i);
                }
                if (voice.wave.channels == 2) {
                    if (voice.lastFiltered) {
                        left += (voice.lastLeftSample * (voice.leftGain >> 2)) >> 6;
                        right += (voice.lastRightSample * (voice.rightGain >> 2)) >> 6;
                    } else {
                        left += (voice.lastLeftSample * voice.leftGain) >> 8;
                        right += (voice.lastRightSample * voice.rightGain) >> 8;
                    }
                    sample = stereoToMonoSample(voice.lastLeftSample, voice.lastRightSample);
                } else {
                    if (voice.lastFiltered) {
                        left += (sample * (voice.leftGain >> 2)) >> 6;
                        right += (sample * (voice.rightGain >> 2)) >> 6;
                    } else {
                        left += (sample * voice.leftGain) >> 8;
                        right += (sample * voice.rightGain) >> 8;
                    }
                }
                int sendDryGain = (voice.leftGain + voice.rightGain) >> 1;
                reverb += effectSendSample(sample, sendDryGain, voice.reverbSend, 24);
                chorus += effectSendSample(sample, sendDryGain, voice.chorusSend, 23);
                reverbActive |= voice.reverbSend > 0;
                chorusActive |= voice.chorusSend > 0;
            }
            mix[s * 2] += left;
            mix[s * 2 + 1] += right;
            currentBlockReverbActive |= reverbActive;
            currentBlockChorusActive |= chorusActive;
            if (reverbBus != null) {
                reverbBus[s] += reverb;
            }
            if (chorusBus != null) {
                chorusBus[s] += chorus;
            }
        }
        return to;
    }

    void handle(MidiEvent event) {
        if (event.status == 0xF0) {
            sysex(event.payload);
            return;
        }
        if (event.channel < 0 || event.channel >= channels.length) {
            return;
        }
        ChannelState ch = channels[event.channel];
        int high = event.status & 0xF0;
        if (high == 0x80 || (high == 0x90 && event.data2 == 0)) {
            if (!noteAllowed(event.channel)) {
                return;
            }
            if (ch.program == VIBRATION_PROGRAM) {
                if (filterVibration) {
                    return;
                }
            }
            int key = bank.percussionKeyAliasFor(ch.programSelected ? ch.selectedBankSelector : ch.bankSelector(),
                    event.data1);
            for (Voice voice : voices) {
                if (voice.channel == event.channel && voice.key == key) {
                    voice.noteOff();
                }
            }
        } else if (high == 0x90) {
            if (!noteAllowed(event.channel)) {
                return;
            }
            if (ch.program == VIBRATION_PROGRAM) {
                if (filterVibration) {
                    return;
                }
            }
            noteOn(event.channel, event.data1, event.data2);
        } else if (high == 0xB0) {
            controller(ch, event.data1, event.data2);
        } else if (high == 0xC0) {
            programChange(ch, event.data1);
        } else if (high == 0xE0) {
            ch.pitchBend = ((event.data2 & 0x7F) << 7) | (event.data1 & 0x7F);
        }
    }

    boolean noteAllowed(int channel) {
        return (activeMask & (1 << channel)) != 0;
    }

    void sysex(byte[] payload) {
        if (payload.length >= 4 && (payload[0] & 0xFF) == 0x7F
                && (payload[2] & 0xFF) == 0x0B && (payload[3] & 0xFF) == 0x01) {
            mip(payload, 4, payload.length - 4);
        } else if (payload.length >= 4 && (payload[0] & 0xFF) == 0x7E
                && (payload[2] & 0xFF) == 0x09) {
            int mode = (payload[3] & 0xFF) == 1 ? 1 : (payload[3] & 0xFF) == 3 ? 2 : 0;
            resetSystemMode(mode);
        } else if (payload.length == 7 && (payload[0] & 0xFF) == 0x43
                && (payload[2] & 0xFF) == 0x4C && payload[3] == 0 && payload[4] == 0
                && (payload[5] & 0xFF) == 0x7E && payload[6] == 0) {
            resetSystemMode(4);
        } else if (payload.length == 9 && (payload[0] & 0xFF) == 0x41 && (payload[1] & 0xFF) == 0x10
                && (payload[2] & 0xFF) == 0x42 && (payload[3] & 0xFF) == 0x12
                && (payload[4] & 0xFF) == 0x40 && payload[5] == 0
                && (payload[6] & 0xFF) == 0x7F && payload[7] == 0 && (payload[8] & 0xFF) == 0x41) {
            resetSystemMode(3);
        } else if (payload.length >= 6 && (payload[0] & 0xFF) == 0x7F
                && (payload[1] & 0xFF) == 0x7F && (payload[2] & 0xFF) == 0x04) {
            int raw = ((payload[5] & 0x7F) << 7) | (payload[4] & 0x7F);
            if ((payload[3] & 0xFF) == 0x01) {
                masterVolumeRaw = raw;
                masterVolumeQ16 = masterVolumeMultiplier(raw);
            } else if ((payload[3] & 0xFF) == 0x03) {
                globalFineRaw = raw;
            } else if ((payload[3] & 0xFF) == 0x04) {
                globalCoarseRaw = (payload[5] & 0x7F) << 7;
            }
        }
    }

    void resetSystemMode(int mode) {
        systemMode = mode;
        voices.clear();
        for (int i = 0; i < channels.length; i++) {
            channels[i].resetAll(mode);
        }
    }

    int globalFinePitchQ16() {
        return ((short) (globalFineRaw - 0x2000) << 16) / 0x2000;
    }

    int globalCoarseSemitones() {
        return ((short) (globalCoarseRaw - 0x2000)) >> 7;
    }

    void mip(byte[] payload, int offset, int length) {
        Arrays.fill(mipChannels, 0);
        Arrays.fill(mipThresholds, 0);
        int pairs = Math.min(length / 2, mipChannels.length);
        int previous = 0;
        for (int i = 0; i < pairs; i++) {
            int channel = payload[offset + i * 2] & 0xFF;
            int threshold = payload[offset + i * 2 + 1] & 0xFF;
            if (channel >= 16 || threshold < previous) {
                activeMask = 0xFFFF;
                return;
            }
            mipChannels[i] = channel;
            mipThresholds[i] = threshold;
            previous = threshold;
        }
        activeMask = 0xFFFF;
        if (pairs > 0 && mipThresholds[0] != 0) {
            activeMask = 0;
            for (int i = 0; i < pairs && mipThresholds[i] != 0; i++) {
                if (mipThresholds[i] <= ordinaryVoiceLimit) {
                    activeMask |= 1 << mipChannels[i];
                }
            }
        }
    }

    void controller(ChannelState ch, int cc, int value) {
        value &= 0x7F;
        if (cc == 0) {
            ch.bankMsb = value;
            ch.bankLsb = 0;
        } else if (cc == 32) {
            ch.bankLsb = value;
        } else if (cc == 1) {
            ch.modulation = value;
        } else if (cc == 33) {
            ch.modulationLsb = value;
        } else if (cc == 4) {
            ch.foot = value;
        } else if (cc == 36) {
            ch.footLsb = value;
        } else if (cc == 7) {
            ch.volume = value;
        } else if (cc == 39) {
            ch.volumeLsb = value;
        } else if (cc == 10) {
            ch.pan = value;
        } else if (cc == 42) {
            ch.panLsb = value;
        } else if (cc == 11) {
            ch.expression = value;
        } else if (cc == 43) {
            ch.expressionLsb = value;
        } else if (cc == 64) {
            ch.sustain = value >= 64;
        } else if (cc == 101) {
            ch.rpnMsb = value;
            ch.rpnLsb = 127;
            ch.selectorMode = 1;
        } else if (cc == 100) {
            ch.rpnLsb = value;
            ch.selectorMode = 1;
        } else if (cc == 99) {
            ch.nrpnMsb = value;
            ch.nrpnLsb = 127;
            ch.selectorMode = 2;
        } else if (cc == 98) {
            ch.nrpnLsb = value;
            ch.selectorMode = 2;
        } else if (cc == 6 || cc == 38 || cc == 96 || cc == 97) {
            int rpn = ((ch.rpnMsb & 0x7F) << 7) | (ch.rpnLsb & 0x7F);
            if (ch.selectorMode == 1 && rpn >= 0 && rpn < ch.rpnValues.length) {
                if (cc == 6) {
                    ch.rpnValues[rpn] = (value & 0x7F) << 7;
                } else if (cc == 38) {
                    ch.rpnValues[rpn] = (ch.rpnValues[rpn] & 0xFF80) | (value & 0x7F);
                } else if (cc == 96) {
                    ch.rpnValues[rpn] = (ch.rpnValues[rpn] + value) & 0xFFFF;
                } else {
                    ch.rpnValues[rpn] = (ch.rpnValues[rpn] - value) & 0xFFFF;
                }
            }
        } else if (cc == 91) {
            ch.reverb = value;
        } else if (cc == 93) {
            ch.chorus = value;
        } else if (cc == 121) {
            ch.resetControllers();
        } else if (cc == 120 || cc == 123 || (cc >= 124 && cc <= 127)) {
            for (Voice voice : voices) {
                if (voice.channel == ch.index) {
                    if (cc == 120) {
                        voice.active = false;
                    } else {
                        voice.noteOff();
                    }
                }
            }
        }
    }

    void noteOn(int channel, int key, int velocity) {
        ChannelState ch = channels[channel];
        if (!ch.programSelected) {
            programChange(ch, ch.program);
        }
        Instrument instrument = ch.selectedInstrument;
        if (instrument == null) {
            return;
        }
        int voiceKey = bank.percussionKeyAliasFor(ch.selectedBankSelector, key);
        Region region = instrument.regionFor(voiceKey, velocity);
        if (region == null || region.tableIndex < 0 || region.tableIndex >= bank.waves.size()) {
            return;
        }
        Wave wave = bank.waves.get(region.tableIndex);
        if (wave.channels != 1 && wave.channels != 2) {
            return;
        }
        SampleInfo sample = region.sample.effectiveWith(wave.sample);
        killExclusiveVoices(channel, voiceKey, region);
        if (voices.size() >= ordinaryVoiceLimit) {
            voices.remove(stealVoiceIndex(channel));
        }
        voices.add(new Voice(channel, voiceKey, region.index, region.keyGroup, wave, sample,
                region.articulation, voiceKey, velocity, ch, sampleRate, nextVoiceSerial++));
    }

    void programChange(ChannelState ch, int program) {
        ch.program = program & 0x7F;
        ch.selectedBankSelector = ch.bankSelector();
        ch.selectedInstrument = bank.midiInstrument(ch.selectedBankSelector, ch.program);
        ch.programSelected = true;
    }

    void killExclusiveVoices(int channel, int key, Region region) {
        int exclusiveClass = region.keyGroup & 0x0F;
        for (Voice voice : voices) {
            if (voice.channel != channel) {
                continue;
            }
            if (((region.options & 0x10) != 0 && voice.key == key && voice.regionIndex == region.index)
                    || (exclusiveClass != 0 && (voice.keyGroup & 0x0F) == exclusiveClass)) {
                voice.fastKill();
            }
        }
    }

    int stealVoiceIndex(int newChannel) {
        for (int i = 0; i < voices.size(); i++) {
            if (!voices.get(i).active) {
                return i;
            }
        }
        int candidate = findRecyclableVoice(newChannel);
        if (candidate >= 0) {
            return candidate;
        }
        candidate = findSustainedReleasedVoice();
        if (candidate >= 0) {
            return candidate;
        }
        candidate = findActiveVoice(newChannel);
        return candidate >= 0 ? candidate : 0;
    }

    int findRecyclableVoice(int newChannel) {
        for (int channel : VOICE_STEAL_ORDER) {
            if (newChannel != 9 && channel == 9) {
                continue;
            }
            int candidate = -1;
            for (int i = 0; i < voices.size(); i++) {
                Voice voice = voices.get(i);
                if (voice.channel == channel && voice.recyclable()) {
                    candidate = i;
                }
            }
            if (candidate >= 0) {
                return candidate;
            }
        }
        return -1;
    }

    int findSustainedReleasedVoice() {
        int candidate = -1;
        long oldest = Long.MAX_VALUE;
        for (int i = 0; i < voices.size(); i++) {
            Voice voice = voices.get(i);
            if (voice.sustainedReleased() && voice.startSerial < oldest) {
                candidate = i;
                oldest = voice.startSerial;
            }
        }
        return candidate;
    }

    int findActiveVoice(int newChannel) {
        int candidate = -1;
        for (int channel : VOICE_STEAL_ORDER) {
            if (newChannel != 9 && channel == 9) {
                continue;
            }
            int channelCandidate = -1;
            long oldest = Long.MAX_VALUE;
            for (int i = 0; i < voices.size(); i++) {
                Voice voice = voices.get(i);
                if (voice.channel == channel && voice.stealableActive() && voice.startSerial < oldest) {
                    channelCandidate = i;
                    oldest = voice.startSerial;
                }
            }
            if (channelCandidate >= 0) {
                candidate = channelCandidate;
            }
        }
        return candidate;
    }
}
final class ChannelState {
    final PreviewRenderer renderer;
    final int index;
    int bankMsb;
    int bankLsb;
    int program;
    int modulation;
    int modulationLsb;
    int foot;
    int footLsb;
    int volume;
    int volumeLsb;
    int expression;
    int expressionLsb;
    int pan;
    int panLsb;
    boolean sustain;
    int reverb;
    int chorus;
    int pitchBend;
    final int[] rpnValues = new int[5];
    int rpnMsb;
    int rpnLsb;
    int nrpnMsb;
    int nrpnLsb;
    int selectorMode;
    Instrument selectedInstrument;
    int selectedBankSelector;
    boolean programSelected;

    ChannelState(int index) {
        this(null, index);
    }

    ChannelState(PreviewRenderer renderer, int index) {
        this.renderer = renderer;
        this.index = index;
        resetAll(0);
    }

    void resetAll(int mode) {
        bankMsb = mode == 2 ? (index == 9 ? 120 : 121) : mode == 4 && index == 9 ? 127 : 0;
        bankLsb = 0;
        program = 0;
        selectedInstrument = null;
        selectedBankSelector = 0;
        programSelected = false;
        resetControllers();
    }

    void resetControllers() {
        modulation = 0;
        modulationLsb = 0;
        foot = 0;
        footLsb = 0;
        volume = 100;
        volumeLsb = 0;
        expression = 127;
        expressionLsb = 0;
        pan = 64;
        panLsb = 0;
        sustain = false;
        reverb = 40;
        chorus = 0;
        pitchBend = 8192;
        Arrays.fill(rpnValues, 0);
        rpnValues[0] = 0x0100;
        rpnValues[1] = 0x2000;
        rpnValues[2] = 0x2000;
        rpnMsb = 127;
        rpnLsb = 127;
        nrpnMsb = 127;
        nrpnLsb = 127;
        selectorMode = 0;
    }

    int nrpnSelector() {
        return ((nrpnMsb & 0x7F) << 7) | (nrpnMsb & 0x7F);
    }

    int bankSelector() {
        int mode = renderer == null ? 0 : renderer.systemMode;
        int msb = bankMsb & 0x7F;
        int lsb = bankLsb & 0x7F;
        if (mode == 1 || mode == 3) {
            return ((index == 9 ? 120 : 121) << 7);
        }
        if (mode == 2) {
            return (msb << 7) | lsb;
        }
        if (mode == 4) {
            return ((msb == 126 || msb == 127 ? 120 : 121) << 7);
        }
        int defaultMsb = index == 9 ? 120 : 121;
        if (msb == 120 || msb == 121) {
            return (msb << 7) | lsb;
        }
        if (msb != 0) {
            return (defaultMsb << 7) | (lsb == 0 ? msb : lsb);
        }
        return (defaultMsb << 7) | lsb;
    }

    int volume14() {
        return ((volume & 0x7F) << 7) | (volumeLsb & 0x7F);
    }

    int modulation14() {
        return ((modulation & 0x7F) << 7) | (modulationLsb & 0x7F);
    }

    int expression14() {
        return ((expression & 0x7F) << 7) | (expressionLsb & 0x7F);
    }

    int pan14() {
        return ((pan & 0x7F) << 7) | (panLsb & 0x7F);
    }
}
final class Voice {
    final int channel;
    final int key;
    final int regionIndex;
    final int keyGroup;
    final Wave wave;
    final ChannelState channelState;
    final List<Connection> runtimeConnections;
    final int baseGainQ16;
    final int basePanOffset;
    final int baseReverbSend;
    final int baseChorusSend;
    final long baseIncrement;
    final long loopStart;
    final long loopEnd;
    final boolean looping;
    final int controlBlockFrames;
    final PlusFilter filter;
    final Envelope envelope;
    final Envelope eg2Envelope;
    final Lfo vibratoLfo;
    final Lfo modulationLfo;
    final long startSerial;
    long currentIncrement;
    int controlFramesUntilTick;
    int targetLeftGain;
    int targetRightGain;
    int targetReverbSend;
    int targetChorusSend;
    int rampStartLeftGain;
    int rampStartRightGain;
    int rampStartReverbSend;
    int rampStartChorusSend;
    int rampFrame;
    int rampSegmentFrame;
    int rampSegmentFrames;
    int rampSegmentStartLeftGain;
    int rampSegmentStartRightGain;
    int rampSegmentStartReverbSend;
    int rampSegmentStartChorusSend;
    boolean rampInitialized;
    int leftGain;
    int rightGain;
    int reverbSend;
    int chorusSend;
    int lastLeftSample;
    int lastRightSample;
    boolean lastFiltered;
    long position;
    boolean keyHeld = true;
    boolean sustainSnapshot;
    boolean active = true;

    Voice(int channel, int key, int regionIndex, int keyGroup, Wave wave, SampleInfo sample, Articulation articulation,
                  int midiKey, int velocity, ChannelState ch, int outputRate) {
        this(channel, key, regionIndex, keyGroup, wave, sample, articulation, midiKey, velocity, ch, outputRate, 0);
    }

    Voice(int channel, int key, int regionIndex, int keyGroup, Wave wave, SampleInfo sample, Articulation articulation,
                  int midiKey, int velocity, ChannelState ch, int outputRate, long startSerial) {
        this.channel = channel;
        this.key = key;
        this.regionIndex = regionIndex;
        this.keyGroup = keyGroup;
        this.wave = wave;
        this.channelState = ch;
        this.runtimeConnections = articulation.runtimeConnections;
        this.controlBlockFrames = defaultRenderBlockFrames(outputRate);
        this.startSerial = startSerial;
        int pitchMultiplierQ16 = pitchRatioQ16(articulation.pitch);
        int gainQ16 = exp10Q16(sample.attenuation / 200);
        int panOffset = articulation.pan;
        int eg1Attack = articulation.eg1Attack;
        int eg1Decay = articulation.eg1Decay;
        int eg1Release = articulation.eg1Release;
        int eg2Attack = articulation.eg2Attack;
        int eg2Decay = articulation.eg2Decay;
        int eg2Release = articulation.eg2Release;
        int filterCutoff = articulation.filterCutoff == FILTER_DISABLED_CUTOFF ? FILTER_DISABLED_CUTOFF
                : Math.max(articulation.filterCutoff, FILTER_MIN_CUTOFF);
        for (Connection connection : articulation.runtimeConnections) {
            int value = noteOnConnectionValueQ16(connection, midiKey, velocity, sample.unityNote,
                    ch.modulation14(), ch.rpnValues[0]);
            if (value == 0) {
                continue;
            }
            if (connection.destination == 1) {
                gainQ16 = fixedMul16_16(gainQ16, exp10Q16(value / 200));
            } else if (connection.destination == 3) {
                pitchMultiplierQ16 = fixedMul16_16(pitchMultiplierQ16, exp2Q16(value / 1200));
            } else if (connection.destination == 4) {
                panOffset += value / 500;
            } else if (connection.destination == 0x206) {
                eg1Attack = modulatedTimeMicros(eg1Attack, value);
            } else if (connection.destination == 0x207) {
                eg1Decay = modulatedTimeMicros(eg1Decay, value);
            } else if (connection.destination == 0x209) {
                eg1Release = modulatedTimeMicros(eg1Release, value);
            } else if (connection.destination == 0x30A) {
                eg2Attack = modulatedTimeMicros(eg2Attack, value);
            } else if (connection.destination == 0x30B) {
                eg2Decay = modulatedTimeMicros(eg2Decay, value);
            } else if (connection.destination == 0x30D) {
                eg2Release = modulatedTimeMicros(eg2Release, value);
            } else if (connection.destination == 0x500) {
                // Plus note-on modulation folds FILTER_CUTOFF into the initial filter base.
                filterCutoff += value / 100;
            }
        }
        if (sample.fineTuneCents != 0) {
            pitchMultiplierQ16 = fixedMul16_16(pitchMultiplierQ16,
                    pitchRatioQ16((sample.fineTuneCents << 16) / 100));
        }
        int rateQ16 = fixedDiv16_16(wave.sampleRate, outputRate);
        this.baseIncrement = Math.max(1L, fixedMul16_16(rateQ16, pitchMultiplierQ16));
        this.baseGainQ16 = gainQ16;
        this.basePanOffset = panOffset;
        this.baseReverbSend = articulation.reverb;
        this.baseChorusSend = articulation.chorus;
        int loopStartFrame = clamp(sample.loopStart, 0, wave.frames);
        int loopEndFrame = (int) Math.max(0L,
                Math.min((long) wave.frames, (long) sample.loopEndInclusive + 1L));
        this.looping = sample.loopMode == LOOP_FORWARD && loopEndFrame > loopStartFrame;
        this.loopStart = ((long) loopStartFrame) << 16;
        this.loopEnd = ((long) loopEndFrame) << 16;
        this.filter = filterCutoff == FILTER_DISABLED_CUTOFF ? null
                : new PlusFilter(outputRate, filterCutoff, articulation.filterResonance);
        this.envelope = new Envelope(eg1Attack, eg1Decay, articulation.eg1Sustain, eg1Release, true);
        this.eg2Envelope = new Envelope(eg2Attack, eg2Decay, articulation.eg2Sustain, eg2Release, false);
        this.vibratoLfo = new Lfo(articulation.vibratoFrequency, articulation.vibratoStartDelay);
        this.modulationLfo = new Lfo(articulation.lfoFrequency, articulation.lfoStartDelay);
        tickControl();
    }

    void release() {
        envelope.release();
        eg2Envelope.release();
    }

    void fastKill() {
        keyHeld = false;
        sustainSnapshot = false;
        release();
        controlFramesUntilTick = 0;
    }

    void noteOff() {
        keyHeld = false;
    }

    boolean recyclable() {
        return active && !keyHeld && !sustainSnapshot;
    }

    boolean sustainedReleased() {
        return active && !keyHeld && sustainSnapshot;
    }

    boolean stealableActive() {
        return active;
    }

    int next() {
        lastFiltered = false;
        if (controlFramesUntilTick <= 0) {
            tickControl();
        }
        if (!active) {
            return 0;
        }
        applyGainRamp();
        int frame = (int) (position >>> 16);
        if (frame >= wave.frames) {
            active = false;
            return 0;
        }
        int frac = (int) (position & 0xFFFF);
        int base = frame * wave.channels;
        int nextFrame = frame + 1;
        nextFrame = Math.min(nextFrame, wave.frames);
        int nextBase = nextFrame * wave.channels;
        int a = wave.pcm[base];
        int b = wave.pcm[nextBase];
        int sample;
        boolean filtered = filter != null && filter.enabled();
        lastFiltered = filtered;
        if (wave.channels == 2) {
            int ar = wave.pcm[base + 1];
            int br = wave.pcm[nextBase + 1];
            lastLeftSample = interpolateSourceSample(wave, a, b, frac);
            lastRightSample = interpolateSourceSample(wave, ar, br, frac);
            if (filtered) {
                lastLeftSample = filter.nextLeft(lastLeftSample);
                lastRightSample = filter.nextRight(lastRightSample);
            }
            sample = stereoToMonoSample(lastLeftSample, lastRightSample);
        } else {
            sample = interpolateSourceSample(wave, a, b, frac);
            if (filtered) {
                sample = filter.nextLeft(sample);
            }
            lastLeftSample = sample;
            lastRightSample = sample;
        }
        position += currentIncrement;
        while (looping && position >= loopEnd) {
            position = loopStart + (position - loopEnd);
        }
        advanceGainRamp();
        controlFramesUntilTick--;
        return sample;
    }

    void tickControl() {
        if (rampInitialized) {
            leftGain = targetLeftGain;
            rightGain = targetRightGain;
            reverbSend = targetReverbSend;
            chorusSend = targetChorusSend;
        }
        sustainSnapshot = channelState.sustain;
        if (!keyHeld && !sustainSnapshot) {
            release();
        }
        int currentLevel = envelope.next();
        eg2Envelope.next();
        vibratoLfo.next();
        modulationLfo.next();
        currentIncrement = updateRuntimeModulation(currentLevel);
        if (!rampInitialized) {
            leftGain = targetLeftGain;
            rightGain = targetRightGain;
            reverbSend = targetReverbSend;
            chorusSend = targetChorusSend;
            rampInitialized = true;
        }
        rampStartLeftGain = leftGain;
        rampStartRightGain = rightGain;
        rampStartReverbSend = reverbSend;
        rampStartChorusSend = chorusSend;
        rampFrame = 0;
        rampSegmentStartLeftGain = leftGain;
        rampSegmentStartRightGain = rightGain;
        rampSegmentStartReverbSend = reverbSend;
        rampSegmentStartChorusSend = chorusSend;
        rampSegmentFrame = 0;
        beginGainRampSegment();
        if (envelope.finished) {
            active = false;
        }
        controlFramesUntilTick = controlBlockFrames;
    }

    void applyGainRamp() {
        leftGain = rampValue(rampSegmentStartLeftGain, rampStartLeftGain, targetLeftGain);
        rightGain = rampValue(rampSegmentStartRightGain, rampStartRightGain, targetRightGain);
        reverbSend = rampValue(rampSegmentStartReverbSend, rampStartReverbSend, targetReverbSend);
        chorusSend = rampValue(rampSegmentStartChorusSend, rampStartChorusSend, targetChorusSend);
    }

    void advanceGainRamp() {
        if (rampFrame >= controlBlockFrames) {
            return;
        }
        rampFrame++;
        rampSegmentFrame++;
        if (rampFrame < controlBlockFrames && rampSegmentFrame >= rampSegmentFrames) {
            rampSegmentStartLeftGain = rampSegmentEnd(rampSegmentStartLeftGain, rampStartLeftGain, targetLeftGain);
            rampSegmentStartRightGain = rampSegmentEnd(rampSegmentStartRightGain, rampStartRightGain, targetRightGain);
            rampSegmentStartReverbSend = rampSegmentEnd(rampSegmentStartReverbSend, rampStartReverbSend, targetReverbSend);
            rampSegmentStartChorusSend = rampSegmentEnd(rampSegmentStartChorusSend, rampStartChorusSend, targetChorusSend);
            rampSegmentFrame = 0;
            beginGainRampSegment();
        }
    }

    void beginGainRampSegment() {
        int remaining = controlBlockFrames - rampFrame;
        if (remaining <= 0) {
            rampSegmentFrames = 0;
            return;
        }
        int frame = (int) (position >>> 16);
        int sourceBoundary = nextSourceBoundary(frame);
        if (sourceBoundary <= frame) {
            sourceBoundary = Math.min(wave.frames, frame + SOURCE_WINDOW_FRAMES);
        }
        long distance = (((long) sourceBoundary) << 16) - position;
        int framesToBoundary = distance <= 0 || currentIncrement <= 0 ? remaining
                : (int) Math.min(Integer.MAX_VALUE, (distance + currentIncrement - 1) / currentIncrement);
        rampSegmentFrames = clamp(framesToBoundary, 1, remaining);
    }

    int nextSourceBoundary(int frame) {
        int boundary = Math.min(wave.frames, ((frame / SOURCE_WINDOW_FRAMES) + 1) * SOURCE_WINDOW_FRAMES);
        if (looping) {
            boundary = Math.min(boundary, (int) (loopEnd >>> 16));
        }
        return boundary;
    }

    int rampValue(int segmentStart, int blockStart, int target) {
        int scaledStep = ((target - blockStart) << 8) / controlBlockFrames;
        int groupFrames = rampSegmentFrames & ~3;
        if (rampSegmentFrame < groupFrames) {
            return segmentStart + (rampSegmentFrame >> 2) * (scaledStep / 64);
        }
        return segmentStart + (groupFrames >> 2) * (scaledStep / 64)
                + (rampSegmentFrame - groupFrames) * (scaledStep / 256);
    }

    int rampSegmentEnd(int segmentStart, int blockStart, int target) {
        int scaledStep = ((target - blockStart) << 8) / controlBlockFrames;
        int groupFrames = rampSegmentFrames & ~3;
        return segmentStart + (groupFrames >> 2) * (scaledStep / 64)
                + (rampSegmentFrames - groupFrames) * (scaledStep / 256);
    }

    long updateRuntimeModulation(int currentLevel) {
        int runtimePitch = 0;
        int gainQ16 = baseGainQ16;
        int gainAttenuation = 0;
        int panOffset = basePanOffset;
        int reverbSendQ16 = baseReverbSend;
        int chorusSendQ16 = baseChorusSend;
        int filterCutoffDelta = 0;
        for (Connection connection : runtimeConnections) {
            int value = runtimeConnectionValueQ16(connection);
            if (value == 0) {
                continue;
            }
            if (connection.destination == 1) {
                gainAttenuation += value / 10;
            } else if (connection.destination == 3) {
                runtimePitch += value / 100;
            } else if (connection.destination == 4) {
                panOffset += value / 500;
            } else if (connection.destination == 0x81) {
                reverbSendQ16 += value / 1000;
            } else if (connection.destination == 0x80) {
                chorusSendQ16 += value / 1000;
            } else if (connection.destination == 0x500) {
                filterCutoffDelta += value / 100;
            }
        }
        if (gainAttenuation < 0) {
            gainQ16 = fixedMul16_16(gainQ16, exp10Q16(gainAttenuation / 20));
        }
        if (channelState.renderer != null) {
            runtimePitch += channelState.renderer.globalFinePitchQ16()
                    + (channelState.renderer.globalCoarseSemitones() << 16);
            gainQ16 = fixedMul16_16(gainQ16, channelState.renderer.masterVolumeQ16);
            gainQ16 = fixedMul16_16(gainQ16, channelState.renderer.childTailGainQ16);
        }
        gainQ16 = fixedMul16_16(gainQ16, currentLevel);
        int pan = clamp(panOffset, -0x10000, 0x10000);
        targetLeftGain = fixedMul16_16(gainQ16, panScaleQ16(-pan));
        targetRightGain = fixedMul16_16(gainQ16, panScaleQ16(pan));
        targetReverbSend = clamp(reverbSendQ16, 0, 0x10000);
        targetChorusSend = clamp(chorusSendQ16, 0, 0x10000);
        if (filter != null) {
            filter.update(filterCutoffDelta);
        }
        return clampSourceIncrement((baseIncrement * pitchRatioQ16(runtimePitch)) >> 16);
    }

    int runtimeConnectionValueQ16(Connection connection) {
        if (connection.source == 1 || connection.source == 5 || connection.source == 9) {
            int control = 0x10000;
            if (connection.control == 0x81) {
                control = clamp(channelState.modulation14(), 0, 0x3FFF) << 2;
            } else if (connection.control == 0x100) {
                control = clamp(channelState.rpnValues[0], 0, 0x3FFF) << 2;
            } else if (connection.control != 0) {
                return 0;
            }
            int source = connection.source == 1 ? modulationLfo.output
                    : connection.source == 9 ? vibratoLfo.output : eg2Envelope.current;
            return connectionValueQ16(connection, source, control);
        }
        return controllerConnectionValueQ16(connection, channelState.pitchBend,
                channelState.modulation14(), channelState.volume14(), channelState.expression14(),
                channelState.pan14(), channelState.reverb, channelState.chorus,
                channelState.rpnValues[0], channelState.rpnValues[1], channelState.rpnValues[2]);
    }
}




