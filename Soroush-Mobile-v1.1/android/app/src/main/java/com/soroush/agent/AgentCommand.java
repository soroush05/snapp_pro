package com.soroush.agent;

public final class AgentCommand {
    public enum Type { REQUEST_RIDE, CANCEL_RIDE, PAY_WALLET }
    public final Type type;
    public final String origin;
    public final String destination;
    public final double destinationLat;
    public final double destinationLon;

    public AgentCommand(Type type, String origin, String destination) {
        this(type, origin, destination, Double.NaN, Double.NaN);
    }

    public AgentCommand(Type type, String origin, String destination, double destinationLat, double destinationLon) {
        this.type=type; this.origin=origin; this.destination=destination;
        this.destinationLat=destinationLat; this.destinationLon=destinationLon;
    }

    public boolean hasDestinationPoint(){ return !Double.isNaN(destinationLat) && !Double.isNaN(destinationLon); }
}
