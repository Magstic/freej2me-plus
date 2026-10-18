/*
	This file is part of FreeJ2ME.

	FreeJ2ME is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.

	FreeJ2ME is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
	GNU General Public License for more details.

	You should have received a copy of the GNU General Public License
	along with FreeJ2ME.  If not, see http://www.gnu.org/licenses/
*/
package org.recompile.mobile.dls;

import static org.recompile.mobile.dls.SynthesisSupport.*;

/** Synthesis DSP helpers: filters, envelopes, LFOs, and dynamics. */
final class PlusFilter
{
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

	PlusFilter(int sampleRate, int cutoff, int resonance)
	{
		int sampleRateLike = sampleRate << 16;
		int base = Integer.divideUnsigned(sampleRateLike, 440);
		lowThreshold = filterMappedCutoff(log10Q16(base / 240));
		midThreshold = filterMappedCutoff(log10Q16(base / 6));
		highThreshold = filterMappedCutoff(log10Q16(base / 2));
		baseCutoff = cutoff;
		this.resonance = clamp(resonance, 0, FILTER_MAX_RESONANCE);
		effectiveCutoff = baseCutoff;
		if(effectiveCutoff < highThreshold)
		{
			updateCoefficients(effectiveCutoff);
		}
	}

	boolean enabled()
	{
		return effectiveCutoff < highThreshold;
	}

	void update(int runtimeCutoffDelta)
	{
		int cutoff = baseCutoff + runtimeCutoffDelta;
		if(cutoff < 0)
		{
			cutoff = 0;
		}
		if(cutoff != effectiveCutoff)
		{
			effectiveCutoff = cutoff;
			if(effectiveCutoff < highThreshold)
			{
				updateCoefficients(effectiveCutoff);
			}
		}
	}

	int nextLeft(int sample)
	{
		int raw = filteredRaw(sample, h1Left, h2Left);
		h2Left = h1Left;
		h1Left = raw;
		return raw >> 10;
	}

	int nextRight(int sample)
	{
		int raw = filteredRaw(sample, h1Right, h2Right);
		h2Right = h1Right;
		h1Right = raw;
		return raw >> 10;
	}

	int filteredRaw(int sample, int h1, int h2)
	{
		return ((c0 * sample) >> 6)
			- (((c2 >> 3) * (h2 >> 9)) >> 4)
			- (((c1 >> 4) * (h1 >> 9)) >> 3);
	}

	void updateCoefficients(int cutoff)
	{
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

		c1 = FILTER_C1[t0] + (int)(((long) fraction * (FILTER_C1[t1] - FILTER_C1[t0])) >> 16);
		c2 = FILTER_C2[t0] + (int)(((long) fraction * (FILTER_C2[t1] - FILTER_C2[t0])) >> 16);
		c0 = fixedMul16_16(FILTER_C0_SCALE[resIndex], c1 + c2 + 0x10000);

		if(normIndex >= 65)
		{
			int extra = fixedDiv16_16(cutoff - midThreshold, highThreshold - midThreshold);
			c0 += fixedMul16_16(extra, 0x10000 - c0);
			c1 = fixedMul16_16(c1, 0x10000 - extra);
			c2 = fixedMul16_16(c2, 0x10000 - extra);
		}
	}
}
final class MixDynamics
{
	int threshold = exp10Q16(-1179648 / 20) << 7;
	int ceiling = 1572864;
	int thresholdRaw = -1179648;
	int ratioReciprocal = fixedDiv16_16(0x10000, 0x20000);
	int noCompressionTarget = exp10Q16(fixedMul16_16(-1179648, fixedDiv16_16(0x10000, 0x20000) - 0x10000) / 20);
	int attackRaw = 13107;
	int attackSmoothing = smoothingGain(13107);
	int releaseRaw = 0x10000;
	int releaseSmoothing = smoothingGain(0x10000);
	int currentGain = 0x10000;

	MixDynamics()
	{
		this(false);
	}

	MixDynamics(boolean mixrDefaults)
	{
		if(mixrDefaults)
		{
			setRatio(0xA0000);
			setThreshold(-0x10000);
			ceiling = -0x4000;
			setAttack(0);
			setRelease(3277);
		}
	}

