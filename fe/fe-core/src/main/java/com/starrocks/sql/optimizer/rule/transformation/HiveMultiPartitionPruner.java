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

package com.starrocks.sql.optimizer.rule.transformation;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.starrocks.analysis.BinaryType;
import com.starrocks.analysis.LiteralExpr;
import com.starrocks.catalog.FunctionSet;
import com.starrocks.common.AnalysisException;
import com.starrocks.planner.PartitionPruner;
import com.starrocks.sql.common.ErrorType;
import com.starrocks.sql.common.StarRocksPlannerException;
import com.starrocks.sql.optimizer.operator.scalar.BinaryPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.CallOperator;
import com.starrocks.sql.optimizer.operator.scalar.CastOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.CompoundPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
import com.starrocks.sql.optimizer.operator.scalar.InPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.IsNullPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rewrite.ScalarOperatorFunctions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.stream.Collectors;

public class HiveMultiPartitionPruner implements PartitionPruner {

    private static final Logger LOG = LogManager.getLogger(HiveMultiPartitionPruner.class);

    private List<ScalarOperator> partitionConjuncts;
    private Map<Long, String> idToPartitionPath;
    private Map<ColumnRefOperator, ConcurrentNavigableMap<LiteralExpr, Set<Long>>> columnToPartitionValuesMap;
    private Map<ColumnRefOperator, Set<Long>> columnToNullPartitions;
    // Conjuncts that not eval in partition pruner, and will be sent to backend.
    private List<ScalarOperator> noEvalConjuncts = Lists.newArrayList();

    private Map<Integer, ScalarOperator> noEvalConjunctMap = new HashMap<>();

    private final List<String> validFunctions = Lists.newArrayList(
            FunctionSet.CONCAT_WS,
            FunctionSet.CONCAT
    );

    public List<ScalarOperator> getPartitionConjuncts() {
        return partitionConjuncts;
    }

    public Map<Long, String> getIdToPartitionPath() {
        return idToPartitionPath;
    }

    public Map<ColumnRefOperator, ConcurrentNavigableMap<LiteralExpr, Set<Long>>> getColumnToPartitionValuesMap() {
        return columnToPartitionValuesMap;
    }

    public Map<ColumnRefOperator, Set<Long>> getColumnToNullPartitions() {
        return columnToNullPartitions;
    }

    public HiveMultiPartitionPruner(List<ScalarOperator> partitionConjuncts,
                                    Map<Long, String> idToPartitionPath,
                                    Map<ColumnRefOperator, Set<Long>> columnToNullPartitions,
                                    Map<ColumnRefOperator,
                                            ConcurrentNavigableMap<LiteralExpr, Set<Long>>> columnToPartitionValuesMap) {
        this.partitionConjuncts = partitionConjuncts;
        this.idToPartitionPath = idToPartitionPath;
        this.columnToNullPartitions = columnToNullPartitions;
        this.columnToPartitionValuesMap = columnToPartitionValuesMap;
    }

    @Override
    public List<Long> prune() throws AnalysisException {
        Preconditions.checkNotNull(columnToPartitionValuesMap);
        Preconditions.checkNotNull(columnToNullPartitions);
        Preconditions.checkArgument(columnToPartitionValuesMap.size() == columnToNullPartitions.size());
        Preconditions.checkNotNull(partitionConjuncts);
        Preconditions.checkNotNull(idToPartitionPath);
        if (partitionConjuncts.isEmpty()) {
            // no partition conjuncts, return all partitions
            return null;
        }
        // init noEvalConjunctMap
        for (int i = 0; i < partitionConjuncts.size(); i++) {
            ScalarOperator scalarOperator = partitionConjuncts.get(i);
            noEvalConjunctMap.put(i, scalarOperator);
        }
        return computePartitionFromPath();
    }

