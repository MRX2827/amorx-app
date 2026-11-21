#!/usr/bin/env python3
"""Launch the Release app in a clean simulator matching the selected Xcode SDK."""
import json, pathlib, subprocess, time

root = pathlib.Path(__file__).resolve().parents[1]
build = root / 'build'
build.mkdir(exist_ok=True)
log = build / 'simulator-launch.log'

def run(*args, timeout=60):
    with log.open('a') as stream:
        stream.write('$ ' + ' '.join(map(str, args)) + '\n')
    try:
        result = subprocess.run(args, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=timeout, check=False)
    except subprocess.TimeoutExpired as error:
        output = error.stdout or b''
        if isinstance(output, bytes): output = output.decode(errors='replace')
        with log.open('a') as stream: stream.write(output + '\nTimed out\n')
        raise
    with log.open('a') as stream: stream.write(result.stdout + '\n')
    result.check_returncode()
    return result.stdout

log.write_text('')
sdk = run('xcrun', '--sdk', 'iphonesimulator', '--show-sdk-version').strip()
runtimes = json.loads(run('xcrun', 'simctl', 'list', 'runtimes', '--json'))['runtimes']
# Newer runtimes may coexist on a runner but belong to a different installed Xcode.
version = tuple(map(int, sdk.split('.')[:2]))
matching = [runtime for runtime in runtimes
            if runtime.get('isAvailable') and runtime.get('identifier', '').startswith('com.apple.CoreSimulator.SimRuntime.iOS-')
            and tuple(map(int, runtime['version'].split('.')[:2])) == version]
assert matching, f'No available iOS simulator runtime matching SDK {sdk}'
runtime = matching[0]
uid = run('xcrun', 'simctl', 'create', 'AmorX release smoke',
          'com.apple.CoreSimulator.SimDeviceType.iPhone-16', runtime['identifier']).strip()
(build / 'simulator-environment.json').write_text(json.dumps({'sdk': sdk, 'runtime': runtime, 'device': uid}, indent=2))
app = root / 'DerivedData/Build/Products/Release-iphonesimulator/AmorX.app'
# Device IPA remains unsigned; the simulator copy needs its own local ad-hoc signature.
run('codesign', '--force', '--deep', '--sign', '-', '--timestamp=none', str(app))
run('xcrun', 'simctl', 'boot', uid)
run('xcrun', 'simctl', 'bootstatus', uid, '-b', timeout=240)
run('xcrun', 'simctl', 'install', uid, str(app), timeout=180)
result = run('xcrun', 'simctl', 'launch', uid, 'com.amorx.ios', timeout=90)
pid = int(result.strip().rsplit(':', 1)[1])
time.sleep(6)
subprocess.run(['kill', '-0', str(pid)], check=True)
run('xcrun', 'simctl', 'io', uid, 'screenshot', str(build / 'ios-launch.png'))
(build / 'simulator-verification.json').write_text(json.dumps({
    'bundle_id': 'com.amorx.ios', 'sdk': sdk, 'runtime': runtime['identifier'],
    'pid': pid, 'launch_passed': True, 'alive_after_seconds': 6
}, indent=2) + '\n')
print('Release app remained running after startup on iPhone 16, iOS', runtime['version'])
