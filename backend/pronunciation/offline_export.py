"""Pinned ONNX export and acoustic parity gate. No Android integration before PASS.

Run from backend: python -m pronunciation.offline_export export|validate
Outputs are reproducible local artifacts, not evidence of pronunciation accuracy.
"""
import argparse
import gc
import hashlib
import json
import time
from pathlib import Path
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'models' / 'offline'
REVISION = 'ae45363bf3413b374fecd9dc8bc1df0e24c3b7f4'


def checksum(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def export():
    import torch
    import onnx
    import onnxruntime as ort
    from onnxruntime.quantization import quantize_dynamic, QuantType
    from transformers import Wav2Vec2ForCTC
    assert ort.__version__ == '1.24.3', ort.__version__
    source = ROOT / 'models' / 'primary'
    manifest = json.loads((source / 'manifest.json').read_text())
    assert manifest['revision'] == REVISION
    for name in ('pytorch_model.bin', 'config.json', 'preprocessor_config.json', 'vocab.json'):
        assert checksum(source / name) == manifest['sha256'][name], name
    OUT.mkdir(parents=True, exist_ok=True)
    fp32 = OUT / 'model.fp32.onnx'
    int8 = OUT / 'model.int8.onnx'
    torch.set_num_threads(4)
    model = Wav2Vec2ForCTC.from_pretrained(source, local_files_only=True,
                                         attn_implementation='eager').eval()

    class Logits(torch.nn.Module):
        def __init__(self, model):
            super().__init__(); self.model = model
        def forward(self, input_values):
            return self.model(input_values, attention_mask=None).logits

    print('Exporting FP32, opset 17, dynamic samples/frames', flush=True)
    wrapper = Logits(model)
    with torch.inference_mode():
        torch.onnx.export(wrapper, (torch.zeros(1, 16000),), str(fp32),
                          input_names=['input_values'], output_names=['logits'],
                          dynamic_axes={'input_values': {1: 'samples'}, 'logits': {1: 'frames'}},
                          opset_version=17, dynamo=False, do_constant_folding=True)
    del wrapper, model
    gc.collect()
    onnx.checker.check_model(str(fp32))
    print('Quantizing constant MatMul/Gemm weights, per-channel QInt8', flush=True)
    quantize_dynamic(str(fp32), str(int8), per_channel=True, weight_type=QuantType.QInt8,
                     op_types_to_quantize=['MatMul', 'Gemm'],
                     extra_options={'MatMulConstBOnly': True})
    onnx.checker.check_model(str(int8))
    files = {p.name: {'bytes': p.stat().st_size, 'sha256': checksum(p)} for p in (fp32, int8)}
    receipt = {'modelId': manifest['modelId'], 'revision': REVISION,
               'sourceSha256': manifest['sha256'], 'opset': 17,
               'onnx': onnx.__version__, 'onnxruntime': ort.__version__,
               'torch': torch.__version__, 'files': files,
               'quantization': 'dynamic QInt8 per-channel constant RHS MatMul/Gemm only',
               'validationStatus': 'PENDING'}
    (OUT / 'export-manifest.json').write_text(json.dumps(receipt, indent=2), encoding='utf-8')
    print(json.dumps(files, indent=2), flush=True)


def cases():
    corpus = ROOT / 'runs' / 'speechocean-pilot'
    manifest = json.loads((corpus / 'manifest.json').read_text())
    scores = json.loads((ROOT / 'data' / 'speechocean762' / 'scores.json').read_text())
    result = []
    for key, spec in manifest['utterances'].items():
        path = corpus / f'{key}.wav'
        assert checksum(path) == spec['audioSha256'], key
        result.append((key, path, scores[key]['text']))
    assert len(result) == 120
    return result


def log_softmax(logits):
    # Same float32 domain as PyTorch; stable with large negative logits.
    shifted = logits - logits.max(axis=-1, keepdims=True)
    return shifted - np.log(np.exp(shifted).sum(axis=-1, keepdims=True))


def validate():
    import onnxruntime as ort
    from .engine import GopEngine, text_words
    from .audio import decode_wav
    from .alignment import forced_align
    from .scoring import phone_features
    assert ort.__version__ == '1.24.3'
    receipt = json.loads((OUT / 'export-manifest.json').read_text())
    for name, spec in receipt['files'].items():
        assert checksum(OUT / name) == spec['sha256'], name
    run = ROOT / 'runs' / 'offline-parity'
    run.mkdir(parents=True, exist_ok=True)
    corpus = cases()
    engine = GopEngine()
    metadata = []
    for number, (key, path, text) in enumerate(corpus):
        cached = run / f'{key}.npz'
        samples = decode_wav(path.read_bytes())
        spans = engine.get_speech_timestamps(engine.torch.from_numpy(samples), engine.vad, sampling_rate=16000)
        if not spans:
            raise RuntimeError(f'{key}: no VAD speech; cannot silently exclude parity case')
        start = max(0, spans[0]['start'] - 1600)
        stop = min(len(samples), spans[-1]['end'] + 1600)
        words = text_words(text)
        targets = engine.phonemize(words)
        values = engine.processor(samples[start:stop], sampling_rate=16000, return_tensors='pt').input_values
        with engine.torch.inference_mode():
            logits = engine.model(values).logits[0].numpy()
        np.savez_compressed(cached, values=values.numpy(), logits=logits)
        metadata.append({'id': key, 'text': text, 'audioSha256': checksum(path), 'offsetSamples': start,
                         'targets': targets, 'phones': [p.ipa for w in words for p in w.phonemes]})
        print(f'PyTorch {number+1}/120 {key}', flush=True)
    vocab = engine.vocab
    phone_ids = engine.phone_ids
    blank = engine.blank
    del engine
    gc.collect()
    (run / 'cases.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    options.inter_op_num_threads = 1
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    reports = {}
    for kind in ('fp32', 'int8'):
        session = ort.InferenceSession(str(OUT / f'model.{kind}.onnx'), sess_options=options,
                                       providers=['CPUExecutionProvider'])
        drifts, signs, offsets, max_errors, shape_errors, failures, raw_logit_failures = [], [], [], [], [], [], []
        posterior_errors, logp_diagnostics = [], []
        for number, spec in enumerate(metadata):
            key = spec['id']
            cached = np.load(run / f'{key}.npz')
            t0 = time.perf_counter()
            actual = session.run(['logits'], {'input_values': cached['values']})[0][0]
            elapsed = (time.perf_counter()-t0)*1000
            expected = cached['logits']
            if actual.shape != expected.shape or not np.isfinite(actual).all():
                shape_errors.append(key); continue
            max_errors.append(float(np.abs(actual-expected).max()))
            reference_logp, actual_logp = log_softmax(expected), log_softmax(actual)
            # CTC consumes normalized log-probabilities. Raw logits are invariant to
            # a common shift; relative error near raw zero is not an acoustic gate.
            # Keep the original diagnostic, and gate the actual CTC input instead.
            if kind == 'fp32':
                if not np.allclose(actual, expected, atol=1e-3, rtol=1e-3):
                    raw_logit_failures.append(key)
                if not np.allclose(actual_logp, reference_logp, atol=1e-3, rtol=1e-3):
                    logp_diagnostics.append(key)
                posterior = np.exp(actual_logp); reference_posterior = np.exp(reference_logp)
                posterior_errors.append(float(np.abs(posterior-reference_posterior).max()))
                if not np.allclose(posterior,reference_posterior,atol=1e-3,rtol=1e-3):
                    failures.append(key)
            targets = [tuple(q) for q in spec['targets']]
            try:
                reference = forced_align(reference_logp, targets, blank)
                aligned = forced_align(actual_logp, targets, blank)
            except Exception as error:
                failures.append(f'{key}: {error}'); continue
            details = []
            for i, target in enumerate(targets):
                a = phone_features(reference_logp, target, reference.frames[i], phone_ids, blank)
                b = phone_features(actual_logp, target, aligned.frames[i], phone_ids, blank)
                drifts.append(abs(a['gopRaw'] - b['gopRaw']))
                offsets.extend(abs(x-y) for x,y in zip(reference.bounds[i], aligned.bounds[i]))
                if abs(a['targetCompetitorLogRatio']) >= .25:
                    signs.append(np.sign(a['targetCompetitorLogRatio']) == np.sign(b['targetCompetitorLogRatio']))
            
            np.savez_compressed(run / f'{key}-{kind}.npz', logits=actual)
            print(f'ONNX {kind} {number+1}/120 {key}', flush=True)
        quant_gates = bool(drifts and signs) and float(np.mean(drifts)) <= .3 and float(np.percentile(drifts,95)) <= 1 and float(np.mean(signs)) >= .95
        fp32_gates = bool(drifts and signs) and float(np.mean(drifts)) <= 1e-4 and float(np.percentile(drifts,95)) <= 1e-3 and float(np.mean(signs)) == 1 and max(offsets,default=999) <= 1
        passed = not shape_errors and not failures and (quant_gates if kind == 'int8' else fp32_gates)
        reports[kind] = {'passed': passed, 'cases': len(metadata), 'phones': len(drifts),
                         'gopMAE': float(np.mean(drifts)), 'gopP95': float(np.percentile(drifts,95)),
                         'strongMarginSignAgreement': float(np.mean(signs)), 'maxBoundaryDriftFrames': max(offsets),
                         'maxLogitsAbsoluteError': max(max_errors), 'shapeErrors': shape_errors,
                         'failures': failures, 'rawLogitsAllcloseDiagnostics': raw_logit_failures,
                         'logProbabilityAllcloseDiagnostics': logp_diagnostics,
                         'maxPosteriorAbsoluteError': max(posterior_errors,default=None),
                         'fp32NumericalGate': 'posterior allclose atol=1e-3 rtol=1e-3 AND GOP MAE<=1e-4 P95<=1e-3 AND strong-margin agreement=100% AND boundary drift<=1 frame; raw logits/logp retained',
                         }
        del session
        gc.collect()
    reports['status'] = 'PASS' if all(reports[k]['passed'] for k in ('fp32','int8')) else 'BLOCKED'
    reports['note'] = 'Engineering parity only; not human pronunciation accuracy or Redmi K80 performance.'
    (run / 'report.json').write_text(json.dumps(reports, indent=2), encoding='utf-8')
    receipt['validationStatus'] = reports['status']
    receipt['validationReportSha256'] = checksum(run / 'report.json')
    (OUT / 'export-manifest.json').write_text(json.dumps(receipt, indent=2), encoding='utf-8')
    print(json.dumps(reports, indent=2), flush=True)
    if reports['status'] != 'PASS':
        raise SystemExit(2)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('export','validate'))
    args = parser.parse_args()
    export() if args.operation == 'export' else validate()
