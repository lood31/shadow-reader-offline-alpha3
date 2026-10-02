import asyncio
import logging
from contextlib import asynccontextmanager
from threading import Lock
from fastapi import FastAPI, File, Form, UploadFile, Request
from fastapi.responses import JSONResponse
from pydantic import ValidationError
from .audio import MAX_BYTES
from .schemas import Metadata, Assessment, AssessmentError

log=logging.getLogger('pronunciation')

def create_app(load_model: bool = True) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app):
        if load_model:
            try:
                from .engine import GopEngine
                app.state.engine=await asyncio.to_thread(GopEngine)
            except Exception:
                log.exception('Pronunciation model unavailable')
        yield
    app=FastAPI(title='Shadow Reader Pronunciation',version='0.1.0',lifespan=lifespan)
    app.state.engine=None
    busy=Lock()

    @app.exception_handler(AssessmentError)
    async def assessment_error(request,error):
        return JSONResponse(status_code=error.status,content={'error':{'code':error.code,'message':str(error)}})

    @app.get('/health')
    def health():
        engine=app.state.engine
        return {'ready':engine is not None,'modelVersion':getattr(engine,'version',None),
                'calibrated':bool(engine and engine.calibrator.data)}

    @app.post('/api/v1/pronunciation/assess',response_model=Assessment)
    async def assess(request: Request,audio: UploadFile=File(...),metadata: str=Form(...)):
        try:
            if len(metadata)>4000:
                raise AssessmentError('INVALID_METADATA','Metadata too long',400)
            try:
                meta=Metadata.model_validate_json(metadata)
            except ValidationError as error:
                raise AssessmentError('INVALID_METADATA','Invalid metadata or unsupported language',400) from error
            data=await audio.read(MAX_BYTES+1)
            if len(data)>MAX_BYTES:
                raise AssessmentError('AUDIO_TOO_LARGE','WAV exceeds 1 MiB',413)
            from .audio import decode_wav
            decode_wav(data)
            if app.state.engine is None:
                raise AssessmentError('MODEL_NOT_READY','Prepare model and eSpeak dependencies, then restart service',503)
            if not busy.acquire(blocking=False):
                raise AssessmentError('SERVICE_BUSY','A sentence is already being assessed; retry manually',429)
            def run():
                try:
                    return app.state.engine.assess(data,meta)
                finally:
                    busy.release()
            task=asyncio.create_task(asyncio.to_thread(run))
            # Keep the worker/lock alive until forward completes even if the client disconnects.
            try:
                result=await asyncio.shield(task)
            except asyncio.CancelledError:
                task.add_done_callback(lambda t: t.exception() if not t.cancelled() else None)
                raise
            if await request.is_disconnected():
                raise AssessmentError('CLIENT_DISCONNECTED','Result discarded',499)
            log.info('request=%s model=%s timings=%s',meta.requestId,result.modelVersion,result.timingsMs)
            return result
        except AssessmentError:
            raise
        except Exception as error:
            log.error('Assessment failed: %s',type(error).__name__)
            raise AssessmentError('INFERENCE_FAILED','Pronunciation assessment failed',500) from error
        finally:
            await audio.close()
    return app

app=create_app()
