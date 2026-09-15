const $ = (q) => document.querySelector(q);
const state = {
  pending: null,
  deferredInstall: null,
  map: null,
  marker: null,
  selectedPoint: null,
  geoCache: null,
  searchCity: localStorage.getItem('soroush_web_search_city') || localStorage.getItem('nc_web_search_city') || 'تهران',
  confirmMap: null,
  confirmMarker: null,
  lastResolvedLocation: null,
  geocodeCache: new Map(),
  nominatimLastAt: 0,
  flowVersion: 0,
  activeRide: null,
  get places(){
    const current=localStorage.getItem('soroush_web_places');
    if(current) return JSON.parse(current);
    const legacy=localStorage.getItem('nc_web_places');
    if(legacy){ localStorage.setItem('soroush_web_places', legacy); return JSON.parse(legacy); }
    return [];
  },
  set places(v){ localStorage.setItem('soroush_web_places', JSON.stringify(v)); renderPlaces(); },
  get senior(){
    const current=localStorage.getItem('soroush_web_senior');
    if(current) return JSON.parse(current);
    const legacy=localStorage.getItem('nc_web_senior');
    if(legacy){ localStorage.setItem('soroush_web_senior', legacy); return JSON.parse(legacy); }
    return {enabled:true,wallet:500000};
  },
  set senior(v){ localStorage.setItem('soroush_web_senior', JSON.stringify(v)); renderSenior(); }
};

const normalizeMap = [
  [/ي/g,'ی'],[/ك/g,'ک'],[/ۀ|ة/g,'ه'],[/‌/g,' '],[/خونه/g,'خانه'],[/محلکار/g,'محل کار'],[/دانش گاه/g,'دانشگاه'],[/اسنب/g,'اسنپ']
];
function norm(s=''){ s=String(s).trim().toLowerCase(); for(const [a,b] of normalizeMap)s=s.replace(a,b); return s.replace(/[،,:;!?؟.\-_/\\()\[\]{}]+/g,' ').replace(/\s+/g,' ').trim(); }
function levenshtein(a,b){ const m=Array.from({length:a.length+1},()=>Array(b.length+1).fill(0)); for(let i=0;i<=a.length;i++)m[i][0]=i; for(let j=0;j<=b.length;j++)m[0][j]=j; for(let i=1;i<=a.length;i++)for(let j=1;j<=b.length;j++)m[i][j]=Math.min(m[i-1][j]+1,m[i][j-1]+1,m[i-1][j-1]+(a[i-1]===b[j-1]?0:1)); return m[a.length][b.length]; }
function strScore(a,b){ a=norm(a); b=norm(b); if(!a||!b)return 0; if(a===b)return 1; return Math.max(0,1-levenshtein(a,b)/Math.max(a.length,b.length)); }
function tokenScore(a,b){
  const A=norm(a).split(' ').filter(Boolean), B=norm(b).split(' ').filter(Boolean);
  if(!A.length||!B.length)return 0;
  let total=0, hit=0;
  for(const t of A){
    const w=t.length<=2?0.7:1; total+=w;
    let best=0; for(const u of B) best=Math.max(best,strScore(t,u));
    if(best>=0.66) hit+=w*best;
  }
  const coverage=hit/total;
  const lengthPenalty=Math.min(A.length,B.length)/Math.max(A.length,B.length);
  return coverage*(0.72+0.28*lengthPenalty);
}
function savedNameScore(query,name){
  const q=norm(query), n=norm(name);
  if(!q||!n)return 0;
  if(q===n)return 1;
  const full=strScore(q,n);
  const tok=tokenScore(q,n);
  let score=Math.max(full,tok);
  const qTokens=q.split(' ').filter(Boolean), nTokens=n.split(' ').filter(Boolean);
  if(qTokens.length>nTokens.length && q.startsWith(n+' ')) score*=0.72;
  if(nTokens.length>qTokens.length && n.startsWith(q+' ')) score*=0.82;
  return score;
}
function savedLookupForms(text){
  const base=norm(text);
  if(!base)return [];
  const forms=new Set([base]);
  // Remove conversational shells and relationship/location words only for PERSONAL saved-place lookup.
  // This lets "منزل لیلا", "خونه لیلا", "می‌رم منزل لیلا" resolve to a saved place named "لیلا"
  // without changing the user's text for public map search.
  let x=base
    .replace(/^(?:من|ما)?\s*(?:می ?رم|میرم|می ?خوام برم|میخوام برم|می ?خواهم برم)\s+/,'')
    .replace(/^(?:بریم|برو|ببر|منو ببر)\s+(?:به\s+)?/,'')
    .replace(/^(?:به|تا|مقصد|پیش|نزد|کنار)\s+/,'')
    .trim();
  if(x)forms.add(x);
  const relation=/^(?:منزل|خانه|خونه|خانه ی|خانه‌ی|خونه ی|خونه‌ی|محل|آدرس|ادرس|پیش|نزد|کنار)\s+/;
  let y=x.replace(relation,'').trim();
  if(y)forms.add(y);
  // Also tolerate Persian ezafe written separately after a relation word.
  y=x.replace(/^(?:منزل|خانه|خونه)\s+(?:ی\s+)?/,'').trim();
  if(y)forms.add(y);
  return [...forms];
}

function exactPersonalMention(text){
  const n=norm(text);
  if(!n)return null;
  const shells=[
    /^(?:به|تا|مقصد|به مقصد)\s+/,
    /^(?:منزل|خانه|خانه ی|محل|آدرس|ادرس|پیش|نزد|کنار)\s+/,
    /^(?:میرم|می رم|میخوام برم|می خوام برم|میخواهم برم|می خواهم برم)\s+(?:(?:به|پیش|نزد|کنار)\s+)?/,
    /^(?:بریم|برو|ببر|منو ببر)\s+(?:(?:به|پیش|نزد|کنار)\s+)?/
  ];
  const variants=new Set([n]);
  let changed=n;
  for(let pass=0;pass<4;pass++){
    let before=changed;
    for(const r of shells) changed=changed.replace(r,'').trim();
    variants.add(changed);
    if(changed===before)break;
  }
  // Strip relationship words even when another light word precedes the saved name.
  const relational=n.replace(/^(?:به|تا|مقصد|به مقصد)\s+/,'').replace(/^(?:منزل|خانه|خانه ی|محل|آدرس|ادرس|پیش|نزد|کنار)\s+/,'').trim();
  if(relational)variants.add(relational);
  for(const p of state.places){
    const names=[p.name,...(p.aliases||[])].map(norm).filter(Boolean);
    for(const name of names){
      if([...variants].some(v=>v===name)) return p;
      // Exact saved label as the semantic head/tail of a relational phrase, never a fuzzy substring.
      const esc=name.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
      const re=new RegExp(`(?:^|\\s)(?:منزل|خانه|محل|آدرس|ادرس)?\\s*${esc}(?:$|\\s)`);
      if(re.test(n)) return p;
    }
  }
  return null;
}

function personalReferenceForms(text){
  // Ordered from most specific/raw to most relationship-stripped form.
  // Example: «منزل لیلا» -> [«منزل لیلا», «لیلا»].
  const seen=new Set();
  const out=[];
  for(const f of savedLookupForms(text)){
    const n=norm(f);
    if(n && !seen.has(n)){ seen.add(n); out.push(n); }
  }
  return out;
}
function personalLabels(place){
  return [place.name,...(place.aliases||[])].map(norm).filter(Boolean);
}
function labelTokens(label){ return norm(label).split(' ').filter(Boolean); }

function exactSavedMatches(text){
  const forms=personalReferenceForms(text);
  const matches=[];
  for(const p of state.places){
    if(personalLabels(p).some(label=>forms.includes(label))) matches.push({...p,semantic:1,matchType:'full-exact'});
  }
  return matches;
}

function exactTokenPersonalMatches(text){
  const forms=personalReferenceForms(text);
  const byId=new Map();
  for(const p of state.places){
    const labels=personalLabels(p);
    let strength=0;
    for(const q of forms){
      const qTokens=labelTokens(q);
      if(!qTokens.length) continue;
      for(const label of labels){
        const lTokens=labelTokens(label);
        if(q===label){ strength=Math.max(strength,1); continue; }

        // A single spoken/name token can refer to ANY token in a saved multi-part name.
        // Examples: حسن -> محمد حسن, لیلا -> لیلا حسینی.
        if(qTokens.length===1 && q.length>=2 && lTokens.includes(q)){
          strength=Math.max(strength,.97);
          continue;
        }

        // Multi-token references may identify a subset in any order.
        // Example: حسن محمد -> محمد حسن.
        if(qTokens.length>=2 && qTokens.every(t=>lTokens.includes(t))){
          const coverage=qTokens.length/lTokens.length;
          strength=Math.max(strength,.94 + Math.min(.04,coverage*.04));
        }
      }
    }
    if(strength>0) byId.set(p.id||p.name,{...p,semantic:strength,matchType:'token-exact'});
  }
  return [...byId.values()].sort((a,b)=>b.semantic-a.semantic || String(a.name).localeCompare(String(b.name),'fa'));
}

function fuzzyPersonalMatches(text){
  const forms=personalReferenceForms(text);
  const out=[];
  for(const p of state.places){
    const labels=personalLabels(p);
    let semantic=0;
    for(const q of forms){
      const qTokens=labelTokens(q);
      for(const label of labels){
        let score=savedNameScore(q,label);
        const lTokens=labelTokens(label);
        // Conservative typo matching on every token. This is lower priority than exact token matches.
        if(qTokens.length===1 && q.length>=3){
          let bestToken=0;
          for(const t of lTokens) bestToken=Math.max(bestToken,strScore(q,t));
          if(bestToken>=.78) score=Math.max(score,.76 + (bestToken-.78)*.60);
        }
        semantic=Math.max(semantic,score);
      }
    }
    if(semantic>=.60) out.push({...p,semantic,matchType:'fuzzy'});
  }
  return out.sort((a,b)=>b.semantic-a.semantic || String(a.name).localeCompare(String(b.name),'fa'));
}

