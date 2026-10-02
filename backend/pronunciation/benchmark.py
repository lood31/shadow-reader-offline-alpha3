"""Reproducible speaker-disjoint SpeechOcean762 pilot; never train on test labels.

Requires the original OpenSLR 101 archive and its human scores.json/train-utt2spk.
Only whole words with compatible ARPAbet/IPA inventories contribute phone labels.
"""
import argparse
import hashlib
import json
import re
import tarfile
from pathlib import Path
import numpy as np
from .engine import GopEngine
from .schemas import Metadata
from .scoring import Calibrator, SCORING_VERSION, aggregate, status
from .calibrate import fit

ARPA = {
    'AA': {'ɑː','ɑ'}, 'AE': {'æ'}, 'AH': {'ʌ','ə','ɐ'}, 'AO': {'ɔː','ɔ','ɑː'},
    'AW': {'aʊ'}, 'AY': {'aɪ'}, 'EH': {'ɛ'}, 'ER': {'ɚ','ɜː'}, 'EY': {'eɪ'},
    'IH': {'ɪ','i'}, 'IY': {'iː','i'}, 'OW': {'oʊ'}, 'OY': {'ɔɪ'}, 'UH': {'ʊ'},
    'UW': {'uː','u'}, 'B': {'b'}, 'CH': {'tʃ'}, 'D': {'d'}, 'DH': {'ð'}, 'F': {'f'},
    'G': {'ɡ'}, 'HH': {'h'}, 'JH': {'dʒ'}, 'K': {'k'}, 'L': {'l'}, 'M': {'m'},
    'N': {'n'}, 'NG': {'ŋ'}, 'P': {'p'}, 'R': {'ɹ'}, 'S': {'s'}, 'SH': {'ʃ'},
    'T': {'t'}, 'TH': {'θ'}, 'V': {'v'}, 'W': {'w'}, 'Y': {'j'}, 'Z': {'z'}, 'ZH': {'ʒ'},
}

