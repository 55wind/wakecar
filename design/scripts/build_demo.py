"""Bundle the prototype as one directly openable HTML file."""
from pathlib import Path
import re, base64
root=Path(__file__).resolve().parents[1]
proto=root/'proto'
s=(proto/'index.html').read_text()
css='\n'.join((proto/f).read_text() for f in ['tokens.css','base.css','app.css'])
css=re.sub(r'@import url\("\./(?:tokens|base)\.css"\);', '', css)
s=s.replace('<link rel="stylesheet" href="app.css">','<style>\n'+css+'\n</style>')
for f in ['audio.js','spring.js','chars.js','grow.js','app.js']:
    s=s.replace(f'<script src="{f}"></script>', '<script>\n'+(proto/f).read_text()+'\n</script>')
s=s.replace('src="assets/icon.svg"','src="data:image/svg+xml;base64,'+base64.b64encode((proto/'assets/icon.svg').read_bytes()).decode()+'"')
for f in sorted((proto/'assets').rglob('*.webp')):
    rel=str(f.relative_to(proto))
    s=s.replace(rel,'data:image/webp;base64,'+base64.b64encode(f.read_bytes()).decode())
(root/'wakecar-demo.html').write_text(s)
print('Built',root/'wakecar-demo.html')