function savedResolution(text){
  const forms=personalReferenceForms(text);
  if(!forms.length || !state.places.length) return {kind:'none',candidates:[]};

  // IMPORTANT: ambiguity is checked BEFORE accepting an exact one-word label.
  // If saved places include «لیلا» and «لیلا حسینی», query «لیلا» is ambiguous.
  // If saved places include «حسن» and «محمد حسن», query «حسن» is ambiguous.
  const exactFull=exactSavedMatches(text);
  const exactToken=exactTokenPersonalMatches(text);

  // De-duplicate exact-token candidates while preserving all distinct saved places.
  const tokenCandidates=[];
  const seen=new Set();
  for(const c of exactToken){
    const key=c.id||`${c.name}|${c.lat}|${c.lon}`;
    if(!seen.has(key)){ seen.add(key); tokenCandidates.push(c); }
  }

  // A fully specified multi-token saved label should win directly when unique.
  // Example: «محمد حسن» exactly names that place even if another saved place is «حسن».
  const cleanForms=forms.filter(Boolean);
  const exactMulti=exactFull.filter(p=>personalLabels(p).some(label=>cleanForms.includes(label) && labelTokens(label).length>=2));
  if(exactMulti.length===1) return {kind:'exact',candidate:exactMulti[0],candidates:exactMulti,reason:'full-multi-exact'};
  if(exactMulti.length>1) return {kind:'ambiguous',candidates:exactMulti.slice(0,8),reason:'duplicate-full-name'};

  // For a one-token personal reference, ALL saved places containing that exact token participate.
  // Never silently choose the short exact label over a longer saved name containing the same token.
  if(tokenCandidates.length===1){
    const c=tokenCandidates[0];
    const hasExact=exactFull.some(x=>(x.id||x.name)===(c.id||c.name));
    return {kind:hasExact?'exact':'probable',candidate:c,candidates:[c],reason:hasExact?'unique-full-exact':'unique-token'};
  }
  if(tokenCandidates.length>1) return {kind:'ambiguous',candidates:tokenCandidates.slice(0,8),reason:'shared-personal-token'};

  // Only after exact lexical ambiguity is exhausted do we use fuzzy matching.
  const fuzzy=fuzzyPersonalMatches(text);
  if(!fuzzy.length) return {kind:'none',candidates:[]};
  const top=fuzzy[0], second=fuzzy[1];
  const margin=top.semantic-(second?.semantic||0);
  if(top.semantic>=.78 && (!second || margin>=.12)) return {kind:'probable',candidate:top,candidates:fuzzy,reason:'fuzzy-unique'};
  if(top.semantic>=.68 && second && second.semantic>=.64) return {kind:'ambiguous',candidates:fuzzy.slice(0,5),reason:'fuzzy-ambiguous'};
  return {kind:'weak',candidates:fuzzy};
}
function isGreetingOnly(text){ const n=norm(text); return /^(سلام|درود|سلام عزیزم|سلام خوبی|سلام خوب هستی|صبح بخیر|شب بخیر|عصر بخیر)$/.test(n); }
function cityInResult(item, city=state.searchCity){ const hay=norm(`${item.display_name||''} ${Object.values(item.address||{}).join(' ')}`); return norm(city) && hay.includes(norm(city)); }
function strictCityInResult(item,city=state.searchCity){
  const c=norm(city); if(!c)return false; const a=item.address||{};
  const locals=[a.city,a.town,a.municipality,a.village,a.locality,a.suburb].map(norm).filter(Boolean);
  return locals.some(x=>x===c||x.includes(c)||c.includes(x));
}
function cityBiasScore(item){ return strictCityInResult(item)?0.26:(cityInResult(item)?0.025:0); }
function geoDistanceKm(a,b){
  const R=6371,toRad=x=>x*Math.PI/180; const lat1=+a.lat,lon1=+a.lon,lat2=+b.lat,lon2=+b.lon;
  if(![lat1,lon1,lat2,lon2].every(Number.isFinite))return 9999;
  const dLat=toRad(lat2-lat1),dLon=toRad(lon2-lon1); const h=Math.sin(dLat/2)**2+Math.cos(toRad(lat1))*Math.cos(toRad(lat2))*Math.sin(dLon/2)**2;
  return 2*R*Math.asin(Math.sqrt(h));
}

const rideWords=['اسنپ','ماشین','تاکسی','خودرو','سواری'];
const fillerWords=['سلام عزیزم','سلام','عزیزم','لطفا','لطفاً','خواهش میکنم','خواهش می کنم','میشه','می شه','برام','برای من'];
function hasRideIntent(t){ const n=norm(t); return rideWords.some(w=>n.includes(w)) || /منو ببر|میخوام برم|می خوام برم/.test(n); }
function cleanPlace(t){ let n=norm(t); [...rideWords,'بگیر','میخوام','می خوام','میخواهم','لازم دارم','برام','برای من','رو','را','لطفا','لطفاً'].forEach(w=>{n=n.replace(new RegExp(`(^|\\s)${w}(?=\\s|$)`,'g'),' ')}); return n.replace(/\s+/g,' ').trim(); }
function parseRide(raw){ let n=norm(raw); if(!hasRideIntent(n))return null; fillerWords.forEach(f=>n=n.replace(new RegExp(`(^|\\s)${f}(?=\\s|$)`,'g'),' ')); n=n.replace(/\s+/g,' ').trim(); let m;
  m=n.match(/(?:^|\s)از\s+(.+?)\s+(?:به\s+مقصد|به|تا)\s+(.+)$/); if(m)return {origin:cleanPlace(m[1]),destination:cleanPlace(m[2])};
  m=n.match(/(?:به\s+مقصد|مقصد|تا)\s+(.+)$/); if(m)return {origin:null,destination:cleanPlace(m[1])};
  m=n.match(/(?:^|\s)به\s+(.+)$/); if(m)return {origin:null,destination:cleanPlace(m[1])};
  m=n.match(/(?:میخوام|می خوام)\s+برم\s+(.+)$/); if(m)return {origin:null,destination:cleanPlace(m[1])};
  m=n.match(/منو\s+ببر\s+(?:به\s+)?(.+)$/); if(m)return {origin:null,destination:cleanPlace(m[1])};
  return {origin:null,destination:null};
}

const addressMarkers=['خیابان','خیابون','کوچه','بن بست','بنبست','پلاک','بلوار','میدان','بزرگراه','اتوبان','چهارراه','محله','منطقه'];
function addressIntent(query){ const n=norm(query).replace(/خیابون/g,'خیابان').replace(/بنبست/g,'بن بست'); const house=(n.match(/پلاک\s*(\d{1,6})/)||[])[1]||null; const markers=addressMarkers.filter(x=>n.includes(x)); return {raw:query,n,house,markers,tokens:n.split(' ').filter(Boolean),kind:(markers.length>=2||house)?'structured':markers.length?'address':'named'}; }
function semanticRank(intent,item){ const corpus=norm(`${item.display_name||''} ${item.name||''}`).replace(/خیابون/g,'خیابان').replace(/بنبست/g,'بن بست'); const ct=corpus.split(' ').filter(Boolean); let hit=0,total=0; const missing=[],warnings=[];
  for(const t of intent.tokens){ if(['به','از','تا','در','مقصد','برای','یک','یه'].includes(t))continue; if(intent.house && t===intent.house)continue; const weight=(addressMarkers.includes(t)||t==='بن'||t==='بست')?.35:(t.length<=2?.5:(intent.kind==='structured'?2.2:1.4)); total+=weight; let best=0,bestToken=''; for(const c of ct){const s=strScore(t,c);if(s>best){best=s;bestToken=c}} if(ct.includes(t)){hit+=weight}else if(best>=.82){hit+=weight*best;if(weight>=1.2&&bestToken!==t)warnings.push(`شما «${t}» گفتید/نوشتید، اما نتیجه «${bestToken}» است.`)}else if(weight>=1.8)missing.push(t); }
  if(intent.house){ const exactHouse=new RegExp(`(^|\\D)${intent.house}(\\D|$)`).test(corpus); if(exactHouse)hit+=1.5; else missing.push(`پلاک ${intent.house}`); total+=1.5; }
  return {score:total?Math.max(0,Math.min(1,hit/total)):0,missing:[...new Set(missing)],warnings:[...new Set(warnings)]};
}

function addMsg(role,text,actions=[]){ const wrap=document.createElement('div'); wrap.className=`msg ${role}`; const bubble=document.createElement('div'); bubble.className='bubble'; bubble.textContent=text; if(actions.length){ const row=document.createElement('div'); row.className='actionRow'; actions.forEach(a=>{const b=document.createElement('button');b.textContent=a.label;b.className=a.primary?'primaryAction':'';b.onclick=a.onClick;row.appendChild(b)}); bubble.appendChild(row); } wrap.appendChild(bubble); $('#chat').appendChild(wrap); requestAnimationFrame(()=>wrap.scrollIntoView({behavior:'smooth',block:'end'})); }
function userMsg(t){addMsg('user',t)}
function agentMsg(t,a=[]){addMsg('agent',t,a)}
function setPending(x){state.pending=x}
function bumpFlow(){ state.flowVersion=(state.flowVersion||0)+1; return state.flowVersion; }
function cancelActiveFlow(message='باشه، این کار لغو شد.'){
  bumpFlow(); setPending(null); state.activeMapResolution=null;
  try{closeConfirmMap();}catch{}
  agentMsg(message);
}
function isRideCancelIntent(text){
  const n=norm(text);
  return /(?:سفر|اسنپ|ماشین|تاکسی|خودرو).*(?:لغو|کنسل)/.test(n)
    || /(?:لغو|کنسل).*(?:سفر|اسنپ|ماشین|تاکسی|خودرو)/.test(n);
}
function isCancelIntent(text){
  const n=norm(text);
  return /^(?:لغو|لغوش کن|کنسل|کنسلش کن|نمیخوام|نمی خواهم|نمیخام|بیخیال|بی خیال|ولش کن|منصرف شدم|تمام|تموم)$/.test(n)
    || /^(?:نه[، ]*)?(?:دیگه )?(?:نمیخوام|نمی خواهم|بیخیال|بی خیال|ولش کن)/.test(n);
}
function isCorrectionIntent(text){
  const n=norm(text);
  return /^(?:اصلاح کن|ویرایش کن|درستش کن|عوضش کن|اشتباهه|اشتباه است|درست نیست|این نیست|نه این نیست|میخوام اصلاح کنم|می خوام اصلاح کنم|میخوام ویرایش کنم|می خوام ویرایش کنم)$/.test(n)
    || /(?:آدرس|ادرس|مقصد|مبدأ|مبدا).*(?:اصلاح|ویرایش|اشتباه)/.test(n);
}
function fuzzyTokenIn(tokens, targets, threshold=.72){
  return tokens.some(t=>targets.some(x=>strScore(t,x)>=threshold));
}
function isAddPlaceIntent(text){
  const n=norm(text);
  const tokens=n.split(' ').filter(Boolean);
  const objectWords=['آدرس','ادرس','نشانی','مکان','موقعیت','جا'];
  const actionWords=['اضافه','افزودن','افزود','ذخیره','ثبت','تعریف','بساز','ساختن'];
  const wantWords=['میخوام','می خواهم','میخواهم','می خوام','میخوامش','لازم دارم'];
  const newWords=['جدید','تازه'];
  const hasObject=fuzzyTokenIn(tokens,objectWords,.74);
  const hasAction=fuzzyTokenIn(tokens,actionWords,.70) || /اضافه\s*(?:کن|کردن|بکن)|ذخیره\s*(?:کن|کردن)|ثبت\s*(?:کن|کردن)|تعریف\s*(?:کن|کردن)/.test(n);
  const hasWant=wantWords.some(w=>n.includes(w));
  const hasNew=fuzzyTokenIn(tokens,newWords,.78);
  if(hasObject && hasAction) return true;
  if(hasObject && hasNew && hasWant) return true;
  if(hasObject && /(?:میخوام|می خواهم|میخواهم|می خوام).*(?:اضافه|ثبت|ذخیره|تعریف)/.test(n)) return true;
  if(/(?:یه|یک)\s+(?:آدرس|ادرس|نشانی|مکان|موقعیت)\s+(?:جدید\s+)?(?:میخوام|می خوام|میخواهم)/.test(n)) return true;
  // Also accept a natural one-line save request where the user supplied the address but forgot a title.
  // Example: «تهران دروس کماسایی گلخانه رو ذخیره کن».
  const content=tokens.filter(t=>![...objectWords,...actionWords,...wantWords,...newWords,'را','رو','کن','بکن','شود','کنم','کردن','یه','یک','این','همین'].includes(t));
  if(hasAction && content.length>=2 && !hasRideIntent(n)) return true;
  return false;
}
function isExplicitNewTask(text){ return isAddPlaceIntent(text) || hasRideIntent(text); }

