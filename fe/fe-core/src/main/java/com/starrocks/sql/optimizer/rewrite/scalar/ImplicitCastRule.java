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

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.starrocks.analysis.ArithmeticExpr;
import com.starrocks.analysis.BinaryType;
import com.starrocks.catalog.Function;
import com.starrocks.catalog.FunctionSet;
import com.starrocks.catalog.MapType;
import com.starrocks.catalog.Type;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.SessionVariableConstants;
import com.starrocks.sql.common.ErrorType;
import com.starrocks.sql.common.StarRocksPlannerException;
import com.starrocks.sql.common.TypeManager;
import com.starrocks.sql.optimizer.Utils;
import com.starrocks.sql.optimizer.operator.scalar.BetweenPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.BinaryPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.CallOperator;
import com.starrocks.sql.optimizer.operator.scalar.CaseWhenOperator;
import com.starrocks.sql.optimizer.operator.scalar.CastOperator;
import com.starrocks.sql.optimizer.operator.scalar.CompoundPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
import com.starrocks.sql.optimizer.operator.scalar.InPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.LikePredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.MapOperator;
import com.starrocks.sql.optimizer.operator.scalar.MultiInPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rewrite.ScalarOperatorRewriteContext;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

//
// Add cast function when children's type different with parent required type
//
// example:
//        Binary(+)
//        /      \
//  a(String)   b(int)
//
// After rule:
//             Binary(+)
//             /     \
//   cast(bigint)   cast(bigint)
//          /           \
//   a(String)          b(int)
//
public class ImplicitCastRule extends TopDownScalarOperatorRewriteRule {
    @Override
    public ScalarOperator visitCall(CallOperator call, ScalarOperatorRewriteContext context) {
        Function fn = call.getFunction();
        if (fn == null) {
            for (int i = 0; i < call.getChildren().size(); ++i) {
                Type type = call.getType();
                if (!type.matchesType(call.getChild(i).getType())) {
                    addCastChild(type, call, i);
                }
            }
        } else {
            // functions with various data types do not need to implicit cast, such as following functions.
            if (fn.functionName().equals(FunctionSet.ARRAY_MAP) ||
                    fn.functionName().equals(FunctionSet.EXCHANGE_BYTES) ||
                    fn.functionName().equals(FunctionSet.EXCHANGE_SPEED) ||
                    fn.functionName().equals(FunctionSet.ARRAY_SORTBY)) {
                return call;
            }
            if (!call.isAggregate() || FunctionSet.AVG.equalsIgnoreCase(fn.functionName())) {
                Preconditions.checkArgument(Arrays.stream(fn.getArgs()).noneMatch(Type::isWildcardDecimal),
                        String.format("Resolved function %s has wildcard decimal as argument type", fn.functionName()));
            }

            boolean needAdjustScale = ArithmeticExpr.DECIMAL_SCALE_ADJUST_OPERATOR_SET
                    .contains(fn.getFunctionName().getFunction());
            for (int i = 0; i < fn.getNumArgs(); i++) {
                Type type = fn.getArgs()[i];
                ScalarOperator child = call.getChild(i);

                // For compatibility, decimal ArithmeticExpr(+-*/%) use Type::equals instead of Type::matchesType to
                // determine whether to cast child of the ArithmeticExpr
                if (needAdjustScale && type.isDecimalOfAnyVersion() && !type.equals(child.getType())) {
                    addCastChild(type, call, i);
                    continue;
                }

                if (!type.matchesType(child.getType())) {
                    addCastChild(type, call, i);
                }
            }

            // variable args
            if (fn.hasVarArgs() && call.getChildren().size() > fn.getNumArgs()) {
                Type type = fn.getVarArgsType();
                for (int i = fn.getNumArgs(); i < call.getChildren().size(); i++) {
                    ScalarOperator child = call.getChild(i);
                    if (!type.matchesType(child.getType())) {
                        addCastChild(type, call, i);
                    }
                }
            }
        }

        return call;
    }

