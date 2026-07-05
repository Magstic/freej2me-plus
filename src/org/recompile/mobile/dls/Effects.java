package org.recompile.mobile.dls;

/** Synthesis DSP helpers: filters, envelopes, LFOs, chorus, reverb, and dynamics. */
final class PlusFilter extends SynthesisSupport {
    final int lowThreshold;
    final int midThreshold;
    final int highThreshold;
    final int baseCutoff;
    final int resonance;
    int effectiveCutoff;
    int c0;
    int c1;
    int c2;
    int h1Left;
    int h2Left;
    int h1Right;
    int h2Right;

    PlusFilter(int sampleRate, int cutoff, int resonance) {
        int sampleRateLike = sampleRate << 16;
        int base = Integer.divideUnsigned(sampleRateLike, 440);
        lowThreshold = filterMappedCutoff(log10Q16(base / 240));
        midThreshold = filterMappedCutoff(log10Q16(base / 6));
        highThreshold = filterMappedCutoff(log10Q16(base / 2));
        baseCutoff = Math.max(cutoff, FILTER_MIN_CUTOFF);
        this.resonance = clamp(resonance, 0, FILTER_MAX_RESONANCE);
        effectiveCutoff = baseCutoff;
        if (effectiveCutoff < highThreshold) {
            updateCoefficients(effectiveCutoff);
        }
    }

    boolean enabled() {
        return effectiveCutoff < highThreshold;
    }

    void update(int runtimeCutoffDelta) {
        int cutoff = baseCutoff + runtimeCutoffDelta;
        if (cutoff < 0) {
            cutoff = 0;
        }
        if (cutoff != effectiveCutoff) {
            effectiveCutoff = cutoff;
            if (effectiveCutoff < highThreshold) {
                updateCoefficients(effectiveCutoff);
            }
        }
    }

    int nextLeft(int sample) {
        int raw = filteredRaw(sample, h1Left, h2Left);
        h2Left = h1Left;
        h1Left = raw;
        return raw >> 10;
    }

    int nextRight(int sample) {
        int raw = filteredRaw(sample, h1Right, h2Right);
        h2Right = h1Right;
        h1Right = raw;
        return raw >> 10;
    }

    int filteredRaw(int sample, int h1, int h2) {
        return ((c0 * sample) >> 6)
                - (((c2 >> 3) * (h2 >> 9)) >> 4)
                - (((c1 >> 4) * (h1 >> 9)) >> 3);
    }

    void updateCoefficients(int cutoff) {
        cutoff = Math.max(cutoff, lowThreshold);
        int norm = 65 * fixedDiv16_16(cutoff - lowThreshold, midThreshold - lowThreshold);
        int resIndex = (16 * fixedDiv16_16(Math.max(resonance, 0), FILTER_MAX_RESONANCE)) >> 16;
        resIndex = Math.min(resIndex, 15);

        int normIndex = norm >> 16;
        int i0 = Math.min(normIndex, 64);
        int i1 = Math.min(i0 + 1, 64);
        int fraction = norm - (i0 << 16);
        int t0 = 65 * resIndex + i0;
        int t1 = 65 * resIndex + i1;

        c1 = FILTER_C1[t0] + (int) (((long) fraction * (FILTER_C1[t1] - FILTER_C1[t0])) >> 16);
        c2 = FILTER_C2[t0] + (int) (((long) fraction * (FILTER_C2[t1] - FILTER_C2[t0])) >> 16);
        c0 = fixedMul16_16(FILTER_C0_SCALE[resIndex], c1 + c2 + 0x10000);

        if (normIndex >= 65) {
            int extra = fixedDiv16_16(cutoff - midThreshold, highThreshold - midThreshold);
            c0 += fixedMul16_16(extra, 0x10000 - c0);
            c1 = fixedMul16_16(c1, 0x10000 - extra);
            c2 = fixedMul16_16(c2, 0x10000 - extra);
        }
    }
}
final class ChorusEffect extends SynthesisSupport {
    // ponytail: default CHRS type 2; wire compact effect blob/public properties when those inputs exist.
    static final int FEEDBACK_GAIN = 0x00000F5C;
    static final int RATE_FIXED = 0x00028000;
    static final int DEPTH_UNITS = 0x0000019D;

