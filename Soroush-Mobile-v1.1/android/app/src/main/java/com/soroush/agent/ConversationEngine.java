package com.soroush.agent;

public final class ConversationEngine {
    public IntentResult parse(String raw, ConversationContext ctx){
        String n=PersianText.norm(raw);
        if(n.isEmpty()) return IntentResult.of(IntentResult.Intent.UNKNOWN);
        if(PersianText.hasAny(n,"بیخیال","بی خیال","ولش کن","منصرف شدم","نمیخوام","نمی خوام","فعلا نه","فعلاً نه")) return IntentResult.of(IntentResult.Intent.CANCEL_FLOW);
        if(PersianText.hasAny(n,"سفر رو لغو","سفر را لغو","اسنپ رو کنسل","اسنپ را کنسل","ماشین رو لغو","درخواست اسنپ رو لغو")) return IntentResult.of(IntentResult.Intent.CANCEL_RIDE);
        if(PersianText.hasAny(n,"تایید","تأیید","همینه","درسته","بله","آره","اوکی","انجام بده")) return IntentResult.of(IntentResult.Intent.CONFIRM);
        if(PersianText.hasAny(n,"این نیست","اشتباهه","غلطه","نه این","نه اون","درست نیست")) return IntentResult.of(IntentResult.Intent.REJECT);
        if(PersianText.hasAny(n,"روی نقشه","نقشه رو نشون","نقشه را نشان","ببینم روی نقشه","دیدن روی نقشه")) return IntentResult.of(IntentResult.Intent.SHOW_MAP);

        boolean edit=PersianText.hasAny(n,"ویرایش","ادیت","تغییر آدرس","تغییر ادرس","آدرسش رو عوض","ادرسش رو عوض","لوکیشنش رو عوض","موقعیتش رو عوض","آدرس اشتباه","ادرس اشتباه","جاشو عوض","جاش رو عوض","درستش کن","درست کن","اصلاح کن","اصلاحش کن","جابجا کن","جا به جا کن");
        if(edit){return new IntentResult(IntentResult.Intent.EDIT_PLACE,"","",extractTargetForEdit(n),0.90);}
        boolean add=PersianText.hasAny(n,"آدرس اضافه","ادرس اضافه","موقعیت اضافه","مکان اضافه","آدرس جدید","ادرس جدید","مکان جدید","موقعیت جدید","ذخیره کن","ثبت کن") && !looksLikeRide(n);
        if(add){return new IntentResult(IntentResult.Intent.ADD_PLACE,"","",extractTitleForAdd(n),0.90);}

        if(looksLikeRide(n)){
            String[] p=extractFromTo(n);
            if(p!=null)return new IntentResult(IntentResult.Intent.REQUEST_RIDE,p[0],p[1],"",0.96);
            String d=extractDestination(n);
            return new IntentResult(IntentResult.Intent.REQUEST_RIDE,"",d,"",d.isEmpty()?0.76:0.88);
        }
        if(ctx!=null&&ctx.pending!=ConversationContext.Pending.NONE) return new IntentResult(IntentResult.Intent.ANSWER,"","",raw,0.95);
        return IntentResult.of(IntentResult.Intent.UNKNOWN);
    }

    private boolean looksLikeRide(String n){return PersianText.hasAny(n,"میخوام برم","می خوام برم","برم ","بریم ","ببر منو","ببرم","سفر","ماشین لازم","ماشین میخوام") || PersianText.hasApproxAny(n,"اسنپ","ماشین","تاکسی","خودرو");}
    private String[] extractFromTo(String n){
        int f=n.indexOf("از "); if(f<0)return null;
        int t=n.indexOf(" به ",f+3); int sep=4;
        if(t<0){t=n.indexOf(" تا ",f+3);sep=4;}
        if(t<0){
            String[] joins={" میخوام برم "," می خوام برم "," برم "," بریم "};
            for(String j:joins){int k=n.indexOf(j,f+3);if(k>f+3){String o=cleanRideTail(n.substring(f+3,k));String d=cleanRideTail(n.substring(k+j.length()));if(!o.isEmpty()&&!d.isEmpty())return new String[]{o,d};}}
            return null;
        }
        String o=cleanRideTail(n.substring(f+3,t)); String d=cleanRideTail(n.substring(t+sep));
        return o.isEmpty()||d.isEmpty()?null:new String[]{o,d};
    }
    private String extractDestination(String n){
        String[] m={"برم پیش ","بریم پیش ","میخوام برم پیش ","می خوام برم پیش ","به سمت "," برم "," بریم "," به "};
        for(String x:m){int i=n.lastIndexOf(PersianText.norm(x));if(i>=0){String d=cleanRideTail(n.substring(i+PersianText.norm(x).length()));if(!d.isEmpty())return d;}}
        return "";
    }
    private String cleanRideTail(String s){return PersianText.norm(s).replaceAll("(اسنپ بگیر|ماشین بگیر|تاکسی بگیر|خودرو بگیر|لطفا|لطفاً|خواهشا|خواهشاً)$","").trim();}
    private String extractTargetForEdit(String n){
        String x=n.replaceAll("(آدرس|ادرس|لوکیشن|موقعیت|ویرایش|ادیت|تغییر|عوض|کن|کنم|میخوام|می خوام|اشتباهه|درستش|درست|اصلاح|جابجا|جا به جا)"," ").replaceAll("\\s+"," ").trim();
        return x.replaceAll("(^| )(رو|را)($| )"," ").replaceAll("\\s+"," ").trim();
    }
    private String extractTitleForAdd(String n){
        int i=n.indexOf(" رو ذخیره"); if(i<0)i=n.indexOf(" را ذخیره"); if(i>0)return n.substring(0,i).replace("آدرس ","").replace("ادرس ","").trim();
        return "";
    }
}
