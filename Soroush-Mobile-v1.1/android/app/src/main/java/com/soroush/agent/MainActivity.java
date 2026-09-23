package com.soroush.agent;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity implements SnappBridge.Listener {
    private TextView chat, places, connectionStatus, tripStatus;
    private EditText input;
    private Switch autoPay;
    private SavedPlaceRepository repo;
    private String pendingRideDestination=null;
    private AgentCommand pendingSensitive=null;
    private String wizard=null, addCity=null, addTitle=null, addRawAddress=null;
    private static final int REQ_MAP_PICKER=4401;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        repo=new SavedPlaceRepository(this); SnappBridge.setListener(this);
        buildUi(); refreshPlaces(); refreshConnection();
        say("سلام! طبیعی بگو چه کاری برات انجام بدم.");
    }

    @Override protected void onResume(){ super.onResume(); refreshConnection(); }

    private void buildUi(){
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(24,22,24,18); root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL); root.setBackgroundColor(Color.WHITE);
        TextView title=t("Soroush Agent",25,true); title.setGravity(Gravity.CENTER); root.addView(title);

        LinearLayout statusRow=new LinearLayout(this); statusRow.setOrientation(LinearLayout.HORIZONTAL); statusRow.setGravity(Gravity.CENTER_VERTICAL);
        connectionStatus=t("",14,true); statusRow.addView(connectionStatus,new LinearLayout.LayoutParams(0,-2,1));
        Button acc=new Button(this); acc.setText("دسترسی Snapp"); acc.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))); statusRow.addView(acc);
        root.addView(statusRow);

        LinearLayout actions=new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL); actions.setGravity(Gravity.CENTER);
        Button add=new Button(this); add.setText("＋ افزودن موقعیت"); add.setOnClickListener(v->{ wizard="city"; say("این موقعیت در کدام شهر است؟"); }); actions.addView(add,new LinearLayout.LayoutParams(0,-2,1));
        Button open=new Button(this); open.setText("باز کردن Snapp"); open.setOnClickListener(v->SnappBridge.openSnapp(this)); actions.addView(open,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(actions);

        autoPay=new Switch(this); autoPay.setText("پرداخت خودکار"); autoPay.setTextSize(16); root.addView(autoPay);
        tripStatus=t("سفر فعال: ندارد",14,true); tripStatus.setPadding(0,8,0,8); root.addView(tripStatus);

        TextView placesTitle=t("موقعیت‌های من",18,true); root.addView(placesTitle);
        places=t("",15,false); places.setPadding(0,6,0,10); root.addView(places);

        TextView chatTitle=t("گفت‌وگو",18,true); root.addView(chatTitle);
        chat=t("",16,false); chat.setGravity(Gravity.RIGHT); ScrollView sv=new ScrollView(this); sv.addView(chat); root.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        input=new EditText(this); input.setHint("مثلاً: یه اسنپ بگیر برم پیش علی"); input.setTextSize(16); input.setSingleLine(false); input.setMaxLines(3); input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE); root.addView(input);
        Button send=new Button(this); send.setText("ارسال"); send.setOnClickListener(v->{String s=input.getText().toString().trim(); if(!s.isEmpty()){ input.setText(""); say("شما: "+s); handle(s);} }); root.addView(send);
        setContentView(root);
    }

    private TextView t(String s,int size,boolean bold){ TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(Color.rgb(25,25,25)); if(bold)v.setTypeface(null,1);v.setGravity(Gravity.RIGHT);return v; }
    private void say(String s){ chat.append((chat.length()>0?"\n\n":"")+s); }
    private void refreshPlaces(){ StringBuilder s=new StringBuilder(); Map<String,String> all=repo.all(); if(all.isEmpty()) s.append("هنوز موقعیتی ذخیره نشده."); else for(Map.Entry<String,String>e:all.entrySet())s.append("• ").append(e.getKey()).append(" — ").append(e.getValue()).append("\n"); places.setText(s.toString().trim()); }

    private void refreshConnection(){
        boolean enabled=isAccessibilityEnabled();
        connectionStatus.setText(enabled?"اتصال Snapp: فعال ✓":"اتصال Snapp: غیرفعال");
        connectionStatus.setTextColor(enabled?Color.rgb(20,120,50):Color.rgb(170,50,40));
    }
    private boolean isAccessibilityEnabled(){
        try {
            String enabled=Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if(enabled==null) return false;
            ComponentName me=new ComponentName(this, SnappAccessibilityService.class);
            String flat=me.flattenToString();
            for(String x:enabled.split(":")) if(x.equalsIgnoreCase(flat)) return true;
        } catch(Exception ignored){}
        return false;
    }

    private void handle(String raw){
        String n=PersianText.norm(raw);
        if (PersianText.hasAny(n,"لغو","بیخیال","نمیخوام") && pendingSensitive==null && wizard!=null) { wizard=null; addCity=addTitle=null; say("فرایند جاری لغو شد."); return; }
        if (pendingSensitive!=null) {
            if(PersianText.hasAny(n,"تایید","تأیید","بله","انجام بده")){ AgentCommand c=pendingSensitive; pendingSensitive=null; if(!isAccessibilityEnabled()){say("اول دسترسی Snapp را فعال کن؛ دکمه بالای صفحه را بزن.");return;} SnappBridge.launch(this,c); return; }
            if(PersianText.hasAny(n,"نه","لغو","بیخیال")){pendingSensitive=null;say("انجام نشد.");return;}
        }
        if (wizard!=null) { handleWizard(raw); return; }
        if (isCancelRide(n)) { pendingSensitive=new AgentCommand(AgentCommand.Type.CANCEL_RIDE,null,null); say("سفر فعال Snapp لغو شود؟ بگو «تأیید»."); return; }
        if (isPay(n)) { pendingSensitive=new AgentCommand(AgentCommand.Type.PAY_WALLET,null,null); say("هزینه سفر از کیف پول Snapp پرداخت شود؟ بگو «تأیید»."); return; }
        if (isAdd(n)) { wizard="city"; say("این موقعیت در کدام شهر است؟"); return; }
        if (isRide(n)) { parseRide(raw); return; }
        if (pendingRideDestination!=null) { acceptAddressForMissingPlace(raw); return; }
        say("درخواستت را کامل نفهمیدم. مثلاً بگو «میخوام برم پیش علی»، «آدرس جدید اضافه کن»، «سفر رو لغو کن» یا «هزینه رو پرداخت کن».");
    }

    private boolean isRide(String n){return PersianText.hasAny(n,"اسنپ","ماشین","تاکسی","میخوام برم","می خوام برم","بریم","برم پیش");}
    private boolean isAdd(String n){return PersianText.hasAny(n,"آدرس اضافه","ادرس اضافه","موقعیت اضافه","آدرس جدید","ادرس جدید","مکان جدید","ذخیره کن") && !isRide(n);}
    private boolean isCancelRide(String n){return PersianText.hasAny(n,"لغو سفر","سفر رو لغو","اسنپ رو لغو","اسنپ رو کنسل","ماشین رو لغو","ماشین رو کنسل");}
    private boolean isPay(String n){return PersianText.hasAny(n,"هزینه رو پرداخت","هزینه را پرداخت","پرداخت کن","از کیف پول پرداخت","کرایه رو پرداخت","کرایه را پرداخت");}

    private void parseRide(String raw){ String dest=extractDestination(raw); if(dest.isEmpty()){wizard="rideDest"; say("کجا می‌خوای بری؟"); return;} resolveDestination(dest); }
    private String extractDestination(String s){ String n=PersianText.norm(s); String[] markers={"میخوام برم پیش ","می خوام برم پیش ","برم پیش ","بریم پیش ","به خونه ","به خانه ","به منزل "," به ","برم ","بریم "}; for(String m:markers){int i=n.lastIndexOf(PersianText.norm(m));if(i>=0){String x=n.substring(i+PersianText.norm(m).length()).trim();x=x.replaceAll("(لطفا|لطفاً)$","").trim();if(!x.isEmpty())return x;}} return ""; }
    private void resolveDestination(String dest){ SavedPlaceRepository.Match m=repo.findPersonal(dest); if(m!=null){ askRide(m.title,m.address,m.searchAddress,m.lat,m.lon); return; } String core=SavedPlaceRepository.stripRelationWords(dest); pendingRideDestination=core; wizard="missingPlaceAddress"; say("موقعیتی با عنوان «"+core+"» ذخیره نشده. اگر منظورت یک جای مشخصه، آدرسش رو بده تا برای همین سفر استفاده کنم؛ یا بگو «لغو»."); }
    private void acceptAddressForMissingPlace(String address){ askRide(pendingRideDestination,address,address,Double.NaN,Double.NaN); pendingRideDestination=null; wizard=null; }
    private void askRide(String title,String address,String searchAddress,double lat,double lon){
        String q=(searchAddress==null||searchAddress.trim().isEmpty())?address:searchAddress;
        pendingSensitive=new AgentCommand(AgentCommand.Type.REQUEST_RIDE,"CURRENT",q,lat,lon);
        String point=(!Double.isNaN(lat)&&!Double.isNaN(lon))?"\nنقطه نقشه ذخیره شده ✓":"";
        say("مقصد «"+title+"» پیدا شد:\n"+address+point+"\nاز موقعیت فعلی درخواست Snapp بدم؟ بگو «تأیید».");
    }

    private void handleWizard(String raw){
        if("rideDest".equals(wizard)){wizard=null; resolveDestination(raw);return;}
        if("missingPlaceAddress".equals(wizard)){ wizard=null; String title=pendingRideDestination; pendingRideDestination=null; askRide(title,raw); return; }
        if("city".equals(wizard)){addCity=raw.trim();wizard="title";say("چه عنوانی براش ذخیره کنم؟");return;}
        if("title".equals(wizard)){addTitle=raw.trim();wizard="address";say("آدرس «"+addTitle+"» رو در «"+addCity+"» بفرست.");return;}
        if("address".equals(wizard)){
            addRawAddress=raw.trim();
            String full=addCity+" "+addRawAddress;
            wizard="map";
            say("آدرس را روی نقشه باز می‌کنم. پین را روی نقطه دقیق بگذار و ثبت کن.");
            Intent i=new Intent(this,MapPickerActivity.class); i.putExtra("query",full); startActivityForResult(i,REQ_MAP_PICKER);
            return;
        }
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=REQ_MAP_PICKER) return;
        if(resultCode==RESULT_OK && data!=null){
            double lat=data.getDoubleExtra("lat",Double.NaN);
            double lon=data.getDoubleExtra("lon",Double.NaN);
            String canonical=data.getStringExtra("canonicalAddress");
            String shown=addCity+" "+(addRawAddress==null?"":addRawAddress);
            repo.save(addTitle,shown,canonical,lat,lon);
            refreshPlaces();
            say("«"+addTitle+"» با نقطه دقیق نقشه ذخیره شد.\n"+canonical);
            wizard=null; addCity=addTitle=addRawAddress=null;
        }else{
            wizard="address";
            say("ثبت نقشه انجام نشد. آدرس را دوباره بفرست یا فرایند را لغو کن.");
        }
    }

    @Override public void onStatus(final String text){ runOnUiThread(()->{ say(text); if(text.contains("درخواست خودرو")||text.contains("راننده")||text.contains("سفر")) tripStatus.setText("وضعیت سفر: "+text.replace('\n',' ')); }); }
    @Override protected void onDestroy(){ super.onDestroy(); SnappBridge.setListener(null); }
}
