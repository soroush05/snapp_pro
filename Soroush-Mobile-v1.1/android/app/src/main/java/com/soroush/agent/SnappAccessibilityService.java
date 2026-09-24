package com.soroush.agent;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;

/**
 * Goal-driven Snapp executor for alpha4.
 * It no longer trusts a remembered integer stage as the source of truth: on each cycle it observes
 * the real Snapp screen, reconciles it with the requested ride goal, then performs one bounded action.
 */
public class SnappAccessibilityService extends AccessibilityService {
    private enum Screen { SUPER_HOME, ORIGIN_PICKER, ORIGIN_CONFIRM, DEST_PICKER, DEST_CONFIRM, ROUTE_READY, SEARCHING_DRIVER, ACTIVE_RIDE, UNKNOWN }
    private enum Phase { SYNC, SEARCH_ORIGIN, CONFIRM_ORIGIN, SEARCH_DESTINATION, CONFIRM_DESTINATION, REQUEST, VERIFY_REQUEST }

    private static volatile SnappAccessibilityService instance;
    private final Handler h=new Handler(Looper.getMainLooper());
    private AgentCommand active;
    private Phase phase=Phase.SYNC;
    private long startedAt=0,phaseStartedAt=0;
    private int unknownCycles=0,requestAttempts=0,backAttempts=0;
    private boolean originQueryEntered=false,destinationQueryEntered=false;
    // A query being typed is NOT proof that Snapp selected the requested point. We only allow
    // confirmation after a concrete search result was clicked (or Current Location was explicitly used).
    private boolean originResultSelected=false,destinationResultSelected=false;
    private String originSelectedText="",destinationSelectedText="";
    private int originSelectionScore=0,destinationSelectionScore=0;
    private Screen lastScreen=null;
    private String lastSnapshot="";

    @Override protected void onServiceConnected(){super.onServiceConnected();instance=this;}
    public static void kickPending(){SnappAccessibilityService s=instance;if(s!=null){s.h.removeCallbacks(s.stepper);s.h.postDelayed(s.stepper,450);}}
    public static void abortPending(){SnappAccessibilityService s=instance;if(s!=null){s.h.removeCallbacks(s.stepper);s.active=null;s.phase=Phase.SYNC;s.originQueryEntered=false;s.destinationQueryEntered=false;s.originResultSelected=false;s.destinationResultSelected=false;s.originSelectedText="";s.destinationSelectedText="";s.originSelectionScore=0;s.destinationSelectionScore=0;s.unknownCycles=0;}}

    @Override public void onAccessibilityEvent(AccessibilityEvent e){
        if(e==null||e.getPackageName()==null)return;
        String pkg=e.getPackageName().toString();if(!pkg.startsWith("cab.snapp.passenger"))return;
        AgentCommand p=SnappBridge.getPending();if(p==null)return;
        if(active==null||!active.sessionId.equals(p.sessionId))resetFor(p);
        h.removeCallbacks(stepper);h.postDelayed(stepper,220);
    }

    private void resetFor(AgentCommand p){active=p;phase=Phase.SYNC;startedAt=System.currentTimeMillis();phaseStartedAt=startedAt;unknownCycles=0;requestAttempts=0;backAttempts=0;originQueryEntered=false;destinationQueryEntered=false;originResultSelected=false;destinationResultSelected=false;originSelectedText="";destinationSelectedText="";originSelectionScore=0;destinationSelectionScore=0;lastScreen=null;}
    private void move(Phase p){phase=p;phaseStartedAt=System.currentTimeMillis();unknownCycles=0;}
    private final Runnable stepper=this::process;

