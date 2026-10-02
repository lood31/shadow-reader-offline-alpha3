"""Fit monotone phone calibration from HUMAN-labelled JSONL, split by speaker."""
import argparse
import json
from pathlib import Path
import numpy as np
from sklearn.isotonic import IsotonicRegression
from .scoring import SCORING_VERSION

def fit(rows: list[dict], model_version: str) -> dict:
    # Required schema: speaker, split=train|dev|test, gopRaw, humanScore (0..100), modelVersion.
    speakers={s:{r['speaker'] for r in rows if r['split']==s} for s in ('train','dev','test')}
    if any(speakers[a]&speakers[b] for a,b in [('train','dev'),('train','test'),('dev','test')]):
        raise ValueError('Speaker leakage between train/dev/test')
    if any(r['modelVersion']!=model_version or r.get('scoringVersion',SCORING_VERSION)!=SCORING_VERSION for r in rows):
        raise ValueError('Mixed model/scorer versions')
    if any(r['split'] not in speakers or not np.isfinite(r['gopRaw']) or not 0<=r['humanScore']<=100 for r in rows):
        raise ValueError('Invalid labels or split')
    train=[r for r in rows if r['split']=='train']
    dev=[r for r in rows if r['split']=='dev']
    if len(train)<50 or len(dev)<20 or len(speakers['train'])<3 or not speakers['dev']:
        raise ValueError('At least 50 train/20 dev phone labels and 3 train speakers required')
    if len({r['humanScore'] for r in train})<3:
        raise ValueError('Need multiple human pronunciation quality levels')
    model=IsotonicRegression(y_min=0,y_max=100,out_of_bounds='clip').fit([r['gopRaw'] for r in train],[r['humanScore'] for r in train])
    error=np.abs(model.predict([r['gopRaw'] for r in dev])-[r['humanScore'] for r in dev])
    return {'version':'human-pilot-v1','modelVersion':model_version,'scoringVersion':SCORING_VERSION,
            'x':model.X_thresholds_.tolist(),'y':model.y_thresholds_.tolist(),
            'labels':{'train':len(train),'dev':len(dev),'trainSpeakers':len(speakers['train']),'devMAE':float(error.mean())},
            'note':'Pilot calibration only; word false positives, coverage and device acceptance remain required.'}

def main():
    p=argparse.ArgumentParser();p.add_argument('labels',type=Path);p.add_argument('--model-version',required=True);p.add_argument('--output',type=Path,required=True)
    args=p.parse_args()
    rows=[json.loads(line) for line in args.labels.read_text(encoding='utf-8').splitlines() if line.strip()]
    data=fit(rows,args.model_version)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(data,indent=2),encoding='utf-8')
    print(json.dumps(data['labels'],indent=2))

if __name__=='__main__':main()
