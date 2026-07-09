package org.recompile.mobile.dls;

/** DLS connection block entry used by articulation and modulation. */
public final class Connection {
    public final int source;
    public final int control;
    public final int destination;
    public final int transform;
    public final int scale;

    Connection(int source, int control, int destination, int transform, int scale) {
        this.source = source;
        this.control = control;
        this.destination = destination;
        this.transform = transform;
        this.scale = scale;
    }
}