    private boolean checkFunctionValid(ScalarOperator operator) {
        // hive partition = year=2023/month=05/day=01/city_id=1
        // concat_ws('-', year, month, day) >= '2023-05-01' or city_id = 1
        // ListPartitionPruner can't prune the partition and will return all noEvalConjunctList
        // noEvalConjunction is CompoundPredicateOperator
        if (operator instanceof CompoundPredicateOperator) {
            return false;
        }
        // concat_ws('-', year, month, day) >= '2023-05-01'
        if (operator.getChildren().size() < 1) {
            return false;
        }
        CallOperator funcOperator = extractFunctionOperator(operator.getChild(0));
        if (funcOperator == null) {
            return false;
        }
        if (!validFunctions.contains(funcOperator.getFnName().toLowerCase())) {
            return false;
        }
        // concat_ws('-', year, month, day)
        if (funcOperator.getChildren().size() < 2) {
            return false;
        }
        // concat_ws('-', year, month, day) is null
        if (operator.getChildren().size() == 1) {
            return true;
        }
        for (int i = 1; i < operator.getChildren().size(); i++) {
            ScalarOperator child = extractConstantOperator(operator.getChild(i));
            if (child == null) {
                return false;
            }
            if (!child.isConstantRef()) {
                return false;
            }
            // remake constant child
            operator.setChild(i, child);
        }
        return true;
    }

    private ConstantOperator extractConstantOperator(ScalarOperator operator) {
        if (operator.isConstantRef()) {
            return (ConstantOperator) operator;
        }
        if (operator instanceof CallOperator) {
            if (operator.getChildren().size() == 0) {
                return null;
            }
            ScalarOperator child = operator.getChild(0);
            if (child.isConstantRef()) {
                return (ConstantOperator) child;
            }
            if (child instanceof CallOperator) {
                return extractConstantOperator(child);
            }
        }

        if (operator instanceof BinaryPredicateOperator) {
            BinaryPredicateOperator binaryPredicateOperator = (BinaryPredicateOperator) operator;
            for (ScalarOperator child : binaryPredicateOperator.getChildren()) {
                ConstantOperator constantOperator = extractConstantOperator(child);
                if (constantOperator != null) {
                    return constantOperator;
                }
            }
        }

        return null;
    }

    private CallOperator extractFunctionOperator(ScalarOperator operator) {
        if (operator.getChildren().size() == 0) {
            return null;
        }
        if (operator instanceof CallOperator) {
            CallOperator funcOperator = (CallOperator) operator;
            if (validFunctions.contains(funcOperator.getFnName().toLowerCase())) {
                return funcOperator;
            }
        }
        if (operator instanceof CastOperator)  {
            return extractFunctionOperator(operator.getChild(0));
        }

        if (operator instanceof BinaryPredicateOperator) {
            BinaryPredicateOperator binaryPredicateOperator = (BinaryPredicateOperator) operator;
            for (ScalarOperator child : binaryPredicateOperator.getChildren()) {
                CallOperator funcOperator = extractFunctionOperator(child);
                if (funcOperator != null) {
                    return funcOperator;
                }
            }
        }

        return null;
    }

    private boolean checkInputParamsAllInPartitionColumns(List<ScalarOperator> predictArgs) {
        List<String> partitionColumns = columnToPartitionValuesMap.keySet()
                .stream().map(ColumnRefOperator::getName).collect(Collectors.toList());
        for (ScalarOperator predictArg : predictArgs) {
            if (!predictArg.isColumnRef()) {
                continue;
            }
            ColumnRefOperator columnRefOperator = (ColumnRefOperator) predictArg;
            if (!partitionColumns.contains(columnRefOperator.getName())) {
                return false;
            }
        }
        return true;
    }

    private List<ConstantOperator> getPartitionValues(List<ScalarOperator> predictArgs, String location) {
        List<ConstantOperator> sortedPartitionValues = new ArrayList<>();
        Map<String, String> partitionMap = parseLocation(location);
        for (int i = 0; i < predictArgs.size(); i++) {
            ColumnRefOperator columnRefOperator = (ColumnRefOperator) predictArgs.get(i);
            String column = columnRefOperator.getName();
            String value = partitionMap.get(column);
            if (value == null || value.length() == 0) {
                throw new StarRocksPlannerException("hdfs partition location [" + location + "] abnormal",
                        ErrorType.INTERNAL_ERROR);
            }
            ConstantOperator paramOperator = new ConstantOperator(value, columnRefOperator.getType());
            sortedPartitionValues.add(paramOperator);
        }
        return sortedPartitionValues;
    }

