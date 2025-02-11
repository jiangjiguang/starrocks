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

package com.starrocks.sql.optimizer.rule;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.starrocks.analysis.BinaryType;
import com.starrocks.analysis.IntLiteral;
import com.starrocks.analysis.LiteralExpr;
import com.starrocks.analysis.StringLiteral;
import com.starrocks.catalog.FunctionSet;
import com.starrocks.catalog.Type;
import com.starrocks.common.AnalysisException;
import com.starrocks.sql.optimizer.operator.scalar.BinaryPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.CallOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.CompoundPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
import com.starrocks.sql.optimizer.operator.scalar.InPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.IsNullPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rule.transformation.HiveMultiPartitionPruner;
import com.starrocks.sql.optimizer.rule.transformation.ListPartitionPruner;
import org.junit.Assert;
import org.junit.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

public class HiveMultiPartitionPrunerTest {
    private final ColumnRefOperator yearColumn = new ColumnRefOperator(1, Type.STRING, "year", true);
    private final ColumnRefOperator monthColumn = new ColumnRefOperator(2, Type.STRING, "month", true);
    private final ColumnRefOperator dayColumn = new ColumnRefOperator(3, Type.STRING, "day", true);
    private final ColumnRefOperator intColumn = new ColumnRefOperator(4, Type.INT, "int_col", true);
    private final CallOperator dateColumn = new CallOperator(FunctionSet.CONCAT_WS, Type.STRING,
            Lists.newArrayList(ConstantOperator.createVarchar("-"), yearColumn, monthColumn, dayColumn));

    // year=2021/month=01/day=01/int_col=0   0
    // year=2021/month=01/day=02/int_col=1   1
    // year=2021/month=01/day=03/int_col=2   2
    // year=2021/month=01/day=04/int_col=0   3
    // year=2021/month=01/day=05/int_col=1   4
    // year=2021/month=01/day=06/int_col=2   5
    private HiveMultiPartitionPruner initPruner(List<ScalarOperator> conjuncts) {
        // partition id -> partition path
        Map<Long, String> idToPartitionPathMap = Maps.newHashMap();
        idToPartitionPathMap.put(0L, "year=2021/month=01/day=01/int_col=0");
        idToPartitionPathMap.put(1L, "year=2021/month=01/day=02/int_col=1");
        idToPartitionPathMap.put(2L, "year=2021/month=01/day=03/int_col=2");
        idToPartitionPathMap.put(3L, "year=2021/month=01/day=04/int_col=0");
        idToPartitionPathMap.put(4L, "year=2021/month=01/day=05/int_col=1");
        idToPartitionPathMap.put(5L, "year=2021/month=01/day=06/int_col=2");

        // column -> partition values
        Map<ColumnRefOperator, ConcurrentNavigableMap<LiteralExpr, Set<Long>>> columnToPartitionValuesMap = Maps.newHashMap();

        ConcurrentNavigableMap<LiteralExpr, Set<Long>> yearPartitionValuesMap = new ConcurrentSkipListMap<>();
        yearPartitionValuesMap.put(new StringLiteral("2021"), Sets.newHashSet(0L, 1L, 2L, 3L, 4L, 5L));
        columnToPartitionValuesMap.put(yearColumn, yearPartitionValuesMap);

        ConcurrentNavigableMap<LiteralExpr, Set<Long>> monthPartitionValuesMap = new ConcurrentSkipListMap<>();
        monthPartitionValuesMap.put(new StringLiteral("01"), Sets.newHashSet(0L, 1L, 2L, 3L, 4L, 5L));
        columnToPartitionValuesMap.put(monthColumn, monthPartitionValuesMap);

        ConcurrentNavigableMap<LiteralExpr, Set<Long>> dayPartitionValuesMap = new ConcurrentSkipListMap<>();
        dayPartitionValuesMap.put(new StringLiteral("01"), Sets.newHashSet(0L));
        dayPartitionValuesMap.put(new StringLiteral("02"), Sets.newHashSet(1L));
        dayPartitionValuesMap.put(new StringLiteral("03"), Sets.newHashSet(2L));
        dayPartitionValuesMap.put(new StringLiteral("04"), Sets.newHashSet(3L));
        dayPartitionValuesMap.put(new StringLiteral("05"), Sets.newHashSet(4L));
        dayPartitionValuesMap.put(new StringLiteral("06"), Sets.newHashSet(5L));
        columnToPartitionValuesMap.put(dayColumn, dayPartitionValuesMap);


        ConcurrentNavigableMap<LiteralExpr, Set<Long>> intPartitionValuesMap = new ConcurrentSkipListMap<>();
        columnToPartitionValuesMap.put(intColumn, intPartitionValuesMap);
        intPartitionValuesMap.put(new IntLiteral(0, Type.INT), Sets.newHashSet(0L, 3L));
        intPartitionValuesMap.put(new IntLiteral(1, Type.INT), Sets.newHashSet(1L, 4L));
        intPartitionValuesMap.put(new IntLiteral(2, Type.INT), Sets.newHashSet(2L, 5L));

        // column -> null partitions
        Map<ColumnRefOperator, Set<Long>> columnToNullPartitions = Maps.newHashMap();
        columnToNullPartitions.put(yearColumn, Sets.newHashSet());
        columnToNullPartitions.put(monthColumn, Sets.newHashSet());
        columnToNullPartitions.put(dayColumn, Sets.newHashSet());
        columnToNullPartitions.put(intColumn, Sets.newHashSet());

        return new HiveMultiPartitionPruner(conjuncts, idToPartitionPathMap,
                columnToNullPartitions, columnToPartitionValuesMap);
    }