    private void process(){
        AgentCommand p=SnappBridge.getPending();if(p==null){active=null;return;}
        if(active==null||!active.sessionId.equals(p.sessionId))resetFor(p);
        if(System.currentTimeMillis()-startedAt>105000){fail("نتونستم Snapp رو در زمان مناسب به وضعیت قابل اطمینان برسونم؛ عملیات رو متوقف کردم.");return;}
        AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null){retry(500);return;}
        lastSnapshot=snapshot(root);Screen screen=detect(root);
        if(screen!=lastScreen){lastScreen=screen;SnappBridge.debug("TECH: screen="+screenLabel(screen)+" phase="+phase.name());}
        boolean consumed=false;
        if(active.type==AgentCommand.Type.REQUEST_RIDE)consumed=ride(root,screen);
        else if(active.type==AgentCommand.Type.CANCEL_RIDE)consumed=cancel(root);
        else if(active.type==AgentCommand.Type.PAY_WALLET)consumed=pay(root);
        if(!consumed)retry(600);
    }

    private boolean ride(AccessibilityNodeInfo root,Screen screen){
        if(screen==Screen.SEARCHING_DRIVER||screen==Screen.ACTIVE_RIDE){
            if(phase==Phase.VERIFY_REQUEST){SnappBridge.event(SnappEvent.RIDE_REQUEST_CONFIRMED,"درخواست سفر در Snapp ثبت شد.");finish();return true;}
            fail("Snapp یک درخواست یا سفر فعال نشون می‌ده. برای جلوگیری از ایجاد درخواست دوم، ادامه ندادم.");return true;
        }

        switch(phase){
            case SYNC:return syncToOrigin(root,screen);
            case SEARCH_ORIGIN:return searchLocation(root,screen,true);
            case CONFIRM_ORIGIN:return confirmOrigin(root,screen);
            case SEARCH_DESTINATION:return searchLocation(root,screen,false);
            case CONFIRM_DESTINATION:return confirmDestination(root,screen);
            case REQUEST:return requestRide(root,screen);
            case VERIFY_REQUEST:return verifyRequest(root,screen);
            default:return false;
        }
    }

    private boolean syncToOrigin(AccessibilityNodeInfo root,Screen screen){
        SnappBridge.debug("در حال همگام‌سازی Snapp با درخواست جدید…");
        if(screen==Screen.SUPER_HOME){AccessibilityNodeInfo tile=findExactAny(root,"اسنپ","اسنپ خودرو","تاکسی اینترنتی");if(tile!=null&&click(tile)){retry(850);return true;}return false;}
        if(screen==Screen.ORIGIN_PICKER){move(Phase.SEARCH_ORIGIN);return searchLocation(root,screen,true);}
        if(screen==Screen.ORIGIN_CONFIRM){
            if(active.usesCurrentOrigin()){move(Phase.CONFIRM_ORIGIN);return confirmOrigin(root,screen);}
            if(openLocationEditor(root,true)){move(Phase.SEARCH_ORIGIN);retry(650);return true;}
            if(backAttempts++<2){performGlobalAction(GLOBAL_ACTION_BACK);retry(700);return true;}
            fail("صفحه مبدأ رو پیدا کردم، اما کنترل قابل اطمینانی برای تغییر مبدأ پیدا نشد.");return true;
        }
        if(screen==Screen.DEST_PICKER||screen==Screen.DEST_CONFIRM||screen==Screen.ROUTE_READY){
            if(tryEditOrigin(root)){move(Phase.SEARCH_ORIGIN);retry(700);return true;}
            if(backAttempts++<3){performGlobalAction(GLOBAL_ACTION_BACK);retry(750);return true;}
            fail("Snapp روی مسیر قبلی مونده و نتونستم با اطمینان به انتخاب مبدأ برگردم.");return true;
        }
        // Search/result overlays are often exposed as UNKNOWN. If an editable is visible, use it.
        if(bestEditable(root,"مبدا","مبدأ","جستجو")!=null){move(Phase.SEARCH_ORIGIN);return searchLocation(root,screen,true);}
        AccessibilityNodeInfo tile=findExactAny(root,"اسنپ","اسنپ خودرو","تاکسی اینترنتی");
        if(tile!=null&&click(tile)){retry(800);return true;}
        return unknownOrRecover(screen,"وضعیت شروع Snapp قابل تشخیص نیست.");
    }

    private boolean searchLocation(AccessibilityNodeInfo root,Screen screen,boolean origin){
        String query=origin?active.origin:active.destination;
        String canonical=origin?active.originExpected:active.destinationExpected;
        String expected=(canonical==null||canonical.trim().isEmpty())?query:canonical;
        String expectedCity=origin?active.originCity:active.destinationCity;
        boolean current=origin&&active.usesCurrentOrigin();
        if(current){
            if(screen==Screen.ORIGIN_CONFIRM){move(Phase.CONFIRM_ORIGIN);return confirmOrigin(root,screen);}
            AccessibilityNodeInfo cur=findAny(root,"موقعیت فعلی","مکان فعلی","موقعیت من","اینجا","لوکیشن فعلی");
            if(cur!=null&&click(cur)){originResultSelected=true;originSelectedText="CURRENT";move(Phase.CONFIRM_ORIGIN);retry(750);return true;}
        }

        // Never treat arrival on a confirmation map as proof of a successful search. In alpha2 merely
        // typing text could set *QueryEntered and the current/default marker was then confirmed by mistake.
        if(origin&&screen==Screen.ORIGIN_CONFIRM){
            if(originResultSelected){move(Phase.CONFIRM_ORIGIN);return confirmOrigin(root,screen);}
            if(openLocationEditor(root,true)){retry(650);return true;}
            if(System.currentTimeMillis()-phaseStartedAt>7000){fail("صفحه تأیید مبدأ باز شد، اما Snapp هیچ نتیجه‌ای از مبدأ درخواستی رو انتخاب نکرده. برای جلوگیری از تأیید نقطه اشتباه ادامه ندادم.");return true;}
            return false;
        }
        if(!origin&&screen==Screen.DEST_CONFIRM){
            if(destinationResultSelected){move(Phase.CONFIRM_DESTINATION);return confirmDestination(root,screen);}
            if(openLocationEditor(root,false)){retry(650);return true;}
            if(System.currentTimeMillis()-phaseStartedAt>7000){fail("صفحه تأیید مقصد باز شد، اما Snapp هیچ نتیجه‌ای از مقصد درخواستی رو انتخاب نکرده. برای جلوگیری از تأیید نقطه اشتباه ادامه ندادم.");return true;}
            return false;
        }

        AccessibilityNodeInfo edit=bestEditable(root,origin?new String[]{"مبدا","مبدأ","جستجو","از کجا"}:new String[]{"مقصد","جستجو","کجا"});
        boolean entered=origin?originQueryEntered:destinationQueryEntered;
        if(edit!=null&&!entered){
            if(setText(edit,query)){
                if(origin)originQueryEntered=true;else destinationQueryEntered=true;
                SnappBridge.debug((origin?"مبدأ":"مقصد")+" در جستجوی Snapp وارد شد؛ منتظر نتیجه قابل تطبیق هستم.");
                retry(1000);return true;
            }
        }

        entered=origin?originQueryEntered:destinationQueryEntered;
        if(entered && System.currentTimeMillis()-phaseStartedAt>600){
            AddressMatch match=findBestAddressMatch(root,query,expected,expectedCity);
            if(match!=null){
                String chosen=match.text;
                if(click(match.node)){
                    if(origin){originResultSelected=true;originSelectedText=chosen;originSelectionScore=match.score;}else{destinationResultSelected=true;destinationSelectedText=chosen;destinationSelectionScore=match.score;}
                    SnappBridge.debug("TECH: "+(origin?"origin":"destination")+" result score="+match.score+" margin="+match.margin+" text="+chosen);
                    SnappBridge.debug((origin?"مبدأ":"مقصد")+" از یک نتیجه مشخص Snapp انتخاب شد؛ منتظر صفحه تأیید هستم.");
                    move(origin?Phase.CONFIRM_ORIGIN:Phase.CONFIRM_DESTINATION);retry(900);return true;
                }
            }
        }

        if(System.currentTimeMillis()-phaseStartedAt>12000){
            SnappBridge.debug("TECH: no safe result for expected="+expected+" snapshot="+lastSnapshot);
            fail("نتیجه قابل اطمینانی برای "+(origin?"مبدأ":"مقصد")+" در Snapp پیدا نکردم؛ هیچ نقطه‌ای رو حدسی تأیید نکردم.");return true;
        }
        return unknownOrRecover(screen,"نتیجه قابل اطمینانی برای "+(origin?"مبدأ":"مقصد")+" پیدا نشد.");
    }

    private boolean confirmOrigin(AccessibilityNodeInfo root,Screen screen){
        if(screen!=Screen.ORIGIN_CONFIRM){
            if(screen==Screen.DEST_PICKER||screen==Screen.DEST_CONFIRM){move(Phase.SEARCH_DESTINATION);return searchLocation(root,screen,false);}
            return unknownOrRecover(screen,"منتظر صفحه تأیید مبدأ هستم.");
        }
        if(!active.usesCurrentOrigin()&&!originResultSelected){
            if(openLocationEditor(root,true)){move(Phase.SEARCH_ORIGIN);retry(650);return true;}
            if(System.currentTimeMillis()-phaseStartedAt>7000){fail("مبدأ در Snapp قابل تأیید نبود چون انتخاب نتیجه جستجو قابل اثبات نیست.");return true;}
            return false;
        }
        String expected=active.originExpected==null||active.originExpected.trim().isEmpty()?active.origin:active.originExpected;
        if(!active.usesCurrentOrigin()&&!confirmationCompatible(root,expected,originSelectedText,active.originCity,originSelectionScore)){
            fail("صفحه تأیید مبدأ با نتیجه‌ای که انتخاب کردم سازگاری کافی نداره؛ برای جلوگیری از تأیید نقطه اشتباه متوقف شدم.");return true;
        }
        AccessibilityNodeInfo c=findAny(root,"تایید مبدا","تأیید مبدا","تایید مبدأ","تأیید مبدأ");
        if(c!=null&&click(c)){SnappBridge.debug("مبدأ انتخاب‌شده در Snapp تأیید شد؛ در حال تنظیم مقصد…");move(Phase.SEARCH_DESTINATION);retry(850);return true;}
        return false;
    }

    private boolean confirmDestination(AccessibilityNodeInfo root,Screen screen){
        if(screen==Screen.ROUTE_READY&&destinationResultSelected){move(Phase.REQUEST);return requestRide(root,screen);}
        if(screen!=Screen.DEST_CONFIRM)return unknownOrRecover(screen,"منتظر صفحه تأیید مقصد هستم.");
        if(!destinationResultSelected){
            if(openLocationEditor(root,false)){move(Phase.SEARCH_DESTINATION);retry(650);return true;}
            if(System.currentTimeMillis()-phaseStartedAt>7000){fail("مقصد در Snapp قابل تأیید نبود چون انتخاب نتیجه جستجو قابل اثبات نیست.");return true;}
            return false;
        }
        String expected=active.destinationExpected==null||active.destinationExpected.trim().isEmpty()?active.destination:active.destinationExpected;
        if(!confirmationCompatible(root,expected,destinationSelectedText,active.destinationCity,destinationSelectionScore)){
            fail("صفحه تأیید مقصد با نتیجه‌ای که انتخاب کردم سازگاری کافی نداره؛ برای جلوگیری از تأیید نقطه اشتباه متوقف شدم.");return true;
        }
        AccessibilityNodeInfo c=findAny(root,"تایید مقصد","تأیید مقصد");
        if(c!=null&&click(c)){SnappBridge.debug("مقصد انتخاب‌شده در Snapp تأیید شد؛ در حال بررسی صفحه درخواست نهایی…");move(Phase.REQUEST);retry(950);return true;}
        return false;
    }

    private boolean requestRide(AccessibilityNodeInfo root,Screen screen){
        if(screen==Screen.DEST_CONFIRM){move(Phase.CONFIRM_DESTINATION);return confirmDestination(root,screen);}
        if(screen!=Screen.ROUTE_READY)return unknownOrRecover(screen,"منتظر صفحه نهایی انتخاب سرویس هستم.");
        AccessibilityNodeInfo req=findAny(root,"درخواست اسنپ","درخواست خودرو","درخواست سفر");
        if(req!=null&&click(req)){requestAttempts++;move(Phase.VERIFY_REQUEST);SnappBridge.debug("دکمه درخواست سفر زده شد؛ منتظر تأیید واقعی Snapp هستم.");retry(1400);return true;}
        return false;
    }

    private boolean verifyRequest(AccessibilityNodeInfo root,Screen screen){
        if(screen==Screen.SEARCHING_DRIVER||screen==Screen.ACTIVE_RIDE){SnappBridge.event(SnappEvent.RIDE_REQUEST_CONFIRMED,"درخواست سفر در Snapp ثبت شد.");finish();return true;}
        if(screen==Screen.ROUTE_READY&&requestAttempts<2){move(Phase.REQUEST);return requestRide(root,screen);}
        if(System.currentTimeMillis()-phaseStartedAt>9000){fail("دکمه درخواست زده شد، اما Snapp ثبت نهایی سفر رو تأیید نکرد. برای جلوگیری از وضعیت مبهم ادامه ندادم.");return true;}
        return false;
    }

    private boolean openLocationEditor(AccessibilityNodeInfo root,boolean origin){
        // Prefer explicit edit/select controls. A generic "جستجو" may belong to the Super App search bar
        // and caused alpha2 to enter text in the wrong control.
        AccessibilityNodeInfo direct=findAny(root,origin?new String[]{"تغییر مبدأ","تغییر مبدا","انتخاب مبدأ","انتخاب مبدا","ویرایش مبدأ","ویرایش مبدا"}:new String[]{"تغییر مقصد","انتخاب مقصد","ویرایش مقصد"});
        if(direct!=null&&click(direct))return true;
        AccessibilityNodeInfo panel=findBottomAddressPanel(root,origin);if(panel!=null&&click(panel))return true;
        AccessibilityNodeInfo search=findLowerScreenControl(root,"جستجو");return search!=null&&click(search);
    }

    private AccessibilityNodeInfo findLowerScreenControl(AccessibilityNodeInfo root,String needle){
        Rect rb=new Rect();root.getBoundsInScreen(rb);int h=Math.max(1,rb.height());String q=PersianText.norm(needle);
        for(AccessibilityNodeInfo n:flatten(root)){String t=PersianText.norm(nodeText(n));if(!t.contains(q))continue;AccessibilityNodeInfo c=clickableAncestor(n);if(c==null)continue;Rect r=new Rect();c.getBoundsInScreen(r);int cy=r.centerY()-rb.top;if(cy>h*45/100)return c;}return null;
    }

    private boolean tryEditOrigin(AccessibilityNodeInfo root){
        AccessibilityNodeInfo n=findExactAny(root,"مبدأ","مبدا");
        if(n!=null&&click(n))return true;
        n=findAny(root,"ویرایش مبدأ","ویرایش مبدا","تغییر مبدأ","تغییر مبدا");
        return n!=null&&click(n);
    }

    /**
     * Fallback for Snapp's map confirmation UI: the address/search card is visually near the bottom
     * but its text may be a reverse-geocoded street name, not the word "جستجو". We only choose a
     * clickable textual node in the lower part of the screen and explicitly exclude confirm buttons.
     */
    private AccessibilityNodeInfo findBottomAddressPanel(AccessibilityNodeInfo root,boolean origin){
        Rect rb=new Rect();root.getBoundsInScreen(rb);int height=Math.max(1,rb.height());AccessibilityNodeInfo best=null;int bestScore=-999;
        Set<AccessibilityNodeInfo> seen=Collections.newSetFromMap(new IdentityHashMap<AccessibilityNodeInfo,Boolean>());
        for(AccessibilityNodeInfo n:flatten(root)){
            AccessibilityNodeInfo clickable=clickableAncestor(n);if(clickable==null||seen.contains(clickable))continue;seen.add(clickable);
            Rect r=new Rect();clickable.getBoundsInScreen(r);int cy=r.centerY()-rb.top;if(cy<height*55/100||cy>height*94/100)continue;
            String t=aggregateText(clickable);if(t.length()<4||isControlLike(t))continue;
            int tokens=PersianText.addressTokens(t).size();if(tokens<2)continue;
            int score=tokens*8 + Math.min(30,t.length()/5);
            if(score>bestScore){bestScore=score;best=clickable;}
        }
        return best;
    }

    private AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n){AccessibilityNodeInfo x=n;for(int i=0;i<6&&x!=null;i++,x=x.getParent())if(x.isClickable())return x;return null;}

    private boolean unknownOrRecover(Screen screen,String reason){
        if(screen==Screen.UNKNOWN){unknownCycles++;if(unknownCycles>12){SnappBridge.debug("TECH: snapshot="+lastSnapshot);fail(reason+" اگر صفحه Snapp تغییر کرده، یک اسکرین‌شات از همین صفحه بفرست.");return true;}}
        else unknownCycles=0;
        return false;
    }

    private Screen detect(AccessibilityNodeInfo root){
        if(has(root,"در جستجوی راننده","جستجوی راننده","در حال یافتن راننده","در حال جستجوی خودرو"))return Screen.SEARCHING_DRIVER;
        if(has(root,"راننده رسید","لغو سفر","تماس با راننده","اطلاعات راننده","مشخصات راننده"))return Screen.ACTIVE_RIDE;
        if(has(root,"تایید مبدأ","تأیید مبدأ","تایید مبدا","تأیید مبدا"))return Screen.ORIGIN_CONFIRM;
        if(has(root,"تایید مقصد","تأیید مقصد"))return Screen.DEST_CONFIRM;
        if(has(root,"درخواست اسنپ","درخواست خودرو","درخواست سفر")&&has(root,"ماشین","موتور","پیک","اسنپ"))return Screen.ROUTE_READY;
        if(has(root,"مقصد کجاست","انتخاب مقصد","کجا می روید","کجا می‌روید","جستجوی مقصد"))return Screen.DEST_PICKER;
        if(has(root,"مبدا کجاست","مبدأ کجاست","انتخاب مبدا","انتخاب مبدأ","جستجوی مبدا","جستجوی مبدأ","از کجا"))return Screen.ORIGIN_PICKER;
        if(findExactAny(root,"اسنپ","اسنپ خودرو","تاکسی اینترنتی")!=null&&has(root,"اسنپ کلاب","سوپر اپ","سرویس"))return Screen.SUPER_HOME;
        return Screen.UNKNOWN;
    }

    private String screenLabel(Screen s){switch(s){case SUPER_HOME:return "صفحه اصلی";case ORIGIN_PICKER:return "انتخاب مبدأ";case ORIGIN_CONFIRM:return "تأیید مبدأ";case DEST_PICKER:return "انتخاب مقصد";case DEST_CONFIRM:return "تأیید مقصد";case ROUTE_READY:return "مسیر آماده";case SEARCHING_DRIVER:return "در جستجوی راننده";case ACTIVE_RIDE:return "سفر فعال";default:return "نامشخص";}}

    private boolean cancel(AccessibilityNodeInfo root){
        AccessibilityNodeInfo n=findAny(root,"لغو سفر","لغو درخواست","کنسل سفر","انصراف از سفر");
        if(n!=null&&click(n)){retry(700);AccessibilityNodeInfo yes=findAny(root,"تایید لغو","تأیید لغو","بله، لغو کن","لغو کن");if(yes!=null)click(yes);SnappBridge.event(SnappEvent.RIDE_CANCEL_SENT,"درخواست لغو به Snapp ارسال شد.");finish();return true;}
        if(System.currentTimeMillis()-phaseStartedAt>10000){fail("گزینه مطمئنی برای لغو سفر در صفحه فعلی Snapp پیدا نکردم.");return true;}return false;
    }

    private boolean pay(AccessibilityNodeInfo root){AccessibilityNodeInfo p=findAny(root,"روش پرداخت","پرداخت سفر","پرداخت هزینه");if(p!=null&&click(p)){retry(700);return true;}AccessibilityNodeInfo w=findAny(root,"کیف پول","کیف‌پول","اعتبار اسنپ");if(w!=null&&click(w)){retry(700);return true;}AccessibilityNodeInfo c=findAny(root,"تایید پرداخت","تأیید پرداخت","پرداخت کن");if(c!=null&&click(c)){SnappBridge.event(SnappEvent.PAYMENT_SENT,"فرمان پرداخت به Snapp ارسال شد.");finish();return true;}return false;}

    private AddressMatch findBestAddressMatch(AccessibilityNodeInfo root,String query,String expected,String expectedCity){
        AccessibilityNodeInfo best=null;String bestText="";int bestScore=-999,second=-999;
        Set<AccessibilityNodeInfo> seen=Collections.newSetFromMap(new IdentityHashMap<AccessibilityNodeInfo,Boolean>());
        int expectedTokens=Math.max(PersianText.addressTokens(expected).size(),PersianText.addressTokens(query).size());
        String city=PersianText.addressNorm(expectedCity);
        for(AccessibilityNodeInfo x:flatten(root)){
            if(x.isEditable())continue;AccessibilityNodeInfo clickable=clickableAncestor(x);if(clickable==null||seen.contains(clickable))continue;seen.add(clickable);
            String t=aggregateText(clickable);if(t.isEmpty()||isControlLike(t))continue;
            int s=Math.max(PersianText.addressSimilarity(expected,t),PersianText.addressSimilarity(query,t));
            int candidateTokens=PersianText.addressTokens(t).size();if(candidateTokens>=2)s+=6;
            if(!city.isEmpty()&&PersianText.addressNorm(t).contains(city))s+=18;
            if(s>bestScore){second=bestScore;bestScore=s;best=clickable;bestText=t;}else if(s>second)second=s;
        }
        int min=expectedTokens<=1?62:48;if(best==null||bestScore<min)return null;
        int margin=second<=-900?99:bestScore-second;if(margin<10&&bestScore<96)return null;
        return new AddressMatch(best,bestText,bestScore,margin);
    }

    private boolean confirmationCompatible(AccessibilityNodeInfo root,String expected,String selected,String city,int selectionScore){
        AccessibilityNodeInfo panel=findBottomAddressPanel(root,false);String visible=panel==null?"":aggregateText(panel);
        if(visible.isEmpty())return selectionScore>=58;
        int a=PersianText.addressSimilarity(expected,visible),b=PersianText.addressSimilarity(selected,visible);
        String nc=PersianText.addressNorm(city),nv=PersianText.addressNorm(visible);if(!nc.isEmpty()&&nv.contains(nc)){a+=18;b+=18;}
        if(Math.max(a,b)>=22)return true;
        // A genuinely address-like but conflicting confirmation card is evidence against the selection.
        if(PersianText.addressTokens(visible).size()>=2&&Math.max(a,b)<10)return false;
        return selectionScore>=78;
    }

    private static final class AddressMatch{final AccessibilityNodeInfo node;final String text;final int score,margin;AddressMatch(AccessibilityNodeInfo n,String t,int s,int m){node=n;text=t;score=s;margin=m;}}

    private boolean isControlLike(String text){
        String n=PersianText.norm(text);
        return n.contains("تایید")||n.contains("تأیید")||n.contains("لغو")||n.contains("درخواست اسنپ")||
                n.contains("جستجوی راننده")||n.equals("مبدأ")||n.equals("مبدا")||n.equals("مقصد")||
                n.contains("اسنپ کلاب")||n.contains("راهنمایی")||n.contains("برای خودم")||n.contains("بازگشت");
    }

    private String aggregateText(AccessibilityNodeInfo node){
        if(node==null)return "";StringBuilder s=new StringBuilder();ArrayDeque<AccessibilityNodeInfo> q=new ArrayDeque<>();q.add(node);int count=0;
        while(!q.isEmpty()&&count++<45){AccessibilityNodeInfo n=q.remove();String t=nodeText(n);if(!t.isEmpty())s.append(t).append(' ');for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null)q.add(c);}}
        return s.toString().replaceAll("\\s+"," ").trim();
    }

    private boolean has(AccessibilityNodeInfo r,String...x){return findAny(r,x)!=null;}
    private AccessibilityNodeInfo findExactAny(AccessibilityNodeInfo root,String... needles){for(String needle:needles){String n=PersianText.norm(needle);for(AccessibilityNodeInfo x:flatten(root)){String t=PersianText.norm(nodeText(x));if(!t.isEmpty()&&t.equals(n))return x;}}return null;}
    private AccessibilityNodeInfo findAny(AccessibilityNodeInfo root,String... needles){for(String needle:needles){String n=PersianText.norm(needle);for(AccessibilityNodeInfo x:flatten(root)){String t=PersianText.norm(nodeText(x));if(!t.isEmpty()&&t.contains(n))return x;}}return null;}
    private AccessibilityNodeInfo bestEditable(AccessibilityNodeInfo root,String...hints){AccessibilityNodeInfo fb=null;for(AccessibilityNodeInfo n:flatten(root))if(n.isEditable()||"android.widget.EditText".contentEquals(n.getClassName())){if(fb==null)fb=n;String t=PersianText.norm(nodeText(n));for(String h:hints)if(t.contains(PersianText.norm(h)))return n;}return fb;}
    private boolean setText(AccessibilityNodeInfo n,String text){try{n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b);}catch(Exception e){return false;}}
    private boolean click(AccessibilityNodeInfo n){AccessibilityNodeInfo x=n;for(int i=0;i<6&&x!=null;i++,x=x.getParent())if(x.isClickable()&&x.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);}
    private List<AccessibilityNodeInfo> flatten(AccessibilityNodeInfo root){ArrayList<AccessibilityNodeInfo> out=new ArrayList<>();ArrayDeque<AccessibilityNodeInfo> q=new ArrayDeque<>();q.add(root);while(!q.isEmpty()&&out.size()<900){AccessibilityNodeInfo n=q.remove();out.add(n);for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null)q.add(c);}}return out;}
    private String nodeText(AccessibilityNodeInfo n){StringBuilder s=new StringBuilder();if(n.getText()!=null)s.append(n.getText()).append(' ');if(n.getContentDescription()!=null)s.append(n.getContentDescription()).append(' ');if(n.getHintText()!=null)s.append(n.getHintText());return s.toString().trim();}
    private String snapshot(AccessibilityNodeInfo root){StringBuilder s=new StringBuilder();int c=0;for(AccessibilityNodeInfo n:flatten(root)){String t=nodeText(n);if(!t.isEmpty()){if(c++>70)break;s.append(t).append(" | ");}}return s.toString();}
    private void retry(long ms){h.removeCallbacks(stepper);h.postDelayed(stepper,ms);}
    private void finish(){String id=active==null?null:active.sessionId;SnappBridge.clear(id);active=null;phase=Phase.SYNC;unknownCycles=0;originQueryEntered=false;destinationQueryEntered=false;originResultSelected=false;destinationResultSelected=false;originSelectedText="";destinationSelectedText="";originSelectionScore=0;destinationSelectionScore=0;}
    private void fail(String msg){SnappBridge.event(SnappEvent.SAFE_FAILURE,msg);finish();}
    @Override public void onInterrupt(){}
    @Override public void onDestroy(){if(instance==this)instance=null;super.onDestroy();}
}
