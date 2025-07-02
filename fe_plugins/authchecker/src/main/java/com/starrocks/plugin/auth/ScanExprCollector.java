package com.starrocks.plugin.auth;

import com.starrocks.analysis.TableName;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptExpressionVisitor;
import com.starrocks.sql.optimizer.operator.logical.LogicalScanOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalScanOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ScanExprCollector extends OptExpressionVisitor<Void, ScanExprCollector.Context> {

    @Override
    public Void visit(OptExpression optExpression, Context context) {
        for (OptExpression input : optExpression.getInputs()) {
            input.getOp().accept(this, input, context);
        }
        return null;
    }

    @Override
    public Void visitPhysicalScan(OptExpression optExpression, Context context) {
        PhysicalScanOperator physicalScanOperator = (PhysicalScanOperator) optExpression.getOp();
        context.addTableAuth(physicalScanOperator.getTable().getId(), physicalScanOperator.getPredicate());
        return null;
    }

    @Override
    public Void visitLogicalTableScan(OptExpression optExpression, Context context) {
        LogicalScanOperator scanOperator = (LogicalScanOperator) optExpression.getOp();
        context.addTableAuth(scanOperator.getTable().getId(), scanOperator.getPredicate());
        return null;
    }

    public static class Context {
        private Map<Long, TableName> tableAuthMap = new HashMap<>();
        private List<TableAuth> tables = new ArrayList<>();
        private String account;

        public String getAccount() {
            return account;
        }

        public void setAccount(String account) {
            this.account = account;
        }

        public void addTable(Long id, TableName table) {
            tableAuthMap.put(id, table);
        }

        public void addTableAuth(long id, ScalarOperator predicate) {
            TableName tableName = tableAuthMap.get(id);
            if (tableName != null) {
                tables.add(new TableAuth(tableName, predicate));
            }
        }

        public List<TableAuth> getTables() {
            return tables;
        }
    }

    public static class TableAuth {
        private long id;
        private TableName table;
        private ScalarOperator predicate;

        public TableAuth(TableName table, ScalarOperator predicate) {
            this.table = table;
            this.predicate = predicate;
        }

        public TableName getTable() {
            return table;
        }

        public ScalarOperator getPredicate() {
            return predicate;
        }
    }

}