    private ListPartitionPruner initListPruner(HiveMultiPartitionPruner pruner) {
        return new ListPartitionPruner(pruner.getColumnToPartitionValuesMap(),
                pruner.getColumnToNullPartitions(), pruner.getPartitionConjuncts(), null);
    }


    @Test
    public void testEmptyConjunctsOrPartitionColumns() throws AnalysisException {
        List<ScalarOperator> conjuncts = new ArrayList<>();
        HiveMultiPartitionPruner pruner = initPruner(conjuncts);
        Assert.assertNull(pruner.prune());
        pruner.getColumnToPartitionValuesMap().clear();
        pruner.getColumnToNullPartitions().clear();
        Assert.assertNull(pruner.prune());
    }


    @Test
    public void testBinaryPredicate() throws AnalysisException {
        // concat_ws('-', year, month, day) = "2021-01-01"
        List<ScalarOperator> conjuncts = new ArrayList<>();
        conjuncts.add(new BinaryPredicateOperator(BinaryType.EQ, dateColumn,
                ConstantOperator.createDate(LocalDateTime.of(2021, 1, 1, 0, 0, 0))));
        HiveMultiPartitionPruner pruner = initPruner(conjuncts);
        Assert.assertEquals(Lists.newArrayList(0L), pruner.prune());

        // concat_ws('-', year, month, day) < "2021-01-02"
        conjuncts = new ArrayList<>();
        conjuncts.add(new BinaryPredicateOperator(BinaryType.LT, dateColumn,
                ConstantOperator.createDate(LocalDateTime.of(2021, 1, 2, 0, 0, 0))));
        pruner = initPruner(conjuncts);
        Assert.assertEquals(Lists.newArrayList(0L), pruner.prune());

        // concat_ws('-', year, month, day) >= "2021-01-03" and concat_ws('-', year, month, day) <= "2021-01-03"
        conjuncts = new ArrayList<>();
        conjuncts.add(new BinaryPredicateOperator(BinaryType.GE, dateColumn,
                ConstantOperator.createDate(LocalDateTime.of(2021, 1, 3, 0, 0, 0))));
        conjuncts.add(new BinaryPredicateOperator(BinaryType.LE, dateColumn,
                ConstantOperator.createDate(LocalDateTime.of(2021, 1, 3, 0, 0, 0))));
        pruner = initPruner(conjuncts);
        Assert.assertEquals(Lists.newArrayList(2L), pruner.prune());

        // concat_ws('-', year, month, day) > "2021-01-03"
        conjuncts = new ArrayList<>();
        conjuncts.add(new BinaryPredicateOperator(BinaryType.GT, dateColumn,
                ConstantOperator.createDate(LocalDateTime.of(2021, 1, 3, 0, 0, 0))));
        pruner = initPruner(conjuncts);
        Assert.assertEquals(Lists.newArrayList(3L, 4L, 5L), pruner.prune());

        // concat_ws('-', year, month, day) <= "2020-12-31"
        conjuncts = new ArrayList<>();
        conjuncts.add(new BinaryPredicateOperator(BinaryType.LE, dateColumn,
                ConstantOperator.createDate(LocalDateTime.of(2020, 12, 31, 0, 0, 0))));
        pruner = initPruner(conjuncts);
        Assert.assertNull(pruner.prune());

        // int_col = 0
        conjuncts = new ArrayList<>();
        conjuncts.add(new BinaryPredicateOperator(BinaryType.EQ, intColumn,
                ConstantOperator.createInt(0)));
        pruner = initPruner(conjuncts);
        Assert.assertNull(pruner.prune());
        ListPartitionPruner listPartitionPruner = initListPruner(pruner);
        List<Long> partIds = listPartitionPruner.prune();
        Assert.assertEquals(Lists.newArrayList(0L, 3L), partIds);

        // int_col >= 1
        conjuncts = new ArrayList<>();
        conjuncts.add(new BinaryPredicateOperator(BinaryType.GE, intColumn,
                ConstantOperator.createInt(1)));
        pruner = initPruner(conjuncts);
        listPartitionPruner = initListPruner(pruner);
        Assert.assertEquals(Lists.newArrayList(1L, 2L, 4L, 5L), listPartitionPruner.prune());

        // concat_ws('-', year, month, day) >= "2021-01-02" and int_col < 2
        conjuncts = new ArrayList<>();
        conjuncts.add(new BinaryPredicateOperator(BinaryType.GE, dateColumn,
                ConstantOperator.createDate(LocalDateTime.of(2021, 1, 2, 0, 0, 0))));
        conjuncts.add(new BinaryPredicateOperator(BinaryType.LT, intColumn,
                ConstantOperator.createInt(2)));
        pruner = initPruner(conjuncts);
        listPartitionPruner = initListPruner(pruner);
        partIds = listPartitionPruner.prune();
        Assert.assertEquals(Lists.newArrayList(0L, 1L, 3L, 4L), partIds);

        conjuncts = listPartitionPruner.getNoEvalConjuncts();
        pruner = initPruner(conjuncts);
        List<Long> prunedPartIds = pruner.prune();
        Assert.assertEquals(Lists.newArrayList(1L, 2L, 3L, 4L, 5L), prunedPartIds);
        partIds.retainAll(prunedPartIds);
        Assert.assertEquals(Lists.newArrayList(1L, 3L, 4L), partIds);


        // concat_ws('-', year, month, day) >= "2021-01-02" and concat_ws('-', year, month, day) <= "2021-01-05"
        conjuncts = new ArrayList<>();
        conjuncts.add(new BinaryPredicateOperator(BinaryType.GE, dateColumn,
                ConstantOperator.createDate(LocalDateTime.of(2021, 1, 2, 0, 0, 0))));
        conjuncts.add(new BinaryPredicateOperator(BinaryType.LE, dateColumn,
                ConstantOperator.createDate(LocalDateTime.of(2021, 1, 5, 0, 0, 0))));
        pruner = initPruner(conjuncts);
        listPartitionPruner = initListPruner(pruner);
        partIds = listPartitionPruner.prune();
        Assert.assertNull(partIds);

        conjuncts = listPartitionPruner.getNoEvalConjuncts();
        pruner = initPruner(conjuncts);
        prunedPartIds = pruner.prune();
        Assert.assertEquals(Lists.newArrayList(1L, 2L, 3L, 4L), prunedPartIds);
    }


