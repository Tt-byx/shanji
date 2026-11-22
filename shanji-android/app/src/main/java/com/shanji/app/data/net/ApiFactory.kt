package com.shanji.app.data.net

import android.content.Context
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/* Retrofit 单例工厂：baseUrl 由构建时 BuildConfig.SERVER_URL 固定注入，
   登录页不再展示/允许修改服务器地址（私有配置 server-url.properties 不入库） */
object ApiFactory {

    val DEFAULT_URL: String = com.shanji.app.BuildConfig.SERVER_URL

    // encodeDefaults=true：accountId 等默认值字段必须上送，否则服务端收到 null 违反非空约束
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var cachedUrl: String? = null
    private var cachedRetrofit: Retrofit? = null
    private var cachedAuth: AuthApi? = null
    private var cachedSync: SyncApi? = null
    private var cachedAi: AiApi? = null

    fun baseUrl(context: Context): String = DEFAULT_URL

    private fun client(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
            )
            .build()

    private fun retrofit(context: Context): Retrofit {
        val url = baseUrl(context)
        cachedRetrofit?.let { if (url == cachedUrl) return it }
        cachedUrl = url
        cachedAuth = null
        cachedSync = null
        cachedAi = null
        return Retrofit.Builder()
            .baseUrl(url)
            .client(client())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .also { cachedRetrofit = it }
    }

    fun auth(context: Context): AuthApi =
        cachedAuth ?: retrofit(context).create(AuthApi::class.java).also { cachedAuth = it }

    fun sync(context: Context): SyncApi =
        cachedSync ?: retrofit(context).create(SyncApi::class.java).also { cachedSync = it }

    /** AI 中转：读超时放宽到 70s（服务端要等 GLM 返回） */
    fun ai(context: Context): AiApi {
        cachedAi?.let { return it }
        val url = baseUrl(context)
        val aiClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(70, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(url)
            .client(aiClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AiApi::class.java)
            .also { cachedAi = it }
    }
}
