/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ververica.cdc.connectors.mysql.source.reader;

import org.apache.flink.api.common.eventtime.Watermark;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.connector.source.ReaderOutput;
import org.apache.flink.api.connector.source.SourceOutput;
import org.apache.flink.api.connector.source.SourceReaderContext;
import org.apache.flink.connector.base.source.reader.RecordsWithSplitIds;
import org.apache.flink.connector.base.source.reader.synchronization.FutureCompletingBlockingQueue;
import org.apache.flink.connector.testutils.source.reader.TestingReaderContext;
import org.apache.flink.connector.testutils.source.reader.TestingReaderOutput;
import org.apache.flink.core.io.InputStatus;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.types.DataType;
import org.apache.flink.util.Collector;

import com.ververica.cdc.connectors.mysql.debezium.DebeziumUtils;
import com.ververica.cdc.connectors.mysql.source.MySqlSourceTestBase;
import com.ververica.cdc.connectors.mysql.source.assigners.MySqlBinlogSplitAssigner;
import com.ververica.cdc.connectors.mysql.source.assigners.MySqlSnapshotSplitAssigner;
import com.ververica.cdc.connectors.mysql.source.config.MySqlSourceConfig;
import com.ververica.cdc.connectors.mysql.source.config.MySqlSourceConfigFactory;
import com.ververica.cdc.connectors.mysql.source.metrics.MySqlSourceReaderMetrics;
import com.ververica.cdc.connectors.mysql.source.split.MySqlBinlogSplit;
import com.ververica.cdc.connectors.mysql.source.split.MySqlSnapshotSplit;
import com.ververica.cdc.connectors.mysql.source.split.MySqlSplit;
import com.ververica.cdc.connectors.mysql.source.utils.TableDiscoveryUtils;
import com.ververica.cdc.connectors.mysql.table.StartupOptions;
import com.ververica.cdc.connectors.mysql.testutils.RecordsFormatter;
import com.ververica.cdc.connectors.mysql.testutils.UniqueDatabase;
import com.ververica.cdc.debezium.DebeziumDeserializationSchema;
import io.debezium.connector.mysql.MySqlConnection;
import io.debezium.jdbc.JdbcConnection;
import io.debezium.relational.TableId;
import io.debezium.relational.history.TableChanges;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.Test;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/** Tests for {@link MySqlSourceReader}. */
public class MySqlSourceReaderTest extends MySqlSourceTestBase {

    private final UniqueDatabase customerDatabase =
            new UniqueDatabase(MYSQL_CONTAINER, "customer", "mysqluser", "mysqlpw");
    private final UniqueDatabase inventoryDatabase =
            new UniqueDatabase(MYSQL_CONTAINER, "inventory", "mysqluser", "mysqlpw");

    @Test
    public void testBinlogReadFailoverCrossTransaction() throws Exception {
        customerDatabase.createAndInitialize();
        final MySqlSourceConfig sourceConfig = getConfig(new String[] {"customers"});
        final DataType dataType =
                DataTypes.ROW(
                        DataTypes.FIELD("id", DataTypes.BIGINT()),
                        DataTypes.FIELD("name", DataTypes.STRING()),
                        DataTypes.FIELD("address", DataTypes.STRING()),
                        DataTypes.FIELD("phone_number", DataTypes.STRING()));
        MySqlSplit binlogSplit;
        try (MySqlConnection jdbc =
                DebeziumUtils.createMySqlConnection(sourceConfig.getDbzConfiguration())) {
            Map<TableId, TableChanges.TableChange> tableSchemas =
                    TableDiscoveryUtils.discoverCapturedTableSchemas(sourceConfig, jdbc);
            binlogSplit =
                    MySqlBinlogSplit.fillTableSchemas(
                            createBinlogSplit(sourceConfig).asBinlogSplit(), tableSchemas);
        }

        MySqlSourceReader<SourceRecord> reader = createReader(sourceConfig);
        reader.start();
        reader.addSplits(Arrays.asList(binlogSplit));

        // step-1: make 6 change events in one MySQL transaction
        TableId tableId = binlogSplit.getTableSchemas().keySet().iterator().next();
        makeBinlogEventsInOneTransaction(sourceConfig, tableId.toString());

        // step-2: fetch the first 2 records belong to the MySQL transaction
        String[] expectedRecords =
                new String[] {
                    "-U[103, user_3, Shanghai, 123567891234]",
                    "+U[103, user_3, Hangzhou, 123567891234]"
                };
        // the 2 records are produced by 1 operations
        List<String> actualRecords = consumeRecords(reader, dataType, 1);
        assertEqualsInOrder(Arrays.asList(expectedRecords), actualRecords);
        List<MySqlSplit> splitsState = reader.snapshotState(1L);
        // check the binlog split state
        assertEquals(1, splitsState.size());
        reader.close();

        // step-3: mock failover from a restored state
        MySqlSourceReader<SourceRecord> restartReader = createReader(sourceConfig);
        restartReader.start();
        restartReader.addSplits(splitsState);

        // step-4: fetch the rest 4 records belong to the MySQL transaction
        String[] expectedRestRecords =
                new String[] {
                    "-D[102, user_2, Shanghai, 123567891234]",
                    "+I[102, user_2, Shanghai, 123567891234]",
                    "-U[103, user_3, Hangzhou, 123567891234]",
                    "+U[103, user_3, Shanghai, 123567891234]"
                };
        // the 4 records are produced by 3 operations
        List<String> restRecords = consumeRecords(restartReader, dataType, 3);
        assertEqualsInOrder(Arrays.asList(expectedRestRecords), restRecords);
        restartReader.close();
    }

