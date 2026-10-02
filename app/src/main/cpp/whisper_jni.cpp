#include <jni.h>
#include <whisper.h>
#include <atomic>
#include <string>
#include <vector>
#include <algorithm>
#include <thread>

struct Recognizer {
    whisper_context *ctx;
    std::atomic<bool> canceled{false};
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_shadowreader_app_asr_WhisperNative_create(JNIEnv *env, jobject, jstring path) {
    const char *value = env->GetStringUTFChars(path, nullptr);
    auto params = whisper_context_default_params();
    params.use_gpu = false;
    auto *ctx = whisper_init_from_file_with_params(value, params);
    env->ReleaseStringUTFChars(path, value);
    if (!ctx) return 0;
    return reinterpret_cast<jlong>(new Recognizer{ctx});
}

extern "C" JNIEXPORT void JNICALL
Java_com_shadowreader_app_asr_WhisperNative_cancel(JNIEnv *, jobject, jlong ptr) {
    reinterpret_cast<Recognizer *>(ptr)->canceled.store(true);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_shadowreader_app_asr_WhisperNative_transcribe(JNIEnv *env, jobject, jlong ptr, jfloatArray audio) {
    auto *r = reinterpret_cast<Recognizer *>(ptr);
    // Each context is used for exactly one attempt. Cancellation is never reset here.
    const int n = env->GetArrayLength(audio);
    std::vector<float> samples(n);
    env->GetFloatArrayRegion(audio, 0, n, samples.data());
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = std::min(4u, std::max(1u, std::thread::hardware_concurrency()));
    params.language = "en";
    params.translate = false;
    params.no_context = true;
    params.initial_prompt = nullptr;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.temperature = 0.0f;
    params.temperature_inc = 0.0f;
    params.abort_callback = [](void *data) { return static_cast<Recognizer *>(data)->canceled.load(); };
    params.abort_callback_user_data = r;
    if (r->canceled.load() || whisper_full(r->ctx, params, samples.data(), n) != 0 || r->canceled.load()) return nullptr;
    std::string text;
    for (int i = 0; i < whisper_full_n_segments(r->ctx); ++i) {
        if (whisper_full_get_segment_no_speech_prob(r->ctx, i) < 0.6f)
            text += whisper_full_get_segment_text(r->ctx, i);
    }
    auto bytes = env->NewByteArray(static_cast<jsize>(text.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    auto stringClass = env->FindClass("java/lang/String");
    auto constructor = env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
    auto encoding = env->NewStringUTF("UTF-8");
    return static_cast<jstring>(env->NewObject(stringClass, constructor, bytes, encoding));
}

extern "C" JNIEXPORT void JNICALL
Java_com_shadowreader_app_asr_WhisperNative_free(JNIEnv *, jobject, jlong ptr) {
    auto *r = reinterpret_cast<Recognizer *>(ptr);
    whisper_free(r->ctx);
    delete r;
}
