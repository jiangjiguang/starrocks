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

import com.starrocks.analysis.FunctionCallExpr;
import com.starrocks.analysis.HintNode;
import com.starrocks.analysis.ParseNode;
import com.starrocks.analysis.SlotRef;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.ast.SelectList;
import com.starrocks.sql.ast.SelectListItem;
import com.starrocks.sql.ast.SelectRelation;
import mockit.Mocked;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class AstToConcatWsSqlBuilderTest {
    @Mocked
    private ParseNode mockParseNode;
    @Mocked
    private SelectRelation mockSelectRelation;
    @Mocked
    private SelectList mockSelectList;
    @Mocked
    private RelationFields mockRelationFields;
    @Mocked
    private Field mockField1;
    @Mocked
    private Field mockField2;
    @Mocked
    private SlotRef mockSlotRef;
    @Mocked
    private FunctionCallExpr mockFunctionCallExpr;
    @Mocked
    private ConnectContext mockConnectContext;

    private List<Field> fieldList = new ArrayList<>();
    private List<HintNode> hintNodes = new ArrayList<>();

    @Before
    public void setUp() {
        when(mockSelectRelation.getSelectList()).thenReturn(mockSelectList);
        when(mockSelectList.getHintNodes()).thenReturn(hintNodes);

        when(mockSelectRelation.getScope()).thenReturn(mock(Scope.class));
        when(mockSelectRelation.getScope().getRelationFields()).thenReturn(mockRelationFields);
        when(mockRelationFields.getAllFields()).thenReturn(fieldList);
    }

    @Test
    public void testToSQLWithDefaultSeparator() {
        ConnectContext session = AnalyzeTestUtil.getConnectContext();
        session.getSessionVariable().setEnablePrestoTranslation(true);
        String result = AstToConcatWsSQLBuilder.toSQL(false, false, mockParseNode, null,
                session.getSessionVariable());
        assertNotNull(result);
        // Verify the default separator is used
        assertTrue(result.contains("CONCAT_WS(char(001)"));
    }

    @Test
    public void testToSQLWithCustomSeparator() {
        ConnectContext session = AnalyzeTestUtil.getConnectContext();
        session.getSessionVariable().setEnablePrestoTranslation(true);
        String customSeparator = "','";
        String result = AstToConcatWsSQLBuilder.toSQL(false, false, mockParseNode, customSeparator,
                session.getSessionVariable());
        assertNotNull(result);
        assertTrue(result.contains("CONCAT_WS(" + customSeparator));
    }

    @Test
    public void testGetOriginFieldListWithFunctionCall() {
        ConnectContext session = AnalyzeTestUtil.getConnectContext();
        session.getSessionVariable().setEnablePrestoTranslation(true);
        fieldList.add(mockField1);
        when(mockField1.getOriginExpression()).thenReturn(mockFunctionCallExpr);
        when(mockFunctionCallExpr.toSql()).thenReturn("COUNT(*)");

        List<String> fields = AstToConcatWsSQLBuilder.getOriginFiledList(mockSelectRelation, session.getSessionVariable());
        assertEquals(1, fields.size());
        assertEquals("COUNT(*)", fields.get(0));
    }

    @Test
    public void testGetOriginFieldListWithMixedTypes() {
        ConnectContext session = AnalyzeTestUtil.getConnectContext();
        session.getSessionVariable().setEnablePrestoTranslation(true);
        // Add SlotRef field
        fieldList.add(mockField1);
        when(mockField1.getOriginExpression()).thenReturn(mockSlotRef);
        when(mockSlotRef.getColumnName()).thenReturn("column1");

        // Add FunctionCallExpr field
        fieldList.add(mockField2);
        when(mockField2.getOriginExpression()).thenReturn(mockFunctionCallExpr);
        when(mockFunctionCallExpr.toSql()).thenReturn("SUM(column2)");

        List<String> fields = AstToConcatWsSQLBuilder.getOriginFiledList(mockSelectRelation, session.getSessionVariable());
        assertEquals(2, fields.size());
        assertTrue(fields.contains("column1"));
        assertTrue(fields.contains("SUM(column2)"));
    }


    @Test
    public void testInitSessionVariableWithNullStatement() {
        // Should not throw exception
        AstToConcatWsSQLBuilder.initSessionVariableByQueryHint(null, mockConnectContext);
        verifyNoInteractions(mockConnectContext);
    }


    @Test
    public void testVisitSelectWithOutermostSelect() {
        ConnectContext session = AnalyzeTestUtil.getConnectContext();
        session.getSessionVariable().setEnablePrestoTranslation(true);
        AstToConcatWsSQLBuilder.AstToConcatWsSQLBuilderVisitor visitor =
                new AstToConcatWsSQLBuilder.AstToConcatWsSQLBuilderVisitor(false, false, false, "|",
                        session.getSessionVariable());

        when(mockSelectRelation.getOutputExpression()).thenReturn(new ArrayList<>());

        String result = visitor.visitSelect(mockSelectRelation, null);
        assertTrue(result.contains("CONCAT_WS(|"));
        assertTrue(result.contains("AS combined_result"));
    }

    @Test
    public void testVisitSelectWithNestedSelect() {
        ConnectContext session = AnalyzeTestUtil.getConnectContext();
        session.getSessionVariable().setEnablePrestoTranslation(true);
        AstToConcatWsSQLBuilder.AstToConcatWsSQLBuilderVisitor visitor =
                new AstToConcatWsSQLBuilder.AstToConcatWsSQLBuilderVisitor(false, false, false, "|",
                        session.getSessionVariable());
        visitor.isOutermostSelect = false; // Simulate nested select

        List<SelectListItem> selectListItems = new ArrayList<>();
        when(mockSelectList.getItems()).thenReturn(selectListItems);

        String result = visitor.visitSelect(mockSelectRelation, null);
        assertFalse(result.contains("CONCAT_WS"));
        assertFalse(result.contains("AS combined_result"));
    }
}
