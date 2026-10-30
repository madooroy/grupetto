import os, sys; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from evalv import *
def bandf(f):
    return lambda L,c,m: max(3., f*(m.at(L+1,c)-m.at(L,c)))
V=[('current',V_PT6)]
for f in [.4,.5,.6,.7]: V.append((f'band {f}',dict(V_PT6,band=bandf(f))))
V.append(('persist 1.5s',dict(V_PT6,persist=1.5,immediate=12)))
for f in [.5,.6]: V.append((f'band {f}+persist',dict(V_PT6,band=bandf(f),persist=1.5,immediate=12)))
if __name__=="__main__":
    for n,v in V:
      a=evaluate(v); b=evaluate(v,workout='th')
      print(f"{n:18s} PT settle {a['settle']:5.2f} over {a['over']:5.2f} steady {a['steady']:5.1f} chg {a['changes']:5.1f} | TH settle {b['settle']:5.2f} steady {b['steady']:5.1f} chg {b['changes']:5.1f} hi {b['hi_changes']:5.1f}")
