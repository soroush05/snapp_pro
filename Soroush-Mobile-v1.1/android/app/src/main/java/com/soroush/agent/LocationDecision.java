package com.soroush.agent;

import java.util.*;

public final class LocationDecision {
    public enum Kind { CURRENT_LOCATION, RESOLVED_SAVED, SAVED_OR_MAP_AMBIGUOUS, SAVED_CANDIDATES, MAP_REQUIRED, NEED_MORE_CONTEXT }
    public final Kind kind;public final LocationRef location;public final String query;public final List<SavedPlaceRepository.Match> savedMatches;
    public LocationDecision(Kind k,LocationRef l,String q,List<SavedPlaceRepository.Match> m){kind=k;location=l;query=q==null?"":q;savedMatches=m==null?Collections.emptyList():m;}
    public static LocationDecision of(Kind k,String q){return new LocationDecision(k,null,q,null);}
}
