#!/usr/bin/env python3
"""Boot an available iPhone, install the Release app, and capture startup diagnostics."""
import json, pathlib, subprocess, time
root=pathlib.Path(__file__).resolve().parents[1]
def run(*args, **kw): return subprocess.check_output(args,text=True,**kw)
devices=json.loads(run('xcrun','simctl','list','devices','available','--json'))['devices']
phones=[d for runtime, rows in devices.items() if 'iOS' in runtime for d in rows if d['name'].startswith('iPhone')]
assert phones, 'No iPhone simulator installed'
phone=next((d for d in phones if d['state']=='Booted'),phones[0]); uid=phone['udid']
if phone['state']!='Booted': run('xcrun','simctl','boot',uid)
run('xcrun','simctl','bootstatus',uid,'-b',timeout=180)
app=root/'DerivedData/Build/Products/Release-iphonesimulator/AmorX.app'
run('xcrun','simctl','install',uid,str(app),timeout=60)
result=run('xcrun','simctl','launch',uid,'com.amorx.ios',timeout=60)
(root/'build/simulator-launch.log').write_text(result)
pid=int(result.strip().rsplit(':',1)[1]); time.sleep(6)
subprocess.run(['kill','-0',str(pid)],check=True)
run('xcrun','simctl','io',uid,'screenshot',str(root/'build/ios-launch.png'),timeout=30)
print('Release app remained running after startup on',phone['name'])
