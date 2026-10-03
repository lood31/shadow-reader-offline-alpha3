#import "SRNativeEngine.h"
#if __has_include(<onnxruntime/onnxruntime_cxx_api.h>)
#include <onnxruntime/onnxruntime_cxx_api.h>
#else
#include <onnxruntime_cxx_api.h>
#endif
#include <whisper.h>
#include <espeak-ng/speak_lib.h>
#include <mach/mach.h>
#include <atomic>
#include <mutex>
#include <memory>
#include <vector>
#include <string>
#include <thread>
#include <algorithm>
#include <stdexcept>

static void report(NSError **error, const std::exception &e) {
    if (error) *error = [NSError errorWithDomain:@"ShadowReader.Native" code:1 userInfo:@{NSLocalizedDescriptionKey: [NSString stringWithUTF8String:e.what()] ?: @"本地推理失败，未评估。"}];
}
@implementation SRNativeEngine {
    std::unique_ptr<Ort::Env> _env;
    std::unique_ptr<Ort::Session> _model, _vad;
    std::mutex _cancelGuard;
    std::atomic<bool> _cancelled;
    std::string _request;
    Ort::RunOptions *_running;
    whisper_context *_whisper;
    bool _g2pReady;
}
- (instancetype)init {
    if ((self = [super init])) { _cancelled = false; _running = nullptr; _whisper = nullptr; _g2pReady = false; }
    return self;
}
- (void)beginRequest:(NSString *)requestId {
    std::lock_guard<std::mutex> guard(_cancelGuard); _request = requestId.UTF8String; _cancelled = false;
}
- (void)endRequest:(NSString *)requestId {
    std::lock_guard<std::mutex> guard(_cancelGuard);
    if (_request == requestId.UTF8String) { _request.clear(); _running = nullptr; }
}
- (void)cancelRequest:(NSString *)requestId {
    std::lock_guard<std::mutex> guard(_cancelGuard);
    if (_request != requestId.UTF8String) return;
    _cancelled = true;
    if (_running) { try { _running->SetTerminate(); } catch (...) {} }
}
- (void)checkCancelled { if (_cancelled.load()) throw std::runtime_error("任务已取消，未评估。"); }
- (BOOL)loadPronunciationAt:(NSString *)path error:(NSError **)error {
    try {
        [self checkCancelled];
        if (_model && _vad) return YES;
        if (!_env) _env = std::make_unique<Ort::Env>(ORT_LOGGING_LEVEL_WARNING, "ShadowReader");
        if (std::string(OrtGetApiBase()->GetVersionString()) != "1.24.3") throw std::runtime_error("ONNX Runtime 版本不匹配，需要1.24.3。");
        if (!_g2pReady) {
            if (strlen(path.UTF8String) + sizeof("/espeak-ng-data") >= 1024) throw std::runtime_error("音素资源路径过长。");
            _g2pReady = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, path.UTF8String, espeakINITIALIZE_DONT_EXIT) > 0 && espeak_SetVoiceByName("en-us") == EE_OK;
            if (!_g2pReady) throw std::runtime_error("英语音素资源初始化失败。");
        }
        Ort::SessionOptions options;
        options.SetInterOpNumThreads(1); options.SetExecutionMode(ExecutionMode::ORT_SEQUENTIAL);
        options.DisableMemPattern(); options.DisableCpuMemArena(); options.SetIntraOpNumThreads(1);
        _vad = std::make_unique<Ort::Session>(*_env, [[path stringByAppendingPathComponent:@"vad.onnx"] UTF8String], options);
        [self checkCancelled]; options.SetIntraOpNumThreads(4);
        _model = std::make_unique<Ort::Session>(*_env, [[path stringByAppendingPathComponent:@"model.int8.onnx"] UTF8String], options);
        return YES;
    } catch (const std::exception &e) { _model.reset(); _vad.reset(); report(error, e); return NO; }
}
- (NSString *)phonemize:(NSString *)text error:(NSError **)error {
    try {
        [self checkCancelled]; if (!_g2pReady) throw std::runtime_error("音素引擎未初始化。");
        const void *position = text.UTF8String; std::string result;
        while (position) {
            [self checkCancelled];
            const char *phones = espeak_TextToPhonemes(&position, espeakCHARS_UTF8, espeakPHONEMES_IPA | ('_' << 8));
            if (phones) { if (!result.empty()) result += '_'; result += phones; }
        }
        return [NSString stringWithUTF8String:result.c_str()];
    } catch (const std::exception &e) { report(error, e); return nil; }
}
- (void)setRunning:(Ort::RunOptions *)run {
    std::lock_guard<std::mutex> guard(_cancelGuard); _running = run; if (run && _cancelled.load()) run->SetTerminate();
}
- (NSArray<NSNumber *> *)speechRange:(NSData *)audio error:(NSError **)error {
    Ort::RunOptions run;
    try {
        [self checkCancelled]; if (!_vad || audio.length % sizeof(float)) throw std::runtime_error("VAD 输入异常。");
        [self setRunning:&run];
        const float *samples = static_cast<const float *>(audio.bytes); const size_t count = audio.length/sizeof(float);
        std::vector<float> state(256, 0), context(64, 0), input(576, 0);
        bool triggered = false; size_t start = 0, tempEnd = 0;
        std::vector<std::pair<size_t,size_t>> spans;
        auto memory = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
        int64_t rate = 16000;
        for (size_t pos = 0; pos < count; pos += 512) {
            [self checkCancelled]; std::copy(context.begin(), context.end(), input.begin());
            for (size_t i = 0; i < 512; ++i) input[64+i] = pos+i < count ? samples[pos+i] : 0;
            int64_t inputShape[] = {1,576}, stateShape[] = {2,1,128};
            std::vector<Ort::Value> values;
            values.emplace_back(Ort::Value::CreateTensor<float>(memory, input.data(), input.size(), inputShape, 2));
            values.emplace_back(Ort::Value::CreateTensor<float>(memory, state.data(), state.size(), stateShape, 3));
            values.emplace_back(Ort::Value::CreateTensor<int64_t>(memory, &rate, 1, nullptr, 0));
            const char *names[] = {"input","state","sr"}, *outs[] = {"output","stateN"};
            auto result = _vad->Run(run, names, values.data(), 3, outs, 2);
            float probability = result[0].GetTensorData<float>()[0];
            if (result[1].GetTensorTypeAndShapeInfo().GetElementCount() != state.size()) throw std::runtime_error("VAD 状态形状不匹配。");
            std::copy_n(result[1].GetTensorData<float>(), state.size(), state.begin());
            std::copy(input.begin()+512, input.end(), context.begin());
            if (probability >= .5f && tempEnd) tempEnd = 0;
            if (probability >= .5f && !triggered) { triggered = true; start = pos; continue; }
            if (probability < .35f && triggered) {
                if (!tempEnd) tempEnd = pos;
                if (pos-tempEnd < 1600) continue;
                if (tempEnd-start > 4000) spans.emplace_back(start, tempEnd);
                triggered = false; tempEnd = 0;
            }
        }
        if (triggered && count-start > 4000) spans.emplace_back(start, count);
        [self setRunning:nullptr];
        if (spans.empty()) return @[];
        return @[@(spans.front().first > 480 ? spans.front().first-480 : 0), @(std::min(count, spans.back().second+480))];
    } catch (const std::exception &e) { [self setRunning:nullptr]; report(error, e); return nil; }
}
- (NSArray<NSArray<NSNumber *> *> *)logits:(NSData *)audio error:(NSError **)error {
    Ort::RunOptions run;
    try {
        [self checkCancelled]; if (!_model || audio.length % sizeof(float)) throw std::runtime_error("发音模型输入异常。");
        [self setRunning:&run];
        auto memory = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
        int64_t shape[] = {1, static_cast<int64_t>(audio.length/sizeof(float))};
        auto input = Ort::Value::CreateTensor<float>(memory, const_cast<float *>(static_cast<const float *>(audio.bytes)), shape[1], shape, 2);
        const char *name[] = {"input_values"};
        Ort::AllocatorWithDefaultOptions allocator;
        auto outputName = _model->GetOutputNameAllocated(0, allocator); const char *outs[] = {outputName.get()};
        auto output = _model->Run(run, name, &input, 1, outs, 1);
        auto dims = output[0].GetTensorTypeAndShapeInfo().GetShape();
        if (dims.size() != 3 || dims[0] != 1 || dims[1] <= 0 || dims[2] <= 0) throw std::runtime_error("发音模型输出形状不匹配。");
        const float *data = output[0].GetTensorData<float>();
        NSMutableArray *rows = [NSMutableArray arrayWithCapacity:dims[1]];
        for (int64_t t = 0; t < dims[1]; ++t) {
            [self checkCancelled]; NSMutableArray *row = [NSMutableArray arrayWithCapacity:dims[2]];
            for (int64_t p = 0; p < dims[2]; ++p) [row addObject:@(data[t*dims[2]+p])];
            [rows addObject:row];
        }
        [self setRunning:nullptr]; return rows;
    } catch (const std::exception &e) { [self setRunning:nullptr]; report(error, e); return nil; }
}
- (NSString *)transcribe:(NSData *)audio model:(NSString *)model error:(NSError **)error {
    whisper_context *ctx = nullptr;
    try {
        [self checkCancelled];
        auto init = whisper_context_default_params(); init.use_gpu = false;
        ctx = whisper_init_from_file_with_params(model.UTF8String, init);
        if (!ctx) throw std::runtime_error("Whisper 加载失败或内存不足，录音已保留。");
        { std::lock_guard<std::mutex> guard(_cancelGuard); _whisper = ctx; }
        [self checkCancelled]; auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.n_threads = std::min(4u, std::max(1u, std::thread::hardware_concurrency()));
        params.language = "en"; params.translate = false; params.no_context = true; params.initial_prompt = nullptr;
        params.print_progress = false; params.print_realtime = false; params.print_timestamps = false; params.print_special = false;
        params.temperature = 0; params.temperature_inc = 0;
        params.abort_callback = [](void *data) { return static_cast<std::atomic<bool> *>(data)->load(); };
        params.abort_callback_user_data = &_cancelled;
        if (whisper_full(ctx, params, static_cast<const float *>(audio.bytes), static_cast<int>(audio.length/sizeof(float))) != 0) throw std::runtime_error("Whisper 识别失败或被取消，未评估。");
        [self checkCancelled]; std::string text;
        for (int i = 0; i < whisper_full_n_segments(ctx); ++i) if (whisper_full_get_segment_no_speech_prob(ctx, i) < .6f) text += whisper_full_get_segment_text(ctx, i);
        { std::lock_guard<std::mutex> guard(_cancelGuard); _whisper = nullptr; }
        whisper_free(ctx); return [NSString stringWithUTF8String:text.c_str()];
    } catch (const std::exception &e) {
        { std::lock_guard<std::mutex> guard(_cancelGuard); _whisper = nullptr; }
        if (ctx) whisper_free(ctx); report(error, e); return nil;
    }
}
- (void)releasePronunciation { _model.reset(); _vad.reset(); }
+ (uint64_t)physicalFootprint {
    task_vm_info_data_t info = {}; mach_msg_type_number_t count = TASK_VM_INFO_COUNT;
    return task_info(mach_task_self(), TASK_VM_INFO, reinterpret_cast<task_info_t>(&info), &count) == KERN_SUCCESS ? info.phys_footprint : 0;
}
@end
