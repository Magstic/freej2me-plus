package org.recompile.mobile.dls;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.decoder.Obuffer;

/** One-shot MPEG Layer I/II/III conversion used while loading compressed DLS waves. */
final class MpegDecoder {
    private static final int[][] SAMPLE_RATES = {{22050, 24000, 16000}, {44100, 48000, 32000}};
    private static final int[][][] BITRATES = {
            {
                    {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256},
                    {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160},
                    {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160}
            },
            {
                    {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448},
                    {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384},
                    {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320}
            }
    };

    private MpegDecoder() {
    }

    static Decoded decode(byte[] encoded, Fmt fmt) {
        if (fmt.channels != 1 && fmt.channels != 2) {
            throw new IllegalArgumentException("bad MPEG wave");
        }
        ByteArrayOutputStream framesOnly = new ByteArrayOutputStream(encoded.length);
        int frames = scanFrames(encoded, framesOnly);
        if (frames == 0) {
            throw new IllegalArgumentException("empty MPEG data");
        }
        if (frames > Integer.MAX_VALUE / fmt.channels - 1) {
            throw new IllegalArgumentException("MPEG wave is too long");
        }
        PcmBuffer pcm = new PcmBuffer(frames, fmt.channels);
        Bitstream stream = new Bitstream(new ByteArrayInputStream(framesOnly.toByteArray()));
        Decoder decoder = new Decoder();
        decoder.setOutputBuffer(pcm);
        try {
            Header header;
            while (pcm.frames() < frames && (header = stream.readFrame()) != null) {
                try {
                    int channels = header.mode() == Header.SINGLE_CHANNEL ? 1 : 2;
                    if ((header.version() != Header.MPEG1 && header.version() != Header.MPEG2_LSF)
                            || header.frequency() != fmt.sampleRate || channels != fmt.channels) {
                        throw new IllegalArgumentException("MPEG stream does not match fmt");
                    }
                    int frameStart = pcm.frames();
                    decoder.decodeFrame(header, stream);
                    if (pcm.overflow) {
                        throw new IllegalArgumentException("MPEG decoder exceeded frame boundary");
                    }
                    int samples = header.layer() == 1 ? 384
                            : header.layer() == 3 && header.version() == Header.MPEG2_LSF ? 576 : 1152;
                    if (pcm.frames() == frameStart) {
                        pcm.replaceFrameWithSilence(frameStart, samples);
                    } else if (pcm.frames() != frameStart + samples) {
                        throw new IllegalArgumentException("MPEG decoder produced partial frame");
                    }
                } finally {
                    stream.closeFrame();
                }
            }
        } catch (JavaLayerException ex) {
            throw new IllegalArgumentException("bad MPEG data", ex);
        }
        return new Decoded(Arrays.copyOf(pcm.samples, (frames + 1) * fmt.channels), frames);
    }

    static int scanFrames(byte[] encoded) {
        return scanFrames(encoded, null);
    }

    private static int scanFrames(byte[] encoded, ByteArrayOutputStream framesOnly) {
        long frames = 0;
        for (int p = 0; p + 4 <= encoded.length;) {
            int header = ((encoded[p] & 0xFF) << 24) | ((encoded[p + 1] & 0xFF) << 16)
                    | ((encoded[p + 2] & 0xFF) << 8) | (encoded[p + 3] & 0xFF);
            int version = (header >>> 19) & 3;
            int layerBits = (header >>> 17) & 3;
            int bitrateIndex = (header >>> 12) & 15;
            int rateIndex = (header >>> 10) & 3;
            if ((encoded[p] & 0xFF) != 0xFF || ((encoded[p + 1] & 0xFF) & 0xF6) <= 0xF0
                    || (version != 2 && version != 3) || layerBits == 0 || bitrateIndex == 15
                    || rateIndex == 3 || (header & 3) == 2) {
                p++;
                continue;
            }
            int layer = 4 - layerBits;
            int rate = SAMPLE_RATES[version == 3 ? 1 : 0][rateIndex];
            int samples = layer == 1 ? 384 : (layer == 3 && version == 2 ? 576 : 1152);
            int frameBytes;
            if (bitrateIndex == 0) {
                int next = p + 4;
                int mask = 0xFFF80CC0;
                while (next + 4 <= encoded.length && (readInt(encoded, next) & mask) != (header & mask)) {
                    next++;
                }
                if (next + 4 > encoded.length) {
                    break;
                }
                frameBytes = next - p;
            } else {
                int bitrate = BITRATES[version == 3 ? 1 : 0][layer - 1][bitrateIndex] * 1000;
                int padding = (header >>> 9) & 1;
                frameBytes = layer == 1
                        ? (12 * bitrate / rate + padding) * 4
                        : samples * bitrate / rate / 8 + padding;
            }
            if (frameBytes <= 4 || p + frameBytes > encoded.length) {
                p++;
                continue;
            }
            if (framesOnly != null) {
                framesOnly.write(encoded, p, frameBytes);
            }
            frames += samples;
            if (frames > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("MPEG wave is too long");
            }
            p += frameBytes;
        }
        return (int) frames;
    }

    private static int readInt(byte[] data, int p) {
        return ((data[p] & 0xFF) << 24) | ((data[p + 1] & 0xFF) << 16)
                | ((data[p + 2] & 0xFF) << 8) | (data[p + 3] & 0xFF);
    }

    private static final class PcmBuffer extends Obuffer {
        final short[] samples;
        final int channels;
        final int sampleLimit;
        final int[] positions = new int[2];
        boolean overflow;

        PcmBuffer(int frames, int channels) {
            this.channels = channels;
            samples = new short[(frames + 1) * channels];
            sampleLimit = frames * channels;
            positions[1] = 1;
        }

        int frames() {
            return Math.min(positions[0] / channels, sampleLimit / channels);
        }

        void replaceFrameWithSilence(int startFrame, int frameCount) {
            int start = startFrame * channels;
            int end = (startFrame + frameCount) * channels;
            Arrays.fill(samples, start, end, (short) 0);
            positions[0] = end;
            positions[1] = end + 1;
        }

        @Override
        public void append(int channel, short value) {
            int position = positions[channel];
            if (position < sampleLimit) {
                samples[position] = value;
                positions[channel] += channels;
            } else {
                overflow = true;
            }
        }

        @Override public void write_buffer(int value) {}
        @Override public void close() {}
        @Override public void clear_buffer() {}
        @Override public void set_stop_flag() {}
    }
}
