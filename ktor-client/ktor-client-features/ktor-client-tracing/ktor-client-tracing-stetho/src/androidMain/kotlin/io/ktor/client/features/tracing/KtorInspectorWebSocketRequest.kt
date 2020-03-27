package io.ktor.client.features.tracing

import com.facebook.stetho.inspector.network.*
import io.ktor.client.request.*

internal class KtorInspectorWebSocketRequest(
    private val requestId: String,
    private val requestData: HttpRequestData
) : NetworkEventReporter.InspectorWebSocketRequest,
    NetworkEventReporter.InspectorHeaders by KtorInterceptorHeaders(requestData.headers) {

    override fun id(): String {
        return requestId
    }

    override fun friendlyName(): String {
        return "ktor-stetho-tracer"
    }
}
