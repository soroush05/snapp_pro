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
        this.label=s(label);this.rawText=s(raw);this.canonicalAddress=s(canonical);
        String normalizedCity=s(city);this.city=normalizedCity.isEmpty()?inferCity(this.canonicalAddress):normalizedCity;this.lat=lat;this.lon=lon;this.type=type==null?Type.UNKNOWN:type;
        this.confidence=confidence==null?Confidence.UNVERIFIED:confidence;this.userConfirmed=confirmed;this.source=s(source);this.verifiedAt=verifiedAt;
    }
    public boolean hasPoint(){return !Double.isNaN(lat)&&!Double.isNaN(lon);}
    public String searchText(){return canonicalAddress.isEmpty()?rawText:canonicalAddress;}
    public String providerQuery(){
        String r=PersianText.norm(rawText);
        if(PersianText.addressTokens(r).size()>=2 && !r.contains("موقعیت انتخاب شده")) return r;
        String compact=compactAddress(canonicalAddress,city);
        return compact.isEmpty()?searchText():compact;
    }

    /** Compact provider query: keep the useful local components and city, drop postal/admin noise. */
    static String compactAddress(String address,String city){
        String a=s(address);if(a.isEmpty())return "";
        java.util.ArrayList<String> useful=new java.util.ArrayList<>();
        for(String rawPart:a.split(",")){
            String part=rawPart.trim();String n=PersianText.norm(part);
            if(part.isEmpty())continue;
            if(n.matches(".*\\d{4,}.*"))continue;
            if(n.startsWith("بخش ")||n.startsWith("شهرستان ")||n.startsWith("استان ")||n.startsWith("دهستان ")||n.startsWith("مرز ")||n.equals("ایران"))continue;
            useful.add(part);if(useful.size()>=3)break;
        }
        String c=s(city);if(c.isEmpty())c=inferCity(a);
        StringBuilder out=new StringBuilder();
        if(!c.isEmpty())out.append(cityCore(c));
        for(String part:useful){String n=PersianText.norm(part);if(!c.isEmpty()&&n.contains(PersianText.norm(cityCore(c))))continue;if(out.length()>0)out.append(' ');out.append(part);}
        return PersianText.norm(out.toString());
    }

    static String inferCity(String address){
        String n=PersianText.norm(address);if(n.isEmpty())return "";
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:شهر|شهرستان|استان)\\s+([آ-ی]+(?:\\s+[آ-ی]+)?)").matcher(n);
        while(m.find()){String x=cityCore(m.group(1));if(!x.isEmpty()&&!x.equals("مرکزی"))return x;}
        return "";
    }
    static String cityCore(String city){
        String n=PersianText.norm(city);if(n.isEmpty())return "";
        for(String part:n.split(" ")){if(part.isEmpty()||part.equals("شهر")||part.equals("شهرستان")||part.equals("استان")||part.equals("بخش")||part.equals("مرکزی")||part.equals("ایران"))continue;return part;}
        return "";
    }
    private static String s(String x){return x==null?"":x.trim();}
}