    final int[] delayLine;
    final int mask;
    final int lfoRange;
    final int lfoStep;
    int lfoPhase;
    int lfoDirection = 1;
    int writeIndex;

    ChorusEffect(int sampleRate) {
        int delaySamples = (int) Math.max(1L, ((long) sampleRate * 0x0A3D) >> 16);
        delayLine = new int[nextPowerOfTwo(delaySamples)];
        mask = delayLine.length - 1;
        lfoRange = (int) Math.max(1L, (long) sampleRate * DEPTH_UNITS);
        int stepBase = fixedDiv16_16(lfoRange, RATE_FIXED >> 1);
        lfoStep = Math.max(1, stepBase / Math.max(1, sampleRate));
    }

    void process(int[] input, int[] stereoMix, int from, int to) {
        for (int frame = from; frame < to; frame++) {
            int wetA = tap(lfoPhase);
            int phaseB = lfoPhase + (lfoRange >> 1);
            if (phaseB > lfoRange) {
                phaseB = (lfoRange << 1) - phaseB;
            }
            int wetB = tap(phaseB);
            stereoMix[frame * 2] += wetA;
            stereoMix[frame * 2 + 1] += wetB;
            int feedbackWet = (wetA + wetB) >> 1;
            delayLine[writeIndex] = input[frame] + ((FEEDBACK_GAIN >> 8) * (feedbackWet >> 8));
            writeIndex = (writeIndex + 1) & mask;
            lfoPhase += lfoDirection * lfoStep;
            if (lfoPhase >= lfoRange) {
                lfoDirection = -1;
                lfoPhase -= lfoStep;
            } else if (lfoPhase <= 0) {
                lfoDirection = 1;
                lfoPhase += lfoStep;
            }
        }
    }

    int tailFrames() {
        return fixedMul16_16(delayLine.length, fixedDiv16_16(-0x30000, log10Q16(FEEDBACK_GAIN)));
    }

    int tap(int phase) {
        int tap = writeIndex - (phase >> 16) + delayLine.length;
        int base = delayLine[tap & mask];
        int prev = delayLine[(tap - 1) & mask];
        return base + (int) (((long) (phase & 0xFFFF) * (prev - base)) >> 16);
    }
}
final class ReverbEffect extends SynthesisSupport {
    // ponytail: bridge default IRVB type 1/time 1300; wire public properties and compact blobs when inputs exist.
    static final int DELAY_LENGTH = 8192;
    static final int TYPE_SCALE = 0x00010000;
    static final int REVERB_TIME = 85197;
    static final int[] COMB_RATIO = {0x596, 0x642, 0x74B, 0x828, 0x93C, 0xA19};
    static final int[] INPUT_RATIO = {0x31C, 0x3FE, 0x441, 0x527, 0x5BF, 0x77A, 0x1F9};
    static final int[] DIFFUSION_GAIN = {
            0x0001F39C, 0x0001575E, 0x000138A5, 0x0000EA31, 0x0000C6E6, 0x00008630, 0x0000C000
    };
    static final int[] SMOOTHING = {
            0x10000, 0xD439, 0xCA7F, 0xBB64, 0xAC08, 0x9DF4, 0x91AA, 0x86A8, 0x7CEE, 0x747B, 0x6CCD
    };

    final short[] inputDelay = new short[DELAY_LENGTH];
    final short[][] comb = new short[6][DELAY_LENGTH];
    final int[] inputIndex = new int[8];
    final int[] combRead = new int[6];
    final int[] combWrite = new int[6];
    final int[] combFeedback = new int[6];
    final short[] early = new short[256];
    final short[] stereoL = new short[512];
    final short[] stereoR = new short[512];
    final int sampleRate;
    final int wetSmoothingGain;
    short wetSmoothingState;
    int earlyRead;
    int earlyWrite;
    int stereoRead;
    int stereoWrite;

