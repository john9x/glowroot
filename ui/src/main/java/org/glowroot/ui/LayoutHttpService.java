/*
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
package org.glowroot.ui;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;

import org.glowroot.ui.HttpSessionManager.Authentication;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;

class LayoutHttpService implements HttpService {

    private final LayoutService layoutService;

    LayoutHttpService(LayoutService layoutService) {
        this.layoutService = layoutService;
    }

    @Override
    public String getPermission() {
        // this service does not require any permission
        return "";
    }

    @Override
    public FullHttpResponse handleRequest(ChannelHandlerContext ctx, HttpRequest request,
            Authentication authentication) throws Exception {
        return HttpServices.createJsonResponse(layoutService.getLayout(authentication), OK);
    }
}
