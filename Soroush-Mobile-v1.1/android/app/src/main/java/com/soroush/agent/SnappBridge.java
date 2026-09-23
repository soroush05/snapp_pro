package com.soroush.agent;

import android.content.Context;
import android.content.Intent;
import java.util.concurrent.atomic.AtomicReference;

public final class SnappBridge {
    private static final AtomicReference<AgentCommand> pending = new AtomicReference<>();
    private static volatile Listener listener;
    private SnappBridge() {}

    public interface Listener { void onStatus(String text); }
    public static void setListener(Listener l){ listener=l; }
    public static AgentCommand getPending(){ return pending.get(); }
    public static void clear(){ pending.set(null); }
    public static void status(String s){ if(listener!=null) listener.onStatus(s); }

    public static boolean openSnapp(Context c){
        String[] pkgs={"cab.snapp.passenger","cab.snapp.passenger.play"};
        for(String p:pkgs){ Intent i=c.getPackageManager().getLaunchIntentForPackage(p); if(i!=null){i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT); c.startActivity(i); return true;} }
        status("اپ Snapp روی این گوشی پیدا نشد."); return false;
    }

    public static boolean launch(Context c, AgentCommand command) {
        // Always replace the previous command so repeated rides begin from a clean state.
        pending.set(command);
        if(openSnapp(c)){
            status("Snapp باز شد؛ در حال اجرای فرمان…");
            // Launching an already-running Snapp instance does not always emit a fresh
            // accessibility event. Kick the service explicitly as well.
            SnappAccessibilityService.kickPending();
            return true;
        }
        pending.set(null); return false;
    }
}