    ReverbEffect(int sampleRate) {
        this.sampleRate = sampleRate;
        for (int i = 0; i < INPUT_RATIO.length; i++) {
            inputIndex[i] = delayIndex(sampleRate, INPUT_RATIO[i]);
        }
        inputIndex[7] = 0;
        for (int i = 0; i < COMB_RATIO.length; i++) {
            int delayBase = fixedMul16_16(COMB_RATIO[i], TYPE_SCALE);
            combRead[i] = delayIndex(sampleRate, COMB_RATIO[i]);
            int value = fixedDiv16_16(delayBase, REVERB_TIME);
            combFeedback[i] = -exp10Q16(-3 * value);
        }
        earlyRead = (256 - (int) (((long) sampleRate * 0x0126) >> 16)) & 0xFF;
        stereoRead = (512 - (int) (((long) sampleRate * 456) >> 16)) & 0x1FF;
        wetSmoothingGain = smoothingGain(sampleRate);
    }

    void process(int[] input, int[] stereoMix, int from, int to) {
        for (int frame = from; frame < to; frame++) {
            inputDelay[inputIndex[7]] = (short) (input[frame] >> 11);
            int diffusionSum = 0;
            for (int i = 0; i < 6; i++) {
                diffusionSum += mulShift(DIFFUSION_GAIN[i], inputDelay[inputIndex[i]], 16);
            }
            int combFeed = mulShift(DIFFUSION_GAIN[6], inputDelay[inputIndex[6]], 16);
            for (int i = 0; i < inputIndex.length; i++) {
                inputIndex[i] = (inputIndex[i] + 1) & (DELAY_LENGTH - 1);
            }

            int combSum = 0;
            for (int i = 0; i < 6; i++) {
                int old = comb[i][combRead[i]];
                comb[i][combWrite[i]] = (short) (combFeed + mulShift(combFeedback[i], old, 16));
                combRead[i] = (combRead[i] + 1) & (DELAY_LENGTH - 1);
                combWrite[i] = (combWrite[i] + 1) & (DELAY_LENGTH - 1);
                combSum += old;
            }

            int earlyOld = early[earlyRead];
            int earlyValue = mulShift(22937, combSum - 2 * earlyOld, 15);
            early[earlyWrite] = (short) ((combSum + earlyValue) >> 1);
            earlyRead = (earlyRead + 1) & 0xFF;
            earlyWrite = (earlyWrite + 1) & 0xFF;
            int target = diffusionSum + earlyValue + 2 * earlyOld;
            wetSmoothingState = (short) (wetSmoothingState
                    + mulShift(wetSmoothingGain, target - wetSmoothingState, 16));
            addStereoWet(frame, stereoMix, wetSmoothingState);
        }
    }

    void addStereoWet(int frame, int[] stereoMix, int wet) {
        int oldL = stereoL[stereoRead];
        int oldR = stereoR[stereoRead];
        int deltaL = mulShift(26214, wet - oldL, 16);
        int deltaR = mulShift(26214, oldR - wet, 16);
        stereoL[stereoWrite] = (short) (wet + deltaL);
        stereoR[stereoWrite] = (short) (wet + deltaR);
        stereoMix[frame * 2] += (oldL + deltaL) << 10;
        stereoMix[frame * 2 + 1] += (oldR + deltaR) << 10;
        stereoRead = (stereoRead + 1) & 0x1FF;
        stereoWrite = (stereoWrite + 1) & 0x1FF;
    }

    static int mulShift(int a, int b, int shift) {
        return (a * b) >> shift;
    }

