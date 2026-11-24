package com.shanji.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 语音记账：调用系统 SpeechRecognizer（免费、端侧）识别出一句话，
 * 再走已有的 /api/ai/parse 解析成账单字段——语音只负责「把话变成字」。
 *
 * 回调都发生在主线程（SpeechRecognizer 必须在主线程创建/调用）。
 * 回调字段是 var，调用方每次重组时重新赋值，避免闭包捕获过期状态。
 */
class VoiceInput(private val context: Context) {

    /** 识别出的完整句子 */
    var onResult: (String) -> Unit = {}

    /** 录音状态变化（true=正在听） */
    var onStateChange: (Boolean) -> Unit = {}

    /** 识别失败（已翻成人话） */
    var onFail: (String) -> Unit = {}

    var listening = false
        private set

    private var recognizer: SpeechRecognizer? = null

    /** 系统有没有装语音识别服务（部分模拟器没有） */
    fun available(): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    fun start() {
        if (listening) return
        if (!available()) { onFail("这台设备没有语音识别服务"); return }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        r.setRecognitionListener(listener)
        listening = true
        onStateChange(true)
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "说一句要记的账")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
        )
    }

    fun stop() {
        if (!listening) return
        listening = false
        onStateChange(false)
        runCatching { recognizer?.cancel() }
    }

    fun destroy() {
        listening = false
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            listening = false
            onStateChange(false)
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) onResult(text) else onFail("没听清，再说一次")
        }

        override fun onError(errorCode: Int) {
            listening = false
            onStateChange(false)
            onFail(msgOf(errorCode))
        }

        override fun onEndOfSpeech() {
            // 正常顺序是 onEndOfSpeech →（网络延迟）→ onResults。
            // 这里不能把 listening 置回 false：UI 会提前显示「没在听」，用户以为没听上而重按，
            // 此时 startListening 还没收到结果 → ERROR_RECOGNIZER_BUSY。
            // 保持「处理中」，由 onResults / onError 统一收尾。
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {}
    }

    private fun msgOf(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "录音出错了，检查一下麦克风"
        SpeechRecognizer.ERROR_CLIENT -> "语音服务忙，再试一次"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限"
        SpeechRecognizer.ERROR_NETWORK -> "网络不通，稍后再试"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络太慢，稍后再试"
        SpeechRecognizer.ERROR_NO_MATCH -> "没听清，再说一次"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙，再试一次"
        SpeechRecognizer.ERROR_SERVER -> "语音服务不可用"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听到声音，再说一次"
        else -> "没识别出来，再试一次"
    }
}