    @Override
    public ScalarOperator visitBetweenPredicate(BetweenPredicateOperator predicate,
                                                ScalarOperatorRewriteContext context) {
        return castForBetweenAndIn(predicate, true, context);
    }

    @Override
    public ScalarOperator visitMap(MapOperator map, ScalarOperatorRewriteContext context) {
        MapType mapType = (MapType) map.getType();
        Type[] kvType = {mapType.getKeyType(), mapType.getValueType()};
        for (int i = 0; i < map.getChildren().size(); i++) {
            if (!map.getChildren().get(i).getType().matchesType(kvType[i % 2])) {
                addCastChild(kvType[i % 2], map, i);
            }
        }
        return map;
    }

    @Override
    public ScalarOperator visitBinaryPredicate(BinaryPredicateOperator predicate,
                                               ScalarOperatorRewriteContext context) {
        ScalarOperator leftChild = predicate.getChild(0);
        ScalarOperator rightChild = predicate.getChild(1);
        Type type1 = leftChild.getType();
        Type type2 = rightChild.getType();

        // For a query like: select 'a' <=> NULL, we should cast Constant Null to the type of the other side
        if (predicate.getBinaryType() == BinaryType.EQ_FOR_NULL &&
                (leftChild.isConstantNull() || rightChild.isConstantNull())) {
            if (leftChild.isConstantNull()) {
                predicate.setChild(0, ConstantOperator.createNull(type2));
            }
            if (rightChild.isConstantNull()) {
                predicate.setChild(1, ConstantOperator.createNull(type1));
            }
            return predicate;
        }

        if (type1.matchesType(type2)) {
            return predicate;
        }

        boolean enablePrestoTranslation = false;
        if (context != null && context.getSession() != null) {
            enablePrestoTranslation = context.getSession().getSessionVariable().isEnablePrestoTranslation();
        }
        // we will try cast const operator to variable operator
        if (rightChild.isVariable() && leftChild.isConstantRef()) {
            if (enablePrestoTranslation) {
                if (rightChild.getType().isVarchar()) {
                    // 1 > xx => right child [xx] is varchar
                    if (leftChild.getType().isIntegerType() || leftChild.getType().isDouble()) {
                        // cast(1 as double)
                        addCastChild(Type.DOUBLE, predicate, 0);
                        // cast(xx as double)
                        addCastChild(Type.DOUBLE, predicate, 1);
                        return predicate;
                    }
                }
                // 1 > xx => right child [xx] origin column type is decimal or double
                if (rightChild.getType().isDecimalOfAnyVersion() || leftChild.getType().isDouble()) {
                    // cast left child to decimal => cast(1 as decimal)
                    // cast left child to double => cast(1 as double)
                    addCastChild(rightChild.getType(), predicate, 0);
                    return predicate;
                }
            }
            int constant = 0;
            int variable = 1 - constant;
            Optional<BinaryPredicateOperator> optional = optimizeConstantAndVariable(predicate, constant, variable);
            if (optional.isPresent()) {
                return optional.get();
            }
        } else if (leftChild.isVariable() && rightChild.isConstantRef()) {
            if (enablePrestoTranslation) {
                if (leftChild.getType().isVarchar()) {
                    // xx > 1 => step 1
                    if (rightChild.getType().isIntegerType() || rightChild.getType().isDouble()) {
                        // cast(xx as double)
                        addCastChild(Type.DOUBLE, predicate, 0);
                        // cast(1 as double)
                        addCastChild(Type.DOUBLE, predicate, 1);
                        return predicate;
                    }
                }
                // xx > 1 => left child [xx] origin column type is decimal or double
                if (leftChild.getType().isDecimalOfAnyVersion() || leftChild.getType().isDouble()) {
                    // cast right child to decimal => cast(1 as decimal)
                    // cast right child to double => cast(1 as double)
                    addCastChild(leftChild.getType(), predicate, 1);
                    return predicate;
                }
            }
            int constant = 1;
            int variable = 1 - constant;
            Optional<BinaryPredicateOperator> optional = optimizeConstantAndVariable(predicate, constant, variable);
            if (optional.isPresent()) {
                return optional.get();
            }
        }

        Type compatibleType =
                TypeManager.getCompatibleTypeForBinary(predicate.getBinaryType().isRange(), type1, type2);

        if (!type1.matchesType(compatibleType)) {
            addCastChild(compatibleType, predicate, 0);
        }
        if (!type2.matchesType(compatibleType)) {
            addCastChild(compatibleType, predicate, 1);
        }
        return predicate;
    }