    static int delayIndex(int sampleRate, int ratio) {
        int delayBase = fixedMul16_16(ratio, TYPE_SCALE);
        int samples = (int) (((long) sampleRate * delayBase) >> 16);
        return (DELAY_LENGTH - samples) & (DELAY_LENGTH - 1);
    }

    static int smoothingGain(int sampleRate) {
        if (sampleRate <= 8000) {
            return 0xD439;
        }
        if (sampleRate >= 48000) {
            return 0x6666;
        }
        int pos = (sampleRate - 8000) / 4000;
        int rem = (sampleRate - 8000) % 4000;
        int a = SMOOTHING[pos + 1];
        int b = pos + 2 < SMOOTHING.length ? SMOOTHING[pos + 2] : 0x6666;
        return a + fixedMul16_16(b - a, (rem << 16) / 4000);
    }

    int tailFrames() {
        return fixedMul16_16(sampleRate, REVERB_TIME);
    }
}
final class EffectGate extends SynthesisSupport {
    final int tailBlocks;
    int state = 1;
    int tailRemaining;

    EffectGate(int tailFrames, int blockFrames) {
        tailBlocks = Math.max(0, tailFrames) / blockFrames + 1;
    }

    boolean processThisBlock(boolean activeSend) {
        if (activeSend) {
            state = 3;
            tailRemaining = 0;
            return true;
        }
        if (state == 3) {
            state = 2;
            tailRemaining = tailBlocks;
        }
        if (state == 2) {
            tailRemaining--;
            if (tailRemaining <= 0) {
                state = 1;
            }
            return true;
        }
        return false;
    }
}
final class MixDynamics extends SynthesisSupport {
    boolean dynamicEnabled;
    int targetGain = 0x10000;
    int threshold = exp10Q16(-1179648 / 20) << 7;
    int ceiling = 1572864;
    int thresholdRaw = -1179648;
    int ratioRaw = 0x20000;
    int ratioReciprocal = fixedDiv16_16(0x10000, 0x20000);
    int noCompressionTarget = exp10Q16(fixedMul16_16(-1179648, fixedDiv16_16(0x10000, 0x20000) - 0x10000) / 20);
    int attackRaw = 13107;
    int attackSmoothing = smoothingGain(13107);
    int releaseRaw = 0x10000;
    int releaseSmoothing = smoothingGain(0x10000);
    int currentGain = 0x10000;

    MixDynamics() {
        this(false);
    }

    MixDynamics(boolean mixrDefaults) {
        if (mixrDefaults) {
            setRatio(0xA0000);
            setThreshold(-0x10000);
            ceiling = -0x4000;
            setAttack(0);
            setRelease(3277);
            dynamicEnabled = true;
            currentGain = 0x10000;
        }
    }

    void process(int[] input, int[] output, int channels, int frames, boolean add) {
        process(input, 0, output, 0, channels, frames, add);
    }

    void process(int[] input, int inputOffset, int[] output, int outputOffset,
                         int channels, int frames, boolean add) {
        int sampleCount = Math.max(0, frames * channels);
        if (sampleCount == 0) {
            return;
        }
        int startGain = currentGain;
        int step;
        if (dynamicEnabled) {
            int peak = peak(input, inputOffset, sampleCount);
            int target = fixedMul16_16(dynamicTargetFactor(peak), targetGain);
            if (target < startGain) {
                if (attackRaw == 0) {
                    startGain = target;
                    currentGain = target;
                    step = 0;
                } else {
                    currentGain = startGain + fixedMul16_16(target - startGain, 0x10000 - attackSmoothing);
                    step = (currentGain - startGain) / sampleCount;
                }
            } else {
                if (releaseRaw == 0) {
                    startGain = target;
                    currentGain = target;
                    step = 0;
                } else {
                    currentGain = startGain + fixedMul16_16(target - startGain, 0x10000 - releaseSmoothing);
                    step = (currentGain - startGain) / sampleCount;
                }
            }
        } else {
            currentGain = targetGain;
            step = (targetGain - startGain) / sampleCount;
        }
        int gain = startGain;
        int i = 0;
        for (int groups = sampleCount >> 2; groups > 0; groups--) {
            for (int j = 0; j < 4; j++, i++) {
                int sample = fixedMul16_16(input[inputOffset + i], gain);
                if (add) {
                    output[outputOffset + i] += sample;
                } else {
                    output[outputOffset + i] = sample;
                }
            }
            gain += step << 2;
        }
        for (; i < sampleCount; i++) {
            int sample = fixedMul16_16(input[inputOffset + i], gain);
            if (add) {
                output[outputOffset + i] += sample;
            } else {
                output[outputOffset + i] = sample;
            }
            gain += step;
        }
    }

