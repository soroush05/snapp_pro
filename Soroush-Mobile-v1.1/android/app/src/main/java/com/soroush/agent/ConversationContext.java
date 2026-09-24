package com.soroush.agent;

import java.util.ArrayDeque;
import java.util.Deque;

/** Structured conversation state. Meaning comes from SemanticRouter; this object supplies context. */
public final class ConversationContext {
    public enum Pending {
        NONE,
        RIDE_ORIGIN, RIDE_DESTINATION,
        SAVED_OR_MAP_ORIGIN, SAVED_OR_MAP_DESTINATION,
        MAP_DETAILS_ORIGIN, MAP_DETAILS_DESTINATION,
        RIDE_CONFIRM, CANCEL_RIDE_CONFIRM,
        ADD_TITLE, ADD_ADDRESS,
        EDIT_TARGET, EDIT_ADDRESS,
        DELETE_TARGET, DELETE_CONFIRM,
        LOCATION_CONFIRM
    }
    public enum Goal { NONE, RIDE, ADD_PLACE, EDIT_PLACE, DELETE_PLACE, SEARCH_LOCATION }
    public enum EntityRole { NONE, ORIGIN, DESTINATION, SAVED_PLACE, MAP_LOCATION }

    public Pending pending=Pending.NONE;
    public Goal activeGoal=Goal.NONE;
    public RideSession ride;
    public String pendingText="";
    public String pendingTitle="";
    public String pendingCity="";
    public String lastEntity="";
    public EntityRole lastEntityRole=EntityRole.NONE;
    public SemanticIntent lastIntent=SemanticIntent.UNKNOWN;
    public final Deque<Goal> pausedGoals=new ArrayDeque<>();

    public void setPending(Pending p){pending=p==null?Pending.NONE:p;}
    public void clearPending(){pending=Pending.NONE;pendingText="";}
    public void startGoal(Goal g){if(activeGoal!=Goal.NONE&&activeGoal!=g)pausedGoals.push(activeGoal);activeGoal=g;}
    public Goal completeGoal(){Goal completed=activeGoal;activeGoal=pausedGoals.isEmpty()?Goal.NONE:pausedGoals.pop();clearPending();return completed;}
    public void cancelGoal(){activeGoal=pausedGoals.isEmpty()?Goal.NONE:pausedGoals.pop();clearPending();}
    public void abandonRide(){if(ride!=null&&!ride.terminal())ride.state=RideSession.State.ABANDONED;ride=null;if(activeGoal==Goal.RIDE)activeGoal=Goal.NONE;clearPending();}
}
