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
package org.glowroot.server.storage;

import java.util.List;
import java.util.Map;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.SocketOptions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.common.live.ImmutableOverallQuery;
import org.glowroot.common.live.ImmutableTransactionQuery;
import org.glowroot.common.live.LiveAggregateRepository.OverallQuery;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.TransactionQuery;
import org.glowroot.common.model.MutableQuery;
import org.glowroot.common.model.OverallErrorSummaryCollector;
import org.glowroot.common.model.OverallErrorSummaryCollector.OverallErrorSummary;
import org.glowroot.common.model.OverallSummaryCollector;
import org.glowroot.common.model.OverallSummaryCollector.OverallSummary;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.model.Result;
import org.glowroot.common.model.TransactionErrorSummaryCollector;
import org.glowroot.common.model.TransactionErrorSummaryCollector.ErrorSummarySortOrder;
import org.glowroot.common.model.TransactionErrorSummaryCollector.TransactionErrorSummary;
import org.glowroot.common.model.TransactionSummaryCollector;
import org.glowroot.common.model.TransactionSummaryCollector.SummarySortOrder;
import org.glowroot.common.model.TransactionSummaryCollector.TransactionSummary;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate.QueriesByType;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate.Query;
import org.glowroot.wire.api.model.AggregateOuterClass.AggregatesByType;
import org.glowroot.wire.api.model.AggregateOuterClass.TransactionAggregate;
import org.glowroot.wire.api.model.Proto.OptionalInt64;

import static org.assertj.core.api.Assertions.assertThat;

public class AggregateDaoIT {

    private static Cluster cluster;
    private static Session session;
    private static AggregateDao aggregateDao;

    @BeforeClass
    public static void setUp() throws Exception {
        SharedSetupRunListener.startCassandra();
        cluster = Cluster.builder().addContactPoint("127.0.0.1")
                // long read timeout is sometimes needed on slow travis ci machines
                .withSocketOptions(new SocketOptions().setReadTimeoutMillis(30000))
                .build();
        session = cluster.newSession();
        session.execute("create keyspace if not exists glowroot_unit_tests with replication ="
                + " { 'class' : 'SimpleStrategy', 'replication_factor' : 1 }");
        session.execute("use glowroot_unit_tests");

        ServerConfigDao serverConfigDao = new ServerConfigDao(session);
        AgentDao agentDao = new AgentDao(session);
        KeyspaceMetadata keyspace = cluster.getMetadata().getKeyspace("glowroot_unit_tests");
        UserDao userDao = new UserDao(session, keyspace);
        RoleDao roleDao = new RoleDao(session, keyspace);
        ConfigRepository configRepository =
                new ConfigRepositoryImpl(serverConfigDao, agentDao, userDao, roleDao);
        agentDao.setConfigRepository(configRepository);
        serverConfigDao.setConfigRepository(configRepository);
        TransactionTypeDao transactionTypeDao = new TransactionTypeDao(session, configRepository);
        aggregateDao = new AggregateDao(session, transactionTypeDao, configRepository);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        session.close();
        cluster.close();
        SharedSetupRunListener.stopCassandra();
    }

