package com.soroush.agent;

import java.util.*;

/**
 * Lightweight statistical semantic router.
 *
 * Unlike the legacy parser, intent is NOT selected with contains("some command").  Each intent owns
 * diverse training utterances. User text and examples are represented with Persian-normalized token,
 * token-prefix and character n-gram features, then classified by cosine similarity. This lets unseen
 * paraphrases share evidence with known meanings. Context is applied only after classification.
 *
 * This is intentionally small/offline. A stronger model can implement the same IntentEnvelope contract
 * later without changing Ride, Saved Places, Map or Snapp execution.
 */
public final class SemanticRouter {
    private final Map<SemanticIntent,List<Map<String,Double>>> model=new EnumMap<>(SemanticIntent.class);
    private final Map<SemanticIntent,Map<String,Double>> centroids=new EnumMap<>(SemanticIntent.class);
    private final Map<String,EnumMap<SemanticIntent,Integer>> tokenIntentCounts=new HashMap<>();

    public SemanticRouter(){train();buildCentroids();}

    public IntentEnvelope understand(String raw,ConversationContext ctx){
        String text=semanticNormalize(raw);
        if(text.isEmpty())return new IntentEnvelope(SemanticIntent.UNKNOWN,0,IntentEnvelope.Decision.UNKNOWN,raw,"","","","","",Collections.emptyMap());

        String taskText=stripLeadingSocial(text);
        String intentText=intentView(taskText.isEmpty()?text:taskText);
        List<Map<String,Double>> qvecs=representations(intentText);
        LinkedHashMap<SemanticIntent,Double> scores=new LinkedHashMap<>();
        for(Map.Entry<SemanticIntent,List<Map<String,Double>>> e:model.entrySet()){
            double first=0,second=0;
            for(Map<String,Double> ex:e.getValue()){
                double sim=0;for(Map<String,Double> q:qvecs)sim=Math.max(sim,cosine(q,ex));
                if(sim>first){second=first;first=sim;}else if(sim>second)second=sim;
            }
            double centroid=0;for(Map<String,Double> q:qvecs)centroid=Math.max(centroid,cosine(q,centroids.getOrDefault(e.getKey(),Collections.emptyMap())));
            double lex=tokenEvidence(e.getKey(),intentText);
            double lexMax=maxTokenEvidence(e.getKey(),intentText);
            double score=first*0.42+second*0.08+centroid*0.18+lex*0.12+lexMax*0.20;
            scores.put(e.getKey(),score);
        }

        applyContext(scores,taskText,ctx);
        applySemanticCues(scores,taskText,ctx);
        List<Map.Entry<SemanticIntent,Double>> ranked=new ArrayList<>(scores.entrySet());
        ranked.sort((a,b)->Double.compare(b.getValue(),a.getValue()));
        SemanticIntent best=ranked.get(0).getKey();double top=ranked.get(0).getValue();double second=ranked.size()>1?ranked.get(1).getValue():0;

        // A pending slot is a strong interpretation for noun/address-like fragments, but a clear new
        // command is still allowed to interrupt the old goal.
        if(ctx!=null&&ctx.pending!=ConversationContext.Pending.NONE&&looksLikeSlotValue(taskText) && top<0.53){
            best=SemanticIntent.ANSWER_SLOT;top=Math.max(0.76,top);second=0;
        }

        IntentEnvelope.Decision decision;
        boolean control=best==SemanticIntent.CANCEL_FLOW||best==SemanticIntent.CANCEL_RIDE||best==SemanticIntent.CONFIRM||best==SemanticIntent.REJECT||best==SemanticIntent.SHOW_MAP;
        if(control&&top>=0.42)decision=IntentEnvelope.Decision.ACCEPT;
        else if(top<0.24){best=SemanticIntent.UNKNOWN;decision=IntentEnvelope.Decision.UNKNOWN;}
        else if((top-second)<0.045 && top<0.58){decision=IntentEnvelope.Decision.CLARIFY;}
        else decision=IntentEnvelope.Decision.ACCEPT;

        // Preserve a pure greeting, but if a greeting prefixes a real task the task wins.
        if(!taskText.equals(text) && taskText.length()<2){best=SemanticIntent.GREETING;top=0.96;decision=IntentEnvelope.Decision.ACCEPT;}

        String origin="",destination="",target="",value="",city=SemanticEntityExtractor.cityHint(raw);
        String[] od=SemanticEntityExtractor.rideEndpoints(raw);
        if(od!=null){origin=od[0];destination=od[1];best=SemanticIntent.REQUEST_RIDE;top=Math.max(top,0.88);decision=IntentEnvelope.Decision.ACCEPT;}
        if(best==SemanticIntent.REQUEST_RIDE && od==null)destination=SemanticEntityExtractor.destination(raw);
        if(best==SemanticIntent.CHANGE_ORIGIN)value=SemanticEntityExtractor.changedOrigin(raw);
        if(best==SemanticIntent.CHANGE_DESTINATION)value=SemanticEntityExtractor.changedDestination(raw);
        if(best==SemanticIntent.ADD_SAVED_PLACE)target=SemanticEntityExtractor.probableSavedPlaceTitle(raw);
        if(best==SemanticIntent.EDIT_SAVED_PLACE||best==SemanticIntent.DELETE_SAVED_PLACE||best==SemanticIntent.SHOW_SAVED_PLACE)target=SemanticEntityExtractor.editTarget(raw);

        LinkedHashMap<SemanticIntent,Double> alts=new LinkedHashMap<>();
        for(int i=0;i<Math.min(4,ranked.size());i++)alts.put(ranked.get(i).getKey(),ranked.get(i).getValue());
        return new IntentEnvelope(best,Math.min(1.0,top),decision,raw,origin,destination,target,value,city,alts);
    }

