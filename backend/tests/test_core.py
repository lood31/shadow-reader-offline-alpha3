import io
import json
import wave
import numpy as np
import pytest
from fastapi.testclient import TestClient
from pronunciation.alignment import forced_align
from pronunciation.audio import decode_wav
from pronunciation.api import create_app
from pronunciation.schemas import Phone, AssessmentError
from pronunciation.scoring import phone_features, calibrated_phone_score, Thresholds, Calibrator, aggregate, status
from pronunciation.engine import text_words
from pronunciation.calibrate import fit

def wav(rate=16000, channels=1):
    out=io.BytesIO()
    with wave.open(out,'wb') as f:
        f.setnchannels(channels);f.setsampwidth(2);f.setframerate(rate)
        f.writeframes(np.zeros(rate*channels,dtype='<i2').tobytes())
    return out.getvalue()

def emissions(tokens,vocab=3):
    p=np.full((len(tokens),vocab),.001)
    for t,q in enumerate(tokens): p[t,q]=.998
    return np.log(p/p.sum(axis=1,keepdims=True))

def test_repeat_occurrences_require_blank():
    aligned=forced_align(emissions([0,1,0,1,0]),[(1,),(1,)],0)
    assert [f.tolist() for f in aligned.frames]==[[1],[3]]
    with pytest.raises(AssessmentError): forced_align(emissions([1,1]),[(1,),(1,)],0)

def test_empty_emissions_fail_without_fabricated_alignment():
    with pytest.raises(AssessmentError): forced_align(np.empty((0,3)),[(1,)],0)

def test_blank_padding_does_not_reduce_gop():
    a=emissions([0,1,0]);b=emissions([0,0,0,1,0,0])
    x=phone_features(a,(1,),forced_align(a,[(1,)],0).frames[0],[1,2],0)
    y=phone_features(b,(1,),forced_align(b,[(1,)],0).frames[0],[1,2],0)
    assert x['gopRaw']==pytest.approx(y['gopRaw'])

def test_confident_competing_phone_has_reliable_low_target_score():
    p=emissions([2])
    f=phone_features(p,(1,),np.array([0]),[1,2],0)
    assert f['confidence']>.9 and f['gopRaw'] < -5

def test_scalar_calibration_cannot_override_competing_acoustics():
    class OverconfidentCalibration:
        def score(self,raw):return 98
    f=phone_features(emissions([2]),(1,),np.array([0]),[1,2],0)
    assert calibrated_phone_score(f,OverconfidentCalibration(),Thresholds())==(None,'ACOUSTIC_CONFLICT')
    good=phone_features(emissions([1]),(1,),np.array([0]),[1,2],0)
    assert calibrated_phone_score(good,OverconfidentCalibration(),Thresholds())==(98,None)

def test_allowed_variants_are_not_competing_phones():
    f=phone_features(emissions([2],vocab=4),(1,2),np.array([0]),[1,2,3],0)
    assert f['targetCompetitorLogRatio']>0

def test_long_word_cannot_hide_a_conflicted_phone_in_its_average():
    phones=[Phone(phonemeIndex=i,expected='s',ipa='s',score=98,confidence=.99) for i in range(9)]
    phones.append(Phone(phonemeIndex=9,expected='θ',ipa='θ',confidence=.99,reasonCode='ACOUSTIC_CONFLICT'))
    score,_,coverage=aggregate(phones,Thresholds())
    assert coverage==.9 and score is None

def test_all_blank_not_confident():
    f=phone_features(emissions([0]),(1,),np.array([0]),[1,2],0)
    assert f['confidence']<.01

def test_no_calibrator_means_unknown():
    assert Calibrator(None,'model').score(-.1) is None
    assert status(None,.99,1,Thresholds())=='UNKNOWN'
    assert status(10,.2,1,Thresholds())=='UNKNOWN'

def test_severe_reliable_phone_penalty_and_unknown_coverage():
    cfg=Thresholds()
    def phone(s,c=1): return Phone(phonemeIndex=0,expected='s',ipa='s',score=s,confidence=c)
    score,_,_=aggregate([phone(100),phone(20)],cfg)
    assert score < 60
    assert aggregate([phone(100),phone(None,0)],cfg)[0] is None

def test_utf16_contractions_and_repeated_words():
    words=text_words('😀 I think I can’t.')
    assert [(w.text,w.sourceStart,w.sourceEnd) for w in words]==[('I',3,4),('think',5,10),('I',11,12),('can’t',13,18)]

def test_audio_contract_and_truncation():
    assert decode_wav(wav()).shape==(16000,)
    for data in [wav(44100),wav(channels=2),wav()[:-4],b'invalid']:
        with pytest.raises(AssessmentError):decode_wav(data)

def test_api_without_model_and_invalid_language():
    with TestClient(create_app(load_model=False)) as client:
        assert client.get('/health').json()['ready'] is False
        meta={'requestId':'test','text':'Hello.','language':'en-US'}
        r=client.post('/api/v1/pronunciation/assess',files={'audio':('a.wav',wav(),'audio/wav')},data={'metadata':json.dumps(meta)})
        assert r.status_code==503 and r.json()['error']['code']=='MODEL_NOT_READY'
        meta['language']='en-GB'
        assert client.post('/api/v1/pronunciation/assess',files={'audio':('a.wav',wav())},data={'metadata':json.dumps(meta)}).status_code==400

def test_api_busy_does_not_overlap_acoustic_inference():
    from concurrent.futures import ThreadPoolExecutor
    from threading import Event
    from pronunciation.schemas import Assessment
    entered,release=Event(),Event()
    class SlowEngine:
        def assess(self,data,meta):
            entered.set()
            assert release.wait(5)
            return Assessment(requestId=meta.requestId,text=meta.text,audioSha256='test',modelVersion='test',configVersion='test')
    app=create_app(load_model=False);app.state.engine=SlowEngine()
    with TestClient(app) as client,ThreadPoolExecutor(max_workers=1) as executor:
        def request():
            return client.post('/api/v1/pronunciation/assess',files={'audio':('a.wav',wav())},
                               data={'metadata':json.dumps({'requestId':'test','text':'Hello.'})})
        first=executor.submit(request)
        try:
            assert entered.wait(3)
            busy=request()
            assert busy.status_code==429 and busy.json()['error']['code']=='SERVICE_BUSY'
        finally:
            release.set()
        assert first.result(timeout=5).status_code==200

def test_calibration_rejects_speaker_leakage():
    rows=[{'speaker':'same','split':split,'modelVersion':'m','gopRaw':-.5,'humanScore':50} for split in ['train','dev']]
    with pytest.raises(ValueError,match='Speaker leakage'):fit(rows,'m')

def test_calibration_version_guard(tmp_path):
    p=tmp_path/'calibration.json'
    p.write_text(json.dumps({'modelVersion':'old','scoringVersion':'ctc_align_mean_log_v1','x':[-1,0],'y':[0,100]}))
    with pytest.raises(ValueError,match='does not match'):Calibrator(p,'new')
