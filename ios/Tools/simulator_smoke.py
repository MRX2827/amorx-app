#!/usr/bin/env python3
"""Independent startup, compact-layout and native-download verification phases."""
import hashlib, http.server, json, pathlib, plistlib, ssl, subprocess, sys, threading, time

root = pathlib.Path(__file__).resolve().parents[1]
build = root / 'build'; build.mkdir(exist_ok=True)
phase = sys.argv[1] if len(sys.argv) > 1 else 'all'
log = build / f'simulator-{phase}.log'
app = root / 'DerivedData/Build/Products/Release-iphonesimulator/AmorX.app'
bundle = 'com.amorx.ios'

def run(*args, timeout=60):
    with log.open('a') as stream: stream.write('$ ' + ' '.join(map(str, args)) + '\n')
    try:
        result = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout, check=False)
    except subprocess.TimeoutExpired as error:
        output = error.stdout or b''
        if isinstance(output, bytes): output = output.decode(errors='replace')
        with log.open('a') as stream: stream.write(output + '\nTimed out\n')
        raise
    with log.open('a') as stream: stream.write(result.stdout + '\n')
    result.check_returncode(); return result.stdout

def best_effort(*args, timeout=30):
    try: return run(*args, timeout=timeout)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired): return ''

def save(path, data): path.write_text(json.dumps(data, indent=2) + '\n')

def prepare():
    sdk = run('xcrun', '--sdk', 'iphonesimulator', '--show-sdk-version').strip()
    runtimes = json.loads(run('xcrun', 'simctl', 'list', 'runtimes', '--json'))['runtimes']
    version = tuple(map(int, sdk.split('.')[:2]))
    matching = [r for r in runtimes if r.get('isAvailable') and r.get('identifier', '').startswith('com.apple.CoreSimulator.SimRuntime.iOS-') and tuple(map(int, r['version'].split('.')[:2])) == version]
    assert matching, f'No iOS runtime matching Xcode SDK {sdk}'
    runtime = matching[0]
    uid = run('xcrun', 'simctl', 'create', 'AmorX release smoke', 'com.apple.CoreSimulator.SimDeviceType.iPhone-16', runtime['identifier']).strip()
    env = {'sdk': sdk, 'runtime': runtime, 'device': uid}; save(build / 'simulator-environment.json', env)
    # First-boot migration happens while Xcode compiles the app.
    run('xcrun', 'simctl', 'boot', uid)
    return env

state = build / 'simulator-environment.json'
env = json.loads(state.read_text()) if state.exists() else prepare()
uid = env['device']
if phase == 'prepare': sys.exit(0)

def documents(): return pathlib.Path(run('xcrun', 'simctl', 'get_app_container', uid, bundle, 'data').strip()) / 'Documents'

def stop(): best_effort('xcrun', 'simctl', 'terminate', uid, bundle)

def diagnose(label):
    output = best_effort('xcrun', 'simctl', 'spawn', uid, 'log', 'show', '--last', '2m', '--style', 'compact', '--predicate', 'process == "AmorX"', timeout=20)
    (build / f'simulator-{label}-process.log').write_text(output)
    for crash in pathlib.Path.home().joinpath('Library/Logs/DiagnosticReports').glob('AmorX*'):
        try: (build / (crash.stem + '.log')).write_text(crash.read_text())
        except OSError: pass

