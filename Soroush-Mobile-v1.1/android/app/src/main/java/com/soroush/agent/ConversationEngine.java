package com.soroush.agent;

import java.util.*;

/**
 * Local semantic/control layer for alpha2.
 *
 * This is deliberately not a cloud LLM. It is a context-aware local parser whose job is to:
 *  - keep control intents (cancel/confirm/reject) reliable even offline,
 *  - understand common Persian paraphrases and light typos,
 *  - avoid turning fuzzy matches into silent actions,
 *  - hand ambiguous content back to the conversation flow for clarification.
 *
 * A remote NLU/LLM can later be plugged in above this class without changing Ride/Map/Snapp execution.
 */
public final class ConversationEngine {
    public IntentResult parse(String raw, ConversationContext ctx){
        String n=PersianText.norm(raw);
        if(n.isEmpty()) return IntentResult.of(IntentResult.Intent.UNKNOWN);

        // Consequential cancellation must be distinguished from abandoning the current conversation.
        if(isRideCancellation(n)) return IntentResult.of(IntentResult.Intent.CANCEL_RIDE);
        if(isGenericCancel(n)) return IntentResult.of(IntentResult.Intent.CANCEL_FLOW);

        if(isShowMap(n)) return IntentResult.of(IntentResult.Intent.SHOW_MAP);
        if(isConfirm(n)) return IntentResult.of(IntentResult.Intent.CONFIRM);
        if(isReject(n)) return IntentResult.of(IntentResult.Intent.REJECT);

        // A complete ride request should beat correction heuristics such as a sentence beginning with "از".
        if(looksLikeRide(n)){
            String[] p=extractFromTo(n);
            if(p!=null) return new IntentResult(IntentResult.Intent.REQUEST_RIDE,p[0],p[1],"",0.97);
        }

        if(ctx!=null && ctx.ride!=null && !ctx.ride.terminal()){
            String changedOrigin=extractOriginCorrection(n);
            if(changedOrigin!=null) return new IntentResult(IntentResult.Intent.CHANGE_ORIGIN,"","",changedOrigin,0.93);
            String changedDestination=extractDestinationCorrection(n);
            if(changedDestination!=null) return new IntentResult(IntentResult.Intent.CHANGE_DESTINATION,"","",changedDestination,0.93);
        }

        boolean edit=isEditIntent(n);
        if(edit) return new IntentResult(IntentResult.Intent.EDIT_PLACE,"","",extractTargetForEdit(n),0.91);

        boolean add=isAddIntent(n) && !looksLikeRide(n);
        if(add) return new IntentResult(IntentResult.Intent.ADD_PLACE,"","",extractTitleForAdd(n),0.91);

        if(looksLikeRide(n)){
            String d=extractDestination(n);
            return new IntentResult(IntentResult.Intent.REQUEST_RIDE,"",d,"",d.isEmpty()?0.80:0.90);
        }

        // Greeting is intentionally checked before pending-answer fallback, so "سلام" never becomes an address.
        if(isGreeting(n)) return IntentResult.of(IntentResult.Intent.GREETING);

        if(ctx!=null && ctx.pending!=ConversationContext.Pending.NONE)
            return new IntentResult(IntentResult.Intent.ANSWER,"","",raw,0.96);

        return IntentResult.of(IntentResult.Intent.UNKNOWN);
    }

    private boolean isGreeting(String n){
        if(PersianText.hasApproxAny(n,"سلام","درود","هی")) return true;
        return PersianText.hasAny(n,"صبح بخیر","شب بخیر","عصر بخیر","روز بخیر","خوبی","حالت چطوره","حالت خوبه");
    }

    private boolean isRideCancellation(String n){
        return PersianText.hasAny(n,
                "سفر رو لغو","سفر را لغو","سفرمو لغو","سفرم رو لغو","درخواست سفر رو لغو",
                "درخواست اسنپ رو لغو","اسنپ رو کنسل","اسنپ را کنسل","ماشین رو لغو","ماشین را لغو",
                "راننده رو لغو","لغو سفر","کنسل سفر");
    }

    private boolean isGenericCancel(String n){
        String x=n.trim();
        if(x.equals("لغو")||x.equals("کنسل")||x.equals("منصرف")||x.equals("بیخیال")||x.equals("بی خیال")||x.equals("ولش کن")||x.equals("نمیخوام")||x.equals("نمی خوام")) return true;
        return PersianText.hasAny(n,
                "بیخیالش","بی خیالش","ولش کن","منصرف شدم","منصرفم","نمیخوامش","نمی خوامش",
                "فعلا نه","فعلاً نه","بس کن","متوقف کن","قطعش کن","ادامه نده","دیگه نمیخوام","دیگه نمی خوام");
    }

    private boolean isConfirm(String n){
        String x=n.trim();
        if(x.equals("بله")||x.equals("آره")||x.equals("اره")||x.equals("اوکی")||x.equals("تایید")||x.equals("تأیید")) return true;
        return PersianText.hasAny(n,"همینه","درسته","همین درسته","تاییدش کن","تأییدش کن","انجام بده","ادامه بده");
    }

    private boolean isReject(String n){
        String x=n.trim();
        if(x.equals("نه")||x.equals("خیر")) return true;
        return PersianText.hasAny(n,"این نیست","اشتباهه","غلطه","نه این","نه اون","درست نیست","این اشتباهه","این رو نمیخوام","این را نمیخوام");
    }

    private boolean isShowMap(String n){
        return PersianText.hasAny(n,"روی نقشه","نقشه رو نشون","نقشه را نشان","ببینم روی نقشه","دیدن روی نقشه","نقشه اش رو ببین","نقشه‌اش رو ببین");
    }