    private void applyContext(Map<SemanticIntent,Double> s,String text,ConversationContext ctx){
        if(ctx==null)return;
        if(ctx.ride!=null&&!ctx.ride.terminal()){
            boost(s,SemanticIntent.CHANGE_ORIGIN,0.025);
            boost(s,SemanticIntent.CHANGE_DESTINATION,0.025);
            boost(s,SemanticIntent.CANCEL_FLOW,0.015);
        }
        if(ctx.pending==ConversationContext.Pending.RIDE_ORIGIN||ctx.pending==ConversationContext.Pending.RIDE_DESTINATION||
           ctx.pending==ConversationContext.Pending.ADD_TITLE||ctx.pending==ConversationContext.Pending.ADD_ADDRESS||
           ctx.pending==ConversationContext.Pending.EDIT_TARGET||ctx.pending==ConversationContext.Pending.EDIT_ADDRESS){
            // Do not artificially force a specific semantic intent. Pending-question interpretation is
            // handled later only for fragment-like answers.
            
        }
        if(ctx.pending==ConversationContext.Pending.RIDE_CONFIRM||ctx.pending==ConversationContext.Pending.LOCATION_CONFIRM){
            boost(s,SemanticIntent.CONFIRM,0.06);boost(s,SemanticIntent.REJECT,0.05);boost(s,SemanticIntent.CORRECT_PREVIOUS,0.03);
        }
    }

    private void boost(Map<SemanticIntent,Double> s,SemanticIntent i,double v){s.put(i,Math.min(1.0,s.getOrDefault(i,0.0)+v));}