function looksLikePersonalPlacePhrase(text){
  const n=norm(text);
  return /^(?:خانه|منزل|خونه|محل کار|محل|آدرس|ادرس)\s+\S+/.test(n);
}
function savedPlaceButtons(onPick,extra=[]){
  const items=state.places.slice(0,10).map((x,i)=>({label:`${i+1}) ${x.name}`,primary:i===0,onClick:()=>onPick(x)}));
  return items.concat(extra);
}
function askPersonalOrMap(text,{role='destination',origin=null,continuation=null}={}){
  const label=String(text||'').trim();
  const buttons=[
    {label:'از آدرس‌های ذخیره‌شده انتخاب می‌کنم',primary:true,onClick:()=>{
      const onPick = role==='destination' ? (p=>confirmTrip(origin,p,label)) : (p=>continuation?.(p));
      if(!state.places.length){
        agentMsg('هنوز هیچ عنوانی در آدرس‌های ذخیره‌شده نداری. می‌خواهی همین عبارت را روی نقشه جست‌وجو کنم؟',[
          {label:'بله، روی نقشه جست‌وجو کن',primary:true,onClick:()=> role==='destination' ? searchDestination(label,origin,true) : resolveFreeformMapLocation(label,'origin',continuation,()=>{setPending({type:'manualOrigin',continuation});agentMsg('مبدأ را دوباره بنویس.')})},
          {label:'نه، عنوان دیگری می‌گویم',onClick:()=>{setPending(role==='destination'?{type:'destinationText',origin}:{type:'manualOrigin',continuation});agentMsg(role==='destination'?'عنوان یا آدرس مقصد را بنویس.':'عنوان یا آدرس مبدأ را بنویس.')}}
        ]); return;
      }
      agentMsg('کدام عنوان ذخیره‌شده را منظورت بود؟',savedPlaceButtons(onPick,[{label:'هیچ‌کدام؛ روی نقشه جست‌وجو کن',onClick:()=> role==='destination' ? searchDestination(label,origin,true) : resolveFreeformMapLocation(label,'origin',continuation,()=>{setPending({type:'manualOrigin',continuation})})}]));
    }},
    {label:'منظورم یک موقعیت روی نقشه است',onClick:()=> role==='destination' ? searchDestination(label,origin,true) : resolveFreeformMapLocation(label,'origin',continuation,()=>{setPending({type:'manualOrigin',continuation});agentMsg('مبدأ را دوباره بنویس.')})}
  ];
  agentMsg(`عنوان «${label}» بین آدرس‌های ذخیره‌شده‌ات پیدا نشد. منظورت یک عنوان شخصی است که فکر می‌کنی قبلاً ثبت شده، یا می‌خواهی همین عبارت را روی نقشه پیدا کنم؟`,buttons);
}
function extractAddPlacePayload(text){
  const original=String(text||'').trim(); const n=norm(original);
  if(!isAddPlaceIntent(original)) return null;
  const city=explicitCityInQuery(n)||null;
  let x=n;
  // Remove common task shells while preserving the location description.
  x=x.replace(/^(?:میخوام|می خوام|میخواهم|می خواهم)?\s*(?:یه|یک)?\s*(?:آدرس|ادرس|نشانی|مکان|موقعیت|جا)?\s*(?:جدید|تازه)?\s*/,'').trim();
  x=x.replace(/\s*(?:را|رو)?\s*(?:اضافه|افزودن|ذخیره|ثبت|تعریف)\s*(?:کن|بکن|شود|کنم|کردن)?\s*$/,'').trim();
  x=x.replace(/^(?:اضافه|ذخیره|ثبت|تعریف)\s*(?:کن|بکن)?\s*/,'').trim();
  // A residual generic word is not an address.
  if(/^(?:آدرس|ادرس|نشانی|مکان|موقعیت|جا|جدید)$/.test(x)) x='';
  return {city,address:x};
}
function cityScopedAddress(address,city){
  const a=String(address||'').trim(); const c=String(city||'').trim();
  if(!c) return a;
  if(explicitCityInQuery(a)) return a;
  return `${c} ${a}`.trim();
}

async function getGeo(force=false){ if(state.geoCache&&!force)return state.geoCache; if(!navigator.geolocation)throw new Error('مرورگر Location را پشتیبانی نمی‌کند.'); return new Promise((resolve,reject)=>navigator.geolocation.getCurrentPosition(p=>{state.geoCache={lat:p.coords.latitude,lon:p.coords.longitude,accuracy:p.coords.accuracy};resolve(state.geoCache)},()=>reject(new Error('اجازه موقعیت داده نشد یا GPS در دسترس نیست.')),{enableHighAccuracy:true,timeout:12000,maximumAge:30000})); }
function sleep(ms){return new Promise(r=>setTimeout(r,ms));}
async function fetchWithTimeout(url,options={},timeoutMs=5000){
  const controller=new AbortController();
  const timer=setTimeout(()=>controller.abort(),timeoutMs);
  try{return await fetch(url,{...options,signal:controller.signal});}
  finally{clearTimeout(timer);}
}
async function withDeadline(promise,ms,label='operation'){
  let timer;
  try{return await Promise.race([promise,new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error(label+'_timeout')),ms)})]);}
  finally{clearTimeout(timer);}
}
async function nominatimFetch(url){
  const key=url.toString();
  if(state.geocodeCache.has(key)) return structuredClone(state.geocodeCache.get(key));
  const wait=Math.max(0,1100-(Date.now()-state.nominatimLastAt));
  if(wait) await sleep(wait);
  state.nominatimLastAt=Date.now();
  const r=await fetchWithTimeout(url,{headers:{Accept:'application/json'}},5200);
  if(!r.ok) throw Error('nominatim');
  const data=await r.json();
  state.geocodeCache.set(key,data);
  return structuredClone(data);
}
async function reverseGeoDetails(pos){ const u=new URL('https://nominatim.openstreetmap.org/reverse'); u.searchParams.set('format','jsonv2');u.searchParams.set('addressdetails','1');u.searchParams.set('lat',pos.lat);u.searchParams.set('lon',pos.lon);u.searchParams.set('accept-language','fa'); return nominatimFetch(u); }
async function reverseGeo(pos){ const j=await reverseGeoDetails(pos); return j.display_name||`${pos.lat.toFixed(5)}, ${pos.lon.toFixed(5)}`; }
let cityUpdateTimer=null;
async function updateSearchCityFromMap(){
  if(!state.map)return;
  clearTimeout(cityUpdateTimer);
  cityUpdateTimer=setTimeout(async()=>{
    try{
      const c=state.map.getCenter(); const j=await reverseGeoDetails({lat:c.lat,lon:c.lng}); const a=j.address||{};
      let city=a.city||a.town||a.municipality||a.county||a.state_district||'';
      city=String(city).replace(/شهرستان\s*/,'').trim();
      if(city){state.searchCity=city;localStorage.setItem('soroush_web_search_city',city);const el=$('#searchContext');if(el)el.textContent=`تمرکز جست‌وجو: ${city}`;}
    }catch{}
  },850);
}
function uniqueByPlaceId(items){ const seen=new Set(); return items.filter(x=>{const k=x.place_id||x.osm_id||x.display_name;if(seen.has(k))return false;seen.add(k);return true;}); }
const knownIranCities=['تهران','کرج','قم','مشهد','اصفهان','شیراز','تبریز','اهواز','رشت','ساری','قزوین','اراک','همدان','کرمان','یزد','اردبیل','ارومیه','سنندج','بندرعباس','بوشهر'];
function explicitCityInQuery(raw){const n=norm(raw);return knownIranCities.find(c=>new RegExp(`(?:^|\s)${c}(?:\s|$)`).test(n))||null;}
function buildSearchVariants(raw){
  const q=norm(raw).replace(/خیابون/g,'خیابان').replace(/بنبست/g,'بن بست');
  const tokens=q.split(' ').filter(Boolean);
  const explicitCity=explicitCityInQuery(q); const city=explicitCity||state.searchCity||'تهران';
  const variants=[q];
  if(!explicitCity && !tokens.includes(norm(city)))variants.push(`${q}، ${city}`);
  if(addressMarkers.some(m=>q.includes(m)))variants.push(`${city}، ${q}`);
  return [...new Set(variants.filter(Boolean))];
}
function bboxFromResult(item,padRatio=.18){
  const bb=item?.boundingbox?.map(Number);
  let south,north,west,east;
  if(bb?.length===4 && bb.every(Number.isFinite)){[south,north,west,east]=bb;}else{
    const lat=Number(item?.lat),lon=Number(item?.lon);if(!Number.isFinite(lat)||!Number.isFinite(lon))return null;
    south=lat-.015;north=lat+.015;west=lon-.02;east=lon+.02;
  }
  const latPad=Math.max(.006,(north-south)*padRatio),lonPad=Math.max(.008,(east-west)*padRatio);
  return {west:west-lonPad,north:north+latPad,east:east+lonPad,south:south-latPad};
}
function mapViewbox(){ if(!state.map)return null;const b=state.map.getBounds();return {west:b.getWest(),north:b.getNorth(),east:b.getEast(),south:b.getSouth()}; }
async function nominatimSearch(q,limit=8,opts={}){
  const u=new URL('https://nominatim.openstreetmap.org/search');
  u.searchParams.set('format','jsonv2');u.searchParams.set('addressdetails','1');u.searchParams.set('limit',String(limit));
  u.searchParams.set('accept-language','fa');u.searchParams.set('countrycodes','ir');u.searchParams.set('q',q);
  const vb=opts.viewbox||mapViewbox()||(norm(state.searchCity)==='تهران'?{west:51.05,north:35.85,east:51.65,south:35.50}:null);
  if(vb){u.searchParams.set('viewbox',`${vb.west},${vb.north},${vb.east},${vb.south}`);u.searchParams.set('bounded',opts.bounded?'1':'0');}
  return nominatimFetch(u);
}
async function nominatimStructured(parts,limit=8,opts={}){
  const u=new URL('https://nominatim.openstreetmap.org/search');
  u.searchParams.set('format','jsonv2');u.searchParams.set('addressdetails','1');u.searchParams.set('limit',String(limit));
  u.searchParams.set('accept-language','fa');u.searchParams.set('countrycodes','ir');
  for(const k of ['street','city','county','state','country','postalcode']) if(parts[k]) u.searchParams.set(k,parts[k]);
  const vb=opts.viewbox||mapViewbox(); if(vb){u.searchParams.set('viewbox',`${vb.west},${vb.north},${vb.east},${vb.south}`);u.searchParams.set('bounded',opts.bounded?'1':'0');}
  return nominatimFetch(u);
}
function photonToItem(f){
  const p=f.properties||{}, c=f.geometry?.coordinates||[];
  const pieces=[p.name,p.street,p.housenumber,p.district,p.locality,p.city,p.county,p.state,p.postcode,p.country].filter(Boolean);
  return {
    place_id:`photon:${p.osm_type||''}:${p.osm_id||pieces.join('|')}`, osm_id:p.osm_id, osm_type:p.osm_type,
    display_name:[...new Set(pieces.map(String))].join(', '), name:p.name||p.street||p.city||pieces[0]||'',
    lat:String(c[1]), lon:String(c[0]), importance:0, category:p.osm_key||'', type:p.osm_value||'',
    address:{road:p.street,house_number:p.housenumber,suburb:p.district||p.locality,city:p.city,county:p.county,state:p.state,postcode:p.postcode,country:p.country,country_code:p.countrycode},
    _provider:'photon'
  };
}
async function photonSearch(q,limit=10,opts={}){
  const u=new URL('https://photon.komoot.io/api/');u.searchParams.set('q',q);u.searchParams.set('limit',String(limit));u.searchParams.set('lang','fa');
  let focus=opts.focus; if(!focus && !explicitCityInQuery(q) && state.map){const c=state.map.getCenter();focus={lat:c.lat,lon:c.lng};}
  if(focus){u.searchParams.set('lat',String(focus.lat));u.searchParams.set('lon',String(focus.lon));u.searchParams.set('zoom',String(opts.zoom||13));}
  const r=await fetchWithTimeout(u,{headers:{Accept:'application/json'}},4200);if(!r.ok)throw Error('photon');const j=await r.json();return (j.features||[]).map(photonToItem).filter(x=>Number.isFinite(+x.lat)&&Number.isFinite(+x.lon));
}

