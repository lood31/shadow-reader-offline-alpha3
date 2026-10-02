"""Occurrence-aware CTC Viterbi in log space; no uniform-alignment fallback."""
from dataclasses import dataclass
import numpy as np
from .schemas import AssessmentError

@dataclass
class Alignment:
    frames: list[np.ndarray]
    bounds: list[tuple[int, int]]
    gap: float
    state_path: np.ndarray

def forced_align(logp: np.ndarray, targets: list[tuple[int, ...]], blank: int) -> Alignment:
    if logp.ndim != 2 or 0 in logp.shape or not np.isfinite(logp).all() or not targets:
        raise AssessmentError('ALIGNMENT_FAILED', 'Invalid emissions or empty target')
    time, vocab = logp.shape
    if not 0 <= blank < vocab or any(not q or any(x == blank or not 0 <= x < vocab for x in q) for q in targets):
        raise AssessmentError('ALIGNMENT_FAILED', 'Invalid phone inventory')
    states = len(targets) * 2 + 1
    emissions = np.empty((time, states), dtype=np.float64)
    emissions[:, ::2] = logp[:, blank, None]
    for i, phones in enumerate(targets):
        emissions[:, 2*i+1] = np.max(logp[:, phones], axis=1)
    skip = np.zeros(states, dtype=bool)
    for i in range(1, len(targets)):
        skip[2*i+1] = set(targets[i]).isdisjoint(targets[i-1])
    prev = np.full(states, -np.inf)
    prev[:2] = emissions[0, :2]
    back = np.zeros((time, states), dtype=np.int8)
    for t in range(1, time):
        choices = np.stack((prev, np.r_[-np.inf, prev[:-1]], np.r_[-np.inf, -np.inf, prev[:-2]]))
        choices[2, ~skip] = -np.inf
        step = choices.argmax(axis=0)
        back[t] = step
        prev = choices[step, np.arange(states)] + emissions[t]
    state = states - 1 if prev[-1] >= prev[-2] else states - 2
    best = prev[state]
    if not np.isfinite(best):
        raise AssessmentError('ALIGNMENT_FAILED', 'No legal CTC alignment')
    path = np.empty(time, dtype=np.int32)
    for t in range(time-1, -1, -1):
        path[t] = state
        state -= int(back[t, state])
    frames = [np.flatnonzero(path == 2*i+1) for i in range(len(targets))]
    if any(len(f) == 0 for f in frames):
        raise AssessmentError('ALIGNMENT_FAILED', 'Missing phone occurrence')
    # Midpoints provide display estimates, not physical phone boundaries.
    centers = [int(np.median(f)) for f in frames]
    cuts = [max(0, int(frames[0][0]))] + [(a+b+1)//2 for a,b in zip(centers, centers[1:])] + [min(time, int(frames[-1][-1])+1)]
    bounds = [(min(cuts[i], int(f[0])), max(cuts[i+1], int(f[-1])+1)) for i,f in enumerate(frames)]
    gap = float((np.max(logp, axis=1).sum() - best) / time)
    return Alignment(frames, bounds, gap, path)