    private boolean isEditIntent(String n){
        return PersianText.hasAny(n,
                "ویرایش","ادیت","تغییر آدرس","تغییر ادرس","آدرسش رو عوض","ادرسش رو عوض",
                "لوکیشنش رو عوض","موقعیتش رو عوض","آدرس اشتباه","ادرس اشتباه","جاشو عوض","جاش رو عوض",
                "درستش کن","اصلاح کن","اصلاحش کن","جابجا کن","جا به جا کن","پینش رو عوض","پین رو عوض");
    }

    private boolean isAddIntent(String n){
        return PersianText.hasAny(n,"آدرس اضافه","ادرس اضافه","موقعیت اضافه","مکان اضافه","آدرس جدید","ادرس جدید","مکان جدید","موقعیت جدید","ذخیره کن","ثبت کن","سیو کن");
    }

    private boolean looksLikeRide(String n){
        int score=0;
        if(PersianText.hasApproxAny(n,"اسنپ","تاکسی")) score+=3;
        if(PersianText.hasApproxAny(n,"ماشین","خودرو")) score+=2;
        if(PersianText.hasAny(n,"بگیر","بگیری","بگیره","لازم دارم","میخوام","می خوام","میخام")) score+=1;
        if(PersianText.hasAny(n,"میخوام برم","می خوام برم","میخام برم","برم ","بریم ","ببر منو","ببرم","سفر")) score+=2;
        return score>=2;
    }

    private String[] extractFromTo(String n){
        int f=n.indexOf("از "); if(f<0)return null;
        int t=n.indexOf(" به ",f+3); int sep=4;
        if(t<0){t=n.indexOf(" تا ",f+3);sep=4;}
        if(t<0){
            String[] joins={" میخوام برم "," می خوام برم "," میخام برم "," برم "," بریم "};
            for(String j:joins){int k=n.indexOf(j,f+3);if(k>f+3){String o=cleanRideTail(n.substring(f+3,k));String d=cleanRideTail(n.substring(k+j.length()));if(!o.isEmpty()&&!d.isEmpty())return new String[]{o,d};}}
            return null;
        }
        String o=cleanRideTail(n.substring(f+3,t)); String d=cleanRideTail(n.substring(t+sep));
        return o.isEmpty()||d.isEmpty()?null:new String[]{o,d};
    }

    private String extractDestination(String n){
        String[] m={"برم پیش ","بریم پیش ","میخوام برم پیش ","می خوام برم پیش ","میخام برم پیش ","به سمت "," برم "," بریم "," به "};
        for(String x:m){int i=n.lastIndexOf(PersianText.norm(x));if(i>=0){String d=cleanRideTail(n.substring(i+PersianText.norm(x).length()));if(!d.isEmpty())return d;}}
        return "";
    }

    /** null = not an origin correction, empty = correction intent but new origin missing. */
    private String extractOriginCorrection(String n){
        if(PersianText.hasAny(n,"مبدا","مبدأ")){
            String x=n.replaceAll("(مبدا|مبدأ|رو|را|عوض|تغییر|کن|بکن|اصلاح|جدید)"," ").replaceAll("\\s+"," ").trim();
            return cleanCorrectionValue(x);
        }
        if(n.startsWith("نه از ")) return cleanRideTail(n.substring("نه از ".length()));
        if(n.startsWith("از ") && !n.contains(" به ") && !n.contains(" تا ")) return cleanRideTail(n.substring(3));
        return null;
    }

    /** null = not a destination correction, empty = correction intent but new destination missing. */
    private String extractDestinationCorrection(String n){
        if(PersianText.hasAny(n,"مقصد")){
            String x=n.replaceAll("(مقصد|رو|را|عوض|تغییر|کن|بکن|اصلاح|جدید)"," ").replaceAll("\\s+"," ").trim();
            return cleanCorrectionValue(x);
        }
        if(n.startsWith("نه به ")) return cleanRideTail(n.substring("نه به ".length()));
        if(n.startsWith("نه برم ")) return cleanRideTail(n.substring("نه برم ".length()));
        return null;
    }

    private String cleanCorrectionValue(String x){
        x=PersianText.norm(x).replaceAll("^(به|از) ","").trim();
        if(PersianText.hasAny(x,"عوض","تغییر","اصلاح") && x.split(" ").length<=2) return "";
        return x;
    }

    private String cleanRideTail(String s){
        return PersianText.norm(s)
                .replaceAll("(اسنپ بگیر|ماشین بگیر|تاکسی بگیر|خودرو بگیر|لطفا|لطفاً|خواهشا|خواهشاً)$","")
                .replaceAll("^(لطفا|لطفاً) ","")
                .trim();
    }

    private String extractTargetForEdit(String n){
        String x=n.replaceAll("(آدرس|ادرس|لوکیشن|موقعیت|ویرایش|ادیت|تغییر|عوض|کن|کنم|میخوام|می خوام|میخام|اشتباهه|درستش|درست|اصلاح|جابجا|جا به جا|پین)"," ").replaceAll("\\s+"," ").trim();
        return x.replaceAll("(^| )(رو|را)($| )"," ").replaceAll("\\s+"," ").trim();
    }

    private String extractTitleForAdd(String n){
        int i=n.indexOf(" رو ذخیره"); if(i<0)i=n.indexOf(" را ذخیره"); if(i>0)return n.substring(0,i).replace("آدرس ","").replace("ادرس ","").trim();
        i=n.indexOf(" رو ثبت"); if(i<0)i=n.indexOf(" را ثبت"); if(i>0)return n.substring(0,i).replace("آدرس ","").replace("ادرس ","").trim();
        return "";
    }
}
