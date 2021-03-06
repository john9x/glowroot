/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.agent.impl;

import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.junit.Test;

import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate.QueriesByType;

import static org.assertj.core.api.Assertions.assertThat;

public class QueryCollectorTest {

    @Test
    public void testAddInAscendingOrder() {
        // given
        QueryCollector queries = new QueryCollector(100, 2);
        for (int i = 1; i <= 300; i++) {
            queries.mergeQuery("SQL", Integer.toString(i), i, 1, true, 1);
        }
        // when
        List<String> sharedQueryTexts = Lists.newArrayList();
        Map<String, Integer> sharedQueryTextIndexes = Maps.newHashMap();
        List<QueriesByType> queriesByTypeList =
                queries.toProto(sharedQueryTexts, sharedQueryTextIndexes);
        // then
        assertThat(queriesByTypeList).hasSize(1);
        QueriesByType queriesByType = queriesByTypeList.get(0);
        assertThat(queriesByType.getQueryList()).hasSize(100);
        assertThat(queriesByType.getQueryList().get(0).getTotalDurationNanos()).isEqualTo(300);
        assertThat(queriesByType.getQueryList().get(99).getTotalDurationNanos()).isEqualTo(201);
    }

    @Test
    public void testAddInDescendingOrder() {
        // given
        QueryCollector queries = new QueryCollector(100, 2);
        for (int i = 300; i > 0; i--) {
            queries.mergeQuery("SQL", Integer.toString(i), i, 1, true, 1);
        }
        // when
        List<String> sharedQueryTexts = Lists.newArrayList();
        Map<String, Integer> sharedQueryTextIndexes = Maps.newHashMap();
        List<QueriesByType> queriesByTypeList =
                queries.toProto(sharedQueryTexts, sharedQueryTextIndexes);
        // then
        assertThat(queriesByTypeList).hasSize(1);
        QueriesByType queriesByType = queriesByTypeList.get(0);
        assertThat(queriesByType.getQueryList()).hasSize(100);
        assertThat(queriesByType.getQueryList().get(0).getTotalDurationNanos()).isEqualTo(300);
        assertThat(queriesByType.getQueryList().get(99).getTotalDurationNanos()).isEqualTo(201);
    }
}