    /**
     * Linguistic concept evidence. These are word-family/grammar cues, not complete command phrases.
     * They make the local classifier sensitive to concepts such as keep/remove/correct/cancel even
     * when the user's sentence has never appeared in the training examples.
     */
    private void applySemanticCues(Map<SemanticIntent,Double> s,String raw,ConversationContext ctx){
        String n=PersianText.norm(raw);List<String> ws=Arrays.asList(n.split(" "));
        boolean location=hasStem(ws,"آدرس","ادرس","لوکیشن","موقعیت","مکان","پین","جای","نقطه");
        boolean saved=location||hasStem(ws,"ذخیره","ذخیر","حافظه","آدرسها","ادرسها");
        boolean keep=hasStem(ws,"ذخیر","ثبت","اضاف","نگه","بمون")||n.contains("یادت بمونه")||n.contains("یاد بمونه")||n.contains("داشته باش");
        boolean negKeep=(hasStem(ws,"نگه")&&hasStem(ws,"ندار","نکن","نخوام","نمیخوام"))||((hasStem(ws,"نخوام","نمیخوام","نمی خوام")||n.contains("دیگه نمیخوام"))&&hasStem(ws,"داشت","داشته","بمون"))||hasStem(ws,"فراموش","حذف","پاک","بردار")||n.contains("یادت نمونه")||n.contains("یاد نمونه");
        boolean edit=hasStem(ws,"عوض","تغییر","اصلاح","ویرایش","ادیت","درست","اشتباه","غلط");
        boolean ride=hasStem(ws,"اسنپ","ماشین","تاکسی","خودرو","سفر","راننده");
        boolean request=hasStem(ws,"درخواست","بگیر")||(ride&&hasStem(ws,"میخوام","لازم"))||n.contains("ماشین میخوام")||n.contains("اسنپ میخوام");
        boolean cancel=hasStem(ws,"لغو","کنسل","بیخیال","ولش","منصرف","متوقف","رها")||n.contains("ادامه نده")||n.contains("ادامه ندیم")||n.contains("ادامه ندین");
        boolean map=hasStem(ws,"نقشه","پین")&&(hasStem(ws,"انتخاب","مشخص","بزن","دستی","باز"));
        boolean search=hasStem(ws,"پیدا","کجاست","کجاس","جستجو","بگرد");
        boolean origin=hasStem(ws,"مبدا","مبدأ")||n.startsWith("از ");
        boolean destination=hasStem(ws,"مقصد")||hasStem(ws,"برم","بریم","ببر");

        if(negKeep&&saved){boost(s,SemanticIntent.DELETE_SAVED_PLACE,0.24);demote(s,SemanticIntent.ADD_SAVED_PLACE,0.10);}
        else if(keep&&(saved||n.contains("برای بعد")||n.contains("دم دست"))){boost(s,SemanticIntent.ADD_SAVED_PLACE,0.20);demote(s,SemanticIntent.DELETE_SAVED_PLACE,0.08);}
        if(edit&&saved){boost(s,SemanticIntent.EDIT_SAVED_PLACE,0.16);}
        if(cancel){
            if(ride){boost(s,SemanticIntent.CANCEL_RIDE,0.24);demote(s,SemanticIntent.REQUEST_RIDE,0.12);}
            else boost(s,SemanticIntent.CANCEL_FLOW,0.22);
        }else{
            // A ride noun by itself means a request/ride context, never cancellation. This prevents
            // short utterances such as "اسنپ" from competing with CANCEL_RIDE merely because
            // cancellation training examples also mention Snapp/ride nouns.
            if(ride){boost(s,SemanticIntent.REQUEST_RIDE,0.24);demote(s,SemanticIntent.CANCEL_RIDE,0.20);}
            if(request){boost(s,SemanticIntent.REQUEST_RIDE,0.18);demote(s,SemanticIntent.CANCEL_RIDE,0.14);}
        }
        if(map)boost(s,SemanticIntent.SHOW_MAP,0.18);
        if(search&&location)boost(s,SemanticIntent.SEARCH_LOCATION,0.16);
        if(origin&&edit)boost(s,SemanticIntent.CHANGE_ORIGIN,0.16);
        if(destination&&edit)boost(s,SemanticIntent.CHANGE_DESTINATION,0.16);
        if(ctx!=null&&edit&&ctx.lastEntityRole!=ConversationContext.EntityRole.NONE)boost(s,SemanticIntent.CORRECT_PREVIOUS,0.13);
    }

    private static final String[] CONTROL_VOCAB={
            "سلام","درود","اسنپ","درخواست","ماشین","تاکسی","سفر","راننده","لغو","کنسل","مبدا","مبدأ","مقصد",
            "نقشه","تایید","تأیید","ذخیره","موقعیت","لوکیشن","آدرس","ادرس","ویرایش","اصلاح","بیخیال","منصرف"};

    /** Generic typo tolerance for short control/intent vocabulary. Location names are deliberately
     * excluded so a fuzzy language correction can never rewrite an address or POI. */
    private String semanticNormalize(String raw){
        String n=PersianText.norm(raw);if(n.isEmpty())return n;
        String[] parts=n.split(" ");
        for(int i=0;i<parts.length;i++){
            String w=parts[i];if(w.length()<3)continue;String best=w;int bestD=99;
            for(String v0:CONTROL_VOCAB){String v=PersianText.norm(v0);int max=Math.max(w.length(),v.length());if(Math.abs(w.length()-v.length())>2)continue;int d=PersianText.editDistance(w,v);int allowed=max>=6?2:1;if(d<=allowed&&d<bestD){bestD=d;best=v;}}
            if(bestD<99)parts[i]=best;
        }
        return String.join(" ",parts);
    }

