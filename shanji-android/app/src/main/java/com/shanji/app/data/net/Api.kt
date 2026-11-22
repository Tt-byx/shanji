package com.shanji.app.data.net

import com.shanji.app.data.db.AccountEntity
import com.shanji.app.data.db.BudgetEntity
import com.shanji.app.data.db.TxEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

/* ---- 与后端 ApiResponse 包装对齐的 DTO ---- */

@Serializable
data class ApiResponse<T>(
    val code: Int,
    val message: String = "",
    val data: T? = null
)

@Serializable
data class AuthRequest(
    val username: String,
    val password: String,
    val channel: String = "account",
    val code: String? = null   // 预留：手机号验证码
)

@Serializable
data class AuthData(
    val token: String,
    val expiresAt: Long = 0,
    val username: String = ""
)

@Serializable
data class PushRequest(
    val txs: List<TxEntity> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
    val budgets: List<BudgetEntity> = emptyList()
)

@Serializable
data class PushData(
    val serverTime: Long = 0,
    val accepted: Int = 0
)

@Serializable
data class PullData(
    val serverTime: Long = 0,
    val txs: List<TxEntity> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
    val budgets: List<BudgetEntity> = emptyList()
)

@Serializable
data class HealthData(
    val status: String = "",
    val service: String = "",
    val time: Long = 0
)

/* ---- Retrofit 接口 ---- */

interface AuthApi {
    @POST("api/auth/register")
    suspend fun register(@Body req: AuthRequest): ApiResponse<AuthData>

    @POST("api/auth/login")
    suspend fun login(@Body req: AuthRequest): ApiResponse<AuthData>
}

interface SyncApi {
    @GET("api/sync/pull")
    suspend fun pull(
        @Header("Authorization") bearer: String,
        @Query("since") since: Long
    ): ApiResponse<PullData>

    @POST("api/sync/push")
    suspend fun push(
        @Header("Authorization") bearer: String,
        @Body req: PushRequest
    ): ApiResponse<PushData>

    @GET("api/health")
    suspend fun health(): HealthData
}
