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
package org.glowroot.agent.fat.init;

import java.io.File;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.fat.storage.AgentDao;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.GaugeValueRepository;
import org.glowroot.storage.repo.TraceRepository;
import org.glowroot.storage.repo.helper.AlertingService;
import org.glowroot.wire.api.Collector;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.AggregatesByType;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogEvent;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.SystemInfo;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

class CollectorImpl implements Collector {

    private static final Logger logger = LoggerFactory.getLogger(CollectorImpl.class);

    private static final String AGENT_ID = "";

    private final AgentDao agentDao;
    private final AggregateRepository aggregateRepository;
    private final TraceRepository traceRepository;
    private final GaugeValueRepository gaugeValueRepository;
    private final AlertingService alertingService;

    CollectorImpl(AgentDao agentDao, AggregateRepository aggregateRepository,
            TraceRepository traceRepository, GaugeValueRepository gaugeValueRepository,
            AlertingService alertingService) {
        this.agentDao = agentDao;
        this.aggregateRepository = aggregateRepository;
        this.traceRepository = traceRepository;
        this.gaugeValueRepository = gaugeValueRepository;
        this.alertingService = alertingService;
    }

    @Override
    public void init(File glowrootBaseDir, SystemInfo systemInfo, AgentConfig agentConfig,
            AgentConfigUpdater agentConfigUpdater) throws Exception {
        agentDao.store(systemInfo);
    }

    @Override
    public void collectAggregates(long captureTime, List<AggregatesByType> aggregatesByType,
            List<String> sharedQueryTexts) throws Exception {
        aggregateRepository.store(AGENT_ID, captureTime, aggregatesByType, sharedQueryTexts);
        try {
            alertingService.checkTransactionAlerts(AGENT_ID, captureTime, null);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    @Override
    public void collectGaugeValues(List<GaugeValue> gaugeValues) throws Exception {
        gaugeValueRepository.store(AGENT_ID, gaugeValues);
        long maxCaptureTime = 0;
        for (GaugeValue gaugeValue : gaugeValues) {
            maxCaptureTime = Math.max(maxCaptureTime, gaugeValue.getCaptureTime());
        }
        try {
            alertingService.checkGaugeAlerts(AGENT_ID, maxCaptureTime, null);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    @Override
    public void collectTrace(Trace trace) throws Exception {
        traceRepository.store(AGENT_ID, trace);
    }

    @Override
    public void log(LogEvent logEvent) {
        // do nothing, already logging locally through ConsoleAppender and RollingFileAppender
    }
}
