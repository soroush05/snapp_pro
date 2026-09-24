package com.soroush.agent;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;

public class SnappAccessibilityService extends AccessibilityService {
    private enum Screen { SUPER_HOME, ORIGIN_PICKER, ORIGIN_CONFIRM, DEST_PICKER, DEST_CONFIRM, ROUTE_READY, SEARCHING_DRIVER, ACTIVE_RIDE, UNKNOWN }
    private static volatile SnappAccessibilityService instance;
    private final Handler h=new Handler(Looper.getMainLooper());
    private AgentCommand active; private int stage=0; private long startedAt=0; private int unknownCycles=0; private String lastSnapshot="";
    @Override protected void onServiceConnected(){super.onServiceConnected();instance=this;}
    public static void kickPending(){SnappAccessibilityService s=instance;if(s!=null){s.h.removeCallbacks(s.stepper);s.h.postDelayed(s.stepper,650);}}
    @Override public void onAccessibilityEvent(AccessibilityEvent e){if(e==null||e.getPackageName()==null)return;String pkg=e.getPackageName().toString();if(!pkg.startsWith("cab.snapp.passenger"))return;AgentCommand p=SnappBridge.getPending();if(p==null)return;if(active==null||!active.sessionId.equals(p.sessionId))resetFor(p);h.removeCallbacks(stepper);h.postDelayed(stepper,280);}
    private void resetFor(AgentCommand p){active=p;stage=0;startedAt=System.currentTimeMillis();unknownCycles=0;}
    private final Runnable stepper=this::process;
    private void process(){AgentCommand p=SnappBridge.getPending();if(p==null)return;if(active==null||!active.sessionId.equals(p.sessionId))resetFor(p);if(System.currentTimeMillis()-startedAt>90000){fail("عملیات Snapp به‌موقع به وضعیت قابل اطمینان نرسید.");return;}AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null){retry(600);return;}lastSnapshot=snapshot(root);Screen screen=detect(root);SnappBridge.status("Snapp: "+screenLabel(screen));boolean done=false;if(active.type==AgentCommand.Type.REQUEST_RIDE)done=ride(root,screen);else if(active.type==AgentCommand.Type.CANCEL_RIDE)done=cancel(root);else if(active.type==AgentCommand.Type.PAY_WALLET)done=pay(root);if(!done)retry(650);}

    private boolean ride(AccessibilityNodeInfo root,Screen screen){
        if(screen==Screen.SEARCHING_DRIVER||screen==Screen.ACTIVE_RIDE){fail("Snapp یک درخواست یا سفر فعال نشان می‌دهد؛ برای جلوگیری از ایجاد درخواست ناخواسته ادامه ندادم.");return true;}
        if(screen==Screen.ROUTE_READY && stage<40){ // stale route from a previous abandoned flow
            SnappBridge.status("یک مسیر نهایی‌نشده از قبل در Snapp مانده؛ در حال برگشت به شروع برای درخواست جدید…");
            performGlobalAction(GLOBAL_ACTION_BACK);stage=1;retry(900);return true;
        }
        if(screen==Screen.ROUTE_READY && stage>=40 && stage<60){stage=60;}
        if(screen==Screen.SUPER_HOME){AccessibilityNodeInfo tile=findExactAny(root,"اسنپ","اسنپ خودرو","تاکسی اینترنتی");if(tile!=null&&click(tile)){stage=10;retry(900);return true;}return false;}
        if(screen==Screen.ORIGIN_PICKER){stage=20;if(active.usesCurrentOrigin()){AccessibilityNodeInfo cur=findAny(root,"موقعیت فعلی","مکان فعلی","موقعیت من","اینجا");if(cur!=null&&click(cur)){retry(800);return true;}}AccessibilityNodeInfo edit=bestEditable(root,"مبدا","مبدأ","جستجو");if(edit!=null&&setText(edit,active.origin)){stage=21;retry(1100);return true;}return false;}
        if(stage==21 && screen==Screen.UNKNOWN){AccessibilityNodeInfo m=findBestAddressMatch(root,active.origin);if(m!=null&&click(m)){stage=22;retry(1000);return true;}}
        if(screen==Screen.ORIGIN_CONFIRM){
            if(!active.usesCurrentOrigin()&&stage<22){AccessibilityNodeInfo search=findAny(root,"جستجو","انتخاب مبدأ","تغییر مبدأ","تغییر مبدا");if(search!=null&&click(search)){stage=20;retry(700);return true;}fail("صفحه تأیید مبدأ باز است، اما نتونستم مبدأ درخواستی رو با اطمینان تنظیم کنم؛ مبدأ فعلی رو تأیید نکردم.");return true;}
            AccessibilityNodeInfo c=findAny(root,"تایید مبدا","تأیید مبدا","تایید مبدأ","تأیید مبدأ");if(c!=null&&click(c)){stage=30;retry(1100);return true;}return false;}
        if(screen==Screen.DEST_PICKER){stage=40;AccessibilityNodeInfo edit=bestEditable(root,"مقصد","جستجو","کجا");if(edit!=null&&setText(edit,active.destination)){stage=41;retry(1100);return true;}return false;}
        if(stage==41 && screen==Screen.UNKNOWN){AccessibilityNodeInfo m=findBestAddressMatch(root,active.destination);if(m!=null&&click(m)){stage=42;retry(1000);return true;}}
        if(screen==Screen.DEST_CONFIRM){if(stage<42){fail("صفحه تأیید مقصد باز است، اما مقصد فعلی از این درخواست تأیید نشده؛ برای جلوگیری از مقصد اشتباه ادامه ندادم.");return true;}AccessibilityNodeInfo c=findAny(root,"تایید مقصد","تأیید مقصد");if(c!=null&&click(c)){stage=60;retry(1200);return true;}return false;}
        if(screen==Screen.ROUTE_READY && stage>=60){AccessibilityNodeInfo req=findAny(root,"درخواست اسنپ","درخواست خودرو","درخواست سفر");if(req!=null&&click(req)){stage=70;SnappBridge.status("درخواست نهایی سفر به Snapp ارسال شد؛ در حال بررسی نتیجه…");retry(1500);return true;}return false;}
        if(stage>=70 && (screen==Screen.SEARCHING_DRIVER||screen==Screen.ACTIVE_RIDE)){SnappBridge.status("درخواست سفر در Snapp ثبت شد.");finish();return true;}
        if(screen==Screen.UNKNOWN){unknownCycles++;if(unknownCycles>8){fail("صفحه Snapp با وضعیت مورد انتظار تطبیق نداشت؛ ادامه ندادم.");return true;}}
        else unknownCycles=0;
        return false;
    }

    private Screen detect(AccessibilityNodeInfo root){
        if(has(root,"در جستجوی راننده","جستجوی راننده","در حال یافتن راننده"))return Screen.SEARCHING_DRIVER;
        if(has(root,"راننده رسید","لغو سفر","تماس با راننده","اطلاعات راننده"))return Screen.ACTIVE_RIDE;
        if(has(root,"تایید مبدأ","تأیید مبدأ","تایید مبدا","تأیید مبدا"))return Screen.ORIGIN_CONFIRM;
        if(has(root,"تایید مقصد","تأیید مقصد"))return Screen.DEST_CONFIRM;
        if(has(root,"درخواست اسنپ","درخواست خودرو","انتخاب سرویس"))return Screen.ROUTE_READY;
        if(has(root,"مقصد کجاست","انتخاب مقصد","کجا می روید","کجا می‌روید"))return Screen.DEST_PICKER;
        if(has(root,"مبدا کجاست","مبدأ کجاست","انتخاب مبدا","انتخاب مبدأ","جستجوی مبدا","جستجوی مبدأ"))return Screen.ORIGIN_PICKER;
        if(findExactAny(root,"اسنپ","اسنپ خودرو","تاکسی اینترنتی")!=null&&has(root,"اسنپ کلاب","سوپر اپ","سرویس"))return Screen.SUPER_HOME;
        return Screen.UNKNOWN;
    }
    private String screenLabel(Screen s){switch(s){case SUPER_HOME:return "صفحه اصلی";case ORIGIN_PICKER:return "انتخاب مبدأ";case ORIGIN_CONFIRM:return "تأیید مبدأ";case DEST_PICKER:return "انتخاب مقصد";case DEST_CONFIRM:return "تأیید مقصد";case ROUTE_READY:return "مسیر آماده";case SEARCHING_DRIVER:return "در جستجوی راننده";case ACTIVE_RIDE:return "سفر فعال";default:return "در حال تشخیص";}}

    private boolean cancel(AccessibilityNodeInfo root){AccessibilityNodeInfo n=findAny(root,"لغو سفر","لغو درخواست","کنسل سفر","انصراف از سفر");if(n!=null&&click(n)){retry(800);AccessibilityNodeInfo yes=findAny(root,"تایید لغو","تأیید لغو","بله، لغو کن");if(yes!=null)click(yes);SnappBridge.status("درخواست لغو به Snapp ارسال شد.");finish();return true;}return false;}
    private boolean pay(AccessibilityNodeInfo root){AccessibilityNodeInfo p=findAny(root,"روش پرداخت","پرداخت سفر","پرداخت هزینه");if(p!=null&&click(p)){retry(700);return true;}AccessibilityNodeInfo w=findAny(root,"کیف پول","کیف‌پول","اعتبار اسنپ");if(w!=null&&click(w)){retry(700);return true;}AccessibilityNodeInfo c=findAny(root,"تایید پرداخت","تأیید پرداخت","پرداخت کن");if(c!=null&&click(c)){SnappBridge.status("فرمان پرداخت به Snapp ارسال شد.");finish();return true;}return false;}

    private AccessibilityNodeInfo findBestAddressMatch(AccessibilityNodeInfo root,String expected){AccessibilityNodeInfo best=null;int bestScore=-999,second=-999;for(AccessibilityNodeInfo x:flatten(root)){String t=nodeText(x);if(t.isEmpty()||x.isEditable())continue;int s=PersianText.addressSimilarity(expected,t);if(s>bestScore){second=bestScore;bestScore=s;best=x;}else if(s>second)second=s;}if(best==null||bestScore<28)return null;if(second>-999&&bestScore-second<8&&bestScore<85)return null;return best;}
    private boolean has(AccessibilityNodeInfo r,String...x){return findAny(r,x)!=null;}
    private AccessibilityNodeInfo findExactAny(AccessibilityNodeInfo root,String... needles){for(String needle:needles){String n=PersianText.norm(needle);for(AccessibilityNodeInfo x:flatten(root)){String t=PersianText.norm(nodeText(x));if(!t.isEmpty()&&t.equals(n))return x;}}return null;}
    private AccessibilityNodeInfo findAny(AccessibilityNodeInfo root,String... needles){for(String needle:needles){String n=PersianText.norm(needle);for(AccessibilityNodeInfo x:flatten(root)){String t=PersianText.norm(nodeText(x));if(!t.isEmpty()&&t.contains(n))return x;}}return null;}
    private AccessibilityNodeInfo bestEditable(AccessibilityNodeInfo root,String...hints){AccessibilityNodeInfo fb=null;for(AccessibilityNodeInfo n:flatten(root))if(n.isEditable()||"android.widget.EditText".contentEquals(n.getClassName())){if(fb==null)fb=n;String t=PersianText.norm(nodeText(n));for(String h:hints)if(t.contains(PersianText.norm(h)))return n;}return fb;}
    private boolean setText(AccessibilityNodeInfo n,String text){try{Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b);}catch(Exception e){return false;}}
    private boolean click(AccessibilityNodeInfo n){AccessibilityNodeInfo x=n;for(int i=0;i<6&&x!=null;i++,x=x.getParent())if(x.isClickable()&&x.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);}
    private List<AccessibilityNodeInfo> flatten(AccessibilityNodeInfo root){ArrayList<AccessibilityNodeInfo> out=new ArrayList<>();ArrayDeque<AccessibilityNodeInfo> q=new ArrayDeque<>();q.add(root);while(!q.isEmpty()&&out.size()<700){AccessibilityNodeInfo n=q.remove();out.add(n);for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null)q.add(c);}}return out;}
    private String nodeText(AccessibilityNodeInfo n){StringBuilder s=new StringBuilder();if(n.getText()!=null)s.append(n.getText()).append(' ');if(n.getContentDescription()!=null)s.append(n.getContentDescription()).append(' ');if(n.getHintText()!=null)s.append(n.getHintText());return s.toString().trim();}
    private String snapshot(AccessibilityNodeInfo root){StringBuilder s=new StringBuilder();int c=0;for(AccessibilityNodeInfo n:flatten(root)){String t=nodeText(n);if(!t.isEmpty()){if(c++>50)break;s.append(t).append(" | ");}}return s.toString();}
    private void retry(long ms){h.removeCallbacks(stepper);h.postDelayed(stepper,ms);}
    private void finish(){String id=active==null?null:active.sessionId;SnappBridge.clear(id);active=null;stage=0;unknownCycles=0;}
    private void fail(String msg){SnappBridge.status(msg);finish();}
    @Override public void onInterrupt(){}
    @Override public void onDestroy(){if(instance==this)instance=null;super.onDestroy();}
}
