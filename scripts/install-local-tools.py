#!/usr/bin/env python3
"""Install checksum-verified, project-local kind/kubectl/Helm binaries. No global changes."""
import hashlib
import io
import platform
import re
import tarfile
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

root = Path(__file__).resolve().parents[1] / '.local' / 'tools'
root.mkdir(parents=True, exist_ok=True)
os_name = {'Darwin': 'darwin', 'Linux': 'linux'}[platform.system()]
arch = {'arm64': 'arm64', 'aarch64': 'arm64', 'x86_64': 'amd64'}[platform.machine()]

def fetch(url):
    with urllib.request.urlopen(url, timeout=90) as response:
        return response.read()

def install(item):
    name, url, checksum_url, member = item
    data = fetch(url)
    expected = fetch(checksum_url).decode().split()[0].lower()
    if not re.fullmatch('[a-f0-9]{64}', expected) or hashlib.sha256(data).hexdigest() != expected:
        raise RuntimeError('Checksum verification failed for ' + name)
    if member:
        with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as archive:
            entry = archive.extractfile(member)
            if entry is None:
                raise RuntimeError('Missing binary in archive')
            data = entry.read()
    path = root / name
    path.write_bytes(data)
    path.chmod(0o755)
    return str(path)

kind_url = f'https://kind.sigs.k8s.io/dl/v0.33.0/kind-{os_name}-{arch}'
kubectl_url = f'https://dl.k8s.io/release/v1.35.8/bin/{os_name}/{arch}/kubectl'
helm_url = f'https://get.helm.sh/helm-v3.21.3-{os_name}-{arch}.tar.gz'
items = [('kind', kind_url, kind_url + '.sha256sum', None),
         ('kubectl', kubectl_url, kubectl_url + '.sha256', None),
         ('helm', helm_url, helm_url + '.sha256sum', f'{os_name}-{arch}/helm')]
with ThreadPoolExecutor(max_workers=3) as executor:
    for path in executor.map(install, items):
        print(path)
