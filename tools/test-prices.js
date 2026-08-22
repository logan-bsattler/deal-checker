const t=require('./test-matcher.js');
module.exports={};
const W=1080,H=2400;
const R=(l,tp,r,b)=>({left:l,top:tp,right:r,bottom:b,centerX:()=>(l+r)/2,centerY:()=>(tp+b)/2,width:()=>r-l});

const RE_PRICE=/\$\s?([0-9]{1,3}(?:,[0-9]{3})*|[0-9]{1,5})(?:[.,]([0-9]{2}))?/g;
const RE_PERCENT=/(\d{1,2})\s?%/g;
const RE_LIST=/\b(msrp|list price|list|was|reg\.?|regular|retail|compare at|compare|orig\.?|originally|value)\b/i;
const RE_SAVE=/\b(you save|save)\b/i;
const RE_NOT=/\b(out of|stars?|rating|complete|cotton|battery|charged)\b/i;
const RE_NOT_PRICE=/\b(free shipping|orders? over|ships? free|spend|minimum|subtotal|total|coupon|gift card|shipping|delivery|financing|per month|\/mo)\b/i;

function pricesIn(lines){const out=[];for(const l of lines){if(RE_NOT_PRICE.test(l.text))continue;const isList=RE_LIST.test(l.text);const isSave=RE_SAVE.test(l.text)&&!isList;
  RE_PRICE.lastIndex=0;let m;while((m=RE_PRICE.exec(l.text))){const v=(+m[1].replace(/,/g,''))+(m[2]?+m[2]/100:0);
   if(v<1||v>2000||isSave)continue;out.push({value:v,box:l.box,isList});}}return out;}
function discountsIn(lines){const out=[];for(const l of lines){if(RE_NOT.test(l.text))continue;
  if(!(l.text.includes('-')||/off/i.test(l.text)||RE_SAVE.test(l.text)))continue;
  RE_PERCENT.lastIndex=0;let m;while((m=RE_PERCENT.exec(l.text))){const p=+m[1];if(p>=15&&p<=95)out.push({percent:p,box:l.box});}}return out;}
