package com.soroush.agent;

public final class IntentResult {
    public enum Intent { REQUEST_RIDE, ADD_PLACE, EDIT_PLACE, CANCEL_FLOW, CANCEL_RIDE, CONFIRM, REJECT, SHOW_MAP, ANSWER, UNKNOWN }
    public final Intent intent;
    public final String origin;
    public final String destination;
    public final String target;
    public final double confidence;
    public IntentResult(Intent i,String o,String d,String t,double c){intent=i;origin=o==null?"":o;destination=d==null?"":d;target=t==null?"":t;confidence=c;}
    public static IntentResult of(Intent i){return new IntentResult(i,"","","",1.0);}
}
