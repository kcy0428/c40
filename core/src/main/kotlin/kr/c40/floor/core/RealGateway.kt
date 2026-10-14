package kr.c40.floor.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.eclipse.californium.core.CoapClient
import org.eclipse.californium.core.coap.MediaTypeRegistry
import java.util.concurrent.TimeUnit

class RealGateway(private val endpoints: RobotEndpoints) : RobotGateway, WriteGateway {

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    override fun httpGet(path: String): GatewayResult = httpCall(path) { get() }

    override fun httpPost(path: String, json: String): GatewayResult =
        httpCall(path) { post(json.toRequestBody("application/json; charset=utf-8".toMediaType())) }

    private fun httpCall(path: String, build: Request.Builder.() -> Request.Builder): GatewayResult {
        val start = System.currentTimeMillis()
        return try {
            val req = Request.Builder().url(endpoints.httpBase + path).build(build).build()
            http.newCall(req).execute().use { r ->
                GatewayResult(r.isSuccessful, "HTTP ${r.code}", r.body.string(), elapsed(start))
            }
        } catch (e: Exception) {
            GatewayResult(false, "ERR", "${e.javaClass.simpleName}: ${e.message}", elapsed(start))
        }
    }

    private fun Request.Builder.build(f: Request.Builder.() -> Request.Builder) = f()

    override fun coapGet(path: String): GatewayResult = coapCall(path) { it.get() }

    override fun coapPost(path: String, json: String): GatewayResult =
        coapCall(path) { it.post(json, MediaTypeRegistry.APPLICATION_JSON) }

    private fun coapCall(path: String, send: (CoapClient) -> org.eclipse.californium.core.CoapResponse?): GatewayResult {
        val start = System.currentTimeMillis()
        val client = CoapClient("coap://${endpoints.coapHost}:${endpoints.coapPort}$path")
        client.setTimeout(4000L)
        return try {
            val r = send(client)
            if (r == null) {
                GatewayResult(false, "TIMEOUT", "응답 없음", elapsed(start))
            } else {
                GatewayResult(r.isSuccess, r.code.toString(), r.responseText ?: "", elapsed(start))
            }
        } catch (e: Exception) {
            GatewayResult(false, "ERR", "${e.javaClass.simpleName}: ${e.message}", elapsed(start))
        } finally {
            client.shutdown()
        }
    }

    private fun elapsed(start: Long) = System.currentTimeMillis() - start
}
