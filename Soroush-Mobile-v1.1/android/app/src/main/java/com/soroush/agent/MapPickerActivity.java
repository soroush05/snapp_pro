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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MapPickerActivity extends Activity {
    private WebView map;
    private TextView status;
    private Button save;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private double lat=Double.NaN, lon=Double.NaN;
    private String canonicalAddress="";
    private String query="";

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        query=getIntent().getStringExtra("query"); if(query==null) query="";
        buildUi();
        loadMap(32.0,53.0,5);
        geocode(query);
    }

    private void buildUi(){
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(16,16,16,16); root.setBackgroundColor(Color.WHITE);
        TextView title=new TextView(this); title.setText("تأیید موقعیت روی نقشه"); title.setTextSize(21); title.setGravity(Gravity.RIGHT); root.addView(title);
        TextView hint=new TextView(this); hint.setText("نقطه را روی نقشه لمس کن تا پین دقیق جابه‌جا شود. سپس «ثبت این نقطه» را بزن."); hint.setTextSize(14); hint.setGravity(Gravity.RIGHT); root.addView(hint);
        status=new TextView(this); status.setText("در حال پیدا کردن آدرس…"); status.setTextSize(14); status.setGravity(Gravity.RIGHT); root.addView(status);
        map=new WebView(this); WebSettings s=map.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); map.addJavascriptInterface(new JsBridge(),"Android"); root.addView(map,new LinearLayout.LayoutParams(-1,0,1));
        save=new Button(this); save.setText("ثبت این نقطه"); save.setEnabled(false); save.setOnClickListener(v->finishWithPoint()); root.addView(save);
        setContentView(root);
    }

    private void loadMap(double la,double lo,int zoom){
        String html="<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1.0'>"+
                "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'/>"+
                "<style>html,body,#m{height:100%;margin:0} .leaflet-control-attribution{font-size:9px}</style></head><body><div id='m'></div>"+
                "<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script><script>"+
                "var map=L.map('m').setView(["+la+","+lo+"],"+zoom+");"+
                "L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);"+
                "var marker=null; function setPoint(a,o,z){ if(marker){marker.setLatLng([a,o]);}else{marker=L.marker([a,o]).addTo(map);} map.setView([a,o],z||17);}"+
                "map.on('click',function(e){setPoint(e.latlng.lat,e.latlng.lng,17); Android.onPointSelected(e.latlng.lat,e.latlng.lng);});"+
                "</script></body></html>";
        map.loadDataWithBaseURL("https://localhost/",html,"text/html","UTF-8",null);
    }

    private void geocode(String q){
        if(q.trim().isEmpty()){ status.setText("روی نقشه نقطه را انتخاب کن."); return; }
        executor.execute(()->{
            try{
                String url="https://nominatim.openstreetmap.org/search?format=json&limit=1&accept-language=fa&q="+URLEncoder.encode(q,"UTF-8");
                String body=get(url); JSONArray a=new JSONArray(body);
                if(a.length()==0){ runOnUiThread(()->status.setText("آدرس دقیق پیدا نشد؛ نقطه را دستی روی نقشه انتخاب کن.")); return; }
                JSONObject o=a.getJSONObject(0); double la=Double.parseDouble(o.getString("lat")); double lo=Double.parseDouble(o.getString("lon")); String display=o.optString("display_name",q);
                lat=la; lon=lo; canonicalAddress=display;
                runOnUiThread(()->{ map.evaluateJavascript("setPoint("+la+","+lo+",17)",null); status.setText(display); save.setEnabled(true); });
            }catch(Exception e){ runOnUiThread(()->status.setText("جست‌وجوی نقشه انجام نشد؛ نقطه را دستی انتخاب کن.")); }
        });
    }

    private void reverse(double la,double lo){
        executor.execute(()->{
            try{
                String url="https://nominatim.openstreetmap.org/reverse?format=json&accept-language=fa&lat="+la+"&lon="+lo+"&zoom=18&addressdetails=1";
                JSONObject o=new JSONObject(get(url)); String display=o.optString("display_name",query);
                canonicalAddress=display;
                runOnUiThread(()->status.setText(display));
            }catch(Exception e){ canonicalAddress=query; }
        });
    }

    private String get(String u) throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection(); c.setConnectTimeout(7000); c.setReadTimeout(7000); c.setRequestProperty("User-Agent","SoroushAgent/1.4 personal-use Android app"); c.setRequestProperty("Accept","application/json");
        try(InputStream in=c.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[4096]; int n; while((n=in.read(buf))!=-1) out.write(buf,0,n); return new String(out.toByteArray(),StandardCharsets.UTF_8);
        } finally { c.disconnect(); }
    }

    private void finishWithPoint(){
        if(Double.isNaN(lat)||Double.isNaN(lon)) return;
        Intent data=new Intent(); data.putExtra("lat",lat); data.putExtra("lon",lon); data.putExtra("canonicalAddress",canonicalAddress.isEmpty()?query:canonicalAddress); setResult(RESULT_OK,data); finish();
    }

    public final class JsBridge {
        @JavascriptInterface public void onPointSelected(double la,double lo){ lat=la; lon=lo; canonicalAddress=query; runOnUiThread(()->{save.setEnabled(true); status.setText("در حال خواندن آدرس این نقطه…");}); reverse(la,lo); }
    }

    @Override protected void onDestroy(){ super.onDestroy(); executor.shutdownNow(); }
}
