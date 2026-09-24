package com.soroush.agent;

public final class LocationCandidate {
    public final double lat,lon;
    public final String display,city,neighborhood,road,name,source;
    public double score;
    public boolean hardRejected;
    public String evidence="";
    public LocationCandidate(double lat,double lon,String display,String city,String neighborhood,String road,String name,String source){
        this.lat=lat;this.lon=lon;this.display=s(display);this.city=s(city);this.neighborhood=s(neighborhood);this.road=s(road);this.name=s(name);this.source=s(source);
    }
    private static String s(String x){return x==null?"":x.trim();}
}
