"""Normalize music, add chapters, and mux the finished Full HD film."""
import json,subprocess,re,shutil
from pathlib import Path
R=Path(__file__).resolve().parent;B=R/'build';O=R/'output'
def run(args):subprocess.run(args,check=True)
scenes=json.loads((R/'scenes.json').read_text());meta=[';FFMETADATA1','title=일어나차 | 심사위원 소개','artist=난나고현대는너야','comment=앱 원작 음악과 한국어 자막. 음성 내레이션 없음.'];start=0
for s in scenes:
 meta+=['[CHAPTER]','TIMEBASE=1/1000',f'START={start*1000}',f'END={(start+s["duration"])*1000}',f'title={s["chapter"]}'];start+=s['duration']
(B/'chapters.txt').write_text('\n'.join(meta)+'\n')
p=subprocess.run(['ffmpeg','-hide_banner','-i',str(B/'mix.wav'),'-af','loudnorm=I=-18:TP=-1.5:LRA=9:print_format=json','-f','null','-'],capture_output=True,text=True,check=True)
v=json.loads(re.findall(r'\{[^{}]*"input_i"[^{}]*\}',p.stderr,re.S)[-1])
f=(f'loudnorm=I=-18:TP=-1.5:LRA=9:measured_I={v["input_i"]}:measured_TP={v["input_tp"]}:measured_LRA={v["input_lra"]}:measured_thresh={v["input_thresh"]}:offset={v["target_offset"]}:linear=true')
run(['ffmpeg','-y','-hide_banner','-loglevel','warning','-i',str(B/'visual.mp4'),'-i',str(B/'mix.wav'),'-i',str(B/'chapters.txt'),'-map','0:v','-map','1:a','-map_metadata','2','-map_chapters','2','-c:v','copy','-af',f,'-c:a','aac','-b:a','192k','-ar','48000','-t','180','-movflags','+faststart',str(O/'wakecar-jury-hallmark-3min.mp4')])
shutil.copy(B/'preview-13.jpg',O/'wakecar-cover.jpg')
print('Final film:',O/'wakecar-jury-hallmark-3min.mp4')
