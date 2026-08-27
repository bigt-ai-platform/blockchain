import re,sys
#
# NOTE: session driver — W=/tmp/opencode and JDK paths must be adapted
# to the host (see header vars). Kept for reproducibility of the
# 2026-08-27 TPS campaign (see docs/performance.md).
f=sys.argv[1]; label=sys.argv[2]; submitted=sys.argv[3]
rows=[]
for l in open(f, errors="replace"):
    m=re.match(r"^\s*(\d+)s\s+(\d+)",l)
    if m: rows.append((int(m.group(1)),int(m.group(2))))
if not rows: print(label,"no data"); sys.exit()
nz=next((i for i,r in enumerate(rows) if r[1]>0),None)
best=0
for i in range(1,len(rows)):
    dt=rows[i][0]-rows[i-1][0]
    if dt>0:
        s=(rows[i][1]-rows[i-1][1])/dt
        if s>best: best=s
sus=0
for i in range(len(rows)):
    w=[r for r in rows if rows[i][0]<=r[0]<=rows[i][0]+60]
    if len(w)>3:
        s=(w[-1][1]-w[0][1])/max(1,w[-1][0]-w[0][0])
        sus=max(sus,s)
print("%s confirmed=%d submitted=%s peak15s=%.0f best60s=%.0f first_t=%ss" % (label,rows[-1][1],submitted,best,sus,rows[nz][0] if nz is not None else "-"))