	void process(int[] samples, int frames)
	{
		int sampleCount = Math.max(0, frames * 2);
		if(sampleCount == 0) { return; }

		int startGain = currentGain;
		int peak = peak(samples, sampleCount);
		int target = fixedMul16_16(dynamicTargetFactor(peak), 0x10000);
		int step;
		if(target < startGain)
		{
			if(attackRaw == 0)
			{
				startGain = target;
				currentGain = target;
				step = 0;
			}
			else
			{
				currentGain = startGain + fixedMul16_16(target - startGain, 0x10000 - attackSmoothing);
				step = (currentGain - startGain) / sampleCount;
			}
		}
		else
		{
			if(releaseRaw == 0)
			{
				startGain = target;
				currentGain = target;
				step = 0;
			}
			else
			{
				currentGain = startGain + fixedMul16_16(target - startGain, 0x10000 - releaseSmoothing);
				step = (currentGain - startGain) / sampleCount;
			}
		}
		int gain = startGain;
		int i = 0;
		for(int groups = sampleCount >> 2; groups > 0; groups--)
		{
			for(int j = 0; j < 4; j++, i++) { samples[i] = fixedMul16_16(samples[i], gain); }
			gain += step << 2;
		}
		for(; i < sampleCount; i++)
		{
			samples[i] = fixedMul16_16(samples[i], gain);
			gain += step;
		}
	}

	int peak(int[] samples, int sampleCount)
	{
		int max = 0;
		int min = 0;
		for(int i = 0; i < sampleCount; i++)
		{
			int value = samples[i];
			if(value > max) { max = value; }
			if(value < min) { min = value; }
		}
		return Math.max(max, -min);
	}

	int dynamicTargetFactor(int peak)
	{
		if(peak <= threshold)
		{
			return noCompressionTarget;
		}
		int logPeak = log10Q16(peak >> 7);
		int scaledPeak = 20 * logPeak;
		int compressed = fixedMul16_16(scaledPeak, ratioReciprocal);
		if(compressed > ceiling)
		{
			compressed = ceiling;
		}
		return exp10Q16((compressed - scaledPeak) / 20);
	}

	void setThreshold(int value)
	{
		thresholdRaw = value;
		threshold = exp10Q16(value / 20) << 7;
		updateNoCompressionTarget();
	}

	void setRatio(int value)
	{
		ratioReciprocal = fixedDiv16_16(0x10000, value);
		updateNoCompressionTarget();
	}

	void updateNoCompressionTarget()
	{
		noCompressionTarget = exp10Q16(fixedMul16_16(thresholdRaw, ratioReciprocal - 0x10000) / 20);
	}

	void setAttack(int value)
	{
		attackRaw = value;
		attackSmoothing = smoothingGain(value);
	}

	void setRelease(int value)
	{
		releaseRaw = value;
		releaseSmoothing = smoothingGain(value);
	}

	static int smoothingGain(int raw)
	{
		int wholeMillis = fixedDiv16_16(1000 * raw, 0xA0000) & 0xFFFF0000;
		return exp10Q16(fixedDiv16_16(-315652, wholeMillis + 0x10000));
	}
}
final class Lfo
{
	final int startDelay;
	final int period;
	int phase;
	int output = 0x8000;
	boolean active;

	Lfo(int periodMicros, int startDelayMicros)
	{
		startDelay = clamp(startDelayMicros, 0, 10000000);
		period = clamp(periodMicros, 50000, 10000000);
		phase = period >> 2;
	}

