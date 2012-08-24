/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.local.trace;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.informantproject.core.util.ByteStream;
import org.informantproject.core.util.Clock;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class TraceSnapshotTestData {

    private static final AtomicInteger counter = new AtomicInteger();

    private final Clock clock;

    @Inject
    TraceSnapshotTestData(Clock clock) {
        this.clock = clock;
    }

    TraceSnapshot createSnapshot() {
        return TraceSnapshot.builder()
                .id("abc" + counter.getAndIncrement())
                .startAt(clock.currentTimeMillis() - 10)
                .stuck(false)
                .duration(TimeUnit.MILLISECONDS.toNanos(10))
                .completed(true)
                .description("test description")
                .username("j")
                .spans(ByteStream.of("[{\"offset\":0,\"duration\":0,\"index\":0,"
                        + "\"parentIndex\":-1,\"level\":0,\"description\":\"Level One\","
                        + "\"contextMap\":\"{\"arg1\":\"a\",arg2\":\"b\",\"nested1\":"
                        + "{\"nestedkey11\":\"a\",\"nestedkey12\":\"b\",\"subnestedkey1\":"
                        + "{\"subnestedkey1\":\"a\",\"subnestedkey2\":\"b\"}},\"nested2\":"
                        + "{\"nestedkey21\":\"a\",\"nestedkey22\":\"b\"}}},{\"offset\":0,"
                        + "\"duration\":0,\"index\":1,\"parentIndex\":0,\"level\":1,"
                        + "\"description\":\"Level Two\",\"contextMap\":{\"arg1\":\"ax\",\"arg2\":"
                        + "\"bx\"}},{\"offset\":0,\"duration\":0,\"index\":2,\"parentIndex\":1,"
                        + "\"level\":2,\"description\":\"Level Three\",\"contextMap\":{\"arg1\":"
                        + "\"axy\",\"arg2\":\"bxy\"}}]"))
                .build();
    }
}