    int peak(int[] input, int offset, int sampleCount) {
        int max = 0;
        int min = 0;
        for (int i = 0; i < sampleCount; i++) {
            int value = input[offset + i];
            if (value > max) {
                max = value;
            }
            if (value < min) {
                min = value;
            }
        }
        return Math.max(max, -min);
    }

    int dynamicTargetFactor(int peak) {
        if (peak <= threshold) {
            return noCompressionTarget;
        }
        int logPeak = log10Q16(peak >> 7);
        int scaledPeak = 20 * logPeak;
        int compressed = fixedMul16_16(scaledPeak, ratioReciprocal);
        if (compressed > ceiling) {
            compressed = ceiling;
        }
        return exp10Q16((compressed - scaledPeak) / 20);
    }

    void setThreshold(int value) {
        thresholdRaw = value;
        threshold = exp10Q16(value / 20) << 7;
        updateNoCompressionTarget();
    }

    void setRatio(int value) {
        ratioRaw = value;
        ratioReciprocal = fixedDiv16_16(0x10000, ratioRaw);
        updateNoCompressionTarget();
    }

    void updateNoCompressionTarget() {
        noCompressionTarget = exp10Q16(fixedMul16_16(thresholdRaw, ratioReciprocal - 0x10000) / 20);
    }

    void setAttack(int value) {
        attackRaw = value;
        attackSmoothing = smoothingGain(value);
    }

    void setRelease(int value) {
        releaseRaw = value;
        releaseSmoothing = smoothingGain(value);
    }

    static int smoothingGain(int raw) {
        int wholeMillis = fixedDiv16_16(1000 * raw, 0xA0000) & 0xFFFF0000;
        return exp10Q16(fixedDiv16_16(-315652, wholeMillis + 0x10000));
    }
}
final class Lfo extends SynthesisSupport {
    final int startDelay;
    final int period;
    int phase;
    int output = 0x8000;
    boolean active;

    Lfo(int periodMicros, int startDelayMicros) {
        startDelay = clamp(startDelayMicros, 0, 10000000);
        period = clamp(periodMicros, 50000, 10000000);
        phase = period >> 2;
    }

    int next() {
        if (!active) {
            phase += 10000;
            if (Integer.compareUnsigned(phase, startDelay) >= 0) {
                active = true;
                phase = period >> 2;
            }
            return output;
        }
        int folded = phase;
        if (Integer.compareUnsigned(phase, period >> 1) >= 0) {
            folded = period + 0x03FFFFFF * phase;
        }
        output = Integer.divideUnsigned(folded << 6, period >> 3) << 8;
        phase += 10000;
        if (Integer.compareUnsigned(phase, period) >= 0) {
            phase -= period;
        }
        return output;
    }
}
final class Envelope extends SynthesisSupport {
    static final long EG1_FULL = 0xFFFF0000L;
    static final int EG2_FULL = 0xFFFF;
    final int attackMicros;
    final int decayMicros;
    final int attackTicks;
    final int decayTicks;
    final int releaseTicks;
    final int sustain;
    final boolean eg1;
    final long eg1Sustain;
    final int decayMultiplier;
    final int releaseMultiplier;
    int stage;
    int tickIndex;
    int releaseStart;
    int current;
    long eg1Current;
    boolean finished;

