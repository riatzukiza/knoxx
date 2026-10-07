from pathlib import Path
import sys,subprocess,hashlib,base64,json
r=Path(__file__).parent; p=r/'worktree/backend/shadow-cljs.edn';old=p.read_bytes();s=old.decode();a=s.index('  :test\n');b=s.index('  ;; End-to-end suite',a);fragment=s[a:b];start=fragment.index(':ns-regexp ');end=fragment.index('\n',start)
replacement=':ns-regexp "^knoxx[.]backend[.]infra[.]discord-gateway-capabilities-test$",'
fragment=fragment[:start]+replacement+fragment[end:];new=(s[:a]+fragment+s[b:]).encode();(r/'gateway-red-original-shadow.b64').write_bytes(base64.b64encode(old));(r/'gateway-red-diagnostic-shadow.b64').write_bytes(base64.b64encode(new));
try:
 p.write_bytes(new)
 result=subprocess.run(['timeout','600','python3',str(r/'isolated-command.py'),'offline',str(r/'worktree/backend'),'pnpm','exec','node','scripts/run-shadow-tests-ci.mjs'])
finally:
 p.write_bytes(old)
 assert p.read_bytes()==old
 (r/'gateway-red-config-preservation.json').write_text(json.dumps({'original_sha256':hashlib.sha256(old).hexdigest(),'diagnostic_sha256':hashlib.sha256(new).hexdigest(),'restored_exact':True,'external_diagnostic_override_only':True},indent=2)+'\n')
sys.exit(result.returncode)
