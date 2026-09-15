package com.soroush.agent;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.*;

public class SavedPlaceRepository {
    private final SharedPreferences prefs;
    public SavedPlaceRepository(Context c) { prefs = c.getSharedPreferences("places", Context.MODE_PRIVATE); }

    public void save(String title, String address) {
        try {
            JSONObject o = new JSONObject(prefs.getString("items", "{}"));
            o.put(title.trim(), address.trim());
            prefs.edit().putString("items", o.toString()).apply();
        } catch (Exception ignored) {}
    }

    public Map<String,String> all() {
        LinkedHashMap<String,String> out = new LinkedHashMap<>();
        try {
            JSONObject o = new JSONObject(prefs.getString("items", "{}"));
            Iterator<String> it = o.keys();
            while (it.hasNext()) { String k=it.next(); out.put(k, o.optString(k)); }
        } catch (Exception ignored) {}
        return out;
    }

    public Match findPersonal(String utterance) {
        String q = stripRelationWords(utterance);
        List<Match> candidates = new ArrayList<>();
        for (Map.Entry<String,String> e : all().entrySet()) {
            String t = PersianText.norm(e.getKey());
            if (q.equals(t)) candidates.add(new Match(e.getKey(), e.getValue(), 100));
            else if (q.contains(t) || t.contains(q)) candidates.add(new Match(e.getKey(), e.getValue(), 75));
        }
        candidates.sort((a,b)->Integer.compare(b.score,a.score));
        if (candidates.size()==1) return candidates.get(0);
        if (!candidates.isEmpty() && (candidates.size()==1 || candidates.get(0).score > candidates.get(1).score)) return candidates.get(0);
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

    public static final class Match {
        public final String title, address; public final int score;
        Match(String t,String a,int s){title=t;address=a;score=s;}
    }
}
