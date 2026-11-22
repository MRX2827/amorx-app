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

# Validate compact and larger logical viewports using offline simulator-only fixtures.
# This covers SE 2016 geometry without pretending iOS 18 supports that physical model.
import plistlib, hashlib, threading, http.server, struct
plist = app / 'Info.plist'
settings = plistlib.loads(plist.read_bytes())
settings['NSAppTransportSecurity'] = {'NSAllowsArbitraryLoads': True}  # Test copy only, for the loopback fixture.
plist.write_bytes(plistlib.dumps(settings))
run('codesign', '--force', '--deep', '--sign', '-', '--timestamp=none', str(app))
run('xcrun', 'simctl', 'terminate', uid, 'com.amorx.ios')
run('xcrun', 'simctl', 'install', uid, str(app), timeout=180)
container = pathlib.Path(run('xcrun', 'simctl', 'get_app_container', uid, 'com.amorx.ios', 'data').strip()) / 'Documents'
layouts = build / 'layout-screenshots'; layouts.mkdir(exist_ok=True)
layout_results = []
for width, height in [(320, 548), (568, 300), (375, 647), (393, 759), (430, 820)]:
    for screen in ['login', 'library', 'player', 'restore', 'profile']:
        try: run('xcrun', 'simctl', 'terminate', uid, 'com.amorx.ios')
        except subprocess.CalledProcessError: pass
        report = container / 'layout-verification.json'; report.unlink(missing_ok=True)
        run('xcrun', 'simctl', 'launch', uid, 'com.amorx.ios', '--layout-screen', screen,
            '--layout-width', str(width), '--layout-height', str(height))
        deadline = time.monotonic() + 20
        while not report.exists() and time.monotonic() < deadline: time.sleep(.2)
        assert report.exists(), f'Missing layout measurements: {screen} {width}x{height}'
        time.sleep(.5)
        measured = json.loads(report.read_text())
        run('xcrun', 'simctl', 'io', uid, 'screenshot', str(layouts / f'{screen}-{width}x{height}.png'))
        layout_results.append(measured)
        (build / 'layout-verification.json').write_text(json.dumps(layout_results, indent=2))

# Native downloader integration: exact bytes across multiple 206 responses,
# a server ignoring Range (200), and a corrupt range that must never be saved.
payload = bytes(range(256)) * (3 * 1024 * 1024 // 256 + 3)
requests = []
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        header = self.headers.get('Range', '')
        requests.append({'path': self.path, 'range': header})
        if self.path == '/whole':
            self.send_response(200); data = payload
        else:
            start, end = map(int, header.removeprefix('bytes=').split('-')); end = min(end, len(payload) - 1)
            self.send_response(206); data = payload[start:end+1]
            self.send_header('Content-Range', f'bytes {start + (1 if self.path == "/bad" else 0)}-{end}/{len(payload)}')
        self.send_header('Content-Type', 'application/octet-stream'); self.send_header('Content-Length', str(len(data)))
        self.end_headers(); self.wfile.write(data)
    def log_message(self, *_): pass
server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
download_results = []
for mode in ['range', 'whole', 'bad']:
    run('xcrun', 'simctl', 'terminate', uid, 'com.amorx.ios')
    report = container / 'download-verification.json'; report.unlink(missing_ok=True)
    run('xcrun', 'simctl', 'launch', uid, 'com.amorx.ios', '--layout-screen', 'download',
        '--download-url', f'http://127.0.0.1:{server.server_port}/{mode}')
    deadline = time.monotonic() + 90
    while not report.exists() and time.monotonic() < deadline: time.sleep(.25)
    assert report.exists(), f'Download {mode} did not finish'
    result = json.loads(report.read_text())
    passed = (not result['downloaded']) if mode == 'bad' else (result['indexed'] and result['size'] == len(payload) and result['sha256'] == hashlib.sha256(payload).hexdigest())
    download_results.append({'mode': mode, 'passed': passed, **result})
    (build / 'download-verification.json').write_text(json.dumps({'results': download_results, 'requests': requests}, indent=2))
assert len([r for r in requests if r['path'] == '/range']) >= 2, requests
server.shutdown()
(build / 'download-verification.json').write_text(json.dumps({'results': download_results, 'requests': requests}, indent=2))
assert all(result['passed'] for result in download_results), download_results
assert not [result for result in layout_results if result['violations']], layout_results
print('Layout viewports and native ranged downloads passed')
