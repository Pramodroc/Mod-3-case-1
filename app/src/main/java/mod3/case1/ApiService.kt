package mod3.case1

import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface ApiService {

    @Multipart
    @POST("upload")
    suspend fun uploadDocuments(
        @Part files: List<MultipartBody.Part>
    ): Response<UploadResponse>

    @POST("ask")
    suspend fun askQuestion(
        @Body request: QueryRequest
    ): Response<QueryResponse>

    @GET("documents")
    suspend fun getDocuments(): Response<DocumentsResponse>

    @POST("reset")
    suspend fun resetDocuments(): Response<ResetResponse>
}

object RetrofitClient {
    // 10.0.2.2 = host machine loopback from Android Emulator
    private const val BASE_URL = "http://10.0.2.2:8000/"

    val api: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
