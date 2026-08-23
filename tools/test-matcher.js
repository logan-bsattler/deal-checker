const fs=require('fs');
const norm=s=>{let out=s.normalize('NFD').replace(/[̀-ͯ]/g,'').toLowerCase().replace(/[^a-z0-9]/g,'');
  const lower=s.trim().toLowerCase();
  if(lower.startsWith('the ')){const alt=norm(s.trim().substring(4)); if(alt.length>=3) out=alt;}
  return out;};
const CHROME=new Set('checkout privacy terms contact wishlist cart account login logout register signin signup search filter filters sort home menu help support reviews review shipping returns refund sale clearance categories category collections collection brands brand blog news events about careers sitemap newsletter giftcards giftcard bestsellers bestseller featured trending popular preorder preorders instock soldout outofstock quickview notifyme comparesimilar addtocart buynow viewall seeall showmore loadmore continue checkoutnow subtotal total quantity description details specifications shippingpolicy privacypolicy termsofservice contactus aboutus myaccount orderhistory trackorder'.split(' '));
const ADDON=new Set('expansion expansions exp promo promos pack packs minipack upgrade upgrades kit accessory accessories sleeves sleeve playmat playmats mat insert inserts organizer organiser miniatures minis meeples tokens dice bag bundle addon supplement scenario scenarios module modules deck booster replacement sticker stickers poster shirt puzzle'.split(' '));
const STOP=new Set("the a an of and or to in on for with game board edition new sale off free add cart price shipping buy now from by your you all out stock save deal deals".split(' '));

// index
const lines=fs.readFileSync('../app/src/main/assets/games.tsv','utf8').split('\n');
const games=[]; const byNorm=new Map(); const byTok=new Map(); const byAff=new Map();
for(let i=1;i<lines.length;i++){const p=lines[i].split('\t'); if(p.length<7)continue;
  const g={id:p[0],name:p[1],year:p[2],rank:+p[3],rating:+p[4],users:+p[5],norm:p[6]};
  const idx=games.length; games.push(g);
  if(!byNorm.has(g.norm)) byNorm.set(g.norm,idx);
  for(const raw of g.name.split(/[^A-Za-z0-9]+/)){ if(raw.length<4)continue; const t=norm(raw); if(t.length>=4&&!STOP.has(t)){ if(!byTok.has(t))byTok.set(t,[]); byTok.get(t).push(idx);} }
  if(g.users>=2000&&g.norm.length>=6&&g.norm.length<=18){ for(const key of ['p'+g.norm.slice(0,3),'s'+g.norm.slice(-3)]){ if(!byAff.has(key))byAff.set(key,[]); byAff.get(key).push(idx);} }
}
const owned=new Set(JSON.parse(fs.readFileSync('../app/src/main/assets/owned.json','utf8')).map(norm));
const medians=new Map(JSON.parse(fs.readFileSync('../app/src/main/assets/medians.json','utf8')).map(([n,v])=>[norm(n),v]));

const SEP=/\s*[:\u2013\u2014]\s*|\s+-\s+/;
function prefixBeforeSep(text,phrase){const parts=text.split(SEP);
  if(parts.length<2||!parts[1].trim())return false;
  return norm(parts[0])===norm(phrase);}