def launch(*args, recover=False):
    docs = documents(); marker = docs / 'startup-ready.json'
    for attempt in range(3 if recover else 1):
        stop(); marker.unlink(missing_ok=True)
        try: run('xcrun', 'simctl', 'launch', uid, bundle, *args, timeout=60)
        except (subprocess.TimeoutExpired, subprocess.CalledProcessError):
            # simctl can time out while SpringBoard acknowledges a successful launch.
            # Only accept independent evidence from the app's first appearing view.
            if not marker.exists():
                diagnose(f'launch-{attempt}')
                if not recover or attempt == 2: raise
                best_effort('xcrun', 'simctl', 'shutdown', uid, timeout=40)
                run('xcrun', 'simctl', 'boot', uid)
                run('xcrun', 'simctl', 'bootstatus', uid, '-b', timeout=180)
                continue
        deadline = time.monotonic() + 20
        while not marker.exists() and time.monotonic() < deadline: time.sleep(.2)
        if not marker.exists():
            diagnose(f'no-view-{attempt}'); raise AssertionError('App did not show its first view')
        ready = json.loads(marker.read_text())
        subprocess.run(['kill', '-0', str(ready['pid'])], check=True)
        return ready
    raise AssertionError('Simulator launch failed')

def startup():
    run('xcrun', 'simctl', 'bootstatus', uid, '-b', timeout=240)
    run('codesign', '--force', '--deep', '--sign', '-', '--timestamp=none', str(app))
    run('xcrun', 'simctl', 'install', uid, str(app), timeout=180)
    ready = launch(recover=True)
    time.sleep(6); subprocess.run(['kill', '-0', str(ready['pid'])], check=True)
    run('xcrun', 'simctl', 'io', uid, 'screenshot', str(build / 'ios-launch.png'))
    save(build / 'simulator-verification.json', {'bundle_id': bundle, 'sdk': env['sdk'], 'runtime': env['runtime']['identifier'], 'pid': ready['pid'], 'launch_passed': True, 'root_view_appeared': True, 'alive_after_seconds': 6})
    print('Release startup verified', flush=True)

def fixtures():
    global bundle
    bundle = 'com.amorx.ios.verification'
    plist = app / 'Info.plist'; settings = plistlib.loads(plist.read_bytes())
    if settings['CFBundleIdentifier'] != bundle:
        # A separate simulator bundle avoids reusing the production background session.
        # Its transport policy remains the same as the device IPA.
        settings['CFBundleIdentifier'] = bundle
        plist.write_bytes(plistlib.dumps(settings))
        run('codesign', '--force', '--deep', '--sign', '-', '--timestamp=none', str(app))
        best_effort('xcrun', 'simctl', 'terminate', uid, 'com.amorx.ios')
        run('xcrun', 'simctl', 'install', uid, str(app), timeout=180)

def layouts():
    fixtures(); docs = documents(); screenshots = build / 'layout-screenshots'; screenshots.mkdir(exist_ok=True)
    results = []
    for width, height in [(320, 548), (568, 300), (375, 647), (393, 759), (430, 820)]:
        for screen in ['login', 'library', 'player', 'restore', 'profile']:
            report = docs / 'layout-verification.json'; report.unlink(missing_ok=True)
            launch('--layout-screen', screen, '--layout-width', str(width), '--layout-height', str(height))
            deadline = time.monotonic() + 20
            while not report.exists() and time.monotonic() < deadline: time.sleep(.2)
            time.sleep(.5)
            measured = json.loads(report.read_text()) if report.exists() else {'screen': screen, 'width': width, 'height': height, 'violations': ['missing measurements']}
            observed = docs / 'layout-observed.json'
            if observed.exists(): measured['observed'] = json.loads(observed.read_text())
            run('xcrun', 'simctl', 'io', uid, 'screenshot', str(screenshots / f'{screen}-{width}x{height}.png'))
            results.append(measured); save(build / 'layout-verification.json', results)
            print(f'Layout {screen} {width}x{height}: {measured["violations"] or "passed"}', flush=True)
    assert not [r for r in results if r['violations']], results

