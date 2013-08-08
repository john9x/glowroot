/*
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.local.ui;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.markers.Singleton;

/**
 * Json service to capture a thread dump, bound to /backend/threads.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
@JsonService
class ThreadsJsonService {

    private static final Logger logger = LoggerFactory.getLogger(ThreadsJsonService.class);
    private static final JsonFactory jsonFactory = new JsonFactory();

    @JsonServiceMethod
    String getThreadDump() throws IOException {
        logger.debug("getThreadDump()");
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        List<ThreadInfo> threadInfos = Lists.newArrayList();
        long[] threadIds = threadBean.getAllThreadIds();
        // sort thread ids for consistent results across F5 refresh
        Arrays.sort(threadIds);
        for (long threadId : threadIds) {
            ThreadInfo threadInfo = threadBean.getThreadInfo(threadId, Integer.MAX_VALUE);
            if (threadInfo != null) {
                threadInfos.add(threadInfo);
            }
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = jsonFactory.createGenerator(CharStreams.asWriter(sb));
        jg.writeStartArray();
        for (ThreadInfo threadInfo : threadInfos) {
            jg.writeStartObject();
            jg.writeStringField("name", threadInfo.getThreadName());
            jg.writeStringField("state", threadInfo.getThreadName());
            jg.writeStringField("lockName", threadInfo.getLockName());
            jg.writeArrayFieldStart("stackTrace");
            for (StackTraceElement stackTraceElement : threadInfo.getStackTrace()) {
                jg.writeString(stackTraceElement.toString());
            }
            jg.writeEndArray();
            jg.writeEndObject();
        }
        jg.writeEndArray();
        jg.close();
        return sb.toString();
    }
}