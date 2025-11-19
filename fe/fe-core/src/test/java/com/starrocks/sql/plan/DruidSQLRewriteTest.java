// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.sql.plan;

import org.junit.Test;

public class DruidSQLRewriteTest extends PlanTestBase {

    @Test
    public void testNonHllCountDistinct() throws Exception {
        String sql = "SELECT v2, count(distinct v1) from t0 group by v2";
        String planFragment = getFragmentPlan(sql);
        assertNotContains(planFragment, "approx_count_distinct");

        connectContext.getSessionVariable().setEnableDruidRewrite(true);
        sql = "SELECT v2, count(distinct v1) from t0 group by v2";
        planFragment = getFragmentPlan(sql);
        assertContains(planFragment, "approx_count_distinct");
        connectContext.getSessionVariable().setEnableDruidRewrite(false);
    }

    @Test
    public void testHllCountDistinct() throws Exception {
        String sql = "SELECT count(distinct h1) from test_object";
        String planFragment = getFragmentPlan(sql);
        assertContains(planFragment, "output: hll_union_agg(9: h1)");

        sql = "SELECT v1, count(distinct h1) from test_object group by v1";
        planFragment = getFragmentPlan(sql);
        assertContains(planFragment, "output: hll_union_agg(9: h1)");

        sql = "SELECT v1, v2, count(distinct h1) from test_object group by v1, v2";
        planFragment = getFragmentPlan(sql);
        assertContains(planFragment, "output: hll_union_agg(9: h1)");

        sql = "SELECT v1, v2, hll_union_agg(h1) from test_object group by v1, v2";
        planFragment = getFragmentPlan(sql);
        assertContains(planFragment, "output: hll_union_agg(9: h1)");

        sql = "SELECT v1, count(distinct h1, h2) from test_object group by v1";
        planFragment = getFragmentPlan(sql);
        assertContains(planFragment, "3:AGGREGATE (merge serialize)");
    }

    @Test
    public void testHllCount() throws Exception {
        String sql = "SELECT count(h1) from test_object";
        String planFragment = getFragmentPlan(sql);
        assertContains(planFragment, "output: count(9: h1)");

        sql = "SELECT v1, count(h1) from test_object group by v1";
        planFragment = getFragmentPlan(sql);
        assertContains(planFragment, "output: count(9: h1)");

        sql = "SELECT v1, v2, count(h1) from test_object group by v1, v2";
        planFragment = getFragmentPlan(sql);
        assertContains(planFragment, "output: count(9: h1)");

        sql = "SELECT v1, count(distinct h1, h2) from test_object group by v1";
        planFragment = getFragmentPlan(sql);
        assertContains(planFragment, "AGGREGATE (merge serialize)");
    }
}
