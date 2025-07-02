package com.starrocks.plugin.auth;

import com.starrocks.analysis.Expr;
import com.starrocks.analysis.Subquery;
import com.starrocks.analysis.TableName;
import com.starrocks.catalog.ConnectorView;
import com.starrocks.catalog.HiveTable;
import com.starrocks.catalog.Table;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.analyzer.AnalyzeState;
import com.starrocks.sql.analyzer.ExpressionAnalyzer;
import com.starrocks.sql.analyzer.QueryAnalyzer;
import com.starrocks.sql.analyzer.RelationFields;
import com.starrocks.sql.analyzer.RelationId;
import com.starrocks.sql.analyzer.Scope;
import com.starrocks.sql.analyzer.SelectAnalyzer;
import com.starrocks.sql.ast.Relation;
import com.starrocks.sql.ast.SelectRelation;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.ast.TableFunctionRelation;
import com.starrocks.sql.ast.TableRelation;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static com.starrocks.catalog.HiveTable.HiveTableType.MANAGED_TABLE;
import static com.starrocks.server.CatalogMgr.ResourceMappingCatalog.toResourceName;
import static com.starrocks.sql.common.UnsupportedException.unsupportedException;

public class AuthAnalyzer extends QueryAnalyzer {
    private ScanExprCollector.Context ctx;
    private ConnectContext session;

    public AuthAnalyzer(ConnectContext session, ScanExprCollector.Context collectorCtx) {
        super(session);
        this.ctx = collectorCtx;
        this.session = session;
    }

    @Override
    public void analyze(StatementBase node) {
        new AuthQueryVisitor().process(node, new Scope(RelationId.anonymous(), new RelationFields()));
    }

    @Override
    public Table resolveTable(TableRelation tableRelation) {
        Table table = super.resolveTable(tableRelation);
        ctx.addTable(table.getId(), tableRelation.getName());
        if (table instanceof ConnectorView) {
            ConnectorView view = (ConnectorView) table;
            HiveTable hiveTable = convertView(view, tableRelation);
            return hiveTable;
        }
        return table;
    }


    public class AuthQueryVisitor extends Visitor {

        @Override
        public Scope visitSelect(SelectRelation selectRelation, Scope scope) {
            AnalyzeState analyzeState = new AnalyzeState();
            //Record aliases at this level to prevent alias conflicts
            Set<TableName> aliasSet = new HashSet<>();
            Relation resolvedRelation = resolveTableRef(selectRelation.getRelation(), scope, aliasSet);
            if (resolvedRelation instanceof TableFunctionRelation) {
                throw unsupportedException("Table function must be used with lateral join");
            }
            selectRelation.setRelation(resolvedRelation);
            //for avoid init column meta, try to prune unused columns
            pruneScanColumns(selectRelation, resolvedRelation);
            Scope sourceScope = process(resolvedRelation, scope);
            sourceScope.setParent(scope);

            AuthSelectAnalyzer selectAnalyzer = new AuthSelectAnalyzer(session, ctx);
            selectAnalyzer.analyze(
                    analyzeState,
                    selectRelation.getSelectList(),
                    selectRelation.getRelation(),
                    sourceScope,
                    selectRelation.getGroupByClause(),
                    selectRelation.getHavingClause(),
                    selectRelation.getWhereClause(),
                    selectRelation.getOrderBy(),
                    selectRelation.getLimit());

            selectRelation.fillResolvedAST(analyzeState);
            GeneratedColumnExprMappingCollector collector = new GeneratedColumnExprMappingCollector();
            collector.process(selectRelation, sourceScope);
            return analyzeState.getOutputScope();
        }
    }

    private static class AuthSelectAnalyzer extends SelectAnalyzer {
        private ConnectContext session;
        private ScanExprCollector.Context ctx;

        public AuthSelectAnalyzer(ConnectContext session, ScanExprCollector.Context collectorCtx) {
            super(session);
            this.session = session;
            this.ctx = collectorCtx;
        }

        @Override
        protected void analyzeExpression(Expr expr, AnalyzeState analyzeState, Scope scope) {
            AuthExpressionAnalyzer expressionAnalyzer = new AuthExpressionAnalyzer(session, ctx);
            expressionAnalyzer.analyze(expr, analyzeState, scope);
        }
    }

    private static class AuthExpressionAnalyzer extends ExpressionAnalyzer {
        private ConnectContext session;
        private ScanExprCollector.Context ctx;

        public AuthExpressionAnalyzer(ConnectContext session, ScanExprCollector.Context ctx) {
            super(session);
            this.session = session;
            this.ctx = ctx;
        }

        @Override
        public void analyze(Expr expression, AnalyzeState analyzeState, Scope scope) {
            AuthExpressionVisitor visitor = new AuthExpressionVisitor(analyzeState, session, ctx);
            bottomUpAnalyze(visitor, expression, scope);
        }
    }

    private static class AuthExpressionVisitor extends ExpressionAnalyzer.Visitor {
        private ConnectContext session;
        private ScanExprCollector.Context ctx;

        public AuthExpressionVisitor(AnalyzeState analyzeState, ConnectContext session, ScanExprCollector.Context ctx) {
            super(analyzeState, session);
            this.session = session;
            this.ctx = ctx;
        }

        @Override
        public Void visitSubquery(Subquery node, Scope context) {
            AuthAnalyzer queryAnalyzer = new AuthAnalyzer(session, ctx);
            queryAnalyzer.analyze(node.getQueryStatement(), context);
            node.setType(node.getQueryStatement().getQueryRelation().getRelationFields().getFieldByIndex(0).getType());
            return null;
        }
    }

    private HiveTable convertView(ConnectorView view, TableRelation tableRelation) {
        HiveTable.Builder tableBuilder = HiveTable.builder()
                .setId(view.getId())
                .setTableName(view.getName())
                .setCatalogName(view.getCatalogName())
                .setResourceName(toResourceName(view.getCatalogName(), "hive"))
                .setHiveDbName(tableRelation.getName().getDb())
                .setHiveTableName(tableRelation.getName().getTbl())
                .setPartitionColumnNames(Collections.emptyList())
                .setDataColumnNames(Collections.emptyList())
                .setFullSchema(view.getFullSchema())
                .setComment(view.getComment())
                .setTableLocation(null)
                .setProperties(null)
                .setSerdeProperties(null)
                .setStorageFormat(null)
                .setCreateTime(view.getCreateTime())
                .setHiveTableType(MANAGED_TABLE);
        return tableBuilder.build();

    }
}
