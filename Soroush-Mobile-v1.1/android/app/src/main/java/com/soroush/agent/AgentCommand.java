package com.soroush.agent;

public final class AgentCommand {
    public enum Type { REQUEST_RIDE, CANCEL_RIDE, PAY_WALLET }
    public final String sessionId;
    public final Type type;
    public final String origin;
    public final String destination;
    public final double originLat,originLon,destinationLat,destinationLon;
    public final long createdAt=System.currentTimeMillis();
    public AgentCommand(String sessionId,Type type,String origin,String destination,double oLat,double oLon,double dLat,double dLon){this.sessionId=sessionId;this.type=type;this.origin=origin;this.destination=destination;originLat=oLat;originLon=oLon;destinationLat=dLat;destinationLon=dLon;}
    public AgentCommand(Type type,String origin,String destination){this("manual-"+System.currentTimeMillis(),type,origin,destination,Double.NaN,Double.NaN,Double.NaN,Double.NaN);}
    public boolean usesCurrentOrigin(){return "CURRENT".equals(origin);}
}