    private ConstantOperator getResultByScalarFunction(CallOperator funcOperator,
                                                       List<ConstantOperator> sortedPartitionValues) {
        ConstantOperator result = null;
        if (funcOperator.getFnName().equalsIgnoreCase(FunctionSet.CONCAT_WS)) {
            ConstantOperator split = (ConstantOperator) funcOperator.getChild(0);
            result = ScalarOperatorFunctions.concat_ws(split,
                    sortedPartitionValues.toArray(new ConstantOperator[0]));
        } else if (funcOperator.getFnName().equalsIgnoreCase(FunctionSet.CONCAT)) {
            result = ScalarOperatorFunctions.concat(sortedPartitionValues.toArray(new ConstantOperator[0]));
        }
        if (result == null) {
            throw new StarRocksPlannerException("get result by constant function fail",
                    ErrorType.INTERNAL_ERROR);
        }
        return result;
    }

    /**
     * step 1: concat_ws('-', year, month, day) >= '2023-05-01'
     * step 2: concat_ws('-', year, month, day) = '2023-05-01'
     * step 3: '2023-05-01' >= '2023-05-01'
     * @return partition ids
     */
    public List<Long> computePartitionFromPath() {
        // get partition id list
        Set<Long> partitionIds = new HashSet<>();
        for (int i = 0; i < partitionConjuncts.size(); i++) {
            ScalarOperator operator = partitionConjuncts.get(i);
            // concat_ws('-', year, month, day) >= '2023-05-01'
            if (!checkFunctionValid(operator)) {
                continue;
            }
            CallOperator funcOperator = extractFunctionOperator(operator.getChild(0));
            if (funcOperator == null) {
                continue;
            }

            // skip the split and get the other input parameters
            List<ScalarOperator> predictArgs = funcOperator.getChildren().stream().skip(1).collect(Collectors.toList());
            if (funcOperator.getFnName().equalsIgnoreCase(FunctionSet.CONCAT)) {
                predictArgs = funcOperator.getChildren();
            }
            // check if concat_ws contains all partition columns
            if (!checkInputParamsAllInPartitionColumns(predictArgs)) {
                continue;
            }

            // replace the column with the partition value
            List<ScalarOperator> finalPredictArgs = predictArgs;
            idToPartitionPath.keySet().forEach(partId -> {
                String location = idToPartitionPath.get(partId);
                List<ConstantOperator> sortedPartitionValues = getPartitionValues(finalPredictArgs, location);
                // get the real partition value by concat_ws function
                ConstantOperator result = getResultByScalarFunction(funcOperator, sortedPartitionValues);
                // set the operator with the real partition value
                ScalarOperator newOperator = operator.clone();
                newOperator.setChild(0, result);
                if (evalPartitionPruneFilter(newOperator)) {
                    partitionIds.add(partId);
                } else {
                    partitionIds.remove(partId);
                }
            });
            // remove the partition id that not match the condition
            idToPartitionPath.keySet().removeIf(partId -> !partitionIds.contains(partId));
            // remove the conjunct that eval in partition pruner
            noEvalConjunctMap.remove(i);
        }
        // if no partition id match the condition, return null and add the conjuncts that not eval in partition pruner
        if (partitionIds.isEmpty()) {
            noEvalConjuncts.addAll(partitionConjuncts);
            return null;
        }
        noEvalConjuncts.addAll(noEvalConjunctMap.values());
        return new ArrayList<>(partitionIds);
    }

    private boolean evalPartitionPruneFilter(ScalarOperator operator) {
        boolean bingo = false;
        if (operator instanceof BinaryPredicateOperator) {
            bingo = evalBinaryPredicate((BinaryPredicateOperator) operator);
        } else if (operator instanceof InPredicateOperator) {
            bingo = evalInPredicate((InPredicateOperator) operator);
        } else if (operator instanceof IsNullPredicateOperator) {
            bingo = evalIsNullPredicate((IsNullPredicateOperator) operator);
        } else if (operator instanceof CompoundPredicateOperator) {
            bingo = evalCompoundPredicate((CompoundPredicateOperator) operator);
        }
        return bingo;
    }

    private boolean evalIsNullPredicate(IsNullPredicateOperator operator) {
        ScalarOperator child = operator.getChild(0);
        if (!child.isConstantRef()) {
            return false;
        }
        ConstantOperator constantOperator = (ConstantOperator) child;
        if (!operator.isNotNull()) {
            return constantOperator.getValue() == null;
        }
        return constantOperator.getValue() != null;
    }

