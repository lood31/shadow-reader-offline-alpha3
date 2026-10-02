import math
import numpy as np
import pytest
from pronunciation.alignment import forced_align
from pronunciation.evidence import ReplacementPaths, classify, summarize, assess_phones


def reference_score(logp, targets, blank):
    alignment = forced_align(logp,targets,blank)
    total = 0.
    for t,state in enumerate(alignment.state_path):
        total += logp[t,blank] if state%2 == 0 else max(logp[t,p] for p in targets[(state-1)//2])
    return total,alignment


def test_replacement_matches_full_ctc_with_repeats_and_overlapping_sets():
    random = np.random.default_rng(391)
    checked = 0
    for n in range(1,7):
        for _ in range(30):
            targets = [tuple(sorted(random.choice(np.arange(1,5),size=random.integers(1,3),replace=False))) for _ in range(n)]
            logits = random.normal(size=(n*3+3,5))
            logp = logits-np.log(np.exp(logits).sum(axis=1,keepdims=True))
            paths = ReplacementPaths(logp,targets,0)
            for i in range(n):
                for candidate in range(1,5):
                    replaced = targets.copy(); replaced[i] = (candidate,)
                    reference,alignment = reference_score(logp,replaced,0)
                    actual = paths.replace(i,(candidate,))
                    assert actual.score == pytest.approx(reference,abs=1e-9)
                    assert not actual.ambiguous
                    assert (actual.start,actual.end) == (int(alignment.frames[i][0]),int(alignment.frames[i][-1])+1)
                    checked += 1
    assert checked == 2520


def test_equal_paths_report_different_support_intervals_as_ambiguous():
    paths = ReplacementPaths(np.log(np.full((4,3),1/3)),[(1,)],0)
    assert paths.replace(0,(2,)).ambiguous


def test_green_red_yellow_unknown_and_allophone_boundaries():
    assert classify(.7,math.log(3),math.log(3))[0] == 'GREEN'
    assert classify(.8,-math.log(10),-math.log(10))[0] == 'RED'
    assert classify(.99,-7,-7)[0] == 'RED'  # concentrated wrong phone remains reliable
    assert classify(.69,8,8)[0] == 'UNKNOWN'
    assert classify(.9,0,0)[0] == 'YELLOW'
    assert classify(.79,-7,-7)[0] == 'YELLOW'
    assert classify(.9,-7,-7,flap=True) == ('YELLOW','POSSIBLE_ALLOPHONE')
    assert classify(.99,8,8,reason='ALIGNMENT_AMBIGUOUS')[0] == 'UNKNOWN'


def test_word_red_is_not_averaged_away_and_coverage_is_count_based():
    assert summarize(['GREEN']*10+['RED'])[0] == 'RED'
    assert summarize(['RED','UNKNOWN','UNKNOWN'])[0] == 'RED'
    assert summarize(['GREEN']*4+['UNKNOWN']) == ('YELLOW',.8)
    assert summarize(['GREEN']*3+['UNKNOWN']*2) == ('UNKNOWN',.6)
    assert summarize(['GREEN','GREEN']) == ('GREEN',1.)
    assert summarize([]) == ('UNKNOWN',0.)


def test_full_pipeline_strong_target_and_single_strong_substitution():
    targets = [(p,) for p in [1,3,1,3,1,3]]
    probabilities = np.full((18,4),.004)
    for t in range(18):
        probabilities[t,targets[t//3][0] if t%3 == 1 else 0] = .988
    logp = np.log(probabilities)
    _,normal = assess_phones(logp,targets,0,[1,2,3],['θ','i']*3,{1:'θ',2:'s',3:'i'})
    assert normal[0]['status'] == 'GREEN'
    probabilities[1] = [.004,.004,.988,.004]
    _,changed = assess_phones(np.log(probabilities),targets,0,[1,2,3],['θ','i']*3,{1:'θ',2:'s',3:'i'})
    assert changed[0]['status'] == 'RED'
    assert changed[0]['competitorId'] == 2


def test_full_pipeline_flap_exception_and_low_mass_remain_conservative():
    targets = [(p,) for p in [1,3,1,3,1,3]]
    probabilities = np.full((18,4),.004)
    for t in range(18):
        probabilities[t,targets[t//3][0] if t%3 == 1 else 0] = .988
    probabilities[1] = [.004,.004,.988,.004]
    _,result = assess_phones(np.log(probabilities),targets,0,[1,2,3],['t','i']*3,{1:'t',2:'ɾ',3:'i'})
    assert result[0]['status'] == 'YELLOW'
    assert result[0]['reasonCode'] == 'POSSIBLE_ALLOPHONE'
    probabilities[1] = [.997,.001,.001,.001]
    _,result = assess_phones(np.log(probabilities),targets,0,[1,2,3],['θ','i']*3,{1:'θ',2:'s',3:'i'})
    assert result[0]['status'] == 'UNKNOWN'
