package com.soroush.agent;

public final class SessionLifecycleManager {
    private static final long ABANDON_MS=5*60*1000L;
    private static final long EXPIRE_MS=30*60*1000L;
    public void reconcile(ConversationContext ctx){
        if(ctx==null||ctx.ride==null||ctx.ride.terminal())return;
        long age=System.currentTimeMillis()-ctx.ride.updatedAt;
        if(age>EXPIRE_MS){ctx.ride.state=RideSession.State.EXPIRED;SnappBridge.abortCurrent();ctx.ride=null;ctx.clearPending();return;}
        if(age>ABANDON_MS&&ctx.ride.state!=RideSession.State.EXECUTING){ctx.ride.state=RideSession.State.ABANDONED;SnappBridge.abortCurrent();ctx.ride=null;ctx.clearPending();return;}
        // A remembered EXECUTING state without a matching pending command is stale. Do not let it block a new goal.
        if(ctx.ride.state==RideSession.State.EXECUTING){AgentCommand p=SnappBridge.getPending();if(p==null||!ctx.ride.id.equals(p.sessionId)){ctx.ride.state=RideSession.State.ABANDONED;ctx.ride=null;ctx.clearPending();}}
    }
}
