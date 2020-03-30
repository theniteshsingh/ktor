/*
 * Copyright 2014-2020 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.features.tracing

import com.facebook.stetho.inspector.network.*
import io.ktor.client.request.*
import io.ktor.http.content.*

/**
 * Implementation of [NetworkEventReporter.InspectorRequest] that is built to work with [StethoTracer].
 */
internal class KtorInterceptorRequest(
    private val requestId: String,
    private val requestData: HttpRequestData
) : NetworkEventReporter.InspectorRequest,
    NetworkEventReporter.InspectorHeaders by KtorInterceptorHeaders(requestData.headers) {

    override fun id(): String {
        return requestId
    }

    override fun friendlyName(): String {
        return "ktor-stetho-tracer"
    }

    override fun friendlyNameExtra(): Int? {
        return null
    }

    override fun body(): ByteArray? = when (val body = requestData.body) {
        is OutgoingContent.NoContent -> null
        is OutgoingContent.ProtocolUpgrade -> null
        is OutgoingContent.ReadChannelContent -> error("Stetho tracer does not support ReadChannelContent")
        is OutgoingContent.WriteChannelContent -> error("Stetho tracer does not support WriteChannelContent")
        is OutgoingContent.ByteArrayContent -> body.bytes()
    }

    override fun url(): String {
        return requestData.url.toString()
    }

    override fun method(): String {
        return requestData.method.value
    }
}
