package com.soroush.agent;

public final class LocationRef {
    public enum Type { SAVED_PLACE, MAP, CURRENT_LOCATION, MANUAL_PIN, ADDRESS }
    public enum Confidence { HIGH, MEDIUM, LOW, UNVERIFIED }
    public final String label;
    public final String rawText;
    public final String canonicalAddress;
    public final String city;
    public final double lat;
    public final double lon;
    public final Type type;
    public final Confidence confidence;
    public final boolean userConfirmed;

    public LocationRef(String label,String raw,String canonical,String city,double lat,double lon,Type type,Confidence confidence,boolean confirmed){
        this.label=label==null?"":label; this.rawText=raw==null?"":raw; this.canonicalAddress=canonical==null?"":canonical;
        this.city=city==null?"":city; this.lat=lat; this.lon=lon; this.type=type; this.confidence=confidence; this.userConfirmed=confirmed;
    }
    public boolean hasPoint(){ return !Double.isNaN(lat)&&!Double.isNaN(lon); }
    public String searchText(){ return canonicalAddress.trim().isEmpty()?rawText:canonicalAddress; }
}
