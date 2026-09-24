package com.soroush.agent;

/** Small policy object so new intents can interrupt/pause old goals without leaking state-machine logic into NLU. */
public final class GoalManager {
    public void begin(ConversationContext ctx,ConversationContext.Goal goal){
        if(ctx==null)return;
        if(ctx.activeGoal==goal)return;
        // A ride is valuable context and may be resumed after a short Saved Place sub-flow.
        if((goal==ConversationContext.Goal.ADD_PLACE||goal==ConversationContext.Goal.EDIT_PLACE||goal==ConversationContext.Goal.DELETE_PLACE)
                && ctx.activeGoal==ConversationContext.Goal.RIDE){
            ctx.pausedGoals.push(ConversationContext.Goal.RIDE);
            ctx.activeGoal=goal;ctx.clearPending();return;
        }
        ctx.startGoal(goal);
    }
    public ConversationContext.Goal complete(ConversationContext ctx){return ctx==null?ConversationContext.Goal.NONE:ctx.completeGoal();}
    public void cancel(ConversationContext ctx){if(ctx!=null)ctx.cancelGoal();}
}
