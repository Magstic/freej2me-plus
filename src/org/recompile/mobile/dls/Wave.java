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

/** Decoded PCM wave data plus its sample metadata. */
final class Wave
{
	final int formatTag;
	final int channels;
	final int sampleRate;
	final int bitsPerSample;
	final int frames;
	final short[] pcm;
	final SampleInfo sample;

	Wave(int formatTag, int channels, int sampleRate, int bitsPerSample,
		int frames, short[] pcm, SampleInfo sample)
	{
		long requiredSamples = ((long) frames + 1L) * channels;
		if(frames < 0 || channels <= 0 || requiredSamples > Integer.MAX_VALUE
			|| pcm == null || pcm.length < requiredSamples)
		{
			throw new IllegalArgumentException("invalid wave dimensions");
		}
		this.formatTag = formatTag;
		this.channels = channels;
		this.sampleRate = sampleRate;
		this.bitsPerSample = bitsPerSample;
		this.frames = frames;
		this.pcm = pcm;
		this.sample = sample;
	}
}
