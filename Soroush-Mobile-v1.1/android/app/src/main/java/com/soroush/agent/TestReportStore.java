package com.soroush.agent;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Persistent test-session log used to export one copyable conversation/report during alpha testing. */
public final class TestReportStore {
    private static final String PREFS="test_report";
    private static final String K_CONVERSATION="conversation";
    private static final String K_TRACE="trace";
    private static final String K_STARTED="started";
    private static final int MAX_CHARS=500000;
    private final SharedPreferences p;

    public TestReportStore(Context c){
        p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        ensureStarted();
    }

    public void ensureStarted(){
        if(p.getLong(K_STARTED,0L)==0L)p.edit().putLong(K_STARTED,System.currentTimeMillis()).apply();
    }

    public void appendConversation(String role,String text){
        String safe=text==null?"":text;
        append(K_CONVERSATION,"["+time(System.currentTimeMillis())+"] "+(role==null?"UNKNOWN":role)+"\n"+safe+"\n\n");
    }

    public void appendTrace(String category,String text){
        String safe=redactTrace(text==null?"":text);
        append(K_TRACE,"["+time(System.currentTimeMillis())+"] "+(category==null?"TRACE":category)+"\n"+safe+"\n\n");
    }

    public String conversationDump(){
        String s=p.getString(K_CONVERSATION,"");
        return s==null||s.trim().isEmpty()?"(هنوز مکالمه‌ای در Test Log ثبت نشده است.)":s.trim();
    }

    public String fullReport(String version,String currentState,String diagnostics){
        StringBuilder out=new StringBuilder();
        out.append("SOROUSH TEST REPORT\n");
        out.append("===================\n\n");
        out.append("App Version: ").append(clean(version)).append('\n');
        out.append("Test Log Started: ").append(time(p.getLong(K_STARTED,System.currentTimeMillis()))).append("\n\n");
        out.append("CURRENT STATE\n-------------\n").append(clean(currentState)).append("\n\n");
        out.append("CURRENT DIAGNOSTICS\n-------------------\n").append(clean(diagnostics)).append("\n\n");
        out.append("CONVERSATION\n------------\n").append(conversationDump()).append("\n\n");
        out.append("DECISIONS / MAP / SNAPP TRACE\n----------------------------\n");
        String t=p.getString(K_TRACE,"");
        out.append(t==null||t.trim().isEmpty()?"(هیچ trace فنی ثبت نشده است.)":t.trim());
        return out.toString();
    }

    public void clear(){
        p.edit().clear().putLong(K_STARTED,System.currentTimeMillis()).apply();
        appendTrace("TEST","Test log reset by user.");
    }

    private void append(String key,String block){
        String old=p.getString(key,"");if(old==null)old="";
        String next=old+block;
        if(next.length()>MAX_CHARS)next=next.substring(next.length()-MAX_CHARS);
        p.edit().putString(key,next).apply();
    }

    private static String time(long ms){
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.US).format(new Date(ms));
    }

    private static String clean(String s){return s==null?"":s;}

    /** Trace can include library/debug messages; redact common credential-shaped values. */
    private static String redactTrace(String s){
        String r=s;
        r=r.replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+\\-/=]+","$1<redacted>");
        r=r.replaceAll("(?i)((?:api[_-]?key|password|token|secret)\\s*[:=]\\s*)[^\\s,;]+","$1<redacted>");
        return r;
    }
}