async function overpassNearbyNameSearch(term,anchor,radius=2200){
  const lat=+anchor?.lat, lon=+anchor?.lon;
  if(!Number.isFinite(lat)||!Number.isFinite(lon)||!term) return [];
  const escaped=String(term).replace(/[\\"]/g,'\\$&');
  const q=`[out:json][timeout:12];(node(around:${radius},${lat},${lon})["name"~"${escaped}",i];way(around:${radius},${lat},${lon})["name"~"${escaped}",i];relation(around:${radius},${lat},${lon})["name"~"${escaped}",i];);out center tags 30;`;
  const u='https://overpass-api.de/api/interpreter?data='+encodeURIComponent(q);
  const r=await fetchWithTimeout(u,{headers:{Accept:'application/json'}},5000); if(!r.ok) throw Error('overpass');
  const j=await r.json();
  return (j.elements||[]).map(e=>{
    const la=e.lat??e.center?.lat, lo=e.lon??e.center?.lon, tags=e.tags||{};
    if(!Number.isFinite(+la)||!Number.isFinite(+lo)) return null;
    const pieces=[tags.name,tags['addr:street'],tags['addr:housenumber'],tags['addr:suburb'],tags['addr:city'],state.searchCity].filter(Boolean);
    return {place_id:`overpass:${e.type}:${e.id}`,osm_id:e.id,osm_type:e.type,display_name:[...new Set(pieces.map(String))].join(', '),name:tags.name||term,lat:String(la),lon:String(lo),address:{road:tags['addr:street']||tags.name,house_number:tags['addr:housenumber'],suburb:tags['addr:suburb'],city:tags['addr:city']||state.searchCity},_provider:'overpass'};
  }).filter(Boolean);
}
function structuredAddressParts(raw){
  const n=norm(raw).replace(/خیابون/g,'خیابان').replace(/بنبست/g,'بن بست');
  let city=explicitCityInQuery(n)||state.searchCity||'تهران';
  const house=(n.match(/پلاک\s*([۰-۹0-9A-Za-z-]+)/)||[])[1]||'';
  const streetMatch=n.match(/خیابان\s+(.+?)(?=\s+(?:کوچه|بن بست|پلاک|محله|منطقه)|$)/);
  const alleyMatch=n.match(/کوچه\s+(.+?)(?=\s+(?:بن بست|پلاک|محله|منطقه)|$)/);
  const deadMatch=n.match(/بن بست\s+(.+?)(?=\s+(?:پلاک|محله|منطقه)|$)/);
  const road=[streetMatch?.[1],alleyMatch?.[1],deadMatch?.[1]].filter(Boolean).join('، ');
  return {city,street:[house,road].filter(Boolean).join(' '),raw:n};
}
function meaningfulTokens(q){return norm(q).split(' ').filter(t=>t && !['به','از','تا','در','برای','مقصد','خیابان','خیابون','کوچه','محله','منطقه','شهر','بن','بست','بنبست'].includes(t) && !knownIranCities.includes(t));}
function splitPlans(raw){
  const q=norm(raw).replace(/خیابون/g,'خیابان').replace(/بنبست/g,'بن بست');
  const tokens=meaningfulTokens(q); const plans=[];
  for(let i=1;i<tokens.length;i++){
    const left=tokens.slice(0,i).join(' '),right=tokens.slice(i).join(' ');
    plans.push({anchor:left,child:right,order:'left'});plans.push({anchor:right,child:left,order:'right'});
  }
  // Also allow one token to be an anchor and all remaining tokens to be searched inside it.
  if(tokens.length>2){for(let i=0;i<tokens.length;i++){plans.push({anchor:tokens[i],child:tokens.filter((_,j)=>j!==i).join(' '),order:'token'});}}
  const seen=new Set();return plans.filter(x=>x.anchor&&x.child&&!seen.has(`${x.anchor}|${x.child}`)&&(seen.add(`${x.anchor}|${x.child}`),true)).slice(0,8);
}
function anchorQuality(item,anchor){
  const hay=norm(`${item.name||''} ${item.display_name||''}`);const base=Math.max(strScore(anchor,item.name||''),tokenScore(anchor,hay));
  return base+(cityInResult(item)?0.12:0);
}
async function hierarchicalSearch(raw){
  const city=state.searchCity||'تهران'; const out=[]; const plans=splitPlans(raw).slice(0,4);
  const started=Date.now();
  for(const plan of plans){
    if(Date.now()-started>6500) break;
    let anchors=[];
    try{anchors=await photonSearch(`${plan.anchor}، ${city}`,6);}catch{}
    if(!anchors.length && Date.now()-started<5200){try{anchors=await nominatimSearch(`${plan.anchor}، ${city}`,4,{bounded:false});}catch{}}
    anchors=anchors.map(x=>({...x,_aq:anchorQuality(x,plan.anchor)})).sort((a,b)=>b._aq-a._aq).slice(0,1);
    for(const anchor of anchors){
      if(anchor._aq<.45)continue;
      const focus={lat:+anchor.lat,lon:+anchor.lon};
      const childQueries=[`${plan.child}، ${plan.anchor}، ${city}`,`${plan.child}، ${city}`];
      let found=[];
      for(const cq of childQueries){
        if(Date.now()-started>6500) break;
        try{found=await photonSearch(cq,7,{focus,zoom:16});}catch{found=[]}
        if(found.length){for(const row of found){row._queryVariant=cq;row._anchor=anchor.display_name;row._anchorTerm=plan.anchor;row._childTerm=plan.child;row._hierarchical=true;out.push(row);}break;}
      }
      if(!found.length && Date.now()-started<5200){
        const vb=bboxFromResult(anchor,.40);
        if(vb){try{const rows=await nominatimSearch(`${plan.child}، ${plan.anchor}، ${city}`,5,{viewbox:vb,bounded:true});for(const row of rows){row._queryVariant=`${plan.child}، ${plan.anchor}، ${city}`;row._anchor=anchor.display_name;row._anchorTerm=plan.anchor;row._childTerm=plan.child;row._hierarchical=true;out.push(row);}}catch{}}
      }
      if(!found.length && !out.some(x=>x._anchorTerm===plan.anchor&&x._childTerm===plan.child) && Date.now()-started<5000){
        try{const rows=await overpassNearbyNameSearch(plan.child,anchor,2600);for(const row of rows){row._queryVariant=`${plan.child} داخل ${plan.anchor}`;row._anchor=anchor.display_name;row._anchorTerm=plan.anchor;row._childTerm=plan.child;row._hierarchical=true;row._deepLocal=true;out.push(row);}}catch{}
      }
      if(out.length>=18)return out;
    }
  }
  return out;
}

function deepestContextPlan(raw){
  const parsed=parseAddressSegments(raw); if(!parsed.parts.length)return null;
  const child=parsed.parts[parsed.parts.length-1]; const n=norm(parsed.noHouse);
  const idx=n.lastIndexOf(norm(child.label));
  if(idx<=0)return null;
  const parent=n.slice(0,idx).trim();
  if(meaningfulTokens(parent).length<1)return null;
  return {parent,child,city:explicitCityInQuery(raw)||state.searchCity||'تهران'};
}
async function contextualDeepSearch(raw){
  const plan=deepestContextPlan(raw); if(!plan)return [];
  const anchorRows=[];
  try{anchorRows.push(...await photonSearch(`${plan.parent}، ${plan.city}`,10));}catch{}
  if(anchorRows.length<3){try{anchorRows.push(...await nominatimSearch(`${plan.parent}، ${plan.city}`,6,{bounded:false}));}catch{}}
  const parentIntent=addressIntent(plan.parent);
  const anchors=uniqueByPlaceId(anchorRows).map(x=>({x,score:semanticRank(parentIntent,x).score+cityBiasScore(x)})).sort((a,b)=>b.score-a.score).slice(0,2);
  const out=[];
  for(const a of anchors){
    if(a.score<.48)continue; const anchor=a.x; const focus={lat:+anchor.lat,lon:+anchor.lon};
    const queries=[`${plan.child.label}، ${plan.parent}، ${plan.city}`,`${plan.child.name}، ${plan.parent}، ${plan.city}`];
    for(const q of queries){
      try{const rows=await photonSearch(q,10,{focus,zoom:18}); for(const row of rows){const d=geoDistanceKm(anchor,row); if(d<=4.5){row._contextLocked=true;row._contextParent=plan.parent;row._contextChild=plan.child.label;row._contextDistance=d;row._queryVariant=q;out.push(row);}}}catch{}
    }
    try{const rows=await overpassNearbyNameSearch(plan.child.name,anchor,1800);for(const row of rows){row._contextLocked=true;row._contextParent=plan.parent;row._contextChild=plan.child.label;row._contextDistance=geoDistanceKm(anchor,row);row._deepLocal=true;out.push(row);}}catch{}
  }
  return uniqueByPlaceId(out);
}

async function publicSearch(q){
  const intent=addressIntent(q); const direct=[];
  // Preserve already-resolved parent context when a deeper address component is added.
  try{const locked=await withDeadline(contextualDeepSearch(q),5200,'context_locked');direct.push(...locked);}catch{}
  const variants=buildSearchVariants(q).slice(0,3);
  const fastJobs=variants.map(v=>photonSearch(v,10).then(rows=>rows.map(row=>{row._queryVariant=v;row._hierarchical=false;return row})).catch(()=>[]));
  let fastGroups=[];
  try{fastGroups=await withDeadline(Promise.all(fastJobs),5200,'photon_batch');}catch{}
  for(const g of fastGroups) direct.push(...g);

  // One bounded Nominatim request. It must never block the entire UI.
  try{const rows=await nominatimSearch(variants[0]||q,8,{bounded:false});for(const row of rows){row._queryVariant=variants[0]||q;row._hierarchical=false;row._provider='nominatim';direct.push(row);}}catch{}

  if(intent.kind==='structured'||intent.kind==='address'){
    const parts=structuredAddressParts(q);
    if(parts.street){try{const rows=await nominatimStructured({street:parts.street,city:parts.city},6,{bounded:false});for(const row of rows){row._queryVariant=`${parts.street}، ${parts.city}`;row._structured=true;row._provider='nominatim';direct.push(row);}}catch{}}
  }
  let ranked=uniqueByPlaceId(direct).map(x=>({x,r:semanticRank(intent,x)})).sort((a,b)=>(b.r.score+cityBiasScore(b.x)+(b.x._contextLocked?.30:0))-(a.r.score+cityBiasScore(a.x)+(a.x._contextLocked?.30:0)));
  if(ranked[0] && ranked[0].r.score+cityBiasScore(ranked[0].x)>=.80)return ranked.map(z=>z.x).slice(0,30);

  let nested=[];
  try{nested=await withDeadline(hierarchicalSearch(q),7600,'hierarchical');}catch{}
  let all=uniqueByPlaceId([...nested,...direct]);
  ranked=all.map(x=>({x,r:semanticRank(intent,x)})).sort((a,b)=>(b.r.score+cityBiasScore(b.x)+(b.x._hierarchical?.10:0)+(b.x._contextLocked?.30:0))-(a.r.score+cityBiasScore(a.x)+(a.x._hierarchical?.10:0)+(a.x._contextLocked?.30:0)));
  if(ranked.length)return ranked.map(z=>z.x).slice(0,40);

  // Last resort runs in parallel and is time bounded.
  const toks=meaningfulTokens(q).slice(0,4); const fallback=[];
  try{
    const groups=await withDeadline(Promise.all(toks.map(t=>photonSearch(`${t}، ${state.searchCity||'تهران'}`,6).then(rows=>rows.map(row=>{row._partialFallback=true;row._queryVariant=t;return row})).catch(()=>[]))),4800,'fallback');
    for(const g of groups) fallback.push(...g);
  }catch{}
  return uniqueByPlaceId(fallback).slice(0,25);
}

function findHomePlace(){
  const homeTerms=new Set(['خانه','خونه','منزل','خانه من','خونه من','منزل من']);
  return state.places.find(p=>[p.name,...(p.aliases||[])].some(x=>homeTerms.has(norm(x)))) || null;
}
function chooseHomeOrigin(continuation){
  const home=findHomePlace();
  if(home) return continuation(home);
  agentMsg('هنوز موقعیتی با عنوان «خانه» برایت تعریف نشده. اگر واقعاً خانه هستی، می‌توانم با اجازه‌ات موقعیت فعلی را بگیرم و بعد از تأیید به‌عنوان «خانه» ذخیره کنم.',[
    {label:'📍 بله، موقعیت خانه را بگیر',primary:true,onClick:()=>useCurrentOriginAsHome(continuation)},
    {label:'نه، مبدأ دیگری می‌گویم',onClick:()=>{setPending({type:'manualOrigin',continuation});agentMsg('باشه. نام یا آدرس مبدأ را بنویس.')}}
  ]);
}
async function useCurrentOriginAsHome(continuation){
  agentMsg('با اجازه‌ات موقعیت فعلی را از GPS می‌گیرم…');
  try{
    const pos=await getGeo(true);
    const address=await reverseGeo(pos);
    const candidate={name:'خانه',address,lat:pos.lat,lon:pos.lon};
    agentMsg(`موقعیت فعلی تقریباً اینجاست:
${address}
دقت GPS حدود ${Math.max(1,Math.round(pos.accuracy))} متر است.

این موقعیت را به‌عنوان «خانه» ذخیره کنم و مبدأ سفر قرار بدهم؟`,[
      {label:'بله، ذخیره و استفاده کن',primary:true,onClick:()=>{
        const arr=state.places.filter(p=>norm(p.name)!=='خانه');
        arr.push({id:crypto.randomUUID?.()||String(Date.now()),name:'خانه',address,aliases:['خونه','منزل من'],lat:pos.lat,lon:pos.lon});
        state.places=arr;
        continuation(candidate);
      }},
      {label:'فقط این بار استفاده کن',onClick:()=>continuation({name:'موقعیت فعلی',address,lat:pos.lat,lon:pos.lon})},
      {label:'نه، مبدأ را می‌نویسم',onClick:()=>{setPending({type:'manualOrigin',continuation});agentMsg('مبدأ را بنویس.')}}
    ]);
  }catch(e){
    agentMsg(`${e.message}
می‌توانی نام یا آدرس مبدأ را بنویسی.`);
    setPending({type:'manualOrigin',continuation});
  }
}
function parseSavePlaceCommand(raw){
  const original=String(raw||'').trim(); const n=norm(original);
  if(!/(ذخیره|اضافه|ثبت)/.test(n) || !/(عنوان|اسم|نام)/.test(n))return null;
  let m=n.match(/^(.+?)\s+(?:را|رو)?\s*(?:با عنوان|به اسم|به نام)\s+(.+?)(?:\s+(?:به قسمت\s+)?(?:آدرس|ادرس)(?:\s*ها)?\s*)?(?:اضافه|ذخیره|ثبت)\s*(?:کن|شود|بکن)?$/);
  if(!m)m=n.match(/^(.+?)\s+(?:با عنوان|به اسم|به نام)\s+(.+?)\s+(?:به قسمت\s+)?(?:آدرس|ادرس)(?:\s*ها)?\s+(?:اضافه|ذخیره|ثبت)\s*(?:کن|شود)?$/);
  if(!m)return null;
  let address=m[1].replace(/^(?:این|همین)\s+(?:آدرس|ادرس|مکان|موقعیت)\s*(?:را|رو)?\s*/,'').trim();
  const title=m[2].replace(/(?:به قسمت.*|اضافه|ذخیره|ثبت).*$/,'').trim();
  const refersLast=/^(?:این|همین)?\s*(?:آدرس|ادرس|مکان|موقعیت)?$/.test(address)||!address;
  return {address:refersLast?'':address,title};
}
function ensureConfirmMap(){
  if(state.confirmMap)return;
  state.confirmMap=L.map('confirmMap',{zoomControl:true}).setView([35.6892,51.3890],14);
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'&copy; OpenStreetMap contributors'}).addTo(state.confirmMap);
  state.confirmMap.on('click',async e=>{
    if(state.confirmMarker)state.confirmMarker.setLatLng(e.latlng);else state.confirmMarker=L.marker(e.latlng).addTo(state.confirmMap);
    try{const addr=await reverseGeo({lat:e.latlng.lat,lon:e.latlng.lng});$('#confirmAddress').textContent=addr;state.confirmDraft={...(state.confirmDraft||{}),lat:e.latlng.lat,lon:e.latlng.lng,address:addr,name:addr.split(',')[0]};}catch{}
  });
}
function closeConfirmMap(){ $('#confirmOverlay').classList.add('hidden');$('#confirmOverlay').setAttribute('aria-hidden','true'); }
function confirmMapLocation(candidate,{role='location',title='',onConfirm,onEdit}={}){
  state.lastResolvedLocation=candidate; state.confirmDraft={...candidate};
  $('#confirmOverlay').classList.remove('hidden');$('#confirmOverlay').setAttribute('aria-hidden','false');
  $('#confirmTitle').textContent=role==='origin'?'تأیید مبدأ':role==='destination'?'تأیید مقصد':'تأیید موقعیت';
  $('#confirmSubtitle').textContent=title||'نقطه پیدا شده را روی نقشه بررسی کن. اگر دقیق نیست، روی نقشه نقطه درست را لمس کن.';
  $('#confirmAddress').textContent=candidate.address||candidate.display_name||candidate.name||'';
  setTimeout(()=>{ensureConfirmMap();state.confirmMap.invalidateSize();const lat=+candidate.lat,lon=+candidate.lon;if(state.confirmMarker)state.confirmMarker.setLatLng([lat,lon]);else state.confirmMarker=L.marker([lat,lon]).addTo(state.confirmMap);state.confirmMap.setView([lat,lon],17);},80);
  $('#confirmYes').onclick=()=>{const c={...candidate,...state.confirmDraft};closeConfirmMap();onConfirm?.(c)};
  $('#confirmEdit').onclick=()=>{closeConfirmMap();onEdit?.()};
}
function rankMapResults(query,results){
  const intent=addressIntent(query);return results.map(x=>{const r=semanticRank(intent,x);return {...x,semantic:Math.min(1,r.score+cityBiasScore(x)+(x._hierarchical?0.10:0)+(x._structured?0.06:0)+(x._contextLocked?0.30:0)),missing:r.missing,warnings:r.warnings,name:x.name||x.display_name?.split(',')[0]||query,address:x.display_name||x.address||query,lat:+x.lat,lon:+x.lon}}).filter(x=>Number.isFinite(x.lat)&&Number.isFinite(x.lon)).sort((a,b)=>b.semantic-a.semantic);
}
function parseAddressSegments(raw){
  const n=norm(raw).replace(/خیابون/g,'خیابان').replace(/بنبست/g,'بن بست');
  const explicitHouse=(n.match(/پلاک\s*([۰-۹0-9A-Za-z-]+)/)||[])[1]||'';
  const trailingHouse=!explicitHouse ? ((n.match(/(?:^|\s)([۰-۹0-9]{1,6})(?:\s*)$/)||[])[1]||'') : '';
  const house=explicitHouse||trailingHouse;
  let noHouse=n.replace(/\s*پلاک\s*[۰-۹0-9A-Za-z-]+\s*/g,' ').replace(/\s+/g,' ').trim();
  if(trailingHouse) noHouse=noHouse.replace(new RegExp('(?:^|\\s)'+trailingHouse+'(?:\\s*)$'),' ').replace(/\s+/g,' ').trim();
  const marker='(?:میدان|خیابان|کوچه|بن \s*بست|بلوار|محله|منطقه|بزرگراه|اتوبان|چهارراه)';
  const re=new RegExp('(?:^|\\s)('+marker+')\\s+(.+?)(?=\\s+'+marker+'\\s+|$)','g');
  const parts=[]; let m;
  while((m=re.exec(noHouse))){
    const type=m[1].replace(/\s+/g,' ').trim(); const name=m[2].trim();
    if(name)parts.push({type,name,label:(type+' '+name).trim(),inferred:false});
  }
  const loose=meaningfulTokens(noHouse).filter(t=>!addressMarkers.includes(t)&&!['بن','بست'].includes(t));
  return {raw:n,noHouse,house,houseInferred:!!trailingHouse&&!explicitHouse,parts,loose};
}
function looksLikeFreeformAddress(raw){
  const p=parseAddressSegments(raw);
  return p.parts.length>0 || !!p.house || p.loose.length>=3;
}
function inferAddressUnderstanding(query,item){
  const p=parseAddressSegments(query);
  const corpus=norm(`${item.display_name||item.address||''} ${item.name||''}`);
  const corpusTokens=corpus.split(' ').filter(Boolean);
  if(p.parts.length){
    const confirmed=[], probable=[], unconfirmed=[];
    for(const part of p.parts){
      const key=norm(part.name); let best=0,bestToken='';
      for(const c of corpusTokens){const z=strScore(key,c);if(z>best){best=z;bestToken=c}}
      if(corpus.includes(key)||best>=.86) confirmed.push(part.label);
      else if(best>=.68) probable.push(`${part.label}${bestToken?` (نزدیک به «${bestToken}» در نقشه)`:''}`);
      else unconfirmed.push(part.label);
    }
    if(p.house){
      const exact=new RegExp(`(^|\\D)${p.house}(\\D|$)`).test(corpus);
      (exact?confirmed:unconfirmed).push(`${p.houseInferred?'عدد پایانی/پلاک احتمالی':'پلاک'} ${p.house}`);
    }
    return {chain:p.parts.map(x=>x.label),confirmed,probable,unconfirmed,parsed:p};
  }
  const tokens=p.loose.filter(t=>t.length>1);
  const confirmed=[],probable=[],unconfirmed=[];
  for(const t of tokens){
    let best=0,bestToken='';
    for(const c of corpusTokens){const z=strScore(t,c);if(z>best){best=z;bestToken=c}}
    if(corpusTokens.includes(t)||best>=.88) confirmed.push(t);
    else if(best>=.70) probable.push(bestToken&&bestToken!==t?`${t} ≈ ${bestToken}`:t);
    else unconfirmed.push(t);
  }
  if(p.house){
    const exact=new RegExp(`(^|\\D)${p.house}(\\D|$)`).test(corpus);
    (exact?confirmed:unconfirmed).push(`${p.houseInferred?'پلاک احتمالی':'پلاک'} ${p.house}`);
  }
  return {chain:tokens,confirmed,probable,unconfirmed,parsed:p};
}

