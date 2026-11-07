package com.example.systemset

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

internal object BackendApi {
    const val BASE_URL = BuildConfig.BACKEND_BASE_URL
    const val TENANT_PAYLOAD_ENDPOINT = "/api/v1/tenant/payload"
    const val TURN_CREDENTIALS_ENDPOINT = "/api/v1/tenant/turn-credentials"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun request(
        tenantKey: String,
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        queryParameters: Map<String, String> = emptyMap()
    ): Request {
        val urlBuilder = "$BASE_URL$path".toHttpUrl().newBuilder()
        queryParameters.forEach { (name, value) ->
            urlBuilder.addQueryParameter(name, value)
        }

        val builder = Request.Builder()
            .url(urlBuilder.build())
            .header("Accept", "application/json")
            .header("X-Tenant-Key", tenantKey)

        if (body != null) {
            builder.header("Content-Type", "application/json")
                .method(method, body.toString().toRequestBody(jsonMediaType))
        } else if (method == "POST" || method == "PUT" || method == "PATCH") {
            builder.header("Content-Type", "application/json")
                .method(method, ByteArray(0).toRequestBody(jsonMediaType))
        } else {
            builder.method(method, null)
        }
        return builder.build()
    }

    fun parseSuccessfulJson(responseBody: String, path: String): JSONObject =
        try {
            JSONObject(responseBody)
        } catch (e: JSONException) {
            throw IOException("Central Hub returned invalid JSON for $path.", e)
        }
}