def downloads():
    fixtures(); docs = documents()
    payload = bytes(range(256)) * (3 * 1024 * 1024 // 256 + 3)
    requests = []
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            header = self.headers.get('Range', ''); requests.append({'path': self.path, 'range': header})
            if self.path == '/whole': self.send_response(200); data = payload
            else:
                start, end = map(int, header.removeprefix('bytes=').split('-')); end = min(end, len(payload)-1)
                self.send_response(206); data = payload[start:end+1]
                self.send_header('Content-Range', f'bytes {start+(1 if self.path == "/bad" else 0)}-{end}/{len(payload)}')
            self.send_header('Content-Type', 'application/octet-stream'); self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
        def log_message(self, *_): pass
    # Trust a short-lived loopback certificate inside this disposable simulator only.
    # No user/device trust store or packaged IPA is modified.
    cert, key = build / 'fixture-cert.pem', build / 'fixture-key.pem'
    authority, authority_key = build / 'fixture-ca.pem', build / 'fixture-ca-key.pem'
    config, csr = build / 'fixture-openssl.cnf', build / 'fixture.csr'
    # An explicit configuration avoids duplicate extensions from runner OpenSSL defaults.
    config.write_text("""[req]
distinguished_name = dn
prompt = no
[dn]
CN = AmorX CI loopback
[ca]
basicConstraints = critical,CA:TRUE
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
[server]
basicConstraints = critical,CA:FALSE
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = IP:127.0.0.1
authorityKeyIdentifier = keyid,issuer
subjectKeyIdentifier = hash
""")
    run('openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-sha256', '-days', '2',
        '-config', str(config), '-extensions', 'ca', '-keyout', str(authority_key), '-out', str(authority))
    run('openssl', 'req', '-new', '-newkey', 'rsa:2048', '-nodes', '-sha256',
        '-config', str(config), '-keyout', str(key), '-out', str(csr))
    run('openssl', 'x509', '-req', '-in', str(csr), '-CA', str(authority), '-CAkey', str(authority_key),
        '-CAcreateserial', '-out', str(cert), '-days', '2', '-sha256', '-extfile', str(config), '-extensions', 'server')
    run('openssl', 'verify', '-CAfile', str(authority), '-purpose', 'sslserver', '-verify_ip', '127.0.0.1', str(cert))
    run('security', 'verify-cert', '-c', str(cert), '-r', str(authority), '-p', 'ssl', '-s', '127.0.0.1')
    run('xcrun', 'simctl', 'keychain', uid, 'add-root-cert', str(authority))
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); context.load_cert_chain(cert, key)
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    threading.Thread(target=server.serve_forever, daemon=True).start(); results = []
    try:
        for mode in ['range', 'whole', 'bad']:
            report = docs / 'download-verification.json'; report.unlink(missing_ok=True)
            launch('--layout-screen', 'download', '--download-url', f'https://127.0.0.1:{server.server_port}/{mode}')
            deadline = time.monotonic() + 90
            while not report.exists() and time.monotonic() < deadline: time.sleep(.25)
            result = json.loads(report.read_text()) if report.exists() else {'downloaded': False, 'error': 'timed out'}
            passed = (not result['downloaded'] and not result.get('indexed') and 'Не удалось скачать аудио (206)' in result.get('failure', '') and any(r['path'] == '/bad' for r in requests)) if mode == 'bad' else (result.get('indexed') and result.get('size') == len(payload) and result.get('sha256') == hashlib.sha256(payload).hexdigest())
            results.append({'mode': mode, 'passed': bool(passed), **result}); save(build/'download-verification.json', {'results': results, 'requests': requests})
            print(f'Download {mode}: {"passed" if passed else result}', flush=True)
    finally: server.shutdown()
    daemon_log = best_effort('xcrun', 'simctl', 'spawn', uid, 'log', 'show', '--last', '2m', '--style', 'compact', '--predicate', 'process == \"nsurlsessiond\" OR process == \"AmorX\"', timeout=20)
    (build / 'simulator-network-process.log').write_text(daemon_log)
    assert len([r for r in requests if r['path'] == '/range']) >= 2, {'requests': requests, 'results': results}
    assert all(r['passed'] for r in results), results

if phase in ['startup', 'all']: startup()
if phase in ['layouts', 'all']: layouts()
if phase in ['downloads', 'all']: downloads()
