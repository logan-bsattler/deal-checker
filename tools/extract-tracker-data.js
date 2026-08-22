const fs=require('fs');
const src=fs.readFileSync('/mnt/user-data/uploads/cowork-artifacts/board-game-deal-tracker/index.html','utf8');
function grab(name){
  const i=src.indexOf('const '+name+' = [');
  if(i<0) throw new Error('no '+name);
  let j=src.indexOf('[',i), depth=0, k=j;
  for(;k<src.length;k++){ if(src[k]==='[')depth++; else if(src[k]===']'){depth--; if(!depth)break;} }
  return src.slice(j,k+1);
}
const OWNED=eval(grab('OWNED'));
const ALL_DEALS=eval(grab('ALL_DEALS'));
console.log('owned',OWNED.length,'deals',ALL_DEALS.length);
fs.writeFileSync('/home/claude/dealcheck/out/owned.json',JSON.stringify(OWNED,null,0));
const med={};
for(const d of ALL_DEALS){ if(d.median&&d.name){ (med[d.name]=med[d.name]||[]).push(d.median); } }
const medians=Object.entries(med).map(([n,v])=>[n, v.sort((a,b)=>a-b)[Math.floor(v.length/2)]]);
fs.writeFileSync('/home/claude/dealcheck/out/medians.json',JSON.stringify(medians));
console.log('medians',medians.length);
console.log(medians.slice(0,5));
