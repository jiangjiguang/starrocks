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

package com.starrocks.sql.optimizer.statistics;

import com.starrocks.catalog.Type;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import org.junit.Assert;
import org.junit.Test;

public class StatisticsTest {

    @Test
    public void testGetColumnStatisticExisting() {
        ColumnRefOperator col = new ColumnRefOperator(1, Type.INT, "col1", true);
        ColumnStatistic stats = new ColumnStatistic(0, 100, 0.1, 4, 1000);

        Statistics statistics = Statistics.builder()
                .setOutputRowCount(1000)
                .addColumnStatistic(col, stats)
                .build();

        ColumnStatistic result = statistics.getColumnStatistic(col);
        Assert.assertEquals(stats, result);
    }

    @Test
    public void testGetColumnStatisticMissingReturnsUnknown() {
        // Before the fix, getColumnStatistic would throw StarRocksPlannerException when
        // the column was not in the map. After the fix, it returns ColumnStatistic.unknown()
        // so that CBO statistics derivation can degrade gracefully instead of failing the query.
        ColumnRefOperator present = new ColumnRefOperator(1, Type.INT, "col1", true);
        ColumnRefOperator missing = new ColumnRefOperator(2, Type.BIGINT, "cast_col", true);
        ColumnStatistic stats = new ColumnStatistic(0, 100, 0.1, 4, 1000);

        Statistics statistics = Statistics.builder()
                .setOutputRowCount(1000)
                .addColumnStatistic(present, stats)
                .build();

        // Existing column returns its actual statistics
        ColumnStatistic result = statistics.getColumnStatistic(present);
        Assert.assertEquals(stats, result);

        // Missing column returns unknown instead of throwing exception
        ColumnStatistic missingResult = statistics.getColumnStatistic(missing);
        Assert.assertTrue(missingResult.isUnknown());
    }

    @Test
    public void testGetColumnStatisticEmptyStatistics() {
        ColumnRefOperator col = new ColumnRefOperator(1, Type.VARCHAR, "col1", true);

        Statistics statistics = Statistics.builder()
                .setOutputRowCount(100)
                .build();

        ColumnStatistic result = statistics.getColumnStatistic(col);
        Assert.assertTrue(result.isUnknown());
    }
}
