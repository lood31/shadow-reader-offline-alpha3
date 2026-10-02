"""Experimental evidence, not calibrated pronunciation probability or score.

One forward/backward Viterbi table; O(T) for each occurrence replacement.
The original CTC skip policy (disjoint allowed-phone sets only) is preserved.
"""
import math
from dataclasses import dataclass
import numpy as np
from .alignment import forced_align
from .scoring import phone_features

VERSION = 'acoustic-evidence-v1'
KIND = 'ACOUSTIC_EVIDENCE_EXPERIMENTAL'
GREEN_MARGIN = math.log(3)
RED_MARGIN = -math.log(10)


@dataclass
class Replacement:
    score: float
    start: int
    end: int  # exclusive
    ambiguous: bool


class ReplacementPaths:
    def __init__(self, logp, targets, blank):
        self.logp, self.targets = logp, targets
        self.time, self.states = len(logp), len(targets)*2+1
        self.emissions = np.empty((self.time, self.states), dtype=np.float64)
        self.emissions[:, ::2] = logp[:, blank, None]
        for i, allowed in enumerate(targets):
            self.emissions[:, 2*i+1] = logp[:, allowed].max(axis=1)
        skip = np.zeros(self.states, dtype=bool)
        for i in range(1, len(targets)):
            skip[2*i+1] = set(targets[i]).isdisjoint(targets[i-1])
        self.prefix = np.full_like(self.emissions, -np.inf)
        self.prefix[0,:2] = self.emissions[0,:2]
        for t in range(1,self.time):
            previous = self.prefix[t-1]
            leap = np.r_[-np.inf,-np.inf,previous[:-2]]
            leap[~skip] = -np.inf
            self.prefix[t] = self.emissions[t] + np.maximum.reduce((previous,np.r_[-np.inf,previous[:-1]],leap))
        # Suffix excludes the current frame emission.
        self.suffix = np.full_like(self.emissions, -np.inf)
        self.suffix[-1,-2:] = 0
        for t in range(self.time-2,-1,-1):
            future = self.emissions[t+1] + self.suffix[t+1]
            leap = np.r_[future[2:],-np.inf,-np.inf]
            leap[~np.r_[skip[2:],False,False]] = -np.inf
            self.suffix[t] = np.maximum.reduce((future,np.r_[future[1:],-np.inf],leap))
        self.score = float(max(self.prefix[-1,-2:]))

    def replace(self, occurrence, allowed):
        c = 2*occurrence+1
        enter_skip = occurrence > 0 and set(allowed).isdisjoint(self.targets[occurrence-1])
        exit_skip = occurrence+1 < len(self.targets) and set(allowed).isdisjoint(self.targets[occurrence+1])
        emissions = self.logp[:,allowed].max(axis=1)
        previous = -np.inf
        start_min = start_max = 0
        best = -np.inf
        best_start_min = best_start_max = best_end_min = best_end_max = 0
        for t in range(self.time):
            choices = [(previous,start_min,start_max)]
            if t:
                choices.append((self.prefix[t-1,c-1],t,t))
                if enter_skip:
                    choices.append((self.prefix[t-1,c-2],t,t))
            elif c == 1:
                choices.append((0.,0,0))
            value = max(q[0] for q in choices)
            previous = value + float(emissions[t])
            if not np.isfinite(value):
                continue
            winners = [q for q in choices if abs(q[0]-value) <= 1e-9]
            start_min = min(q[1] for q in winners)
            start_max = max(q[2] for q in winners)
            if t+1 == self.time:
                tail = 0. if c == self.states-2 else -np.inf
            else:
                tail = self.emissions[t+1,c+1]+self.suffix[t+1,c+1]
                if exit_skip:
                    tail = max(tail,self.emissions[t+1,c+2]+self.suffix[t+1,c+2])
            total = previous+tail
            if not np.isfinite(total):
                continue
            if total > best+1e-9:
                best = total
                best_start_min,best_start_max = start_min,start_max
                best_end_min = best_end_max = t+1
            elif abs(total-best) <= 1e-9:
                best = max(best,total)
                best_start_min = min(best_start_min,start_min)
                best_start_max = max(best_start_max,start_max)
                best_end_min = min(best_end_min,t+1)
                best_end_max = max(best_end_max,t+1)
        return Replacement(float(best),best_start_min,best_end_min,
                           best_start_min != best_start_max or best_end_min != best_end_max)


def classify(confidence, frame_margin, path_margin, reason=None, flap=False):
    if reason:
        return 'UNKNOWN',reason
    if confidence < .70:
        return 'UNKNOWN','LOW_RELIABILITY'
    if frame_margin >= GREEN_MARGIN and path_margin >= GREEN_MARGIN:
        return 'GREEN','TARGET_SUPPORTED'
    if confidence >= .80 and frame_margin <= RED_MARGIN and path_margin <= RED_MARGIN:
        return ('YELLOW','POSSIBLE_ALLOPHONE') if flap else ('RED','COMPETING_EVIDENCE')
    return 'YELLOW','MIXED_EVIDENCE'


def summarize(statuses):
    coverage = sum(s != 'UNKNOWN' for s in statuses)/max(1,len(statuses))
    if 'RED' in statuses:
        return 'RED',coverage
    if not statuses or coverage < .80:
        return 'UNKNOWN',coverage
    return ('GREEN' if all(s == 'GREEN' for s in statuses) else 'YELLOW'),coverage


def assess_phones(logp, targets, blank, phone_ids, ipas, id_to_ipa):
    alignment = forced_align(logp,targets,blank)
    if alignment.gap > .50:
        return alignment,[{'status':'UNKNOWN','reasonCode':'TARGET_INCOMPATIBLE','confidence':0.} for _ in targets]
    paths = ReplacementPaths(logp,targets,blank)
    results = []
    for i,allowed in enumerate(targets):
        frames = alignment.frames[i]
        others = [p for p in phone_ids if p not in allowed]
        candidates = sorted(others,key=lambda p: (-float(np.exp(logp[frames,p]).mean()),p))[:3]
        original = paths.replace(i,allowed)
        alternatives = [(paths.replace(i,(p,)),p) for p in candidates]
        if not alternatives or not np.isfinite(original.score):
            results.append({'status':'UNKNOWN','reasonCode':'ALIGNMENT_FAILED','confidence':0.}); continue
        competitor,p = max(alternatives,key=lambda pair: pair[0].score)
        reason = None
        lo,hi = alignment.bounds[i]
        if original.ambiguous or competitor.ambiguous or competitor.start < lo or competitor.end > hi or not np.isfinite(competitor.score):
            reason = 'ALIGNMENT_AMBIGUOUS'
        union = np.union1d(frames,np.arange(competitor.start,competitor.end))
        if not len(union):
            results.append({'status':'UNKNOWN','reasonCode':'ALIGNMENT_FAILED','confidence':0.}); continue
        feature = phone_features(logp,allowed,union,phone_ids,blank)
        # Keep raw GOP based on original forced frames for parity with existing diagnostics.
        raw = phone_features(logp,allowed,frames,phone_ids,blank)
        path_margin = (paths.score-competitor.score)/len(union)
        flap = ipas[i] in ('t','d') and id_to_ipa.get(p) == 'ɾ'
        state,why = classify(feature['confidence'],feature['targetCompetitorLogRatio'],path_margin,reason,flap)
        results.append({'status':state,'reasonCode':why,'confidence':feature['confidence'],
                        'gopRaw':raw['gopRaw'],'frameMargin':feature['targetCompetitorLogRatio'],
                        'pathMargin':path_margin,'competitorId':p,'supportFrames':len(frames)})
    return alignment,results
