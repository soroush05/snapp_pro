package com.soroush.agent;

import java.util.*;

/** Explainable map-candidate ranking. Ranking never implies user confirmation. */
public final class LocationScorer {
    private LocationScorer(){}
    public static double score(AddressComponents expected,LocationCandidate c,String lockedCity){
        String city=PersianText.addressNorm(lockedCity);String cc=PersianText.addressNorm(c.city+" "+c.display);
        if(!city.isEmpty()&&!c.city.isEmpty()&&!PersianText.tokenNear(city,PersianText.addressNorm(c.city))){c.hardRejected=true;c.evidence="city mismatch";c.score=-1000;return c.score;}
        double score=0;StringBuilder ev=new StringBuilder();
        if(!city.isEmpty()){
            if(cc.contains(city)){score+=35;ev.append("city+35 ");}else{score-=12;ev.append("city? ");}
        }
        List<String> q=expected.orderedTokens;List<String> dt=PersianText.addressTokens(c.name+" "+c.road+" "+c.neighborhood+" "+c.display);
        for(int i=0;i<q.size();i++){
            String token=q.get(i);double w=i==0?9:(i==q.size()-1?15:11);double sim=bestToken(token,dt);
            score+=w*sim;if(sim>.75)ev.append(token).append('+').append((int)Math.round(w*sim)).append(' ');
        }
        int text=PersianText.addressSimilarity(expected.raw,c.display);score+=Math.max(-15,Math.min(35,text*.22));
        if(!c.name.isEmpty())score+=Math.min(8,PersianText.addressSimilarity(expected.raw,c.name)*.08);
        if("nominatim".equals(c.source))score+=2;
        c.score=score;c.evidence=ev.toString().trim();return score;
    }
    private static double bestToken(String q,List<String> xs){double best=0;for(String x:xs)best=Math.max(best,PersianText.tokenSimilarity(q,x));return best;}
}
