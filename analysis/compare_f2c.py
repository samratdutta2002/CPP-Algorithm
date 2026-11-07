"""Comparison with Fields2Cover: rotation on/off pairs, single configurations, virtual best of nine
(with polygon-clustered bootstrap intervals), coverage-matched variants and runtime ratios.
Writes results/numbers.json.  Usage: python analysis/compare_f2c.py"""
import json, os
import numpy as np, pandas as pd
from common import RES, load_both
A, H = load_both()
rng=np.random.default_rng(11)
key=['polygon','robot_width']
def tolcmp(a,b,lower=True,tol=None,rel=0.02):
    diff=a-b
    tie=(diff.abs()<=tol) if tol is not None else ((diff.abs()/b.abs().clip(lower=1e-9))<=rel)
    win=((diff<0) if lower else (diff>0))&~tie
    return win.mean()*100,tie.mean()*100,100-win.mean()*100-tie.mean()*100
def ci_median_ratio(J,a,b,npoly=None):
    polys=J.index.get_level_values(0).unique()
    idx={p:np.where(J.index.get_level_values(0)==p)[0] for p in polys}
    r=(J[a]/J[b]).values
    bs=[]
    for _ in range(1000):
        pick=rng.choice(polys,len(polys),replace=True)
        bs.append(np.median(r[np.concatenate([idx[p] for p in pick])]))
    return float(np.median(r)),float(np.percentile(bs,2.5)),float(np.percentile(bs,97.5))
out={}
tabs={}
for name,d0 in [('aligned',A),('headland',H)]:
    d=d0[~d0.split].copy()
    o=d[d.route_planner=='ortools']
    cols=['java_path_length','java_turns','java_total_turn_deg','java_coverage_pct','java_time_s']
    on=o[o.objective!='Fixed0'].groupby(key)[cols].first().add_prefix('on_')
    off=o[o.objective=='Fixed0'].groupby(key)[cols].first().add_prefix('off_')
    # ---- rotation
    j=off.join(on,how='inner'); R={'n':len(j)}
    for m,lab,lower,tol in [('java_path_length','path',True,None),('java_turns','turns',True,None),('java_total_turn_deg','turndeg',True,None),('java_coverage_pct','cov',False,0.5)]:
        w,t,l=tolcmp(j['on_'+m],j['off_'+m],lower,tol)
        R[lab]=dict(off=j['off_'+m].mean(),on=j['on_'+m].mean(),med_off=j['off_'+m].median(),med_on=j['on_'+m].median(),meddiff=(j['on_'+m]-j['off_'+m]).median(),win=w,tie=t,loss=l)
    rel=(j.off_java_path_length-j.on_java_path_length)/j.off_java_path_length*100
    R['rel_mean']=rel.mean(); R['rel_med']=rel.median(); R['rel_q']=[rel.quantile(.25),rel.quantile(.75)]
    out[name+'_rot']=R
    # ---- single configs
    rows=[]
    for cid,g in d.groupby('config_id'):
        rot=('NSwath' in cid) or ('SwathLength' in cid)
        jj=g.set_index(key).join(on if rot else off,how='inner')
        pj=jj['on_java_path_length' if rot else 'off_java_path_length']; tj=jj['on_java_turns' if rot else 'off_java_turns']
        cj=jj['on_java_coverage_pct' if rot else 'off_java_coverage_pct']
        w,t,l=tolcmp(pj,jj.f2c_path_length,True)
        w2,t2,l2=tolcmp(tj,jj.f2c_turns,True)
        rows.append(dict(cid=cid,n=len(jj),f_path=jj.f2c_path_length.mean(),p_path=pj.mean(),ratio=float((pj/jj.f2c_path_length).median()),win=w,tie=t,loss=l,f_turns=jj.f2c_turns.mean(),p_turns=tj.mean(),tratio=float((tj/jj.f2c_turns).median()),twin=w2,tloss=l2,f_cov=jj.f2c_coverage_pct.mean(),p_cov=cj.mean()))
    tabs[name+'_single']=sorted(rows,key=lambda r:r['f_path'])
    # ---- virtual best
    g=d.groupby(key)
    F=pd.DataFrame({'f_path':g.f2c_path_length.min(),'f_turns':g.f2c_turns.min(),'f_td':g.f2c_total_turn_deg.min(),'nconf':g.size(),'rt':g.f2c_runtime_s.sum()})
    J=on.join(F,how='inner')
    V={'n':len(J)}
    for lab,a,b in [('path','on_java_path_length','f_path'),('turns','on_java_turns','f_turns'),('td','on_java_total_turn_deg','f_td')]:
        w,t,l=tolcmp(J[a],J[b],True)
        V[lab]=dict(win=w,tie=t,loss=l,ratio=ci_median_ratio(J,a,b))
    full=J[J.nconf==9]; V['n9']=len(full); V['runtime_ratio']=float((full.rt/full.on_java_time_s).median())
    V['runtime_med_prop']=float(J.on_java_time_s.median()); V['runtime_med_sum']=float(full.rt.median())
    # coverage matched
    dd=d.merge(on[['on_java_coverage_pct']],left_on=key,right_index=True)
    dd=dd[dd.f2c_coverage_pct>=dd.on_java_coverage_pct-0.5]
    g2=dd.groupby(key)
    Fc=pd.DataFrame({'f_path':g2.f2c_path_length.min(),'f_turns':g2.f2c_turns.min(),'f_td':g2.f2c_total_turn_deg.min()})
    Jc=on.join(Fc,how='inner'); C={'n':len(Jc)}
    for lab,a,b in [('path','on_java_path_length','f_path'),('turns','on_java_turns','f_turns'),('td','on_java_total_turn_deg','f_td')]:
        w,t,l=tolcmp(Jc[a],Jc[b],True)
        C[lab]=dict(win=w,tie=t,loss=l,ratio=ci_median_ratio(Jc,a,b))
    out[name+'_vb']=V; out[name+'_vbc']=C
json.dump(dict(out=out,tabs=tabs),open(os.path.join(RES,'numbers.json'),'w'),indent=1,default=float)
for k in ['aligned_vb','aligned_vbc','headland_vb','headland_vbc']:
    v=out[k]; print(k,v['n'],{x:(v[x]['ratio'],round(v[x]['win'],1),round(v[x]['loss'],1)) for x in ['path','turns','td']},v.get('runtime_ratio'),v.get('n9'))