    private Optional<BinaryPredicateOperator> optimizeConstantAndVariable(BinaryPredicateOperator predicate,
                                                                          int constantIndex, int variableIndex) {
        ScalarOperator constant = predicate.getChild(constantIndex);
        ScalarOperator variable = predicate.getChild(variableIndex);
        Type typeConstant = constant.getType();
        Type typeVariable = variable.getType();

        boolean checkStringCastToNumber = false;
        if (typeVariable.isNumericType() && typeConstant.isStringType()) {
            if (predicate.getBinaryType().isNotRangeComparison()) {
                String baseType = ConnectContext.get() != null ?
                        ConnectContext.get().getSessionVariable().getCboEqBaseType() : SessionVariableConstants.VARCHAR;
                checkStringCastToNumber = SessionVariableConstants.DECIMAL.equals(baseType) ||
                        SessionVariableConstants.DOUBLE.equals(baseType);
            } else {
                // range compare, base type must be double
                checkStringCastToNumber = true;
            }
        }

        // strict check, only support white check
        if ((typeVariable.isNumericType() && typeConstant.isNumericType()) ||
                (typeVariable.isNumericType() && typeConstant.isBoolean()) ||
                (typeVariable.isDateType() && typeConstant.isNumericType()) ||
                (typeVariable.isDateType() && typeConstant.isStringType()) ||
                (typeVariable.isFixedPointType() && typeConstant.isStringType() &&
                        predicate.getBinaryType() == BinaryType.EQ) ||
                (typeVariable.isBoolean() && typeConstant.isStringType()) ||
                checkStringCastToNumber) {

            Optional<ScalarOperator> op = Utils.tryCastConstant(constant, variable.getType());
            if (op.isPresent()) {
                predicate.getChildren().set(constantIndex, op.get());
                return Optional.of(predicate);
            } else if (variable.getType().isDateType() && Type.canCastTo(constant.getType(), variable.getType())) {
                // For like MySQL, convert to date type as much as possible
                addCastChild(variable.getType(), predicate, constantIndex);
                return Optional.of(predicate);
            }
        }

        return Optional.empty();
    }

    @Override
    public ScalarOperator visitCompoundPredicate(CompoundPredicateOperator predicate,
                                                 ScalarOperatorRewriteContext context) {
        for (int i = 0; i < predicate.getChildren().size(); i++) {
            ScalarOperator child = predicate.getChild(i);

            if (!Type.BOOLEAN.matchesType(child.getType())) {
                addCastChild(Type.BOOLEAN, predicate, i);
            }
        }

        return predicate;
    }

    @Override
    public ScalarOperator visitInPredicate(InPredicateOperator predicate, ScalarOperatorRewriteContext context) {
        return castForBetweenAndIn(predicate, false, context);
    }

    @Override
    public ScalarOperator visitMultiInPredicate(MultiInPredicateOperator predicate, ScalarOperatorRewriteContext c) {
        throw new StarRocksPlannerException("Implicit casting of multi-column IN predicate is not supported.",
                ErrorType.INTERNAL_ERROR);
    }

    @Override
    public ScalarOperator visitLikePredicateOperator(LikePredicateOperator predicate,
                                                     ScalarOperatorRewriteContext context) {
        Type type1 = predicate.getChild(0).getType();
        Type type2 = predicate.getChild(1).getType();

        if (!type1.isStringType()) {
            addCastChild(Type.VARCHAR, predicate, 0);
        }

        if (!type2.isStringType()) {
            addCastChild(Type.VARCHAR, predicate, 1);
        }

        return predicate;
    }