    private boolean hasStem(List<String> ws,String...stems){
        for(String w:ws)for(String st:stems){String a=PersianText.norm(st);if(w.equals(a)||w.startsWith(a)||w.endsWith(a))return true;}
        return false;
    }
    private void demote(Map<SemanticIntent,Double> s,SemanticIntent i,double v){s.put(i,Math.max(0,s.getOrDefault(i,0.0)-v));}

    private boolean looksLikeSlotValue(String n){
        if(n.length()>90||n.split(" ").length>10)return false;
        // Questions and long verb-heavy clauses are less likely to be a direct slot answer.
        int verbs=0;for(String w:n.split(" "))if(w.endsWith("کن")||w.endsWith("کنم")||w.endsWith("بگیر")||w.endsWith("میخوام")||w.endsWith("میخام"))verbs++;
        return verbs<=1;
    }

    private String stripLeadingSocial(String n){
        String x=n;
        String[] prefixes={"سلام ","سلام، ","درود ","هی ","سلام عزیزم ","سلام خوبی ","سلام حالت خوبه "};
        for(String p:prefixes){String np=PersianText.norm(p);if(x.startsWith(np+" "))return x.substring(np.length()).trim();}
        return x;
    }

    private void train(){
        add(SemanticIntent.GREETING,"سلام","درود","هی","صبح بخیر","عصر بخیر","شب بخیر","سلام خوبی","حالت چطوره","چه خبر");
        add(SemanticIntent.HELP,"چه کارهایی میتونی بکنی","کمکم کن","راهنمایی میخوام","چطوری ازت استفاده کنم","چه قابلیت هایی داری");
        add(SemanticIntent.REQUEST_RIDE,
                "برام ماشین بگیر","یه اسنپ میخوام","تاکسی لازم دارم","میخوام برم دانشگاه","از خونه به دانشگاه برام ماشین بگیر",
                "میشه برام خودرو بگیری","میخوام برم پیش علی","یه ماشین میخوام برای رفتن","برام سفر بگیر","میخوام جایی برم",
                "اسنپ","درخواست","درخواست سفر","ماشین");
        add(SemanticIntent.CHANGE_ORIGIN,
                "مبدا رو عوض کن","از دانشگاه بگیر","نه از خونه نگیر از محل کار بگیر","مبدا اشتباهه","شروع سفر از یه جای دیگه باشه",
                "جایی که ماشین میاد رو تغییر بده","از اینجا نگیر","محل سوار شدن رو عوض کن");
        add(SemanticIntent.CHANGE_DESTINATION,
                "مقصد رو عوض کن","نه میخوام برم خونه","مقصد اشتباهه","جایی که میرم رو تغییر بده","آخر سفر رو بکن دانشگاه",
                "نمیخوام برم اونجا","به یه جای دیگه ببر","آخرش نمیخوام برم دانشگاه برو فرودگاه","اون مقصد قبلی رو نمیخوام مقصد جدید فرودگاهه");
        add(SemanticIntent.CANCEL_FLOW,
                "لغو","بیخیال","ولش کن","منصرف شدم","فعلا نمیخوام","ادامه نده","این کار رو کنسل کن","بیخیالش شو","فعلا نه","متوقفش کن",
                "این کار رو نمیخوام ادامه بدیم","فعلا ولش کنیم","بسش کنیم","دیگه ادامه نده","همین کار فعلی رو رها کن");
        add(SemanticIntent.CANCEL_RIDE,
                "سفر رو لغو کن","اسنپ رو کنسل کن","ماشین رو لغو کن","درخواست سفر رو کنسل کن","راننده رو لغو کن","این سفر رو نمیخوام");
        add(SemanticIntent.ADD_SAVED_PLACE,
                "یه آدرس جدید میخوام اضافه کنم","این مکان رو برام ذخیره کن","میخوام یه جا رو داشته باشی","این نقطه رو برای بعد نگه دار",
                "جای مامانم رو یادت بمونه","یه لوکیشن جدید تعریف کنیم","این محل رو به آدرس هام اضافه کن","میخوام مکان جدید ثبت کنم",
                "یه جایی هست میخوام همیشه دم دستم باشه","این جا رو بعدا لازم دارم","محل کار جدیدمو نگه دار","میخوام موقعیت خونه دوستم رو بهت بدم");
        add(SemanticIntent.EDIT_SAVED_PLACE,
                "آدرس علی رو عوض کن","این لوکیشن اشتباهه","اون جایی که برای علی ذخیره کردم درست نیست","موقعیت علی رو درست کن",
                "جای خونه مامان رو اصلاح کن","پین محل کار اشتباهه","میخوام مکان ذخیره شده رو تغییر بدم","لوکیشنشو ادیت کن");
        add(SemanticIntent.DELETE_SAVED_PLACE,
                "آدرس علی رو پاک کن","این موقعیت ذخیره شده رو حذف کن","دیگه این مکان رو نمیخوام","لوکیشن محل کار رو از لیست بردار",
                "دیگه آدرس دفترم رو نگه ندار","این مکان رو از حافظه ات پاک کن","این آدرس رو یادت نمونه","موقعیت خونه رو فراموش کن","این جای ذخیره شده رو دیگه نداشته باش");
        add(SemanticIntent.SHOW_SAVED_PLACE,
                "آدرس علی چیه","موقعیت خونه رو نشون بده","لوکیشن ذخیره شده مامان کجاست","آدرس محل کارمو بگو");
        add(SemanticIntent.SEARCH_LOCATION,
                "آدرس بیمارستان رو پیدا کن","بوعلی کجاست","این مکان رو روی نقشه پیدا کن","دنبال یه آدرس میگردم","موقعیت دانشگاه رو پیدا کن");
        add(SemanticIntent.SHOW_MAP,
                "روی نقشه انتخاب میکنم","نقشه رو باز کن","میخوام خودم روی نقشه بزنم","بذار روی نقشه مشخص کنم","دیدن روی نقشه","نقطه رو دستی انتخاب کنم");
        add(SemanticIntent.USE_CURRENT_LOCATION,
                "از موقعیت فعلی","از همینجا","اینجا مبدا باشه","لوکیشن الانم","از جایی که هستم");
        add(SemanticIntent.CONFIRM,"تایید","درسته","همینه","بله","آره","اوکی","انجام بده","ادامه بده");
        add(SemanticIntent.REJECT,"نه","این نیست","اشتباهه","غلطه","درست نیست","این رو نمیخوام","نه این یکی");
        add(SemanticIntent.CORRECT_PREVIOUS,"منظورم اون نبود","اصلاحش کن","اشتباه گفتم","منظورم این یکی بود","قبلی رو درست کن","نه منظورم بیمارستانشه",
                "این آدرسی که انتخاب کردی غلطه","اون نقطه ای که انتخاب شد درست نیست","چیزی که قبلا انتخاب کردی اشتباهه","همون قبلی رو اصلاح کن");
    }

