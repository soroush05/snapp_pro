package com.soroush.agent;

import java.util.*;
import java.util.regex.*;

/**
 * Extracts slots after intent classification. This class is intentionally grammar/role based;
 * it does not decide intent by matching complete commands.
 */
public final class SemanticEntityExtractor {
    private SemanticEntityExtractor(){}

    public static String[] rideEndpoints(String raw){
        String n=PersianText.norm(raw);
        Matcher m=Pattern.compile("(?:^| )از\\s+(.+?)\\s+(?:به|تا)\\s+(.+?)(?:\\s+(?:ماشین|اسنپ|تاکسی).*)?$").matcher(n);
        if(m.find())return new String[]{cleanupPlace(m.group(1)),cleanupPlace(m.group(2))};
        m=Pattern.compile("(?:^| )از\\s+(.+?)\\s+(?:میخوام|می خوام|میخام)?\\s*(?:برم|بریم|حرکت کنم)\\s+(.+)$").matcher(n);
        if(m.find())return new String[]{cleanupPlace(m.group(1)),cleanupPlace(m.group(2))};
        // Colloquial ride grammar: «از دفتر بگیر برو فرودگاه» / «از خونه بگیر بریم دانشگاه».
        m=Pattern.compile("(?:^| )از\\s+(.+?)\\s+(?:ماشین\\s+)?(?:بگیر|بردار)\\s+(?:و\\s+)?(?:برو|بریم|برم|ببر)\\s+(?:به\\s+|پیش\\s+)?(.+)$").matcher(n);
        if(m.find())return new String[]{cleanupPlace(m.group(1)),cleanupPlace(m.group(2))};
        return null;
    }

    public static String destination(String raw){
        String n=PersianText.norm(raw);
        String[] patterns={
                "(?:میخوام|می خوام|میخام)\\s+(?:برم|بریم)\\s+(?:به\\s+|پیش\\s+)?(.+)$",
                "(?:برم|بریم)\\s+(?:به\\s+|پیش\\s+)?(.+)$",
                "(?:ببر(?:م|مون| منو)?\\s+)(.+)$"
        };
        for(String p:patterns){Matcher m=Pattern.compile(p).matcher(n);if(m.find()){String v=cleanupPlace(m.group(1));if(!v.isEmpty())return v;}}
        return "";
    }

    public static String changedOrigin(String raw){
        String n=PersianText.norm(raw);
        Matcher m=Pattern.compile("(?:مبدا|مبدأ).*?(?:به|بشه|بکن|کن)\\s+(.+)$").matcher(n);
        if(m.find())return cleanupPlace(m.group(1));
        m=Pattern.compile("^(?:نه\\s+)?از\\s+(.+)$").matcher(n);
        if(m.find()&&!n.contains(" به ")&&!n.contains(" تا "))return cleanupPlace(m.group(1));
        return "";
    }

    public static String changedDestination(String raw){
        String n=PersianText.norm(raw);
        Matcher swap=Pattern.compile("(?:نمیخوام|نمی خوام)\\s+(?:برم|بریم)\\s+.+?(?:\\s+(?:برو|برم|بریم|مقصد(?:م)?(?:\\s+بشه|\\s+باشه)?))\\s+(.+)$").matcher(n);
        if(swap.find())return cleanupPlace(swap.group(1));
        Matcher m=Pattern.compile("مقصد.*?(?:به|بشه|بکن|کن)\\s+(.+)$").matcher(n);
        if(m.find())return cleanupPlace(m.group(1));
        m=Pattern.compile("^(?:نه\\s+)?(?:به|برم)\\s+(.+)$").matcher(n);
        if(m.find())return cleanupPlace(m.group(1));
        return "";
    }

