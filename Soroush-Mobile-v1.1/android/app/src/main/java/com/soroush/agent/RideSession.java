package com.soroush.agent;

import java.util.UUID;

public final class RideSession {
    public enum State { COLLECTING, READY_FOR_CONFIRMATION, EXECUTING, COMPLETED, CANCELLED, FAILED, ABANDONED, EXPIRED }
    public final String id=UUID.randomUUID().toString();
    public final long createdAt=System.currentTimeMillis();
    public long updatedAt=createdAt;
    public LocationRef origin;
    public LocationRef destination;
    public String originSpec="";
    public String destinationSpec="";
    public State state=State.COLLECTING;
    public void touch(){updatedAt=System.currentTimeMillis();}
    public boolean terminal(){return state==State.COMPLETED||state==State.CANCELLED||state==State.FAILED||state==State.ABANDONED||state==State.EXPIRED;}
}
