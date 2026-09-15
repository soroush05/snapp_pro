package com.soroush.agent;

import android.app.Activity;
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
    private TextView chat, places;
    private EditText input;
    private Switch autoPay;
    private SavedPlaceRepository repo;
    private String pendingRideDestination=null;
    private AgentCommand pendingSensitive=null;
    private String wizard=null, addCity=null, addTitle=null;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        repo=new SavedPlaceRepository(this); SnappBridge.setListener(this);
        buildUi(); refreshPlaces();
        say("سلام! طبیعی بگو چه کاری برات انجام بدم. برای کنترل واقعی Snapp، یک‌بار «دسترسی Snapp» را فعال کن.");
    }

    private void buildUi(){
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(28,28,28,28); root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL); root.setBackgroundColor(Color.WHITE);
        TextView title=t("Soroush Agent",26,true); root.addView(title);
        LinearLayout top=new LinearLayout(this); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        autoPay=new Switch(this); autoPay.setText("پرداخت خودکار"); autoPay.setTextSize(16); top.addView(autoPay,new LinearLayout.LayoutParams(0,-2,1));
        Button acc=new Button(this); acc.setText("دسترسی Snapp"); acc.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))); top.addView(acc); root.addView(top);
        places=t("",15,false); places.setPadding(0,14,0,14); root.addView(places);
        chat=t("",17,false); chat.setGravity(Gravity.RIGHT); ScrollView sv=new ScrollView(this); sv.addView(chat); root.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        input=new EditText(this); input.setHint("مثلاً: یه اسنپ بگیر برم پیش علی"); input.setTextSize(17); input.setSingleLine(false); input.setMaxLines(3); input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE); root.addView(input);
        Button send=new Button(this); send.setText("ارسال"); send.setOnClickListener(v->{String s=input.getText().toString().trim(); if(!s.isEmpty()){ input.setText(""); say("شما: "+s); handle(s);} }); root.addView(send);
        setContentView(root);
    }

    private TextView t(String s,int size,boolean bold){ TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(Color.rgb(25,25,25)); if(bold)v.setTypeface(null,1);v.setGravity(Gravity.RIGHT);return v; }
    private void say(String s){ chat.append((chat.length()>0?"\n\n":"")+s); }
    private void refreshPlaces(){ StringBuilder s=new StringBuilder("موقعیت‌های من"); for(Map.Entry<String,String>e:repo.all().entrySet())s.append("\n• ").append(e.getKey()).append(" — ").append(e.getValue()); places.setText(s); }

    private void handle(String raw){
        String n=PersianText.norm(raw);
        if (PersianText.hasAny(n,"لغو","بیخیال","نمیخوام") && pendingSensitive==null && wizard!=null) { wizard=null; addCity=addTitle=null; say("فرایند جاری لغو شد."); return; }
        if (pendingSensitive!=null) {
            if(PersianText.hasAny(n,"تایید","تأیید","بله","انجام بده")){ AgentCommand c=pendingSensitive; pendingSensitive=null; SnappBridge.launch(this,c); return; }
            if(PersianText.hasAny(n,"نه","لغو","بیخیال")){pendingSensitive=null;say("انجام نشد.");return;}
        }
        if (wizard!=null) { handleWizard(raw); return; }

        if (isCancelRide(n)) { pendingSensitive=new AgentCommand(AgentCommand.Type.CANCEL_RIDE,null,null); say("سفر فعال Snapp لغو شود؟ بگو «تأیید»."); return; }
        if (isPay(n)) { pendingSensitive=new AgentCommand(AgentCommand.Type.PAY_WALLET,null,null); say("هزینه سفر از کیف پول Snapp پرداخت شود؟ بگو «تأیید»."); return; }
        if (isAdd(n)) { wizard="city"; say("این موقعیت در کدام شهر است؟"); return; }
        if (isRide(n)) { parseRide(raw); return; }
        if (pendingRideDestination!=null) { acceptAddressForMissingPlace(raw); return; }
        say("درخواستت را کامل نفهمیدم. می‌تونی طبیعی‌تر بگی؛ مثلاً «میخوام برم پیش علی»، «آدرس جدید اضافه کن»، «سفر رو لغو کن» یا «هزینه رو پرداخت کن».");
    }

    private boolean isRide(String n){return PersianText.hasAny(n,"اسنپ","ماشین","تاکسی","میخوام برم","می خوام برم","بریم","برم پیش");}
    private boolean isAdd(String n){return PersianText.hasAny(n,"آدرس اضافه","ادرس اضافه","موقعیت اضافه","آدرس جدید","ادرس جدید","مکان جدید","ذخیره کن") && !isRide(n);}
    private boolean isCancelRide(String n){return PersianText.hasAny(n,"لغو سفر","سفر رو لغو","اسنپ رو لغو","اسنپ رو کنسل","ماشین رو لغو","ماشین رو کنسل");}
    private boolean isPay(String n){return PersianText.hasAny(n,"هزینه رو پرداخت","هزینه را پرداخت","پرداخت کن","از کیف پول پرداخت","کرایه رو پرداخت","کرایه را پرداخت");}

    private void parseRide(String raw){
        String dest=extractDestination(raw);
        if(dest.isEmpty()){wizard="rideDest"; say("کجا می‌خوای بری؟"); return;}
        resolveDestination(dest);
    }
    private String extractDestination(String s){
        String n=PersianText.norm(s);
        String[] markers={"میخوام برم پیش ","می خوام برم پیش ","برم پیش ","بریم پیش ","به خونه ","به خانه ","به منزل "," به ","برم ","بریم "};
        for(String m:markers){int i=n.lastIndexOf(PersianText.norm(m));if(i>=0){String x=n.substring(i+PersianText.norm(m).length()).trim();x=x.replaceAll("(لطفا|لطفاً)$","").trim();if(!x.isEmpty())return x;}}
        return "";
    }
    private void resolveDestination(String dest){
        SavedPlaceRepository.Match m=repo.findPersonal(dest);
        if(m!=null){ askRide(m.title,m.address); return; }
        String core=SavedPlaceRepository.stripRelationWords(dest);
        pendingRideDestination=core;
        wizard="missingPlaceAddress";
        say("موقعیتی با عنوان «"+core+"» ذخیره نشده. اگر منظورت یک جای مشخصه، آدرسش رو بده تا برای همین سفر استفاده کنم؛ یا بگو «لغو».");
    }
    private void acceptAddressForMissingPlace(String address){ askRide(pendingRideDestination,address); pendingRideDestination=null; wizard=null; }
    private void askRide(String title,String address){ pendingSensitive=new AgentCommand(AgentCommand.Type.REQUEST_RIDE,"CURRENT",address); say("مقصد «"+title+"» پیدا شد:\n"+address+"\nاز موقعیت فعلی درخواست Snapp بدم؟ بگو «تأیید»."); }

    private void handleWizard(String raw){
        if("rideDest".equals(wizard)){wizard=null; resolveDestination(raw);return;}
        if("missingPlaceAddress".equals(wizard)){ wizard=null; String title=pendingRideDestination; pendingRideDestination=null; askRide(title,raw); return; }
        if("city".equals(wizard)){addCity=raw.trim();wizard="title";say("چه عنوانی براش ذخیره کنم؟");return;}
        if("title".equals(wizard)){addTitle=raw.trim();wizard="address";say("آدرس «"+addTitle+"» رو در «"+addCity+"» بفرست.");return;}
        if("address".equals(wizard)){String full=addCity+" "+raw.trim();repo.save(addTitle,full);refreshPlaces();wizard=null;say("«"+addTitle+"» ذخیره شد. قبل از استفاده در سفر، Snapp خودش نتیجه آدرس را نشان می‌دهد تا اشتباه انتخاب نشود.");addCity=addTitle=null;}
    }

    @Override public void onStatus(final String text){ runOnUiThread(()->say(text)); }
    @Override protected void onDestroy(){ super.onDestroy(); SnappBridge.setListener(null); }
}