    @Override
    public ScalarOperator visitCaseWhenOperator(CaseWhenOperator operator, ScalarOperatorRewriteContext context) {
        if (operator.hasElse() && !operator.getType().matchesType(operator.getElseClause().getType())) {
            operator.setElseClause(new CastOperator(operator.getType(), operator.getElseClause()));
        }

        for (int i = 0; i < operator.getWhenClauseSize(); i++) {
            if (!operator.getType().matchesType(operator.getThenClause(i).getType())) {
                operator.setThenClause(i, new CastOperator(operator.getType(), operator.getThenClause(i)));
            }
        }

        Type compatibleType = Type.BOOLEAN;
        if (operator.hasCase()) {
            List<Type> whenTypes = Lists.newArrayList();
            whenTypes.add(operator.getCaseClause().getType());
            for (int i = 0; i < operator.getWhenClauseSize(); i++) {
                whenTypes.add(operator.getWhenClause(i).getType());
            }

            compatibleType = TypeManager.getCompatibleTypeForCaseWhen(whenTypes);

            if (!compatibleType.matchesType(operator.getCaseClause().getType())) {
                operator.setCaseClause(new CastOperator(compatibleType, operator.getCaseClause()));
            }
        }

        for (int i = 0; i < operator.getWhenClauseSize(); i++) {
            if (!compatibleType.matchesType(operator.getWhenClause(i).getType())) {
                operator.setWhenClause(i, new CastOperator(compatibleType, operator.getWhenClause(i)));
            }
        }

        return castToDoubleInCompareCaseWhen(operator, context);
    }

    /**
     * CASE WHEN xx IN (6, 7) THEN yy ELSE 0 END
     * variable column xx's type is varchar
     * constant column type is int
     * we should cast constant type and variable type to double type
     * else in VectorizedInPredicate, we will meet the exception [VectorizedInPredicate type not same]
     * @param operator
     * @param context
     * @return
     */
    private CaseWhenOperator castToDoubleInCompareCaseWhen(CaseWhenOperator operator, ScalarOperatorRewriteContext context) {
        boolean enablePrestoTranslation = false;
        if (context != null && context.getSession() != null) {
            enablePrestoTranslation = context.getSession().getSessionVariable().isEnablePrestoTranslation();
        }
        if (!enablePrestoTranslation) {
            return operator;
        }
        if (operator.getWhenClauseSize() == 0) {
            return operator;
        }
        for (int i = 0; i < operator.getWhenClauseSize(); i++) {
            ScalarOperator whenOperator = operator.getWhenClause(i);
            if (whenOperator.getChildren() == null || whenOperator.getChildren().isEmpty()) {
                continue;
            }

            // Reason: Function calls (such as array_contains) are usually type-safe by function signatures
            // and do not require additional conversions.
            // case when array_container(map_keys(x), '123') then 'abc'
            // case when substr(current_date, 1, 4) then 'xx'
            if (whenOperator instanceof CallOperator) {
                continue;
            }

            // CASE WHEN to_date(xx) BETWEEN '2025-02-24' AND '2025-03-02' THEN 1 ELSE 0 END
            // CASE WHEN xx > date_sub(current_date(), 7) THEN 1 ELSE 0 END
            boolean isDateColumn = whenOperator.getChildren().stream().anyMatch(item -> item.getType().isDate());
            if (isDateColumn) {
                for (int index = 0; index < whenOperator.getChildren().size(); index++) {
                    ScalarOperator currentOperator = whenOperator.getChild(index);
                    if (!currentOperator.getType().isDate()) {
                        ScalarOperator newChildOperator = new CastOperator(Type.DATE, currentOperator);
                        whenOperator.setChild(index, newChildOperator);
                    }
                }
                operator.setWhenClause(i, whenOperator);
                continue;
            }

            // CASE WHEN xx > now() THEN 1 ELSE 0 END
            boolean isDateTimeColumn = whenOperator.getChildren().stream().anyMatch(item -> item.getType().isDatetime());
            if (isDateTimeColumn) {
                for (int index = 0; index < whenOperator.getChildren().size(); index++) {
                    ScalarOperator currentOperator = whenOperator.getChild(index);
                    if (!currentOperator.getType().isDatetime()) {
                        ScalarOperator newChildOperator = new CastOperator(Type.DATETIME, currentOperator);
                        whenOperator.setChild(index, newChildOperator);
                    }
                }
                operator.setWhenClause(i, whenOperator);
                continue;
            }

            // CASE WHEN xx in (1, 2) THEN 1 ELSE 0 END
            ScalarOperator firstOperator = whenOperator.getChild(0);
            boolean needCastToDouble = whenOperator.getChildren().stream().skip(1)
                    .anyMatch(child -> !firstOperator.getType().matchesType(child.getType()));
            if (needCastToDouble) {
                for (int index = 0; index < whenOperator.getChildren().size(); index++) {
                    ScalarOperator currentOperator = whenOperator.getChild(index);
                    if (!currentOperator.getType().isDouble()) {
                        ScalarOperator newChildOperator = new CastOperator(Type.DOUBLE, currentOperator);
                        whenOperator.setChild(index, newChildOperator);
                    }
                }
                operator.setWhenClause(i, whenOperator);
            }
        }
        return operator;
    }

