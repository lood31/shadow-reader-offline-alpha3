package com.shadowreader.app.pronunciation

import com.shadowreader.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RemoteGopPronunciationEngine(baseUrl: String) : PronunciationEngine {
    private val endpoint = validateEndpoint(baseUrl).trimEnd('/') + "/api/v1/pronunciation/assess"
    private val client = OkHttpClient.Builder().callTimeout(30,TimeUnit.SECONDS).retryOnConnectionFailure(false).build()

    companion object {
        fun validateEndpoint(value: String): String {
            val url = value.trim().toHttpUrlOrNull() ?: error("请输入电脑服务的完整 HTTP(S) 地址。")
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "服务地址不能包含密码、参数或片段。" }
            require(url.isHttps || BuildConfig.DEBUG) { "正式版评分服务需要 HTTPS。" }
            return url.toString().trimEnd('/')
        }
    }

    override suspend fun assess(request: PronunciationRequest): PronunciationAssessment {
        val wav = withContext(Dispatchers.Default) { PcmWav.encode(request.samples) }
        val hash = PcmWav.fingerprint(wav)
        val metadata = JSONObject().put("requestId",request.requestId).put("text",request.expectedText)
            .put("language",request.language).toString()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("metadata",metadata)
            .addFormDataPart("audio","recording.wav",wav.toRequestBody("audio/wav".toMediaType())).build()
        val call = client.newCall(Request.Builder().url(endpoint).post(body).build())
        val raw = suspendCancellableCoroutine<String> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call,error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("无法连接评分电脑，录音仍保留。",error))
                }
                override fun onResponse(call: Call,response: Response) {
                    response.use {
                        try {
                            val value = it.body?.string() ?: error("评分服务返回空响应。")
                            if (!it.isSuccessful) {
                                val code = runCatching { JSONObject(value).getJSONObject("error").getString("code") }.getOrDefault("HTTP_${it.code}")
                                error("评分未完成（$code），录音仍保留。")
                            }
                            if (continuation.isActive) continuation.resume(value)
                        } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                    }
                }
            })
        }
        return withContext(Dispatchers.Default) {
            PronunciationJson.decode(raw).also {
                require(it.requestId == request.requestId && it.text == request.expectedText && it.audioSha256 == hash) { "评分响应与本次录音不对应。" }
            }
        }
    }
}
