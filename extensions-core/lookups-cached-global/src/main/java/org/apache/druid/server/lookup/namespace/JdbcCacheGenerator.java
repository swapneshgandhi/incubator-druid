/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.server.lookup.namespace;

import com.google.common.base.Strings;
import org.apache.druid.java.util.common.JodaUtils;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.query.lookup.namespace.CacheGenerator;
import org.apache.druid.query.lookup.namespace.JdbcExtractionNamespace;
import org.apache.druid.server.lookup.namespace.cache.CacheScheduler;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.StatementContext;
import org.skife.jdbi.v2.tweak.HandleCallback;
import org.skife.jdbi.v2.tweak.ResultSetMapper;
import org.skife.jdbi.v2.util.TimestampMapper;

import javax.annotation.Nullable;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 *
 */
public final class JdbcCacheGenerator implements CacheGenerator<JdbcExtractionNamespace>
{
  private static final Logger LOG = new Logger(JdbcCacheGenerator.class);
  private final ConcurrentMap<CacheScheduler.EntryImpl<JdbcExtractionNamespace>, DBI> dbiCache =
      new ConcurrentHashMap<>();

  @Override
  @Nullable
  public CacheScheduler.VersionedCache generateCache(
      final JdbcExtractionNamespace namespace,
      final CacheScheduler.EntryImpl<JdbcExtractionNamespace> entryId,
      final String lastVersion,
      final CacheScheduler scheduler
  )
  {
    final long lastCheck = lastVersion == null ? JodaUtils.MIN_INSTANT : Long.parseLong(lastVersion);
    final Long lastDBUpdate = lastUpdates(entryId, namespace);
    if (lastDBUpdate != null && lastDBUpdate <= lastCheck) {
      return null;
    }
    final long dbQueryStart = System.currentTimeMillis();
    final DBI dbi = ensureDBI(entryId, namespace);
    final String table = namespace.getTable();
    final String filter = namespace.getFilter();
    final String valueColumn = namespace.getValueColumn();
    final String keyColumn = namespace.getKeyColumn();

    LOG.debug("Updating %s", entryId);

    boolean doIncrementalLoad = lastDBUpdate != null && !Strings.isNullOrEmpty(namespace.getTsColumn())
            && lastVersion != null;

    String sqlQuery = doIncrementalLoad ?
            buildIncrementalLookupQuery(table, filter, keyColumn, valueColumn, namespace.getTsColumn(), lastCheck) :
            buildLookupQuery(table, filter, keyColumn, valueColumn);

    final List<Pair<String, String>> pairs = dbi.withHandle(
        new HandleCallback<List<Pair<String, String>>>()
        {
          @Override
          public List<Pair<String, String>> withHandle(Handle handle)
          {
            return handle
                .createQuery(
                    sqlQuery
                ).map(
                    new ResultSetMapper<Pair<String, String>>()
                    {
                      @Override
                      public Pair<String, String> map(
                          final int index,
                          final ResultSet r,
                          final StatementContext ctx
                      ) throws SQLException
                      {
                        return new Pair<>(r.getString(keyColumn), r.getString(valueColumn));
                      }
                    }
                ).list();
          }
        }
    );
    final String newVersion;
    CacheScheduler.VersionedCache versionedCache = null;
    try {
      if (doIncrementalLoad) {
        newVersion = StringUtils.format("%d", lastDBUpdate);
        ConcurrentMap<String, String> newCachedEntries = new ConcurrentHashMap<>();
        LOG.info(" found %s new incremental entries", pairs.size());
        for (Pair<String, String> pair : pairs) {
          newCachedEntries.put(pair.lhs, pair.rhs);
        }
        versionedCache = entryId.createFromExisitngCache(entryId, newVersion, newCachedEntries);
        return versionedCache;
      } else {
        LOG.info("Not doing incremental load since lastDBUpdate: %s, namespace.getTsColumn() %s, lastVersion %s," +
              " lastCheck %s", lastDBUpdate, namespace.getTsColumn(), lastVersion, lastCheck);
        if (lastDBUpdate != null){
          // for incremental lookups this will set the new version to last db update,
          // so that during next load we will read all keys that were after the db update.
          newVersion = StringUtils.format("%d", lastDBUpdate);
        }
        else{
          newVersion = StringUtils.format("%d", dbQueryStart);
        }
        versionedCache = scheduler.createVersionedCache(entryId, newVersion);
        final Map<String, String> cache = versionedCache.getCache();
        for (Pair<String, String> pair : pairs) {
          cache.put(pair.lhs, pair.rhs);
        }
        LOG.info("Finished loading %d values for %s", cache.size(), entryId);
        return versionedCache;
      }
    }
    catch (Throwable t) {
      try {
        if (versionedCache != null) {
          versionedCache.close();
        }
      }
      catch (Exception e) {
        t.addSuppressed(e);
      }
      throw t;
    }
  }

