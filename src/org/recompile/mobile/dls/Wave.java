package org.recompile.mobile.dls;

/** Decoded PCM wave data plus its sample metadata. */
public final class Wave {
    public final int index;
    public final int formatTag;
    public final int channels;
    public final int sampleRate;
    public final int bitsPerSample;
    public final int frames;
    public final int factFrames;
    public final short[] pcm;
    public final SampleInfo sample;

    Wave(int index, int formatTag, int channels, int sampleRate, int bitsPerSample,
                 int frames, int factFrames, short[] pcm, SampleInfo sample) {
        this.index = index;
        this.formatTag = formatTag;
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.bitsPerSample = bitsPerSample;
        this.frames = frames;
        this.factFrames = factFrames;
        this.pcm = pcm;
        this.sample = sample;
    }
}



