import os
from run import DATA
import numpy as np, json
from extract import load
def seed(r): return np.exp(1.491016+.108267*r-.000584*r*r)
rows=[]
for d in ['r5','r6','r7','r8','r9','r10','r11']:
    S,C=load(os.path.join(DATA,d,'bike.log'))
    for i in range(3,len(S)):
        w=S[i-3:i+1]
        if (w[:,1]==w[-1,1]).all() and S[i,2]>=60 and S[i,3]>20 and np.ptp(w[:,2])<=3:
            rows.append((int(S[i,1]),S[i,3]/((S[i,2]/90)**1.7)))
rows=np.array(rows)
A={}
for lv in sorted(set(rows[:,0].astype(int))):
    v=rows[rows[:,0]==lv,1]
    if len(v)>=8: A[lv]=float(np.median(v))
for lv,v in A.items(): print(lv, round(v,1), 'seed',round(seed(lv),1), 'ratio',round(v/seed(lv),3), (rows[:,0]==lv).sum())
json.dump({int(k):v for k,v in A.items()},open('plantA.json','w'))
