package com.soroush.agent;

import java.util.*;

/** Parsed, ordered address evidence. Fields may be empty; orderedTokens always preserves user context. */
public final class AddressComponents {
    public final String raw,city,neighborhood,street,child;
    public final List<String> orderedTokens;
    public AddressComponents(String raw,String city,String neighborhood,String street,String child,List<String> tokens){
        this.raw=raw==null?"":raw;this.city=clean(city);this.neighborhood=clean(neighborhood);this.street=clean(street);this.child=clean(child);
        this.orderedTokens=Collections.unmodifiableList(new ArrayList<>(tokens==null?Collections.emptyList():tokens));
    }
    public String deepestParent(){
        StringBuilder s=new StringBuilder();
        for(int i=0;i<Math.max(0,orderedTokens.size()-1);i++){if(s.length()>0)s.append(' ');s.append(orderedTokens.get(i));}
        return s.toString();
    }
    private static String clean(String s){return s==null?"":s.trim();}
}
