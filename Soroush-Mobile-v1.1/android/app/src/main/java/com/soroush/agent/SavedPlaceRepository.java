package com.soroush.agent;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

public class SavedPlaceRepository {
    private final SharedPreferences prefs;
    public SavedPlaceRepository(Context c){prefs=c.getSharedPreferences("places",Context.MODE_PRIVATE); migrateIfNeeded();}

    private void migrateIfNeeded(){
        try{
            JSONObject root=new JSONObject(prefs.getString("items","{}")); boolean changed=false;
            Iterator<String> it=root.keys(); List<String> keys=new ArrayList<>(); while(it.hasNext())keys.add(it.next());
            for(String k:keys){Object v=root.opt(k); if(!(v instanceof JSONObject)){JSONObject n=new JSONObject();String a=root.optString(k,"");n.put("address",a);n.put("searchAddress",a);n.put("city","");n.put("aliases",new JSONArray());n.put("confirmed",false);root.put(k,n);changed=true;}
                else{JSONObject n=(JSONObject)v;if(!n.has("searchAddress")){n.put("searchAddress",n.optString("address",""));changed=true;}if(!n.has("city")){n.put("city","");changed=true;}if(!n.has("aliases")){n.put("aliases",new JSONArray());changed=true;}if(!n.has("confirmed")){n.put("confirmed",n.has("lat")&&n.has("lon"));changed=true;}}
            }
            if(changed)prefs.edit().putString("items",root.toString()).apply();
        }catch(Exception ignored){}
    }

    public void save(String title,String address,String searchAddress,String city,double lat,double lon,boolean confirmed,String... aliases){
        try{
            JSONObject root=new JSONObject(prefs.getString("items","{}")); JSONObject v=new JSONObject();
            v.put("address",safe(address));v.put("searchAddress",safe(searchAddress));v.put("city",safe(city));v.put("confirmed",confirmed);
            if(!Double.isNaN(lat))v.put("lat",lat);if(!Double.isNaN(lon))v.put("lon",lon);
            JSONArray a=new JSONArray();if(aliases!=null)for(String x:aliases)if(x!=null&&!x.trim().isEmpty())a.put(x.trim());v.put("aliases",a);
            root.put(title.trim(),v);prefs.edit().putString("items",root.toString()).apply();
        }catch(Exception ignored){}
    }
    public void save(String title,String address,String searchAddress,double lat,double lon){save(title,address,searchAddress,"",lat,lon,!Double.isNaN(lat)&&!Double.isNaN(lon));}
    public void save(String title,String address){save(title,address,address,"",Double.NaN,Double.NaN,false);}

    public List<Place> allPlaces(){
        ArrayList<Place> out=new ArrayList<>();
        try{JSONObject root=new JSONObject(prefs.getString("items","{}"));Iterator<String> it=root.keys();while(it.hasNext()){String k=it.next();Place p=readPlace(root,k);if(p!=null)out.add(p);}}catch(Exception ignored){}
        out.sort(Comparator.comparing(a->PersianText.norm(a.title)));return out;
    }
    public Map<String,String> all(){LinkedHashMap<String,String> m=new LinkedHashMap<>();for(Place p:allPlaces())m.put(p.title,p.address);return m;}

    public Place exact(String query){String q=PersianText.norm(query);for(Place p:allPlaces()){if(PersianText.norm(p.title).equals(q))return p;for(String a:p.aliases)if(PersianText.norm(a).equals(q))return p;}return null;}

    public List<Match> findCandidates(String utterance){
        String q=stripRelationWords(utterance);ArrayList<Match> out=new ArrayList<>();
        for(Place p:allPlaces()){
            int score=scoreTitle(q,p.title);for(String a:p.aliases)score=Math.max(score,scoreTitle(q,a));
            if(score>0)out.add(new Match(p,score));
        }
        out.sort((a,b)->Integer.compare(b.score,a.score));return out;
    }
    public Match uniqueStrong(String utterance){List<Match> c=findCandidates(utterance);if(c.isEmpty())return null;if(c.get(0).score>=95)return c.get(0);if(c.size()==1&&c.get(0).score>=70)return c.get(0);if(c.size()>1&&c.get(0).score>=80&&c.get(0).score-c.get(1).score>=18)return c.get(0);return null;}

    private int scoreTitle(String q,String title){String t=PersianText.norm(title);if(q.equals(t))return 100;if(q.startsWith(t+" ")||q.endsWith(" "+t))return 86;if(q.contains(t)||t.contains(q))return 72;int sim=PersianText.addressSimilarity(q,t);return sim>=20?55:0;}
    private Place readPlace(JSONObject root,String title){try{JSONObject v=root.optJSONObject(title);if(v==null)return null;ArrayList<String> aliases=new ArrayList<>();JSONArray a=v.optJSONArray("aliases");if(a!=null)for(int i=0;i<a.length();i++)aliases.add(a.optString(i));return new Place(title,v.optString("address",""),v.optString("searchAddress",v.optString("address","")),v.optString("city",""),v.has("lat")?v.optDouble("lat"):Double.NaN,v.has("lon")?v.optDouble("lon"):Double.NaN,v.optBoolean("confirmed",false),aliases);}catch(Exception e){return null;}}
    private String safe(String s){return s==null?"":s.trim();}

    public static String stripRelationWords(String s){String n=PersianText.norm(s);String[] p={"میخوام برم پیش","می خوام برم پیش","بریم پیش","برم پیش","پیش","میخوام برم","می خوام برم","برو به","به سمت","بریم","برم","به"};for(String x:p){String nx=PersianText.norm(x);if(n.startsWith(nx+" ")){n=n.substring(nx.length()).trim();break;}}return n;}

    public static final class Place{
        public final String title,address,searchAddress,city;public final double lat,lon;public final boolean confirmed;public final List<String> aliases;
        Place(String t,String a,String s,String c,double la,double lo,boolean conf,List<String> al){title=t;address=a;searchAddress=s;city=c;lat=la;lon=lo;confirmed=conf;aliases=al;}
        public boolean hasPoint(){return !Double.isNaN(lat)&&!Double.isNaN(lon);}
        public LocationRef toLocation(){return new LocationRef(title,title,searchAddress,city,lat,lon,LocationRef.Type.SAVED_PLACE,confirmed&&hasPoint()?LocationRef.Confidence.HIGH:LocationRef.Confidence.UNVERIFIED,confirmed&&hasPoint());}
    }
    public static final class Match{public final Place place;public final int score;Match(Place p,int s){place=p;score=s;}}
}
