package com.soroush.agent;

/** Canonical location object. User-confirmed coordinates are authoritative and immutable by geocoding. */
public final class LocationRef {
    public enum Type { SAVED_PLACE, MAP_POI, STREET, ADDRESS, CURRENT_LOCATION, MANUAL_PIN, UNKNOWN }
    public enum Confidence { CONFIRMED, HIGH, MEDIUM, LOW, UNVERIFIED }
    public final String label,rawText,canonicalAddress,city,source;
    public final double lat,lon;
    public final Type type;
    public final Confidence confidence;
    public final boolean userConfirmed;
    public final long verifiedAt;

    public LocationRef(String label,String raw,String canonical,String city,double lat,double lon,Type type,Confidence confidence,boolean confirmed){
        this(label,raw,canonical,city,lat,lon,type,confidence,confirmed,confirmed?"USER_CONFIRMED":"UNSPECIFIED",confirmed?System.currentTimeMillis():0);
    }
    public LocationRef(String label,String raw,String canonical,String city,double lat,double lon,Type type,Confidence confidence,boolean confirmed,String source,long verifiedAt){
        this.label=s(label);this.rawText=s(raw);this.canonicalAddress=s(canonical);this.city=s(city);this.lat=lat;this.lon=lon;this.type=type==null?Type.UNKNOWN:type;
        this.confidence=confidence==null?Confidence.UNVERIFIED:confidence;this.userConfirmed=confirmed;this.source=s(source);this.verifiedAt=verifiedAt;
    }
    public boolean hasPoint(){return !Double.isNaN(lat)&&!Double.isNaN(lon);}
    public String searchText(){return canonicalAddress.isEmpty()?rawText:canonicalAddress;}
    public String providerQuery(){
        String r=PersianText.norm(rawText);if(PersianText.addressTokens(r).size()>=2)return r;
        return searchText();
    }
    private static String s(String x){return x==null?"":x.trim();}
}
