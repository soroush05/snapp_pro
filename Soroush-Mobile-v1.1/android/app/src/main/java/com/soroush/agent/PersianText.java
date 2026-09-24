package com.soroush.agent;

import java.util.*;

public final class PersianText {
    private PersianText(){}

    public static String norm(String s){
        if(s==null)return "";
        return s.trim().toLowerCase(Locale.ROOT)
                .replace('ي','ی').replace('ك','ک').replace('ۀ','ه').replace('ة','ه')
                .replace("‌"," ").replaceAll("[ًٌٍَُِّْـ]","")
                .replace('۰','0').replace('۱','1').replace('۲','2').replace('۳','3').replace('۴','4').replace('۵','5').replace('۶','6').replace('۷','7').replace('۸','8').replace('۹','9')
                .replaceAll("[،,:;؛()\\[\\]{}!?؟]"," ").replaceAll("\\s+"," ").trim();
    }

    public static String addressNorm(String s){
        String n=norm(s).replace("بن بست","بن‌بست");
        n=n.replaceAll("(^| )(خیابان|خ|کوچه|ک|بلوار|میدان)( |$)"," ");
        n=n.replaceAll("(^| )(شهید|دکتر|مهندس)( |$)"," ");
        return n.replaceAll("\\s+"," ").trim();
    }

    public static List<String> addressTokens(String s){
        String n=addressNorm(s);ArrayList<String> out=new ArrayList<>();
        Set<String> stop=new HashSet<>(Arrays.asList("ایران","استان","شهر","منطقه","محله","شهرستان","بخش","جنب","نزدیک","به","از","در","روی"));
        for(String x:n.split(" "))if(x.length()>=2&&!stop.contains(x))out.add(x);return out;
    }

    public static List<String> addressTokensKeepTypes(String s){
        String n=norm(s);ArrayList<String> out=new ArrayList<>();
        Set<String> stop=new HashSet<>(Arrays.asList("ایران","استان","شهر","منطقه","شهرستان","بخش","جنب","نزدیک","به","از","در","روی"));
        for(String x:n.split(" "))if(x.length()>=2&&!stop.contains(x))out.add(x);return out;
    }

    public static int addressSimilarity(String expected,String candidate){
        String a=addressNorm(expected),b=addressNorm(candidate);if(a.isEmpty()||b.isEmpty())return 0;
        int score=0;if(a.equals(b))score+=100;if(b.contains(a)||a.contains(b))score+=35;
        List<String> at=addressTokens(a),bt=addressTokens(b);int matched=0;
        for(int i=0;i<at.size();i++){
            double best=0;for(String c:bt)best=Math.max(best,tokenSimilarity(at.get(i),c));
            if(best>=.92){matched++;score+=(i==0?12:8);}else if(best>=.70){matched++;score+=4;}
        }
        if(!at.isEmpty()&&matched==at.size())score+=25;if(at.size()>=3&&matched<2)score-=25;return score;
    }

    public static double tokenSimilarity(String a,String b){
        String x=addressNorm(a),y=addressNorm(b);if(x.isEmpty()||y.isEmpty())return 0;if(x.equals(y))return 1;
        if(x.startsWith(y)||y.startsWith(x))return .88;
        int max=Math.max(x.length(),y.length());if(max<3)return 0;int d=editDistance(x,y);double sim=1.0-(double)d/max;
        if((x.equals("امینی")&&y.equals("شهید امینی"))||(y.equals("امینی")&&x.equals("شهید امینی")))sim=Math.max(sim,.95);
        return Math.max(0,sim);
    }
    public static boolean tokenNear(String a,String b){return tokenSimilarity(a,b)>=.78;}

    public static boolean looksLikePublicPlacePhrase(String q){
        String n=norm(q);String[] types={"بیمارستان","دانشگاه","فرودگاه","ترمینال","پارک","میدان","خیابان","بلوار","بازار","مرکز","ایستگاه","هتل","رستوران","کافه"};
        for(String t:types)if(n.startsWith(t+" ")||n.endsWith(" "+t))return true;return false;
    }

    public static int editDistance(String a,String b){
        int[] prev=new int[b.length()+1],cur=new int[b.length()+1];for(int j=0;j<=b.length();j++)prev[j]=j;
        for(int i=1;i<=a.length();i++){cur[0]=i;for(int j=1;j<=b.length();j++)cur[j]=Math.min(Math.min(cur[j-1]+1,prev[j]+1),prev[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));int[] t=prev;prev=cur;cur=t;}return prev[b.length()];
    }
}
