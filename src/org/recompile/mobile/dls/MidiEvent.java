package org.recompile.mobile.dls;

/** Single MIDI event normalized to absolute ticks and microseconds. */
public final class MidiEvent extends SynthesisSupport {
    public final long tick;
    public long micros;
    public final int track;
    public final int order;
    public final int status;
    public final int channel;
    public final int data1;
    public final int data2;
    public final int metaType;
    public final byte[] payload;

    MidiEvent(long tick, int track, int order, int status, int channel, int data1, int data2,
                      int metaType, byte[] payload) {
        this.tick = tick;
        this.track = track;
        this.order = order;
        this.status = status;
        this.channel = channel;
        this.data1 = data1;
        this.data2 = data2;
        this.metaType = metaType;
        this.payload = payload == null ? new byte[0] : payload;
    }

    public boolean isNoteOn() {
        return (status & 0xF0) == 0x90 && data2 != 0;
    }

    public boolean isNoteOff() {
        return (status & 0xF0) == 0x80 || ((status & 0xF0) == 0x90 && data2 == 0);
    }
}