    Envelope(int attackMicros, int decayMicros, int sustainQ16, int releaseMicros, boolean eg1) {
        this.attackMicros = clamp(attackMicros, 0, 40000000);
        this.decayMicros = clamp(decayMicros, 0, 40000000);
        attackTicks = microsToControlTicks(attackMicros);
        decayTicks = microsToControlTicks(decayMicros);
        releaseTicks = microsToControlTicks(releaseMicros);
        sustain = eg1 ? clamp(sustainQ16, 0, 0x10000)
                : (int) (((long) EG2_FULL * clamp(sustainQ16, 0, 0x10000)) >> 16);
        this.eg1 = eg1;
        eg1Sustain = eg1SustainTarget(sustainQ16);
        decayMultiplier = eg1Multiplier(decayMicros);
        releaseMultiplier = eg1Multiplier(releaseMicros);
        current = !eg1 && attackTicks == 0 ? EG2_FULL : 0;
        eg1Current = 0;
        stage = !eg1 && attackTicks == 0 ? (decayTicks == 0 ? 2 : 1) : 0;
    }

    int next() {
        if (eg1) {
            return nextEg1();
        }
        return nextLinear();
    }

    int nextLinear() {
        if (finished) {
            return 0;
        }
        if (stage == 0) {
            current = (int) Math.min(EG2_FULL, tickIndex++ * (long) EG2_FULL / Math.max(1, attackTicks));
            if (tickIndex >= attackTicks) {
                stage = decayTicks == 0 ? 2 : 1;
                tickIndex = 0;
                current = EG2_FULL;
            }
        } else if (stage == 1) {
            current = EG2_FULL - (int) ((EG2_FULL - (long) sustain) * tickIndex++ / Math.max(1, decayTicks));
            if (tickIndex >= decayTicks) {
                stage = 2;
                current = sustain;
            }
        } else if (stage == 2) {
            current = sustain;
        } else if (stage == 3) {
            current = releaseStart - (int) (releaseStart * (long) tickIndex++ / Math.max(1, releaseTicks));
            if (releaseTicks == 0 || tickIndex >= releaseTicks) {
                current = 0;
                finished = true;
            }
        }
        return clamp(current, 0, EG2_FULL);
    }

    int nextEg1() {
        if (finished) {
            return 0;
        }
        int output;
        if (stage == 0) {
            if (attackMicros == 0) {
                stage = decayMicros == 0 ? 2 : 1;
                tickIndex = 0;
                eg1Current = EG1_FULL;
                return nextEg1();
            }
            long level = (((long) tickIndex << 6) / Math.max(1, attackMicros >> 2)) << 8;
            if (level >= 0xFFFFL) {
                stage = decayMicros == 0 ? 2 : 1;
                tickIndex = 10000;
                eg1Current = EG1_FULL;
                output = eg1Level(eg1Current);
            } else {
                eg1Current = level << 16;
                output = eg1Level(eg1Current);
                tickIndex += 10000;
            }
        } else if (stage == 1) {
            output = eg1Level(eg1Current);
            eg1Current = (eg1Current * decayMultiplier) >>> 16;
            if (eg1Current <= eg1Sustain) {
                stage = 2;
                eg1Current = eg1Sustain;
            }
        } else if (stage == 2) {
            eg1Current = eg1Sustain;
            output = eg1Level(eg1Current);
        } else if (stage == 3) {
            if (releaseTicks == 0) {
                eg1Current = 0;
                output = 0;
            } else {
                output = eg1Level(eg1Current);
                eg1Current = (eg1Current * releaseMultiplier) >>> 16;
            }
        } else {
            eg1Current = 0;
            output = 0;
        }
        current = output;
        if (stage > 0 && output == 0) {
            finished = true;
            stage = 4;
        }
        return output;
    }

    void release() {
        if (stage != 3 && !finished) {
            releaseStart = current;
            tickIndex = 0;
            stage = 3;
        }
    }
}



