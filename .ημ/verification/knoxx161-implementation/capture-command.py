import sys,os,subprocess,json,hashlib,base64,datetime,fcntl
from pathlib import Path
root=Path(__file__).parent;label=sys.argv[1];command=sys.argv[2:];started=datetime.datetime.now(datetime.timezone.utc).isoformat();result=subprocess.run(command,capture_output=True,env=os.environ.copy());entry={"label":label,"command":command,"cwd":os.getcwd(),"at":started,"exit":result.returncode}
for name,data in [("stdout",result.stdout),("stderr",result.stderr)]:
 file=root/(label+"."+name+".b64");file.write_bytes(base64.b64encode(data));entry[name]={"capture":file.name,"bytes":len(data),"sha256":hashlib.sha256(data).hexdigest()}
with (root/"capture-manifest.lock").open("w") as lock:
 fcntl.flock(lock,fcntl.LOCK_EX);m=root/"capture-manifest.json";records=json.loads(m.read_text());records.append(entry);m.write_text(json.dumps(records,indent=2)+"\n")
print(json.dumps(entry));sys.exit(result.returncode)
