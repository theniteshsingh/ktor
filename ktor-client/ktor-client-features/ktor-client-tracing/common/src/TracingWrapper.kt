/*
 * Copyright 2014-2020 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.features.tracing

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
import io.ktor.util.*
import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*

class TracingWrapper<T : HttpClientEngineConfig>(
    private val delegate: HttpClientEngineFactory<T>,
    private val tracer: Tracer
) : HttpClientEngineFactory<T> {
    override fun create(block: T.() -> Unit): HttpClientEngine {
        val engine = delegate.create(block)
        return EngineWithTracer(engine, tracer)
    }

    private class EngineWithTracer(private val delegate: HttpClientEngine, private val tracer: Tracer) :
        HttpClientEngine by delegate {

        private val sequence = atomic(0)

        @InternalAPI
        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            val requestId = "${sequence.getAndIncrement()}"
            val isWebSocket = data.body is ClientUpgradeContent
            if (isWebSocket) {
                tracer.webSocketCreated(requestId, data.url.toString())
                tracer.webSocketWillSendHandshakeRequest(requestId, data)
            } else {
                tracer.requestWillBeSent(requestId, data)
            }

            try {
                val result = delegate.execute(data)
                if (isWebSocket) {
                    tracer.webSocketHandshakeResponseReceived(requestId, data, result)
                } else {
                    tracer.responseHeadersReceived(requestId, data, result)
                }

                coroutineContext[Job]!!.invokeOnCompletion {
                    if (isWebSocket) {
                        tracer.webSocketClosed(requestId)
                    } else {
                        tracer.responseReadFinished(requestId)
                    }
                }

                return with(result) {
                    HttpResponseData(
                        statusCode,
                        requestTime,
                        headers,
                        version,
                        if (isWebSocket) {
                            WebSocketSessionTracer(
                                requestId,
                                tracer,
                                result.body as DefaultWebSocketSession
                            )
                        } else {
                            tracer.interpretResponse(
                                requestId,
                                headers[HttpHeaders.ContentType],
                                headers[HttpHeaders.ContentEncoding],
                                result.body
                            )!!
                        },
                        callContext
                    )
                }
            } catch (cause: Throwable) {
                tracer.httpExchangeFailed(requestId, cause.message!!)
                throw cause
            }
        }

        @InternalAPI
        override fun install(client: HttpClient) {
            super.install(client)
        }
    }
}

private class WebSocketSessionTracer(requestId: String, tracer: Tracer, private val delegate: DefaultWebSocketSession) :
    DefaultWebSocketSession by delegate {
    override val incoming = IncomingChannelTracer(requestId, tracer, delegate.incoming)
    override val outgoing = OutgoingChannelTracer(requestId, tracer, delegate.outgoing)
    override suspend fun send(frame: Frame) {
        outgoing.send(frame)
    }
}

private class IncomingChannelTracer(
    private val requestId: String,
    private val tracer: Tracer,
    private val delegate: ReceiveChannel<Frame>
) : ReceiveChannel<Frame> by delegate {
    override fun iterator(): ChannelIterator<Frame> {
        return ChannelIteratorTracer(requestId, tracer, delegate.iterator())
    }

    override fun poll(): Frame? {
        val result = delegate.poll()
        if (result != null) {
            tracer.webSocketFrameReceived(requestId, result)
        }
        return result
    }

    override suspend fun receive(): Frame {
        val result = delegate.receive()
        tracer.webSocketFrameReceived(requestId, result)
        return result
    }

    @InternalCoroutinesApi
    override suspend fun receiveOrClosed(): ValueOrClosed<Frame> {
        val result = delegate.receiveOrClosed()
        if (!result.isClosed) {
            tracer.webSocketFrameReceived(requestId, result.value)
        }
        return result
    }

    @ObsoleteCoroutinesApi
    override suspend fun receiveOrNull(): Frame? {
        val result = delegate.receiveOrNull()
        if (result != null) {
            tracer.webSocketFrameReceived(requestId, result)
        }
        return result
    }
}

private class ChannelIteratorTracer(
    private val requestId: String,
    private val tracer: Tracer,
    private val delegate: ChannelIterator<Frame>
) : ChannelIterator<Frame> by delegate {
    override fun next(): Frame {
        val result = delegate.next()
        tracer.webSocketFrameReceived(requestId, result)
        return result
    }
}

private class OutgoingChannelTracer(
    private val requestId: String,
    private val tracer: Tracer,
    private val delegate: SendChannel<Frame>
) : SendChannel<Frame> by delegate {
    override fun offer(element: Frame): Boolean {
        val result = delegate.offer(element)
        if (result) {
            tracer.webSocketFrameSent(requestId, element)
        }
        return result
    }

    override suspend fun send(element: Frame) {
        delegate.send(element)
        tracer.webSocketFrameSent(requestId, element)
    }
}