  private String buildLookupQuery(String table, String filter, String keyColumn, String valueColumn)
  {
    if (Strings.isNullOrEmpty(filter)) {
      return StringUtils.format(
          "SELECT %s, %s FROM %s WHERE %s IS NOT NULL",
          keyColumn,
          valueColumn,
          table,
          valueColumn
      );
    }

    return StringUtils.format(
        "SELECT %s, %s FROM %s WHERE %s AND %s IS NOT NULL",
        keyColumn,
        valueColumn,
        table,
        filter,
        valueColumn
    );
  }

  private String buildIncrementalLookupQuery(String table, String filter, String keyColumn, String valueColumn, String tsColumn, Long lastLoadTs)
  {
    if (Strings.isNullOrEmpty(filter)) {
      return StringUtils.format(
          "SELECT %s, %s FROM %s WHERE %s >= '%s' AND %s IS NOT NULL",
          keyColumn,
          valueColumn,
          table,
          tsColumn,
          new Timestamp(lastLoadTs).toString(),
          valueColumn
      );
    }

    return StringUtils.format(
          "SELECT %s, %s FROM %s WHERE %s AND %s >= '%s' AND %s IS NOT NULL",
          keyColumn,
          valueColumn,
          table,
          filter,
          tsColumn,
          new Timestamp(lastLoadTs).toString(),
          valueColumn
    );
  }

  private DBI ensureDBI(CacheScheduler.EntryImpl<JdbcExtractionNamespace> id, JdbcExtractionNamespace namespace)
  {
    final CacheScheduler.EntryImpl<JdbcExtractionNamespace> key = id;
    DBI dbi = null;
    if (dbiCache.containsKey(key)) {
      dbi = dbiCache.get(key);
    }
    if (dbi == null) {
      final DBI newDbi = new DBI(
          namespace.getConnectorConfig().getConnectURI(),
          namespace.getConnectorConfig().getUser(),
          namespace.getConnectorConfig().getPassword()
      );
      dbiCache.putIfAbsent(key, newDbi);
      dbi = dbiCache.get(key);
    }
    return dbi;
  }

  private Long lastUpdates(CacheScheduler.EntryImpl<JdbcExtractionNamespace> id, JdbcExtractionNamespace namespace)
  {
    final DBI dbi = ensureDBI(id, namespace);
    final String table = namespace.getTable();
    final String tsColumn = namespace.getTsColumn();
    if (Strings.isNullOrEmpty(tsColumn)) {
      return null;
    }
    final Timestamp update = dbi.withHandle(
        new HandleCallback<Timestamp>()
        {

          @Override
          public Timestamp withHandle(Handle handle)
          {
            final String query = StringUtils.format(
                "SELECT MAX(%s) FROM %s",
                tsColumn, table
            );
            return handle
                .createQuery(query)
                .map(TimestampMapper.FIRST)
                .first();
          }
        }
    );
    return update.getTime();
  }
}
