package com.soroush.agent;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/**
 * Saved Places v2. Coordinates confirmed by the user are authoritative. Legacy text-only entries are
 * migrated as UNVERIFIED rather than pretending they are precise.
 */
public class SavedPlaceRepository {
    private static final int SCHEMA_VERSION=2;
    private final SharedPreferences prefs;
    public SavedPlaceRepository(Context c){prefs=c.getSharedPreferences("places",Context.MODE_PRIVATE);migrateIfNeeded();}

    private void migrateIfNeeded(){
        try{
            JSONObject root=new JSONObject(prefs.getString("items","{}"));boolean changed=false;
            Iterator<String> it=root.keys();List<String> keys=new ArrayList<>();while(it.hasNext())keys.add(it.next());
            for(String k:keys){
                Object raw=root.opt(k);JSONObject v;
                if(!(raw instanceof JSONObject)){
                    String a=root.optString(k,"");v=new JSONObject();v.put("address",a);v.put("searchAddress",a);v.put("city","");v.put("aliases",new JSONArray());v.put("confirmed",false);v.put("source","LEGACY");v.put("confidence","UNVERIFIED");v.put("verifiedAt",0);root.put(k,v);changed=true;continue;
                }
                v=(JSONObject)raw;
                if(!v.has("searchAddress")){v.put("searchAddress",v.optString("address",""));changed=true;}
                if(!v.has("city")){v.put("city","");changed=true;}
                if(!v.has("aliases")){v.put("aliases",new JSONArray());changed=true;}
                if(!v.has("confirmed")){v.put("confirmed",v.has("lat")&&v.has("lon"));changed=true;}
                if(!v.has("source")){
                    v.put("source",v.optBoolean("confirmed",false)?"USER_CONFIRMED_PIN":"LEGACY");changed=true;
                }
                if(!v.has("confidence")){
                    v.put("confidence",v.optBoolean("confirmed",false)?"CONFIRMED":"UNVERIFIED");changed=true;
                }
                if(!v.has("verifiedAt")){v.put("verifiedAt",v.optBoolean("confirmed",false)?System.currentTimeMillis():0);changed=true;}
            }
            if(changed)prefs.edit().putString("items",root.toString()).putInt("schema",SCHEMA_VERSION).apply();
            else if(prefs.getInt("schema",0)!=SCHEMA_VERSION)prefs.edit().putInt("schema",SCHEMA_VERSION).apply();
        }catch(Exception ignored){}
    }

    public void save(String title,String address,String searchAddress,String city,double lat,double lon,boolean confirmed,String... aliases){
        save(title,address,searchAddress,city,lat,lon,confirmed,confirmed?"USER_CONFIRMED_PIN":"TEXT_ONLY",confirmed?"CONFIRMED":"UNVERIFIED",confirmed?System.currentTimeMillis():0,aliases);
    }

    public void save(String title,String address,String searchAddress,String city,double lat,double lon,boolean confirmed,String source,String confidence,long verifiedAt,String... aliases){
        if(title==null||title.trim().isEmpty())return;
        try{
            JSONObject root=new JSONObject(prefs.getString("items","{}"));JSONObject old=root.optJSONObject(title.trim());JSONObject v=new JSONObject();
            v.put("address",safe(address));v.put("searchAddress",safe(searchAddress));v.put("city",safe(city));v.put("confirmed",confirmed);
            v.put("source",safe(source));v.put("confidence",safe(confidence));v.put("verifiedAt",verifiedAt);
            if(!Double.isNaN(lat))v.put("lat",lat);if(!Double.isNaN(lon))v.put("lon",lon);
            LinkedHashSet<String> allAliases=new LinkedHashSet<>();
            if(old!=null){JSONArray oldA=old.optJSONArray("aliases");if(oldA!=null)for(int i=0;i<oldA.length();i++){String x=oldA.optString(i);if(!x.trim().isEmpty())allAliases.add(x.trim());}}
            if(aliases!=null)for(String x:aliases)if(x!=null&&!x.trim().isEmpty())allAliases.add(x.trim());
            JSONArray a=new JSONArray();for(String x:allAliases)a.put(x);v.put("aliases",a);
            root.put(title.trim(),v);prefs.edit().putString("items",root.toString()).apply();
        }catch(Exception ignored){}
    }

    public boolean delete(String title){
        if(title==null)return false;
        try{JSONObject root=new JSONObject(prefs.getString("items","{}"));String key=findStoredKey(root,title);if(key==null)return false;root.remove(key);prefs.edit().putString("items",root.toString()).apply();return true;}catch(Exception e){return false;}
    }

    public List<Place> allPlaces(){
        ArrayList<Place> out=new ArrayList<>();
        try{JSONObject root=new JSONObject(prefs.getString("items","{}"));Iterator<String> it=root.keys();while(it.hasNext()){String k=it.next();Place p=readPlace(root,k);if(p!=null)out.add(p);}}catch(Exception ignored){}
        out.sort(Comparator.comparing(a->PersianText.norm(a.title)));return out;
    }
    public Map<String,String> all(){LinkedHashMap<String,String> m=new LinkedHashMap<>();for(Place p:allPlaces())m.put(p.title,p.address);return m;}