    private ScalarOperator castForBetweenAndIn(ScalarOperator predicate, boolean isBetween,
                                               ScalarOperatorRewriteContext context) {
        Type firstType = predicate.getChildren().get(0).getType();
        if (predicate.getChildren().stream().skip(1).allMatch(o -> firstType.matchesType(o.getType()))) {
            return predicate;
        }

        boolean enablePrestoTranslation = false;
        if (context != null && context.getSession() != null) {
            enablePrestoTranslation = context.getSession().getSessionVariable().isEnablePrestoTranslation();
        }

        List<Type> types = predicate.getChildren().stream().map(ScalarOperator::getType).collect(Collectors.toList());
        if (predicate.getChild(0).isVariable() && predicate.getChildren().stream().skip(1)
                .allMatch(ScalarOperator::isConstantRef)) {
            if (enablePrestoTranslation) {
                // CASE WHEN xx = 'a' OR yy IN (1, 2) THEN 'abc' END
                // yy IN (1, 2) will go into visitInPredicate
                // variable of yy is varchar, so we need cast all columns to double type for compare
                if (firstType.isVarchar()) {
                    if (types.stream().skip(1).anyMatch(
                            t -> t.isIntegerType() || t.isDouble() || t.isDecimalOfAnyVersion())) {
                        for (int i = 0; i < predicate.getChildren().size(); i++) {
                            addCastChild(Type.DOUBLE, predicate, i);
                        }
                        return predicate;
                    }
                }
                // xx IN ('-', '0') => xx is bigint, and '-' is varchar, we just try to cast xx to varchar
                if (types.stream().skip(1).anyMatch(Type::isVarchar)) {
                    if (firstType.isIntegerType()) {
                        // cast(xx as varchar)
                        addCastChild(Type.VARCHAR, predicate, 0);
                        return predicate;
                    }
                }
            }
            List<ScalarOperator> newChild = Lists.newArrayList();
            newChild.add(predicate.getChild(0));
            for (int i = 1; i < types.size(); i++) {
                Optional<ScalarOperator> op = Utils.tryCastConstant(predicate.getChild(i), firstType);
                op.ifPresent(newChild::add);
            }

            if (newChild.size() == predicate.getChildren().size()) {
                predicate.getChildren().clear();
                predicate.getChildren().addAll(newChild);
                return predicate;
            }
        }

        Type compatibleType = TypeManager.getCompatibleTypeForBetweenAndIn(types, isBetween);
        for (int i = 0; i < predicate.getChildren().size(); i++) {
            Type childType = predicate.getChild(i).getType();

            if (!childType.matchesType(compatibleType)) {
                addCastChild(compatibleType, predicate, i);
            }
        }
        return predicate;
    }

    private void addCastChild(Type returnType, ScalarOperator node, int index) {
        node.getChildren().set(index, new CastOperator(returnType, node.getChild(index), true));
    }
}
