import json
from dataclasses import dataclass, asdict
from pathlib import Path
import numpy as np

SCORING_VERSION = 'ctc_align_mean_log_v1'

@dataclass(frozen=True)
class Thresholds:
    version: str = 'experimental-v1'
    green: float = 80
    yellow: float = 55
    min_confidence: float = .70
    min_coverage: float = .80
    high_confidence: float = .80
    severe_penalty: float = .25
    max_alignment_gap: float = .50

def load_thresholds(path: Path) -> Thresholds:
    cfg = Thresholds(**json.loads(path.read_text(encoding='utf-8')))
    if not (0 <= cfg.yellow < cfg.green <= 100 and 0 <= cfg.min_confidence <= cfg.high_confidence <= 1 and 0 < cfg.min_coverage <= 1 and 0 <= cfg.severe_penalty <= 1 and cfg.max_alignment_gap > 0):
        raise ValueError('Invalid threshold configuration')
    return cfg

class Calibrator:
    def __init__(self, path: Path | None, model_version: str):
        self.data = None
        if path and not path.is_file():
            raise ValueError('Calibration file not found')
        if path and path.exists():
            data = json.loads(path.read_text(encoding='utf-8'))
            if data['modelVersion'] != model_version or data['scoringVersion'] != SCORING_VERSION:
                raise ValueError('Calibration does not match model/scorer')
            x,y = np.asarray(data['x']),np.asarray(data['y'])
            if len(x) < 2 or len(x) != len(y) or not np.isfinite(x).all() or not np.isfinite(y).all() or np.any(np.diff(x) <= 0) or np.any(np.diff(y) < 0) or np.any((y<0)|(y>100)):
                raise ValueError('Invalid monotone calibration')
            self.data = data

    def score(self, raw: float) -> float | None:
        return None if self.data is None else round(float(np.interp(raw, self.data['x'], self.data['y'])), 2)

def status(score: float | None, confidence: float, coverage: float, cfg: Thresholds) -> str:
    if score is None or confidence < cfg.min_confidence or coverage < cfg.min_coverage:
        return 'UNKNOWN'
    return 'GREEN' if score >= cfg.green else 'YELLOW' if score >= cfg.yellow else 'RED'

def phone_features(logp: np.ndarray, targets: tuple[int,...], frames: np.ndarray, phone_ids: list[int], blank: int) -> dict:
    expected = np.clip(np.exp(logp[frames][:, targets]).sum(axis=1), 1e-12, 1)
    raw = float(np.log(expected).mean())
    # Reliability uses the unrestricted acoustic distribution, not P(expected).
    probs = np.exp(logp[frames][:, phone_ids])
    mass = probs.sum(axis=1)
    conditional = probs / np.maximum(mass[:,None], 1e-12)
    entropy = -(conditional * np.log(np.maximum(conditional, 1e-12))).sum(axis=1) / np.log(max(2, len(phone_ids)))
    confidence = float(np.clip(np.mean(mass * (1-entropy)), 0, 1))
    competitors = [p for p in phone_ids if p not in targets]
    other = np.exp(logp[frames][:, competitors]).max(axis=1) if competitors else np.zeros(len(frames))
    margin = float(np.mean(np.log(expected) - np.log(np.maximum(other, 1e-12))))
    return {'gopRaw': raw, 'confidence': confidence, 'supportFrames': len(frames),
            'logMeanPosterior': float(np.log(expected.mean())), 'nonBlankMass': float(mass.mean()),
            'targetCompetitorLogRatio': margin}

def calibrated_phone_score(feature: dict, calibrator: Calibrator, cfg: Thresholds):
    # A positive scalar calibration cannot override acoustics favouring another phone.
    # Zero is the equal-likelihood boundary, not a threshold fitted to user samples.
    if feature['confidence'] < cfg.min_confidence:
        return None, 'LOW_RELIABILITY'
    if feature['targetCompetitorLogRatio'] < 0:
        return None, 'ACOUSTIC_CONFLICT'
    score = calibrator.score(feature['gopRaw'])
    return score, None if score is not None else 'CALIBRATION_REQUIRED'

def aggregate(phones: list, cfg: Thresholds) -> tuple[float | None,float,float]:
    if not phones:
        return None,0,0
    usable = [p for p in phones if p.score is not None and p.confidence >= cfg.min_confidence]
    coverage = len(usable)/len(phones)
    confidence = float(np.quantile([p.confidence for p in phones], .10))
    if any(getattr(p, 'reasonCode', None) == 'ACOUSTIC_CONFLICT' for p in phones):
        return None,confidence,coverage
    if not usable or coverage < cfg.min_coverage:
        return None,confidence,coverage
    mean = sum(p.score*p.confidence for p in usable)/sum(p.confidence for p in usable)
    severe = max((max(0,cfg.yellow-p.score) for p in usable if p.confidence>=cfg.high_confidence), default=0)
    return round(max(0,min(100,mean-cfg.severe_penalty*severe)),2),confidence,coverage
