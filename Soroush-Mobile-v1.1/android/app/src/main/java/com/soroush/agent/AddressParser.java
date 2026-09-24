package com.soroush.agent;

import java.util.*;

/** Persian address parser/query planner that keeps parent context instead of flattening to one string. */
public final class AddressParser {
    private AddressParser(){}
    private static final Set<String> TYPE_WORDS=new HashSet<>(Arrays.asList(
            "خیابان","خ","بلوار","میدان","کوچه","کوی","بن","بست","بن‌بست","محله","شهرک","بزرگراه","اتوبان","جاده","چهارراه","بیمارستان","دانشگاه","پارک","مرکز"));

    public static AddressComponents parse(String raw,String cityHint){
        String n=PersianText.norm(raw);List<String> t=PersianText.addressTokensKeepTypes(n);
        String city=PersianText.norm(cityHint);
        if(city.isEmpty()&&t.size()>=4&&isPlausibleCityToken(t.get(0)))city=t.get(0); // provisional; geocoder must validate before hard-locking.
        String neighborhood="",street="",child="";
        if(t.size()>=2)neighborhood=t.get(Math.min(city.isEmpty()?0:1,t.size()-1));
        if(t.size()>=3)street=t.get(t.size()-2);
        if(t.size()>=1)child=t.get(t.size()-1);
        return new AddressComponents(raw,city,neighborhood,street,child,t);
    }

    public static List<String> queryPlan(String raw,String cityHint){
        AddressComponents a=parse(raw,cityHint);LinkedHashSet<String> q=new LinkedHashSet<>();
        String n=PersianText.norm(raw);if(!n.isEmpty())q.add(n);
        if(!a.city.isEmpty()&&!n.startsWith(a.city+" "))q.add(a.city+" "+n);
        // Common Iranian honorific alias: preserve all parents and vary only the deepest component.
        if(a.orderedTokens.size()>=4){
            List<String> x=new ArrayList<>(a.orderedTokens);int last=x.size()-1;
            if(last>=0&&!"شهید".equals(x.get(last))){
                if(last>0&&"شهید".equals(x.get(last-1))){ArrayList<String> y=new ArrayList<>(x);y.remove(last-1);q.add(join(y));}
                else{ArrayList<String> y=new ArrayList<>(x);y.add(last,"شهید");q.add(join(y));}
            }
            // Progressive fallback drops only the deepest component and NEVER drops the known city/context wholesale.
            if(x.size()>=4){ArrayList<String> parent=new ArrayList<>(x);parent.remove(parent.size()-1);q.add(join(parent));}
        }
        return new ArrayList<>(q);
    }

    public static boolean isPlausibleCityToken(String t){String n=PersianText.norm(t);return n.length()>=3&&!TYPE_WORDS.contains(n)&&!n.matches("\\d+");}
    private static String join(List<String> xs){StringBuilder s=new StringBuilder();for(String x:xs){if(s.length()>0)s.append(' ');s.append(x);}return s.toString().trim();}
}