    private boolean evalCompoundPredicate(CompoundPredicateOperator operator) {
        List<ScalarOperator> children = operator.getChildren();
        Preconditions.checkState(children.size() == 2);
        ScalarOperator left = children.get(0);
        ScalarOperator right = children.get(1);
        switch (operator.getCompoundType()) {
            case AND:
                return evalPartitionPruneFilter(left) && evalPartitionPruneFilter(right);
            case OR:
                return evalPartitionPruneFilter(left) || evalPartitionPruneFilter(right);
            default:
                return false;
        }
    }

    private ConstantOperator castTargetDateType(ConstantOperator sourceConst, ConstantOperator targetConst) {
        // if source is not date type, return target directly
        if (!sourceConst.getType().isDateType()) {
            return targetConst;
        }
        // if target is date type, return target directly
        if (targetConst.getType().isDateType()) {
            return targetConst;
        }
        // if target is not date type, cast target to source type
        try {
            if (!targetConst.castTo(sourceConst.getType()).isPresent()) {
                throw new StarRocksPlannerException("cast target constant type to [" + sourceConst.getType() + "] fail",
                        ErrorType.INTERNAL_ERROR);
            }
            return targetConst.castTo(sourceConst.getType()).get();
        } catch (Exception e) {
            throw new StarRocksPlannerException("cast right constant type to  [" + sourceConst.getType() + "] fail",
                    ErrorType.INTERNAL_ERROR);
        }
    }

    private boolean evalBinaryPredicate(BinaryPredicateOperator operator) {
        ScalarOperator left = operator.getChild(0);
        ScalarOperator right = operator.getChild(1);
        if (!left.isConstant() || !right.isConstant()) {
            return false;
        }
        ConstantOperator leftConst = (ConstantOperator) left;
        ConstantOperator rightConst = (ConstantOperator) right;
        rightConst = castTargetDateType(leftConst, rightConst);
        leftConst = castTargetDateType(rightConst, leftConst);

        BinaryType type = operator.getBinaryType();
        switch (type) {
            case LT:
                return leftConst.compareTo(rightConst) < 0;
            case LE:
                return leftConst.compareTo(rightConst) <= 0;
            case GT:
                return leftConst.compareTo(rightConst) > 0;
            case GE:
                return leftConst.compareTo(rightConst) >= 0;
            case EQ:
                return leftConst.compareTo(rightConst) == 0;
            case NE:
            case EQ_FOR_NULL:
                return leftConst.compareTo(rightConst) != 0;
            default:
                return false;
        }
    }

    private boolean evalInPredicate(InPredicateOperator operator) {
        ScalarOperator left = operator.getChild(0);
        if (!left.isConstant()) {
            return false;
        }
        ConstantOperator leftConst = (ConstantOperator) left;
        List<ScalarOperator> rightList = operator.getChildren().stream().skip(1).collect(Collectors.toList());

        List<ScalarOperator> newSameRightList = new ArrayList<>();
        for (ScalarOperator right : rightList) {
            if (!right.isConstant()) {
                return false;
            }
            ConstantOperator rightConst = (ConstantOperator) right;

            rightConst = castTargetDateType(leftConst, rightConst);
            leftConst = castTargetDateType(rightConst, leftConst);

            newSameRightList.add(rightConst);
        }
        if (operator.isNotIn()) {
            return !newSameRightList.contains(leftConst);
        }
        return newSameRightList.contains(leftConst);
    }

    private Map<String, String> parseLocation(String input) {
        Map<String, String> partitionMap = new HashMap<>();

        // Split the input string by slashes '/'
        String[] parts = input.split("/");

        for (String part : parts) {
            // Split each part by equals sign '=' to get key-value pairs
            String[] keyValue = part.split("=");
            if (keyValue.length == 2) {
                // Trim any spaces and put the key-value pair into the map
                partitionMap.put(keyValue[0].trim(), keyValue[1].trim());
            }
        }

        return partitionMap;
    }

    public List<ScalarOperator> getNoEvalConjuncts() {
        return noEvalConjuncts;
    }
}