    public Place exact(String query){
        String q=PersianText.norm(query);if(q.isEmpty())return null;
        for(Place p:allPlaces()){if(PersianText.norm(p.title).equals(q))return p;for(String a:p.aliases)if(PersianText.norm(a).equals(q))return p;}return null;
    }

    public List<Match> findCandidates(String utterance){
        String q=stripRelationWords(utterance);ArrayList<Match> out=new ArrayList<>();
        for(Place p:allPlaces()){
            int score=scoreTitle(q,p.title);for(String a:p.aliases)score=Math.max(score,scoreTitle(q,a));
            if(score>=45)out.add(new Match(p,score));
        }
        out.sort((a,b)->Integer.compare(b.score,a.score));return out;
    }

    public Match uniqueStrong(String utterance){
        List<Match> c=findCandidates(utterance);if(c.isEmpty())return null;
        if(c.get(0).score>=96)return c.get(0);
        if(c.size()==1&&c.get(0).score>=76)return c.get(0);
        if(c.size()>1&&c.get(0).score>=84&&c.get(0).score-c.get(1).score>=20)return c.get(0);
        return null;
    }

    private int scoreTitle(String q,String title){
        String a=PersianText.norm(q),b=PersianText.norm(title);if(a.isEmpty()||b.isEmpty())return 0;if(a.equals(b))return 100;
        double token=PersianText.tokenSimilarity(a,b);int addr=PersianText.addressSimilarity(a,b);
        int score=(int)Math.round(token*70)+Math.min(30,Math.max(0,addr/3));
        if(a.startsWith(b+" ")||a.endsWith(" "+b))score=Math.max(score,86);
        return Math.min(100,score);
    }

    private Place readPlace(JSONObject root,String title){
        try{
            JSONObject v=root.optJSONObject(title);if(v==null)return null;ArrayList<String> aliases=new ArrayList<>();JSONArray a=v.optJSONArray("aliases");if(a!=null)for(int i=0;i<a.length();i++){String x=a.optString(i);if(!x.isEmpty())aliases.add(x);}
            return new Place(title,v.optString("address",""),v.optString("searchAddress",v.optString("address","")),v.optString("city",""),
                    v.has("lat")?v.optDouble("lat"):Double.NaN,v.has("lon")?v.optDouble("lon"):Double.NaN,v.optBoolean("confirmed",false),aliases,
                    v.optString("source","LEGACY"),v.optString("confidence","UNVERIFIED"),v.optLong("verifiedAt",0));
        }catch(Exception e){return null;}
    }

    private String findStoredKey(JSONObject root,String title){String q=PersianText.norm(title);Iterator<String> it=root.keys();while(it.hasNext()){String k=it.next();if(PersianText.norm(k).equals(q))return k;}return null;}
    private String safe(String s){return s==null?"":s.trim();}

    public static String stripRelationWords(String s){
        String n=PersianText.norm(s);String[] p={"میخوام برم پیش","می خوام برم پیش","بریم پیش","برم پیش","پیش","میخوام برم","می خوام برم","برو به","به سمت","بریم","برم","به"};
        for(String x:p){String nx=PersianText.norm(x);if(n.startsWith(nx+" ")){n=n.substring(nx.length()).trim();break;}}return n;
    }
    public static boolean hasPersonalRelationCue(String s){String n=PersianText.norm(s);return n.startsWith("پیش ")||n.contains(" برم پیش ")||n.startsWith("خانه ")||n.startsWith("خونه ")||n.startsWith("منزل ");}

    public static final class Place{
        public final String title,address,searchAddress,city,source,confidence;public final double lat,lon;public final boolean confirmed;public final List<String> aliases;public final long verifiedAt;
        Place(String t,String a,String s,String c,double la,double lo,boolean conf,List<String> al,String src,String confidence,long verifiedAt){title=t;address=a;searchAddress=s;city=c;lat=la;lon=lo;confirmed=conf;aliases=Collections.unmodifiableList(new ArrayList<>(al));source=src;this.confidence=confidence;this.verifiedAt=verifiedAt;}
        public boolean hasPoint(){return !Double.isNaN(lat)&&!Double.isNaN(lon);}
        public boolean isClearlyPersonal(){String n=PersianText.norm(title);return n.contains("خانه")||n.contains("خونه")||n.contains("مامان")||n.contains("بابا")||n.contains("مادر")||n.contains("پدر")||n.contains("محل کار")||n.contains("دوست");}
        public LocationRef toLocation(){
            LocationRef.Confidence c;try{c=LocationRef.Confidence.valueOf(confidence);}catch(Exception e){c=confirmed&&hasPoint()?LocationRef.Confidence.CONFIRMED:LocationRef.Confidence.UNVERIFIED;}
            return new LocationRef(title,title,searchAddress,city,lat,lon,LocationRef.Type.SAVED_PLACE,c,confirmed&&hasPoint(),source,verifiedAt);
        }
    }
    public static final class Match{public final Place place;public final int score;public Match(Place p,int s){place=p;score=s;}}
}
