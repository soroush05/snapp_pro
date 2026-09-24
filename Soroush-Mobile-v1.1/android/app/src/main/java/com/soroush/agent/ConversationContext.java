package com.soroush.agent;

public final class ConversationContext {
    public enum Pending { NONE, RIDE_ORIGIN, RIDE_DESTINATION, SAVED_OR_MAP_ORIGIN, SAVED_OR_MAP_DESTINATION, MAP_DETAILS_ORIGIN, MAP_DETAILS_DESTINATION, RIDE_CONFIRM, CANCEL_RIDE_CONFIRM, ADD_CITY, ADD_TITLE, ADD_ADDRESS, EDIT_TARGET, EDIT_ADDRESS, LOCATION_CONFIRM }
    public Pending pending=Pending.NONE;
    public RideSession ride;
    public String pendingText="";
    public String pendingTitle="";
    public String pendingCity="";
    public String lastEntity="";
    public void clearPending(){pending=Pending.NONE;pendingText="";}
    public void abandonRide(){if(ride!=null&&!ride.terminal())ride.state=RideSession.State.ABANDONED;ride=null;clearPending();}
}
