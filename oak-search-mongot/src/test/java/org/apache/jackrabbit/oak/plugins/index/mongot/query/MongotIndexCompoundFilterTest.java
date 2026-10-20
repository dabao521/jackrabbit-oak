/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.plugins.index.mongot.query;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.apache.jackrabbit.oak.plugins.index.mongot.index.MongoFieldNames;
import org.apache.jackrabbit.oak.query.index.FilterImpl;
import org.apache.jackrabbit.oak.spi.query.Filter;
import org.bson.Document;

import org.junit.Test;

/**
 * Validates that translated filter restrictions are pushed into the
 * {@code $search} operator's {@code compound.filter} instead of surviving as
 * {@code $match} stages after {@code $search}, where mongod would have to
 * re-read and discard every matching document.
 */
public class MongotIndexCompoundFilterTest {

    /**
     * The Oak query shape behind the Adobe Oak benchmark: a full-text term
     * restricted to a subtree (ISDESCENDANTNODE) and a single node type. All of
     * it must end up inside one $search operator, with no residual $match.
     */
    @Test
    public void foldsPathAndNodeTypeRestrictionsIntoCompoundFilter() {
        Filter filter = mock(Filter.class);
        when(filter.getPath()).thenReturn("/content/dam");
        when(filter.getPathRestriction()).thenReturn(Filter.PathRestriction.ALL_CHILDREN);
        when(filter.getPropertyRestrictions()).thenReturn(List.of());
        when(filter.matchesAllTypes()).thenReturn(false);
        when(filter.getPrimaryTypes()).thenReturn(Set.of("dam:Asset"));
        when(filter.getMixinTypes()).thenReturn(Set.of());

        Document textOperator = new Document("text",
                new Document("path", MongoFieldNames.FULLTEXT).append("query", "oak"));
        Document searchOperator = new Document("compound",
                new Document("must", List.of(textOperator)));

        MongotQueryTranslation filters = MongotQueryTranslator.translateFilter(filter);
        Document folded = MongotIndex.applyCompoundFilter(searchOperator, filters,
                new Document());

        assertEquals(new Document("compound", new Document("must", List.of(textOperator))
                        .append("filter", List.of(
                                new Document("equals", new Document("path",
                                        MongoFieldNames.ANCESTORS).append("value", "/content/dam")),
                                new Document("in", new Document("path",
                                        MongoFieldNames.PRIMARY_TYPE)
                                        .append("value", List.of("dam:Asset")))))),
                folded);
        assertEquals("the pushed-down clauses must not survive as a residual $match",
                List.of(), filters.pipeline());
    }

    @Test
    public void wrapsNonCompoundSearchOperatorWithFilterClauses() {
        FilterImpl filter = FilterImpl.newTestInstance();
        filter.restrictPath("/content/a", Filter.PathRestriction.EXACT);

        Document textOperator = new Document("text",
                new Document("path", MongoFieldNames.FULLTEXT).append("query", "oak"));

        Document folded = MongotIndex.applyCompoundFilter(textOperator,
                MongotQueryTranslator.translateFilter(filter), new Document());

        assertEquals(new Document("compound", new Document("must", List.of(textOperator))
                        .append("filter", List.of(new Document("equals",
                                new Document("path", MongoFieldNames.PATH)
                                        .append("value", "/content/a"))))),
                folded);
    }

    /** The sort-guard $exists restrictions become exists filter clauses. */
    @Test
    public void foldsSortGuardExistsRestrictionsIntoCompoundFilter() {
        Document textOperator = new Document("text",
                new Document("path", MongoFieldNames.FULLTEXT).append("query", "oak"));
        Document searchOperator = new Document("compound",
                new Document("must", List.of(textOperator)));

        Document folded = MongotIndex.applyCompoundFilter(searchOperator,
                MongotQueryTranslation.supported(null, List.of()),
                new Document("typed." + MongoFieldNames.encodeProperty("jcr:created"),
                        new Document("$exists", true)));

        assertEquals(new Document("compound", new Document("must", List.of(textOperator))
                        .append("filter", List.of(new Document("exists",
                                new Document("path", "typed."
                                        + MongoFieldNames.encodeProperty("jcr:created")))))),
                folded);
    }

    @Test
    public void returnsSearchOperatorUnchangedWhenNothingToPush() {
        Document textOperator = new Document("text",
                new Document("path", MongoFieldNames.FULLTEXT).append("query", "oak"));

        Document folded = MongotIndex.applyCompoundFilter(textOperator,
                MongotQueryTranslation.supported(null, List.of()), new Document());

        assertEquals(textOperator, folded);
    }
}
