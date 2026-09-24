package com.soroush.agent;

/** Immutable, session-scoped command handed to deterministic provider execution. */
public final class AgentCommand {
    public enum Type { REQUEST_RIDE, CANCEL_RIDE, PAY_WALLET }
    public final String sessionId;
    public final Type type;
    /** Text entered into provider search. */
    public final String origin,destination;
    /** Canonical text used for verification/ranking; may differ from shorter search query. */
    public final String originExpected,destinationExpected,originCity,destinationCity;
    public final double originLat,originLon,destinationLat,destinationLon;
    public final long createdAt=System.currentTimeMillis();

    public AgentCommand(String sessionId,Type type,String origin,String destination,String originExpected,String destinationExpected,String originCity,String destinationCity,double oLat,double oLon,double dLat,double dLon){
        this.sessionId=s(sessionId);this.type=type;this.origin=s(origin);this.destination=s(destination);this.originExpected=s(originExpected);this.destinationExpected=s(destinationExpected);this.originCity=s(originCity);this.destinationCity=s(destinationCity);originLat=oLat;originLon=oLon;destinationLat=dLat;destinationLon=dLon;
    }
    public AgentCommand(String sessionId,Type type,String origin,String destination,double oLat,double oLon,double dLat,double dLon){this(sessionId,type,origin,destination,origin,destination,"","",oLat,oLon,dLat,dLon);}
    public AgentCommand(Type type,String origin,String destination){this("manual-"+System.currentTimeMillis(),type,origin,destination,origin,destination,"","",Double.NaN,Double.NaN,Double.NaN,Double.NaN);}
    public boolean usesCurrentOrigin(){return "CURRENT".equals(origin);}
    private static String s(String x){return x==null?"":x;}
}
