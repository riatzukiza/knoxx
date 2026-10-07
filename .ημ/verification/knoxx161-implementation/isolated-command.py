import sys,subprocess,os
from pathlib import Path
r=Path(__file__).parent;network=sys.argv[1];cwd=sys.argv[2];cmd=sys.argv[3:]
node='/opt/toolchains/node/24.21.0/bin'
a=['bwrap','--die-with-parent','--unshare-pid','--ro-bind','/','/','--tmpfs','/home/err','--tmpfs','/opt','--ro-bind','/home/err/.volta/tools/image','/opt/toolchains','--bind',str(r),str(r),'--tmpfs','/tmp','--tmpfs','/run','--tmpfs','/root','--dir','/run/systemd/resolve','--ro-bind',str(r/'sandbox-resolv.conf'),'/etc/resolv.conf','--proc','/proc','--dev','/dev','--chdir',cwd,'--clearenv']
if network=='offline':a+=['--unshare-net']
e={'HOME':str(r/'jvm-home'),'PATH':str(r/'runtime-bin')+':'+node+':/usr/local/bin:/usr/bin:/bin','TMPDIR':'/tmp','XDG_CACHE_HOME':str(r/'cache'),'XDG_CONFIG_HOME':str(r/'jvm-home/.config'),'GITLIBS':str(r/'gitlibs'),'JAVA_TOOL_OPTIONS':'-Xmx768m -Duser.home='+str(r/'jvm-home'),'CLJ_CONFIG':str(r/'jvm-home/.clojure'),'CLJ_CACHE':str(r/'cache/clojure'),'npm_config_cache':str(r/'cache/npm'),'NODE_OPTIONS':'--dns-result-order=ipv4first --max-old-space-size=2048','KNOXX_CHROMIUM_PATH':str(r/'browser/chrome'),'CI':'true','LANG':'C.UTF-8'}
for k,v in e.items():a+=['--setenv',k,v]
a+=['--',*cmd]
p=subprocess.run(a);sys.exit(p.returncode)
