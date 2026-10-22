package com.fieldrepository.app.forms

import com.fieldrepository.app.data.ApiClient
import com.fieldrepository.app.questionnaire.data.QuestionnaireApi
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * The real Retrofit interfaces over a server in memory: every request is recorded (method, path with
 * query, body) and answered from [routes] ("METHOD /path" prefix to a canned reply). Nothing leaves
 * the JVM; what is checked is exactly what the app would send.
 */
class FakeServer {
    data class Seen(val method: String, val path: String, val body: String, val contentType: String?)
    data class Reply(val code: Int = 200, val body: String = "{}", val headers: Map<String, String> = emptyMap(), val type: String = "application/json")

    val seen = mutableListOf<Seen>()
    val routes = linkedMapOf<String, Reply>()

    fun on(route: String, reply: Reply) { routes[route] = reply }
    fun on(route: String, json: String) { routes[route] = Reply(body = json) }

    private val interceptor = Interceptor { chain ->
        val request = chain.request()
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        val path = request.url.encodedPath.removePrefix("/api") + (request.url.encodedQuery?.let { "?$it" } ?: "")
        seen += Seen(request.method, path, buffer.readUtf8(), request.body?.contentType()?.toString())
        val key = "${request.method} $path"
        val reply = routes.entries.firstOrNull { key.startsWith(it.key) }?.value ?: Reply(404, """{"detail":"No route $key"}""")
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(reply.code).message("x")
            .apply { reply.headers.forEach { (k, v) -> header(k, v) } }
            .body(reply.body.toResponseBody(reply.type.toMediaType())).build()
    }

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://test.invalid/api/")
        .client(OkHttpClient.Builder().addInterceptor(interceptor).build())
        .addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType()))
        .build()

    val forms: QuestionnaireFormsApi = retrofit.create(QuestionnaireFormsApi::class.java)
    val api: QuestionnaireApi = retrofit.create(QuestionnaireApi::class.java)

    fun last(): Seen = seen.last()
}
