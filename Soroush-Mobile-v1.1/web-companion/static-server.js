const http=require('http'),fs=require('fs'),path=require('path'),https=require('https');
const root=path.join(__dirname,'web');
const port=8785;
const types={'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json; charset=utf-8','.webmanifest':'application/manifest+json; charset=utf-8','.png':'image/png','.svg':'image/svg+xml'};
function json(res,status,obj){res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});res.end(JSON.stringify(obj));}
function readBody(req){return new Promise((resolve,reject)=>{let b='';req.on('data',c=>{b+=c;if(b.length>100000)reject(new Error('body too large'))});req.on('end',()=>resolve(b));req.on('error',reject)});}
async function snappWalletPay(req,res){
  const apiUrl=process.env.SNAPP_WALLET_API_URL;
  const token=process.env.SNAPP_WALLET_TOKEN;
  if(!apiUrl||!token)return json(res,503,{success:false,code:'SNAPP_WALLET_NOT_CONFIGURED',message:'Snapp wallet Partner/API is not configured.'});
  let incoming={}; try{incoming=JSON.parse((await readBody(req))||'{}')}catch{return json(res,400,{success:false,message:'Invalid JSON'})}
  // This is a configurable partner connector. The exact official URL/payload must come from Snapp partner documentation.
  const payload=JSON.stringify(incoming);
  let u; try{u=new URL(apiUrl)}catch{return json(res,500,{success:false,message:'Invalid SNAPP_WALLET_API_URL'})}
  const lib=u.protocol==='https:'?https:http;
  const out=lib.request({method:'POST',hostname:u.hostname,port:u.port||undefined,path:u.pathname+u.search,headers:{'Content-Type':'application/json','Authorization':`Bearer ${token}`,'Content-Length':Buffer.byteLength(payload)}},r=>{
    let body='';r.on('data',c=>body+=c);r.on('end',()=>{res.writeHead(r.statusCode||502,{'Content-Type':r.headers['content-type']||'application/json; charset=utf-8','Cache-Control':'no-store'});res.end(body||JSON.stringify({success:(r.statusCode||500)<300}))});
  });
  out.on('error',e=>json(res,502,{success:false,message:e.message}));out.write(payload);out.end();
}

async function snappRideCancel(req,res){
  const apiUrl=process.env.SNAPP_RIDE_CANCEL_API_URL;
  const token=process.env.SNAPP_RIDE_TOKEN;
  if(!apiUrl||!token)return json(res,503,{success:false,code:'SNAPP_RIDE_CANCEL_NOT_CONFIGURED',message:'Snapp ride cancellation Partner/API is not configured.'});
  let incoming={}; try{incoming=JSON.parse((await readBody(req))||'{}')}catch{return json(res,400,{success:false,message:'Invalid JSON'})}
  const tripId=incoming.tripId||process.env.SNAPP_CURRENT_TRIP_ID||null;
  if(!tripId)return json(res,409,{success:false,code:'NO_ACTIVE_TRIP',message:'شناسه سفر فعال Snapp در دسترس نیست؛ لغوی انجام نشد.'});
  const payload=JSON.stringify({...incoming,tripId});
  let u; try{u=new URL(apiUrl)}catch{return json(res,500,{success:false,message:'Invalid SNAPP_RIDE_CANCEL_API_URL'})}
  const lib=u.protocol==='https:'?https:http;
  const out=lib.request({method:'POST',hostname:u.hostname,port:u.port||undefined,path:u.pathname+u.search,headers:{'Content-Type':'application/json','Authorization':`Bearer ${token}`,'Content-Length':Buffer.byteLength(payload)}},r=>{
    let body='';r.on('data',c=>body+=c);r.on('end',()=>{res.writeHead(r.statusCode||502,{'Content-Type':r.headers['content-type']||'application/json; charset=utf-8','Cache-Control':'no-store'});res.end(body||JSON.stringify({success:(r.statusCode||500)<300,status:(r.statusCode||500)<300?'cancelled':'failed'}))});
  });
  out.on('error',e=>json(res,502,{success:false,message:e.message}));out.write(payload);out.end();
}

http.createServer(async(req,res)=>{
  if(req.method==='POST'&&req.url.split('?')[0]==='/api/snapp-wallet/pay')return snappWalletPay(req,res);
  if(req.method==='POST'&&req.url.split('?')[0]==='/api/snapp-ride/cancel')return snappRideCancel(req,res);
  let p=decodeURIComponent(req.url.split('?')[0]); if(p==='/')p='/index.html';
  const f=path.normalize(path.join(root,p));
  if(!f.startsWith(root)){res.writeHead(403);return res.end('Forbidden');}
  fs.readFile(f,(err,data)=>{if(err){res.writeHead(404,{'Content-Type':'text/plain; charset=utf-8'});return res.end('Not found');}
    res.writeHead(200,{'Content-Type':types[path.extname(f)]||'application/octet-stream','Cache-Control':'no-store, no-cache, must-revalidate','Pragma':'no-cache','Expires':'0'});res.end(data);
  });
}).listen(port,'127.0.0.1',()=>console.log(`Soroush Agent Web is running at http://localhost:${port}`));