    public static String probableSavedPlaceTitle(String raw){
        String n=PersianText.norm(raw);
        // Quoted text is the strongest title signal.
        Matcher q=Pattern.compile("[«\"]([^»\"]{1,50})[»\"]").matcher(raw==null?"":raw);
        if(q.find())return q.group(1).trim();
        // Persian object marker: "خونه مامان رو ...", "محل کارم را ..."
        Matcher m=Pattern.compile("^(.{2,60}?)\\s+(?:رو|را)\\s+").matcher(n);
        if(m.find()){
            String x=m.group(1).trim();
            x=x.replaceFirst("^(?:میخوام|می خوام|میخام)\\s+","");
            x=x.replaceFirst("^(?:آدرس|ادرس|لوکیشن|موقعیت|جای)\\s+","");
            if(x.split(" ").length<=6)return x;
        }
        // "جای مامانم ..." / "لوکیشن محل کار ..."
        m=Pattern.compile("(?:جای|لوکیشن|موقعیت|آدرس|ادرس)\\s+([^،,.]{2,45}?)(?:\\s+(?:رو|را|که|میخوام|می خوام|میخام|باید|برام)|$)").matcher(n);
        if(m.find())return cleanupTitle(m.group(1));
        return "";
    }

    public static String editTarget(String raw){
        String title=probableSavedPlaceTitle(raw);if(!title.isEmpty())return title;
        String n=PersianText.norm(raw);
        Matcher m=Pattern.compile("(?:آدرس|ادرس|لوکیشن|موقعیت|جای)\\s+([^،,.]{2,45}?)(?:\\s+(?:اشتباه|غلط|درست|عوض|تغییر|اصلاح|ویرایش|ادیت|رو|را)|$)").matcher(n);
        if(m.find())return cleanupTitle(m.group(1));
        return "";
    }

    public static String cityHint(String raw){
        String n=PersianText.norm(raw);
        Matcher m=Pattern.compile("(?:شهر|تو|در)\\s+([آ-یa-zA-Z]{3,25})(?:\\s|$)").matcher(n);
        if(m.find())return m.group(1).trim();
        return "";
    }


    public static String searchTarget(String raw){
        String n=PersianText.norm(raw);
        n=n.replaceFirst("^(?:آدرس|ادرس|موقعیت|لوکیشن|جای)\\s+","");
        n=n.replaceFirst("\\s+(?:رو|را)?\\s*(?:پیدا|پیداش|نشان|نشون)\\s*(?:کن|بده|بدی)?$","");
        n=n.replaceFirst("\\s+(?:کجاست|کجاس)$","");
        return n.trim();
    }

    public static String probableAddressTail(String raw,String title){
        String n=PersianText.norm(raw);if(n.isEmpty())return "";
        String t=PersianText.norm(title);
        int start=0;if(!t.isEmpty()){int i=n.indexOf(t);if(i>=0)start=i+t.length();}
        String tail=n.substring(Math.min(start,n.length())).trim();
        tail=tail.replaceFirst("^(?:رو|را)\\s+","");
        // Remove only the leading action phrase; this does not participate in intent classification.
        tail=tail.replaceFirst("^(?:(?:میخوام|می خوام|میخام)\\s+)?(?:ذخیره|ثبت|اضافه|نگه)\\s*(?:کن|کنم|کنی|داشته باش|دار)?\\s*","").trim();
        if(PersianText.addressTokens(tail).size()>=3)return tail;
        return "";
    }
    public static String cleanupPlace(String s){
        if(s==null)return "";
        String n=PersianText.norm(s)
                .replaceAll("\\s+(?:لطفا|لطفاً|خواهشا|خواهشاً)$","")
                .replaceAll("\\s+(?:اسنپ|ماشین|تاکسی)\\s*(?:بگیر|بگیری|میخوام|می خوام)?$","")
                .replaceAll("^(?:به|پیش)\\s+","")
                .replaceAll("\\s+"," ").trim();
        return n;
    }

    private static String cleanupTitle(String s){
        return PersianText.norm(s).replaceAll("^(?:اون|این)\\s+","").replaceAll("\\s+"," ").trim();
    }
}