    @Test
    public void testInPredicate() throws AnalysisException {
        // concat_ws('-', year, month, day) in ("2021-01-01")
        List<ScalarOperator> conjuncts = new ArrayList<>();
        conjuncts.add(new InPredicateOperator(dateColumn,
                ConstantOperator.createDate(LocalDateTime.of(2021, 1, 1, 0, 0, 0))));
        HiveMultiPartitionPruner pruner = initPruner(conjuncts);
        Assert.assertEquals(Lists.newArrayList(0L), pruner.prune());

        // concat_ws('-', year, month, day) in ("2021-01-02", "2021-01-03")
        conjuncts = new ArrayList<>();
        conjuncts.add(new InPredicateOperator(dateColumn,
                ConstantOperator.createDate(LocalDateTime.of(2021, 1, 2, 0, 0, 0)),
                ConstantOperator.createDate(LocalDateTime.of(2021, 1, 3, 0, 0, 0))));
        pruner = initPruner(conjuncts);
        Assert.assertEquals(Lists.newArrayList(1L, 2L), pruner.prune());

        // concat_ws('-', year, month, day) in ("2021-01-03", "2021-01-04")
        conjuncts = new ArrayList<>();
        conjuncts.add(new InPredicateOperator(false, Lists.newArrayList(dateColumn,
                ConstantOperator.createDate(LocalDateTime.of(2021, 1, 3, 0, 0, 0)),
                ConstantOperator.createDate(LocalDateTime.of(2021, 1, 4, 0, 0, 0)))));
        pruner = initPruner(conjuncts);
        Assert.assertEquals(Lists.newArrayList(2L, 3L), pruner.prune());

        // concat_ws('-', year, month, day) in ("2021-01-04")
        conjuncts = new ArrayList<>();
        conjuncts.add(new InPredicateOperator(false, Lists.newArrayList(dateColumn,
                ConstantOperator.createDate(LocalDateTime.of(2021, 1, 4, 0, 0, 0)))));
        pruner = initPruner(conjuncts);
        Assert.assertEquals(Lists.newArrayList(3L), pruner.prune());

        // concat_ws('-', year, month, day) not in ("2021-01-04")
        conjuncts = new ArrayList<>();
        conjuncts.add(new InPredicateOperator(true, Lists.newArrayList(dateColumn,
                ConstantOperator.createDate(LocalDateTime.of(2021, 1, 4, 0, 0, 0)))));
        pruner = initPruner(conjuncts);
        Assert.assertEquals(Lists.newArrayList(0L, 1L, 2L, 4L, 5L), pruner.prune());

        // int_col in (0)
        conjuncts = new ArrayList<>();
        conjuncts.add(new InPredicateOperator(false, Lists.newArrayList(intColumn, ConstantOperator.createInt(0))));
        pruner = initPruner(conjuncts);
        ListPartitionPruner listPartitionPruner = initListPruner(pruner);
        Assert.assertEquals(Lists.newArrayList(0L, 3L), listPartitionPruner.prune());
        Assert.assertNull(pruner.prune());


        // concat_ws('-', year, month, day) not in ("2021-01-04") AND int_col not in (1, 2)
        conjuncts = new ArrayList<>();
        conjuncts.add(new InPredicateOperator(true, dateColumn,
                ConstantOperator.createDate(LocalDateTime.of(2021, 1, 4, 0, 0, 0))));
        conjuncts.add(new InPredicateOperator(true,
                Lists.newArrayList(intColumn, ConstantOperator.createInt(1), ConstantOperator.createInt(2))));
        pruner = initPruner(conjuncts);
        listPartitionPruner = initListPruner(pruner);
        List<Long> normalPartIds = listPartitionPruner.prune();
        Assert.assertEquals(Lists.newArrayList(0L, 3L), normalPartIds);
        conjuncts = listPartitionPruner.getNoEvalConjuncts();
        pruner = initPruner(conjuncts);
        List<Long> multiPartIds = pruner.prune();
        Assert.assertEquals(Lists.newArrayList(0L, 1L, 2L, 4L, 5L), multiPartIds);
        normalPartIds.retainAll(multiPartIds);
        Assert.assertEquals(Lists.newArrayList(0L), normalPartIds);
    }