function partialSearchVariants(raw){
  const parsed=parseAddressSegments(raw); const city=explicitCityInQuery(raw)||state.searchCity||'\u062a\u0647\u0631\u0627\u0646';
  const out=[]; const add=(q,level,labels=[])=>{q=String(q||'').replace(/\s+/g,' ').trim();if(q&&!out.some(x=>norm(x.q)===norm(q)))out.push({q,level,labels})};
  add(parsed.noHouse,100,parsed.parts.map(x=>x.label));
  const parts=parsed.parts;
  for(let len=Math.min(4,parts.length);len>=2;len--){
    for(let i=Math.max(0,parts.length-len);i>=0;i--){
      const slice=parts.slice(i,i+len); if(slice.length!==len)continue;
      add(slice.map(x=>x.label).join(' '),70+len*5+(i+len===parts.length?4:0),slice.map(x=>x.label));
    }
  }
  for(let i=parts.length-1;i>=0;i--)add(parts[i].label,45+i*3,[parts[i].label]);
  const toks=meaningfulTokens(parsed.noHouse);
  for(let len=Math.min(3,toks.length);len>=1;len--){
    for(let i=toks.length-len;i>=0;i--)add(toks.slice(i,i+len).join(' '),25+len*4,[]);
  }
  return {parsed,city,variants:out.slice(0,18)};
}
function addressPartStatus(query,item){
  const u=inferAddressUnderstanding(query,item);
  return {confirmed:u.confirmed,probable:u.probable,unconfirmed:u.unconfirmed,parsed:u.parsed,chain:u.chain};
}

