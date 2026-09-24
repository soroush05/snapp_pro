package com.soroush.agent;

import java.util.*;

public final class PersianText {
    private PersianText() {}

    public static String norm(String s) {
        if (s == null) return "";
        String n=s.trim().toLowerCase(Locale.ROOT)
                .replace('ي','ی').replace('ك','ک')
                .replace('ۀ','ه').replace('ة','ه')
                .replace("‌", " ").replaceAll("[ًٌٍَُِّْـ]", "")
                .replace('۰','0').replace('۱','1').replace('۲','2').replace('۳','3').replace('۴','4')
                .replace('۵','5').replace('۶','6').replace('۷','7').replace('۸','8').replace('۹','9')
                .replaceAll("[،,:;؛()\\[\\]{}]", " ")
                .replaceAll("\\s+", " ");
        return n.trim();
    }

    public static String addressNorm(String s){
        String n=norm(s);
        n=n.replace("بن بست","بن‌بست");
        n=n.replaceAll("\\bخیابان\\b|\\bخ\\b", " ");
        n=n.replaceAll("\\bکوچه\\b|\\bک\\b", " ");
        n=n.replaceAll("\\bبلوار\\b", " ");
        n=n.replaceAll("\\bمیدان\\b", " ");
        // Honorifics are useful aliases but should not make an otherwise identical
        // Iranian street name fail to match (e.g. امینی / شهید امینی).
        n=n.replaceAll("\\bشهید\\b|\\bدکتر\\b|\\bمهندس\\b", " ");
        return n.replaceAll("\\s+", " ").trim();
    }

    public static List<String> addressTokens(String s){
        String n=addressNorm(s);
        ArrayList<String> out=new ArrayList<>();
        HashSet<String> stop=new HashSet<>(Arrays.asList("ایران","استان","شهر","منطقه","محله","شهرستان","بخش","جنب","نزدیک"));
        for(String x:n.split(" ")) if(x.length()>=2 && !stop.contains(x)) out.add(x);
        return out;
    }

    public static int addressSimilarity(String expected,String candidate){
        String a=addressNorm(expected), b=addressNorm(candidate);
        if(a.isEmpty()||b.isEmpty()) return 0;
        int score=0;
        if(a.equals(b)) score+=100;
        if(b.contains(a)||a.contains(b)) score+=35;
        List<String> at=addressTokens(a), bt=addressTokens(b);
        Set<String> bs=new HashSet<>(bt);
        int matched=0;
        for(int i=0;i<at.size();i++){
            String t=at.get(i);
            if(bs.contains(t)){ matched++; score += (i==0?12:8); }
            else {
                for(String c:bt){ if(fuzzyToken(t,c)){ matched++; score+=4; break; } }
            }
        }
        if(!at.isEmpty() && matched==at.size()) score+=25;
        if(at.size()>=3 && matched<2) score-=25;
        return score;
    }

    private static boolean fuzzyToken(String a,String b){
        if(a.equals(b)) return true;
        if(a.length()<4||b.length()<4) return false;
        if(a.startsWith(b)||b.startsWith(a)) return true;
        return editDistance(a,b)<=1;
    }

    private static int editDistance(String a,String b){
        int[] prev=new int[b.length()+1], cur=new int[b.length()+1];
        for(int j=0;j<=b.length();j++) prev[j]=j;
        for(int i=1;i<=a.length();i++){
            cur[0]=i;
            for(int j=1;j<=b.length();j++) cur[j]=Math.min(Math.min(cur[j-1]+1,prev[j]+1),prev[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));
            int[] t=prev; prev=cur; cur=t;
        }
        return prev[b.length()];
    }

    public static boolean hasApproxAny(String s, String... xs) {
        String n=norm(s);
        if(hasAny(n,xs)) return true;
        String[] words=n.split(" ");
        for(String x0:xs){
            String x=norm(x0);
            if(x.contains(" ")) continue;
            for(String w:words){
                if(w.length()>=3 && x.length()>=3 && editDistance(w,x)<=1) return true;
            }
        }
        return false;
    }

    public static boolean hasAny(String s, String... xs) {
        String n = norm(s);
        for (String x : xs) if (n.contains(norm(x))) return true;
        return false;
    }
}
