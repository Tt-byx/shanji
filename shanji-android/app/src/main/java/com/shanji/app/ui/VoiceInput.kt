package com.shanji.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 语音记账：系统 SpeechRecognizer 转文字，结果回填记账输入框。
 * 未授予录音权限时静默返回（调用方负责先申请权限）。
 */
object VoiceInput {

    private var recognizer: SpeechRecognizer? = null

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 开始听写；onResult 收到转写文本（可能为空串） */
    fun start(context: Context, onResult: (String) -> Unit) {
        if (!hasPermission(context)) return
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val txt = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: ""
                onResult(txt)
                stop()
            }
            override fun onError(error: Int) {
                onResult("")
                stop()
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        r.startListening(intent)
        // 兜底：8s 没结果自动停，避免录音图标卡住
        Handler(Looper.getMainLooper()).postDelayed({ stop() }, 8_000)
    }

    fun stop() {
        recognizer?.destroy()
        recognizer = null
    }
}
