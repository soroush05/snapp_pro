package com.soroush.agent;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared map picker for ride origin, ride destination and Saved Places.
 * Search results are candidates only; final coordinates always require an explicit user confirmation.
 */
public class MapPickerActivity extends Activity {
    private WebView map;
    private TextView status,modeLabel;
    private LinearLayout candidatesBox;
    private Button save;
    private EditText searchInput;
    private final ExecutorService executor=Executors.newFixedThreadPool(2);
    private double lat=Double.NaN,lon=Double.NaN;
    private String canonicalAddress="",query="",city="",resolvedCity="",purpose="",selectionSource="NONE";
    private boolean mapReady=false,manualPin=false;
    private double pendingLat=Double.NaN,pendingLon=Double.NaN;
    private long searchGeneration=0;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        Intent in=getIntent();
        query=s(in.getStringExtra("query"));city=s(in.getStringExtra("city"));purpose=s(in.getStringExtra("purpose"));
        double initialLat=in.getDoubleExtra("initialLat",Double.NaN),initialLon=in.getDoubleExtra("initialLon",Double.NaN);
        buildUi();
        if(!Double.isNaN(initialLat)&&!Double.isNaN(initialLon)){loadMap(initialLat,initialLon,16);lat=initialLat;lon=initialLon;setMapPoint(lat,lon);save.setEnabled(true);selectionSource="INITIAL_POINT";}
        else loadMap(32.0,53.0,5);
        searchInput.setText(query);
        if(!query.trim().isEmpty())geocodeSmart(query,city);else status.setText("نام یا آدرس را جست‌وجو کن، یا نقطه را مستقیماً روی نقشه انتخاب کن.");
    }

    private void buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(14,14,14,14);root.setBackgroundColor(Color.WHITE);root.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
        TextView title=tv("انتخاب و تأیید موقعیت",21);title.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);root.addView(title);
        modeLabel=tv(modeText(),13);modeLabel.setTextColor(Color.rgb(95,95,100));root.addView(modeLabel);
        TextView hint=tv("نتیجه جست‌وجو فقط پیشنهاد است. نتیجه را انتخاب کن، نقطه را روی نقشه بررسی/جابجا کن و در پایان خودت تأیید کن.",14);root.addView(hint);

        LinearLayout searchRow=new LinearLayout(this);searchRow.setOrientation(LinearLayout.HORIZONTAL);searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchInput=new EditText(this);searchInput.setHint("نام مکان یا آدرس…");searchInput.setSingleLine(false);searchInput.setMaxLines(2);searchInput.setGravity(Gravity.RIGHT);
        Button searchBtn=new Button(this);searchBtn.setText("جست‌وجو");searchBtn.setOnClickListener(v->{String q=searchInput.getText().toString().trim();query=q;geocodeSmart(q,city);});
        searchRow.addView(searchInput,new LinearLayout.LayoutParams(0,-2,1));searchRow.addView(searchBtn);root.addView(searchRow);

        status=tv("",13);status.setTextColor(Color.rgb(70,70,75));root.addView(status);
        candidatesBox=new LinearLayout(this);candidatesBox.setOrientation(LinearLayout.VERTICAL);root.addView(candidatesBox);

        map=new WebView(this);WebSettings ws=map.getSettings();ws.setJavaScriptEnabled(true);ws.setDomStorageEnabled(true);ws.setAllowFileAccess(true);
        map.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView view,String url){mapReady=true;if(!Double.isNaN(pendingLat)&&!Double.isNaN(pendingLon)){double a=pendingLat,o=pendingLon;pendingLat=pendingLon=Double.NaN;setMapPoint(a,o);}}
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){if(request!=null&&request.isForMainFrame())status.setText("نقشه کامل بارگذاری نشد. اینترنت را بررسی کن؛ جست‌وجو همچنان قابل استفاده است.");}
        });
        map.addJavascriptInterface(new JsBridge(),"Android");root.addView(map,new LinearLayout.LayoutParams(-1,0,1));

        save=new Button(this);save.setText("تأیید همین نقطه");save.setEnabled(false);save.setOnClickListener(v->finishWithPoint());root.addView(save);
        setContentView(root);
    }

    private TextView tv(String x,int size){TextView v=new TextView(this);v.setText(x);v.setTextSize(size);v.setGravity(Gravity.RIGHT);v.setPadding(4,4,4,4);return v;}
    private String modeText(){if("rideOrigin".equals(purpose))return "برای مبدأ سفر";if("rideDestination".equals(purpose))return "برای مقصد سفر";if("add".equals(purpose))return "برای موقعیت ذخیره‌شده جدید";if("edit".equals(purpose))return "برای اصلاح موقعیت ذخیره‌شده";return "انتخاب موقعیت";}

    private void loadMap(double la,double lo,int zoom){
        // Leaflet/tiles are online. The activity itself still opens even if a tile/CDN request fails.
        String html="<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1.0,maximum-scale=1.0,user-scalable=no'>"+
                "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css' crossorigin=''/>"+
                "<style>html,body,#m{height:100%;margin:0;background:#eef0f2}.leaflet-control-attribution{font-size:8px}</style></head><body><div id='m'></div>"+
                "<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js' crossorigin=''></script><script>"+
                "var map=L.map('m',{zoomControl:true}).setView(["+la+","+lo+"],"+zoom+");"+
                "L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);"+
                "var marker=null;function setPoint(a,o,z){if(marker){marker.setLatLng([a,o]);}else{marker=L.circleMarker([a,o],{radius:10,weight:3,fillOpacity:.65}).addTo(map);}map.setView([a,o],z||17);}"+
                "map.on('click',function(e){setPoint(e.latlng.lat,e.latlng.lng,17);Android.onPointSelected(e.latlng.lat,e.latlng.lng);});"+
                "</script></body></html>";
        map.loadDataWithBaseURL("https://localhost/",html,"text/html","UTF-8",null);
    }

    private void geocodeSmart(String q,String explicitCity){
        final String request=q==null?"":q.trim();if(request.isEmpty()){status.setText("برای جست‌وجو یک نام یا آدرس بنویس، یا نقطه را روی نقشه انتخاب کن.");return;}
        final long gen=++searchGeneration;status.setText("در حال بررسی سلسله‌مراتبی آدرس…");candidatesBox.removeAllViews();save.setEnabled(!Double.isNaN(lat)&&!Double.isNaN(lon));
        executor.execute(()->{
            try{
                String lockedCity=PersianText.norm(explicitCity);
                AddressComponents parsed=AddressParser.parse(request,lockedCity);
                if(lockedCity.isEmpty()&&!parsed.city.isEmpty()&&cityBounds(parsed.city)!=null)lockedCity=parsed.city; // validate provisional city before locking.
                LinkedHashMap<String,LocationCandidate> merged=new LinkedHashMap<>();
                for(String qq:AddressParser.queryPlan(request,lockedCity)){
                    for(LocationCandidate c:searchNominatim(qq,lockedCity))merge(merged,c);
                    // Secondary provider is best-effort; failure must never break the primary search.
                    try{for(LocationCandidate c:searchPhoton(qq,lockedCity))merge(merged,c);}catch(Exception ignored){}
                    if(merged.size()>=16)break;
                }
                ArrayList<LocationCandidate> ranked=new ArrayList<>();
                for(LocationCandidate c:merged.values()){LocationScorer.score(parsed,c,lockedCity);if(!c.hardRejected)ranked.add(c);}
                ranked.sort((a,b)->Double.compare(b.score,a.score));if(ranked.size()>7)ranked=new ArrayList<>(ranked.subList(0,7));
                final ArrayList<LocationCandidate> result=ranked;final String finalCity=lockedCity;
                runOnUiThread(()->{if(gen==searchGeneration)showCandidates(result,request,finalCity);});
            }catch(Exception e){runOnUiThread(()->{if(gen==searchGeneration)status.setText("جست‌وجو کامل نشد. می‌تونی عبارت دقیق‌تری وارد کنی یا نقطه را دستی روی نقشه انتخاب کنی.");});}
        });
    }

    private List<LocationCandidate> searchNominatim(String q,String lockedCity)throws Exception{
        StringBuilder u=new StringBuilder("https://nominatim.openstreetmap.org/search?format=json&limit=8&addressdetails=1&accept-language=fa&countrycodes=ir&q=").append(URLEncoder.encode(q,"UTF-8"));
        double[] box=cityBounds(lockedCity);if(box!=null)u.append("&viewbox=").append(box[2]).append(',').append(box[1]).append(',').append(box[3]).append(',').append(box[0]).append("&bounded=1");
        JSONArray a=new JSONArray(get(u.toString()));ArrayList<LocationCandidate> out=new ArrayList<>();
        for(int i=0;i<a.length();i++){
            JSONObject o=a.getJSONObject(i),ad=o.optJSONObject("address");
            String c=first(ad,"city","town","village","municipality","county");String nb=first(ad,"suburb","neighbourhood","quarter","city_district");String road=first(ad,"road","pedestrian","residential","footway");
            String name=o.optString("name",first(ad,"amenity","building","shop","tourism"));
            out.add(new LocationCandidate(Double.parseDouble(o.getString("lat")),Double.parseDouble(o.getString("lon")),o.optString("display_name",q),c,nb,road,name,"nominatim"));
        }
        return out;
    }

    private List<LocationCandidate> searchPhoton(String q,String lockedCity)throws Exception{
        String url="https://photon.komoot.io/api/?limit=8&q="+URLEncoder.encode((lockedCity.isEmpty()?"":lockedCity+" ")+q,"UTF-8");
        JSONObject root=new JSONObject(get(url));JSONArray f=root.optJSONArray("features");ArrayList<LocationCandidate> out=new ArrayList<>();if(f==null)return out;
        for(int i=0;i<f.length();i++){
            JSONObject x=f.optJSONObject(i);if(x==null)continue;JSONObject g=x.optJSONObject("geometry"),p=x.optJSONObject("properties");if(g==null||p==null)continue;JSONArray co=g.optJSONArray("coordinates");if(co==null||co.length()<2)continue;
            double lo=co.optDouble(0,Double.NaN),la=co.optDouble(1,Double.NaN);if(Double.isNaN(la)||Double.isNaN(lo))continue;
            String country=PersianText.norm(p.optString("country",""));if(!country.isEmpty()&&!country.contains("ایران")&&!country.contains("iran"))continue;
            String name=p.optString("name","");String c=first(p,"city","locality","district","county");String road=first(p,"street","road");String nb=first(p,"district","suburb","locality");
            String display=joinNonEmpty(name,road,nb,c,p.optString("state",""),p.optString("country",""));
            out.add(new LocationCandidate(la,lo,display,c,nb,road,name,"photon"));
        }
        return out;
    }

    private void merge(Map<String,LocationCandidate> m,LocationCandidate c){
        for(LocationCandidate old:m.values())if(distanceMeters(c.lat,c.lon,old.lat,old.lon)<35)return;
        m.put(c.lat+","+c.lon,c);
    }

    private double[] cityBounds(String c){
        if(c==null||c.trim().isEmpty())return null;
        try{
            String u="https://nominatim.openstreetmap.org/search?format=json&limit=3&addressdetails=1&countrycodes=ir&accept-language=fa&q="+URLEncoder.encode(c,"UTF-8");
            JSONArray a=new JSONArray(get(u));
            for(int i=0;i<a.length();i++){
                JSONObject o=a.getJSONObject(i);String type=PersianText.norm(o.optString("type",""));JSONObject ad=o.optJSONObject("address");String name=o.optString("display_name","");
                if(!(type.contains("city")||type.contains("town")||type.contains("administrative")||PersianText.addressNorm(name).contains(PersianText.addressNorm(c))))continue;
                JSONArray b=o.optJSONArray("boundingbox");if(b!=null&&b.length()>=4)return new double[]{Double.parseDouble(b.getString(0)),Double.parseDouble(b.getString(1)),Double.parseDouble(b.getString(2)),Double.parseDouble(b.getString(3))};
            }
        }catch(Exception ignored){}
        return null;
    }

    private void showCandidates(List<LocationCandidate> list,String request,String lockedCity){
        candidatesBox.removeAllViews();
        if(list.isEmpty()){status.setText("نتیجه قابل اتکایی پیدا نشد. شهر/محدوده بیشتری اضافه کن یا نقطه را دستی انتخاب کن.");return;}
        double first=list.get(0).score,second=list.size()>1?list.get(1).score:-999,margin=first-second;
        String ambiguity=(list.size()>1&&margin<8)?" نتیجه‌های اول به هم نزدیک‌اند؛ انتخاب خودکار انجام نمی‌شود.":"";
        status.setText("نتیجه‌ها بر اساس شهر، ترتیب اجزای آدرس و تطابق نام رتبه‌بندی شده‌اند."+ambiguity+" یکی را انتخاب کن و بعد نقطه را روی نقشه تأیید کن.");
        for(int i=0;i<Math.min(4,list.size());i++){
            LocationCandidate c=list.get(i);Button b=new Button(this);String evidence=c.evidence.isEmpty()?"":"  •  "+c.evidence;b.setText((i+1)+". "+shorten(c.display)+evidence);b.setAllCaps(false);b.setGravity(Gravity.RIGHT);b.setOnClickListener(v->selectCandidate(c));candidatesBox.addView(b);
        }
    }

    private void selectCandidate(LocationCandidate c){
        lat=c.lat;lon=c.lon;canonicalAddress=c.display;resolvedCity=c.city;selectionSource="MAP_SEARCH_"+c.source.toUpperCase(Locale.ROOT);manualPin=false;setMapPoint(lat,lon);status.setText("این فقط نقطه پیشنهادی است: "+c.display+"\nاگر دقیق نیست روی نقطه درست نقشه لمس کن.");save.setEnabled(true);
    }
    private void setMapPoint(double la,double lo){if(!mapReady){pendingLat=la;pendingLon=lo;return;}map.evaluateJavascript("setPoint("+la+","+lo+",17)",null);}

    private void reverse(double la,double lo){
        final long captured=searchGeneration;
        executor.execute(()->{try{
            String url="https://nominatim.openstreetmap.org/reverse?format=json&accept-language=fa&lat="+la+"&lon="+lo+"&zoom=18&addressdetails=1";JSONObject o=new JSONObject(get(url));String display=o.optString("display_name",query);JSONObject ad=o.optJSONObject("address");String rc=first(ad,"city","town","village","municipality","county");
            // Reverse geocoding may update only the descriptive text. It must never move the confirmed coordinates.
            if(lat==la&&lon==lo){canonicalAddress=display;if(!rc.isEmpty())resolvedCity=rc;runOnUiThread(()->{if(lat==la&&lon==lo)status.setText("نقطه دستی: "+display+"\nمختصات انتخاب‌شده ثابت می‌ماند؛ آدرس فقط توضیح این نقطه است.");});}
        }catch(Exception e){if(lat==la&&lon==lo)canonicalAddress=query;}});
    }

    private String get(String u)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();c.setConnectTimeout(6500);c.setReadTimeout(7500);c.setRequestProperty("User-Agent","SoroushAgent/2.1-alpha4 personal-use Android app; map-matching");c.setRequestProperty("Accept","application/json");
        int code=c.getResponseCode();InputStream src=code>=200&&code<300?c.getInputStream():c.getErrorStream();if(src==null)throw new IOException("HTTP "+code);
        try(InputStream in=src;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);if(code<200||code>=300)throw new IOException("HTTP "+code);return new String(out.toByteArray(),StandardCharsets.UTF_8);}finally{c.disconnect();}
    }

    private void finishWithPoint(){
        if(Double.isNaN(lat)||Double.isNaN(lon))return;Intent data=new Intent();data.putExtra("lat",lat);data.putExtra("lon",lon);data.putExtra("canonicalAddress",canonicalAddress.isEmpty()?query:canonicalAddress);data.putExtra("rawQuery",query);data.putExtra("city",resolvedCity.isEmpty()?city:resolvedCity);data.putExtra("source","USER_CONFIRMED_PIN");data.putExtra("selectionSource",selectionSource);data.putExtra("manualPin",manualPin);data.putExtra("verifiedAt",System.currentTimeMillis());setResult(RESULT_OK,data);finish();
    }

    public final class JsBridge{
        @JavascriptInterface public void onPointSelected(double la,double lo){lat=la;lon=lo;manualPin=true;selectionSource="MANUAL_PIN";canonicalAddress=query;runOnUiThread(()->{save.setEnabled(true);status.setText("در حال خواندن آدرس نقطه‌ای که خودت انتخاب کردی…");});reverse(la,lo);}
    }

    @Override protected void onDestroy(){super.onDestroy();executor.shutdownNow();}
    private static String s(String x){return x==null?"":x;}
    private static String first(JSONObject o,String...keys){if(o==null)return "";for(String k:keys){String v=o.optString(k,"");if(v!=null&&!v.trim().isEmpty())return v.trim();}return "";}
    private static String joinNonEmpty(String...xs){StringBuilder b=new StringBuilder();for(String x:xs)if(x!=null&&!x.trim().isEmpty()){if(b.length()>0)b.append("، ");b.append(x.trim());}return b.toString();}
    private static String shorten(String s){return s==null?"":(s.length()>100?s.substring(0,97)+"…":s);}
    private static double distanceMeters(double a,double o,double b,double p){double R=6371000,d1=Math.toRadians(b-a),d2=Math.toRadians(p-o),x=Math.sin(d1/2)*Math.sin(d1/2)+Math.cos(Math.toRadians(a))*Math.cos(Math.toRadians(b))*Math.sin(d2/2)*Math.sin(d2/2);return R*2*Math.atan2(Math.sqrt(x),Math.sqrt(1-x));}
}
