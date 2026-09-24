package com.soroush.agent;

import android.content.Context;
import android.content.Intent;
import java.util.concurrent.atomic.AtomicReference;

public final class SnappBridge {
    private static final AtomicReference<AgentCommand> pending=new AtomicReference<>();
    private static volatile Listener listener;
    private SnappBridge(){}
    public interface Listener{void onEvent(SnappEvent event,String text);void onDebug(String text);}
    public static void setListener(Listener l){listener=l;}
    public static AgentCommand getPending(){return pending.get();}
    public static void clear(String sessionId){AgentCommand c=pending.get();if(c!=null&&(sessionId==null||sessionId.equals(c.sessionId)))pending.compareAndSet(c,null);}
    public static void clear(){pending.set(null);}
    public static void status(String s){event(SnappEvent.INFO,s);}
    public static void event(SnappEvent e,String s){Listener l=listener;if(l!=null)l.onEvent(e==null?SnappEvent.INFO:e,s);}
    public static void debug(String s){Listener l=listener;if(l!=null)l.onDebug(s);}
    public static void abortCurrent(){pending.set(null);SnappAccessibilityService.abortPending();debug("TECH: automation aborted");}
    public static boolean openSnapp(Context c){String[] pkgs={"cab.snapp.passenger","cab.snapp.passenger.play"};for(String p:pkgs){Intent i=c.getPackageManager().getLaunchIntentForPackage(p);if(i!=null){i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);c.startActivity(i);return true;}}event(SnappEvent.SAFE_FAILURE,"اپ Snapp روی این گوشی پیدا نشد.");return false;}
    public static boolean launch(Context c,AgentCommand cmd){abortCurrent();pending.set(cmd);if(!openSnapp(c)){pending.compareAndSet(cmd,null);return false;}debug("Snapp باز شد؛ وضعیت واقعی برنامه را دوباره بررسی می‌کنم.");SnappAccessibilityService.kickPending();return true;}
}