    @Test
    public void shouldRollup() throws Exception {
        aggregateDao.truncateAll();
        List<String> sharedQueryText = ImmutableList.of("select 1");
        aggregateDao.store("one", 60000, createData(), sharedQueryText);
        aggregateDao.store("one", 120000, createData(), sharedQueryText);
        aggregateDao.store("one", 360000, createData(), sharedQueryText);

        // check non-rolled up data
        OverallQuery overallQuery = ImmutableOverallQuery.builder()
                .transactionType("tt1")
                .from(0)
                .to(300000)
                .rollupLevel(0)
                .build();
        TransactionQuery transactionQuery = ImmutableTransactionQuery.builder()
                .transactionType("tt1")
                .from(0)
                .to(300000)
                .rollupLevel(0)
                .build();

        OverallSummaryCollector overallSummaryCollector = new OverallSummaryCollector();
        aggregateDao.mergeOverallSummaryInto("one", overallQuery, overallSummaryCollector);
        OverallSummary overallSummary = overallSummaryCollector.getOverallSummary();
        assertThat(overallSummary.totalDurationNanos()).isEqualTo(3579 * 2);
        assertThat(overallSummary.transactionCount()).isEqualTo(6);

        TransactionSummaryCollector transactionSummaryCollector = new TransactionSummaryCollector();
        SummarySortOrder sortOrder = SummarySortOrder.TOTAL_TIME;
        aggregateDao.mergeTransactionSummariesInto("one", overallQuery, sortOrder, 10,
                transactionSummaryCollector);
        Result<TransactionSummary> result = transactionSummaryCollector.getResult(sortOrder, 10);
        assertThat(result.records()).hasSize(2);
        assertThat(result.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(result.records().get(0).totalDurationNanos()).isEqualTo(2345 * 2);
        assertThat(result.records().get(0).transactionCount()).isEqualTo(4);
        assertThat(result.records().get(1).transactionName()).isEqualTo("tn1");
        assertThat(result.records().get(1).totalDurationNanos()).isEqualTo(1234 * 2);
        assertThat(result.records().get(1).transactionCount()).isEqualTo(2);

        OverallErrorSummaryCollector overallErrorSummaryCollector =
                new OverallErrorSummaryCollector();
        aggregateDao.mergeOverallErrorSummaryInto("one", overallQuery,
                overallErrorSummaryCollector);
        OverallErrorSummary overallErrorSummary =
                overallErrorSummaryCollector.getOverallErrorSummary();
        assertThat(overallErrorSummary.errorCount()).isEqualTo(2);
        assertThat(overallErrorSummary.transactionCount()).isEqualTo(6);

        TransactionErrorSummaryCollector errorSummaryCollector =
                new TransactionErrorSummaryCollector();
        ErrorSummarySortOrder errorSortOrder = ErrorSummarySortOrder.ERROR_COUNT;
        aggregateDao.mergeTransactionErrorSummariesInto("one", overallQuery, errorSortOrder, 10,
                errorSummaryCollector);
        Result<TransactionErrorSummary> errorSummaryResult =
                errorSummaryCollector.getResult(errorSortOrder, 10);
        assertThat(errorSummaryResult.records()).hasSize(1);
        assertThat(errorSummaryResult.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(errorSummaryResult.records().get(0).errorCount()).isEqualTo(2);
        assertThat(errorSummaryResult.records().get(0).transactionCount()).isEqualTo(4);

        List<OverviewAggregate> overviewAggregates =
                aggregateDao.readOverviewAggregates("one", transactionQuery);
        assertThat(overviewAggregates).hasSize(2);
        assertThat(overviewAggregates.get(0).transactionCount()).isEqualTo(3);
        assertThat(overviewAggregates.get(1).transactionCount()).isEqualTo(3);

        List<PercentileAggregate> percentileAggregates =
                aggregateDao.readPercentileAggregates("one", transactionQuery);
        assertThat(percentileAggregates).hasSize(2);
        assertThat(percentileAggregates.get(0).transactionCount()).isEqualTo(3);
        assertThat(percentileAggregates.get(1).transactionCount()).isEqualTo(3);

        List<ThroughputAggregate> throughputAggregates =
                aggregateDao.readThroughputAggregates("one", transactionQuery);
        assertThat(throughputAggregates).hasSize(2);
        assertThat(throughputAggregates.get(0).transactionCount()).isEqualTo(3);
        assertThat(throughputAggregates.get(1).transactionCount()).isEqualTo(3);

        QueryCollector queryCollector = new QueryCollector(1000, 0);
        aggregateDao.mergeQueriesInto("one", transactionQuery, queryCollector);
        Map<String, List<MutableQuery>> queries = queryCollector.getSortedQueries();
        assertThat(queries).hasSize(1);
        List<MutableQuery> queriesByType = queries.get("sqlo");
        assertThat(queriesByType).hasSize(1);
        MutableQuery query = queriesByType.get(0);
        assertThat(query.getTruncatedQueryText()).isEqualTo("select 1");
        assertThat(query.getFullQueryTextSha1()).isNull();
        assertThat(query.getTotalDurationNanos()).isEqualTo(14);
        assertThat(query.hasTotalRows()).isTrue();
        assertThat(query.getTotalRows()).isEqualTo(10);
        assertThat(query.getExecutionCount()).isEqualTo(4);

        // rollup
        aggregateDao.rollup("one");
        aggregateDao.rollup("one");
        aggregateDao.rollup("one");
        aggregateDao.rollup("one");

        // check rolled-up data after rollup
        overallQuery = ImmutableOverallQuery.builder()
                .copyFrom(overallQuery)
                .rollupLevel(1)
                .build();
        transactionQuery = ImmutableTransactionQuery.builder()
                .copyFrom(transactionQuery)
                .rollupLevel(1)
                .build();

        overallSummaryCollector = new OverallSummaryCollector();
        aggregateDao.mergeOverallSummaryInto("one", overallQuery, overallSummaryCollector);
        overallSummary = overallSummaryCollector.getOverallSummary();
        assertThat(overallSummary.totalDurationNanos()).isEqualTo(3579 * 2);
        assertThat(overallSummary.transactionCount()).isEqualTo(6);

        transactionSummaryCollector = new TransactionSummaryCollector();
        aggregateDao.mergeTransactionSummariesInto("one", overallQuery, sortOrder, 10,
                transactionSummaryCollector);
        result = transactionSummaryCollector.getResult(sortOrder, 10);
        assertThat(result.records()).hasSize(2);
        assertThat(result.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(result.records().get(0).totalDurationNanos()).isEqualTo(2345 * 2);
        assertThat(result.records().get(0).transactionCount()).isEqualTo(4);
        assertThat(result.records().get(1).transactionName()).isEqualTo("tn1");
        assertThat(result.records().get(1).totalDurationNanos()).isEqualTo(1234 * 2);
        assertThat(result.records().get(1).transactionCount()).isEqualTo(2);

        overallErrorSummaryCollector = new OverallErrorSummaryCollector();
        aggregateDao.mergeOverallErrorSummaryInto("one", overallQuery,
                overallErrorSummaryCollector);
        overallErrorSummary = overallErrorSummaryCollector.getOverallErrorSummary();
        assertThat(overallErrorSummary.errorCount()).isEqualTo(2);
        assertThat(overallErrorSummary.transactionCount()).isEqualTo(6);

        errorSummaryCollector = new TransactionErrorSummaryCollector();
        aggregateDao.mergeTransactionErrorSummariesInto("one", overallQuery, errorSortOrder, 10,
                errorSummaryCollector);
        errorSummaryResult = errorSummaryCollector.getResult(errorSortOrder, 10);
        assertThat(errorSummaryResult.records()).hasSize(1);
        assertThat(errorSummaryResult.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(errorSummaryResult.records().get(0).errorCount()).isEqualTo(2);
        assertThat(errorSummaryResult.records().get(0).transactionCount()).isEqualTo(4);

        overviewAggregates = aggregateDao.readOverviewAggregates("one", transactionQuery);
        assertThat(overviewAggregates).hasSize(1);
        assertThat(overviewAggregates.get(0).transactionCount()).isEqualTo(6);

        percentileAggregates = aggregateDao.readPercentileAggregates("one", transactionQuery);
        assertThat(percentileAggregates).hasSize(1);
        assertThat(percentileAggregates.get(0).transactionCount()).isEqualTo(6);

        throughputAggregates = aggregateDao.readThroughputAggregates("one", transactionQuery);
        assertThat(throughputAggregates).hasSize(1);
        assertThat(throughputAggregates.get(0).transactionCount()).isEqualTo(6);

        queryCollector = new QueryCollector(1000, 0);
        aggregateDao.mergeQueriesInto("one", transactionQuery, queryCollector);
        queries = queryCollector.getSortedQueries();
        assertThat(queries).hasSize(1);
        queriesByType = queries.get("sqlo");
        assertThat(queriesByType).hasSize(1);
        query = queriesByType.get(0);
        assertThat(query.getTruncatedQueryText()).isEqualTo("select 1");
        assertThat(query.getFullQueryTextSha1()).isNull();
        assertThat(query.getTotalDurationNanos()).isEqualTo(14);
        assertThat(query.hasTotalRows()).isTrue();
        assertThat(query.getTotalRows()).isEqualTo(10);
        assertThat(query.getExecutionCount()).isEqualTo(4);
    }

    private static List<AggregatesByType> createData() {
        List<AggregatesByType> aggregatesByType = Lists.newArrayList();
        aggregatesByType.add(AggregatesByType.newBuilder()
                .setTransactionType("tt0")
                .setOverallAggregate(createOverallAggregate())
                .addTransactionAggregate(createTransactionAggregate1())
                .addTransactionAggregate(createTransactionAggregate2())
                .build());
        aggregatesByType.add(AggregatesByType.newBuilder()
                .setTransactionType("tt1")
                .setOverallAggregate(createOverallAggregate())
                .addTransactionAggregate(createTransactionAggregate1())
                .addTransactionAggregate(createTransactionAggregate2())
                .build());
        return aggregatesByType;
    }

    private static Aggregate createOverallAggregate() {
        return Aggregate.newBuilder()
                .setTotalDurationNanos(3579)
                .setTransactionCount(3)
                .setErrorCount(1)
                .addMainThreadRootTimer(Aggregate.Timer.newBuilder()
                        .setName("abc")
                        .setTotalNanos(333)
                        .setCount(3))
                .addAuxThreadRootTimer(Aggregate.Timer.newBuilder()
                        .setName("xyz")
                        .setTotalNanos(666)
                        .setCount(3))
                .addAsyncTimer(Aggregate.Timer.newBuilder()
                        .setName("mnm")
                        .setTotalNanos(999)
                        .setCount(3))
                .addQueriesByType(QueriesByType.newBuilder()
                        .setType("sqlo")
                        .addQuery(Query.newBuilder()
                                .setSharedQueryTextIndex(0)
                                .setTotalDurationNanos(7)
                                .setTotalRows(OptionalInt64.newBuilder().setValue(5))
                                .setExecutionCount(2)))
                .build();
    }

    private static TransactionAggregate createTransactionAggregate1() {
        return TransactionAggregate.newBuilder()
                .setTransactionName("tn1")
                .setAggregate(Aggregate.newBuilder()
                        .setTotalDurationNanos(1234)
                        .setTransactionCount(1)
                        .addMainThreadRootTimer(Aggregate.Timer.newBuilder()
                                .setName("abc")
                                .setTotalNanos(111)
                                .setCount(1))
                        .addAuxThreadRootTimer(Aggregate.Timer.newBuilder()
                                .setName("xyz")
                                .setTotalNanos(222)
                                .setCount(1))
                        .addAsyncTimer(Aggregate.Timer.newBuilder()
                                .setName("mnm")
                                .setTotalNanos(333)
                                .setCount(1))
                        .addQueriesByType(QueriesByType.newBuilder()
                                .setType("sqlo")
                                .addQuery(Query.newBuilder()
                                        .setSharedQueryTextIndex(0)
                                        .setTotalDurationNanos(7)
                                        .setTotalRows(OptionalInt64.newBuilder().setValue(5))
                                        .setExecutionCount(2))))
                .build();
    }

    private static TransactionAggregate createTransactionAggregate2() {
        return TransactionAggregate.newBuilder()
                .setTransactionName("tn2")
                .setAggregate(Aggregate.newBuilder()
                        .setTotalDurationNanos(2345)
                        .setTransactionCount(2)
                        .setErrorCount(1)
                        .addMainThreadRootTimer(Aggregate.Timer.newBuilder()
                                .setName("abc")
                                .setTotalNanos(222)
                                .setCount(2))
                        .addAuxThreadRootTimer(Aggregate.Timer.newBuilder()
                                .setName("xyz")
                                .setTotalNanos(444)
                                .setCount(2))
                        .addAsyncTimer(Aggregate.Timer.newBuilder()
                                .setName("mnm")
                                .setTotalNanos(666)
                                .setCount(2)))
                .build();
    }
}