	void next()
	{
		if(!active)
		{
			phase += 10000;
			if(Integer.compareUnsigned(phase, startDelay) >= 0)
			{
				active = true;
				phase = period >> 2;
			}
			return;
		}
		int folded = phase;
		if(Integer.compareUnsigned(phase, period >> 1) >= 0)
		{
			folded = period + 0x03FFFFFF * phase;
		}
		output = Integer.divideUnsigned(folded << 6, period >> 3) << 8;
		phase += 10000;
		if(Integer.compareUnsigned(phase, period) >= 0)
		{
			phase -= period;
		}
	}
}
final class Envelope
{
	static final long EG1_FULL = 0xFFFF0000L;
	static final int EG2_FULL = 0xFFFF;
	static final int FORCED_FADE_MICROS = 15000;
	static final int DELAY = 0;
	static final int ATTACK = 1;
	static final int HOLD = 2;
	static final int DECAY = 3;
	static final int SUSTAIN = 4;
	static final int RELEASE = 5;
	static final int FINISHED = 6;
	static final int SHUTDOWN = 7;
	final int delayMicros;
	final int attackMicros;
	final int holdMicros;
	final int decayMicros;
	final int releaseMicros;
	final int attackTicks;
	final int decayTicks;
	final int sustain;
	final boolean eg1;
	final long eg1Sustain;
	final int decayMultiplier;
	int activeReleaseMicros;
	int activeReleaseMultiplier;
	int stage;
	int tickIndex;
	int current;
	int shutdownStart;
	long eg1Current;
	boolean finished;

	Envelope(int delayMicros, int attackMicros, int holdMicros, int decayMicros, int sustainQ16,
		int releaseMicros, boolean eg1)
	{
		this.delayMicros = clamp(delayMicros, 0, 40000000);
		this.attackMicros = clamp(attackMicros, 0, 40000000);
		this.holdMicros = clamp(holdMicros, 0, 40000000);
		this.decayMicros = clamp(decayMicros, 0, 40000000);
		this.releaseMicros = clamp(releaseMicros, 0, 40000000);
		attackTicks = microsToControlTicks(this.attackMicros);
		decayTicks = microsToControlTicks(this.decayMicros);
		sustain = eg1 ? clamp(sustainQ16, 0, 0x10000)
			: (int)(((long) EG2_FULL * clamp(sustainQ16, 0, 0x10000)) >> 16);
		this.eg1 = eg1;
		eg1Sustain = eg1SustainTarget(sustainQ16);
		decayMultiplier = eg1Multiplier(this.decayMicros);
		activeReleaseMicros = this.releaseMicros;
		activeReleaseMultiplier = eg1Multiplier(this.releaseMicros);
		current = 0;
		eg1Current = 0;
		stage = this.delayMicros == 0 ? ATTACK : DELAY;
	}

	int next()
	{
		if(eg1)
		{
			return nextEg1();
		}
		return nextEg2();
	}

	int nextEg2()
	{
		if(finished)
		{
			return 0;
		}
		if(stage == DELAY)
		{
			current = 0;
			if(tickIndex >= delayMicros)
			{
				stage = ATTACK;
				tickIndex = 10000;
			}
			else
			{
				tickIndex += 10000;
			}
		}
		else if(stage == ATTACK)
		{
			if(attackTicks == 0)
			{
				current = EG2_FULL;
				stage = holdMicros == 0 ? (decayTicks == 0 ? SUSTAIN : DECAY) : HOLD;
				tickIndex = 0;
				return nextEg2();
			}
			int step = eg2RampStep(tickIndex, attackMicros);
			current = step;
			if(step >= EG2_FULL)
			{
				stage = holdMicros == 0 ? (decayTicks == 0 ? SUSTAIN : DECAY) : HOLD;
				tickIndex = holdMicros == 0 ? 0 : 10000;
				current = EG2_FULL;
			}
			else
			{
				tickIndex += 10000;
			}
		}
		else if(stage == HOLD)
		{
			current = EG2_FULL;
			if(tickIndex >= holdMicros)
			{
				stage = decayTicks == 0 ? SUSTAIN : DECAY;
				tickIndex = 10000;
			}
			else
			{
				tickIndex += 10000;
			}
		}
		else if(stage == DECAY)
		{
			current = EG2_FULL - eg2RampStep(tickIndex, decayMicros);
			if(current <= sustain)
			{
				stage = SUSTAIN;
				current = sustain;
			}
			else
			{
				tickIndex += 10000;
			}
		}
		else if(stage == SUSTAIN)
		{
			current = sustain;
		}
		else if(stage == RELEASE)
		{
			// Plus sub_11DB140 keeps EG2 release on the full-scale ramp instead of scaling from release start.
			int step = eg2RampStep(tickIndex, activeReleaseMicros);
			current = EG2_FULL - step;
			if(step >= EG2_FULL)
			{
				current = 0;
				finished = true;
			}
			else
			{
				tickIndex += 10000;
			}
		}
		else if(stage == SHUTDOWN)
		{
			int remaining = EG2_FULL - eg2RampStep(tickIndex, FORCED_FADE_MICROS);
			current = fixedMul16_16(shutdownStart, remaining);
			if(current <= 0 || remaining <= 0)
			{
				current = 0;
				finished = true;
				stage = FINISHED;
			}
			else
			{
				tickIndex += 10000;
			}
		}
		return clamp(current, 0, EG2_FULL);
	}

