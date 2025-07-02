package com.starrocks.plugin.auth;

import com.didi.datainfra.platform.auth.sdk.client.v2.TableAuthDreamClientV2;
import com.didi.datainfra.platform.auth.sdk.constant.Constants;
import com.didi.datainfra.platform.auth.sdk.entity.TableRowAuthDTO;
import com.didi.datainfra.platform.auth.sdk.entity.v2.ColumnItem;
import com.didi.datainfra.platform.auth.sdk.entity.v2.ConditionNode;
import com.didi.datainfra.platform.auth.sdk.entity.v2.LogicalNode;
import com.didi.datainfra.platform.auth.sdk.entity.v2.NonEquConditionNode;
import com.didi.datainfra.platform.auth.sdk.entity.v2.SQLNode;
import com.didi.datainfra.platform.auth.sdk.entity.v2.TableRowAuthV2Request;
import com.starrocks.analysis.TableName;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReportException;
import com.starrocks.sql.optimizer.operator.scalar.BinaryPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.CastOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.CompoundPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
import com.starrocks.sql.optimizer.operator.scalar.InPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperatorVisitor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class RemoteAuthChecker {
    private final AuthCheckerPlugin.AuthCheckerConf conf;
    private TableAuthDreamClientV2 tableAuthDreamClientV2 = null;

    public RemoteAuthChecker(AuthCheckerPlugin.AuthCheckerConf conf) {
        this.conf = conf;
    }

    public void open() {
        this.tableAuthDreamClientV2 = TableAuthDreamClientV2.srClient(
                conf.getHost(), conf.getPort(), conf.getTimeout(), conf.getMaxTotal(),
                conf.getMaxIdle(), conf.getMaxRetries(), conf.getRetryInterval());
    }

    public void close() {
        if (this.tableAuthDreamClientV2 != null) {
            this.tableAuthDreamClientV2.closeClientPool();
        }
    }

    public ErrorReportException check(ScanExprCollector.Context collectorCtx, UUID traceId) {
        List<ScanExprCollector.TableAuth> tableAuths = collectorCtx.getTables();
        TableRowAuthV2Request request = new TableRowAuthV2Request();
        request.setFilters(new ArrayList<>());
        request.setTables(new ArrayList<>());
        request.setAccount(collectorCtx.getAccount());
        for (int i = 0; i < tableAuths.size(); i++) {
            String id = i + "";
            ScanExprCollector.TableAuth tableAuth = tableAuths.get(i);
            ScalarOperator predicate = tableAuth.getPredicate();
            if (predicate != null) {
                AuthNodeConverter converter = new AuthNodeConverter(id, tableAuth.getTable());
                SQLNode root = predicate.accept(converter, null);
                request.addToFilters(root);
            }
            ColumnItem table = convertTable(tableAuth, id);
            request.addToTables(table);
        }
        request.setTraceId(traceId.toString());
        request.setApplicationId(conf.getAppId());

        TableRowAuthDTO tableRowAuthDTO = tableAuthDreamClientV2.checkRowAuth(request);
        if (tableRowAuthDTO != null) {
            if (!tableRowAuthDTO.isResult()) {
                return ErrorReportException.report(ErrorCode.ERR_COMMON_ERROR,
                        tableRowAuthDTO.getErrorLog());
            }
        }
        return null;
    }

    public static class AuthNodeConverter extends ScalarOperatorVisitor<SQLNode, Void> {

        private final String id;
        private final String dbName;
        private final String tblName;

        public AuthNodeConverter(String id, TableName tableName) {
            this.id = id;
            this.dbName = tableName.getDb();
            this.tblName = tableName.getTbl();
        }

        private SQLNode notEqualNode(List<ColumnRefOperator> columns) {
            List<ColumnItem> columnItems = convertColumns(columns);
            NonEquConditionNode nonEquConditionNode = new NonEquConditionNode().setColumns(columnItems).setNonEquFlag(true);
            return SQLNode.nonEquConditionNode(nonEquConditionNode);
        }

        private SQLNode equalNode(ColumnRefOperator column, List<ConstantOperator> constants) {
            List<String> values = constants.stream().map(ConstantOperator::toString).collect(Collectors.toList());
            ColumnItem columnItem = convertColumn(column);
            ConditionNode conditionNode = new ConditionNode(columnItem).setValues(values);
            conditionNode.setOperator(constants.size() == 1 ? "equal" : "in");
            return SQLNode.conditionNode(conditionNode);
        }

        private List<ColumnItem> convertColumns(List<ColumnRefOperator> columns) {
            return columns.stream()
                    .map(this::convertColumn)
                    .collect(Collectors.toList());
        }

        private ColumnItem convertColumn(ColumnRefOperator column) {
            return new ColumnItem().setTblId(id).setDbName(dbName).setTableName(tblName).setColumnName(column.getName());
        }

        @Override
        public SQLNode visit(ScalarOperator scalarOperator, Void context) {
            List<ColumnRefOperator> columnRefs = scalarOperator.getColumnRefs();
            return notEqualNode(columnRefs.stream().distinct().collect(Collectors.toList()));
        }

        @Override
        public SQLNode visitCompoundPredicate(CompoundPredicateOperator predicate, Void context) {
            if (predicate.isNot()) {
                List<ColumnRefOperator> columnRefs = predicate.getChild(0).getColumnRefs();
                return notEqualNode(columnRefs.stream().distinct().collect(Collectors.toList()));
            }
            SQLNode leftNode = predicate.getChild(0).accept(this, null);
            SQLNode rightNode = predicate.getChild(1).accept(this, null);
            String operator = predicate.isAnd() ? Constants.AND : Constants.OR;
            List<SQLNode> nodes = Arrays.asList(leftNode, rightNode);
            SQLNode sqlNode = new SQLNode();
            sqlNode.setLogicalNode(new LogicalNode(operator, nodes));
            return sqlNode;
        }

        @Override
        public SQLNode visitBinaryPredicate(BinaryPredicateOperator predicate, Void context) {
            ScalarOperator left = predicate.getChild(0);
            ScalarOperator right = predicate.getChild(1);

            ConstantOperator simpleConst = getSimpleConst(right);
            if (simpleConst != null && predicate.getBinaryType().isEqual()) {
                ColumnRefOperator colRef = getSimpleColumn(left);
                if (colRef != null) {
                    List<ConstantOperator> constants = Collections.singletonList((simpleConst));
                    return equalNode(colRef, constants);
                }
            }

            // others
            Set<ColumnRefOperator> cols = new HashSet<>();

            cols.addAll(left.getColumnRefs());
            cols.addAll(right.getColumnRefs());
            return notEqualNode(new ArrayList<>(cols));
        }


        @Override
        public SQLNode visitInPredicate(InPredicateOperator predicate, Void context) {
            
            List<ScalarOperator> values = predicate.getChildren().stream().skip(1).collect(Collectors.toList());
            if (!predicate.isNotIn() && values.stream().allMatch(ScalarOperator::isConstantRef)) {
                ColumnRefOperator column = getSimpleColumn(predicate.getChild(0));
                if (column != null) {
                    return equalNode(column, values.stream().map(a-> ((ConstantOperator) a)).collect(Collectors.toList()));
                }
            }
            Set<ColumnRefOperator> columns = new HashSet<>();
            for (ScalarOperator child : predicate.getChildren()) {
                columns.addAll(child.getColumnRefs());
            }
            return notEqualNode(new ArrayList<>(columns));
        }

    }

    private static ColumnRefOperator getSimpleColumn(ScalarOperator operator) {
        if (operator.isColumnRef()) {
            return ((ColumnRefOperator) operator);
        } else if (operator.isCast()) {
            CastOperator castOperator = (CastOperator) operator;
            if (castOperator.getChild(0).isColumnRef()) {
                return (ColumnRefOperator) castOperator.getChild(0);
            }
        }
        return null;
    }

    private static ConstantOperator getSimpleConst(ScalarOperator operator) {
        if (operator.isConstantRef()) {
            return ((ConstantOperator) operator);
        } else if (operator.isCast()) {
            CastOperator castOperator = (CastOperator) operator;
            if (castOperator.getChild(0).isConstantRef()) {
                return (ConstantOperator) castOperator.getChild(0);
            }
        }
        return null;
    }

    private static ColumnItem convertTable(ScanExprCollector.TableAuth table, String id) {
        return new ColumnItem()
                .setTblId(id)
                .setDbName(table.getTable().getDb())
                .setTableName(table.getTable().getTbl());
    }
}
