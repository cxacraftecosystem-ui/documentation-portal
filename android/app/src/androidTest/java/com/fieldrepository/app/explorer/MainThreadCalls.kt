package com.fieldrepository.app.explorer

import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

internal object MainThreadCalls {
    fun factory(client: OkHttpClient): Call.Factory = Call.Factory { request -> MainCall(client.newCall(request)) }
}

private class MainCall(private val real: Call) : Call by real {
    private val main = Handler(Looper.getMainLooper())

    override fun enqueue(responseCallback: Callback) {
        real.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val read = runCatching {
                    val body = response.body
                    response.newBuilder().body(body.bytes().toResponseBody(body.contentType())).build()
                }
                main.post {
                    read.fold(
                        onSuccess = { responseCallback.onResponse(this@MainCall, it) },
                        onFailure = { responseCallback.onFailure(this@MainCall, it as? IOException ?: IOException(it)) },
                    )
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                main.post { responseCallback.onFailure(this@MainCall, e) }
            }
        })
    }

    override fun clone(): Call = MainCall(real.clone())
}
