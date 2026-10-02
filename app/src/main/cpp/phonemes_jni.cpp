#include <jni.h>
#include <mutex>
#include <string>
#include <espeak-ng/speak_lib.h>
static std::mutex guard;
static bool initialized = false;
extern "C" JNIEXPORT jboolean JNICALL
Java_com_shadowreader_app_pronunciation_EspeakNative_initialize(JNIEnv* env, jobject, jstring path) {
    std::lock_guard<std::mutex> lock(guard);
    if (initialized) return JNI_TRUE;
    const char* value = env->GetStringUTFChars(path, nullptr);
    int rate = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, value, 0);
    env->ReleaseStringUTFChars(path, value);
    initialized = rate > 0 && espeak_SetVoiceByName("en-us") == EE_OK;
    return initialized ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_shadowreader_app_pronunciation_EspeakNative_phonemize(JNIEnv* env, jobject, jstring text) {
    std::lock_guard<std::mutex> lock(guard);
    if (!initialized) return nullptr;
    const char* value = env->GetStringUTFChars(text, nullptr);
    const void* position = value;
    std::string result;
    while (position != nullptr) {
        const char* phones = espeak_TextToPhonemes(&position, espeakCHARS_UTF8, espeakPHONEMES_IPA | ('_' << 8));
        if (phones) { if (!result.empty()) result += '_'; result += phones; }
    }
    env->ReleaseStringUTFChars(text, value);
    return env->NewStringUTF(result.c_str());
}
