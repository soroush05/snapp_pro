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

/** Conversation-first shell. Natural-language understanding is centralized in SemanticRouter. */
public class MainActivity extends Activity implements SnappBridge.Listener {
    private final ConversationContext ctx=new ConversationContext();
    private final SemanticPipeline semantic=new SemanticPipeline();
    private final GoalManager goals=new GoalManager();
    private final SessionLifecycleManager lifecycle=new SessionLifecycleManager();
    private SavedPlaceRepository repo;private LocationResolver locations;private DiagnosticStore diag;
    private LinearLayout chat;private ScrollView scroll;private EditText input;private TextView connection,activityStatus;
    private String mapPurpose="",tempTitle="",tempCity="",tempAddress="",deleteTarget="";
    private double tempLat=Double.NaN,tempLon=Double.NaN;
    private String tempCanonical="",tempPointSource="";
    private long tempVerifiedAt=0L;
    private static final int REQ_MAP=4401;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);repo=new SavedPlaceRepository(this);locations=new LocationResolver(repo);diag=new DiagnosticStore(this);SnappBridge.setListener(this);buildUi();
        addAgent("سلام! طبیعی بگو چی می‌خوای. اگر معنی یا موقعیتی مبهم باشه، قبل از انجام کار ازت می‌پرسم.");
    }
    @Override protected void onResume(){super.onResume();lifecycle.reconcile(ctx);refreshConnection();}
    @Override protected void onDestroy(){SnappBridge.setListener(null);super.onDestroy();}

    private void buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);root.setBackgroundColor(Color.rgb(248,248,250));
        LinearLayout head=new LinearLayout(this);head.setPadding(20,14,20,10);head.setGravity(Gravity.CENTER_VERTICAL);head.setOrientation(LinearLayout.HORIZONTAL);
        TextView title=text("Soroush Agent",22,true);head.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button diagBtn=new Button(this);diagBtn.setText("وضعیت");diagBtn.setOnClickListener(v->showDiag());head.addView(diagBtn);root.addView(head);
        connection=text("",12,false);connection.setPadding(20,0,20,4);root.addView(connection);refreshConnection();
        activityStatus=text("",12,false);activityStatus.setPadding(20,0,20,6);activityStatus.setTextColor(Color.rgb(95,95,100));activityStatus.setVisibility(View.GONE);root.addView(activityStatus);
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
    private void quickRow(String[] labels,Runnable[] actions){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.LEFT);row.setOrientation(LinearLayout.HORIZONTAL);
        for(int i=0;i<labels.length;i++){final int k=i;Button b=new Button(this);b.setText(labels[i]);b.setOnClickListener(v->{addBubble(labels[k],true);actions[k].run();});row.addView(b);}chat.addView(row);autoScroll();
    }

    private void handle(String raw){
        lifecycle.reconcile(ctx);IntentEnvelope e=semantic.understand(raw,ctx);ctx.lastIntent=e.intent;
        diag.put("intent",e.intent.name()+" "+String.format(Locale.ROOT,"%.2f",e.confidence)+" | "+e.topAlternatives());

        if(e.decision==IntentEnvelope.Decision.CLARIFY){clarifyMeaning(e);return;}

        // Pending location/title/address answers are slot values unless a clear new task/control intent interrupts.
        if(shouldConsumeAsPending(e)){handlePending(raw,e);return;}

        switch(e.intent){
            case GREETING:greet();return;
            case HELP:showHelp();return;
            case CANCEL_FLOW:cancelCurrentFlow();return;
            case CANCEL_RIDE:confirmCancelRide();return;
            case SHOW_MAP:showCurrentMap();return;
            case USE_CURRENT_LOCATION:useCurrentLocation();return;
            case CONFIRM:confirmCurrent();return;
            case REJECT:rejectCurrent();return;
            case ADD_SAVED_PLACE:startAdd(e);return;
            case EDIT_SAVED_PLACE:startEdit(resolveTarget(e));return;
            case DELETE_SAVED_PLACE:startDelete(resolveTarget(e));return;
            case SHOW_SAVED_PLACE:showSaved(resolveTarget(e));return;
            case CHANGE_ORIGIN:changeOrigin(e.value);return;
            case CHANGE_DESTINATION:changeDestination(e.value);return;
            case REQUEST_RIDE:startRide(e);return;
            case SEARCH_LOCATION:startLocationSearch(raw);return;
            case CORRECT_PREVIOUS:correctPrevious(raw);return;
            case ANSWER_SLOT:handlePending(raw,e);return;
            default:addAgent("معنی درخواستت هنوز برای انجام یک کار مشخص کافی نیست. یک‌کم بیشتر درباره کاری که می‌خوای انجام بدم بگو؛ من حدس نمی‌زنم.");
        }
    }

    private boolean shouldConsumeAsPending(IntentEnvelope e){
        if(ctx.pending==ConversationContext.Pending.NONE)return false;
        switch(e.intent){
            case CANCEL_FLOW:case CANCEL_RIDE:case REQUEST_RIDE:case ADD_SAVED_PLACE:case EDIT_SAVED_PLACE:case DELETE_SAVED_PLACE:case HELP:case GREETING:return false;
            case SHOW_MAP:case CONFIRM:case REJECT:case USE_CURRENT_LOCATION:return false;
            default:break;
        }
        switch(ctx.pending){
            case RIDE_ORIGIN:case RIDE_DESTINATION:case MAP_DETAILS_ORIGIN:case MAP_DETAILS_DESTINATION:
            case ADD_TITLE:case ADD_ADDRESS:case EDIT_TARGET:case EDIT_ADDRESS:case DELETE_TARGET:return true;
            default:return e.intent==SemanticIntent.ANSWER_SLOT;
        }
    }

    private void clarifyMeaning(IntentEnvelope e){
        List<SemanticIntent> xs=new ArrayList<>(e.alternatives.keySet());
        String a=xs.size()>0?intentLabel(xs.get(0)):"یک کار";String b=xs.size()>1?intentLabel(xs.get(1)):"کار دیگری";
        addAgent("پیامت دو برداشت نزدیک داره: «"+a+"» یا «"+b+"». کدوم منظورت بود؟");
    }
    private String intentLabel(SemanticIntent i){switch(i){case REQUEST_RIDE:return "درخواست سفر";case ADD_SAVED_PLACE:return "ذخیره موقعیت جدید";case EDIT_SAVED_PLACE:return "اصلاح موقعیت ذخیره‌شده";case CHANGE_ORIGIN:return "تغییر مبدأ";case CHANGE_DESTINATION:return "تغییر مقصد";case CANCEL_FLOW:return "کنار گذاشتن کار فعلی";case CANCEL_RIDE:return "لغو سفر واقعی";case SEARCH_LOCATION:return "پیدا کردن مکان";default:return "ادامه همین گفتگو";}}

    private void greet(){addAgent(ctx.pending!=ConversationContext.Pending.NONE?"سلام 🙂 من اینجام. می‌تونی همون کار رو ادامه بدی یا یک درخواست جدید بگی.":"سلام 🙂 چه کاری برات انجام بدم؟");}
    private void showHelp(){addAgent("می‌تونی طبیعی درباره گرفتن ماشین، مبدأ و مقصد، ذخیره/اصلاح/حذف موقعیت، پیدا کردن مکان یا انتخاب نقطه روی نقشه صحبت کنی. اگر چیزی مبهم باشه قبل از اجرا سؤال می‌کنم.");}

    private void cancelCurrentFlow(){
        if(ctx.activeGoal==ConversationContext.Goal.ADD_PLACE||ctx.activeGoal==ConversationContext.Goal.EDIT_PLACE||ctx.activeGoal==ConversationContext.Goal.DELETE_PLACE){
            clearTempOnly();goals.cancel(ctx);if(ctx.activeGoal==ConversationContext.Goal.RIDE&&ctx.ride!=null){addAgent("باشه، این کار رو کنار گذاشتم. درخواست سفر قبلی هنوز حفظ شده؛ هر وقت خواستی ادامه‌اش می‌دیم.");continueRide();}else addAgent("باشه، این کار رو کنار گذاشتم.");return;
        }
        boolean wasExecuting=ctx.ride!=null&&ctx.ride.state==RideSession.State.EXECUTING;boolean had=ctx.ride!=null||ctx.pending!=ConversationContext.Pending.NONE;
        SnappBridge.abortCurrent();ctx.abandonRide();ctx.pausedGoals.clear();ctx.activeGoal=ConversationContext.Goal.NONE;clearTempOnly();setActivityStatus("");
        if(wasExecuting)addAgent("اتوماسیون فعلی متوقف شد. اگر خود سفر در Snapp ثبت شده و می‌خوای آن را هم لغو کنم، جداگانه بگو سفر را لغو کنم.");
        else addAgent(had?"باشه، کار فعلی کنار گذاشته شد. درخواست بعدی از وضعیت تازه شروع می‌شه.":"الان کاری در حال انجام نبود. هر وقت خواستی درخواست جدیدت رو بگو.");
    }

    private void startRide(IntentEnvelope e){
        SnappBridge.abortCurrent();if(ctx.ride!=null&&!ctx.ride.terminal())ctx.ride.state=RideSession.State.ABANDONED;
        ctx.pausedGoals.clear();ctx.activeGoal=ConversationContext.Goal.RIDE;ctx.ride=new RideSession();ctx.ride.originSpec=e.origin;ctx.ride.destinationSpec=e.destination;diag.put("session",ctx.ride.id);continueRide();
    }
    private void continueRide(){
        RideSession rs=ctx.ride;if(rs==null)return;rs.touch();
        if(rs.origin==null){if(rs.originSpec.trim().isEmpty()){ctx.pending=ConversationContext.Pending.RIDE_ORIGIN;addAgent("از کجا برات ماشین بگیرم؟ اسم موقعیت ذخیره‌شده، آدرس، موقعیت فعلی یا انتخاب روی نقشه قابل استفاده است.");quickRow(new String[]{"موقعیت فعلی","انتخاب روی نقشه"},new Runnable[]{this::useCurrentLocation,()->openMap("","","rideOrigin")});return;}resolveRideLocation(rs.originSpec,true);return;}
        if(rs.destination==null){if(rs.destinationSpec.trim().isEmpty()){ctx.pending=ConversationContext.Pending.RIDE_DESTINATION;addAgent("کجا می‌خوای بری؟ اسم موقعیت ذخیره‌شده، آدرس یا نقطه روی نقشه رو بگو.");quickRow(new String[]{"انتخاب روی نقشه"},new Runnable[]{()->openMap("","","rideDestination")});return;}resolveRideLocation(rs.destinationSpec,false);return;}
        rs.state=RideSession.State.READY_FOR_CONFIRMATION;ctx.pending=ConversationContext.Pending.RIDE_CONFIRM;diag.put("origin",displayLoc(rs.origin));diag.put("destination",displayLoc(rs.destination));
        addAgent("مبدأ: "+displayLoc(rs.origin)+"\nمقصد: "+displayLoc(rs.destination)+"\n\nاگر هر دو درست‌اند تأیید کن. اگر نه، فقط بگو کدام را تغییر بدم.");
        quickRow(new String[]{"تأیید","تغییر مبدأ","تغییر مقصد"},new Runnable[]{this::confirmCurrent,()->changeOrigin(""),()->changeDestination("")});
    }

    private void resolveRideLocation(String spec,boolean origin){
        LocationDecision d=locations.resolve(spec,origin);diag.put(origin?"originDecision":"destinationDecision",d.kind.name()+" | "+d.query);
        switch(d.kind){
            case CURRENT_LOCATION:setRideLocation(d.location,true);return;
            case RESOLVED_SAVED:setRideLocation(d.location,origin);return;
            case SAVED_OR_MAP_AMBIGUOUS:
                ctx.pending=origin?ConversationContext.Pending.SAVED_OR_MAP_ORIGIN:ConversationContext.Pending.SAVED_OR_MAP_DESTINATION;ctx.pendingText=d.query;
                addAgent("«"+d.query+"» هم عنوان موقعیت ذخیره‌شده است و هم می‌تونه منظور یک مکان عمومی روی نقشه باشه. کدام را می‌خوای؟");
                quickRow(new String[]{"موقعیت ذخیره‌شده","روی نقشه"},new Runnable[]{()->chooseSaved(ctx.pendingText,origin),()->openMap(ctx.pendingText,"",origin?"rideOrigin":"rideDestination")});return;
            case SAVED_CANDIDATES:
                ctx.pending=origin?ConversationContext.Pending.MAP_DETAILS_ORIGIN:ConversationContext.Pending.MAP_DETAILS_DESTINATION;ctx.pendingText=d.query;
                if(d.savedMatches.size()==1){SavedPlaceRepository.Place p=d.savedMatches.get(0).place;addAgent("«"+p.title+"» رو پیدا کردم، ولی نقطه دقیقش هنوز تأیید نشده یا عبارتت دقیقاً با عنوانش یکی نیست. عنوان دقیق‌تر بگو یا روی نقشه بررسیش کن.");}
                else{StringBuilder s=new StringBuilder("چند موقعیت ذخیره‌شده شبیه این پیدا کردم: ");for(int i=0;i<Math.min(3,d.savedMatches.size());i++){if(i>0)s.append("، ");s.append("«").append(d.savedMatches.get(i).place.title).append("»");}s.append(". یکی رو دقیق نام ببر یا روی نقشه برو.");addAgent(s.toString());}
                quickRow(new String[]{"روی نقشه"},new Runnable[]{()->openMap(d.query,"",origin?"rideOrigin":"rideDestination")});return;
            case MAP_REQUIRED:
                addAgent("این مکان رو روی نقشه جست‌وجو می‌کنم؛ هیچ نتیجه‌ای خودکار تأیید نمی‌شه. نتیجه یا نقطه درست رو خودت تأیید کن.");openMap(d.query,"",origin?"rideOrigin":"rideDestination");return;
            case NEED_MORE_CONTEXT:
            default:
                ctx.pending=origin?ConversationContext.Pending.MAP_DETAILS_ORIGIN:ConversationContext.Pending.MAP_DETAILS_DESTINATION;ctx.pendingText=d.query;
                addAgent("برای «"+d.query+"» چند برداشت ممکنه. شهر، محدوده یا نوع مکان رو هم بگو؛ یا خودت روی نقشه نقطه رو انتخاب کن.");quickRow(new String[]{"انتخاب روی نقشه"},new Runnable[]{()->openMap(d.query,"",origin?"rideOrigin":"rideDestination")});
        }
    }

    private void chooseSaved(String title,boolean origin){
        SavedPlaceRepository.Place p=repo.exact(title);if(p==null){addAgent("موقعیت ذخیره‌شده موردنظر رو پیدا نکردم. عنوانش رو دقیق‌تر بگو.");return;}
        if(!p.confirmed||!p.hasPoint()){addAgent("این موقعیت از داده قدیمی/متنیه و نقطه دقیقش تأیید نشده. یک بار روی نقشه تأییدش کن.");openMap(p.searchAddress,p.city,origin?"rideOrigin":"rideDestination");return;}
        setRideLocation(p.toLocation(),origin);
    }
    private void setRideLocation(LocationRef l,boolean origin){if(ctx.ride==null)return;if(origin){ctx.ride.origin=l;ctx.lastEntityRole=ConversationContext.EntityRole.ORIGIN;}else{ctx.ride.destination=l;ctx.lastEntityRole=ConversationContext.EntityRole.DESTINATION;}ctx.lastEntity=l.label;ctx.pending=ConversationContext.Pending.NONE;continueRide();}

    private void changeOrigin(String value){
        if(ctx.ride==null||ctx.ride.terminal()){addAgent("درخواست سفر فعالی برای تغییر مبدأ ندارم. اگر سفر جدید می‌خوای همون رو بگو.");return;}
        ctx.ride.origin=null;ctx.ride.originSpec=value==null?"":value.trim();ctx.lastEntityRole=ConversationContext.EntityRole.ORIGIN;
        if(ctx.ride.originSpec.isEmpty()){ctx.pending=ConversationContext.Pending.RIDE_ORIGIN;addAgent("مبدأ جدید کجاست؟");quickRow(new String[]{"موقعیت فعلی","انتخاب روی نقشه"},new Runnable[]{this::useCurrentLocation,()->openMap("","","rideOrigin")});}
        else resolveRideLocation(ctx.ride.originSpec,true);
    }
    private void changeDestination(String value){
        if(ctx.ride==null||ctx.ride.terminal()){addAgent("درخواست سفر فعالی برای تغییر مقصد ندارم. اگر سفر جدید می‌خوای همون رو بگو.");return;}
        ctx.ride.destination=null;ctx.ride.destinationSpec=value==null?"":value.trim();ctx.lastEntityRole=ConversationContext.EntityRole.DESTINATION;
        if(ctx.ride.destinationSpec.isEmpty()){ctx.pending=ConversationContext.Pending.RIDE_DESTINATION;addAgent("مقصد جدید کجاست؟");quickRow(new String[]{"انتخاب روی نقشه"},new Runnable[]{()->openMap("","","rideDestination")});}
        else resolveRideLocation(ctx.ride.destinationSpec,false);
    }

    private void useCurrentLocation(){
        if(ctx.ride!=null&&ctx.ride.origin==null){setRideLocation(new LocationRef("موقعیت فعلی","موقعیت فعلی","","",Double.NaN,Double.NaN,LocationRef.Type.CURRENT_LOCATION,LocationRef.Confidence.HIGH,true,"CURRENT_LOCATION",System.currentTimeMillis()),true);return;}
        addAgent("موقعیت فعلی برای مبدأ سفر قابل استفاده است. اگر می‌خوای از همینجا ماشین بگیری، درخواست سفر رو شروع کن یا مبدأ رو تغییر بده.");
    }

    private void handlePending(String raw,IntentEnvelope e){
        switch(ctx.pending){
            case RIDE_ORIGIN:if(ctx.ride!=null){ctx.ride.originSpec=raw;resolveRideLocation(raw,true);}break;
            case RIDE_DESTINATION:if(ctx.ride!=null){ctx.ride.destinationSpec=raw;resolveRideLocation(raw,false);}break;
            case SAVED_OR_MAP_ORIGIN:handleSavedOrMap(raw,e,true);break;
            case SAVED_OR_MAP_DESTINATION:handleSavedOrMap(raw,e,false);break;
            case MAP_DETAILS_ORIGIN:handleMapDetails(raw,true);break;
            case MAP_DETAILS_DESTINATION:handleMapDetails(raw,false);break;
            case ADD_TITLE:
                tempTitle=raw.trim();ctx.lastEntity=tempTitle;ctx.lastEntityRole=ConversationContext.EntityRole.SAVED_PLACE;
                if(!Double.isNaN(tempLat)&&!Double.isNaN(tempLon)){
                    String addr=tempAddress.isEmpty()?tempCanonical:tempAddress;
                    repo.save(tempTitle,addr,tempCanonical,tempCity,tempLat,tempLon,true,tempPointSource.isEmpty()?"USER_CONFIRMED_PIN":tempPointSource,"CONFIRMED",tempVerifiedAt>0?tempVerifiedAt:System.currentTimeMillis());
                    addAgent("«"+tempTitle+"» با همون نقطه‌ای که تأیید کردی ذخیره شد.");finishSubGoal();
                }else if(!tempAddress.isEmpty())openMap(tempAddress,tempCity,"add");
                else{ctx.pending=ConversationContext.Pending.ADD_ADDRESS;addAgent("موقعیت «"+tempTitle+"» کجاست؟ آدرس رو طبیعی بنویس یا روی نقشه انتخابش کن.");quickRow(new String[]{"انتخاب روی نقشه"},new Runnable[]{()->openMap("",tempCity,"add")});}
                break;
            case ADD_ADDRESS:tempAddress=raw.trim();openMap(tempAddress,tempCity,"add");break;
            case EDIT_TARGET:startEdit(raw);break;
            case EDIT_ADDRESS:tempAddress=raw.trim();openMap(tempAddress,tempCity,"edit");break;
            case DELETE_TARGET:startDelete(raw);break;
            default:addAgent("برای ادامه، بگو دقیقاً کدوم بخش رو منظورت بود.");
        }
    }

    private void handleSavedOrMap(String raw,IntentEnvelope e,boolean origin){
        if(e.intent==SemanticIntent.SHOW_MAP||e.intent==SemanticIntent.SEARCH_LOCATION){openMap(ctx.pendingText,"",origin?"rideOrigin":"rideDestination");return;}
        SavedPlaceRepository.Place typed=repo.exact(raw);if(typed!=null){if(typed.confirmed&&typed.hasPoint())setRideLocation(typed.toLocation(),origin);else openMap(typed.searchAddress,typed.city,origin?"rideOrigin":"rideDestination");return;}
        if(e.intent==SemanticIntent.SHOW_SAVED_PLACE){chooseSaved(ctx.pendingText,origin);return;}
        addAgent("منظورت موقعیت ذخیره‌شده است یا یک مکان عمومی روی نقشه؟ می‌تونی عنوان ذخیره‌شده رو دقیق بگی یا نقشه رو باز کنی.");
    }
    private void handleMapDetails(String raw,boolean origin){
        String q=(ctx.pendingText+" "+raw).trim();ctx.pendingText=q;
        if(PersianText.addressTokens(q).size()>=2){addAgent("با این اطلاعات روی نقشه بررسی می‌کنم. قبل از استفاده، خودت نقطه رو تأیید کن.");openMap(q,"",origin?"rideOrigin":"rideDestination");}
        else addAgent("هنوز برای انتخاب مطمئن کافی نیست. شهر، محدوده یا نوع مکان رو هم اضافه کن.");
    }

    private void startAdd(IntentEnvelope e){
        goals.begin(ctx,ConversationContext.Goal.ADD_PLACE);clearTempOnly();tempTitle=e.target;tempCity=e.city;tempAddress=SemanticEntityExtractor.probableAddressTail(e.raw,tempTitle);
        if(tempTitle.isEmpty()){ctx.pending=ConversationContext.Pending.ADD_TITLE;addAgent("چه عنوانی برای این موقعیت بذارم؟ مثلاً خانه، محل کار یا هر اسمی که خودت می‌خوای.");return;}
        ctx.lastEntity=tempTitle;ctx.lastEntityRole=ConversationContext.EntityRole.SAVED_PLACE;
        if(!tempAddress.isEmpty()){addAgent("عنوان «"+tempTitle+"» و آدرس رو گرفتم. روی نقشه بررسیش کن تا مختصات دقیق ذخیره بشه.");openMap(tempAddress,tempCity,"add");return;}
        ctx.pending=ConversationContext.Pending.ADD_ADDRESS;addAgent("«"+tempTitle+"» کجاست؟ آدرس رو بنویس یا مستقیم روی نقشه انتخابش کن.");quickRow(new String[]{"انتخاب روی نقشه"},new Runnable[]{()->openMap("",tempCity,"add")});
    }

    private void startEdit(String target){
        goals.begin(ctx,ConversationContext.Goal.EDIT_PLACE);String q=target==null?"":target.trim();if(q.isEmpty()&&ctx.lastEntityRole==ConversationContext.EntityRole.SAVED_PLACE)q=ctx.lastEntity;
        if(q.isEmpty()){ctx.pending=ConversationContext.Pending.EDIT_TARGET;addAgent("کدوم موقعیت ذخیره‌شده رو می‌خوای اصلاح کنی؟");return;}
        SavedPlaceRepository.Place p=repo.exact(q);if(p==null){List<SavedPlaceRepository.Match> ms=repo.findCandidates(q);if(ms.size()==1&&ms.get(0).score>=80)p=ms.get(0).place;}
        if(p==null){ctx.pending=ConversationContext.Pending.EDIT_TARGET;addAgent("عنوان ذخیره‌شده‌ای که منظورت هست با اطمینان پیدا نشد. اسمش رو دقیق‌تر بگو.");return;}
        final SavedPlaceRepository.Place selected=p;
        tempTitle=selected.title;tempCity=selected.city;tempAddress=selected.searchAddress;ctx.lastEntity=selected.title;ctx.lastEntityRole=ConversationContext.EntityRole.SAVED_PLACE;ctx.pending=ConversationContext.Pending.EDIT_ADDRESS;
        addAgent("موقعیت جدید «"+selected.title+"» رو بگو یا روی نقشه نقطه درست رو انتخاب کن.");quickRow(new String[]{"انتخاب روی نقشه"},new Runnable[]{()->openMap(tempAddress,tempCity,"edit",selected.lat,selected.lon)});
    }

    private void startDelete(String target){
        goals.begin(ctx,ConversationContext.Goal.DELETE_PLACE);String q=target==null?"":target.trim();if(q.isEmpty()&&ctx.lastEntityRole==ConversationContext.EntityRole.SAVED_PLACE)q=ctx.lastEntity;
        if(q.isEmpty()){ctx.pending=ConversationContext.Pending.DELETE_TARGET;addAgent("کدوم موقعیت ذخیره‌شده رو می‌خوای حذف کنی؟");return;}
        SavedPlaceRepository.Place p=repo.exact(q);if(p==null){ctx.pending=ConversationContext.Pending.DELETE_TARGET;addAgent("موقعیت ذخیره‌شده «"+q+"» رو پیدا نکردم. عنوان دقیقش رو بگو.");return;}
        deleteTarget=p.title;ctx.pending=ConversationContext.Pending.DELETE_CONFIRM;addAgent("موقعیت ذخیره‌شده «"+p.title+"» حذف بشه؟");quickRow(new String[]{"تأیید حذف","بیخیال"},new Runnable[]{this::confirmCurrent,this::cancelCurrentFlow});
    }

    private void showSaved(String target){
        String q=target==null?"":target.trim();if(q.isEmpty()&&ctx.lastEntityRole==ConversationContext.EntityRole.SAVED_PLACE)q=ctx.lastEntity;
        SavedPlaceRepository.Place p=repo.exact(q);if(p==null){addAgent("عنوان موقعیت ذخیره‌شده رو دقیق‌تر بگو تا پیداش کنم.");return;}
        ctx.lastEntity=p.title;ctx.lastEntityRole=ConversationContext.EntityRole.SAVED_PLACE;String precision=p.confirmed&&p.hasPoint()?"نقطه تأییدشده دارد":"مختصاتش هنوز تأیید نشده";
        addAgent("«"+p.title+"» — "+(p.address.isEmpty()?p.searchAddress:p.address)+"\n"+precision+".");
    }

    private void startLocationSearch(String raw){goals.begin(ctx,ConversationContext.Goal.SEARCH_LOCATION);String q=SemanticEntityExtractor.searchTarget(raw);addAgent("روی نقشه چند نتیجه رو مقایسه می‌کنم؛ انتخاب نهایی با خودته.");openMap(q,"","search");}

    private void correctPrevious(String raw){
        if(ctx.lastEntityRole==ConversationContext.EntityRole.ORIGIN&&ctx.ride!=null){changeOrigin(SemanticEntityExtractor.changedOrigin(raw));return;}
        if(ctx.lastEntityRole==ConversationContext.EntityRole.DESTINATION&&ctx.ride!=null){changeDestination(SemanticEntityExtractor.changedDestination(raw));return;}
        if(ctx.lastEntityRole==ConversationContext.EntityRole.SAVED_PLACE&&!ctx.lastEntity.isEmpty()){startEdit(ctx.lastEntity);return;}
        if(ctx.ride!=null){addAgent("کدوم بخش اشتباهه؛ مبدأ یا مقصد؟");quickRow(new String[]{"مبدأ","مقصد"},new Runnable[]{()->changeOrigin(""),()->changeDestination("")});return;}
        addAgent("بگو کدوم موقعیت یا بخش رو می‌خوای اصلاح کنم تا فقط همون رو تغییر بدم.");
    }

    private void confirmCurrent(){
        if(ctx.pending==ConversationContext.Pending.RIDE_CONFIRM&&ctx.ride!=null){launchRide();return;}
        if(ctx.pending==ConversationContext.Pending.CANCEL_RIDE_CONFIRM){ctx.pending=ConversationContext.Pending.NONE;if(!isAccessibilityEnabled()){addAgent("اول دسترسی Snapp رو فعال کن.");return;}SnappBridge.launch(this,new AgentCommand(AgentCommand.Type.CANCEL_RIDE,null,null));return;}
        if(ctx.pending==ConversationContext.Pending.DELETE_CONFIRM&&!deleteTarget.isEmpty()){
            String d=deleteTarget;if(repo.delete(d)){addAgent("«"+d+"» از موقعیت‌های ذخیره‌شده حذف شد.");}else addAgent("حذف انجام نشد چون موقعیت موردنظر پیدا نشد.");deleteTarget="";finishSubGoal();return;
        }
        addAgent("الان مورد مشخصی برای تأیید ندارم. بگو چه چیزی رو می‌خوای تأیید کنم.");
    }

    private void rejectCurrent(){
        if(ctx.pending==ConversationContext.Pending.RIDE_CONFIRM&&ctx.ride!=null){addAgent("کدومش درست نیست؛ مبدأ یا مقصد؟");quickRow(new String[]{"مبدأ","مقصد"},new Runnable[]{()->changeOrigin(""),()->changeDestination("")});return;}
        if(ctx.pending==ConversationContext.Pending.SAVED_OR_MAP_ORIGIN||ctx.pending==ConversationContext.Pending.MAP_DETAILS_ORIGIN){if(ctx.ride!=null){ctx.ride.origin=null;ctx.ride.originSpec="";}ctx.pending=ConversationContext.Pending.RIDE_ORIGIN;addAgent("باشه، مبدأ درست رو بگو یا روی نقشه انتخاب کن.");return;}
        if(ctx.pending==ConversationContext.Pending.SAVED_OR_MAP_DESTINATION||ctx.pending==ConversationContext.Pending.MAP_DETAILS_DESTINATION){if(ctx.ride!=null){ctx.ride.destination=null;ctx.ride.destinationSpec="";}ctx.pending=ConversationContext.Pending.RIDE_DESTINATION;addAgent("باشه، مقصد درست رو بگو یا روی نقشه انتخاب کن.");return;}
        if(ctx.pending==ConversationContext.Pending.DELETE_CONFIRM){deleteTarget="";cancelCurrentFlow();return;}
        addAgent("باشه. بگو کدوم بخش درست نیست تا همون رو اصلاح کنیم.");
    }

    private void launchRide(){
        RideSession rs=ctx.ride;if(rs==null||rs.origin==null||rs.destination==null)return;
        if(!isAccessibilityEnabled()){addAgent("برای اجرای Snapp اول Accessibility مربوط به Soroush Agent رو فعال کن.");startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));return;}
        String o=rs.origin.type==LocationRef.Type.CURRENT_LOCATION?"CURRENT":rs.origin.providerQuery();String d=rs.destination.providerQuery();
        AgentCommand c=new AgentCommand(rs.id,AgentCommand.Type.REQUEST_RIDE,o,d,rs.origin.searchText(),rs.destination.searchText(),rs.origin.city,rs.destination.city,rs.origin.lat,rs.origin.lon,rs.destination.lat,rs.destination.lon);
        rs.state=RideSession.State.EXECUTING;rs.touch();ctx.pending=ConversationContext.Pending.NONE;addAgent("Snapp رو با وضعیت واقعی خودش همگام می‌کنم. هر انتخاب قبل از تأیید دوباره بررسی می‌شه.");setActivityStatus("در حال همگام‌سازی با Snapp…");SnappBridge.launch(this,c);
    }

    private void confirmCancelRide(){ctx.pending=ConversationContext.Pending.CANCEL_RIDE_CONFIRM;addAgent("منظورت لغو خودِ سفر/درخواست فعال در Snapp هست. این کار انجام بشه؟");quickRow(new String[]{"تأیید لغو سفر","نه"},new Runnable[]{this::confirmCurrent,this::rejectCurrent});}

    private void showCurrentMap(){
        if(ctx.pending==ConversationContext.Pending.EDIT_ADDRESS){openMap(tempAddress,tempCity,"edit");return;}
        if(ctx.pending==ConversationContext.Pending.ADD_ADDRESS||ctx.pending==ConversationContext.Pending.ADD_TITLE){openMap(tempAddress,tempCity,"add");return;}
        if(ctx.pending==ConversationContext.Pending.RIDE_ORIGIN||ctx.pending==ConversationContext.Pending.SAVED_OR_MAP_ORIGIN||ctx.pending==ConversationContext.Pending.MAP_DETAILS_ORIGIN){openMap(ctx.pendingText,"","rideOrigin");return;}
        if(ctx.pending==ConversationContext.Pending.RIDE_DESTINATION||ctx.pending==ConversationContext.Pending.SAVED_OR_MAP_DESTINATION||ctx.pending==ConversationContext.Pending.MAP_DETAILS_DESTINATION){openMap(ctx.pendingText,"","rideDestination");return;}
        if(ctx.ride!=null&&ctx.lastEntityRole==ConversationContext.EntityRole.ORIGIN){LocationRef l=ctx.ride.origin;openMap(l==null?"":l.searchText(),l==null?"":l.city,"rideOrigin",l==null?Double.NaN:l.lat,l==null?Double.NaN:l.lon);return;}
        if(ctx.ride!=null&&ctx.lastEntityRole==ConversationContext.EntityRole.DESTINATION){LocationRef l=ctx.ride.destination;openMap(l==null?"":l.searchText(),l==null?"":l.city,"rideDestination",l==null?Double.NaN:l.lat,l==null?Double.NaN:l.lon);return;}
        addAgent("برای باز کردن نقشه بگو این نقطه مربوط به مبدأ، مقصد یا یک موقعیت ذخیره‌شده است.");
    }

    private void openMap(String q,String city,String purpose){openMap(q,city,purpose,Double.NaN,Double.NaN);}
    private void openMap(String q,String city,String purpose,double initialLat,double initialLon){
        mapPurpose=purpose;Intent i=new Intent(this,MapPickerActivity.class);i.putExtra("query",q==null?"":q);i.putExtra("city",city==null?"":city);i.putExtra("purpose",purpose);i.putExtra("initialLat",initialLat);i.putExtra("initialLon",initialLon);
        try{startActivityForResult(i,REQ_MAP);}catch(Exception e){mapPurpose="";diag.put("mapError",e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage()));addAgent("صفحه نقشه باز نشد. خطا در Diagnostics ثبت شد.");}
    }

    @Override protected void onActivityResult(int req,int res,Intent data){
        super.onActivityResult(req,res,data);if(req!=REQ_MAP)return;if(res!=RESULT_OK||data==null){mapPurpose="";return;}
        double lat=data.getDoubleExtra("lat",Double.NaN),lon=data.getDoubleExtra("lon",Double.NaN);String ca=s(data.getStringExtra("canonicalAddress")),raw=s(data.getStringExtra("rawQuery")),returnedCity=s(data.getStringExtra("city")),source=s(data.getStringExtra("source"));long verifiedAt=data.getLongExtra("verifiedAt",System.currentTimeMillis());
        String label=raw.trim().isEmpty()?"موقعیت انتخاب‌شده":raw.trim();LocationRef l=new LocationRef(label,raw,ca,returnedCity.isEmpty()?tempCity:returnedCity,lat,lon,LocationRef.Type.MANUAL_PIN,LocationRef.Confidence.CONFIRMED,true,source.isEmpty()?"USER_CONFIRMED_PIN":source,verifiedAt);
        if("rideOrigin".equals(mapPurpose))setRideLocation(l,true);
        else if("rideDestination".equals(mapPurpose))setRideLocation(l,false);
        else if("add".equals(mapPurpose)){
            if(tempTitle.trim().isEmpty()){
                tempAddress=raw.isEmpty()?ca:raw;tempCity=returnedCity;tempCanonical=ca;tempLat=lat;tempLon=lon;tempPointSource=source.isEmpty()?"USER_CONFIRMED_PIN":source;tempVerifiedAt=verifiedAt;
                ctx.pending=ConversationContext.Pending.ADD_TITLE;addAgent("نقطه تأیید شد. چه عنوانی براش بذارم؟");mapPurpose="";return;
            }
            repo.save(tempTitle,tempAddress.isEmpty()?ca:tempAddress,ca,returnedCity.isEmpty()?tempCity:returnedCity,lat,lon,true,"USER_CONFIRMED_PIN","CONFIRMED",verifiedAt);addAgent("«"+tempTitle+"» با مختصات تأییدشده ذخیره شد.");finishSubGoal();
        }else if("edit".equals(mapPurpose)){
            repo.save(tempTitle,tempAddress.isEmpty()?ca:tempAddress,ca,returnedCity.isEmpty()?tempCity:returnedCity,lat,lon,true,"USER_CONFIRMED_PIN","CONFIRMED",verifiedAt);addAgent("موقعیت «"+tempTitle+"» به نقطه‌ای که خودت تأیید کردی تغییر کرد.");finishSubGoal();
        }else if("search".equals(mapPurpose)){addAgent("نقطه‌ای که تأیید کردی: "+(ca.isEmpty()?label:ca));goals.complete(ctx);}
        mapPurpose="";
    }

    private void finishSubGoal(){clearTempOnly();goals.complete(ctx);if(ctx.activeGoal==ConversationContext.Goal.RIDE&&ctx.ride!=null){addAgent("کار موقعیت انجام شد؛ برمی‌گردم به درخواست سفر.");continueRide();}}
    private void clearTempOnly(){tempTitle="";tempCity="";tempAddress="";deleteTarget="";mapPurpose="";tempLat=Double.NaN;tempLon=Double.NaN;tempCanonical="";tempPointSource="";tempVerifiedAt=0L;if(ctx.activeGoal!=ConversationContext.Goal.RIDE)ctx.clearPending();}
    private String resolveTarget(IntentEnvelope e){if(e.target!=null&&!e.target.trim().isEmpty())return e.target.trim();if(ctx.lastEntityRole==ConversationContext.EntityRole.SAVED_PLACE)return ctx.lastEntity;return "";}
    private String displayLoc(LocationRef l){if(l==null)return "-";String extra=l.canonicalAddress.trim().isEmpty()?"":" — "+l.canonicalAddress;return l.label+extra;}
    private static String s(String x){return x==null?"":x;}

    private void showDiag(){new android.app.AlertDialog.Builder(this).setTitle("Diagnostics").setMessage(diag.dump()).setPositiveButton("باشه",null).show();}
    private void refreshConnection(){boolean e=isAccessibilityEnabled();connection.setText(e?"Snapp access: فعال ✓":"Snapp access: غیرفعال — برای اجرا فعالش کن");connection.setTextColor(e?Color.rgb(20,125,60):Color.rgb(170,55,45));}
    private void setActivityStatus(String s){if(activityStatus==null)return;if(s==null||s.trim().isEmpty()){activityStatus.setText("");activityStatus.setVisibility(View.GONE);}else{activityStatus.setText(s);activityStatus.setVisibility(View.VISIBLE);}}
    private boolean isAccessibilityEnabled(){try{String enabled=Settings.Secure.getString(getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);if(enabled==null)return false;String flat=new ComponentName(this,SnappAccessibilityService.class).flattenToString();for(String x:enabled.split(":"))if(x.equalsIgnoreCase(flat))return true;}catch(Exception ignored){}return false;}

    @Override public void onEvent(SnappEvent event,String s){runOnUiThread(()->{
        diag.put("snappEvent",(event==null?SnappEvent.INFO:event).name()+" | "+s);setActivityStatus("");
        if(event==SnappEvent.RIDE_REQUEST_CONFIRMED&&ctx.ride!=null){ctx.ride.state=RideSession.State.COMPLETED;ctx.activeGoal=ConversationContext.Goal.NONE;ctx.clearPending();}
        else if(event==SnappEvent.SAFE_FAILURE){diag.put("error",s);if(ctx.ride!=null&&ctx.ride.state==RideSession.State.EXECUTING)ctx.ride.state=RideSession.State.FAILED;}
        addAgent(s);
    });}
    @Override public void onDebug(String s){runOnUiThread(()->{diag.put("snapp",s);if(s!=null&&s.startsWith("TECH:"))return;setActivityStatus(s);});}
}
