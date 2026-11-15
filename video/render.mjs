import {chromium} from 'playwright-core';
import fs from 'node:fs';import path from 'node:path';import {fileURLToPath} from 'node:url';import {spawn} from 'node:child_process';import {once} from 'node:events';
const root=path.dirname(fileURLToPath(import.meta.url)), repo=path.dirname(root);
const build=path.join(root,'build'),out=path.join(root,'output');fs.mkdirSync(build,{recursive:true});fs.mkdirSync(out,{recursive:true});
const scenes=JSON.parse(fs.readFileSync(path.join(root,'scenes.json'))),cues=JSON.parse(fs.readFileSync(path.join(build,'cues.json')));
const uri=(p,mime)=>`data:${mime};base64,${fs.readFileSync(p).toString('base64')}`;
const images={icon:uri(path.join(repo,'design/character-v2/3d/out/app_icon_512.png'),'image/png')};
for(const s of scenes)for(const key of ['screen','screen2'])if(s[key])images['screen/'+s[key]]=uri(path.join(repo,'docs/screens',s[key]+'.png'),'image/png');
for(const n of ['kongkong','poksin','ppangppi','mongsil'])for(let i=0;i<55;i++){const id=String(i).padStart(2,'0');images[`char/${n}/${id}`]=uri(path.join(repo,'app/src/main/assets/chars',n,id+'.webp'),'image/webp')}
const fontPath=process.env.WAKECAR_FONT;
if(fontPath&&!fs.existsSync(fontPath))throw Error('WAKECAR_FONT 글꼴 파일이 없습니다.');
const browser=await chromium.launch({headless:true});const page=await browser.newPage({viewport:{width:1920,height:1080}});
page.on('pageerror',e=>console.error('Page error',e));await page.goto('file://'+path.join(root,'film.html'));await page.evaluate(d=>window.init(d),{images,font:fontPath?uri(fontPath,'font/ttf'):null,scenes,cues,wave:JSON.parse(fs.readFileSync(path.join(build,'wave.json')))});
if(process.argv.includes('--preview')){let times=[4,13,26,38,51,64,80,85,96,106,119,129,133,141,151,165,176];for(const t of times){const b=await page.evaluate(t=>window.render(t),t);fs.writeFileSync(path.join(build,`preview-${t}.jpg`),Buffer.from(b,'base64'))}await browser.close();console.log('Preview frames ready.');process.exit(0)}
const fps=30,total=scenes.reduce((a,s)=>a+s.duration,0),file=path.join(build,'visual.mp4');
const encoder=spawn('ffmpeg',['-y','-hide_banner','-loglevel','error','-f','image2pipe','-vcodec','mjpeg','-framerate',String(fps),'-i','pipe:0','-c:v','libx264','-preset','fast','-crf','19','-pix_fmt','yuv420p','-r',String(fps),'-movflags','+faststart',file],{stdio:['pipe','ignore','inherit']});
const done=new Promise((resolve,reject)=>encoder.on('close',code=>code===0?resolve():reject(Error('ffmpeg '+code))));
try{for(let f=0;f<total*fps;f++){const b=await page.evaluate(t=>window.render(t),f/fps);if(!encoder.stdin.write(Buffer.from(b,'base64')))await once(encoder.stdin,'drain');if(f%(fps*10)===0)console.log(`Rendered ${f/fps}s / ${total}s`)}encoder.stdin.end();await done;}finally{await browser.close()}
console.log('Visual render complete:',file);
