/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.search.query;

import org.apache.lucene.search.Collector;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MultiCollector;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SimpleCollector;
import org.apache.lucene.search.Weight;
import org.opensearch.common.lucene.MinimumScoreCollector;
import org.opensearch.common.lucene.search.FilteredCollector;
import org.opensearch.search.profile.query.InternalProfileCollector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.opensearch.search.profile.query.CollectorResult.REASON_SEARCH_MIN_SCORE;
import static org.opensearch.search.profile.query.CollectorResult.REASON_SEARCH_MULTI;
import static org.opensearch.search.profile.query.CollectorResult.REASON_SEARCH_POST_FILTER;
import static org.opensearch.search.profile.query.CollectorResult.REASON_SEARCH_TERMINATE_AFTER_COUNT;

abstract class QueryCollectorContext {
    private static final Collector EMPTY_COLLECTOR = new SimpleCollector() {
        @Override
        public void collect(int doc) {
        }

        @Override
        public ScoreMode scoreMode() {
            return ScoreMode.COMPLETE_NO_SCORES;
        }
    };

    private String profilerName;

    QueryCollectorContext(String profilerName) {
        this.profilerName = profilerName;
    }

    /**
     * Creates a collector that delegates documents to the provided <code>in</code> collector.
     * @param in The delegate collector
     */
    abstract Collector create(Collector in) throws IOException;

    /**
     * Wraps this collector with a profiler
     */
    protected InternalProfileCollector createWithProfiler(InternalProfileCollector in) throws IOException {
        final Collector collector = create(in);
        return new InternalProfileCollector(collector, profilerName, in != null ? Collections.singletonList(in) : Collections.emptyList());
    }

    /**
     * Post-process <code>result</code> after search execution.
     *
     * @param result The query search result to populate
     */
    void postProcess(QuerySearchResult result) throws IOException {}

    /**
     * Creates the collector tree from the provided <code>collectors</code>
     * @param collectors Ordered list of collector context
     */
    static Collector createQueryCollector(List<QueryCollectorContext> collectors) throws IOException {
        Collector collector = null;
        for (QueryCollectorContext ctx : collectors) {
            collector = ctx.create(collector);
        }
        return collector;
    }

    /**
     * Creates the collector tree from the provided <code>collectors</code> and wraps each collector with a profiler
     * @param collectors Ordered list of collector context
     */
    static InternalProfileCollector createQueryCollectorWithProfiler(List<QueryCollectorContext> collectors) throws IOException {
        InternalProfileCollector collector = null;
        for (QueryCollectorContext ctx : collectors) {
            collector = ctx.createWithProfiler(collector);
        }
        return collector;
    }

    /**
     * Filters documents with a query score greater than <code>minScore</code>
     * @param minScore The minimum score filter
     */
    static QueryCollectorContext createMinScoreCollectorContext(float minScore) {
        return new QueryCollectorContext(REASON_SEARCH_MIN_SCORE) {
            @Override
            Collector create(Collector in) {
                return new MinimumScoreCollector(in, minScore);
            }
        };
    }

    /**
     * Filters documents based on the provided <code>query</code>
     */
    static QueryCollectorContext createFilteredCollectorContext(IndexSearcher searcher, Query query) {
        return new QueryCollectorContext(REASON_SEARCH_POST_FILTER) {
            @Override
            Collector create(Collector in ) throws IOException {
                final Weight filterWeight = searcher.createWeight(searcher.rewrite(query), ScoreMode.COMPLETE_NO_SCORES, 1f);
                return new FilteredCollector(in, filterWeight);
            }
        };
    }

    /**
     * Creates a multi collector from the provided <code>subs</code>
     */
    static QueryCollectorContext createMultiCollectorContext(Collection<Collector> subs) {
        return new QueryCollectorContext(REASON_SEARCH_MULTI) {
            @Override
            Collector create(Collector in) {
                List<Collector> subCollectors = new ArrayList<> ();
                subCollectors.add(in);
                subCollectors.addAll(subs);
                return MultiCollector.wrap(subCollectors);
            }

            @Override
            protected InternalProfileCollector createWithProfiler(InternalProfileCollector in) {
                final List<InternalProfileCollector> subCollectors = new ArrayList<> ();
                subCollectors.add(in);
                if (subs.stream().anyMatch((col) -> col instanceof InternalProfileCollector == false)) {
                    throw new IllegalArgumentException("non-profiling collector");
                }
                for (Collector collector : subs) {
                    subCollectors.add((InternalProfileCollector) collector);
                }
                final Collector collector = MultiCollector.wrap(subCollectors);
                return new InternalProfileCollector(collector, REASON_SEARCH_MULTI, subCollectors);
            }
        };
    }

    /**
     * Creates collector limiting the collection to the first <code>numHits</code> documents
     */
    static QueryCollectorContext createEarlyTerminationCollectorContext(int numHits) {
        return new QueryCollectorContext(REASON_SEARCH_TERMINATE_AFTER_COUNT) {
            private Collector collector;

            /**
             * Creates a {@link MultiCollector} to ensure that the {@link EarlyTerminatingCollector}
             * can terminate the collection independently of the provided <code>in</code> {@link Collector}.
             */
            @Override
            Collector create(Collector in) {
                assert collector == null;

                List<Collector> subCollectors = new ArrayList<> ();
                subCollectors.add(new EarlyTerminatingCollector(EMPTY_COLLECTOR, numHits, true));
                subCollectors.add(in);
                this.collector = MultiCollector.wrap(subCollectors);
                return collector;
            }
        };
    }
}
