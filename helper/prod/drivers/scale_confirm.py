# Aggregates TRUE confirmed (spent=true) across bt4_0..bt4_{N-1} for given wallets
#
# NOTE: session driver — W=/tmp/opencode and JDK paths must be adapted
# to the host (see header vars). Kept for reproducibility of the
# 2026-08-27 TPS campaign (see docs/performance.md).
import subprocess, time, sys
wallet_file, nnodes = sys.argv[1], int(sys.argv[2])
addrs=[l.strip() for l in open(wallet_file) if l.strip()]
def spent_count(dbi):
    tot=0
    for i in range(0,len(addrs),500):
        ch=addrs[i:i+500]
        inl=",".join("'"+a+"'" for a in ch)
        out=subprocess.run(["docker","exec","test-bigtangle-postgres","psql","-U","root","-d",f"bt4_{dbi}","-tAc",
          f"select coalesce(sum(spent::int),0) from outputs where toaddress in ({inl})"],
          capture_output=True,text=True).stdout.strip()
        try: tot+=int(out)
        except: pass
    return tot
t0=time.time(); prev=0; wprev=0; wt=t0; peak=0
print("time  confirmed  tps10  tps30", flush=True)
while True:
    c=sum(spent_count(i) for i in range(nnodes))
    now=time.time()
    d10=(c-prev)/max(1,now-t0)
    print(f"{now-t0:6.0f}s {c:7d} {d10:7.1f}", flush=True)
    prev=c
    if now-wt>=30:
        d30=(c-wprev)/(now-wt); peak=max(peak,d30)
        wprev=c; wt=now
    time.sleep(10)