function isAddon(words,start,size,g){
  const own=new Set(g.name.split(/[^\p{L}\p{N}'&:.\u2013-]+/u).map(norm).filter(Boolean));
  for(let i=0;i<words.length;i++){ if(i>=start&&i<start+size)continue;
    const w=norm(words[i]); if(!w||own.has(w))continue; if(ADDON.has(w))return true; }
  return false;}
function acceptable(g,wc,n,whole){
  if(n.length<=3) return whole&&g.rank>=1&&g.rank<=300&&g.users>=20000;
  if(whole){ if(n.length<=5) return g.users>=300; return g.users>=150; }
  if(n.length<=5) return g.rank>=1&&g.rank<=600&&g.users>=5000;
  if(wc===1&&n.length<9) return g.users>=1000;
  if(g.users<50) return wc>=2&&n.length>=10;
  return true;
}
function lev(a,b){if(!a.length)return b.length;if(!b.length)return a.length;let prev=[...Array(b.length+1).keys()],cur=new Array(b.length+1);
 for(let i=1;i<=a.length;i++){cur[0]=i;for(let j=1;j<=b.length;j++){cur[j]=Math.min(cur[j-1]+1,prev[j]+1,prev[j-1]+(a[i-1]===b[j-1]?0:1));}prev=cur.slice();}return prev[b.length];}
const sim=(a,b)=>a===b?1:1-lev(a,b)/Math.max(a.length,b.length);

function matchLine(text){
  const words=text.split(/[^\p{L}\p{N}'&:.\u2013-]+/u).filter(Boolean);
  if(!words.length) return null;
  for(let size=Math.min(8,words.length);size>=1;size--){
    for(let s=0;s+size<=words.length;s++){
      const phrase=words.slice(s,s+size).join(' ');
      const n=norm(phrase); if(n.length<3||CHROME.has(n))continue;
      if(!byNorm.has(n))continue;
      const g=games[byNorm.get(n)];
      const wholeLine=size===words.length;
      if(!acceptable(g,size,n,wholeLine))continue;
      if(!wholeLine&&isAddon(words,s,size,g))continue;
      if(!wholeLine&&prefixBeforeSep(text,phrase))continue;
      return {g,conf:1,matched:phrase,partial:!wholeLine,line:text};
    }
  }
  const nw=words.map(norm).filter(w=>w.length>=4);
  const whole=norm(text); if(whole.length<8||CHROME.has(whole))return null;
  const single=nw.length<2;
  if(single&&whole.length<6)return null;
  let best=null,bs=0,seen=new Set();
  const pool=[];
  if(single){ for(const key of ['p'+whole.slice(0,3),'s'+whole.slice(-3)]){ const ids=byAff.get(key); if(ids&&ids.length<=400) for(const i of ids) pool.push(i);} }
  else { for(const w of nw){ if(STOP.has(w))continue; const ids=byTok.get(w); if(!ids||ids.length>300)continue; for(const i of ids) pool.push(i);} }
  for(const w of [0]){
    for(const i of pool){ if(seen.has(i))continue; seen.add(i); const g=games[i];
      if(g.norm.length<6)continue; const lr=g.norm.length/whole.length; if(lr<0.6||lr>1.6)continue;
      const s=sim(whole,g.norm); if(s>bs){bs=s;best=g;} } }
  const minSim=single?0.86:0.88, minUsers=single?2000:500;
  if(best&&bs>=minSim&&best.users>=minUsers&&best.rank<=15000&&acceptable(best,2,best.norm,true))
    return {g:best,conf:bs,matched:text};
  return null;
}
function verdict(g,price,list){
  if(owned.has(g.norm)) return {tier:'OWNED'};
  const med=medians.get(g.norm);
  let base=null,basis=null;
  if(med!=null){base=med;basis='median';} else if(price!=null&&list!=null&&list>price*1.05){base=list;basis='list';}
  const disc=(price!=null&&base)?Math.round((1-price/base)*100):null;
  const fails=[]; if(g.rating<7)fails.push('rating'); if(g.rank<=0||g.rank>=2500)fails.push('rank');
  if(fails.length) return {tier:'PASS',reason:fails.join(','),disc};
  if(disc==null) return {tier:'UNKNOWN',disc:null};
  if(disc>=50)return{tier:'BUY',disc}; if(disc>=35)return{tier:'NEAR',disc}; return {tier:'PASS',reason:'discount',disc};
}
module.exports={norm,games,byNorm,matchLine,verdict,owned,medians};

if(require.main===module){
 // ---- Test 1: every tracker deal name resolves and agrees with the dashboard ----
 const src=fs.readFileSync('/mnt/user-data/uploads/cowork-artifacts/board-game-deal-tracker/index.html','utf8');
 const i=src.indexOf('const ALL_DEALS = ['); let j=src.indexOf('[',i),d=0,k=j;
 for(;k<src.length;k++){if(src[k]==='[')d++;else if(src[k]===']'){d--;if(!d)break;}}
 const ALL=eval(src.slice(j,k+1));
 let unresolved=[],agree=0,disagree=[];
 for(const row of ALL){
   const m=matchLine(row.name);
   if(!m){unresolved.push(row.name);continue;}
   const trackerQualifies = row.stock && row.rating>=7 && Math.round((1-row.price/row.median)*100)>=50 &&
      !(row.rank===0) && (row.rank===null||row.rank<2500) && !owned.has(norm(row.name));
   const v=verdict(m.g,row.price,null);
   const appBuy = v.tier==='BUY';
   if(appBuy===trackerQualifies) agree++;
   else disagree.push({name:row.name,tracker:trackerQualifies,app:v,idxRank:m.g.rank,idxRating:m.g.rating,rowRank:row.rank,rowRating:row.rating});
 }
 console.log('=== T1 tracker rows:',ALL.length,'unresolved:',unresolved.length,'agree:',agree,'disagree:',disagree.length);
 if(unresolved.length) console.log('  unresolved:',unresolved);
 disagree.slice(0,20).forEach(x=>console.log('  DIFF',JSON.stringify(x)));
}

if(require.main===module){
 console.log('\n=== T2 false positives on ordinary page chrome ===');
 const noise=["Add to Cart","Free shipping on orders over $75","Sign in","Your Account","Today's Deals",
  "Customer reviews","4.6 out of 5 stars","Frequently bought together","Return policy","Search Amazon",
  "In Stock. Ships from Amazon.","Toys & Games","Best Sellers Rank: #1,204 in Toys & Games",
  "About this item","Save 20% with coupon","Only 3 left in stock - order soon.","Compare with similar items",
  "Delivery Tuesday, August 25","Gift options available","Sponsored","Roll over image to zoom in",
  "Brand New","Price: $49.99","See all reviews","Report an issue with this product","Home","Menu",
  "Departments","Board Games","Family Games","Strategy Games","Card Games","Party Games","Sale"];
 let fp=0;
 for(const n of noise){const m=matchLine(n); if(m){fp++;console.log('  FP:',JSON.stringify(n),'->',m.g.name,`(rank ${m.g.rank}, ${m.g.users} users, conf ${m.conf.toFixed(2)})`);}}
 console.log('  lines:',noise.length,'false positives:',fp);

 console.log('\n=== T3 realistic store listing ===');
 const page=[
  ["Praga Caput Regni",null],["$24.97",24.97],["$65.99",65.99],
  ["Wingspan",null],["$44.99",44.99],
  ["Everdell Collectors Edition",null],["$89.99",89.99],
  ["Ark Nova",null],["$59.95",59.95],
  ["Azul",null],["$27.49",27.49],["$39.99",39.99],
  ["Dominion Second Edition",null],["$29.99",29.99],
  ["MEZO",null],["$21.00",21.00],
  ["Bruxelles 1897",null],["$19.99",19.99],
  ["Add to Cart",null],["Free shipping over $75",null]
 ];
 for(const [text] of page){
   const m=matchLine(text);
   if(!m){ if(!/^\$/.test(text)) console.log(' (no match)',text); continue; }
   console.log('  ',text.padEnd(30),'->',m.g.name.padEnd(30),`rank ${String(m.g.rank).padEnd(6)} rating ${m.g.rating}`);
 }

 console.log('\n=== T4 verdicts with prices (median where known, else list) ===');
 const cases=[["Praga Caput Regni",24.97,65.99],["Wingspan",44.99,null],["Azul",27.49,39.99],
   ["Bruxelles 1897",19.99,null],["Dominion",29.99,49.99],["Mezo",21.00,44.99],["Ark Nova",59.95,79.99]];
 for(const [n,p,l] of cases){
   const m=matchLine(n); if(!m){console.log('  no match',n);continue;}
   const v=verdict(m.g,p,l);
   console.log('  ',n.padEnd(22),JSON.stringify(v));
 }

 console.log('\n=== T5 OCR damage (fuzzy) ===');
 for(const s of ["Praga Caput Regnl","Wlngspan","Brass: Birmlngham","Terraforming Marss","Ticket to Rlde: Europe"]){
   const m=matchLine(s);
   console.log('  ',s.padEnd(24),'->',m?`${m.g.name} (${m.conf.toFixed(2)})`:'NO MATCH');
 }
}