async function progressivePartialAddressSearch(query){
  const plan=partialSearchVariants(query); const collected=[];
  for(const v of plan.variants){
    let rows=[];
    try{rows=await photonSearch(`${v.q}\u060c ${plan.city}`,10);}catch{}
    for(const row of rows){row._partialResolution=true;row._partialVariant=v.q;row._partialLevel=v.level;row._partialLabels=v.labels;collected.push(row);}
    if(collected.length>=28)break;
  }
  let uniq=uniqueByPlaceId(collected);
  if(!uniq.length){
    for(const v of plan.variants.slice(0,4)){
      try{const rows=await nominatimSearch(`${v.q}\u060c ${plan.city}`,8,{bounded:false});for(const row of rows){row._partialResolution=true;row._partialVariant=v.q;row._partialLevel=v.level;row._partialLabels=v.labels;collected.push(row);}}catch{}
      if(collected.length>=12)break;
    }
    uniq=uniqueByPlaceId(collected);
  }
  return uniq.map(x=>{
    const st=addressPartStatus(query,x); const sem=semanticRank(addressIntent(query),x).score;
    const denom=Math.max(1,st.confirmed.length+st.unconfirmed.length); const coverage=st.confirmed.length/denom;
    const score=sem*.45+coverage*.38+cityBiasScore(x)+Math.min(.12,(x._partialLevel||0)/900);
    return {...x,_partialStatus:st,_partialScore:score};
  }).filter(x=>x._partialStatus.confirmed.length>0 || x._partialScore>=.38).sort((a,b)=>b._partialScore-a._partialScore).slice(0,20);
}
async function resolveFreeformMapLocation(query,role,onChosen,onRetry){
  const flowToken=state.flowVersion;
  state.activeMapResolution={query,role,onChosen,onRetry};
  agentMsg(`\u062f\u0627\u0631\u0645 \u00ab${query}\u00bb \u0631\u0627 \u0631\u0648\u06cc \u0646\u0642\u0634\u0647 \u0628\u0631\u0631\u0633\u06cc \u0645\u06cc\u200c\u06a9\u0646\u0645\u2026`);
  try{
    let ranked=rankMapResults(query,await publicSearch(query));
    if(flowToken!==state.flowVersion)return;
    const structured=looksLikeFreeformAddress(query);
    const topDirect=ranked[0];
    if(structured && (!topDirect || topDirect.semantic<.46)){
      const partial=await progressivePartialAddressSearch(query);
      if(flowToken!==state.flowVersion)return;
      if(partial.length){
        const converted=partial.map(x=>{const r=semanticRank(addressIntent(query),x);const st=x._partialStatus||addressPartStatus(query,x);return {...x,semantic:Math.max(r.score,x._partialScore||0),missing:st.unconfirmed,warnings:r.warnings,name:x.name||x.display_name?.split(',')[0]||query,address:x.display_name||x.address||query,lat:+x.lat,lon:+x.lon,_partialStatus:st};});
        ranked=uniqueByPlaceId([...converted,...ranked]).sort((a,b)=>(b.semantic||0)-(a.semantic||0));
      }
    }
    if(!ranked.length){
      agentMsg('\u0627\u0632 \u0645\u062a\u0646 \u0622\u062f\u0631\u0633 \u0647\u06cc\u0686 \u0646\u0642\u0637\u0647 \u0642\u0627\u0628\u0644\u200c\u0627\u062a\u06a9\u0627\u06cc\u06cc \u067e\u06cc\u062f\u0627 \u0646\u0634\u062f. \u0628\u0631\u0627\u06cc \u0627\u06cc\u0646\u06a9\u0647 \u0622\u062f\u0631\u0633 \u0627\u0634\u062a\u0628\u0627\u0647 \u062b\u0628\u062a \u0646\u0634\u0648\u062f\u060c \u0622\u062f\u0631\u0633 \u0631\u0627 \u06a9\u0645\u06cc \u0627\u0635\u0644\u0627\u062d \u06a9\u0646 \u06cc\u0627 \u0627\u0632 \u0646\u0642\u0634\u0647 \u0646\u0642\u0637\u0647 \u0631\u0627 \u0645\u0634\u062e\u0635 \u06a9\u0646.');onRetry?.();return;
    }
    const top=ranked[0], second=ranked[1];
    const choose=c=>{
      const st=c._partialStatus||addressPartStatus(query,c);
      let title=`\u0628\u0631\u062f\u0627\u0634\u062a \u0645\u0646 \u0627\u0632 \u00ab${query}\u00bb \u0627\u06cc\u0646 \u0646\u0642\u0637\u0647 \u0627\u0633\u062a.`;
      if(st.confirmed.length)title+=` \u0628\u062e\u0634\u200c\u0647\u0627\u06cc \u067e\u06cc\u062f\u0627\u0634\u062f\u0647: ${st.confirmed.join('\u060c ')}.`;
      if(st.unconfirmed.length)title+=` \u0627\u06cc\u0646 \u0628\u062e\u0634\u200c\u0647\u0627 \u062f\u0631 \u062f\u0627\u062f\u0647 \u0646\u0642\u0634\u0647 \u062a\u0623\u06cc\u06cc\u062f \u0646\u0634\u062f: ${st.unconfirmed.join('\u060c ')}.`;
      title+=' \u0642\u0628\u0644 \u0627\u0632 \u0627\u062f\u0627\u0645\u0647 \u0646\u0642\u0637\u0647 \u0631\u0627 \u0631\u0648\u06cc \u0646\u0642\u0634\u0647 \u062a\u0623\u06cc\u06cc\u062f \u06a9\u0646.';
      confirmMapLocation(c,{role,title,onConfirm:x=>{state.activeMapResolution=null;onChosen?.(x)},onEdit:()=>{state.activeMapResolution=null;onRetry?.()}});
    };
    if(second && second.semantic>.52 && Math.abs((top.semantic||0)-(second.semantic||0))<.075){
      const opts=ranked.slice(0,4).map((x,i)=>({label:`${i+1}) ${x.name} \u2014 ${x.address}`,primary:i===0,onClick:()=>choose(x)}));
      agentMsg('\u0686\u0646\u062f \u0628\u0631\u062f\u0627\u0634\u062a \u0645\u0639\u062a\u0628\u0631 \u0627\u0632 \u0622\u062f\u0631\u0633 \u067e\u06cc\u062f\u0627 \u06a9\u0631\u062f\u0645. \u0646\u062a\u06cc\u062c\u0647 \u062f\u0631\u0633\u062a \u0631\u0627 \u0627\u0646\u062a\u062e\u0627\u0628 \u06a9\u0646\u061b \u0628\u0639\u062f \u0631\u0648\u06cc \u0646\u0642\u0634\u0647 \u0647\u0645 \u062a\u0623\u06cc\u06cc\u062f\u0634 \u0645\u06cc\u200c\u06a9\u0646\u06cc.',opts);return;
    }
    const st=top._partialStatus||addressPartStatus(query,top);
    if(st.unconfirmed.length){
      agentMsg(`\u0628\u062e\u0634\u06cc \u0627\u0632 \u0622\u062f\u0631\u0633 \u0631\u0627 \u067e\u06cc\u062f\u0627 \u06a9\u0631\u062f\u0645.\n${st.confirmed.length?`\u067e\u06cc\u062f\u0627\u0634\u062f\u0647: ${st.confirmed.join('\u060c ')}\n`:''}\u062f\u0631 \u062f\u0627\u062f\u0647 \u0646\u0642\u0634\u0647 \u062a\u0623\u06cc\u06cc\u062f \u0646\u0634\u062f: ${st.unconfirmed.join('\u060c ')}\n\u0627\u0644\u0627\u0646 \u0646\u0642\u0637\u0647\u200c\u0627\u06cc \u06a9\u0647 \u067e\u06cc\u062f\u0627 \u06a9\u0631\u062f\u0645 \u0631\u0627 \u0631\u0648\u06cc \u0646\u0642\u0634\u0647 \u0646\u0634\u0627\u0646 \u0645\u06cc\u200c\u062f\u0647\u0645.`);
    }
    choose(top);
  }catch(e){if(flowToken!==state.flowVersion)return;agentMsg('\u062c\u0633\u062a\u200c\u0648\u062c\u0648\u06cc \u0646\u0642\u0634\u0647 \u0645\u0648\u0642\u062a\u0627\u064b \u067e\u0627\u0633\u062e \u0646\u062f\u0627\u062f. \u062f\u0648\u0628\u0627\u0631\u0647 \u0627\u0645\u062a\u062d\u0627\u0646 \u06a9\u0646 \u06cc\u0627 \u0622\u062f\u0631\u0633 \u0631\u0627 \u0628\u0627 \u0645\u062d\u0644\u0647/\u062e\u06cc\u0627\u0628\u0627\u0646/\u06a9\u0648\u0686\u0647 \u0628\u0646\u0648\u06cc\u0633.');onRetry?.();}
}

function persistPlaceCandidate(title,candidate){
  const saveNow=()=>{const arr=state.places.filter(p=>norm(p.name)!==norm(title));arr.push({id:crypto.randomUUID?.()||String(Date.now()),name:title,address:candidate.address||candidate.display_name||'',aliases:[],lat:+candidate.lat,lon:+candidate.lon});state.places=arr;state.lastResolvedLocation=candidate;agentMsg(`موقعیت «${title}» ذخیره شد.`)};
  const existing=state.places.find(p=>norm(p.name)===norm(title));
  if(existing){agentMsg(`عنوان «${title}» از قبل وجود دارد. آدرس قبلی:\n${existing.address||''}\n\nبا موقعیت جدید جایگزینش کنم؟`,[{label:'بله، جایگزین کن',primary:true,onClick:saveNow},{label:'نه، عنوان دیگری می‌گویم',onClick:()=>{setPending({type:'saveTitleForCandidate',candidate});agentMsg('عنوان جدید را بنویس.')}}]);}else saveNow();
}
async function savePlaceFromChat(cmd){
  const title=cmd.title.trim(); if(!title){agentMsg('چه عنوانی برای این آدرس بگذارم؟');return;}
  const finalize=candidate=>persistPlaceCandidate(title,candidate);
  if(!cmd.address && state.lastResolvedLocation){return confirmMapLocation(state.lastResolvedLocation,{role:'save',title:`این موقعیت با عنوان «${title}» ذخیره شود؟`,onConfirm:finalize,onEdit:()=>{setPending({type:'saveAddressForTitle',title});agentMsg('آدرس درست را بنویس.')}});}
  if(!cmd.address){setPending({type:'saveAddressForTitle',title});agentMsg(`باشه. آدرس «${title}» رو بفرست.`);return;}
  return resolveFreeformMapLocation(cmd.address,'save',finalize,()=>{setPending({type:'saveAddressForTitle',title});agentMsg('آدرس را اصلاح کن و دوباره بفرست.')});
}

async function resolveOrigin(text,continuation){
  if(text){
    if(looksLikePersonalPlacePhrase(text)){
      const exactRaw=state.places.filter(p=>personalLabels(p).includes(norm(text)));
      if(exactRaw.length===1)return continuation(exactRaw[0]);
      return askPersonalOrMap(text,{role:'origin',continuation});
    }
    const sr=savedResolution(text);
    if(sr.kind==='exact')return continuation(sr.candidate);
    if(sr.kind==='probable'){agentMsg(`منظورت مبدأ ذخیره‌شده «${sr.candidate.name}» است؟`,[{label:'بله',primary:true,onClick:()=>continuation(sr.candidate)},{label:'نه، آدرس را روی نقشه پیدا کن',onClick:()=>resolveFreeformMapLocation(text,'origin',continuation,()=>{setPending({type:'manualOrigin',continuation});agentMsg('مبدأ را اصلاح کن و دوباره بنویس.')})}]);return;}
    if(sr.kind==='ambiguous'){agentMsg('چند مبدأ ذخیره‌شده با چیزی که گفتی جور درمی‌آید. کدام را منظورت بود؟',sr.candidates.map((x,i)=>({label:`${i+1}) ${x.name}`,primary:i===0,onClick:()=>continuation(x)})).concat([{label:'هیچ‌کدام؛ روی نقشه جست‌وجو کن',onClick:()=>resolveFreeformMapLocation(text,'origin',continuation,()=>{setPending({type:'manualOrigin',continuation})})},{label:'📍 موقعیت فعلی',onClick:()=>useCurrentOrigin(continuation)}]));return;}
    return resolveFreeformMapLocation(text,'origin',continuation,()=>{setPending({type:'manualOrigin',continuation});agentMsg('مبدأ را اصلاح کن و دوباره بنویس.')});
  }
  agentMsg('باشه، الان کجایی؟',[
    {label:'📍 موقعیت فعلی من',primary:true,onClick:()=>useCurrentOrigin(continuation)},
    {label:'خانه هستم',onClick:()=>chooseHomeOrigin(continuation)},
    {label:'جای دیگه‌ام',onClick:()=>{setPending({type:'manualOrigin',continuation});agentMsg('آدرس یا نام مکان مبدأ را همین‌جا بنویس؛ پیداش می‌کنم و قبل از استفاده روی نقشه ازت تأیید می‌گیرم.')}}
  ]);
}

