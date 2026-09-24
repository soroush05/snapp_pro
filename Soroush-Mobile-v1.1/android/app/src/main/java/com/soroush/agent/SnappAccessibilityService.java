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
    private boolean originConfirmClicked=false,destinationConfirmClicked=false;
    private boolean originConfirmedInSnapp=false,destinationConfirmedInSnapp=false,requestButtonClicked=false;
    private String originSelectedText="",destinationSelectedText="";
    private int originSelectionScore=0,destinationSelectionScore=0;
    private boolean originCityMatched=false,destinationCityMatched=false;
    private int reconciliationAttempts=0;
    private Screen lastScreen=null;
    private String lastSnapshot="";

    @Override protected void onServiceConnected(){super.onServiceConnected();instance=this;}
    public static void kickPending(){SnappAccessibilityService s=instance;if(s!=null){s.h.removeCallbacks(s.stepper);s.h.postDelayed(s.stepper,450);}}
    public static void abortPending(){SnappAccessibilityService s=instance;if(s!=null){s.h.removeCallbacks(s.stepper);s.active=null;s.phase=Phase.SYNC;s.originQueryEntered=false;s.destinationQueryEntered=false;s.originResultSelected=false;s.destinationResultSelected=false;s.originSelectedText="";s.destinationSelectedText="";s.originSelectionScore=0;s.destinationSelectionScore=0;s.originCityMatched=false;s.destinationCityMatched=false;s.originConfirmClicked=false;s.destinationConfirmClicked=false;s.originConfirmedInSnapp=false;s.destinationConfirmedInSnapp=false;s.requestButtonClicked=false;s.reconciliationAttempts=0;s.unknownCycles=0;}}

    @Override public void onAccessibilityEvent(AccessibilityEvent e){
        if(e==null||e.getPackageName()==null)return;
        String pkg=e.getPackageName().toString();if(!pkg.startsWith("cab.snapp.passenger"))return;
        AgentCommand p=SnappBridge.getPending();if(p==null)return;
        if(active==null||!active.sessionId.equals(p.sessionId))resetFor(p);
        h.removeCallbacks(stepper);h.postDelayed(stepper,220);
    }

    private void resetFor(AgentCommand p){active=p;phase=Phase.SYNC;startedAt=System.currentTimeMillis();phaseStartedAt=startedAt;unknownCycles=0;requestAttempts=0;backAttempts=0;reconciliationAttempts=0;originQueryEntered=false;destinationQueryEntered=false;originResultSelected=false;destinationResultSelected=false;originConfirmClicked=false;destinationConfirmClicked=false;originConfirmedInSnapp=false;destinationConfirmedInSnapp=false;requestButtonClicked=false;originSelectedText="";destinationSelectedText="";originSelectionScore=0;destinationSelectionScore=0;originCityMatched=false;destinationCityMatched=false;lastScreen=null;}
    private void move(Phase p){phase=p;phaseStartedAt=System.currentTimeMillis();unknownCycles=0;}
    private final Runnable stepper=this::process;

    private void process(){
        AgentCommand p=SnappBridge.getPending();if(p==null){active=null;return;}
        if(active==null||!active.sessionId.equals(p.sessionId))resetFor(p);
        if(System.currentTimeMillis()-startedAt>105000){fail("نتونستم Snapp رو در زمان مناسب به وضعیت قابل اطمینان برسونم؛ عملیات رو متوقف کردم.");return;}
        AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null){retry(500);return;}
        lastSnapshot=snapshot(root);Screen screen=detect(root);
        if(screen!=lastScreen){lastScreen=screen;SnappBridge.debug("TECH: screen="+screenLabel(screen)+" phase="+phase.name());SnappBridge.debug("TECH: screenSnapshot="+lastSnapshot);}
        boolean consumed=false;
        if(active.type==AgentCommand.Type.REQUEST_RIDE)consumed=ride(root,screen);
        else if(active.type==AgentCommand.Type.CANCEL_RIDE)consumed=cancel(root);
        else if(active.type==AgentCommand.Type.PAY_WALLET)consumed=pay(root);
        if(!consumed)retry(600);
    }

    private boolean ride(AccessibilityNodeInfo root,Screen screen){
        observeMilestones(screen);

        // SEARCHING_DRIVER / ACTIVE_RIDE is only accepted as success after Soroush itself submitted
        // the request. Before submission, strong active-ride evidence is a safety stop.
        if(screen==Screen.SEARCHING_DRIVER||screen==Screen.ACTIVE_RIDE){
            if(phase==Phase.VERIFY_REQUEST&&requestButtonClicked){
                SnappBridge.event(SnappEvent.RIDE_REQUEST_CONFIRMED,"درخواست سفر در Snapp ثبت شد.");finish();return true;
            }
            SnappBridge.debug("TECH: active-ride evidence before request submission; phase="+phase+" snapshot="+lastSnapshot);
            fail("Snapp نشانه‌های قوی یک درخواست یا سفر فعال رو نشون می‌ده. برای جلوگیری از درخواست دوم ادامه ندادم.");return true;
        }

        if(reconcileRidePhase(root,screen))return true;

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

    /**
     * Reconcile remembered execution phase against the screen Snapp is actually showing. A screen
     * transition can never silently authorize the next step: we require evidence that the preceding
     * confirmation was clicked by this session.
     */
    private boolean reconcileRidePhase(AccessibilityNodeInfo root,Screen screen){
        if((phase==Phase.SEARCH_ORIGIN||phase==Phase.CONFIRM_ORIGIN) &&
           (screen==Screen.DEST_PICKER||screen==Screen.DEST_CONFIRM||screen==Screen.ROUTE_READY)){
            if(originConfirmedInSnapp||originResultSelected){
                originConfirmedInSnapp=true;
                SnappBridge.debug("TECH: reconcile origin->destination after session-owned origin selection");
                move(Phase.SEARCH_DESTINATION);
                return screen==Screen.ROUTE_READY?recoverDestinationFromRoute(root):searchLocation(root,screen,false);
            }
            // This is normally a stale Snapp destination screen. In 2.1.3 tryEditOrigin() plus the
            // address-card fallback bounced ORIGIN_CONFIRM <-> DEST_CONFIRM forever. Back once to the
            // origin map and use only strict origin search controls there.
            SnappBridge.debug("TECH: STALE_DESTINATION_WHILE_SETTING_ORIGIN; returning to origin map once");
            if(reconciliationAttempts++>3){fail("Snapp روی مرحله مقصد قبلی مونده و مبدأ جدید هنوز تنظیم نشده. بازیابی امن مبدأ انجام نشد.");return true;}
            if(backAttempts++<4){performGlobalAction(GLOBAL_ACTION_BACK);move(Phase.SEARCH_ORIGIN);retry(900);return true;}
            fail("نتونستم از مرحله مقصد قبلی به انتخاب مبدأ برگردم.");return true;
        }

        if((phase==Phase.SEARCH_DESTINATION||phase==Phase.CONFIRM_DESTINATION) && screen==Screen.ROUTE_READY){
            if(destinationConfirmedInSnapp){move(Phase.REQUEST);return requestRide(root,screen);}
            SnappBridge.debug("TECH: route-ready without verified destination; recovering destination");
            return recoverDestinationFromRoute(root);
        }

        if((phase==Phase.SEARCH_DESTINATION||phase==Phase.CONFIRM_DESTINATION) &&
           (screen==Screen.ORIGIN_PICKER||screen==Screen.ORIGIN_CONFIRM)){
            SnappBridge.debug("TECH: destination phase regressed to origin screen; re-establishing origin safely");
            if(screen==Screen.ORIGIN_CONFIRM&&originResultSelected&&confirmationCompatible(root,expectedOrigin(),originSelectedText,active.originCity,originSelectionScore,originCityMatched)){
                move(Phase.CONFIRM_ORIGIN);return confirmOrigin(root,screen);
            }
            resetOriginSearchEvidence();move(Phase.SEARCH_ORIGIN);return searchLocation(root,screen,true);
        }

        if((phase==Phase.REQUEST||phase==Phase.VERIFY_REQUEST) && !destinationConfirmedInSnapp){
            if(screen==Screen.DEST_CONFIRM){move(Phase.CONFIRM_DESTINATION);return confirmDestination(root,screen);}
            if(screen==Screen.DEST_PICKER){move(Phase.SEARCH_DESTINATION);return searchLocation(root,screen,false);}
            if(screen==Screen.ROUTE_READY)return recoverDestinationFromRoute(root);
        }
        return false;
    }

    private void observeMilestones(Screen screen){
        // Snapp versions differ: some show a separate confirm map, others advance immediately after
        // a concrete search result is selected. A result selected by THIS session is sufficient proof
        // when Snapp advances to the next role; a stale screen transition is not.
        if((originConfirmClicked||originResultSelected) && (screen==Screen.DEST_PICKER||screen==Screen.DEST_CONFIRM||screen==Screen.ROUTE_READY)){
            if(!originConfirmedInSnapp)SnappBridge.debug("TECH: origin transition verified; source="+(originConfirmClicked?"confirm-click":"result-selection")+" screen="+screen);
            originConfirmedInSnapp=true;
        }
        if((destinationConfirmClicked||destinationResultSelected) && screen==Screen.ROUTE_READY){
            if(!destinationConfirmedInSnapp)SnappBridge.debug("TECH: destination transition verified; source="+(destinationConfirmClicked?"confirm-click":"result-selection"));
            destinationConfirmedInSnapp=true;
        }
    }

    private boolean recoverDestinationFromRoute(AccessibilityNodeInfo root){
        if(destinationConfirmedInSnapp){move(Phase.REQUEST);return requestRide(root,Screen.ROUTE_READY);}
        if(reconciliationAttempts++>4){fail("صفحه مسیر باز شد، اما تأیید مقصد این درخواست قابل اثبات نیست. برای جلوگیری از مقصد اشتباه متوقف شدم.");return true;}
        if(tryEditDestination(root)){resetDestinationSearchEvidence();move(Phase.SEARCH_DESTINATION);retry(750);return true;}
        if(backAttempts++<2){performGlobalAction(GLOBAL_ACTION_BACK);resetDestinationSearchEvidence();move(Phase.SEARCH_DESTINATION);retry(800);return true;}
        fail("صفحه مسیر باز شد، اما نتونستم با اطمینان مقصد رو دوباره بررسی کنم.");return true;
    }

    private void resetOriginSearchEvidence(){originQueryEntered=false;originResultSelected=false;originConfirmClicked=false;originConfirmedInSnapp=false;originSelectedText="";originSelectionScore=0;originCityMatched=false;}
    private void resetDestinationSearchEvidence(){destinationQueryEntered=false;destinationResultSelected=false;destinationConfirmClicked=false;destinationConfirmedInSnapp=false;destinationSelectedText="";destinationSelectionScore=0;destinationCityMatched=false;}
    private String expectedOrigin(){return active.originExpected==null||active.originExpected.trim().isEmpty()?active.origin:active.originExpected;}
    private String expectedDestination(){return active.destinationExpected==null||active.destinationExpected.trim().isEmpty()?active.destination:active.destinationExpected;}

    private boolean syncToOrigin(AccessibilityNodeInfo root,Screen screen){
        SnappBridge.debug("در حال همگام‌سازی Snapp با درخواست جدید…");
        if(screen==Screen.SUPER_HOME){AccessibilityNodeInfo tile=findExactAny(root,"اسنپ","اسنپ خودرو","تاکسی اینترنتی");if(tile!=null&&click(tile)){retry(850);return true;}return false;}
        if(screen==Screen.ORIGIN_PICKER){move(Phase.SEARCH_ORIGIN);return searchLocation(root,screen,true);}
        if(screen==Screen.ORIGIN_CONFIRM){
            if(active.usesCurrentOrigin()){move(Phase.CONFIRM_ORIGIN);return confirmOrigin(root,screen);}
            move(Phase.SEARCH_ORIGIN);return searchLocation(root,screen,true);
        }
        if(screen==Screen.DEST_PICKER||screen==Screen.DEST_CONFIRM){
            // Destination map belongs to an old/incomplete flow. Back is deterministic here and,
            // unlike clicking generic cards, cannot silently accept the current marker.
            if(backAttempts++<4){performGlobalAction(GLOBAL_ACTION_BACK);retry(850);return true;}
            fail("Snapp روی مقصد قبلی مونده و نتونستم به صفحه مبدأ برگردم.");return true;
        }
        if(screen==Screen.ROUTE_READY){
            if(tryEditOrigin(root)){move(Phase.SEARCH_ORIGIN);retry(750);return true;}
            if(backAttempts++<3){performGlobalAction(GLOBAL_ACTION_BACK);retry(850);return true;}
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

        // A confirmation map can still expose an editable search field. Prefer it directly. If no
        // editable is exposed, open the role-specific search control only; never click a generic
        // address card because on current Snapp builds that can CONFIRM the current marker.
        if(origin&&screen==Screen.ORIGIN_CONFIRM&&!originResultSelected){
            AccessibilityNodeInfo directEdit=bestEditable(root,"مبدا","مبدأ","جستجو","از کجا");
            if(directEdit==null){
                if(openLocationEditor(root,true)){retry(700);return true;}
                if(System.currentTimeMillis()-phaseStartedAt>9000){SnappBridge.debug("TECH: ORIGIN_SEARCH_CONTROL_NOT_FOUND snapshot="+lastSnapshot);fail("صفحه مبدأ بازه، اما کنترل مطمئنی برای جستجوی مبدأ پیدا نکردم. هیچ نقطه‌ای تأیید نشد.");return true;}
                return false;
            }
        }
        if(!origin&&screen==Screen.DEST_CONFIRM&&!destinationResultSelected){
            AccessibilityNodeInfo directEdit=bestEditable(root,"مقصد","جستجو","کجا");
            if(directEdit==null){
                if(openLocationEditor(root,false)){retry(700);return true;}
                if(System.currentTimeMillis()-phaseStartedAt>9000){SnappBridge.debug("TECH: DEST_SEARCH_CONTROL_NOT_FOUND snapshot="+lastSnapshot);fail("صفحه مقصد بازه، اما کنترل مطمئنی برای جستجوی مقصد پیدا نکردم. هیچ نقطه‌ای تأیید نشد.");return true;}
                return false;
            }
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
                    if(origin){originResultSelected=true;originSelectedText=chosen;originSelectionScore=match.score;originCityMatched=match.cityStatus==CityStatus.MATCH;}else{destinationResultSelected=true;destinationSelectedText=chosen;destinationSelectionScore=match.score;destinationCityMatched=match.cityStatus==CityStatus.MATCH;}
                    SnappBridge.debug("TECH: "+(origin?"origin":"destination")+" result score="+match.score+" margin="+match.margin+" localMatches="+match.localMatches+" city="+match.cityStatus+" text="+chosen);
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
            if(screen==Screen.DEST_PICKER||screen==Screen.DEST_CONFIRM||screen==Screen.ROUTE_READY){
                if(originConfirmedInSnapp){move(Phase.SEARCH_DESTINATION);return screen==Screen.ROUTE_READY?recoverDestinationFromRoute(root):searchLocation(root,screen,false);}
                SnappBridge.debug("TECH: origin confirm phase observed later screen without verified origin transition");
                return reconcileRidePhase(root,screen);
            }
            return unknownOrRecover(screen,"منتظر صفحه تأیید مبدأ هستم.");
        }
        if(!active.usesCurrentOrigin()&&!originResultSelected){
            if(openLocationEditor(root,true)){move(Phase.SEARCH_ORIGIN);retry(650);return true;}
            if(System.currentTimeMillis()-phaseStartedAt>7000){fail("مبدأ در Snapp قابل تأیید نبود چون انتخاب نتیجه جستجو قابل اثبات نیست.");return true;}
            return false;
        }
        String expected=active.originExpected==null||active.originExpected.trim().isEmpty()?active.origin:active.originExpected;
        if(!active.usesCurrentOrigin()&&!confirmationCompatible(root,expected,originSelectedText,active.originCity,originSelectionScore,originCityMatched)){
            fail("صفحه تأیید مبدأ با نتیجه‌ای که انتخاب کردم سازگاری کافی نداره؛ برای جلوگیری از تأیید نقطه اشتباه متوقف شدم.");return true;
        }
        AccessibilityNodeInfo c=findAny(root,"تایید مبدا","تأیید مبدا","تایید مبدأ","تأیید مبدأ");
        if(c!=null&&click(c)){originConfirmClicked=true;SnappBridge.debug("TECH: origin confirm clicked; waiting for destination-screen transition before marking verified");SnappBridge.debug("مبدأ انتخاب‌شده در Snapp تأیید شد؛ در حال بررسی انتقال به مقصد…");move(Phase.SEARCH_DESTINATION);retry(850);return true;}
        return false;
    }

    private boolean confirmDestination(AccessibilityNodeInfo root,Screen screen){
        if(screen==Screen.ROUTE_READY){
            if(destinationConfirmedInSnapp){move(Phase.REQUEST);return requestRide(root,screen);}
            return recoverDestinationFromRoute(root);
        }
        if(screen!=Screen.DEST_CONFIRM)return unknownOrRecover(screen,"منتظر صفحه تأیید مقصد هستم.");
        if(!destinationResultSelected){
            if(openLocationEditor(root,false)){move(Phase.SEARCH_DESTINATION);retry(650);return true;}
            if(System.currentTimeMillis()-phaseStartedAt>7000){fail("مقصد در Snapp قابل تأیید نبود چون انتخاب نتیجه جستجو قابل اثبات نیست.");return true;}
            return false;
        }
        String expected=active.destinationExpected==null||active.destinationExpected.trim().isEmpty()?active.destination:active.destinationExpected;
        if(!confirmationCompatible(root,expected,destinationSelectedText,active.destinationCity,destinationSelectionScore,destinationCityMatched)){
            fail("صفحه تأیید مقصد با نتیجه‌ای که انتخاب کردم سازگاری کافی نداره؛ برای جلوگیری از تأیید نقطه اشتباه متوقف شدم.");return true;
        }
        AccessibilityNodeInfo c=findAny(root,"تایید مقصد","تأیید مقصد");
        if(c!=null&&click(c)){destinationConfirmClicked=true;SnappBridge.debug("TECH: destination confirm clicked; waiting for ROUTE_READY before marking verified");SnappBridge.debug("مقصد انتخاب‌شده در Snapp تأیید شد؛ در حال بررسی صفحه درخواست نهایی…");move(Phase.REQUEST);retry(950);return true;}
        return false;
    }

    private boolean requestRide(AccessibilityNodeInfo root,Screen screen){
        if(screen==Screen.DEST_CONFIRM){move(Phase.CONFIRM_DESTINATION);return confirmDestination(root,screen);}
        if(screen!=Screen.ROUTE_READY)return unknownOrRecover(screen,"منتظر صفحه نهایی انتخاب سرویس هستم.");
        if(!originConfirmedInSnapp){SnappBridge.debug("TECH: request blocked: origin not verified in Snapp");move(Phase.SEARCH_ORIGIN);return syncToOrigin(root,screen);}
        if(!destinationConfirmedInSnapp){SnappBridge.debug("TECH: request blocked: destination not verified in Snapp");return recoverDestinationFromRoute(root);}
        AccessibilityNodeInfo req=findAny(root,"درخواست اسنپ","درخواست خودرو","درخواست سفر");
        if(req!=null&&click(req)){requestAttempts++;requestButtonClicked=true;move(Phase.VERIFY_REQUEST);SnappBridge.debug("TECH: request button clicked after verified origin+destination");SnappBridge.debug("دکمه درخواست سفر زده شد؛ منتظر تأیید واقعی Snapp هستم.");retry(1400);return true;}
        return false;
    }

    private boolean verifyRequest(AccessibilityNodeInfo root,Screen screen){
        if(requestButtonClicked&&(screen==Screen.SEARCHING_DRIVER||screen==Screen.ACTIVE_RIDE)){SnappBridge.event(SnappEvent.RIDE_REQUEST_CONFIRMED,"درخواست سفر در Snapp ثبت شد.");finish();return true;}
        if(screen==Screen.ROUTE_READY&&requestAttempts<2){move(Phase.REQUEST);return requestRide(root,screen);}
        if(System.currentTimeMillis()-phaseStartedAt>9000){fail("دکمه درخواست زده شد، اما Snapp ثبت نهایی سفر رو تأیید نکرد. برای جلوگیری از وضعیت مبهم ادامه ندادم.");return true;}
        return false;
    }

    private boolean openLocationEditor(AccessibilityNodeInfo root,boolean origin){
        // Strict navigation only. 2.1.3 used a bottom-address-card fallback; on the current Snapp UI
        // tapping that card can advance from ORIGIN_CONFIRM to DEST_CONFIRM and therefore skips origin
        // search entirely. We now click only controls whose own text/hint identifies the intended role.
        String[] roleControls=origin
                ?new String[]{"تغییر مبدأ","تغییر مبدا","انتخاب مبدأ","انتخاب مبدا","ویرایش مبدأ","ویرایش مبدا","جستجوی مبدأ","جستجوی مبدا","مبدأ","مبدا","از کجا"}
                :new String[]{"تغییر مقصد","انتخاب مقصد","ویرایش مقصد","جستجوی مقصد","مقصد","کجا می روید","کجا می‌روید"};
        AccessibilityNodeInfo direct=findSafeRoleControl(root,origin,roleControls);
        if(direct!=null&&click(direct)){SnappBridge.debug("TECH: opened "+(origin?"origin":"destination")+" editor via role control="+nodeText(direct));return true;}
        AccessibilityNodeInfo search=findRoleAwareSearchControl(root,origin);
        if(search!=null&&click(search)){SnappBridge.debug("TECH: opened "+(origin?"origin":"destination")+" editor via search control="+nodeText(search));return true;}
        return false;
    }

    private AccessibilityNodeInfo findSafeRoleControl(AccessibilityNodeInfo root,boolean origin,String... needles){
        for(AccessibilityNodeInfo n:flatten(root)){
            String t=PersianText.norm(nodeText(n));if(t.isEmpty())continue;
            if(t.contains("تایید")||t.contains("تأیید"))continue;
            boolean match=false;for(String needle:needles){String q=PersianText.norm(needle);if(t.equals(q)||t.contains(q)){match=true;break;}}
            if(!match)continue;AccessibilityNodeInfo c=clickableAncestor(n);if(c==null)continue;
            String ct=PersianText.norm(aggregateText(c));
            if(ct.contains("تایید مبدا")||ct.contains("تأیید مبدأ")||ct.contains("تایید مقصد")||ct.contains("تأیید مقصد"))continue;
            if(ct.length()>220)continue; // avoid a large container/list ancestor
            return c;
        }
        return null;
    }

    private AccessibilityNodeInfo findRoleAwareSearchControl(AccessibilityNodeInfo root,boolean origin){
        for(AccessibilityNodeInfo n:flatten(root)){
            String t=PersianText.norm(nodeText(n));if(!(t.contains("جستجو")||t.contains("search")))continue;
            if(t.contains("تایید")||t.contains("تأیید"))continue;
            String parent=t;AccessibilityNodeInfo p=n.getParent();if(p!=null)parent+=" "+PersianText.norm(nodeText(p));
            boolean role=origin?(parent.contains("مبدا")||parent.contains("مبدأ")||parent.contains("از کجا")):(parent.contains("مقصد")||parent.contains("کجا"));
            if(!role)continue;AccessibilityNodeInfo c=clickableAncestor(n);if(c!=null)return c;
        }
        return null;
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

    private boolean tryEditDestination(AccessibilityNodeInfo root){
        AccessibilityNodeInfo n=findExactAny(root,"مقصد");
        if(n!=null&&click(n))return true;
        n=findAny(root,"ویرایش مقصد","تغییر مقصد","انتخاب مقصد");
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
        // Do not classify a page as an active ride from one weak phrase such as "لغو سفر".
        // Route/transition screens can expose similar text. Require at least two independent driver/trip signals.
        int activeSignals=0;
        if(has(root,"تماس با راننده"))activeSignals++;
        if(has(root,"اطلاعات راننده","مشخصات راننده"))activeSignals++;
        if(has(root,"راننده رسید","راننده در راه","راننده به مبدأ"))activeSignals++;
        if(has(root,"پلاک خودرو","شماره پلاک","مدل خودرو"))activeSignals++;
        if(has(root,"اشتراک سفر","امنیت سفر","پشتیبانی سفر"))activeSignals++;
        if(activeSignals>=2)return Screen.ACTIVE_RIDE;
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
        AccessibilityNodeInfo best=null;String bestText="";int bestScore=-999,second=-999,bestLocal=0;CityStatus bestCity=CityStatus.UNKNOWN;
        Set<AccessibilityNodeInfo> seen=Collections.newSetFromMap(new IdentityHashMap<AccessibilityNodeInfo,Boolean>());
        int expectedTokens=Math.max(PersianText.addressTokens(expected).size(),PersianText.addressTokens(query).size());
        for(AccessibilityNodeInfo x:flatten(root)){
            if(x.isEditable())continue;AccessibilityNodeInfo clickable=clickableAncestor(x);if(clickable==null||seen.contains(clickable))continue;seen.add(clickable);
            String t=aggregateText(clickable);if(t.isEmpty()||isControlLike(t)||t.length()>360)continue;
            CityStatus cityStatus=cityStatus(expectedCity,t);
            if(cityStatus==CityStatus.CONFLICT){SnappBridge.debug("TECH: CITY_HARD_REJECT expected="+expectedCity+" candidate="+t);continue;}
            int localMatches=countLocalMatches(expected,t);
            int s=Math.max(PersianText.addressSimilarity(expected,t),PersianText.addressSimilarity(query,t));
            int candidateTokens=PersianText.addressTokens(t).size();if(candidateTokens>=2)s+=6;if(localMatches>=2)s+=10;if(localMatches>=3)s+=8;
            if(cityStatus==CityStatus.MATCH)s+=34;
            // If the expected city is known but the result does not expose a city, demand multiple
            // local address tokens. This keeps a similarly named Tehran result from winning.
            if(!cityCore(expectedCity).isEmpty()&&cityStatus==CityStatus.UNKNOWN&&localMatches<2)continue;
            if(s>bestScore){second=bestScore;bestScore=s;best=clickable;bestText=t;bestLocal=localMatches;bestCity=cityStatus;}else if(s>second)second=s;
        }
        int min=expectedTokens<=1?68:50;if(bestCity==CityStatus.UNKNOWN)min=Math.max(min,66);
        if(best==null||bestScore<min)return null;
        int margin=second<=-900?99:bestScore-second;if(margin<9&&bestScore<100)return null;
        return new AddressMatch(best,bestText,bestScore,margin,bestLocal,bestCity);
    }

    private int countLocalMatches(String expected,String candidate){
        List<String> e=PersianText.addressTokens(expected),c=PersianText.addressTokens(candidate);int matches=0;
        for(String x:e){String nx=PersianText.norm(x);if(nx.equals("همدان")||nx.equals("تهران")||nx.equals("ایران")||nx.equals("مرکزی"))continue;double best=0;for(String y:c)best=Math.max(best,PersianText.tokenSimilarity(x,y));if(best>=.82)matches++;}
        return matches;
    }

    private boolean confirmationCompatible(AccessibilityNodeInfo root,String expected,String selected,String city,int selectionScore,boolean selectedCityMatched){
        // The concrete search result clicked by this session is the primary evidence. Snapp's map
        // confirmation screen also contains unrelated saved/suggested addresses, so choosing the
        // longest bottom card (2.1.3) could compare against the wrong item.
        AccessibilityNodeInfo panel=findConfirmationPanel(root,expected,selected,city);
        String visible=panel==null?"":aggregateText(panel);
        if(!visible.isEmpty()){
            CityStatus cs=cityStatus(city,visible);
            if(cs==CityStatus.CONFLICT){SnappBridge.debug("TECH: CONFIRM_CITY_MISMATCH expectedCity="+city+" visible="+visible);return false;}
            int best=Math.max(PersianText.addressSimilarity(expected,visible),PersianText.addressSimilarity(selected,visible));
            if(cs==CityStatus.MATCH)best+=30;
            SnappBridge.debug("TECH: confirmation verify city="+cs+" textScore="+best+" selectionScore="+selectionScore+" visible="+visible);
            if(best>=24)return true;
        }
        // If the confirmation card is not exposed reliably, accept only a strong session-owned
        // result; explicit city match lowers the required textual threshold.
        int threshold=selectedCityMatched?58:82;
        SnappBridge.debug("TECH: confirmation panel unavailable/weak; trusting selected result only if score >= "+threshold+" actual="+selectionScore+" cityMatched="+selectedCityMatched);
        return selectionScore>=threshold;
    }

    private AccessibilityNodeInfo findConfirmationPanel(AccessibilityNodeInfo root,String expected,String selected,String city){
        AccessibilityNodeInfo best=null;int bestScore=-999;Set<AccessibilityNodeInfo> seen=Collections.newSetFromMap(new IdentityHashMap<AccessibilityNodeInfo,Boolean>());
        for(AccessibilityNodeInfo n:flatten(root)){
            AccessibilityNodeInfo c=clickableAncestor(n);if(c==null||seen.contains(c))continue;seen.add(c);String t=aggregateText(c);
            if(t.length()<4||t.length()>320||isControlLike(t))continue;CityStatus cs=cityStatus(city,t);if(cs==CityStatus.CONFLICT)continue;
            int score=Math.max(PersianText.addressSimilarity(expected,t),PersianText.addressSimilarity(selected,t));if(cs==CityStatus.MATCH)score+=30;
            score+=Math.min(20,countLocalMatches(expected,t)*6);if(score>bestScore){bestScore=score;best=c;}
        }
        return bestScore>=18?best:null;
    }

    private enum CityStatus { MATCH, CONFLICT, UNKNOWN }
    private static final Set<String> KNOWN_CITY_NAMES=new HashSet<>(Arrays.asList(
            "تهران","همدان","مشهد","اصفهان","شیراز","تبریز","کرج","قم","اهواز","کرمانشاه","ارومیه","رشت","کرمان",
            "یزد","اردبیل","بندرعباس","اراک","زنجان","سنندج","قزوین","گرگان","ساری","خرم آباد","بوشهر","بیرجند",
            "ایلام","یاسوج","شهرکرد","سمنان","ملایر","نهاوند","تویسرکان"));

    private CityStatus cityStatus(String expectedCity,String candidate){
        String expected=cityCore(expectedCity);if(expected.isEmpty())return CityStatus.UNKNOWN;
        String c=PersianText.norm(candidate);if(c.isEmpty())return CityStatus.UNKNOWN;
        if(containsWholeToken(c,expected))return CityStatus.MATCH;
        // Explicit administrative city/province labels are authoritative evidence of a mismatch.
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:شهر|شهرستان|استان)\\s+([آ-ی]+(?:\\s+[آ-ی]+)?)").matcher(c);
        while(m.find()){String x=cityCore(m.group(1));if(!x.isEmpty()){if(x.equals(expected))return CityStatus.MATCH;if(!genericAdminWord(x))return CityStatus.CONFLICT;}}
        for(String known:KNOWN_CITY_NAMES){String k=PersianText.norm(known);if(!k.equals(expected)&&containsWholeToken(c,k))return CityStatus.CONFLICT;}
        return CityStatus.UNKNOWN;
    }
    private String cityCore(String city){
        String n=PersianText.norm(city);if(n.isEmpty())return "";
        for(String part:n.split(" ")){
            if(part.isEmpty()||part.equals("شهر")||part.equals("شهرستان")||part.equals("استان")||part.equals("بخش")||part.equals("مرکزی")||part.equals("ایران"))continue;
            return part;
        }
        return "";
    }
    private boolean genericAdminWord(String x){return x.equals("مرکزی")||x.equals("بخش")||x.equals("شهر")||x.equals("استان")||x.equals("شهرستان");}
    private boolean containsWholeToken(String text,String token){return (" "+PersianText.norm(text)+" ").contains(" "+PersianText.norm(token)+" ");}

    private static final class AddressMatch{final AccessibilityNodeInfo node;final String text;final int score,margin,localMatches;final CityStatus cityStatus;AddressMatch(AccessibilityNodeInfo n,String t,int s,int m,int l,CityStatus c){node=n;text=t;score=s;margin=m;localMatches=l;cityStatus=c;}}

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
    private void finish(){String id=active==null?null:active.sessionId;SnappBridge.clear(id);active=null;phase=Phase.SYNC;unknownCycles=0;originQueryEntered=false;destinationQueryEntered=false;originResultSelected=false;destinationResultSelected=false;originConfirmClicked=false;destinationConfirmClicked=false;originConfirmedInSnapp=false;destinationConfirmedInSnapp=false;requestButtonClicked=false;reconciliationAttempts=0;originSelectedText="";destinationSelectedText="";originSelectionScore=0;destinationSelectionScore=0;originCityMatched=false;destinationCityMatched=false;}
    private void fail(String msg){SnappBridge.event(SnappEvent.SAFE_FAILURE,msg);finish();}
    @Override public void onInterrupt(){}
    @Override public void onDestroy(){if(instance==this)instance=null;super.onDestroy();}
}
