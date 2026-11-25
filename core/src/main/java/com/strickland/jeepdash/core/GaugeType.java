package com.strickland.jeepdash.core;

/**
 * How a gauge shows its value. The last four belong to the Holo look: the wrap-around arcs either side of the
 * truck, the gear hexagon, and the 3D truck view itself (see HoloFace); other looks draw them as bars.
 */
public enum GaugeType { DIAL, DIGITAL, BAR, ARC_LEFT, ARC_RIGHT, HEX, TRUCK }