    @Test
    public void testIsNullPredicate() throws AnalysisException {
        List<Long> notNullPartitions = Lists.newArrayList(0L, 1L, 2L, 3L, 4L, 5L);

        // concat_ws('-', year, month, day) is null
        List<ScalarOperator> conjuncts = new ArrayList<>();
        conjuncts.add(new IsNullPredicateOperator(false, dateColumn));
        HiveMultiPartitionPruner pruner = initPruner(conjuncts);
        Assert.assertNull(pruner.prune());

        // concat_ws('-', year, month, day) is not null
        conjuncts = new ArrayList<>();
        conjuncts.add(new IsNullPredicateOperator(true, dateColumn));
        pruner = initPruner(conjuncts);
        Assert.assertEquals(notNullPartitions, pruner.prune());

        // int_col is null
        conjuncts = new ArrayList<>();
        conjuncts.add(new IsNullPredicateOperator(false, intColumn));
        pruner = initPruner(conjuncts);
        Assert.assertNull(pruner.prune());
        ListPartitionPruner listPartitionPruner = initListPruner(pruner);
        Assert.assertEquals(Lists.newArrayList(), listPartitionPruner.prune());

        // int_col is not null
        conjuncts = new ArrayList<>();
        conjuncts.add(new IsNullPredicateOperator(true, intColumn));
        pruner = initPruner(conjuncts);
        Assert.assertNull(pruner.prune());
        listPartitionPruner = initListPruner(pruner);
        Assert.assertEquals(notNullPartitions, listPartitionPruner.prune());

        // concat_ws('-', year, month, day) is NOT null AND int_col is NOT null
        conjuncts = new ArrayList<>();
        conjuncts.add(new IsNullPredicateOperator(true, dateColumn));
        conjuncts.add(new IsNullPredicateOperator(true, intColumn));
        pruner = initPruner(conjuncts);
        listPartitionPruner = initListPruner(pruner);
        Assert.assertEquals(notNullPartitions, listPartitionPruner.prune());
        conjuncts = listPartitionPruner.getNoEvalConjuncts();
        pruner = initPruner(conjuncts);
        Assert.assertEquals(notNullPartitions, pruner.prune());
    }

