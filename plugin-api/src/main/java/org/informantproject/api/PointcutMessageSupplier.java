/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.api;

import java.util.List;

import javax.annotation.Nullable;

import org.informantproject.api.Span.MessageUpdater;

import com.google.common.collect.Lists;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PointcutMessageSupplier extends Supplier<Message> {

    private final String template;
    private final Object[] args;
    private volatile boolean hasReturnValue;
    @Nullable
    private volatile Object returnValue;

    public PointcutMessageSupplier(String template, Object... args) {
        this.template = template;
        this.args = args;
    }

    public void setReturnValue(Object returnValue) {
        this.returnValue = returnValue;
        hasReturnValue = true;
    }

    @Override
    public Message get() {
        if (hasReturnValue) {
            List<Object> messageArgs = Lists.newArrayList(args);
            messageArgs.add(returnValue);
            return Message.of(template + " => {{returnValue}}", messageArgs);
        } else {
            return Message.of(template, args);
        }
    }

    public static void updateWithReturnValue(final Object returnValue, Span span) {
        span.updateMessage(new MessageUpdater() {
            public void update(Supplier<Message> message) {
                ((PointcutMessageSupplier) message).setReturnValue(returnValue);
            }
        });
    }
}