def compatible(phones, gold):
    return len(phones)==len(gold['phones']) and all(
        p.expected in ARPA.get(re.sub(r'\d','',g),set())
        for p,g in zip(phones,gold['phones']))

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive',type=Path,required=True)
    parser.add_argument('--data',type=Path,default=Path('data/speechocean762'))
    parser.add_argument('--output',type=Path,default=Path('runs/speechocean-pilot'))
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    scores=json.loads((args.data/'scores.json').read_text(encoding='utf-8'))
    groups={}
    for line in (args.data/'train-utt2spk').read_text().splitlines():
        utt,speaker=line.split();groups.setdefault(speaker,[]).append(utt)
    selected={}
    for i,speaker in enumerate(sorted(groups)[:12]):
        split='train' if i<6 else 'dev' if i<9 else 'test'
        # Include low-rated and high-rated utterances within each held-out speaker.
        ordered=sorted(groups[speaker],key=lambda u:(scores[u]['accuracy'],u))
        indices=np.linspace(0,len(ordered)-1,10,dtype=int)
        for n in indices:selected[ordered[n]]={'speaker':speaker,'split':split}
    manifest={'source':'https://www.openslr.org/101/','license':'CC-BY-4.0',
              'attribution':'SpeechOcean and Junbo Zhang et al., SpeechOcean762 (Interspeech 2021)',
              'selection':'12 speakers from official TRAIN partition; 6/3/3 disjoint train/dev/test, 10 utterances each',
              'utterances':selected}
    # Extract exact selected WAV members, never archive paths or executable files.
    wanted={f'speechocean762/WAVE/SPEAKER{v["speaker"]}/{u}.WAV':u for u,v in selected.items()}
    extracted={}
    with tarfile.open(args.archive,'r:gz') as archive:
        for member in archive:
            if member.name in wanted and member.isfile():
                utt=wanted[member.name];path=args.output/f'{utt}.wav'
                source=archive.extractfile(member)
                if source is None or member.size>1048576:raise ValueError('Invalid corpus audio')
                path.write_bytes(source.read());extracted[utt]=path
                if len(extracted)==len(selected):break
    if len(extracted)!=len(selected):raise ValueError('Selected corpus files missing')
    engine=GopEngine();labels=[];results={};skipped=0;latencies=[]
    for n,(utt,split) in enumerate(selected.items()):
        data=extracted[utt].read_bytes()
        result=engine.assess(data,Metadata(requestId=utt,text=scores[utt]['text']))
        results[utt]=result;latencies.append(result.timingsMs.get('total',0))
        (args.output/f'{utt}.json').write_text(result.model_dump_json(indent=2),encoding='utf-8')
        manifest['utterances'][utt]['audioSha256']=hashlib.sha256(data).hexdigest()
        gold=scores[utt]['words']
        if len(result.words)!=len(gold):skipped+=len(gold);continue
        for word,reference in zip(result.words,gold):
            if word.text.upper()!=reference['text'].upper() or not compatible(word.phonemes,reference):
                skipped+=1;continue
            for phone,human in zip(word.phonemes,reference['phones-accuracy']):
                # Calibration sees all compatible human labels, including uncertain/bad phones.
                # Reliability gates apply only to displayed predictions, avoiding selection bias.
                if phone.gopRaw is not None:
                    labels.append({**split,'utterance':utt,'wordIndex':word.wordIndex,
                        'phonemeIndex':phone.phonemeIndex,'gopRaw':phone.gopRaw,'humanScore':human*50,
                        'modelVersion':engine.version,'scoringVersion':SCORING_VERSION})
        print(f'{n+1}/{len(selected)} {utt} {result.reasonCode}',flush=True)
    (args.output/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    (args.output/'labels.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in labels),encoding='utf-8')
    calibration=fit(labels,engine.version)
    calibration_path=args.output/'calibration.json'
    calibration_path.write_text(json.dumps(calibration,indent=2),encoding='utf-8')
    calibrator=Calibrator(calibration_path,engine.version)
    predictions=[];skipped_test=0;total_test=0;total_incorrect=0;total_correct=0
    for utt,split in selected.items():
        if split['split']!='test':continue
        result=results[utt];gold=scores[utt]['words'];total_test+=len(gold)
        total_incorrect+=sum(w['accuracy']*10<70 for w in gold)
        total_correct+=sum(w['accuracy']*10>=80 for w in gold)
        if len(result.words)!=len(gold):skipped_test+=len(gold);continue
        for word,reference in zip(result.words,gold):
            if word.text.upper()!=reference['text'].upper():skipped_test+=1;continue
            for phone in word.phonemes:
                if phone.gopRaw is not None and phone.reasonCode not in ('ACOUSTIC_CONFLICT','LOW_RELIABILITY'):
                    phone.score=calibrator.score(phone.gopRaw)
            word.score,word.confidence,word.coverage=aggregate(word.phonemes,engine.cfg)
            word.status=status(word.score,word.confidence,word.coverage,engine.cfg)
            if word.status=='UNKNOWN':skipped_test+=1;continue
            predictions.append({'utterance':utt,'word':word.text,'human':reference['accuracy']*10,
                                'score':word.score,'status':word.status})
    incorrect=[p for p in predictions if p['human']<70]
    correct=[p for p in predictions if p['human']>=80]
    report={'calibration':calibration['labels'],'matchedPhoneLabels':len(labels),'inventorySkippedWords':skipped,
            'testWords':total_test,'testScoredWords':len(predictions),'testUnknownWords':skipped_test,
            'testCoverage':len(predictions)/max(1,total_test),
            'testWordMAE':float(np.mean([abs(p['human']-p['score']) for p in predictions])) if predictions else None,
            'incorrectWords':len(incorrect),'incorrectGreenRate':sum(p['status']=='GREEN' for p in incorrect)/len(incorrect) if incorrect else None,
            'allIncorrectWords':total_incorrect,'incorrectCoverage':len(incorrect)/max(1,total_incorrect),
            'allCorrectWords':total_correct,'correctCoverage':len(correct)/max(1,total_correct),
            'correctWords':len(correct),'correctRedRate':sum(p['status']=='RED' for p in correct)/len(correct) if correct else None,
            'p50Ms':float(np.median(latencies)),'p95Ms':float(np.percentile(latencies,95)),
            'releaseStatus':'PILOT_ONLY: thresholds unvalidated; no phone/device/noise/minimal-pair acceptance yet',
            'wordPredictions':predictions}
    (args.output/'report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='wordPredictions'},indent=2))

if __name__=='__main__':main()
