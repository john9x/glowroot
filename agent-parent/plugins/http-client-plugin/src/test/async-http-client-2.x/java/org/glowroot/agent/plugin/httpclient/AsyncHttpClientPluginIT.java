/**
 * Copyright 2015-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.agent.plugin.httpclient;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.Response;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

// TODO test against AsyncHttpClient providers jdk and grizzly (in addition to the default netty)
public class AsyncHttpClientPluginIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.create();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldCaptureHttpGet() throws Exception {
        Trace trace = container.execute(ExecuteHttpGet.class);
        Trace.Timer rootTimer = trace.getHeader().getMainThreadRootTimer();
        assertThat(rootTimer.getChildTimerCount()).isEqualTo(1);
        assertThat(rootTimer.getChildTimer(0).getName()).isEqualTo("http client request");
        assertThat(rootTimer.getChildTimer(0).getCount()).isEqualTo(1);
        assertThat(trace.getHeader().getAuxThreadRootTimerCount()).isZero();
        assertThat(trace.getHeader().getAsyncTimerCount()).isEqualTo(1);
        Trace.Timer asyncTimer = trace.getHeader().getAsyncTimer(0);
        assertThat(asyncTimer.getChildTimerCount()).isZero();
        assertThat(asyncTimer.getName()).isEqualTo("http client request");
        assertThat(asyncTimer.getCount()).isEqualTo(1);
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMessage())
                .matches("http client request: GET http://localhost:\\d+/hello1/");
    }

    @Test
    public void shouldCaptureHttpPost() throws Exception {
        Trace trace = container.execute(ExecuteHttpPost.class);
        Trace.Timer rootTimer = trace.getHeader().getMainThreadRootTimer();
        assertThat(rootTimer.getChildTimerCount()).isEqualTo(1);
        assertThat(rootTimer.getChildTimer(0).getName()).isEqualTo("http client request");
        assertThat(rootTimer.getChildTimer(0).getCount()).isEqualTo(1);
        assertThat(trace.getHeader().getAuxThreadRootTimerCount()).isZero();
        assertThat(trace.getHeader().getAsyncTimerCount()).isEqualTo(1);
        Trace.Timer asyncTimer = trace.getHeader().getAsyncTimer(0);
        assertThat(asyncTimer.getChildTimerCount()).isZero();
        assertThat(asyncTimer.getName()).isEqualTo("http client request");
        assertThat(asyncTimer.getCount()).isEqualTo(1);
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMessage())
                .matches("http client request: POST http://localhost:\\d+/hello2");
    }

    @Test
    public void shouldCaptureHttpGetWithAsyncHandler() throws Exception {
        Trace trace = container.execute(ExecuteHttpGetWithAsyncHandler.class);
        Trace.Timer rootTimer = trace.getHeader().getMainThreadRootTimer();
        assertThat(rootTimer.getChildTimerCount()).isEqualTo(1);
        assertThat(rootTimer.getChildTimer(0).getName()).isEqualTo("http client request");
        assertThat(rootTimer.getChildTimer(0).getCount()).isEqualTo(1);
        assertThat(trace.getHeader().getAuxThreadRootTimerCount()).isZero();
        assertThat(trace.getHeader().getAsyncTimerCount()).isEqualTo(1);
        Trace.Timer asyncTimer = trace.getHeader().getAsyncTimer(0);
        assertThat(asyncTimer.getChildTimerCount()).isZero();
        assertThat(asyncTimer.getName()).isEqualTo("http client request");
        assertThat(asyncTimer.getCount()).isEqualTo(1);
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMessage())
                .matches("http client request: GET http://localhost:\\d+/hello3/");
    }

    public static class ExecuteHttpGet extends ExecuteHttpBase {
        @Override
        public void transactionMarker() throws Exception {
            AsyncHttpClient asyncHttpClient = new DefaultAsyncHttpClient();
            int statusCode =
                    asyncHttpClient.prepareGet("http://localhost:" + getPort() + "/hello1/")
                            .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    public static class ExecuteHttpPost extends ExecuteHttpBase {
        @Override
        public void transactionMarker() throws Exception {
            AsyncHttpClient asyncHttpClient = new DefaultAsyncHttpClient();
            int statusCode =
                    asyncHttpClient.preparePost("http://localhost:" + getPort() + "/hello2")
                            .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    public static class ExecuteHttpGetWithAsyncHandler extends ExecuteHttpBase {
        @Override
        public void transactionMarker() throws Exception {
            AsyncHttpClient asyncHttpClient = new DefaultAsyncHttpClient();
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicInteger statusCode = new AtomicInteger();
            asyncHttpClient.prepareGet("http://localhost:" + getPort() + "/hello3/")
                    .execute(new AsyncHandler<Response>() {
                        @Override
                        public State onBodyPartReceived(HttpResponseBodyPart part) {
                            return null;
                        }
                        @Override
                        public Response onCompleted() throws Exception {
                            latch.countDown();
                            return null;
                        }
                        @Override
                        public State onHeadersReceived(HttpResponseHeaders headers) {
                            return null;
                        }
                        @Override
                        public State onStatusReceived(HttpResponseStatus status) {
                            statusCode.set(status.getStatusCode());
                            return null;
                        }
                        @Override
                        public void onThrowable(Throwable t) {}
                    });
            latch.await();
            asyncHttpClient.close();
            if (statusCode.get() != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }
}
