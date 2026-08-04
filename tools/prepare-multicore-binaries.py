#!/usr/bin/env python3
import json, os, re, stat, sys, tarfile, zipfile, hashlib, shutil, urllib.request, tempfile, gzip, lzma
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'app/src/main/assets/cores'
MANIFEST = ROOT / 'app/src/main/assets/core-manifest-multicore.json'
ALL_ABIS = {'arm64-v8a': ['android-arm64','linux-arm64','arm64-v8a'], 'armeabi-v7a': ['android-armv7','linux-armv7','armeabi-v7a','armv7']}
_requested = [x.strip() for x in os.environ.get('TRIVOX_MULTICORE_ABIS', 'arm64-v8a,armeabi-v7a').split(',') if x.strip()]
ABIS = {k: ALL_ABIS[k] for k in _requested if k in ALL_ABIS}
if not ABIS:
    raise SystemExit('No valid TRIVOX_MULTICORE_ABIS selected')
TOKEN = os.environ.get('GITHUB_TOKEN') or os.environ.get('GH_TOKEN')
HDR = {'Accept':'application/vnd.github+json','User-Agent':'trivox-multicore-preparer'}
if TOKEN: HDR['Authorization'] = 'Bearer ' + TOKEN

def api(url):
    req=urllib.request.Request(url, headers=HDR)
    with urllib.request.urlopen(req, timeout=60) as r: return json.loads(r.read().decode())

def releases(repo): return api(f'https://api.github.com/repos/{repo}/releases?per_page=100')

def pick_release(repo):
    rels = releases(repo)
    pre = [r for r in rels if r.get('prerelease')]
    return (pre or rels)[0]

def download(url, dst):
    req=urllib.request.Request(url, headers=HDR)
    with urllib.request.urlopen(req, timeout=180) as r, open(dst,'wb') as f: shutil.copyfileobj(r,f)

def extract_binary(archive, binary, dst):
    tmp=Path(tempfile.mkdtemp())
    try:
        name=archive.name.lower()
        if name.endswith('.zip'):
            with zipfile.ZipFile(archive) as z: z.extractall(tmp)
        elif name.endswith(('.tar.gz','.tgz')):
            with tarfile.open(archive,'r:gz') as t: t.extractall(tmp)
        elif name.endswith('.gz'):
            out=tmp/binary
            with gzip.open(archive,'rb') as i, open(out,'wb') as o: shutil.copyfileobj(i,o)
        elif name.endswith('.xz'):
            out=tmp/binary
            with lzma.open(archive,'rb') as i, open(out,'wb') as o: shutil.copyfileobj(i,o)
        else:
            out=tmp/binary; shutil.copy2(archive,out)
        candidates=[p for p in tmp.rglob('*') if p.is_file() and p.name in (binary, binary+'.exe')]
        if not candidates: candidates=[p for p in tmp.rglob('*') if p.is_file() and binary in p.name.lower()]
        if not candidates: raise RuntimeError('binary not found in '+archive.name)
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(candidates[0], dst)
        dst.chmod(dst.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

def asset_score(asset_name, abi_terms):
    n=asset_name.lower()
    if 'windows' in n or 'darwin' in n or 'ios' in n or 'freebsd' in n: return -1
    s=sum(20 for t in abi_terms if t in n)
    if 'android' in n: s+=50
    if 'linux' in n: s+=15
    if 'compatible' in n: s-=5
    return s

def install(repo, binary, asset_hint=None):
    rel=pick_release(repo)
    installed=[]
    for abi, terms in ABIS.items():
        assets=rel.get('assets') or []
        scored=sorted([(asset_score(a['name'],terms),a) for a in assets], key=lambda x:x[0], reverse=True)
        scored=[x for x in scored if x[0]>0]
        if not scored:
            print(f'no asset for {binary} {abi}', file=sys.stderr); continue
        asset=scored[0][1]
        with tempfile.TemporaryDirectory() as td:
            arc=Path(td)/asset['name']
            print('downloading', repo, rel['tag_name'], abi, asset['name'])
            download(asset['browser_download_url'], arc)
            dst=OUT/abi/binary
            extract_binary(arc,binary,dst)
            sha=hashlib.sha256(dst.read_bytes()).hexdigest()
            installed.append({'abi':abi,'asset':asset['name'],'sha256':sha,'path':str(dst.relative_to(ROOT))})
    return {'repo':repo,'binary':binary,'version':rel.get('tag_name') or rel.get('name'),'prerelease':rel.get('prerelease'), 'html_url':rel.get('html_url'), 'installed':installed}

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    data={'generatedBy':'tools/prepare-multicore-binaries.py','cores':[install('SagerNet/sing-box','sing-box'), install('MetaCubeX/mihomo','mihomo')]}
    MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    MANIFEST.write_text(json.dumps(data, indent=2, ensure_ascii=False)+'\n', encoding='utf-8')
    print(json.dumps(data, indent=2))
if __name__=='__main__': main()