    @Test
    public void testNoDuplicateRecordsWhenKeepUpdating() throws Exception {
        inventoryDatabase.createAndInitialize();
        String tableName = inventoryDatabase.getDatabaseName() + ".products";
        // use default split size which is large to make sure we only have one snapshot split
        final MySqlSourceConfig sourceConfig =
                new MySqlSourceConfigFactory()
                        .startupOptions(StartupOptions.initial())
                        .databaseList(inventoryDatabase.getDatabaseName())
                        .tableList(tableName)
                        .includeSchemaChanges(false)
                        .hostname(MYSQL_CONTAINER.getHost())
                        .port(MYSQL_CONTAINER.getDatabasePort())
                        .username(customerDatabase.getUsername())
                        .password(customerDatabase.getPassword())
                        .serverTimeZone(ZoneId.of("UTC").toString())
                        .createConfig(0);
        final MySqlSnapshotSplitAssigner assigner =
                new MySqlSnapshotSplitAssigner(
                        sourceConfig,
                        DEFAULT_PARALLELISM,
                        Collections.singletonList(TableId.parse(tableName)),
                        false);
        assigner.open();
        MySqlSnapshotSplit snapshotSplit = (MySqlSnapshotSplit) assigner.getNext().get();
        // should contain only one split
        assertFalse(assigner.getNext().isPresent());
        // and the split is a full range one
        assertNull(snapshotSplit.getSplitStart());
        assertNull(snapshotSplit.getSplitEnd());

        final AtomicBoolean finishReading = new AtomicBoolean(false);
        final CountDownLatch updatingExecuted = new CountDownLatch(1);
        TestingReaderContext testingReaderContext = new TestingReaderContext();
        MySqlSourceReader<SourceRecord> reader = createReader(sourceConfig, testingReaderContext);
        reader.start();

        Thread updateWorker =
                new Thread(
                        () -> {
                            try (Connection connection = inventoryDatabase.getJdbcConnection();
                                    Statement statement = connection.createStatement()) {
                                boolean flagSet = false;
                                while (!finishReading.get()) {
                                    statement.execute(
                                            "UPDATE products SET  description='"
                                                    + UUID.randomUUID().toString()
                                                    + "' WHERE id=101");
                                    if (!flagSet) {
                                        updatingExecuted.countDown();
                                        flagSet = true;
                                    }
                                }
                            } catch (Exception throwables) {
                                throwables.printStackTrace();
                            }
                        });

        // start to keep updating the products table
        updateWorker.start();
        // wait until the updating executed
        updatingExecuted.await();
        // start to read chunks of the products table
        reader.addSplits(Collections.singletonList(snapshotSplit));
        reader.notifyNoMoreSplits();

        TestingReaderOutput<SourceRecord> output = new TestingReaderOutput<>();
        while (true) {
            InputStatus status = reader.pollNext(output);
            if (status == InputStatus.END_OF_INPUT) {
                break;
            }
            if (status == InputStatus.NOTHING_AVAILABLE) {
                reader.isAvailable().get();
            }
        }
        // stop the updating worker
        finishReading.set(true);
        updateWorker.join();

        // check the result
        ArrayList<SourceRecord> emittedRecords = output.getEmittedRecords();
        Map<Object, SourceRecord> recordByKey = new HashMap<>();
        for (SourceRecord record : emittedRecords) {
            SourceRecord existed = recordByKey.get(record.key());
            if (existed != null) {
                fail(
                        String.format(
                                "The emitted record contains duplicate records on key\n%s\n%s\n",
                                existed, record));
            } else {
                recordByKey.put(record.key(), record);
            }
        }
    }

    private MySqlSourceReader<SourceRecord> createReader(MySqlSourceConfig configuration)
            throws Exception {
        return createReader(configuration, new TestingReaderContext());
    }