	int eg2RampStep(int elapsedMicros, int durationMicros)
	{
		if(durationMicros <= 0)
		{
			return EG2_FULL;
		}
		int divisor = durationMicros >> 2;
		if(divisor <= 0)
		{
			return EG2_FULL;
		}
		long step = ((((long) elapsedMicros) << 6) / divisor) << 8;
		return step >= EG2_FULL ? EG2_FULL : (int) step;
	}

	int nextEg1()
	{
		if(finished)
		{
			return 0;
		}
		int output;
		if(stage == DELAY)
		{
			eg1Current = 0;
			output = 0;
			if(tickIndex >= delayMicros)
			{
				stage = ATTACK;
				tickIndex = 10000;
			}
			else
			{
				tickIndex += 10000;
			}
		}
		else if(stage == ATTACK)
		{
			if(attackTicks == 0)
			{
				stage = holdMicros == 0 ? (decayMicros == 0 ? SUSTAIN : DECAY) : HOLD;
				tickIndex = 0;
				eg1Current = EG1_FULL;
				return nextEg1();
			}
			long level = (((long) tickIndex << 6) / Math.max(1, attackMicros >> 2)) << 8;
			if(level >= 0xFFFFL)
			{
				stage = holdMicros == 0 ? (decayMicros == 0 ? SUSTAIN : DECAY) : HOLD;
				tickIndex = 10000;
				eg1Current = EG1_FULL;
				output = eg1Level(eg1Current);
			}
			else
			{
				eg1Current = level << 16;
				output = eg1Level(eg1Current);
				tickIndex += 10000;
			}
		}
		else if(stage == HOLD)
		{
			eg1Current = EG1_FULL;
			output = eg1Level(eg1Current);
			if(tickIndex >= holdMicros)
			{
				stage = decayMicros == 0 ? SUSTAIN : DECAY;
				tickIndex = 0;
			}
			else
			{
				tickIndex += 10000;
			}
		}
		else if(stage == DECAY)
		{
			output = eg1Level(eg1Current);
			eg1Current = (eg1Current * decayMultiplier) >>> 16;
			if(eg1Current <= eg1Sustain)
			{
				stage = SUSTAIN;
				eg1Current = eg1Sustain;
			}
		}
		else if(stage == SUSTAIN)
		{
			eg1Current = eg1Sustain;
			output = eg1Level(eg1Current);
		}
		else if(stage == RELEASE || stage == SHUTDOWN)
		{
			if(activeReleaseMicros == 0)
			{
				eg1Current = 0;
				output = 0;
			}
			else
			{
				output = eg1Level(eg1Current);
				eg1Current = (eg1Current * activeReleaseMultiplier) >>> 16;
			}
		}
		else
		{
			eg1Current = 0;
			output = 0;
		}
		current = output;
		if(stage > ATTACK && output == 0)
		{
			finished = true;
			stage = FINISHED;
		}
		return output;
	}

	void release(int durationMicros)
	{
		if(!finished && stage != SHUTDOWN)
		{
			activeReleaseMicros = clamp(durationMicros, 0, 40000000);
			activeReleaseMultiplier = eg1Multiplier(activeReleaseMicros);
			tickIndex = eg1 ? 0 : fixedMul16_16(EG2_FULL - current, activeReleaseMicros);
			stage = RELEASE;
		}
	}

	void shutdown()
	{
		if(!finished)
		{
			activeReleaseMicros = FORCED_FADE_MICROS;
			activeReleaseMultiplier = eg1Multiplier(FORCED_FADE_MICROS);
			shutdownStart = current;
			tickIndex = 0;
			stage = SHUTDOWN;
		}
	}
}
