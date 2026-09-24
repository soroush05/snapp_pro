package com.soroush.agent;

import java.util.*;

/** Structured output of semantic understanding. Execution modules never parse the user's raw sentence. */
public final class IntentEnvelope {
    public enum Decision { ACCEPT, CLARIFY, UNKNOWN }

    public final SemanticIntent intent;
    public final double confidence;
    public final Decision decision;
    public final String raw;
    public final String origin;
    public final String destination;
    public final String target;
    public final String value;
    public final String city;
    public final Map<SemanticIntent,Double> alternatives;

    public IntentEnvelope(SemanticIntent intent,double confidence,Decision decision,String raw,
                          String origin,String destination,String target,String value,String city,
                          Map<SemanticIntent,Double> alternatives){
        this.intent=intent==null?SemanticIntent.UNKNOWN:intent;
        this.confidence=confidence;
        this.decision=decision==null?Decision.UNKNOWN:decision;
        this.raw=raw==null?"":raw;
        this.origin=clean(origin);this.destination=clean(destination);this.target=clean(target);this.value=clean(value);this.city=clean(city);
        this.alternatives=alternatives==null?Collections.emptyMap():Collections.unmodifiableMap(new LinkedHashMap<>(alternatives));
    }

    public static IntentEnvelope simple(SemanticIntent i,String raw,double c){
        return new IntentEnvelope(i,c,Decision.ACCEPT,raw,"","","","","",Collections.emptyMap());
    }
    public String topAlternatives(){
        StringBuilder s=new StringBuilder();int n=0;
        for(Map.Entry<SemanticIntent,Double> e:alternatives.entrySet()){
            if(n++>=3)break;if(s.length()>0)s.append(", ");s.append(e.getKey().name()).append('=').append(String.format(Locale.ROOT,"%.2f",e.getValue()));
        }
        return s.toString();
    }
    private static String clean(String s){return s==null?"":s.trim();}
}
