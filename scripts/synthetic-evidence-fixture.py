"""Build deterministic, non-personal acoustic fixtures for the Kotlin parity tests."""
import json,sys
from pathlib import Path
import numpy as np
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'backend'))
from pronunciation.evidence import assess_phones
from pronunciation.offline_export import log_softmax

vocab=json.loads((ROOT/'backend/models/primary/vocab.json').read_text(encoding='utf-8'))
ipa_to_id={}
for token,index in vocab.items():
    if not token.startswith('<') and token not in ('|',' '):ipa_to_id.setdefault(token,index)
phone_ids=list(ipa_to_id.values());cases=[]
for seed,(word,ipas) in enumerate([('cat',['k','æ','t']),('book',['b','ʊ','k']),
                                   ('hello',['h','ɛ','l','oʊ']),('letter',['l','ɛ','t','ə','ɹ'])],2026):
    rng=np.random.default_rng(seed);targets=[[ipa_to_id[ipa]] for ipa in ipas]
    logits=rng.normal(0,.35,(len(ipas)*3+1,len(vocab))).astype(np.float32)
    for i,target in enumerate(targets):
        logits[i*3+1:i*3+3,target[0]]=5
        logits[i*3+3,vocab['<pad>']]=5
    logp=log_softmax(logits)
    _,expected=assess_phones(logp,[tuple(x) for x in targets],vocab['<pad>'],phone_ids,ipas,
                             {value:key for key,value in vocab.items()})
    cases.append({'id':'synthetic-'+word,'logp':logp.tolist(),'targets':targets,'ipas':ipas,'expected':expected})
fixture={'blank':vocab['<pad>'],'phoneIds':phone_ids,'vocab':vocab,'cases':cases}
(ROOT/'app/src/test/resources/pronunciation/evidence.json').write_text(
    json.dumps(fixture,separators=(',',':')),encoding='utf-8')