async function useCurrentOrigin(continuation){ agentMsg('با اجازه‌ات موقعیت فعلی را از GPS می‌گیرم…'); try{const pos=await getGeo(true);const address=await reverseGeo(pos);const origin={name:`موقعیت فعلی`,address,lat:pos.lat,lon:pos.lon};confirmMapLocation(origin,{role:'origin',title:`GPS این موقعیت را با دقت تقریبی ${Math.max(1,Math.round(pos.accuracy))} متر پیدا کرده. روی نقشه بررسی کن.`,onConfirm:continuation,onEdit:()=>{setPending({type:'manualOrigin',continuation});agentMsg('باشه. آدرس مبدأ را بنویس.')}});}catch(e){agentMsg(`${e.message}\nمی‌توانی آدرس مبدأ را همین‌جا بنویسی.`);setPending({type:'manualOrigin',continuation});}}


function destinationLooksLikeMapAddress(text){
  const n=norm(text);
  if(!n)return false;
  if(explicitCityInQuery(n))return true;
  if(/\b\d{1,6}\b/.test(n))return true;
  if(addressMarkers.some(m=>n.includes(m)))return true;
  if(/(?:چهارراه|پل|میدان|بلوار|اتوبان|بزرگراه|کوچه|خیابان|خیابون|بن بست|بنبست|پلاک|منطقه|محله)/.test(n))return true;
  return false;
}
function askForUnsavedDestination(query,origin){
  const label=String(query||'').trim();
  setPending({type:'destinationAddressForUnsaved',origin,label});
  agentMsg(`موقعیتی با عنوان «${label}» در موقعیت‌های ذخیره‌شده پیدا نکردم.\nاگر منظورت یک مکان مشخص است، اسم دقیق‌تر یا آدرسش را بده تا روی نقشه پیداش کنم.`,[
    {label:'آدرس یا اسم دقیق را می‌فرستم',primary:true,onClick:()=>{setPending({type:'destinationAddressForUnsaved',origin,label});agentMsg('باشه، اسم دقیق مکان یا آدرسش را بنویس.')}},
    {label:'همین عبارت را روی نقشه جست‌وجو کن',onClick:()=>{setPending(null);searchDestination(label,origin,true)}}
  ]);
}
async function resolveDestination(query,origin){
  // Relational phrases such as «خانه علی» are ambiguous by nature: they may mean a saved title or a map place.
  // Never silently strip «خانه/منزل/خونه» and jump to a saved token such as «علی».
  if(looksLikePersonalPlacePhrase(query)){
    const exactRaw=state.places.filter(p=>personalLabels(p).includes(norm(query)));
    if(exactRaw.length===1)return confirmTrip(origin,exactRaw[0],query);
    return askPersonalOrMap(query,{role:'destination',origin});
  }
  // Personal memory has precedence for ordinary names.
  const sr=savedResolution(query);
  if(sr.kind==='exact') return confirmTrip(origin,sr.candidate,query);
  if(sr.kind==='probable'){
    const top=sr.candidate;
    agentMsg(sr.reason==='personal-name' ? `منظورت موقعیت ذخیره‌شده «${top.name}» است؟` : `به احتمال زیاد منظورت مکان ذخیره‌شده «${top.name}» است.\nشما «${query}» گفتید/نوشتید. همین را منظورت بوده؟`,[
      {label:'بله، همین است',primary:true,onClick:()=>confirmTrip(origin,top,query)},
      {label:'نه، در نقشه جست‌وجو کن',onClick:()=>searchDestination(query,origin)}
    ]); return;
  }
  if(sr.kind==='ambiguous'){
    agentMsg('چند مکان ذخیره‌شده شبیه چیزی که گفتی پیدا کردم. یکی را انتخاب کن:',sr.candidates.map((x,i)=>({label:`${i+1}) ${x.name}`,primary:i===0,onClick:()=>confirmTrip(origin,x,query)})).concat([{label:'هیچ‌کدام؛ اسم دقیق یا آدرس می‌دهم',onClick:()=>askForUnsavedDestination(query,origin)}])); return;
  }
  // Short/category-like destinations are treated as PERSONAL labels first.
  // Example: «میخوام برم آرایشگاه» must not silently search every salon on the public map.
  if(!destinationLooksLikeMapAddress(query)) return askForUnsavedDestination(query,origin);
  return searchDestination(query,origin,true);
}
async function searchDestination(query,origin,forcedMap=false){
  return resolveFreeformMapLocation(query,'destination',candidate=>{
    if(candidate.warnings?.length){
      agentMsg(`⚠️ یک اختلاف نوشتاری دیدم:\n${candidate.warnings.join('\n')}\nممکن است غلط املایی باشد یا مکان متفاوت. چون نقطه را روی نقشه تأیید کردی، از همین نتیجه استفاده می‌کنم.`);
    }
    confirmTrip(origin,candidate,query);
  },()=>{setPending({type:'destinationText',origin});agentMsg('مقصد را اصلاح کن و دوباره بنویس.')});
}
function checkDestinationWarning(origin,candidate,query){ return confirmMapLocation(candidate,{role:'destination',title:`نتیجه «${query}» را روی نقشه بررسی کن.`,onConfirm:c=>confirmTrip(origin,c,query),onEdit:()=>{setPending({type:'destinationText',origin});agentMsg('مقصد درست را بنویس.')}}); }

function confirmTrip(origin,dest){ agentMsg(`برداشت نهایی من:\nمبدأ: ${origin.name}${origin.address?` — ${origin.address}`:''}\nمقصد: ${dest.name}${dest.address?` — ${dest.address}`:''}\n\nدرخواست ماشین ثبت شود؟`,[{label:'تأیید درخواست',primary:true,onClick:()=>simulateRide(origin,dest)},{label:'ویرایش مقصد',onClick:()=>{setPending({type:'destinationText',origin});agentMsg('مقصد جدید را بنویس.')}}]); }
function simulateRide(origin,dest){ agentMsg(`✅ مسیر تأیید شد.\nاز «${origin.name}» به «${dest.name}».\n\nاین نسخه وب فعلاً به API واقعی Snapp وصل نیست، بنابراین در این مرحله سفر واقعی ثبت نمی‌شود. اتصال Partner/API سرویس حمل‌ونقل مرحله بعدی است.`); setPending(null); }
async function paymentFlow(){
  const s=state.senior;
  if(!s.enabled){
    agentMsg('پرداخت خودکار خاموش است. اگر روشنش کنی، فرمان «هزینه را پرداخت کن» از اتصال کیف پول Snapp استفاده می‌کند.');
    return;
  }
  agentMsg('دارم پرداخت را از کیف پول Snapp بررسی می‌کنم…');
  try{
    const r=await fetch('/api/snapp-wallet/pay',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action:'pay-current-trip'})});
    let data={}; try{data=await r.json()}catch{}
    if(!r.ok){
      if(r.status===503||r.status===501||r.status===404){
        agentMsg('پرداخت خودکار روشن است، اما اتصال رسمی کیف پول Snapp هنوز پیکربندی نشده. برای پرداخت واقعی باید Partner/API و دسترسی معتبر Snapp به Backend متصل شود؛ هیچ پرداختی ثبت نشد.');
        return;
      }
      throw new Error(data.message||`خطای پرداخت (${r.status})`);
    }
    if(data.status==='paid'||data.success===true){
      agentMsg(`✅ هزینه از کیف پول Snapp پرداخت شد${data.amount?` — ${data.amount}`:''}.`);
    }else{
      agentMsg(data.message||'پاسخ کیف پول دریافت شد، اما پرداخت نهایی تأیید نشد.');
    }
  }catch(e){
    agentMsg(`اتصال کیف پول Snapp در دسترس نیست؛ هیچ پرداختی ثبت نشد. ${e.message||''}`.trim());
  }
}

