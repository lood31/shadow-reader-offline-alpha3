import hashlib
import json
import os
import re
import time
from pathlib import Path
import numpy as np
from .alignment import forced_align
from .audio import RATE, decode_wav, audio_quality
from .schemas import Assessment, AssessmentError, Metadata, Phone, Word
from .scoring import Calibrator, load_thresholds, phone_features, calibrated_phone_score, aggregate, status

ROOT = Path(__file__).resolve().parents[1]
MODEL_ID = 'facebook/wav2vec2-lv-60-espeak-cv-ft'

def utf16_offset(text: str, offset: int) -> int:
    return len(text[:offset].encode('utf-16-le'))//2

def text_words(text: str) -> list[Word]:
    result=[]
    # Preserve offsets from original text, including supplementary Unicode.
    for match in re.finditer(r"[A-Za-z0-9]+(?:['’‘][A-Za-z]+)*", text):
        result.append(Word(wordIndex=len(result), text=match.group(),
                           sourceStart=utf16_offset(text,match.start()), sourceEnd=utf16_offset(text,match.end())))
    if not result or len(result)>120:
        raise AssessmentError('UNSUPPORTED_TEXT', 'Use an English sentence of at most 120 words')
    return result

class GopEngine:
    def __init__(self):
        import torch
        from transformers import Wav2Vec2ForCTC, Wav2Vec2FeatureExtractor
        from phonemizer.backend import EspeakBackend
        from phonemizer.backend.espeak.wrapper import EspeakWrapper
        from silero_vad import load_silero_vad, get_speech_timestamps
        bundle=ROOT/'models'/'primary'
        manifest_path=bundle/'manifest.json'
        if not manifest_path.exists():
            raise RuntimeError('Model missing: run python -m pronunciation.prepare')
        manifest=json.loads(manifest_path.read_text(encoding='utf-8'))
        self.version=manifest['revision']
        if manifest['modelId'] != MODEL_ID or len(self.version)!=40:
            raise RuntimeError('Invalid pinned model manifest')
        for name in ('pytorch_model.bin','config.json','preprocessor_config.json','vocab.json'):
            with (bundle/name).open('rb') as source:
                actual=hashlib.file_digest(source,'sha256').hexdigest()
            if actual != manifest['sha256'].get(name):
                raise RuntimeError(f'Model checksum mismatch: {name}')
        self.torch=torch
        torch.set_num_threads(int(os.environ.get('PRONUNCIATION_THREADS','4')))
        self.processor=Wav2Vec2FeatureExtractor.from_pretrained(bundle, local_files_only=True)
        self.model=Wav2Vec2ForCTC.from_pretrained(bundle, local_files_only=True).eval().to('cpu')
        self.blank=self.model.config.pad_token_id
        self.vocab=json.loads((bundle/'vocab.json').read_text(encoding='utf-8'))
        self.phone_ids=[i for token,i in self.vocab.items() if not token.startswith('<') and token not in ('|',' ')]
        if os.name == 'nt' and not os.environ.get('PHONEMIZER_ESPEAK_LIBRARY'):
            import espeakng_loader
            os.environ['ESPEAK_DATA_PATH']=espeakng_loader.get_data_path()
            EspeakWrapper.set_library(espeakng_loader.get_library_path())
        self.g2p=EspeakBackend('en-us', with_stress=False, preserve_punctuation=False)
        self.vad=load_silero_vad()
        self.get_speech_timestamps=get_speech_timestamps
        self.cfg=load_thresholds(ROOT/'config'/'thresholds.json')
        calibration=os.environ.get('PRONUNCIATION_CALIBRATION')
        self.calibrator=Calibrator(Path(calibration) if calibration else None,self.version)
        # Warm-up is recorded separately from sentence latency.
        with torch.inference_mode():
            self.model(torch.zeros(1,RATE))

    def phonemize(self, words: list[Word]) -> list[tuple[int,...]]:
        from phonemizer.separator import Separator
        texts=[w.text.lower().replace('’',"'").replace('‘',"'") for w in words]
        strings=self.g2p.phonemize(texts, Separator(phone=' ',word='|'), strip=True)
        targets=[]
        weak={'a':[['ɐ','ə','eɪ']], 'the':[['ð'],['ə','iː','ɪ']],
              'to':[['t'],['ə','uː']], 'of':[['ə','ʌ'],['v']]}
        for word,text,phones in zip(words,texts,strings):
            tokens=phones.replace('|',' ').split()
            if not tokens or any(p not in self.vocab for p in tokens):
                raise AssessmentError('UNSUPPORTED_PHONEME', 'G2P phone not in acoustic inventory')
            variants=weak.get(text)
            for index,token in enumerate(tokens):
                candidates=[token]
                if variants and len(variants)==len(tokens):
                    candidates+=variants[index]
                ids=tuple(dict.fromkeys(self.vocab[p] for p in candidates if p in self.vocab))
                targets.append(ids)
                word.phonemes.append(Phone(phonemeIndex=index,expected=token,ipa=token,confidence=0))
        if len(targets)>400:
            raise AssessmentError('UNSUPPORTED_TEXT','Target has more than 400 phonemes')
        return targets

    def assess(self, data: bytes, metadata: Metadata) -> Assessment:
        start=time.perf_counter()
        result=Assessment(requestId=metadata.requestId,text=metadata.text,audioSha256=hashlib.sha256(data).hexdigest(),
                          modelVersion=self.version,configVersion=self.cfg.version,
                          calibrationStatus='PILOT' if self.calibrator.data else 'UNCALIBRATED')
        result.calibrationVersion=self.calibrator.data['version'] if self.calibrator.data else None
        mark=start
        def timing(name):
            nonlocal mark
            now=time.perf_counter();result.timingsMs[name]=round((now-mark)*1000,2);mark=now
        samples=decode_wav(data)
        result.words=text_words(metadata.text)
        valid,reason=audio_quality(samples)
        if not valid:
            result.reasonCode=reason
            result.timingsMs['total']=round((time.perf_counter()-start)*1000,2)
            return result
        spans=self.get_speech_timestamps(self.torch.from_numpy(samples),self.vad,sampling_rate=RATE)
        if not spans:
            result.reasonCode='NO_SPEECH'
            timing('preprocessing');result.timingsMs['total']=round((time.perf_counter()-start)*1000,2)
            return result
        offset=max(0,spans[0]['start']-RATE//10)
        end=min(len(samples),spans[-1]['end']+RATE//10)
        segment=samples[offset:end]
        timing('preprocessing')
        targets=self.phonemize(result.words)
        timing('g2p')
        values=self.processor(segment,sampling_rate=RATE,return_tensors='pt').input_values
        with self.torch.inference_mode():
            logp=self.model(values).logits.log_softmax(-1)[0].cpu().numpy()
        timing('inference')
        try:
            alignment=forced_align(logp,targets,self.blank)
        except AssessmentError as error:
            result.reasonCode=error.code
            timing('alignment');result.timingsMs['total']=round((time.perf_counter()-start)*1000,2)
            return result
        timing('alignment')
        if alignment.gap>self.cfg.max_alignment_gap:
            result.reasonCode='TARGET_INCOMPATIBLE'
            result.warnings.append('Forced alignment is not evidence that the expected sentence was spoken.')
        else:
            all_phones=[p for w in result.words for p in w.phonemes]
            for i,phone in enumerate(all_phones):
                feature=phone_features(logp,targets[i],alignment.frames[i],self.phone_ids,self.blank)
                phone.gopRaw=feature['gopRaw']
                phone.supportFrames=feature['supportFrames']
                phone.logMeanPosterior=feature['logMeanPosterior']
                phone.nonBlankMass=feature['nonBlankMass']
                phone.targetCompetitorLogRatio=feature['targetCompetitorLogRatio']
                phone.confidence=feature['confidence']
                phone.score,phone.reasonCode=calibrated_phone_score(feature,self.calibrator,self.cfg)
                a,b=alignment.bounds[i]
                phone.startMs=round((offset+a/len(logp)*len(segment))/RATE*1000)
                phone.endMs=round((offset+b/len(logp)*len(segment))/RATE*1000)
                phone.status=status(phone.score,phone.confidence,1,self.cfg)
            for word in result.words:
                word.score,word.confidence,word.coverage=aggregate(word.phonemes,self.cfg)
                word.status=status(word.score,word.confidence,word.coverage,self.cfg)
                word.startMs=word.phonemes[0].startMs;word.endMs=word.phonemes[-1].endMs
            usable=[w for w in result.words if w.status!='UNKNOWN']
            result.coverage=len(usable)/len(result.words)
            if usable and result.coverage>=self.cfg.min_coverage:
                result.accuracyScore=round(sum(w.score for w in usable)/len(usable),2)
            result.assessmentStatus='ASSESSED' if len(usable)==len(result.words) else 'PARTIAL' if usable else 'UNEVALUATED'
            if not self.calibrator.data:
                result.reasonCode='CALIBRATION_REQUIRED'
                result.warnings.append('Raw GOP and CTC timestamps are diagnostic; scores and colors await human calibration.')
        timing('scoring')
        result.timingsMs['total']=round((time.perf_counter()-start)*1000,2)
        return result
