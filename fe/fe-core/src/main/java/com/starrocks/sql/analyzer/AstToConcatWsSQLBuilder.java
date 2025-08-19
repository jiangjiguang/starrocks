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

package com.starrocks.sql.analyzer;

import com.google.common.base.Joiner;
import com.google.common.collect.Maps;
import com.starrocks.analysis.AnalyticExpr;
import com.starrocks.analysis.CaseExpr;
import com.starrocks.analysis.CollectionElementExpr;
import com.starrocks.analysis.Expr;
import com.starrocks.analysis.FunctionCallExpr;
import com.starrocks.analysis.HintNode;
import com.starrocks.analysis.IntLiteral;
import com.starrocks.analysis.LiteralExpr;
import com.starrocks.analysis.ParseNode;
import com.starrocks.analysis.SetVarHint;
import com.starrocks.analysis.SlotRef;
import com.starrocks.analysis.StringLiteral;
import com.starrocks.common.DdlException;
import com.starrocks.http.BaseRequest;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.SessionVariable;
import com.starrocks.sql.ast.CTERelation;
import com.starrocks.sql.ast.QueryStatement;
import com.starrocks.sql.ast.SelectList;
import com.starrocks.sql.ast.SelectRelation;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.ast.SystemVariable;
import com.starrocks.sql.parser.NodePosition;
import org.antlr.v4.runtime.CommonToken;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AstToConcatWsSQLBuilder {
    private static final Logger LOG = LogManager.getLogger(AstToConcatWsSQLBuilder.class);

    // default separator for dps cluster
    private static final String DEFAULT_SEPARATOR = "char(001)";

    public static String toSQL(boolean simple,
                               boolean withoutTbl,
                               ParseNode statement,
                               String separator,
                               SessionVariable sessionVariable) {
        return new AstToConcatWsSQLBuilderVisitor(
                simple,
                withoutTbl,
                false,
                separator,
                sessionVariable
        ).visit(statement);
    }

    public static class AstToConcatWsSQLBuilderVisitor extends AstToSQLBuilder.AST2SQLBuilderVisitor {

        // Is it the outermost select
        boolean isOutermostSelect = true;

        // Whether it is a select within with
        private boolean inCTE = false;

        // Store the delimiter variable
        private final String concatSeparator;

        private final SessionVariable sessionVariable;

        public AstToConcatWsSQLBuilderVisitor(boolean simple,
                                              boolean withoutTbl,
                                              boolean hideCredential,
                                              String separator,
                                              SessionVariable sessionVariable) {
            super(simple, withoutTbl, hideCredential);
            this.concatSeparator = !StringUtils.isBlank(separator) ? separator : DEFAULT_SEPARATOR;
            this.sessionVariable = sessionVariable;
        }


        @Override
        public String visitSelect(SelectRelation stmt, Void context) {
            StringBuilder sqlBuilder = new StringBuilder();
            sqlBuilder.append("SELECT ");

            // keep hint
            if (stmt.getSelectList().getHintNodes() != null) {
                String hintContent = extractHintStr(stmt.getSelectList().getHintNodes());
                sqlBuilder.append("\n").append(hintContent).append("\n");
            }

            // make concat_ws expression
            if (isOutermostSelect && !inCTE) {
                sqlBuilder.append("CONCAT_WS(\n").append(concatSeparator).append(",\n");
                List<String> coalesceColumns = new ArrayList<>();
                getOriginFiledList(stmt, sessionVariable).forEach(columnName -> {
                    String coalesceColumn = String.format(
                            "COALESCE(CAST(%s AS STRING), 'null')",
                            columnName);
                    coalesceColumns.add(coalesceColumn);
                });
                sqlBuilder.append(String.join(",\n", coalesceColumns));
                sqlBuilder.append("\n) AS content");
                isOutermostSelect = false;
            } else {
                if (stmt.getSelectList().isDistinct()) {
                    sqlBuilder.append("DISTINCT ");
                }
                // get select item list
                List<String> selectListString = makeNormalSelectItem(stmt);
                sqlBuilder.append(Joiner.on(", ").join(selectListString));
            }

            String fromClause = visit(stmt.getRelation());
            if (fromClause != null) {
                sqlBuilder.append("\nFROM ");
                sqlBuilder.append(fromClause);
            }

            if (stmt.hasWhereClause()) {
                sqlBuilder.append("\nWHERE ");
                sqlBuilder.append(visit(stmt.getWhereClause()));
            }

            // Handle GROUP BY - convert location references to column names
            if (stmt.hasGroupByClause()) {
                sqlBuilder.append("\nGROUP BY ");
                List<String> groupByExprs = new ArrayList<>();
                for (Expr expr : stmt.getGroupByClause().getGroupingExprs()) {
                    if (expr instanceof IntLiteral) {
                        // Handle location references such as GROUP BY 1,2
                        int pos = (int) ((IntLiteral) expr).getValue();
                        if (pos > 0 && pos <= stmt.getOutputExpression().size()) {
                            groupByExprs.add(visit(stmt.getOutputExpression().get(pos - 1)));
                        }
                    } else {
                        groupByExprs.add(visit(expr));
                    }
                }
                sqlBuilder.append(String.join(", ", groupByExprs));
            }

            if (stmt.hasHavingClause()) {
                sqlBuilder.append("\nHAVING ").append(visit(stmt.getHavingClause()));
            }

            return sqlBuilder.toString();
        }


        @Override
        public String visitCTE(CTERelation relation, Void context) {
            // Enter the CTE range
            boolean prevInCTE = inCTE;
            inCTE = true;
            try {
                StringBuilder sqlBuilder = new StringBuilder();
                sqlBuilder.append("`").append(relation.getName()).append("`");

                if (relation.isResolvedInFromClause()) {
                    if (relation.getAlias() != null) {
                        sqlBuilder.append(" AS ").append(relation.getAlias().getTbl());
                    }
                    return sqlBuilder.toString();
                }

                sqlBuilder.append(" AS (").append(visit(relation.getCteQueryStatement())).append(")\n ");
                return sqlBuilder.toString();
            } finally {
                // Restore the CTE status
                inCTE = prevInCTE;
            }

        }

        @Override
        protected String extractHintStr(List<HintNode> hintNodes) {
            StringBuilder hintBuilder = new StringBuilder();
            for (HintNode hintNode : hintNodes) {
                String sql = hintNode.toSql();
                sql = sql.replace("/*+ SET_VAR( ", "");
                sql = sql.replace(" ) */", "");
                String[] tempArr = sql.split(", ");
                List<String> nodeList = new ArrayList<>();
                for (int i = 0; i < tempArr.length; i++) {
                    String[] temp = tempArr[i].split("=");
                    nodeList.add(tempArr[i]);
                }
                hintBuilder.append("/*+ SET_VAR( ").append(String.join(", ", nodeList)).append(" ) */");
                hintBuilder.append(" ");
            }
            return hintBuilder.toString();
        }

        @Override
        public String visitCollectionElementExpr(CollectionElementExpr node, Void context) {
            remakeCollectionElementExpr(node, sessionVariable);
            return visit(node.getChild(0)) + "[" + visit(node.getChild(1)) + "]";
        }

    }

    public static List<String> getOriginFiledList(SelectRelation stmt, SessionVariable sessionVariable) {
        List<String> columnList = new ArrayList<>();
        List<Field> fieldList = stmt.getScope().getRelationFields().getAllFields();
        if (fieldList.isEmpty()) {
            return new ArrayList<>();
        }
        boolean isCTE = stmt.getRelation() instanceof CTERelation;
        fieldList.forEach(field -> {
            String columnName = "";
            if (isCTE) {
                columnName = field.getName();
            } else {
                if (field.getOriginExpression() instanceof SlotRef) {
                    SlotRef slotRef = (SlotRef) field.getOriginExpression();
                    if (slotRef.getQualifiedName() != null) {
                        columnName = slotRef.getQualifiedName().toString();
                    } else {
                        columnName = slotRef.getColumnName();
                    }
                    columnName = String.format("`%s`", columnName);
                    if (slotRef.getType().isMapType()
                            || slotRef.getType().isArrayType()
                            || slotRef.getType().isStructType()
                            || field.getType().isMapType()
                            || field.getType().isArrayType()
                            || field.getType().isStructType()
                    ) {
                        columnName = String.format("TO_JSON(%s)", columnName);
                    }
                } else if (field.getOriginExpression() instanceof FunctionCallExpr) {
                    FunctionCallExpr functionCallExpr = (FunctionCallExpr) field.getOriginExpression();
                    columnName = functionCallExpr.toSql();
                } else if (field.getOriginExpression() instanceof AnalyticExpr) {
                    AnalyticExpr analyticExpr = (AnalyticExpr) field.getOriginExpression();
                    columnName = analyticExpr.toSqlWithoutTbl();
                } else if (field.getOriginExpression() instanceof LiteralExpr) {
                    LiteralExpr literalExpr = (LiteralExpr) field.getOriginExpression();
                    columnName = literalExpr.toSql();
                } else if (field.getOriginExpression() instanceof CaseExpr) {
                    CaseExpr caseExpr = (CaseExpr) field.getOriginExpression();
                    columnName = caseExpr.toSqlImpl();
                } else if (field.getOriginExpression() instanceof CollectionElementExpr) {
                    CollectionElementExpr node = (CollectionElementExpr) field.getOriginExpression();
                    remakeCollectionElementExpr(node, sessionVariable);
                    columnName = node.toSqlImpl();
                } else {
                    LOG.info("didn't get the instance typ in field list, so we cast to origin type, field: {}", field);
                    columnName = field.getOriginExpression().toSql();
                }
            }

            if (!StringUtils.isBlank(columnName)) {
                columnList.add(columnName);
            }
        });
        if (fieldList.size() != columnList.size()) {
            throw new RuntimeException(String.format(
                    "It might be that the parser did not handle all the fields, fieldList:%s, columnList:%s",
                    fieldList, columnList));
        }
        if (stmt.getSelectList().isDistinct()) {
            String newColumn = "DISTINCT " + columnList.get(0);
            columnList.set(0, newColumn);
        }
        return columnList;
    }

    protected static void remakeCollectionElementExpr(CollectionElementExpr node, SessionVariable sessionVariable) {
        Expr expr = node.getChild(0);
        Expr subscript = node.getChild(1);
        if (expr.getType().isArrayType()) {
            if (!subscript.getType().isNumericType()) {
                throw new SemanticException("array subscript must have type integer", subscript.getPos());
            }
            if (sessionVariable.isEnablePrestoTranslation()) {
                if (subscript instanceof IntLiteral) {
                    IntLiteral newSubscript = (IntLiteral) subscript;
                    long idx = newSubscript.getValue();
                    if (idx < 0) {
                        throw new SemanticException("The array subscript must be greater than or equal to 1");
                    }
                    // During the analyzer stage, the subscript has already been automatically incremented by 1.
                    // Therefore, when reverting to sql, it needs to be decremented by 1 to restore.
                    // Otherwise, if the generated submit task sql is resubmitted to sr,
                    // it will be incremented by 1 again when entering the analyzer stage
                    idx = idx - 1;
                    IntLiteral fitPrestoSubscript = new IntLiteral(idx);
                    node.setChild(1, fitPrestoSubscript);
                    subscript = node.getChild(1);
                }
            }
        }
    }

    public static void initSessionVariableByQueryHint(StatementBase statementBase, ConnectContext ctx) {
        if (statementBase == null) {
            return;
        }
        if (statementBase.getAllQueryScopeHints() == null) {
            return;
        }
        if (statementBase.getAllQueryScopeHints().isEmpty()) {
            return;
        }

        statementBase.getAllQueryScopeHints().forEach(item -> {
            if (item instanceof SetVarHint) {
                SetVarHint setVarHint = (SetVarHint) item;
                setVarHint.getValue().keySet().forEach(key -> {
                    if (!key.equalsIgnoreCase("separator")) {
                        String value = setVarHint.getValue().get(key);
                        try {
                            ctx.modifySystemVariable(new SystemVariable(key, new StringLiteral(value)), true);
                        } catch (DdlException e) {
                            // not session variable
                            LOG.warn(String.format("set key:[%s] for value:[%s] faile", key, value), e);
                        }
                    }
                });
            }
        });
    }


    public static List<HintNode> initSessionVariableByRequestParams(ConnectContext ctx,
                                                                    BaseRequest request,
                                                                    List<HintNode> hintNodeList) {
        List<String> hintList = new ArrayList<>();
        Map<String, String> valueMap = Maps.newHashMap();
        Map<String, List<String>> paramsList = request.getAllParameters();
        if (paramsList.isEmpty()) {
            return hintNodeList;
        }

        // init session to context
        paramsList.keySet().forEach(key -> {
            if (!key.equalsIgnoreCase("separator")) {
                String value = null;
                try {
                    value = request.getSingleParameter(key);
                    ctx.modifySystemVariable(new SystemVariable(key, new StringLiteral(value)), true);
                    hintList.add(String.format("%s='%s'", key, value));
                    valueMap.put(key, value);
                } catch (DdlException e) {
                    // not session variable
                    LOG.warn(String.format("set key:[%s] for value:[%s] fail", key, value), e);
                }
            }
        });

        if (hintList.isEmpty()) {
            return hintNodeList;
        }
        String hintStr = String.format("/*+ SET_VAR( %s ) */", String.join(", ", hintList));
        CommonToken token = new CommonToken(1, hintStr);
        HintNode hintNode = new SetVarHint(new NodePosition(token), valueMap, token.getText());
        hintNodeList.add(hintNode);
        return hintNodeList;
    }

    public static void initHintNodeListInStatement(StatementBase statementBase,
                                             List<HintNode> hintNodeList) {
        if (!(statementBase instanceof QueryStatement)) {
            return;
        }
        QueryStatement queryStatement = (QueryStatement) statementBase;
        if (!(queryStatement.getQueryRelation() instanceof SelectRelation)) {
            return;
        }
        SelectRelation selectRelation = (SelectRelation) queryStatement.getQueryRelation();
        SelectList selectList = selectRelation.getSelectList();
        selectList.setHintNodes(hintNodeList);
    }
}
