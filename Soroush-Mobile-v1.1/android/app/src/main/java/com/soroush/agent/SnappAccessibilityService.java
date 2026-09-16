package com.soroush.agent;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;

public class SnappAccessibilityService extends AccessibilityService {
    private final Handler h = new Handler(Looper.getMainLooper());
    private AgentCommand active;
    private int stage = 0;
    private boolean originConfirmed = false;
    private long startedAt = 0;
    private String lastSnapshot = "";

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        if (!pkg.startsWith("cab.snapp.passenger")) return;
        AgentCommand p = SnappBridge.getPending();
        if (p == null) return;
        if (active != p) { active = p; stage = 0; originConfirmed = false; startedAt = System.currentTimeMillis(); }
        h.removeCallbacks(stepper);
        h.postDelayed(stepper, 350);
    }

    private final Runnable stepper = new Runnable() { @Override public void run() { process(); } };

    private void process() {
        if (active == null) return;
        if (System.currentTimeMillis() - startedAt > 45000) {
            fail("برای جلوگیری از کلیک اشتباه، عملیات Snapp متوقف شد؛ رابط مورد انتظار پیدا نشد.");
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) { retry(); return; }
        lastSnapshot = snapshot(root);
        boolean progressed;
        switch (active.type) {
            case REQUEST_RIDE: progressed = requestRide(root); break;
            case CANCEL_RIDE: progressed = cancelRide(root); break;
            case PAY_WALLET: progressed = payWallet(root); break;
            default: progressed = false;
        }
        if (!progressed) retry();
    }

    private boolean requestRide(AccessibilityNodeInfo root) {
        if (stage == 0) {
            // Snapp may first open on the Super App home screen. Enter the ride service
            // explicitly before looking for the origin/destination flow. We only match
            // an exact service label to avoid clicking the global search bar or other
            // text containing the word Snapp.
            AccessibilityNodeInfo rideService = findExactAny(root, "اسنپ", "اسنپ خودرو", "تاکسی اینترنتی");
            if (rideService != null && click(rideService)) {
                SnappBridge.status("سرویس اسنپ باز شد؛ در حال آماده‌سازی سفر…");
                retrySlow(); return true;
            }

            // Snapp often opens on the origin confirmation screen next.
            AccessibilityNodeInfo confirmOrigin = findAny(root, "تایید مبدا", "تأیید مبدا", "تایید مبدأ", "تأیید مبدأ", "ثبت مبدا", "ثبت مبدأ");
            if (confirmOrigin != null && click(confirmOrigin)) {
                originConfirmed=true;
                SnappBridge.status("مبدأ فعلی در Snapp تأیید شد؛ در حال رفتن به مقصد…");
                retrySlow(); return true;
            }
            AccessibilityNodeInfo n = findAny(root, "انتخاب مقصد", "مقصد کجاست", "مقصد", "کجا می روید", "کجا می‌روید", "کجا میرید", "کجا می‌رید", "کجا؟");
            if (n != null && click(n)) { stage=1; SnappBridge.status("صفحه انتخاب مقصد باز شد."); retryFast(); return true; }
            AccessibilityNodeInfo edit = firstEditable(root);
            if (edit != null) { stage=1; retryFast(); return true; }
            return false;
        }
        if (stage == 1) {
            AccessibilityNodeInfo edit = firstEditable(root);
            if (edit == null) return false;
            if (setText(edit, active.destination)) { stage=2; SnappBridge.status("مقصد در Snapp وارد شد؛ در حال تطبیق نتیجه…"); retrySlow(); return true; }
            return false;
        }
        if (stage == 2) {
            AccessibilityNodeInfo match = findBestAddressMatch(root, active.destination);
            if (match != null && click(match)) { stage=3; SnappBridge.status("مقصد انتخاب شد؛ در حال آماده‌سازی درخواست…"); retrySlow(); return true; }
            return false;
        }
        if (stage == 3) {
            AccessibilityNodeInfo request = findAny(root, "درخواست اسنپ", "درخواست خودرو", "درخواست سفر", "تایید و درخواست", "تأیید و درخواست");
            if (request != null && click(request)) {
                stage=4; SnappBridge.status("فرمان درخواست خودرو به Snapp ارسال شد. وضعیت سفر را در Snapp بررسی کن.");
                finish(); return true;
            }
            // Some versions show service cards first. Do not click a generic price/card blindly.
            return false;
        }
        return true;
    }

    private boolean cancelRide(AccessibilityNodeInfo root) {
        if (stage == 0) {
            AccessibilityNodeInfo n = findAny(root, "لغو سفر", "لغو درخواست", "کنسل سفر", "انصراف از سفر");
            if (n != null && click(n)) { stage=1; SnappBridge.status("صفحه لغو سفر باز شد."); retrySlow(); return true; }
            // If trip screen has a menu/more button, avoid blind clicking; wait for explicit cancel text.
            return false;
        }
        if (stage == 1) {
            AccessibilityNodeInfo reason = findAny(root, "تغییر برنامه", "دیگر نیازی ندارم", "سایر موارد", "سایر");
            if (reason != null) { click(reason); stage=2; retryFast(); return true; }
            AccessibilityNodeInfo confirm = findAny(root, "تایید لغو", "تأیید لغو", "لغو شود", "بله، لغو کن");
            if (confirm != null && click(confirm)) { SnappBridge.status("درخواست لغو به Snapp ارسال شد."); finish(); return true; }
            return false;
        }
        if (stage == 2) {
            AccessibilityNodeInfo confirm = findAny(root, "تایید لغو", "تأیید لغو", "لغو سفر", "بله، لغو کن");
            if (confirm != null && click(confirm)) { SnappBridge.status("درخواست لغو به Snapp ارسال شد."); finish(); return true; }
            return false;
        }
        return false;
    }

    private boolean payWallet(AccessibilityNodeInfo root) {
        if (stage == 0) {
            AccessibilityNodeInfo pay = findAny(root, "پرداخت", "پرداخت هزینه", "روش پرداخت", "پرداخت سفر");
            if (pay != null && click(pay)) { stage=1; SnappBridge.status("صفحه پرداخت باز شد."); retrySlow(); return true; }
            return false;
        }
        if (stage == 1) {
            AccessibilityNodeInfo wallet = findAny(root, "کیف پول", "کیف‌پول", "اعتبار اسنپ", "موجودی کیف پول");
            if (wallet != null && click(wallet)) { stage=2; SnappBridge.status("کیف پول Snapp انتخاب شد."); retryFast(); return true; }
            return false;
        }
        if (stage == 2) {
            AccessibilityNodeInfo confirm = findAny(root, "پرداخت", "پرداخت کن", "تایید پرداخت", "تأیید پرداخت");
            if (confirm != null && click(confirm)) { SnappBridge.status("فرمان پرداخت از کیف پول به Snapp ارسال شد."); finish(); return true; }
            return false;
        }
        return false;
    }

    private AccessibilityNodeInfo findExactAny(AccessibilityNodeInfo root, String... needles) {
        List<AccessibilityNodeInfo> all = flatten(root);
        for (String needle : needles) {
            String n = PersianText.norm(needle);
            for (AccessibilityNodeInfo x : all) {
                String t = PersianText.norm(nodeText(x));
                if (!t.isEmpty() && t.equals(n)) return x;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findAny(AccessibilityNodeInfo root, String... needles) {
        List<AccessibilityNodeInfo> all = flatten(root);
        for (String needle : needles) {
            String n = PersianText.norm(needle);
            for (AccessibilityNodeInfo x : all) {
                String t = nodeText(x);
                if (!t.isEmpty() && PersianText.norm(t).contains(n)) return x;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findBestAddressMatch(AccessibilityNodeInfo root, String address) {
        List<String> tokens = new ArrayList<>();
        for (String t : PersianText.norm(address).split(" ")) if (t.length() >= 3) tokens.add(t);
        AccessibilityNodeInfo best=null; int bestScore=0;
        for (AccessibilityNodeInfo x : flatten(root)) {
            String text = PersianText.norm(nodeText(x));
            if (text.isEmpty()) continue;
            int score=0;
            for (String t: tokens) if (text.contains(t)) score++;
            if (score > bestScore && score >= Math.min(2, Math.max(1,tokens.size()))) { best=x; bestScore=score; }
        }
        return best;
    }

    private AccessibilityNodeInfo firstEditable(AccessibilityNodeInfo root) {
        for (AccessibilityNodeInfo n : flatten(root)) if (n.isEditable() || "android.widget.EditText".contentEquals(n.getClassName())) return n;
        return null;
    }

    private boolean setText(AccessibilityNodeInfo n, String text) {
        try {
            Bundle b = new Bundle(); b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
        } catch (Exception e) { return false; }
    }

    private boolean click(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo x=n;
        for (int i=0; i<5 && x!=null; i++, x=x.getParent()) if (x.isClickable() && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private List<AccessibilityNodeInfo> flatten(AccessibilityNodeInfo root) {
        ArrayList<AccessibilityNodeInfo> out = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>(); q.add(root);
        while(!q.isEmpty() && out.size()<500) {
            AccessibilityNodeInfo n=q.remove(); out.add(n);
            for(int i=0;i<n.getChildCount();i++){ AccessibilityNodeInfo c=n.getChild(i); if(c!=null) q.add(c); }
        }
        return out;
    }

    private String nodeText(AccessibilityNodeInfo n) {
        StringBuilder s=new StringBuilder();
        if(n.getText()!=null) s.append(n.getText()).append(' ');
        if(n.getContentDescription()!=null) s.append(n.getContentDescription()).append(' ');
        if(n.getHintText()!=null) s.append(n.getHintText());
        return s.toString().trim();
    }

    private String snapshot(AccessibilityNodeInfo root) {
        StringBuilder s=new StringBuilder(); int c=0;
        for(AccessibilityNodeInfo n: flatten(root)) { String t=nodeText(n); if(!t.isEmpty()){ if(c++>30) break; s.append(t).append(" | "); } }
        return s.toString();
    }

    private void retry(){ h.removeCallbacks(stepper); h.postDelayed(stepper, 700); }
    private void retryFast(){ h.removeCallbacks(stepper); h.postDelayed(stepper, 450); }
    private void retrySlow(){ h.removeCallbacks(stepper); h.postDelayed(stepper, 1000); }
    private void finish(){ SnappBridge.clear(); active=null; stage=0; originConfirmed=false; }
    private void fail(String msg){ SnappBridge.status(msg + "\nصفحه دیده‌شده: " + lastSnapshot); finish(); }
    @Override public void onInterrupt() { }
}
