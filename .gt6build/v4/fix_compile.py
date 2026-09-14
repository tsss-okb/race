#!/usr/bin/env python3
from pathlib import Path
import argparse

p=argparse.ArgumentParser()
p.add_argument('repo', nargs='?', default='.')
a=p.parse_args()
root=Path(a.repo).resolve()
svc=root/'Flight/src/main/java/de/droiddrone/flight/DDService.java'
manifest=root/'Flight/src/main/AndroidManifest.xml'

s=svc.read_text(encoding='utf-8')
old='''        if (MainActivity.config == null) MainActivity.config = new Config(this, SettingsCommon.versionCompatibleCode);\n'''
new='''        if (MainActivity.config == null) {\n            log("Config not initialized; stopping service restore safely.");\n            stopSelf();\n            return START_NOT_STICKY;\n        }\n'''
if old not in s:
    raise SystemExit('DDService compile-fix anchor not found')
svc.write_text(s.replace(old,new,1),encoding='utf-8')
print('fixed:', svc.relative_to(root))

# AGP controls extraction via useLegacyPackaging=true; keep manifest clean.
m=manifest.read_text(encoding='utf-8')
m=m.replace('        android:extractNativeLibs="true"\n','')
manifest.write_text(m,encoding='utf-8')
print('fixed:', manifest.relative_to(root))
