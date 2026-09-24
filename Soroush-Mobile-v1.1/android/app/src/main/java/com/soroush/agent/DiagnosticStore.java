package com.soroush.agent;

import android.content.Context;
import android.content.SharedPreferences;

public final class DiagnosticStore {
    private final SharedPreferences p;
    public DiagnosticStore(Context c){p=c.getSharedPreferences("diag",Context.MODE_PRIVATE);}
    public void put(String k,String v){p.edit().putString(k,v==null?"":v).apply();}
    public String dump(){return "Intent: "+p.getString("intent","-")+"\nSession: "+p.getString("session","-")+"\nOrigin: "+p.getString("origin","-")+"\nDestination: "+p.getString("destination","-")+"\nSnapp: "+p.getString("snapp","-")+"\nLast error: "+p.getString("error","-");}
}