const ADDON2=new Set("expansion expansions exp promo promos pack packs minipack upgrade upgrades kit accessory accessories sleeves sleeve playmat playmats mat insert inserts organizer organiser miniatures minis meeples tokens dice bag bundle addon supplement scenario scenarios module modules deck booster replacement sticker stickers poster shirt puzzle".split(' '));
const nrm=s=>s.normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/[^a-z0-9]/g,'');
function barriersOf(lines){return lines.filter(l=>l.text.split(/[^\p{L}\p{N}'&:.\u2013-]+/u).some(w=>ADDON2.has(nrm(w)))).map(l=>l.box);}
function blocked(price,title,bars){const lo=Math.min(price.centerY(),title.centerY()),hi=Math.max(price.centerY(),title.centerY());
 for(const b of bars){const by=b.centerY(); if(by<=lo||by>=hi)continue;
  if(b.right>Math.min(price.left,title.left)&&b.left<Math.max(price.right,title.right))return true;} return false;}
function nearest(box,hits,bars){const maxD=0.20*H;let best=null,bs=1e9;const cx=box.centerX(),cy=box.centerY();
 for(const h of hits){const b=h.box; if(blocked(box,b,bars||[]))continue;
  const dy=cy>b.bottom?(cy-b.bottom):(cy<b.top?(b.top-cy)*2.5:0);
  const dx=(cx>=b.left&&cx<=b.right)?0:Math.min(Math.abs(cx-b.left),Math.abs(cx-b.right));
  if(dx>Math.max(0.45*W,b.width()))continue;
  const sc=dy+dx*0.6; if(sc<maxD&&sc<bs){bs=sc;best=h;}}
 return best;}
function attach(hits,prices,badges,bars){const byHit={},bg={};
 for(const p of prices){const h=nearest(p.box,hits,bars);if(h)(byHit[h.g.id]=byHit[h.g.id]||[]).push(p);}
 for(const b of badges){const h=nearest(b.box,hits,bars);if(h)(bg[h.g.id]=bg[h.g.id]||[]).push(b.percent);}
 const out={};
 for(const h of hits){const tags=byHit[h.g.id]||[];const badge=bg[h.g.id]?Math.max(...bg[h.g.id]):null;
  const sales=tags.filter(x=>!x.isList).map(x=>x.value), listed=tags.filter(x=>x.isList).map(x=>x.value);
  const price=sales.length?Math.min(...sales):(listed.length===1?listed[0]:null);
  let list=null,labelled=false;const bl=listed.length?Math.max(...listed):null;
  if(price!=null&&bl!=null&&bl>price*1.05){list=bl;labelled=true;}
  else if(price!=null){const hi=sales.length?Math.max(...sales):null; if(hi!=null&&hi>price*1.15)list=hi;}
  out[h.g.id]={price,list,labelled,badge};}
 return out;}
function verdict(g,ev){
 if(t.owned.has(g.norm))return{tier:'OWNED'};
 const med=t.medians.get(g.norm);let base=null,basis=null,disc=null;
 if(med!=null){base=med;basis='median';}
 else if(ev&&ev.price!=null&&ev.list!=null){base=ev.list;basis=ev.labelled?'list':'struck';}
 if(ev&&ev.price!=null&&base){disc=Math.round((1-ev.price/base)*100);}
 else if(ev&&ev.badge!=null){disc=ev.badge;basis='badge';}
 const fails=[];if(g.rating<7)fails.push('rating');if(g.rank<=0||g.rank>=2500)fails.push('rank');
 if(fails.length)return{tier:'PASS',reason:fails.join(','),disc,basis};
 if(disc==null)return{tier:ev&&ev.price!=null?'NO_BASELINE':'NO_PRICE'};
 if(disc>=50)return{tier:'BUY',disc,basis};
 if(disc>=35)return{tier:'NEAR',disc,basis};
 return{tier:'PASS',reason:'discount',disc,basis};}

function run(name,lines){
 const hits=[];
 for(const l of lines){const m=t.matchLine(l.text); if(m) hits.push({g:m.g,box:l.box});}
 const ev=attach(hits,pricesIn(lines),discountsIn(lines),barriersOf(lines));
 console.log('\n=== '+name+' ===');
 for(const h of hits){const e=ev[h.g.id];const v=verdict(h.g,e);
  console.log('  '+h.g.name.padEnd(26),'price',String(e.price).padEnd(7),'list',String(e.list).padEnd(7),'badge',String(e.badge).padEnd(5),'->',JSON.stringify(v));}
 if(!hits.length)console.log('  (no titles matched)');
}

// --- grid of product tiles, two columns ---
const grid=[];
const tiles=[
 ['Praga Caput Regni',['$24.97','-62%']],
 ['Ark Nova',['$59.95','MSRP $79.99']],
 ['Wingspan',['$44.99']],
 ['Everdell',['$39.99','Was $69.99']],
 ['Mandala Stones',['$12.99','Save 52%']],
 ['Bruxelles 1897',['$19.99','$45.99']],
];
tiles.forEach(([title,extra],i)=>{
 const col=i%2, row=Math.floor(i/2);
 const x=40+col*520, y=200+row*700;
 grid.push({text:title,box:R(x,y,x+460,y+40)});
 extra.forEach((e,k)=>grid.push({text:e,box:R(x,y+60+k*45,x+300,y+95+k*45)}));
});
grid.push({text:'Add to Cart',box:R(40,2200,400,2240)});
grid.push({text:'Free shipping over $75',box:R(40,2260,600,2300)});
run('grid page (2 columns)',grid);

// --- product detail page ---
run('product detail page',[
 {text:'Ticket to Ride: Europe',box:R(60,300,900,360)},
 {text:'4.6 out of 5 stars  (12,481)',box:R(60,380,700,420)},
 {text:'$27.49',box:R(60,520,300,580)},
 {text:'List Price: $54.99',box:R(60,600,500,640)},
 {text:'You save $27.50 (50%)',box:R(60,660,600,700)},
 {text:'In Stock. Ships from Amazon.',box:R(60,760,700,800)},
 {text:'Add to Cart',box:R(60,860,400,910)},
]);

// --- clearance list, price only, no baseline anywhere ---
run('clearance list, bare prices',[
 {text:'Terra Nova',box:R(60,300,600,340)},
 {text:'$29.97',box:R(60,350,300,390)},
 {text:'Messina 1347',box:R(60,500,600,540)},
 {text:'$24.97',box:R(60,550,300,590)},
 {text:'Millennium Blades',box:R(60,700,600,740)},
 {text:'$48.00',box:R(60,750,300,790)},
]);

// --- the Greater Than Games cart: a base game directly above its own expansion ---
run('cart with an expansion row',[
 {text:'Artipia Games',box:R(220,1040,700,1080)},
 {text:'Rush M.D.',box:R(220,1100,700,1160)},
 {text:'$41.98',box:R(220,1180,500,1220)},
 {text:'Total',box:R(220,1260,400,1300)},
 {text:'$41.98',box:R(220,1320,500,1360)},
 {text:'Rush M.D. ICU Expansion',box:R(220,1600,900,1660)},
 {text:'$7.99',box:R(220,1680,500,1720)},
 {text:'Total',box:R(220,1760,400,1800)},
 {text:'$7.99',box:R(220,1820,500,1860)},
]);
