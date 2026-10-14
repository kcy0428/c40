package kr.c40.floor

import okhttp3.OkHttpClient
import okhttp3.Request
import org.eclipse.californium.core.CoapClient
import java.util.concurrent.TimeUnit

class RealGateway(private val endpoints: RobotEndpoints) : RobotGateway {

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    override fun httpGet(path: String): GatewayResult {
        val start = System.currentTimeMillis()
        return try {
            http.newCall(Request.Builder().url(endpoints.httpBase + path).get().build()).execute().use { r ->
                GatewayResult(r.isSuccessful, "HTTP ${r.code}", r.body.string(), System.currentTimeMillis() - start)
            }
        } catch (e: Exception) {
            GatewayResult(false, "ERR", "${e.javaClass.simpleName}: ${e.message}", System.currentTimeMillis() - start)
        }
    }

    override fun coapGet(path: String): GatewayResult {
        val start = System.currentTimeMillis()
        val client = CoapClient("coap://${endpoints.coapHost}:${endpoints.coapPort}$path")
        client.setTimeout(4000L)
        return try {
            val r = client.get()
            if (r == null) {
                GatewayResult(false, "TIMEOUT", "응답 없음", System.currentTimeMillis() - start)
            } else {
                GatewayResult(r.isSuccess, r.code.toString(), r.responseText ?: "", System.currentTimeMillis() - start)
            }
        } catch (e: Exception) {
            GatewayResult(false, "ERR", "${e.javaClass.simpleName}: ${e.message}", System.currentTimeMillis() - start)
        } finally {
            client.shutdown()
        }
    }
}