    private MySqlSourceReader<SourceRecord> createReader(
            MySqlSourceConfig configuration, SourceReaderContext readerContext) throws Exception {
        final FutureCompletingBlockingQueue<RecordsWithSplitIds<SourceRecord>> elementsQueue =
                new FutureCompletingBlockingQueue<>();
        // make  SourceReaderContext#metricGroup compatible between Flink 1.13 and Flink 1.14
        final Method metricGroupMethod = readerContext.getClass().getMethod("metricGroup");
        metricGroupMethod.setAccessible(true);
        final MetricGroup metricGroup = (MetricGroup) metricGroupMethod.invoke(readerContext);

        final MySqlRecordEmitter<SourceRecord> recordEmitter =
                new MySqlRecordEmitter<>(
                        new ForwardDeserializeSchema(),
                        new MySqlSourceReaderMetrics(metricGroup),
                        configuration.isIncludeSchemaChanges());
        final MySqlSourceReaderContext mySqlSourceReaderContext =
                new MySqlSourceReaderContext(readerContext);
        return new MySqlSourceReader<>(
                elementsQueue,
                () -> createSplitReader(configuration, mySqlSourceReaderContext),
                recordEmitter,
                readerContext.getConfiguration(),
                mySqlSourceReaderContext,
                configuration);
    }

    private MySqlSplitReader createSplitReader(
            MySqlSourceConfig configuration, MySqlSourceReaderContext readerContext) {
        return new MySqlSplitReader(configuration, 0, readerContext);
    }

    private void makeBinlogEventsInOneTransaction(MySqlSourceConfig sourceConfig, String tableId)
            throws SQLException {
        JdbcConnection connection = DebeziumUtils.openJdbcConnection(sourceConfig);
        // make 6 binlog events by 4 operations
        connection.setAutoCommit(false);
        connection.execute(
                "UPDATE " + tableId + " SET address = 'Hangzhou' where id = 103",
                "DELETE FROM " + tableId + " where id = 102",
                "INSERT INTO " + tableId + " VALUES(102, 'user_2','Shanghai','123567891234')",
                "UPDATE " + tableId + " SET address = 'Shanghai' where id = 103");
        connection.commit();
        connection.close();
    }

    private MySqlSplit createBinlogSplit(MySqlSourceConfig sourceConfig) {
        MySqlBinlogSplitAssigner binlogSplitAssigner = new MySqlBinlogSplitAssigner(sourceConfig);
        binlogSplitAssigner.open();
        return binlogSplitAssigner.getNext().get();
    }

    private MySqlSourceConfig getConfig(String[] captureTables) {
        String[] captureTableIds =
                Arrays.stream(captureTables)
                        .map(tableName -> customerDatabase.getDatabaseName() + "." + tableName)
                        .toArray(String[]::new);

        return new MySqlSourceConfigFactory()
                .startupOptions(StartupOptions.initial())
                .databaseList(customerDatabase.getDatabaseName())
                .tableList(captureTableIds)
                .includeSchemaChanges(false)
                .hostname(MYSQL_CONTAINER.getHost())
                .port(MYSQL_CONTAINER.getDatabasePort())
                .splitSize(10)
                .fetchSize(2)
                .username(customerDatabase.getUsername())
                .password(customerDatabase.getPassword())
                .serverTimeZone(ZoneId.of("UTC").toString())
                .createConfig(0);
    }

    private List<String> consumeRecords(
            MySqlSourceReader<SourceRecord> sourceReader, DataType recordType, int changeEventNum)
            throws Exception {
        // Poll all the n records of the single split.
        final SimpleReaderOutput output = new SimpleReaderOutput();
        while (output.getResults().size() < changeEventNum) {
            sourceReader.pollNext(output);
        }
        final RecordsFormatter formatter = new RecordsFormatter(recordType);
        return formatter.format(output.getResults());
    }

    // ------------------------------------------------------------------------
    //  test utilities
    // ------------------------------------------------------------------------
    private static class SimpleReaderOutput implements ReaderOutput<SourceRecord> {

        private final List<SourceRecord> results = new ArrayList<>();

        @Override
        public void collect(SourceRecord record) {
            results.add(record);
        }

        public List<SourceRecord> getResults() {
            return results;
        }

        @Override
        public void collect(SourceRecord record, long timestamp) {
            collect(record);
        }

        @Override
        public void emitWatermark(Watermark watermark) {}

        @Override
        public void markIdle() {}

        @Override
        public void markActive() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SourceOutput<SourceRecord> createOutputForSplit(java.lang.String splitId) {
            return this;
        }

        @Override
        public void releaseOutputForSplit(java.lang.String splitId) {}
    }

    private static class ForwardDeserializeSchema
            implements DebeziumDeserializationSchema<SourceRecord> {

        private static final long serialVersionUID = 1L;

        @Override
        public void deserialize(SourceRecord record, Collector<SourceRecord> out) throws Exception {
            out.collect(record);
        }

        @Override
        public TypeInformation<SourceRecord> getProducedType() {
            return TypeInformation.of(SourceRecord.class);
        }
    }
}
