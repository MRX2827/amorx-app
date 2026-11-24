#!/usr/bin/env python3
"""Verify and package an unsigned device Release; a sideload installer signs it later."""
import hashlib, json, pathlib, plistlib, shutil, subprocess, zipfile
root=pathlib.Path(__file__).resolve().parents[1]
app=root/'DerivedData/Build/Products/Release-iphoneos/AmorX.app'
info=plistlib.loads((app/'Info.plist').read_bytes())
assert info['CFBundleIdentifier']=='com.amorx.ios'
assert info['CFBundleVersion']=='1329'
assert info['CFBundleShortVersionString']=='5.5.499'
assert tuple(map(int, info['MinimumOSVersion'].split('.'))) == (15, 0), info['MinimumOSVersion']
assert info.get('DTPlatformName')=='iphoneos'
assert 'audio' in info['UIBackgroundModes']
binary=app/info['CFBundleExecutable']
arches=subprocess.check_output(['lipo','-archs',str(binary)],text=True).strip().split()
assert 'arm64' in arches and 'x86_64' not in arches, arches
for font in info['UIAppFonts']: assert (app/font).is_file(), font
out=root/'build'; out.mkdir(exist_ok=True)
ipa=out/'AmorX-iOS-5.5.499.1329.ipa'
with zipfile.ZipFile(ipa,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for path in sorted(app.rglob('*')):
        if path.is_file(): z.write(path, pathlib.PurePosixPath('Payload/AmorX.app')/path.relative_to(app))
with zipfile.ZipFile(ipa) as z: assert z.testzip() is None
report={'version':'5.5.499.1329','bundle_id':info['CFBundleIdentifier'],'minimum_ios':info['MinimumOSVersion'],'architectures':arches,'configuration':'Release','signing':'unsigned; requires signing by installation tool','sha256':hashlib.sha256(ipa.read_bytes()).hexdigest(),'size':ipa.stat().st_size}
(out/'ipa-verification.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