async function handleText(raw){ const text=raw.trim(); if(!text)return; userMsg(text); const n=norm(text); let p=state.pending;
  // Ride cancellation is a distinct action from cancelling the current conversational flow.
  if(isRideCancelIntent(text)){ bumpFlow(); setPending(null); try{closeConfirmMap();}catch{}; await cancelRideFlow(); return; }
  // Global interrupt layer: cancel or switch tasks BEFORE interpreting text as data for an old state.
  if(isCancelIntent(text)){ cancelActiveFlow(); return; }
  if(isCorrectionIntent(text)){
    if(state.activeMapResolution?.onRetry){
      const retry=state.activeMapResolution.onRetry; bumpFlow(); state.activeMapResolution=null; try{closeConfirmMap();}catch{}; retry?.(); return;
    }
    if(p?.type==='destinationText'||p?.type==='destinationAfterOrigin'){agentMsg('باشه. مقصد درست را بنویس.');return;}
    if(p?.type==='manualOrigin'){agentMsg('باشه. مبدأ درست را بنویس.');return;}
    agentMsg('باشه. بگو کدام بخش را می‌خواهی اصلاح کنی؛ مبدأ، مقصد یا آدرس ذخیره‌شده؟');return;
  }
  if(p && isExplicitNewTask(text)){
    bumpFlow(); setPending(null); try{closeConfirmMap();}catch{} p=null;
    agentMsg('باشه، کار قبلی رو متوقف کردم و درخواست جدیدت رو انجام می‌دم.');
  }
  if(!p && isGreetingOnly(text)){agentMsg('سلام عزیزم 🌷 چه کاری برات انجام بدم؟');return;}
  if(!p){
    const saveCmd=parseSavePlaceCommand(text);if(saveCmd){bumpFlow();return savePlaceFromChat(saveCmd);}
    if(isAddPlaceIntent(text)){
      bumpFlow();
      if(state.lastResolvedLocation && /(?:این|همین)/.test(n)){setPending({type:'saveTitleForCandidate',candidate:state.lastResolvedLocation});agentMsg('حتماً. دوست داری این موقعیت با چه عنوانی ذخیره بشه؟');return;}
      const payload=extractAddPlacePayload(text)||{city:null,address:''};
      if(payload.city){
        state.searchCity=payload.city; localStorage.setItem('soroush_web_search_city',payload.city);
        if(payload.address && norm(payload.address)!==norm(payload.city)){
          setPending({type:'saveWizardTitleAfterAddress',city:payload.city,address:payload.address});
          agentMsg(`باشه. شهر را «${payload.city}» در نظر گرفتم و آدرس را هم نگه داشتم. این آدرس با چه عنوانی ذخیره شود؟`); return;
        }
        setPending({type:'saveWizardTitle',city:payload.city}); agentMsg(`باشه. شهر «${payload.city}». عنوان این آدرس چی باشه؟`); return;
      }
      setPending({type:'saveWizardCity',capturedAddress:payload.address||''});
      agentMsg('حتماً. اول بگو این آدرس در کدام شهر است؟'); return;
    }
  }
  if(p?.type==='saveWizardCity'){
    const city=text.trim();
    if(!city){setPending(p);agentMsg('نام شهر را بنویس؛ مثلاً «تهران» یا «شیراز».');return;}
    state.searchCity=city; localStorage.setItem('soroush_web_search_city',city);
    if(p.capturedAddress){
      setPending({type:'saveWizardTitleAfterAddress',city,address:p.capturedAddress});
      agentMsg(`باشه. شهر «${city}». آدرسی که گفتی را نگه داشتم. این آدرس با چه عنوانی ذخیره شود؟`); return;
    }
    setPending({type:'saveWizardTitle',city}); agentMsg(`باشه. عنوان این آدرس چی باشه؟`); return;
  }
  if(p?.type==='saveWizardTitleAfterAddress'){
    const title=text.trim();
    if(!title){setPending(p);agentMsg('برای این آدرس یک عنوان کوتاه بگو؛ مثلاً «خانه علی» یا «محل کار».');return;}
    const scoped=cityScopedAddress(p.address,p.city);
    setPending(null); agentMsg(`دارم آدرس را در شهر «${p.city}» بررسی می‌کنم…`);
    return savePlaceFromChat({title,address:scoped});
  }
  if(p?.type==='saveWizardTitle'){
    const title=text.trim();
    if(!title){setPending({type:'saveWizardTitle'});agentMsg('یک عنوان کوتاه بگو؛ مثلاً «خانه خاله» یا «محل کار».');return;}
    const exact=state.places.find(x=>norm(x.name)===norm(title));
    const near=state.places.map(x=>({place:x,score:savedNameScore(title,x.name)})).filter(x=>x.score>=.84).sort((a,b)=>b.score-a.score);
    if(exact){
      setPending({type:'saveWizardAddress',title,city:p.city});
      agentMsg(`عنوان «${title}» از قبل وجود دارد. آدرس جدید را در شهر «${p.city||state.searchCity}» بفرست؛ قبل از جایگزینی، آدرس قبلی و جدید را بهت نشان می‌دهم.`);
      return;
    }
    if(near.length){
      const top=near[0].place;
      setPending({type:'saveWizardTitleChoice',typedTitle:title,existing:top,city:p.city});
      agentMsg(`یک عنوان خیلی شبیه پیدا کردم: «${top.name}». منظورت همین عنوان قبلی است یا «${title}» یک موقعیت جدید است؟`,[
        {label:`همان «${top.name}»`,primary:true,onClick:()=>{setPending({type:'saveWizardAddress',title:top.name,city:p.city});agentMsg(`باشه. آدرس «${top.name}» رو در شهر «${p.city||state.searchCity}» بفرست.`)}},
        {label:`«${title}» یک موقعیت جدید است`,onClick:()=>{setPending({type:'saveWizardAddress',title,city:p.city});agentMsg(`باشه. آدرس «${title}» رو در شهر «${p.city||state.searchCity}» بفرست.`)}}
      ]);
      return;
    }
    setPending({type:'saveWizardAddress',title,city:p.city});
    agentMsg(`باشه. حالا آدرس «${title}» رو در شهر «${p.city||state.searchCity}» بفرست.`);
    return;
  }
  if(p?.type==='saveWizardTitleChoice'){
    // Button actions normally resolve this state. Free text is treated as a fresh title so older clients are not trapped.
    const title=text.trim();
    setPending({type:'saveWizardAddress',title,city:p.city});
    agentMsg(`باشه. آدرس «${title}» رو در شهر «${p.city||state.searchCity}» بفرست.`);
    return;
  }
  if(p?.type==='saveWizardAddress'){
    const title=p.title; const city=p.city||state.searchCity; setPending(null);
    const scoped=cityScopedAddress(text,city);
    agentMsg(`دارم نزدیک‌ترین موقعیت به آدرسی که گفتی در شهر «${city}» پیدا می‌کنم…`);
    return savePlaceFromChat({title,address:scoped});
  }
  if(p?.type==='manualOrigin'){
    setPending(null); return resolveOrigin(text,p.continuation);
  }
  if(p?.type==='saveAddressForTitle'){setPending(null);return savePlaceFromChat({title:p.title,address:text});}
  if(p?.type==='saveTitleForCandidate'){setPending(null);const title=text.trim();return confirmMapLocation(p.candidate,{role:'save',title:`این موقعیت با عنوان «${title}» ذخیره شود؟`,onConfirm:c=>persistPlaceCandidate(title,c),onEdit:()=>{setPending({type:'saveTitleForCandidate',candidate:p.candidate});agentMsg('عنوان دیگری بنویس.')}});}
  if(p?.type==='destinationAddressForUnsaved'){ const origin=p.origin; setPending(null); return searchDestination(text,origin,true);}
  if(p?.type==='destinationText'){setPending(null);return resolveDestination(text,p.origin);}
  if(p?.type==='partialDestination'){ if(/اشکال نداره|اشکالی نداره|همین|اوکی|باشه|تایید|تأیید/.test(n)){setPending(null);return confirmTrip(p.origin,p.candidate,p.query)} const m=n.match(/(?:به مقصد|تا|به)\s+(.+?)(?:\s+(?:درخواست )?ثبت کن|$)/); if(m){setPending(null);return resolveDestination(cleanPlace(m[1]),p.origin);} }
  if(/پرداخت|هزینه.*(بده|پرداخت|حساب)/.test(n)){paymentFlow();return;}
  if(hasRideIntent(text)){
    bumpFlow();
    const r=parseRide(text);
    if(!r?.destination){
      // For a bare ride request, establish origin first. This matches natural assisted flow:
      // "ماشین میخوام" -> "الان کجایی؟" -> origin -> "کجا میخوای بری؟"
      return resolveOrigin(r?.origin||null,(origin)=>{
        setPending({type:'destinationAfterOrigin',origin});
        agentMsg('خوبه، کجا می‌خوای بری؟');
      });
    }
    return resolveOrigin(r.origin,(origin)=>resolveDestination(r.destination,origin));
  }
  if(p?.type==='destinationAfterOrigin'){
    const origin=p.origin;
    setPending(null);
    return resolveDestination(text,origin);
  }
  agentMsg('اگر ماشین می‌خوای، طبیعی بگو؛ مثلاً «سلام عزیزم ماشین بگیر» یا «اسنپ میخوام به آرایشگاه نیلوفر».');
}

function renderPlaces(){ const list=$('#placeList');const ps=state.places;$('#placeCount').textContent=String(ps.length).replace(/0/g,'۰').replace(/1/g,'۱').replace(/2/g,'۲').replace(/3/g,'۳').replace(/4/g,'۴').replace(/5/g,'۵').replace(/6/g,'۶').replace(/7/g,'۷').replace(/8/g,'۸').replace(/9/g,'۹'); if(!ps.length){list.innerHTML='<div class="emptyState">هنوز موقعیتی ذخیره نشده. «خانه» و مکان‌های پرتکرارت را از روی نقشه اضافه کن.</div>';return;} list.innerHTML='';ps.forEach((p,i)=>{const d=document.createElement('div');d.className='placeCard';d.innerHTML=`<strong>${escapeHtml(p.name)}</strong><small>${escapeHtml(p.address||'')}</small><div class="placeActions"><button data-i="${i}" class="deletePlace">حذف</button></div>`;list.appendChild(d)});document.querySelectorAll('.deletePlace').forEach(b=>b.onclick=()=>{const a=state.places;a.splice(+b.dataset.i,1);state.places=a}); }
function renderSenior(){const s=state.senior;$('#seniorToggle').checked=!!s.enabled}
function escapeHtml(s=''){return String(s).replace(/[&<>'"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[c]))}

function initMap(){ if(state.map)return;state.map=L.map('map').setView([35.6892,51.3890],11);L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'&copy; OpenStreetMap contributors'}).addTo(state.map);state.map.on('click',async e=>{selectMapPoint(e.latlng.lat,e.latlng.lng);try{$('#placeAddress').value=await reverseGeo({lat:e.latlng.lat,lon:e.latlng.lng})}catch{$('#placeAddress').value=`${e.latlng.lat.toFixed(6)}, ${e.latlng.lng.toFixed(6)}`}});state.map.on('moveend',updateSearchCityFromMap);const el=$('#searchContext');if(el)el.textContent=`تمرکز جست‌وجو: ${state.searchCity}`;updateSearchCityFromMap();}
function selectMapPoint(lat,lon){state.selectedPoint={lat:+lat,lon:+lon};if(state.marker)state.marker.setLatLng([lat,lon]);else state.marker=L.marker([lat,lon]).addTo(state.map);state.map.setView([lat,lon],16)}
function openPlaceModal(){$('#placeOverlay').classList.remove('hidden');$('#placeOverlay').setAttribute('aria-hidden','false');setTimeout(()=>{initMap();state.map.invalidateSize()},100)}
function closePlaceModal(){$('#placeOverlay').classList.add('hidden');$('#placeOverlay').setAttribute('aria-hidden','true')}
async function mapSearch(){const q=$('#mapSearchInput').value.trim();if(!q)return;$('#mapSearchResults').innerHTML='<div class="mapResult">در حال فهم آدرس و جست‌وجو…</div>';try{const rows=await publicSearch(q);const intent=addressIntent(q);const r=rows.map(x=>{const m=semanticRank(intent,x);return {...x,_semantic:Math.min(1,m.score+cityBiasScore(x)+(x._hierarchical?0.10:0)),_inCity:cityInResult(x)}}).sort((a,b)=>(b._inCity-a._inCity)||(b._semantic-a._semantic));$('#mapSearchResults').innerHTML='';r.slice(0,10).forEach(x=>{const d=document.createElement('div');d.className='mapResult';const hint=x._hierarchical?`\nفهم مرحله‌ای: «${x._childTerm}» داخل محدوده «${x._anchorTerm}»`: (x._queryVariant&&norm(x._queryVariant)!==norm(q)?'\nبرداشت جست‌وجو: '+x._queryVariant:'');d.textContent=x.display_name+hint;d.onclick=()=>{selectMapPoint(+x.lat,+x.lon);$('#placeAddress').value=x.display_name;$('#mapSearchResults').innerHTML=''};$('#mapSearchResults').appendChild(d)});if(!r.length)$('#mapSearchResults').innerHTML='<div class="mapResult">نتیجه مطمئنی پیدا نشد. می‌توانی نام محله، خیابان یا کوچه را جداگانه‌تر بنویسی.</div>'}catch{$('#mapSearchResults').innerHTML='<div class="mapResult">جست‌وجو در دسترس نیست.</div>'}}
async function mapMyLocation(){try{const p=await getGeo(true);selectMapPoint(p.lat,p.lon);$('#placeAddress').value=await reverseGeo(p)}catch(e){agentMsg(e.message)}}
function savePlace(){const name=$('#placeName').value.trim(),address=$('#placeAddress').value.trim();if(!name){alert('برای موقعیت یک نام وارد کن.');return}if(!state.selectedPoint){alert('نقطه را روی نقشه انتخاب کن یا موقعیت فعلی را بگیر.');return}const aliases=$('#placeAliases').value.split(',').map(x=>x.trim()).filter(Boolean);const arr=state.places.filter(p=>norm(p.name)!==norm(name));arr.push({id:crypto.randomUUID?.()||String(Date.now()),name,address,aliases,lat:state.selectedPoint.lat,lon:state.selectedPoint.lon});state.places=arr;$('#placeName').value='';$('#placeAliases').value='';$('#placeAddress').value='';$('#mapSearchInput').value='';state.selectedPoint=null;if(state.marker){state.map.removeLayer(state.marker);state.marker=null}closePlaceModal();agentMsg(`موقعیت «${name}» ذخیره شد.`)}

$('#composer').addEventListener('submit',e=>{e.preventDefault();const input=$('#messageInput');const t=input.value;input.value='';handleText(t)});
$('#messageInput').addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();$('#composer').requestSubmit()}});
document.querySelectorAll('[data-text]').forEach(b=>b.onclick=()=>handleText(b.dataset.text));
$('#newPlaceBtn').onclick=openPlaceModal;$('#closePlace').onclick=closePlaceModal;$('#mapSearchBtn').onclick=mapSearch;$('#mapMyLocationBtn').onclick=mapMyLocation;$('#savePlaceBtn').onclick=savePlace;$('#placeOverlay').onclick=e=>{if(e.target===$('#placeOverlay'))closePlaceModal()};
$('#closeConfirm').onclick=closeConfirmMap;$('#confirmOverlay').onclick=e=>{if(e.target===$('#confirmOverlay'))closeConfirmMap()};
$('#seniorToggle').onchange=e=>state.senior={...state.senior,enabled:e.target.checked};$('#menuBtn').onclick=()=>$('#sidePanel').classList.toggle('open');
window.addEventListener('beforeinstallprompt',e=>{e.preventDefault();state.deferredInstall=e;$('#installBtn').hidden=false});$('#installBtn').onclick=async()=>{if(state.deferredInstall){state.deferredInstall.prompt();await state.deferredInstall.userChoice;state.deferredInstall=null;$('#installBtn').hidden=true}};

renderPlaces();renderSenior();agentMsg('سلام! من Soroush Agent هستم. هر کاری داری طبیعی بگو؛ اگر برای سفر اطلاعاتی کم باشد، مرحله‌به‌مرحله ازت می‌پرسم.');
