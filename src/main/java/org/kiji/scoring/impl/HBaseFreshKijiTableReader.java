/**
 * (c) Copyright 2013 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kiji.scoring.impl;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

import com.google.common.collect.Lists;
import org.apache.hadoop.util.ReflectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.kiji.annotations.ApiAudience;
import org.kiji.mapreduce.produce.KijiProducer;
import org.kiji.schema.*;
import org.kiji.schema.KijiDataRequest.Column;
import org.kiji.schema.impl.HBaseKijiTable;
import org.kiji.schema.util.ResourceUtils;
import org.kiji.scoring.FreshKijiTableReader;
import org.kiji.scoring.KijiFreshnessPolicy;

@ApiAudience.Private
public class HBaseFreshKijiTableReader implements FreshKijiTableReader {
  private static final Logger LOG = LoggerFactory.getLogger(HBaseFreshKijiTableReader.class);


  /** The kiji table instance. */
  private final KijiTable mTable;

  /** Default reader to which to delegate reads. */
  private KijiTableReader mReader;

  /** Map from column names to freshness policies and their required state. */
  private Map<String, KijiFreshnessPolicyRecord> mPolicyRecords;

  /**
   * Creates a new <code>HBaseFreshKijiTableReader</code> instance that sends read requests
   * to an HBase table and performs freshening on the returned data.
   */
  public HBaseFreshKijiTableReader(HBaseKijiTable table) {
    mTable = table;
    // opening a reader retains the table, so we do not need to call retain manually.
    mReader = mTable.openTableReader();
    try{
      final KijiMetaTable metaTable = mTable.getKiji().getMetaTable();
      final Set<String> keySet = metaTable.keySet(mTable.getName());
      // For all keys in the metatable, if those keys are freshness policy entries, cache them locally.
      for (String key: keySet) {
        if (key.startsWith("kiji.scoring.fresh.")) {
          mPolicyRecords.put(key.substring(19), metaTable.getValue(mTable.getName(), key));
        }
      }
    } catch (IOException ioe) {
      // TODO something
    }
  }

  /**
   * Gets all freshness policies from the local cache necessary to validate a given data request.
   *
   * @param dataRequest the data request for which to find freshness policies.
   * @return A map from column name to KijiFreshnessPolicy.
   */
  private Map<String, KijiFreshnessPolicy> getPolicies(KijiDataRequest dataRequest) {
    final Collection<Column> columns = dataRequest.getColumns();
    Map<String, KijiFreshnessPolicy> policies = new HashMap();
    for (Column column: columns) {
      final KijiFreshnessPolicyRecord record = mPolicyRecords.get(column.getName());
      if (record != null) {
        // Instantiate and initialize the policies.
        final KijiFreshnessPolicy policy = ReflectionUtils.newInstance(record.getPolicyClass, null);
        policy.load(record.getPolicyState);
        // Set the producer for the policy
        policies.put(column.getName(), policy);
      }
    }
    return policies;
  }

  /**
   * Executes isFresh on all freshness policies according to their various data requests.
   * Unfinished because the roadmap changed and this method is no longer useful.
   */
  private Map<String, Boolean> checkFreshness(
      Map<String, KijiFreshnessPolicy> policies,
      KijiDataRequest dataRequest) {
    Map<String, Boolean> freshness = new HashMap();
    Map<String, KijiFreshnessPolicy> deferred = new HashMap();
    for (Map.Entry<String, KijiFreshnessPolicy> entry: policies) {
      if (entry.getValue().shouldUseClientDataRequest()) {
        // defer execution and run this later along with other defered isFresh calls against the
        // user data request
        deferred.put(entry.getKey(), entry.getValue());
      } else {
        final boolean fresh =
          entry.getValue().isFresh(mReader.get(entry.getValue().getDataRequest));
        freshness.put(entry.getKey(), fresh);
      }
    }
    final KijiRowData clientData = mReader.get(dataRequest);
    for (Map.Entry<String, KijiFreshnesPolicy> entry: deferred) {
      freshness.put(entry.getKey(), entry.getValue().isFresh(clientData));
    }
  }

  /** {@inheritDoc} */
  @Override
  public KijiRowData get(final EntityId eid, final KijiDataRequest dataRequest) throws IOException {
    final long startTime = System.currentTimeMillis();

    // TODO: use helper methods to separate workflow elements
    // One plan:
    // 1) get the freshness policy(ies) from the cache
    // 2) check if the freshness policy uses the client data request
    // 3) issue the appropriate data request
    // 4) call freshnessPolicy.isFresh()
    // 5) if (isFresh && shouldUseClientDataRequest) return to user
    //    if (isFresh && !shouldUse) send the user request to the table and return
    //    if (!isFresh) run producer then send user request to table and return
    // Another plan:
    // 1) get the freshness policies from the cache
    // 2) branch threads for each policy
    // 3) each thread checks for freshness
    // 4) each thread conditionally runs a producer
    // 5) all threads finish or the timeout occurs, ask each thread for shouldReread(),
    //      if any return true, read from the table and return

    Map<String, KijiFreshnessPolicy> policies = getPolicies(dataRequest);
    // If there are no freshness policies attached to the requested columns, return the requested
    // data.
    if (policies.size() == 0) {
      return mReader.get(eid, dataRequest);
    }

    // 1) check if any freshness policy uses the client data request
    // 2) if one does, start a future to get that data
    // 3) Start a future for each freshness policy that requires the client data.
    // 4) start a future for each freshness policy that uses its own data request (these return
    //    Boolean for whether a reread is required.  If the future has not returned when timeout
    //    occurs, assume reread

    final ExecutorService executor = FreshenerThreadPool.getInstance().get();
    Map<String, KijiFreshnessPolicy> usesClientDataRequest = new HashMap();
    Map<String, KijiFreshnessPolicy> usesOwnDataRequest = new HashMap();
    for (String key: policies.keySet()) {
      if (policies.get(key).shouldUseClientDataRequest()) {
        usesClientDataRequest.put(key, policies.get(key));
      } else {
        usesOwnDataRequest.put(key, policies.get(key));
      }
    }
    final Map<String, KijiFreshnessPolicy> finalUsesClientDataRequest = usesClientDataRequest;
    final Map<String, KijiFreshnessPolicy> finalOwnClientDataRequest = usesOwnDataRequest;
    Future<KijiRowData> clientData = null;
    List<Future<Boolean>> futures = Lists.newArrayList();
    if (usesClientDataRequest.size() != 0) {
      clientData = executor.submit(new Callable<KijiRowData>() {
        public KijiRowData call() throws IOException {
          return mReader.get(eid, dataRequest);
        }
      });
      final Future<KijiRowData> finalClientData = clientData;
      for (final String key: usesClientDataRequest.keySet()) {
        final Future<Boolean> requiresReread = executor.submit(new Callable<Boolean>() {
          public Boolean call() {
            KijiRowData rowData = null;
            try {
              rowData = finalClientData.get();
            } catch (InterruptedException ie) {
            } catch (ExecutionException ee) {
            }
            final boolean isFresh = finalUsesClientDataRequest.get(key).isFresh(rowData);
            if (isFresh) {
              return Boolean.FALSE;
            } else {
              final KijiProducer producer = ReflectionUtils.newInstance(
                  mPolicyRecords.get(entry.getKey()).getProducerClass(), null);
              producer.produce(rowData, CONTEXT);
              return Boolean.TRUE;
              // TODO: add the context
            }
          }
        });
        futures.add(requiresReread);
      }
    }
    for (final Map.Entry<String, KijiFreshnessPolicy> entry: usesOwnDataRequest) {
      final Future<Boolean> requiresReread = executor.submit(new Callable<Boolean>() {
        public Boolean call() {
          final KijiRowData rowData = mReader.get(entry.getValue().getDataRequest());
          final boolean isFresh = entry.getValue().isFresh(rowData);
          if (isFresh) {
            return Boolean.FALSE;
          } else {
            final KijiProducer producer = ReflectionUtils.newInstance(
                mPolicyRecords.get(entry.getKey()).getProducerClass(), null);
            producer.produce(rowData, CONTEXT);
            return Boolean.TRUE;
            // TODO: add the context
          }
        }
      });
      futures.add(requiresReread);
    }


    final List<Future<Boolean>> finalFutures = futures;
    final Future<Boolean> superFuture = executor.submit(new Callable<Boolean>() {
      public Boolean call() {
        boolean retVal = false;
        for (Future<Boolean> future: finalFutures) {
          try {
            retval = future.get() || retVal;
          } catch (InterruptedException ie) {
          } catch (ExecutionException ee) {
          }
        }
        return retVal;
      }
    });

    final long timeOut = 1000;
    try {
      // TODO: setup timeout
      if (superFuture.get(timeOut, TimeUnit.MILLISECONDS)) {
        return mReader.get(dataRequest);
      } else {
        return clientData.get();
      }
    } catch (InterruptedException ie) {
    } catch (ExecutionException ee) {
    } catch (TimeoutException te) {
      return mReader.get(dataRequest);
    }

      //for (Future<Boolean> future: futures) {
//        if (future.isDone() && (future.get() == )

    //TODO: Figure out how to block with timeout on these threads.

    /**
    for (Map.Entry<String, KijiFreshnessPolicy> entry: policies) {
      // TODO: get a thread and start running
      // Add threads to list?
      // Alternately, create a future for each policy
      final Future<Boolean> future = executor.submit(new Callable<boolean>() {
        public Boolean call() {
          final
        }
      }
    }
    for (Thread t: threadList) {
      t.join(timeout);
    }
    for (Thread t: threadList) {
    */

  }

  /** {@inheritDoc} */
  @Override
  public KijiRowData bulkGet(List<EntityId> eids, KijiDataRequest dataRequest) {
    // TODO: this
  }

  /** {@inheritDoc} */
  @Override
  public KijiRowScanner getScanner(KijiDataRequest dataRequest) throws IOException {
    throw new UnsupportedOperationException("Freshening Kiji table reader cannot create a row"
        + " scanner");
  }

  /** {@inheritDoc} */
  @Override
  public KijiRowScanner getScanner(
      KijiDataRequest dataRequest, KijiScannerOptions kijiScannerOptions) throws IOException {
    throw new UnsupportedOperationException("Freshening Kiji table reader cannot create a row"
        + " scanner");
  }

  /** {@inheritDoc} */
  @Override
  public void close() {
    ResourceUtils.releaseOrLog(mTable);
  }
}