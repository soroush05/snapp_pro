package com.soroush.agent;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.*;

public class SavedPlaceRepository {
    private final SharedPreferences prefs;
    public SavedPlaceRepository(Context c) { prefs = c.getSharedPreferences("places", Context.MODE_PRIVATE); }

    // Backward-compatible save for old callers.
    public void save(String title, String address) {
        save(title, address, address, Double.NaN, Double.NaN);
    }

    public void save(String title, String address, String searchAddress, double lat, double lon) {
        try {
            JSONObject root = new JSONObject(prefs.getString("items", "{}"));
            JSONObject value = new JSONObject();
            value.put("address", address == null ? "" : address.trim());
            value.put("searchAddress", searchAddress == null ? "" : searchAddress.trim());
            if (!Double.isNaN(lat)) value.put("lat", lat);
            if (!Double.isNaN(lon)) value.put("lon", lon);
            root.put(title.trim(), value);
            prefs.edit().putString("items", root.toString()).apply();
        } catch (Exception ignored) {}
    }

    public Map<String,String> all() {
        LinkedHashMap<String,String> out = new LinkedHashMap<>();
        try {
            JSONObject o = new JSONObject(prefs.getString("items", "{}"));
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k=it.next();
                Object v=o.opt(k);
                if (v instanceof JSONObject) out.put(k, ((JSONObject)v).optString("address", ""));
                else out.put(k, o.optString(k)); // legacy string value
            }
        } catch (Exception ignored) {}
        return out;
    }

    private Place readPlace(String title) {
        try {
            JSONObject o = new JSONObject(prefs.getString("items", "{}"));
            Object v=o.opt(title);
            if (v instanceof JSONObject) {
                JSONObject p=(JSONObject)v;
                return new Place(title,
                        p.optString("address", ""),
                        p.optString("searchAddress", p.optString("address", "")),
                        p.has("lat") ? p.optDouble("lat") : Double.NaN,
                        p.has("lon") ? p.optDouble("lon") : Double.NaN);
            }
            String address=o.optString(title, "");
            return new Place(title,address,address,Double.NaN,Double.NaN);
        } catch(Exception e){ return null; }
    }

    public Match findPersonal(String utterance) {
        String q = stripRelationWords(utterance);
        List<Match> candidates = new ArrayList<>();
        for (Map.Entry<String,String> e : all().entrySet()) {
            String t = PersianText.norm(e.getKey());
            int score=0;
            if (q.equals(t)) score=100;
            else if (q.contains(t) || t.contains(q)) score=75;
            if(score>0){
                Place p=readPlace(e.getKey());
                if(p!=null) candidates.add(new Match(p.title,p.address,p.searchAddress,p.lat,p.lon,score));
            }
        }
        candidates.sort((a,b)->Integer.compare(b.score,a.score));
        if (candidates.size()==1) return candidates.get(0);
        if (!candidates.isEmpty() && candidates.get(0).score > candidates.get(1).score) return candidates.get(0);
        return null;
    }

    public static String stripRelationWords(String s) {
        String n = PersianText.norm(s);
        String[] phrases = {"میخوام برم پیش", "می خوام برم پیش", "میخوام برم", "می خوام برم", "بریم پیش", "برم پیش", "برو به", "بریم", "برم", "پیش", "به سمت", "به"};
        for (String p : phrases) {
            String np = PersianText.norm(p);
            if (n.startsWith(np + " ")) { n = n.substring(np.length()).trim(); break; }
        }
        return n;
    }

    private static final class Place {
        final String title,address,searchAddress; final double lat,lon;
        Place(String t,String a,String s,double la,double lo){title=t;address=a;searchAddress=s;lat=la;lon=lo;}
    }

    public static final class Match {
        public final String title, address, searchAddress; public final double lat,lon; public final int score;
        Match(String t,String a,String sa,double la,double lo,int s){title=t;address=a;searchAddress=sa;lat=la;lon=lo;score=s;}
        public boolean hasPoint(){ return !Double.isNaN(lat) && !Double.isNaN(lon); }
    }
}
