"""Builds the web app from template.html + parser.js + teachers list.

Outputs:
  web/dist/          - site for GitHub Pages (index.html, icons, manifest)
  ios/KNNPary/web/   - the same page bundled into the iOS app
  argv[1] (optional) - single-file version for a Claude artifact
"""
import json, os, shutil, sys

here = os.path.dirname(os.path.abspath(__file__))
root = os.path.dirname(here)
t = open(os.path.join(here, 'template.html'), encoding='utf-8').read()
teachers = [l.split('\t') for l in open(os.path.join(root, 'app/src/main/assets/teachers.tsv'), encoding='utf-8').read().splitlines() if l]
t = (t.replace('__PARSER__', open(os.path.join(here, 'parser.js'), encoding='utf-8').read())
      .replace('__TEACHERS__', json.dumps(teachers, ensure_ascii=False))
      .replace('__SAMPLE__', open(os.path.join(here, 'sample.json'), encoding='utf-8').read()))
if len(sys.argv) > 1:
    open(sys.argv[1], 'w', encoding='utf-8').write(t)

head = '''<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<meta name="apple-mobile-web-app-title" content="КНН пары">
<meta name="theme-color" content="#1E4FD8">
<link rel="apple-touch-icon" href="apple-touch-icon.png">
<link rel="icon" type="image/png" href="icon-192.png">
<link rel="manifest" href="manifest.webmanifest">
'''
g = t.replace('<meta name="claude:viewport-fit-sides" content="cover">\n', '')
i = g.index('<header class="top">')
pre, body = g[:i], g[i:]
pre = pre.replace('<style>', '<style>\n:root { padding-top: env(safe-area-inset-top, 0px); padding-bottom: env(safe-area-inset-bottom, 0px); color-scheme: light; }\nbody { margin: 0; }\nimg { max-width: 100%; }\n[hidden] { display: none !important; }\n', 1)
page = head + pre + '</head>\n<body>\n' + body + '\n</body>\n</html>\n'

dist = os.path.join(here, 'dist'); os.makedirs(dist, exist_ok=True)
open(os.path.join(dist, 'index.html'), 'w', encoding='utf-8').write(page)
open(os.path.join(dist, '.nojekyll'), 'w').close()
for f in ['apple-touch-icon.png', 'icon-192.png', 'icon-512.png', 'manifest.webmanifest']:
    shutil.copy(os.path.join(here, f), os.path.join(dist, f))
ios = os.path.join(root, 'ios', 'KNNPary', 'web'); os.makedirs(ios, exist_ok=True)
open(os.path.join(ios, 'index.html'), 'w', encoding='utf-8').write(page)
print('built', len(page), 'bytes')
