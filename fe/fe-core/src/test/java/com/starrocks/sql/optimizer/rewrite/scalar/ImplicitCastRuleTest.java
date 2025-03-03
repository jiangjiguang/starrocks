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


package com.starrocks.sql.optimizer.rewrite.scalar;

import com.google.common.collect.Lists;
import com.starrocks.analysis.BinaryType;
import com.starrocks.analysis.FunctionName;
import com.starrocks.catalog.Function;
import com.starrocks.catalog.FunctionSet;
import com.starrocks.catalog.PrimitiveType;
import com.starrocks.catalog.Type;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.scalar.BetweenPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.BinaryPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.CallOperator;
import com.starrocks.sql.optimizer.operator.scalar.CaseWhenOperator;
import com.starrocks.sql.optimizer.operator.scalar.CastOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.CompoundPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
import com.starrocks.sql.optimizer.operator.scalar.InPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.LikePredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rewrite.ScalarOperatorRewriteContext;
import mockit.Expectations;
import org.junit.Test;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ImplicitCastRuleTest {

    @Test
    public void testCall() {
        Function fn = new Function(new FunctionName("add"), new Type[] {Type.BIGINT, Type.BIGINT}, Type.BIGINT, true);

        CallOperator op = new CallOperator("add", Type.BIGINT, Lists.newArrayList(
                ConstantOperator.createVarchar("1"),
                ConstantOperator.createBoolean(true),
                ConstantOperator.createBigint(1),
                ConstantOperator.createInt(1)
        ));

        new Expectations(op) {{
                op.getFunction();
                minTimes = 0;
                result = fn;
            }};

        ImplicitCastRule rule = new ImplicitCastRule();
        ScalarOperator result = rule.apply(op, null);

        assertEquals(OperatorType.CALL, result.getOpType());
        assertEquals(4, result.getChildren().size());

        assertTrue(result.getChild(0) instanceof CastOperator);
        assertTrue(result.getChild(1) instanceof CastOperator);
        assertFalse(result.getChild(2) instanceof CastOperator);
        assertTrue(result.getChild(3) instanceof CastOperator);

        assertEquals(Type.BIGINT, result.getChild(0).getType());
        assertEquals(Type.BIGINT, result.getChild(1).getType());
        assertEquals(Type.BIGINT, result.getChild(2).getType());
        assertEquals(Type.BIGINT, result.getChild(3).getType());

        assertEquals(Type.VARCHAR, result.getChild(0).getChild(0).getType());
        assertEquals(Type.BOOLEAN, result.getChild(1).getChild(0).getType());
        assertEquals(Type.INT, result.getChild(3).getChild(0).getType());
    }

    @Test
    public void testBetweenPredicate() {
        BetweenPredicateOperator op = new BetweenPredicateOperator(false, ConstantOperator.createBigint(1),
                ConstantOperator.createVarchar("1"), ConstantOperator.createDate(LocalDateTime.now()));

        ImplicitCastRule rule = new ImplicitCastRule();
        ScalarOperator result = rule.apply(op, null);

        assertTrue(result.getChild(0) instanceof CastOperator);
        assertTrue(result.getChild(1) instanceof CastOperator);
        assertTrue(result.getChild(2) instanceof CastOperator);

        assertEquals(Type.DOUBLE, result.getChild(0).getType());
        assertEquals(Type.DOUBLE, result.getChild(1).getType());
        assertEquals(Type.DOUBLE, result.getChild(2).getType());

        assertEquals(Type.VARCHAR, result.getChild(1).getChild(0).getType());
        assertEquals(Type.DATE, result.getChild(2).getChild(0).getType());
    }

    @Test
    public void testBinaryPredicate() {
        BinaryPredicateOperator op =
                new BinaryPredicateOperator(BinaryType.EQ,
                        ConstantOperator.createVarchar("1"), ConstantOperator.createInt(1));

        ImplicitCastRule rule = new ImplicitCastRule();
        ScalarOperator result = rule.apply(op, null);

        assertTrue(result.getChild(0) instanceof ConstantOperator);
        assertTrue(result.getChild(1) instanceof CastOperator);

        assertEquals(PrimitiveType.VARCHAR, result.getChild(0).getType().getPrimitiveType());
        assertEquals(PrimitiveType.VARCHAR, result.getChild(1).getType().getPrimitiveType());

        assertTrue(result.getChild(1).getChild(0).getType().isInt());
    }

    @Test
    public void testEqualForNullBinaryPredicateWithConstNull() {
        BinaryPredicateOperator[] ops = new BinaryPredicateOperator[] {
                new BinaryPredicateOperator(BinaryType.EQ_FOR_NULL,
                        ConstantOperator.createVarchar("a"),
                        ConstantOperator.createNull(Type.DOUBLE)),
                new BinaryPredicateOperator(BinaryType.EQ_FOR_NULL,
                        ConstantOperator.createNull(Type.DOUBLE),
                        ConstantOperator.createVarchar("a")),
        };
        for (BinaryPredicateOperator op : ops) {
            ImplicitCastRule rule = new ImplicitCastRule();
            ScalarOperator result = rule.apply(op, null);

            assertTrue(result.getChild(0) instanceof ConstantOperator);
            assertTrue(result.getChild(1) instanceof ConstantOperator);

            assertEquals(PrimitiveType.VARCHAR, result.getChild(0).getType().getPrimitiveType());
            assertEquals(PrimitiveType.VARCHAR, result.getChild(1).getType().getPrimitiveType());
        }
    }

    @Test
    public void testEqualForNullBinaryPredicateWithoutConstNull() {
        BinaryPredicateOperator[] ops = new BinaryPredicateOperator[] {
                new BinaryPredicateOperator(BinaryType.EQ_FOR_NULL,
                        ConstantOperator.createDate(LocalDateTime.of(2022, Month.JANUARY, 01, 0, 0, 0)),
                        new ColumnRefOperator(1, Type.DATE, "date_col", true)
                ),
                new BinaryPredicateOperator(BinaryType.EQ_FOR_NULL,
                        new ColumnRefOperator(1, Type.DATE, "date_col", true),
                        ConstantOperator.createInt(20220111)
                ),
                new BinaryPredicateOperator(BinaryType.EQ_FOR_NULL,
                        ConstantOperator.createVarchar("2022-01-01"),
                        new ColumnRefOperator(1, Type.DATE, "date_col", true)
                ),
                new BinaryPredicateOperator(BinaryType.EQ_FOR_NULL,
                        new ColumnRefOperator(1, Type.DATE, "date_col", true),
                        ConstantOperator.createVarchar("2022-01-01")
                ),
        };
        for (BinaryPredicateOperator op : ops) {
            ImplicitCastRule rule = new ImplicitCastRule();
            ScalarOperator result = rule.apply(op, null);

            assertEquals(PrimitiveType.DATE, result.getChild(0).getType().getPrimitiveType());
            assertEquals(PrimitiveType.DATE, result.getChild(1).getType().getPrimitiveType());
        }
    }

    @Test
    public void testCompoundPredicate() {
        CompoundPredicateOperator op =
                new CompoundPredicateOperator(CompoundPredicateOperator.CompoundType.AND,
                        ConstantOperator.createVarchar("1"), ConstantOperator.createInt(1));

        ImplicitCastRule rule = new ImplicitCastRule();
        ScalarOperator result = rule.apply(op, null);

        assertTrue(result.getChild(0) instanceof CastOperator);
        assertTrue(result.getChild(1) instanceof CastOperator);

        assertTrue(result.getChild(0).getType().isBoolean());
        assertTrue(result.getChild(1).getType().isBoolean());

        assertTrue(result.getChild(0).getChild(0).getType().isVarchar());
        assertTrue(result.getChild(1).getChild(0).getType().isInt());
    }

    @Test
    public void testInPredicate() {
        InPredicateOperator op = new InPredicateOperator(ConstantOperator.createBigint(1),
                ConstantOperator.createVarchar("1"), ConstantOperator.createDate(LocalDateTime.now()));

        ImplicitCastRule rule = new ImplicitCastRule();
        ScalarOperator result = rule.apply(op, null);

        assertTrue(result.getChild(0) instanceof CastOperator);
        assertTrue(result.getChild(1) instanceof ConstantOperator);
        assertTrue(result.getChild(2) instanceof CastOperator);

        assertEquals(Type.VARCHAR, result.getChild(0).getType());
        assertEquals(Type.VARCHAR, result.getChild(1).getType());
        assertEquals(Type.VARCHAR, result.getChild(2).getType());

        assertTrue(result.getChild(0).getChild(0).getType().isBigint());
        assertTrue(result.getChild(2).getChild(0).getType().isDate());
    }

    @Test
    public void testLikePredicateOperator() {
        LikePredicateOperator op =
                new LikePredicateOperator(ConstantOperator.createVarchar("1"), ConstantOperator.createInt(1));

        ImplicitCastRule rule = new ImplicitCastRule();
        ScalarOperator result = rule.apply(op, null);

        assertFalse(result.getChild(0) instanceof CastOperator);
        assertTrue(result.getChild(1) instanceof CastOperator);

        assertTrue(result.getChild(0).getType().isVarchar());
        assertTrue(result.getChild(1).getType().isVarchar());

        assertTrue(result.getChild(1).getChild(0).getType().isInt());
    }

    @Test
    public void testPredicateForPrestoTranslate() {
        // create scalar rewrite context
        ScalarOperatorRewriteContext context = new ScalarOperatorRewriteContext();
        ConnectContext session = new ConnectContext();

        // open presto translate
        session.getSessionVariable().setEnablePrestoTranslation(true);
        context.setSession(session);

        ImplicitCastRule rule = new ImplicitCastRule();

        // ==== test case for xx > 1 === begin
        ColumnRefOperator variableColumn = new ColumnRefOperator(0, Type.VARCHAR, "xx", false);
        ConstantOperator constantColumn =  new ConstantOperator(1, Type.TINYINT);
        BinaryPredicateOperator op = new BinaryPredicateOperator(BinaryType.GT,
                variableColumn, constantColumn);

        ScalarOperator result = rule.apply(op, context);
        assertEquals(PrimitiveType.DOUBLE, result.getChild(1).getType().getPrimitiveType());
        // ==== test case for xx = 1 === end


        // ==== test case for xx between 1 and 1000 === begin
        ColumnRefOperator variableColumnBetween = new ColumnRefOperator(0, Type.VARCHAR, "xx", false);
        ConstantOperator constant1ColumnBetween =  new ConstantOperator(1, Type.TINYINT);
        ConstantOperator constant2ColumnBetween =  new ConstantOperator(1000, Type.TINYINT);
        BetweenPredicateOperator opBetween = new BetweenPredicateOperator(false,
                variableColumnBetween, constant1ColumnBetween, constant2ColumnBetween);

        result = rule.apply(opBetween, context);
        assertEquals(PrimitiveType.DOUBLE, result.getChild(0).getType().getPrimitiveType());
        // ==== test case for xx between 1 and 1000 === end


        // ==== test case for xx <> '0' === begin
        ColumnRefOperator variableColumnNotEqual = new ColumnRefOperator(0, Type.DECIMALV2, "xx", false);
        ConstantOperator constantColumnVarchar =  new ConstantOperator("0", Type.VARCHAR);
        op = new BinaryPredicateOperator(BinaryType.NE, variableColumnNotEqual, constantColumnVarchar);

        result = rule.apply(op, context);
        assertEquals(PrimitiveType.DECIMALV2, result.getChild(1).getType().getPrimitiveType());
        // ==== test case for xx <> '0' === end


        // ==== test case for xx in ('-', '0') === begin
        ColumnRefOperator variableColumnIn = new ColumnRefOperator(0, Type.BIGINT, "xx", false);
        ConstantOperator constant1ColumnIn =  new ConstantOperator("-", Type.VARCHAR);
        ConstantOperator constant2ColumnIn =  new ConstantOperator("0", Type.VARCHAR);
        InPredicateOperator opIn = new InPredicateOperator(variableColumnIn, constant1ColumnIn, constant2ColumnIn);
        result = rule.apply(opIn, context);
        assertEquals(PrimitiveType.VARCHAR, result.getChild(0).getType().getPrimitiveType());
        // ==== test case for xx in ('-', '0') === end


        // ==== test case for CASE WHEN xx IN (6, 7) THEN yy ELSE 0 END === begin
        ColumnRefOperator xx = new ColumnRefOperator(0, Type.VARCHAR, "xx", false);
        ConstantOperator in1 =  new ConstantOperator("6", Type.TINYINT);
        ConstantOperator in2 =  new ConstantOperator("7", Type.TINYINT);
        InPredicateOperator caseWhenInOperator = new InPredicateOperator(xx, in1, in2);
        ColumnRefOperator yy = new ColumnRefOperator(1, Type.VARCHAR, "yy", false);
        List<ScalarOperator> whenClause = Lists.newArrayList(caseWhenInOperator, yy);
        ConstantOperator constantColumnElse =  new ConstantOperator("0", Type.TINYINT);
        CaseWhenOperator caseWhenOperator = new CaseWhenOperator(Type.VARCHAR, null, constantColumnElse, whenClause);
        result = rule.apply(caseWhenOperator, context);
        assertEquals(PrimitiveType.DOUBLE, result.getChild(0).getChild(0).getType().getPrimitiveType());
        // ==== test case for CASE WHEN xx IN (6, 7) THEN yy ELSE 0 END === end


        // ==== test case for CASE col_a WHEN 1 THEN 2 ELSE 0 END === begin
        ColumnRefOperator aa = new ColumnRefOperator(0, Type.BIGINT, "col_a", false);
        ConstantOperator when1 =  new ConstantOperator("1", Type.TINYINT);
        ConstantOperator then2 =  new ConstantOperator("2", Type.TINYINT);
        ConstantOperator else3 =  new ConstantOperator("0", Type.TINYINT);
        List<ScalarOperator> caseColumnWhenClause = Lists.newArrayList(when1, then2);
        CaseWhenOperator caseColumnWhenOperator = new CaseWhenOperator(Type.TINYINT, aa, else3, caseColumnWhenClause);
        result = rule.apply(caseColumnWhenOperator, context);
        assertEquals(PrimitiveType.BIGINT, result.getChild(1).getType().getPrimitiveType());
        // ==== test case for CASE xx WHEN 1 THEN 2 ELSE 0 END === end

        // ==== test case for CASE WHEN xx > 6 THEN yy END === begin
        ColumnRefOperator colX = new ColumnRefOperator(0, Type.VARCHAR, "xx", false);
        ConstantOperator gt6 =  new ConstantOperator("6", Type.TINYINT);
        BinaryPredicateOperator whenBinaryCase = new BinaryPredicateOperator(BinaryType.GT, colX, gt6);
        ColumnRefOperator colY = new ColumnRefOperator(1, Type.VARCHAR, "yy", false);
        List<ScalarOperator> whenDiffClause = Lists.newArrayList(whenBinaryCase, colY);
        CaseWhenOperator doubleOperator = new CaseWhenOperator(Type.VARCHAR, null, null, whenDiffClause);
        result = rule.apply(doubleOperator, context);
        assertEquals(PrimitiveType.DOUBLE, result.getChild(0).getChild(0).getType().getPrimitiveType());
        // ==== test case for CASE WHEN xx > 20 THEN yy END === end

        // ==== test case for CASE WHEN (to_date(xx) BETWEEN '2025-02-24' AND '2025-03-02') THEN 'a' END === begin
        ColumnRefOperator dateX = new ColumnRefOperator(0, Type.VARCHAR, "xx", false);
        List<ScalarOperator> argumentList = new ArrayList<>();
        argumentList.add(dateX);
        CallOperator toDateOperator = new CallOperator(FunctionSet.TO_DATE, Type.DATE, argumentList);
        ConstantOperator constBegin =  new ConstantOperator("2025-02-24", Type.VARCHAR);
        ConstantOperator constEnd =  new ConstantOperator("2025-03-02", Type.VARCHAR);
        BetweenPredicateOperator opDateBetween = new BetweenPredicateOperator(false,
                toDateOperator, constBegin, constEnd);
        result = rule.apply(opDateBetween, context);
        assertEquals(PrimitiveType.DATE, result.getChild(1).getType().getPrimitiveType());
        assertEquals(PrimitiveType.DATE, result.getChild(2).getType().getPrimitiveType());
        // ==== test case for CASE WHEN (to_date(xx) BETWEEN '2025-02-24' AND '2025-03-02') THEN 'a' END === end


        // ==== test case for CASE WHEN x > date_sub(current_date(), 7) THEN 1 ELSE 0 END === begin
        ColumnRefOperator varX = new ColumnRefOperator(0, Type.VARCHAR, "xx", false);
        CallOperator currentDate = new CallOperator(FunctionSet.CURDATE, Type.DATE, new ArrayList<>());
        List<ScalarOperator> argList = new ArrayList<>();
        argList.add(currentDate);
        argList.add(new ConstantOperator(7, Type.TINYINT));
        CallOperator dateSubOperator = new CallOperator(FunctionSet.DATE_SUB, Type.DATE, argList);
        BinaryPredicateOperator whenDateBinaryCase = new BinaryPredicateOperator(BinaryType.GT,
                varX, dateSubOperator);
        ConstantOperator const1Then = new ConstantOperator(1, Type.TINYINT);
        List<ScalarOperator> whenDateClause = Lists.newArrayList(whenDateBinaryCase, const1Then);
        ConstantOperator const0Else = new ConstantOperator(0, Type.TINYINT);
        CaseWhenOperator caseWhenOperator1 = new CaseWhenOperator(Type.TINYINT, null, const0Else, whenDateClause);
        result = rule.apply(caseWhenOperator1, context);
        assertEquals(PrimitiveType.DATE, result.getChild(0).getChild(0).getType().getPrimitiveType());
        // ==== test case for CASE WHEN x > date_sub(current_date(), 7) THEN 1 ELSE 0 END === end
    }
}