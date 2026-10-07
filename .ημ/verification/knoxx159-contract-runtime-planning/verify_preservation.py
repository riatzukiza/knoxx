from pathlib import Path
import subprocess,json,hashlib,base64
r=Path(__file__).resolve().parents[4];w=r/'worktree';b='5670338fcf1db7d51a2690f388e4e4c5c409c48a';v=w/'.ημ/verification/knoxx159-contract-runtime-planning'
def git(*a):return subprocess.check_output(['git','-C',str(w),*a])
rows=[]
for line in git('ls-tree','-rz',b).split(b'\0'):
 if line:
  meta,p=line.split(b'\t');mode,typ,oid=meta.decode().split();rows.append((p.decode(),mode,typ,oid))
assert len(rows)==2597
prefix=['kanban/epics/knowledge-ops-contract-runtime-dod-restructure.md','.ημ/receipts.edn','.ημ/session-mycology/ledger.md'];unchanged=0
for path,mode,typ,oid in rows:
 p=w/path
 if path in prefix:assert p.read_bytes().startswith(git('show',b+':'+path))
 elif mode=='120000':assert p.is_symlink() and str(p.readlink()).encode()==git('show',b+':'+path);unchanged+=1
 elif typ=='blob':assert p.read_bytes()==git('show',b+':'+path);unchanged+=1
 else:raise AssertionError((path,mode,typ))
assert unchanged==2594
fm=lambda z:z.split(b'---',2)[1]
card=prefix[0];assert fm((w/card).read_bytes())==fm(git('show',b+':'+card))
for z in json.loads((v/'accepted-source-archives.json').read_text()):
 d=base64.b64decode((v/z['archive']).read_bytes(),validate=True);assert d==git('show',b+':'+z['path']) and len(d)==z['bytes'] and hashlib.sha256(d).hexdigest()==z['sha256']
for spec in [('knowledge-ops-contract-runtime-dod-restructure.md.b64',11649,'17627b37bb0714488b61d6b0ad5fc7cd71e60b5c219bc52745fe8adbc8482ee5'),('superseded-2026.04.17.10.11.17.md.b64',10381,'77bffa2fda761f89e2dc7ae8cdbaac03395fe84735d2fd7c59c5128ded1d879a')]:
 d=base64.b64decode((v/spec[0]).read_bytes(),validate=True);assert len(d)==spec[1] and hashlib.sha256(d).hexdigest()==spec[2]
manifest=json.loads((v/'capture-manifest.json').read_text());streams=0
for g in manifest:
 for k in ['stdout','stderr']:
  z=g[k]['public_view'];t=(v/z['path']).read_bytes();d=base64.b64decode(t,validate=True);assert base64.b64encode(d)==t and len(d)==z['bytes'] and hashlib.sha256(d).hexdigest()==z['sha256'];streams+=1
fixture=json.loads((r/'.final-proof/base-fixture-before.json').read_text());assert len(fixture)==268;assert sum(z.get('mode')=='symlink' for z in fixture)==1
for z in fixture:
 p=r/'fixture'/z['path']
 if z.get('mode')=='symlink':assert p.is_symlink() and str(p.readlink())==z['target']
 elif z['path']==card:assert p.read_bytes()==(w/card).read_bytes()
 else:assert p.stat().st_size==z['bytes'] and hashlib.sha256(p.read_bytes()).hexdigest()==z['sha256']
assert len([p for p in (r/'fixture').rglob('*') if p.is_file() or p.is_symlink()])==268
for label in ['native-neutral-base-task-read-mount-corrected','native-neutral-refined-task-read']:
 output=json.loads(base64.b64decode((r/'audit'/(label+'.stdout.b64')).read_bytes()));assert output['uuid']=='knoxx-knowledge-ops-contract-runtime-dod-restructure';assert output['frontmatter']['status']=='ready' and output['frontmatter']['priority']=='P2' and output['frontmatter']['points']=='null'
 stderr=base64.b64decode((r/'audit'/(label+'.stderr.b64')).read_bytes());assert b'/tmp/workspace/kanban/openhax.kanban.json' in stderr and str(r).encode() not in stderr
rr=json.loads((v/'owning-receipt-source-provenance.json').read_text());assert len(rr['copied_files'])==15
for z in rr['copied_files']:
 p=r/'runtime/receipt-owner'/z['path'];assert p.stat().st_size==z['bytes'] and hashlib.sha256(p.read_bytes()).hexdigest()==z['sha256']
assert all(p.resolve().is_relative_to(r/'runtime') for p in (r/'runtime').rglob('*') if p.is_symlink())
g=r/'source.git';missing=subprocess.check_output(['git','--git-dir='+str(g),'rev-list','--objects','--all','--missing=print']).decode();assert not any(x.startswith('?') for x in missing.splitlines());assert not(g/'objects/info/alternates').exists() and not(g/'shallow').exists() and not list(g.rglob('*.promisor'));assert all(p.stat().st_nlink==1 for p in(g/'objects').rglob('*') if p.is_file());assert subprocess.run(['git','-C',str(w),'diff','--check',b],capture_output=True).returncode==0
api=json.loads(base64.b64decode((r/'audit/actual-current-owner-api-working-suffix.stdout.b64').read_bytes()));assert api['count']==17 and api['historical-refused']==16 and api['owned'][0]['ok'] and api['owned'][0]['schema']['status']=='declared'
discovery=base64.b64decode((r/'audit/canonical-portable-planning-reflection.stdout.b64').read_bytes()).decode();assert discovery=='Appended to '+str(w/'.ημ/session-mycology/ledger.md')+'\n'
print(json.dumps({'base_entries':2597,'other_inherited_exact':unchanged,'prefixes':3,'frontmatter_exact':True,'fixture_entries':268,'fixture_regular':267,'fixture_symlinks':1,'strict_public_streams':streams,'native_neutral_reads':2,'receipt_owned17_declared_valid':True,'inherited_refused16_preserved':True,'runtime_closure_private':True,'complete_git_store':True,'no_product_gates_or_native_mutation':True}))