    private void buildCentroids(){
        for(Map.Entry<SemanticIntent,List<Map<String,Double>>> e:model.entrySet()){
            HashMap<String,Double> c=new HashMap<>();
            for(Map<String,Double> v:e.getValue())for(Map.Entry<String,Double> f:v.entrySet())c.put(f.getKey(),c.getOrDefault(f.getKey(),0.0)+f.getValue());
            if(!e.getValue().isEmpty())for(Map.Entry<String,Double> f:new ArrayList<>(c.entrySet()))c.put(f.getKey(),f.getValue()/e.getValue().size());
            double n=0;for(double x:c.values())n+=x*x;n=Math.sqrt(n);if(n>0)for(Map.Entry<String,Double> f:new ArrayList<>(c.entrySet()))c.put(f.getKey(),f.getValue()/n);
            centroids.put(e.getKey(),c);
        }
    }

    private void add(SemanticIntent i,String... examples){
        ArrayList<Map<String,Double>> v=new ArrayList<>();
        for(String e:examples){
            String ie=intentView(e);v.add(vector(ie));HashSet<String> seen=lexUnits(ie);
            for(String w:seen){EnumMap<SemanticIntent,Integer> m=tokenIntentCounts.computeIfAbsent(w,k->new EnumMap<>(SemanticIntent.class));m.put(i,m.getOrDefault(i,0)+1);}
        }
        model.put(i,v);
    }

