package com.soroush.agent;

import android.content.Context;
import android.content.SharedPreferences;

/** Human-readable diagnostics kept outside normal chat. */
public final class DiagnosticStore {
    private final SharedPreferences p;
    public DiagnosticStore(Context c){p=c.getSharedPreferences("diag",Context.MODE_PRIVATE);}
    public void put(String k,String v){p.edit().putString(k,v==null?"":v).apply();}
    public void clear(){p.edit().clear().apply();}
    public String dump(){
        return "Semantic intent: "+p.getString("intent","-")+"\n"+
                "Session: "+p.getString("session","-")+"\n"+
                "Origin: "+p.getString("origin","-")+"\n"+
                "Origin decision: "+p.getString("originDecision","-")+"\n"+
                "Destination: "+p.getString("destination","-")+"\n"+
                "Destination decision: "+p.getString("destinationDecision","-")+"\n"+
                "Snapp: "+p.getString("snapp","-")+"\n"+
                "Map error: "+p.getString("mapError","-")+"\n"+
                "Last error: "+p.getString("error","-");
    }
}