    @Test
    public void testCompoundPredicate() throws AnalysisException {
        // concat_ws('-', year, month, day) = "2021-01-01" or int_col = 2
        List<ScalarOperator> conjuncts = new ArrayList<>();
        conjuncts.add(new CompoundPredicateOperator(
                CompoundPredicateOperator.CompoundType.OR,
                new BinaryPredicateOperator(BinaryType.EQ, dateColumn,
                        ConstantOperator.createDate(LocalDateTime.of(2021, 1, 1, 0, 0, 0))),
                new BinaryPredicateOperator(BinaryType.EQ, intColumn,
                        ConstantOperator.createInt(2))));
        HiveMultiPartitionPruner pruner = initPruner(conjuncts);
        ListPartitionPruner listPartitionPruner = initListPruner(pruner);
        Assert.assertNull(listPartitionPruner.prune());
        conjuncts = listPartitionPruner.getNoEvalConjuncts();
        pruner = initPruner(conjuncts);
        Assert.assertNull(pruner.prune());

        // concat_ws('-', year, month, day) >= "2021-01-01" and concat_ws('-', year, month, day) <= "2021-01-02"
        conjuncts = new ArrayList<>();
        conjuncts.add(new CompoundPredicateOperator(
                CompoundPredicateOperator.CompoundType.AND,
                new BinaryPredicateOperator(BinaryType.GE, dateColumn,
                        ConstantOperator.createDate(LocalDateTime.of(2021, 1, 1, 0, 0, 0))),
                new BinaryPredicateOperator(BinaryType.LE, dateColumn,
                        ConstantOperator.createDate(LocalDateTime.of(2021, 1, 2, 0, 0, 0)))));
        pruner = initPruner(conjuncts);
        listPartitionPruner = initListPruner(pruner);
        Assert.assertNull(listPartitionPruner.prune());
        conjuncts = listPartitionPruner.getNoEvalConjuncts();
        pruner = initPruner(conjuncts);
        Assert.assertNull(pruner.prune());
    }
}