    private double tokenEvidence(SemanticIntent intent,String raw){
        HashSet<String> seen=lexUnits(raw);
        double num=0,den=0;
        for(String w:seen){EnumMap<SemanticIntent,Integer> m=tokenIntentCounts.get(w);if(m==null)continue;int total=0,classes=0;for(int c:m.values()){total+=c;classes++;}if(total==0)continue;int own=m.getOrDefault(intent,0);double specificity=1.0/Math.max(1,classes);double weight=0.7+1.3*specificity;num+=weight*((double)own/total);den+=weight;}
        return den==0?0:num/den;
    }

    private double maxTokenEvidence(SemanticIntent intent,String raw){
        double best=0;HashSet<String> seen=lexUnits(raw);
        for(String w:seen){EnumMap<SemanticIntent,Integer> m=tokenIntentCounts.get(w);if(m==null)continue;int total=0;for(int c:m.values())total+=c;if(total==0)continue;int own=m.getOrDefault(intent,0);double p=(double)own/total;if(m.size()==1)p=Math.min(1.0,p+0.15);best=Math.max(best,p);}
        return best;
    }

    private String intentView(String raw){
        String n=PersianText.norm(raw);
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("^(.{2,55}?)\\s+(رو|را)\\s+(.+)$").matcher(n);
        if(m.find()){
            String obj=m.group(1),rest=m.group(3);String no=PersianText.norm(obj);
            boolean structural=no.contains("مبدا")||no.contains("مبدأ")||no.contains("مقصد")||no.contains("سفر")||no.contains("اسنپ")||no.contains("ماشین");
            if(!structural)n="مکان "+m.group(2)+" "+rest;
        }
        return n;
    }

    private HashSet<String> lexUnits(String raw){
        String[] w=PersianText.norm(raw).split(" ");HashSet<String> out=new HashSet<>();
        for(int i=0;i<w.length;i++){if(w[i].length()>=2)out.add("u:"+w[i]);if(i+1<w.length&&w[i].length()>=2&&w[i+1].length()>=2)out.add("b:"+w[i]+"_"+w[i+1]);if(i+2<w.length&&w[i].length()>=2&&w[i+1].length()>=2&&w[i+2].length()>=2)out.add("t:"+w[i]+"_"+w[i+1]+"_"+w[i+2]);}
        return out;
    }

    private List<Map<String,Double>> representations(String raw){
        String n=PersianText.norm(raw);ArrayList<Map<String,Double>> out=new ArrayList<>();out.add(vector(n));
        String[] w=n.split(" ");
        if(w.length>=5){
            int min=3,max=Math.min(7,w.length);
            for(int len=min;len<=max;len++)for(int i=0;i+len<=w.length;i++){StringBuilder b=new StringBuilder();for(int j=i;j<i+len;j++){if(b.length()>0)b.append(' ');b.append(w[j]);}out.add(vector(b.toString()));}
        }
        return out;
    }

    private Map<String,Double> vector(String raw){
        String n=PersianText.norm(raw);HashMap<String,Double> f=new HashMap<>();
        String compact=" "+n.replaceAll("\\s+"," ")+" ";
        for(String w:n.split(" ")){
            if(w.isEmpty())continue;inc(f,"w:"+w,1.8);
            if(w.length()>=4)inc(f,"p:"+w.substring(0,Math.min(4,w.length())),0.8);
            if(w.length()>=5)inc(f,"s:"+w.substring(Math.max(0,w.length()-4)),0.55);
        }
        for(int k=3;k<=5;k++)for(int i=0;i+k<=compact.length();i++){
            String g=compact.substring(i,i+k);if(g.trim().length()<2)continue;inc(f,"c"+k+":"+g,k==3?0.35:(k==4?0.48:0.56));
        }
        double norm=0;for(double x:f.values())norm+=x*x;norm=Math.sqrt(norm);if(norm>0)for(Map.Entry<String,Double> e:new ArrayList<>(f.entrySet()))f.put(e.getKey(),e.getValue()/norm);
        return f;
    }
    private void inc(Map<String,Double> m,String k,double v){m.put(k,m.getOrDefault(k,0.0)+v);}
    private double cosine(Map<String,Double> a,Map<String,Double> b){Map<String,Double> x=a.size()<b.size()?a:b,y=x==a?b:a;double d=0;for(Map.Entry<String,Double> e:x.entrySet())d+=e.getValue()*y.getOrDefault(e.getKey(),0.0);return d;}
}
