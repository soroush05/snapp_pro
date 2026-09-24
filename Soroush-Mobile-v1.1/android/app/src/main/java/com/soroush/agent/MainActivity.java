package com.soroush.agent;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity implements SnappBridge.Listener {
    private final ConversationContext ctx=new ConversationContext();
    private final ConversationEngine engine=new ConversationEngine();
    private final SessionLifecycleManager lifecycle=new SessionLifecycleManager();
    private SavedPlaceRepository repo; private DiagnosticStore diag;
    private LinearLayout chat; private ScrollView scroll; private EditText input; private TextView connection;
    private String mapPurpose="",tempTitle="",tempCity="",tempAddress="";
    private static final int REQ_MAP=4401;

    @Override public void onCreate(Bundle b){super.onCreate(b);repo=new SavedPlaceRepository(this);diag=new DiagnosticStore(this);SnappBridge.setListener(this);buildUi();addAgent("سلام. هر طور راحتی بنویس؛ اگر چیزی مبهم باشه ازت می‌پرسم و بدون اطمینان حدس نمی‌زنم.");}
    @Override protected void onResume(){super.onResume();lifecycle.reconcile(ctx);refreshConnection();}

    private void buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);root.setBackgroundColor(Color.rgb(248,248,250));
        LinearLayout head=new LinearLayout(this);head.setPadding(20,14,20,10);head.setGravity(Gravity.CENTER_VERTICAL);head.setOrientation(LinearLayout.HORIZONTAL);
        TextView title=text("Soroush Agent",22,true);head.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button diagBtn=new Button(this);diagBtn.setText("وضعیت");diagBtn.setOnClickListener(v->showDiag());head.addView(diagBtn);root.addView(head);
        connection=text("",12,false);connection.setPadding(20,0,20,8);root.addView(connection);refreshConnection();
        scroll=new ScrollView(this);scroll.setFillViewport(true);chat=new LinearLayout(this);chat.setOrientation(LinearLayout.VERTICAL);chat.setPadding(14,8,14,12);scroll.addView(chat);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout composer=new LinearLayout(this);composer.setOrientation(LinearLayout.HORIZONTAL);composer.setPadding(10,6,10,10);composer.setGravity(Gravity.BOTTOM);
        input=new EditText(this);input.setHint("پیامت رو بنویس…");input.setTextSize(16);input.setMaxLines(4);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);input.setGravity(Gravity.RIGHT);composer.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        Button send=new Button(this);send.setText("ارسال");send.setOnClickListener(v->send());composer.addView(send);root.addView(composer);setContentView(root);
    }
    private TextView text(String s,int size,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(Color.rgb(28,28,30));v.setGravity(Gravity.RIGHT);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private void send(){String s=input.getText().toString().trim();if(s.isEmpty())return;input.setText("");addBubble(s,true);handle(s);}
    private void addAgent(String s){addBubble(s,false);}
    private void addBubble(String s,boolean user){
        TextView b=text(s,16,false);b.setPadding(18,12,18,12);b.setTextColor(user?Color.WHITE:Color.rgb(25,25,28));b.setBackgroundColor(user?Color.rgb(74,104,210):Color.WHITE);b.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        LinearLayout row=new LinearLayout(this);row.setGravity(user?Gravity.RIGHT:Gravity.LEFT);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-2,-2);bp.setMargins(user?70:0,5,user?0:70,5);row.addView(b,bp);chat.addView(row,new LinearLayout.LayoutParams(-1,-2));autoScroll();
    }
    private void autoScroll(){scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));}
    private void quick(String... labels){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.LEFT);row.setOrientation(LinearLayout.HORIZONTAL);for(String x:labels){Button b=new Button(this);b.setText(x);b.setOnClickListener(v->{addBubble(x,true);handle(x);});row.addView(b);}chat.addView(row);autoScroll();}

    private void handle(String raw){
        lifecycle.reconcile(ctx);IntentResult r=engine.parse(raw,ctx);diag.put("intent",r.intent.name());
        if(r.intent==IntentResult.Intent.CANCEL_FLOW){ctx.abandonRide();clearTemp();addAgent("باشه، این فرایند رو کنار گذاشتم. هر وقت خواستی از نو شروع می‌کنیم.");return;}
        if(r.intent==IntentResult.Intent.CANCEL_RIDE){confirmCancelRide();return;}
        if(r.intent==IntentResult.Intent.SHOW_MAP){showCurrentMap();return;}
        if(r.intent==IntentResult.Intent.REJECT){rejectCurrent();return;}
        if(r.intent==IntentResult.Intent.CONFIRM){confirmCurrent();return;}
        if(r.intent==IntentResult.Intent.ADD_PLACE){startAdd(r.target);return;}
        if(r.intent==IntentResult.Intent.EDIT_PLACE){startEdit(r.target);return;}
        if(r.intent==IntentResult.Intent.REQUEST_RIDE){startRide(r);return;}
        if(r.intent==IntentResult.Intent.ANSWER){handlePending(raw);return;}
        addAgent("دقیق متوجه منظورت نشدم. می‌تونی طبیعی‌تر توضیح بدی چه کاری می‌خوای انجام بدم؟");
    }

    private void startRide(IntentResult r){
        if(ctx.ride!=null&&!ctx.ride.terminal()){ctx.ride.state=RideSession.State.ABANDONED;}
        ctx.ride=new RideSession();ctx.ride.originSpec=r.origin;ctx.ride.destinationSpec=r.destination;diag.put("session",ctx.ride.id);
        continueRide();
    }
    private void continueRide(){
        RideSession rs=ctx.ride;if(rs==null)return;rs.touch();
        if(rs.origin==null){if(rs.originSpec.trim().isEmpty()){ctx.pending=ConversationContext.Pending.RIDE_ORIGIN;addAgent("از کجا برات ماشین بگیرم؟ می‌تونی موقعیت فعلی، یک موقعیت ذخیره‌شده یا آدرس رو بگی.");quick("موقعیت فعلی","روی نقشه انتخاب می‌کنم");return;}resolveRideLocation(rs.originSpec,true);return;}
        if(rs.destination==null){if(rs.destinationSpec.trim().isEmpty()){ctx.pending=ConversationContext.Pending.RIDE_DESTINATION;addAgent("کجا می‌خوای بری؟");return;}resolveRideLocation(rs.destinationSpec,false);return;}
        rs.state=RideSession.State.READY_FOR_CONFIRMATION;ctx.pending=ConversationContext.Pending.RIDE_CONFIRM;diag.put("origin",rs.origin.label+" | "+rs.origin.searchText());diag.put("destination",rs.destination.label+" | "+rs.destination.searchText());
        addAgent("مبدأ: "+displayLoc(rs.origin)+"\nمقصد: "+displayLoc(rs.destination)+"\n\nاگر درسته تأیید کن؛ یا بگو مبدأ/مقصد رو تغییر بدم.");quick("تأیید","تغییر مبدأ","تغییر مقصد");
    }

    private void resolveRideLocation(String spec,boolean origin){
        String q=SavedPlaceRepository.stripRelationWords(spec);String n=PersianText.norm(spec);
        if(origin&&PersianText.hasAny(q,"موقعیت فعلی","مکان فعلی","اینجا","همینجا","همین جا")){setRideLocation(new LocationRef("موقعیت فعلی",spec,"","",Double.NaN,Double.NaN,LocationRef.Type.CURRENT_LOCATION,LocationRef.Confidence.HIGH,true),true);return;}
        if(PersianText.hasAny(n,"روی نقشه انتخاب","نقشه انتخاب")){openMap("","",origin?"rideOrigin":"rideDestination");return;}
        SavedPlaceRepository.Place exact=repo.exact(q);
        boolean personalRelation=PersianText.hasAny(n,"پیش ","خونه ","خانه ","منزل ");
        if(exact!=null){
            if(personalRelation || isClearlyPersonalTitle(exact.title)){if(!exact.confirmed||!exact.hasPoint()){ctx.pending=origin?ConversationContext.Pending.MAP_DETAILS_ORIGIN:ConversationContext.Pending.MAP_DETAILS_DESTINATION;ctx.pendingText=q;addAgent("«"+exact.title+"» ذخیره شده، ولی نقطه‌اش قبلاً روی نقشه تأیید نشده. الان روی نقشه دقیقش کنیم؟");quick("دیدن روی نقشه","این مکان نیست");return;}setRideLocation(exact.toLocation(),origin);return;}
            ctx.pending=origin?ConversationContext.Pending.SAVED_OR_MAP_ORIGIN:ConversationContext.Pending.SAVED_OR_MAP_DESTINATION;ctx.pendingText=q;addAgent("«"+q+"» هم عنوان یک موقعیت ذخیره‌شده است و ممکنه منظور یک مکان روی نقشه هم باشه. منظورت کدومه؟");quick("موقعیت ذخیره‌شده","روی نقشه");return;
        }
        List<SavedPlaceRepository.Match> matches=repo.findCandidates(q);
        if(!matches.isEmpty()){ctx.pending=origin?ConversationContext.Pending.MAP_DETAILS_ORIGIN:ConversationContext.Pending.MAP_DETAILS_DESTINATION;ctx.pendingText=q;StringBuilder s=new StringBuilder("چند موقعیت ذخیره‌شده شبیه این پیدا کردم: ");for(int i=0;i<Math.min(3,matches.size());i++){if(i>0)s.append("، ");s.append("«").append(matches.get(i).place.title).append("»");}s.append(". منظورت یکی از این‌هاست یا باید روی نقشه پیداش کنیم؟");addAgent(s.toString());quick("روی نقشه");return;}
        askMapDetails(q,origin);
    }
    private boolean isClearlyPersonalTitle(String t){String n=PersianText.norm(t);return PersianText.hasAny(n,"خانه","خونه","مامان","بابا","مادر","پدر","دوست","علی");}
    private void askMapDetails(String q,boolean origin){ctx.pending=origin?ConversationContext.Pending.MAP_DETAILS_ORIGIN:ConversationContext.Pending.MAP_DETAILS_DESTINATION;ctx.pendingText=q;if(PersianText.addressTokens(q).size()>=3){addAgent("این عبارت رو به‌عنوان آدرس روی نقشه بررسی می‌کنم، ولی قبل از استفاده باید نقطه رو خودت تأیید کنی.");openMap(q,"",origin?"rideOrigin":"rideDestination");}else{addAgent("برای اینکه اشتباه انتخاب نکنم، درباره «"+q+"» اطلاعات بیشتری بده؛ مثلاً شهر، محدوده یا نوع مکان. یا می‌تونی مستقیم روی نقشه انتخابش کنی.");quick("روی نقشه انتخاب می‌کنم");}}
    private void setRideLocation(LocationRef l,boolean origin){if(ctx.ride==null)return;if(origin)ctx.ride.origin=l;else ctx.ride.destination=l;ctx.pending=ConversationContext.Pending.NONE;continueRide();}

    private void handlePending(String raw){
        switch(ctx.pending){
            case RIDE_ORIGIN:ctx.ride.originSpec=raw;resolveRideLocation(raw,true);break;
            case RIDE_DESTINATION:ctx.ride.destinationSpec=raw;resolveRideLocation(raw,false);break;
            case SAVED_OR_MAP_ORIGIN:handleSavedOrMap(raw,true);break;
            case SAVED_OR_MAP_DESTINATION:handleSavedOrMap(raw,false);break;
            case MAP_DETAILS_ORIGIN:handleMapDetails(raw,true);break;
            case MAP_DETAILS_DESTINATION:handleMapDetails(raw,false);break;
            case ADD_CITY:tempCity=raw.trim();if(tempTitle!=null&&!tempTitle.trim().isEmpty()){ctx.pending=ConversationContext.Pending.ADD_ADDRESS;addAgent("آدرسش رو بگو. بعد روی نقشه تأییدش می‌کنیم.");}else{ctx.pending=ConversationContext.Pending.ADD_TITLE;addAgent("چه عنوانی براش بذارم؟ مثلاً «خانه» یا «محل کار».");}break;
            case ADD_TITLE:tempTitle=raw.trim();ctx.pending=ConversationContext.Pending.ADD_ADDRESS;addAgent("آدرس رو هر طور معمول می‌گی بنویس. بعد روی نقشه تأییدش می‌کنیم.");break;
            case ADD_ADDRESS:tempAddress=raw.trim();openMap(tempAddress,tempCity,"add");break;
            case EDIT_TARGET:startEdit(raw);break;
            case EDIT_ADDRESS:tempAddress=raw.trim();openMap(tempAddress,tempCity,"edit");break;
            case RIDE_CONFIRM:handleRideCorrection(raw);break;
            default:addAgent("برای ادامه، کمی واضح‌تر بگو منظورت کدوم بخشه.");
        }
    }
    private void handleSavedOrMap(String raw,boolean origin){String n=PersianText.norm(raw);if(PersianText.hasAny(n,"ذخیره","saved","سیو")){SavedPlaceRepository.Place p=repo.exact(ctx.pendingText);if(p!=null){if(p.confirmed&&p.hasPoint())setRideLocation(p.toLocation(),origin);else{openMap(p.searchAddress,p.city,origin?"rideOrigin":"rideDestination");}}return;}if(PersianText.hasAny(n,"نقشه","map")){openMap(ctx.pendingText,"",origin?"rideOrigin":"rideDestination");return;}addAgent("منظورت موقعیت ذخیره‌شده است یا موقعیتی روی نقشه؟");}
    private void handleMapDetails(String raw,boolean origin){String n=PersianText.norm(raw);if(PersianText.hasAny(n,"نقشه")){openMap(ctx.pendingText,"",origin?"rideOrigin":"rideDestination");return;}String q=(ctx.pendingText+" "+raw).trim();ctx.pendingText=q;if(PersianText.addressTokens(q).size()>=2){addAgent("خوبه، با این اطلاعات روی نقشه بررسی می‌کنم. قبل از استفاده نقطه رو تأیید کن.");openMap(q,"",origin?"rideOrigin":"rideDestination");}else addAgent("هنوز برای انتخاب مطمئن کافی نیست. شهر، محدوده یا نوع مکان رو هم بگو.");}
    private void handleRideCorrection(String raw){String n=PersianText.norm(raw);if(PersianText.hasAny(n,"مبدا","مبدأ","از ")){ctx.ride.origin=null;ctx.ride.originSpec="";ctx.pending=ConversationContext.Pending.RIDE_ORIGIN;addAgent("باشه، مبدأ جدید کجاست؟");return;}if(PersianText.hasAny(n,"مقصد","به ","برم")){ctx.ride.destination=null;ctx.ride.destinationSpec="";ctx.pending=ConversationContext.Pending.RIDE_DESTINATION;addAgent("باشه، مقصد جدید کجاست؟");return;}addAgent("اگر می‌خوای چیزی تغییر کنه بگو «مبدأ رو عوض کن» یا «مقصد رو عوض کن»؛ اگر همه‌چیز درسته تأیید کن.");}

    private void confirmCurrent(){
        if(ctx.pending==ConversationContext.Pending.RIDE_CONFIRM&&ctx.ride!=null){launchRide();return;}
        if(ctx.pending==ConversationContext.Pending.CANCEL_RIDE_CONFIRM){ctx.pending=ConversationContext.Pending.NONE;AgentCommand c=new AgentCommand(AgentCommand.Type.CANCEL_RIDE,null,null);if(!isAccessibilityEnabled()){addAgent("اول دسترسی Snapp رو فعال کن.");return;}SnappBridge.launch(this,c);return;}
        addAgent("مورد مشخصی برای تأیید منتظر نیست. اگر درباره موقعیت یا سفر خاصی منظورت هست بگو کدوم.");
    }
    private void rejectCurrent(){
        if(ctx.pending==ConversationContext.Pending.RIDE_CONFIRM&&ctx.ride!=null){addAgent("باشه. کدومش اشتباهه؛ مبدأ یا مقصد؟");return;}
        if(ctx.pending==ConversationContext.Pending.SAVED_OR_MAP_ORIGIN||ctx.pending==ConversationContext.Pending.MAP_DETAILS_ORIGIN){ctx.ride.origin=null;ctx.ride.originSpec="";ctx.pending=ConversationContext.Pending.RIDE_ORIGIN;addAgent("باشه، مبدأ درست رو بگو یا روی نقشه انتخاب کن.");return;}
        if(ctx.pending==ConversationContext.Pending.SAVED_OR_MAP_DESTINATION||ctx.pending==ConversationContext.Pending.MAP_DETAILS_DESTINATION){ctx.ride.destination=null;ctx.ride.destinationSpec="";ctx.pending=ConversationContext.Pending.RIDE_DESTINATION;addAgent("باشه، مقصد درست رو بگو یا روی نقشه انتخاب کن.");return;}
        addAgent("باشه. بگو کدوم بخش درست نیست تا فقط همون رو اصلاح کنیم.");
    }
    private void launchRide(){RideSession rs=ctx.ride;if(rs==null||rs.origin==null||rs.destination==null)return;if(!isAccessibilityEnabled()){addAgent("برای اجرای Snapp اول دسترسی Accessibility مربوط به Soroush Agent رو فعال کن.");startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));return;}String o=rs.origin.type==LocationRef.Type.CURRENT_LOCATION?"CURRENT":rs.origin.searchText();String d=rs.destination.searchText();AgentCommand c=new AgentCommand(rs.id,AgentCommand.Type.REQUEST_RIDE,o,d,rs.origin.lat,rs.origin.lon,rs.destination.lat,rs.destination.lon);rs.state=RideSession.State.EXECUTING;rs.touch();ctx.pending=ConversationContext.Pending.NONE;addAgent("باشه. وضعیت فعلی Snapp رو بررسی می‌کنم و فقط اگر مراحل قابل تشخیص باشن درخواست رو تا مرحله نهایی اجرا می‌کنم.");SnappBridge.launch(this,c);}

    private void startAdd(String suggested){clearTemp();tempTitle=suggested==null?"":suggested.trim();ctx.pending=ConversationContext.Pending.ADD_CITY;addAgent("این موقعیت در کدوم شهره؟");}
    private void startEdit(String target){String q=target==null?"":target.trim();if(q.isEmpty()){ctx.pending=ConversationContext.Pending.EDIT_TARGET;addAgent("کدوم موقعیت ذخیره‌شده رو می‌خوای تغییر بدی؟");return;}SavedPlaceRepository.Place p=repo.exact(q);if(p==null){addAgent("موقعیت ذخیره‌شده‌ای با عنوان «"+q+"» پیدا نکردم. عنوان دقیقش رو بگو.");ctx.pending=ConversationContext.Pending.EDIT_TARGET;return;}tempTitle=p.title;tempCity=p.city;ctx.pending=ConversationContext.Pending.EDIT_ADDRESS;addAgent("آدرس جدید «"+p.title+"» رو بگو، یا بگو روی نقشه انتخاب کنم.");quick("روی نقشه انتخاب می‌کنم");}

    private void openMap(String q,String city,String purpose){mapPurpose=purpose;Intent i=new Intent(this,MapPickerActivity.class);i.putExtra("query",q==null?"":q);i.putExtra("city",city==null?"":city);startActivityForResult(i,REQ_MAP);}
    private void showCurrentMap(){if(ctx.pending==ConversationContext.Pending.EDIT_ADDRESS){openMap(tempAddress,tempCity,"edit");return;}
        if(ctx.pending==ConversationContext.Pending.ADD_ADDRESS){openMap(tempAddress,tempCity,"add");return;}
        if(ctx.ride!=null&&ctx.ride.destination!=null){openMap(ctx.ride.destination.searchText(),ctx.ride.destination.city,"rideDestination");return;}if(ctx.pending==ConversationContext.Pending.MAP_DETAILS_ORIGIN){openMap(ctx.pendingText,"","rideOrigin");return;}if(ctx.pending==ConversationContext.Pending.MAP_DETAILS_DESTINATION){openMap(ctx.pendingText,"","rideDestination");return;}addAgent("فعلاً موقعیت مشخصی برای نمایش روی نقشه ندارم. بگو کدوم مکان رو می‌خوای ببینی.");}
    @Override protected void onActivityResult(int req,int res,Intent data){super.onActivityResult(req,res,data);if(req!=REQ_MAP||res!=RESULT_OK||data==null)return;double lat=data.getDoubleExtra("lat",Double.NaN),lon=data.getDoubleExtra("lon",Double.NaN);String ca=data.getStringExtra("canonicalAddress");String raw=data.getStringExtra("rawQuery");LocationRef l=new LocationRef(raw==null||raw.isEmpty()?"موقعیت انتخاب‌شده":raw,raw,ca,tempCity,lat,lon,LocationRef.Type.MANUAL_PIN,LocationRef.Confidence.HIGH,true);if("rideOrigin".equals(mapPurpose))setRideLocation(l,true);else if("rideDestination".equals(mapPurpose))setRideLocation(l,false);else if("add".equals(mapPurpose)){repo.save(tempTitle,tempAddress,ca,tempCity,lat,lon,true);addAgent("«"+tempTitle+"» با نقطه‌ای که روی نقشه تأیید کردی ذخیره شد.");clearTemp();ctx.clearPending();}else if("edit".equals(mapPurpose)){repo.save(tempTitle,tempAddress,ca,tempCity,lat,lon,true);addAgent("موقعیت «"+tempTitle+"» به نقطه جدید روی نقشه تغییر کرد.");clearTemp();ctx.clearPending();}mapPurpose="";}

    private void confirmCancelRide(){ctx.pending=ConversationContext.Pending.CANCEL_RIDE_CONFIRM;addAgent("اگر سفر یا درخواست فعالی در Snapp باشه، می‌خوای لغوش کنم؟ برای جلوگیری از لغو ناخواسته باید تأیید کنی.");quick("تأیید","بیخیال");}
    private String displayLoc(LocationRef l){return l.label+(l.canonicalAddress.trim().isEmpty()?"":" — "+l.canonicalAddress);}
    private void clearTemp(){tempTitle=tempCity=tempAddress=mapPurpose="";ctx.pending=ConversationContext.Pending.NONE;}
    private void showDiag(){new android.app.AlertDialog.Builder(this).setTitle("Diagnostic").setMessage(diag.dump()).setPositiveButton("باشه",null).show();}
    private void refreshConnection(){boolean e=isAccessibilityEnabled();connection.setText(e?"Snapp access: فعال ✓":"Snapp access: غیرفعال — برای اجرا فعالش کن");connection.setTextColor(e?Color.rgb(20,125,60):Color.rgb(170,55,45));}
    private boolean isAccessibilityEnabled(){try{String enabled=Settings.Secure.getString(getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);if(enabled==null)return false;String flat=new ComponentName(this,SnappAccessibilityService.class).flattenToString();for(String x:enabled.split(":"))if(x.equalsIgnoreCase(flat))return true;}catch(Exception ignored){}return false;}
    @Override public void onStatus(String s){runOnUiThread(()->{diag.put("snapp",s);if(s.contains("ثبت شد")&&ctx.ride!=null)ctx.ride.state=RideSession.State.COMPLETED;if(s.contains("ادامه ندادم")||s.contains("نرسید")||s.contains("تطبیق نداشت")||s.contains("نتونستم")||s.contains("فعال نشان می‌دهد")){diag.put("error",s);if(ctx.ride!=null)ctx.ride.state=RideSession.State.FAILED;}addAgent(s);});}
}
