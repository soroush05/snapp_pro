package com.soroush.agent;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MapPickerActivity extends Activity {
    private WebView map;
    private TextView status;
    private LinearLayout candidatesBox;
    private Button save;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private double lat=Double.NaN,lon=Double.NaN;
    private String canonicalAddress="",query="",city="";

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        query=getIntent().getStringExtra("query"); if(query==null)query="";
        city=getIntent().getStringExtra("city"); if(city==null)city="";
        buildUi(); loadMap(32.0,53.0,5); geocodeSmart(query,city);
    }

    private void buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(16,16,16,16);root.setBackgroundColor(Color.WHITE);
        TextView title=new TextView(this);title.setText("تأیید موقعیت روی نقشه");title.setTextSize(21);title.setGravity(Gravity.RIGHT);root.addView(title);
        TextView hint=new TextView(this);hint.setText("نتیجه پیشنهادی را بررسی کن. اگر دقیق نیست، روی نقطه درست نقشه لمس کن. ثبت فقط بعد از تأیید خودت انجام می‌شود.");hint.setTextSize(14);hint.setGravity(Gravity.RIGHT);root.addView(hint);
        status=new TextView(this);status.setText("در حال جست‌وجوی چندمرحله‌ای آدرس…");status.setTextSize(14);status.setGravity(Gravity.RIGHT);root.addView(status);
        candidatesBox=new LinearLayout(this);candidatesBox.setOrientation(LinearLayout.VERTICAL);root.addView(candidatesBox);
        map=new WebView(this);WebSettings s=map.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);map.addJavascriptInterface(new JsBridge(),"Android");root.addView(map,new LinearLayout.LayoutParams(-1,0,1));
        save=new Button(this);save.setText("تأیید همین نقطه");save.setEnabled(false);save.setOnClickListener(v->finishWithPoint());root.addView(save);setContentView(root);
    }

    private void loadMap(double la,double lo,int zoom){
        String html="<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1.0'>"+
                "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'/>"+
                "<style>html,body,#m{height:100%;margin:0}.leaflet-control-attribution{font-size:9px}</style></head><body><div id='m'></div>"+
                "<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script><script>"+
                "var map=L.map('m').setView(["+la+","+lo+"],"+zoom+");"+
                "L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);"+
                "var marker=null;function setPoint(a,o,z){if(marker){marker.setLatLng([a,o]);}else{marker=L.marker([a,o]).addTo(map);}map.setView([a,o],z||17);}"+
                "map.on('click',function(e){setPoint(e.latlng.lat,e.latlng.lng,17);Android.onPointSelected(e.latlng.lat,e.latlng.lng);});"+
                "</script></body></html>";
        map.loadDataWithBaseURL("https://localhost/",html,"text/html","UTF-8",null);
    }

    private void geocodeSmart(String q,String c){
        if(q.trim().isEmpty()){status.setText("روی نقشه نقطه را انتخاب کن.");return;}
        executor.execute(()->{
            try{
                List<Candidate> merged=new ArrayList<>();
                LinkedHashSet<String> queries=new LinkedHashSet<>();
                queries.add(q);
                String normalized=PersianText.norm(q);
                String[] parts=normalized.split(" ");
                if(parts.length>=2 && !normalized.contains("شهید ")){
                    StringBuilder v=new StringBuilder();for(int i=0;i<parts.length;i++){if(i==parts.length-1)v.append("شهید ");v.append(parts[i]).append(' ');}queries.add(v.toString().trim());
                }
                if(!c.trim().isEmpty()&&!normalized.startsWith(PersianText.norm(c)+" "))queries.add(c+" "+q);
                // Progressive fallback keeps parent context instead of jumping to unrelated cities.
                if(parts.length>3){StringBuilder parent=new StringBuilder();for(int i=0;i<parts.length-1;i++)parent.append(parts[i]).append(' ');queries.add(parent.toString().trim());}

                for(String qq:queries){
                    List<Candidate> got=search(qq,c);
                    for(Candidate x:got){boolean dup=false;for(Candidate y:merged)if(distanceMeters(x.lat,x.lon,y.lat,y.lon)<25){dup=true;break;}if(!dup)merged.add(x);}
                    if(merged.size()>=8)break;
                }
                for(Candidate x:merged)x.score=scoreCandidate(q,c,x.display);
                merged.sort((a,b)->Integer.compare(b.score,a.score));
                final List<Candidate> top=merged.size()>5?new ArrayList<>(merged.subList(0,5)):merged;
                runOnUiThread(()->showCandidates(top));
            }catch(Exception e){runOnUiThread(()->status.setText("جست‌وجوی نقشه انجام نشد؛ نقطه را دستی انتخاب کن."));}
        });
    }

    private List<Candidate> search(String q,String c) throws Exception{
        StringBuilder u=new StringBuilder("https://nominatim.openstreetmap.org/search?format=json&limit=8&addressdetails=1&accept-language=fa&countrycodes=ir&q=").append(URLEncoder.encode(q,"UTF-8"));
        double[] box=cityBounds(c);
        if(box!=null){
            // Nominatim viewbox format: left,top,right,bottom (lon,lat,lon,lat).
            u.append("&viewbox=").append(box[2]).append(',').append(box[1]).append(',').append(box[3]).append(',').append(box[0]).append("&bounded=1");
        }else if(!c.trim().isEmpty())u.append("&city=").append(URLEncoder.encode(c,"UTF-8"));
        JSONArray a=new JSONArray(get(u.toString()));ArrayList<Candidate> out=new ArrayList<>();
        for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);out.add(new Candidate(Double.parseDouble(o.getString("lat")),Double.parseDouble(o.getString("lon")),o.optString("display_name",q)));}
        return out;
    }

    private double[] cityBounds(String c){
        if(c==null||c.trim().isEmpty())return null;
        try{
            String u="https://nominatim.openstreetmap.org/search?format=json&limit=1&addressdetails=1&countrycodes=ir&accept-language=fa&q="+URLEncoder.encode(c,"UTF-8");
            JSONArray a=new JSONArray(get(u));if(a.length()==0)return null;JSONArray b=a.getJSONObject(0).optJSONArray("boundingbox");if(b==null||b.length()<4)return null;
            // south, north, west, east
            return new double[]{Double.parseDouble(b.getString(0)),Double.parseDouble(b.getString(1)),Double.parseDouble(b.getString(2)),Double.parseDouble(b.getString(3))};
        }catch(Exception e){return null;}
    }

    private int scoreCandidate(String q,String c,String display){
        int score=PersianText.addressSimilarity(q,display);
        if(!c.trim().isEmpty()){
            String nc=PersianText.addressNorm(c),nd=PersianText.addressNorm(display);
            if(nd.contains(nc))score+=60;else score-=80;
        }
        return score;
    }

    private void showCandidates(List<Candidate> list){
        candidatesBox.removeAllViews();
        if(list.isEmpty()){status.setText("آدرس دقیق پیدا نشد. برای جلوگیری از انتخاب اشتباه، نقطه را دستی روی نقشه انتخاب کن.");return;}
        status.setText("چند نتیجه پیدا شد. گزینه اول فقط پیشنهاد است؛ قبل از ثبت، خود نقطه را روی نقشه بررسی کن و در صورت نیاز پین را جابه‌جا کن.");
        for(int i=0;i<Math.min(3,list.size());i++){
            Candidate c=list.get(i);Button b=new Button(this);b.setText((i+1)+". "+shorten(c.display));b.setAllCaps(false);b.setOnClickListener(v->selectCandidate(c));candidatesBox.addView(b);
        }
        selectCandidate(list.get(0));
    }

    private String shorten(String s){return s.length()>105?s.substring(0,102)+"…":s;}
    private void selectCandidate(Candidate c){lat=c.lat;lon=c.lon;canonicalAddress=c.display;map.evaluateJavascript("setPoint("+lat+","+lon+",17)",null);status.setText(c.display);save.setEnabled(true);}

    private void reverse(double la,double lo){
        executor.execute(()->{try{String url="https://nominatim.openstreetmap.org/reverse?format=json&accept-language=fa&lat="+la+"&lon="+lo+"&zoom=18&addressdetails=1";JSONObject o=new JSONObject(get(url));String display=o.optString("display_name",query);canonicalAddress=display;runOnUiThread(()->status.setText(display));}catch(Exception e){canonicalAddress=query;}});
    }

    private String get(String u)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();c.setConnectTimeout(7000);c.setReadTimeout(7000);c.setRequestProperty("User-Agent","SoroushAgent/2.0-alpha personal-use Android app");c.setRequestProperty("Accept","application/json");try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8);}finally{c.disconnect();}}
    private void finishWithPoint(){if(Double.isNaN(lat)||Double.isNaN(lon))return;Intent data=new Intent();data.putExtra("lat",lat);data.putExtra("lon",lon);data.putExtra("canonicalAddress",canonicalAddress.isEmpty()?query:canonicalAddress);data.putExtra("rawQuery",query);setResult(RESULT_OK,data);finish();}
    public final class JsBridge{@JavascriptInterface public void onPointSelected(double la,double lo){lat=la;lon=lo;canonicalAddress=query;runOnUiThread(()->{save.setEnabled(true);status.setText("در حال خواندن آدرس این نقطه…");});reverse(la,lo);}}
    @Override protected void onDestroy(){super.onDestroy();executor.shutdownNow();}

    private static double distanceMeters(double a,double o,double b,double p){double R=6371000,d1=Math.toRadians(b-a),d2=Math.toRadians(p-o),x=Math.sin(d1/2)*Math.sin(d1/2)+Math.cos(Math.toRadians(a))*Math.cos(Math.toRadians(b))*Math.sin(d2/2)*Math.sin(d2/2);return R*2*Math.atan2(Math.sqrt(x),Math.sqrt(1-x));}
    private static final class Candidate{final double lat,lon;final String display;int score;Candidate(double a,double o,String d){lat=a;lon=o;display=d;}}
}
