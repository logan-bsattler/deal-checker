const fs=require('fs');
const csv=fs.readFileSync('/tmp/bgg.csv','utf8');
function parseCsv(text){
  const rows=[]; let i=0,cur='',row=[],q=false;
  while(i<text.length){const c=text[i];
    if(q){ if(c==='"'){ if(text[i+1]==='"'){cur+='"';i++;} else q=false; } else cur+=c; }
    else if(c==='"') q=true;
    else if(c===','){ row.push(cur); cur=''; }
    else if(c==='\n'){ row.push(cur); rows.push(row); row=[]; cur=''; }
    else if(c!=='\r') cur+=c;
    i++;}
  if(cur.length||row.length){row.push(cur);rows.push(row);}
  return rows;
}
const rows=parseCsv(csv);
const head=rows[0];
const idx=n=>head.indexOf(n);
const [cId,cName,cYear,cRank,cAvg,cBayes,cUsers]=['ID','Name','Year','Rank','Average','Bayes average','Users rated'].map(idx);
const norm=s=>s.normalize('NFD').replace(/[̀-ͯ]/g,'').toLowerCase().replace(/^the\s+/,'').replace(/[^a-z0-9]/g,'');
const out=[];
for(let r=1;r<rows.length;r++){
  const x=rows[r]; if(!x||x.length<7) continue;
  const rank=parseInt(x[cRank],10); if(!rank) continue;
  const name=x[cName].trim(); if(!name) continue;
  const nn=norm(name); if(nn.length<3) continue;
  out.push([x[cId],name,x[cYear]||'',rank,x[cAvg]||'',x[cUsers]||'0',nn].join('\t'));
}
out.sort((a,b)=>parseInt(a.split('\t')[3])-parseInt(b.split('\t')[3]));
fs.writeFileSync('/home/claude/dealcheck/out/games.tsv','id\tname\tyear\trank\trating\tusers\tnorm\n'+out.join('\n')+'\n');
console.log('games',out.length);
