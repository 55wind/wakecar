"""Build a music-only soundtrack from WakeCar's original app audio."""
import json, subprocess, wave
from pathlib import Path
import numpy as np
ROOT=Path(__file__).resolve().parent; BUILD=ROOT/'build'; BUILD.mkdir(exist_ok=True)
SCENES=json.loads((ROOT/'scenes.json').read_text()); SR=48000

def decode(name):
 p=ROOT.parent/'app/src/main/res/raw'/f'{name}.ogg'
 b=subprocess.check_output(['ffmpeg','-v','error','-i',str(p),'-f','f32le','-ar',str(SR),'-ac','2','-'])
 return np.frombuffer(b,dtype=np.float32).reshape(-1,2).copy()
def norm(a,rms):return a*(rms/max(float(np.sqrt(np.mean(a*a))),1e-6))
def fade(a,sec=.55):
 n=min(int(SR*sec),len(a)//2);a[:n]*=np.linspace(0,1,n)[:,None];a[-n:]*=np.linspace(1,0,n)[:,None];return a

total=sum(s['duration'] for s in SCENES);mix=np.zeros((total*SR,2),np.float32);cues=[];start=0
for s in SCENES:
 tracks=[(s['music'],s['duration'])]
 if s['id']=='stages':tracks=[(f'alarm_sunrise_{i}',3.5) for i in range(1,5)]
 if s['id']=='sound':tracks=[('sleep_piano',3),('alarm_sunrise_1',3),('alarm_sunrise_3',4)]
 offset=0
 for name,dur in tracks:
  n=round(dur*SR); rms=.085 if name.startswith('sleep') else .075
  if s['id']=='stages':rms={1:.038,2:.055,3:.075,4:.10}[int(name[-1])]
  if s['id']=='sound' and name.startswith('alarm'):rms=.045 if name.endswith('_1') else .09
  a=norm(decode(name),rms)
  a=np.tile(a,(int(np.ceil(n/len(a))),1))[:n].copy();fade(a)
  at=round((start+offset)*SR);mix[at:at+n]+=a;offset+=dur
 slot=(s['duration']-1.5)/len(s['captions'])
 for j,txt in enumerate(s['captions']):
  cues.append({'start':round(start+.8+j*slot,3),'end':round(start+.8+(j+1)*slot-.18,3),'text':txt})
 start+=s['duration']
for at,name in [(32.0,'fx_start'),(72.0,'fx_done'),(178,'fx_done')]:
 a=fade(norm(decode(name),.07),.02);i=int(at*SR);n=min(len(a),len(mix)-i);mix[i:i+n]+=a[:n]
mix=np.clip(mix,-.94,.94)
mono=mix.mean(axis=1)
rms=np.sqrt((mono.reshape(-1,1600)**2).mean(axis=1))
(BUILD/'wave.json').write_text(json.dumps((rms/max(float(rms.max()),1e-6)).tolist()))
with wave.open(str(BUILD/'mix.wav'),'wb') as w:
 w.setnchannels(2);w.setsampwidth(2);w.setframerate(SR);w.writeframes((mix*32767).astype('<i2').tobytes())
(BUILD/'cues.json').write_text(json.dumps(cues,ensure_ascii=False,indent=2))
def stamp(t):
 ms=round(t*1000);return f'{ms//3600000:02}:{ms//60000%60:02}:{ms//1000%60:02},{ms%1000:03}'
(ROOT/'output').mkdir(exist_ok=True)
(ROOT/'output/wakecar-jury-ko.srt').write_text('\n\n'.join(f'{i+1}\n{stamp(c["start"])} --> {stamp(c["end"])}\n{c["text"]}' for i,c in enumerate(cues))+'\n')
print(f'{total}s music-only audio; {len(cues)} subtitle cues; no narration.')
