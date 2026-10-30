"""Interval-change metrics for one ride folder (ride.csv + laps.csv [+ bike.log]).

    python analyze_ride.py data/r12 [--offset SECONDS]

--offset: tablet clock minus FIT clock in seconds (about 3 on Oct 28, about -607 on Oct 29);
only used to line Grupetto's resistance writes up with the laps.
Targets are estimated from the last 11 s of each lap, rounded to 5 W.
"""
import argparse, os, re, numpy as np, pandas as pd
ap=argparse.ArgumentParser(); ap.add_argument('ride'); ap.add_argument('--offset',type=float,default=3.0); a=ap.parse_args()
r=pd.read_csv(os.path.join(a.ride,'ride.csv'),parse_dates=['timestamp']); p=r.power.fillna(0).values
L=pd.read_csv(os.path.join(a.ride,'laps.csv'),parse_dates=['start_time']); t0=r.timestamp.iloc[0]
laps=[(int((l.start_time-t0).total_seconds()),int(l.total_elapsed_time)) for _,l in L.iterrows()]
W=[]; log=os.path.join(a.ride,'bike.log'); loc=t0.tz_convert('America/New_York'); base=loc.hour*3600+loc.minute*60+loc.second
if os.path.exists(log):
    for l in open(log):
        m=re.match(r'\S+ (\d\d):(\d\d):(\d\d\.\d+) W (\d+)',l)
        if m: W.append((int(m.group(1))*3600+int(m.group(2))*60+float(m.group(3))-base-a.offset,int(m.group(4))))
prev=None; rows=[]
for s,d in laps:
    e=s+d; T=round(np.median(p[e-12:e-1])/5)*5
    if prev is not None and abs(T-prev)>=15:
        up=T>prev; w=p[s:min(s+25,e)]; over=(w.max()-T) if up else (T-w.min())
        ok=np.abs(p[s:e]-T)<=7; settle=next((k for k in range(len(ok)-4) if ok[k:k+5].all()),99)
        reach=next((k for k in range(len(w)) if (w[k]>=T-7 if up else w[k]<=T+7)),99)
        steady=np.mean(np.abs(p[s+12:e]-T)<=7) if e-s>12 else np.nan
        chg=sum(1 for t,_ in W if s+12<=t<e)
        rows.append((reach,settle,over)); print(f"{s:4d}s {prev}->{T} W: reach {reach}s settle {settle}s overshoot {over:+.0f} W steady {100*steady:.0f}% writes-after-12s {chg}")
    prev=T
x=np.array(rows,float); print(f"MEAN reach {x[:,0].mean():.1f}s settle {x[:,1].mean():.1f}s |overshoot| {np.abs(x[:,2]).mean():.1f} W worst settle {x[:,1].max():.0f}s")
