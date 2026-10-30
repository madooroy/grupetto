import numpy as np
def preferred(w,prof):
    hi = w>=330
    if prof=='spinner': return 97 if hi else 90
    if prof=='steady': return 90
    if prof=='grinder': return 82 if hi else 90
    if prof=='mixed': return 96 if hi else (88 if w>=280 else 90)
def cadence(segs,prof,seed=0,tau=3.0,antic=5,antic_rpm=101):
    rng=np.random.default_rng(seed); T=int(sum(d for _,d in segs)); out=np.zeros(T)
    bounds=np.cumsum([0]+[d for _,d in segs]); c=preferred(segs[0][0],prof)
    for t in range(T):
        i=int(np.searchsorted(bounds,t,side='right')-1); w=segs[i][0]; goal=preferred(w,prof)
        # spinners anticipate a hard effort: spin up in the last seconds before it
        if prof in('spinner','mixed') and i+1<len(segs) and segs[i+1][0]>=330 and bounds[i+1]-t<=antic: goal=antic_rpm
        c+= (goal-c)*(1-np.exp(-1/tau)); out[t]=c+rng.normal(0,.6)
    return out
