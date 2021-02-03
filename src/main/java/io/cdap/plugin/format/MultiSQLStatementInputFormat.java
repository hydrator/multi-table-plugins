/*
 * Copyright © 2021 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.plugin.format;

import io.cdap.plugin.DriverCleanup;
import io.cdap.plugin.Drivers;
import io.cdap.plugin.format.error.collector.ErrorCollectingRecordReader;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Input format that reads from multiple tables in a database using JDBC. Similar to Hadoop's DBInputFormat.
 */
public class MultiSQLStatementInputFormat extends InputFormat<NullWritable, RecordWrapper> {
  private static final Logger LOG = LoggerFactory.getLogger(MultiSQLStatementInputFormat.class);

  /**
   * Configure the input format to read tables from a database. Should be called from the mapreduce client.
   *
   * @param hConf       the job configuration
   * @param dbConf      the database conf
   * @param driverClass the JDBC driver class used to communicate with the database
   */
  public static void setInput(Configuration hConf, MultiTableConf dbConf,
                              Class<? extends Driver> driverClass) {
    MultiTableDBConfiguration multiTableDBConf = new MultiTableDBConfiguration(hConf);
    multiTableDBConf.setPluginConfiguration(dbConf);
    multiTableDBConf.setDriver(driverClass.getName());
    multiTableDBConf.setSqlStatements(dbConf.getSqlStatements());
  }

  @Override
  public List<InputSplit> getSplits(JobContext context) throws IOException {
    MultiTableDBConfiguration conf = new MultiTableDBConfiguration(context.getConfiguration());

    List<String> sqlStatements = conf.getSqlStatements();
    List<InputSplit> resultSplits = new ArrayList<>();

    int ix = 1;
    for (String statement : sqlStatements) {
      resultSplits.add(new SQLStatementSplit("Statement #" + ix, statement));
      ix++;
    }

    return resultSplits;
  }

  @Override
  public RecordReader<NullWritable, RecordWrapper> createRecordReader(InputSplit split, TaskAttemptContext context)
    throws IOException {
    MultiTableDBConfiguration multiTableDBConf = new MultiTableDBConfiguration(context.getConfiguration());
    MultiTableConf dbConf = multiTableDBConf.getPluginConf();
    String driverClassname = multiTableDBConf.getDriverName();
    SQLStatementSplit sqlStatementSplit = (SQLStatementSplit) split;
    try {
      Class<? extends Driver> driverClass = (Class<? extends Driver>)
        multiTableDBConf.getConf().getClassLoader().loadClass(driverClassname);
      DriverCleanup driverCleanup = Drivers.ensureJDBCDriverIsAvailable(driverClass, dbConf.getConnectionString());
      return new ErrorCollectingRecordReader(
        multiTableDBConf.getPluginConf().getReferenceName(),
        new SQLStatementRecordReader(dbConf,
                                     dbConf.getTableNameField(),
                                     driverCleanup),
        sqlStatementSplit.getStatementId());
    } catch (ClassNotFoundException e) {
      LOG.error("Could not load jdbc driver class {}", driverClassname);
      throw new IOException(e);
    } catch (IllegalAccessException | InstantiationException | SQLException e) {
      LOG.error("Could not register jdbc driver {}", driverClassname);
      throw new IOException(e);
    }
  }
}
