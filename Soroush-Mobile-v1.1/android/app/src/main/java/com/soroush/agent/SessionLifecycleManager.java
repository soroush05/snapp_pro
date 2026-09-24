package com.soroush.agent;

/** Reconciles stale internal ride state so one abandoned request can never permanently block the next. */
public final class SessionLifecycleManager {
    private static final long ABANDON_MS=5*60*1000L;
    private static final long EXPIRE_MS=30*60*1000L;
    public void reconcile(ConversationContext ctx){
        if(ctx==null||ctx.ride==null||ctx.ride.terminal())return;
        long age=System.currentTimeMillis()-ctx.ride.updatedAt;
        if(age>EXPIRE_MS){terminal(ctx,RideSession.State.EXPIRED);return;}
        if(age>ABANDON_MS&&ctx.ride.state!=RideSession.State.EXECUTING){terminal(ctx,RideSession.State.ABANDONED);return;}
        if(ctx.ride.state==RideSession.State.EXECUTING){
            AgentCommand p=SnappBridge.getPending();
            if(p==null||!ctx.ride.id.equals(p.sessionId))terminal(ctx,RideSession.State.ABANDONED);
        }
    }
    private void terminal(ConversationContext ctx,RideSession.State s){
        if(ctx.ride!=null)ctx.ride.state=s;SnappBridge.abortCurrent();ctx.ride=null;ctx.clearPending();
        if(ctx.activeGoal==ConversationContext.Goal.RIDE)ctx.activeGoal=ConversationContext.Goal.NONE;
        ctx.pausedGoals.remove(ConversationContext.Goal.RIDE);
    }
}
