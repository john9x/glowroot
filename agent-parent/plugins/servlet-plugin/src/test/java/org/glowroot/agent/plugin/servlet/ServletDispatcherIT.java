/*
 * Copyright 2011-2016 the original author or authors.
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
package org.glowroot.agent.plugin.servlet;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.ning.http.client.AsyncHttpClient;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class ServletDispatcherIT {

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
    public void testForwardServlet() throws Exception {
        // given
        // when
        Trace trace = container.execute(InvokeFowardServlet.class);
        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getHeadline()).isEqualTo("/first-forward");
        assertThat(header.getTransactionName()).isEqualTo("/first-forward");
        assertThat(trace.getHeader().getEntryCount()).isEqualTo(1);
        assertThat(trace.getEntry(0).getMessage()).isEqualTo("servlet dispatch: /second");
    }

    @Test
    public void testIncludeServlet() throws Exception {
        // given
        // when
        Trace trace = container.execute(InvokeIncludeServlet.class);
        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getHeadline()).isEqualTo("/first-include");
        assertThat(header.getTransactionName()).isEqualTo("/first-include");
        assertThat(trace.getHeader().getEntryCount()).isEqualTo(1);
        assertThat(trace.getEntry(0).getMessage()).isEqualTo("servlet dispatch: /second");
    }

    public static class InvokeFowardServlet extends InvokeServletInTomcat {
        @Override
        protected void doTest(int port) throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            // send initial to trigger servlet init methods so they don't end up in trace
            int statusCode =
                    asyncHttpClient.prepareGet("http://localhost:" + port + "/first-forward")
                            .execute().get().getStatusCode();
            if (statusCode != 200) {
                asyncHttpClient.close();
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
            statusCode = asyncHttpClient.prepareGet("http://localhost:" + port + "/first-forward")
                    .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    public static class InvokeIncludeServlet extends InvokeServletInTomcat {
        @Override
        protected void doTest(int port) throws Exception {
            AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
            // send initial to trigger servlet init methods so they don't end up in trace
            int statusCode =
                    asyncHttpClient.prepareGet("http://localhost:" + port + "/first-include")
                            .execute().get().getStatusCode();
            if (statusCode != 200) {
                asyncHttpClient.close();
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
            statusCode = asyncHttpClient.prepareGet("http://localhost:" + port + "/first-include")
                    .execute().get().getStatusCode();
            asyncHttpClient.close();
            if (statusCode != 200) {
                throw new IllegalStateException("Unexpected status code: " + statusCode);
            }
        }
    }

    @WebServlet("/first-forward")
    @SuppressWarnings("serial")
    public static class FirstForwardServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            request.getRequestDispatcher("/second").forward(request, response);
        }
    }

    @WebServlet("/first-include")
    @SuppressWarnings("serial")
    public static class FirstIncludeServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            request.getRequestDispatcher("/second").include(request, response);
        }
    }

    @WebServlet("/second")
    @SuppressWarnings("serial")
    public static class SecondServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            response.getWriter().print("second");
        }
    }
}
