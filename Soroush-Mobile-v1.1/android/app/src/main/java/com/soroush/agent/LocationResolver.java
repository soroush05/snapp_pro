package com.soroush.agent;

import java.util.*;

/** Personal namespace + map namespace resolver. Fuzzy discovery never silently authorizes selection. */
public final class LocationResolver {
    private final SavedPlaceRepository repo;
    public LocationResolver(SavedPlaceRepository repo){this.repo=repo;}

    public LocationDecision resolve(String spec,boolean origin){
        String raw=spec==null?"":spec;String n=PersianText.norm(raw);String q=SavedPlaceRepository.stripRelationWords(raw);
        if(origin&&isCurrentLocationMeaning(n))return new LocationDecision(LocationDecision.Kind.CURRENT_LOCATION,
                new LocationRef("موقعیت فعلی",raw,"","",Double.NaN,Double.NaN,LocationRef.Type.CURRENT_LOCATION,LocationRef.Confidence.HIGH,true,"CURRENT_LOCATION",System.currentTimeMillis()),q,null);

        SavedPlaceRepository.Place exact=repo.exact(q);
        boolean personalCue=SavedPlaceRepository.hasPersonalRelationCue(raw);
        if(exact!=null){
            if(personalCue||exact.isClearlyPersonal()){
                if(exact.confirmed&&exact.hasPoint())return new LocationDecision(LocationDecision.Kind.RESOLVED_SAVED,exact.toLocation(),q,null);
                return new LocationDecision(LocationDecision.Kind.SAVED_CANDIDATES,null,q,Collections.singletonList(new SavedPlaceRepository.Match(exact,100)));
            }
            // A bare exact personal title can collide with public map names (e.g. بوعلی). Ask, do not guess.
            return new LocationDecision(LocationDecision.Kind.SAVED_OR_MAP_AMBIGUOUS,null,q,Collections.singletonList(new SavedPlaceRepository.Match(exact,100)));
        }
        List<SavedPlaceRepository.Match> candidates=repo.findCandidates(q);
        if(!candidates.isEmpty())return new LocationDecision(LocationDecision.Kind.SAVED_CANDIDATES,null,q,candidates);

        int tokens=PersianText.addressTokens(q).size();
        if(tokens>=2||PersianText.looksLikePublicPlacePhrase(q))return LocationDecision.of(LocationDecision.Kind.MAP_REQUIRED,q);
        return LocationDecision.of(LocationDecision.Kind.NEED_MORE_CONTEXT,q);
    }

    private boolean isCurrentLocationMeaning(String n){
        // This is namespace resolution, not intent classification. Keep a small deterministic vocabulary for GPS semantics.
        String x=PersianText.norm(n);return x.equals("اینجا")||x.equals("همینجا")||x.equals("همین جا")||x.equals("موقعیت فعلی")||x.equals("مکان فعلی")||x.equals("لوکیشن فعلی")||x.equals("جایی که هستم");
    }
}
