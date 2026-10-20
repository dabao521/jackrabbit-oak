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
package org.apache.jackrabbit.oak.plugins.index.mongot;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.ByteArrayInputStream;
import java.util.List;

import org.apache.jackrabbit.oak.api.ContentRepository;
import org.apache.jackrabbit.oak.api.Tree;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.index.FullTextIndexCommonTest;
import org.apache.jackrabbit.oak.plugins.index.TestUtil;
import org.apache.jackrabbit.oak.plugins.index.mongot.index.MongoFieldNames;
import org.apache.jackrabbit.oak.plugins.index.search.FulltextIndexConstants;
import org.apache.jackrabbit.oak.plugins.nodetype.write.NodeTypeRegistry;
import org.junit.ClassRule;
import org.junit.Test;

/**
 * End-to-end result correctness for queries whose path and node-type
 * restrictions are pushed into {@code $search.compound.filter} (see
 * MongotQueryTranslator / MongotIndex#applyCompoundFilter). Runs against a
 * real mongod + mongot deployment via {@link MongotSearchConnectionRule};
 * every negative-control node below matches the full-text term but must be
 * excluded by exactly one pushed clause, so a wrong or dropped filter clause
 * fails the assertion.
 */
public class MongotFilterPushdownCompatibilityTest extends FullTextIndexCommonTest {

    @ClassRule
    public static final MongotSearchConnectionRule mongo = new MongotSearchConnectionRule();

    public MongotFilterPushdownCompatibilityTest() {
        indexOptions = new MongotIndexOptions();
    }

    @Override
    protected ContentRepository createRepository() {
        repositoryOptionsUtil = new MongotCommonTestRepositoryBuilder(mongo).build();
        return repositoryOptionsUtil.getOak().createContentRepository();
    }

    @Override
    protected void createTestIndexNode() {
        setTraversalEnabled(false);
    }

    @Override
    protected void assertEventually(Runnable assertion) {
        TestUtil.assertEventually(assertion, 30_000);
    }

    private static final String ASSET_NODE_TYPE = "[dam:Asset]\n"
            + " - * (UNDEFINED) multiple\n"
            + " - * (UNDEFINED)\n"
            + " + * (nt:base) = oak:TestNode VERSION";

    private static Tree typed(Tree node, String primaryType) {
        node.setProperty("jcr:primaryType", primaryType, Type.NAME);
        return node;
    }

    /**
     * The Adobe Oak benchmark shape: full text + ISDESCENDANTNODE + single
     * node type. dam/asset-a is the only node matching all three; asset-b
     * fails the full-text term, file-c fails the node type, asset-d fails the
     * subtree restriction, and dam-other/asset-e is the prefix-sibling trap
     * for the {@code _ancestors} equals clause.
     */
    @Test
    public void descendantNodeTypeAndFullTextRestrictions() throws Exception {
        NodeTypeRegistry.register(root,
                new ByteArrayInputStream(ASSET_NODE_TYPE.getBytes()), "test nodeType");
        Tree index = setup(builder -> {
            // setup() seeds an nt:base rule which would shadow the typed rules below
            // (every type is based on nt:base, and the nt:base rule carries no
            // full-text definition), leaving the query unplannable on this index.
            builder.getBuilderTree().getChild(FulltextIndexConstants.INDEX_RULES)
                    .getChild("nt:base").remove();
            builder.indexRule("dam:Asset").property("propa").nodeScopeIndex().analyzed();
            builder.indexRule("nt:file").property("propa").nodeScopeIndex().analyzed();
        }, idx -> {
        }, "propa");

        Tree test = root.getTree("/").addChild("test");
        typed(test.addChild("dam").addChild("asset-a"), "dam:Asset").setProperty("propa", "oak benchmark");
        typed(test.addChild("dam").addChild("asset-b"), "dam:Asset").setProperty("propa", "different content");
        typed(test.addChild("dam").addChild("file-c"), "nt:file").setProperty("propa", "oak benchmark");
        typed(test.addChild("other").addChild("asset-d"), "dam:Asset").setProperty("propa", "oak benchmark");
        typed(test.addChild("dam-other").addChild("asset-e"), "dam:Asset").setProperty("propa", "oak benchmark");
        root.commit();

        String descendants = "/jcr:root/test/dam//element(*, dam:Asset)[jcr:contains(., 'oak')]";
        String children = "/jcr:root/test/dam/element(*, dam:Asset)[jcr:contains(., 'oak')]";
        assertEventually(() -> {
            String plan = explain(descendants, XPATH);
            assertThat(plan,
                    containsString(indexOptions.getIndexType() + ":" + index.getName()));
            // The plan description carries the aggregation pipeline: the path and
            // node-type restrictions must be inside $search.compound.filter...
            assertThat(plan, containsString("filter=[Document{{equals=Document{{path="
                    + MongoFieldNames.ANCESTORS + ", value=/test/dam}}}}"));
            assertThat(plan, containsString("Document{{in=Document{{path="
                    + MongoFieldNames.PRIMARY_TYPE + ", value=[dam:Asset]}}}}"));
            // ...and no residual $match stage may follow the $search.
            assertThat(plan, not(containsString("$match")));
            assertQuery(descendants, XPATH, List.of("/test/dam/asset-a"));
            assertQuery(children, XPATH, List.of("/test/dam/asset-a"));
        });
    }

    /** Same restrictions without full text: the filter clauses stand alone. */
    @Test
    public void descendantAndNodeTypeRestrictionsWithoutFullText() throws Exception {
        NodeTypeRegistry.register(root,
                new ByteArrayInputStream(ASSET_NODE_TYPE.getBytes()), "test nodeType");
        Tree index = setup(builder -> {
            // setup() seeds an nt:base rule which would shadow the typed rules below
            // (every type is based on nt:base, and the nt:base rule carries no
            // full-text definition), leaving the query unplannable on this index.
            builder.getBuilderTree().getChild(FulltextIndexConstants.INDEX_RULES)
                    .getChild("nt:base").remove();
            builder.indexRule("dam:Asset").property("propa").nodeScopeIndex().analyzed();
            builder.indexRule("nt:file").property("propa").nodeScopeIndex().analyzed();
        }, idx -> {
        }, "propa");

        Tree test = root.getTree("/").addChild("test");
        typed(test.addChild("dam").addChild("asset-a"), "dam:Asset").setProperty("propa", "oak benchmark");
        typed(test.addChild("dam").addChild("file-c"), "nt:file").setProperty("propa", "oak benchmark");
        typed(test.addChild("other").addChild("asset-d"), "dam:Asset").setProperty("propa", "oak benchmark");
        root.commit();

        String descendants = "/jcr:root/test/dam//element(*, dam:Asset)";
        assertEventually(() -> {
            String plan = explain(descendants, XPATH);
            assertThat(plan,
                    containsString(indexOptions.getIndexType() + ":" + index.getName()));
            // Without a lexical $search stage there is nothing to fold the clauses
            // into, so they must be restored as $match stages instead.
            assertThat(plan, containsString("$match"));
            assertQuery(descendants, XPATH, List.of("/test/dam/asset-a"));
        });
    }
}
