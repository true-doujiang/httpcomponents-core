/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.hc.core5.http2.impl.nio;

import java.io.IOException;
import java.nio.charset.Charset;

import org.apache.hc.core5.http.impl.BasicHttpConnectionMetrics;
import org.apache.hc.core5.http.protocol.HttpProcessor;
import org.apache.hc.core5.http2.config.H2Config;
import org.apache.hc.core5.http2.frame.DefaultFrameFactory;
import org.apache.hc.core5.http2.frame.FrameFactory;
import org.apache.hc.core5.http2.frame.StreamIdGenerator;
import org.apache.hc.core5.http2.nio.AsyncServerExchangeHandler;
import org.apache.hc.core5.http2.nio.HandlerFactory;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.util.Args;

/**
 * Server side HTTP/2 stream multiplexer.
 *
 * @since 5.0
 */
public class ServerHttp2StreamMultiplexer extends AbstractHttp2StreamMultiplexer {

    private final HandlerFactory<AsyncServerExchangeHandler> exchangeHandlerFactory;

    public ServerHttp2StreamMultiplexer(
            final IOSession ioSession,
            final FrameFactory frameFactory,
            final HttpProcessor httpProcessor,
            final HandlerFactory<AsyncServerExchangeHandler> exchangeHandlerFactory,
            final Charset charset,
            final H2Config h2Config,
            final Http2StreamListener callback) {
        super(Mode.SERVER, ioSession, frameFactory, StreamIdGenerator.EVEN, httpProcessor, charset, h2Config, callback);
        this.exchangeHandlerFactory = Args.notNull(exchangeHandlerFactory, "Handler factory");
    }

    public ServerHttp2StreamMultiplexer(
            final IOSession ioSession,
            final HttpProcessor httpProcessor,
            final HandlerFactory<AsyncServerExchangeHandler> exchangeHandlerFactory,
            final Charset charset,
            final H2Config h2Config) {
        this(ioSession, DefaultFrameFactory.INSTANCE, httpProcessor, exchangeHandlerFactory, charset, h2Config, null);
    }

    @Override
    Http2StreamHandler createRemotelyInitiatedStream(
            final Http2StreamChannel channel,
            final HttpProcessor httpProcessor,
            final BasicHttpConnectionMetrics connMetrics) throws IOException {
        return new ServerHttp2StreamHandler(channel, httpProcessor, connMetrics, exchangeHandlerFactory);
    }

}