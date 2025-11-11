package com.starrocks.plugin.auth;

import com.didi.datainfra.platform.auth.sdk.entity.v2.ColumnItem;
import com.didi.datainfra.platform.auth.sdk.entity.v2.ConditionNode;
import com.didi.datainfra.platform.auth.sdk.entity.v2.ExpressionNode;
import com.didi.datainfra.platform.auth.sdk.entity.v2.NonEquConditionNode;
import com.didi.datainfra.platform.auth.sdk.entity.v2.SQLNode;
import com.starrocks.analysis.BinaryType;
import com.starrocks.analysis.TableName;
import com.starrocks.catalog.Type;
import com.starrocks.sql.optimizer.operator.scalar.BinaryPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.CallOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
import com.starrocks.sql.optimizer.operator.scalar.InPredicateOperator;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for AuthNodeConverter
 */
public class AuthNodeConverterTest {

    private RemoteAuthChecker.AuthNodeConverter converter;
    private TableName tableName;
    private String tableId;

    @Before
    public void setUp() {
        tableId = "1";
        tableName = new TableName("test_db", "test_table");
        converter = new RemoteAuthChecker.AuthNodeConverter(tableId, tableName);
    }

    /**
     * Test case: column = constant
     * Expected: ConditionNode with operator "equal"
     */
    @Test
    public void testBinaryPredicateEqual() {
        // Create column reference: user_id
        ColumnRefOperator columnRef = new ColumnRefOperator(1, Type.INT, "user_id", true);

        // Create constant: 100
        ConstantOperator constant = ConstantOperator.createInt(100);

        // Create binary predicate: user_id = 100
        BinaryPredicateOperator predicate = new BinaryPredicateOperator(
                BinaryType.EQ, columnRef, constant);

        // Convert
        SQLNode result = converter.visitBinaryPredicate(predicate, null);

        // Verify
        assertNotNull(result);
        assertNotNull(result.getConditionNode());

        ConditionNode conditionNode = result.getConditionNode();
        assertEquals("equal", conditionNode.getOperator());
        assertEquals(1, conditionNode.getValues().size());
        assertEquals("100", conditionNode.getValues().get(0));

        // Verify column info
        ColumnItem column = conditionNode.getColumn();
        assertNotNull(column);
        assertEquals(tableId, column.getTblId());
        assertEquals("test_db", column.getDbName());
        assertEquals("test_table", column.getTableName());
        assertEquals("user_id", column.getColumnName());
    }

    /**
     * Test case: column != constant
     * Expected: NonEquConditionNode
     */
    @Test
    public void testBinaryPredicateNotEqual() {
        ColumnRefOperator columnRef = new ColumnRefOperator(1, Type.INT, "user_id", true);
        ConstantOperator constant = ConstantOperator.createInt(100);

        // Create binary predicate: user_id != 100
        BinaryPredicateOperator predicate = new BinaryPredicateOperator(
                BinaryType.NE, columnRef, constant);

        SQLNode result = converter.visitBinaryPredicate(predicate, null);

        assertNotNull(result);
        assertNotNull(result.getNonEquConditionNode());

        NonEquConditionNode nonEquNode = result.getNonEquConditionNode();
        assertTrue(nonEquNode.isNonEquFlag());
        assertEquals(1, nonEquNode.getColumns().size());

        ColumnItem column = nonEquNode.getColumns().get(0);
        assertEquals("user_id", column.getColumnName());
    }

    /**
     * Test case: column IN (value1, value2, value3)
     * Expected: ConditionNode with operator "in"
     */
    @Test
    public void testInPredicate() {
        ColumnRefOperator columnRef = new ColumnRefOperator(1, Type.INT, "status", true);

        // Create IN predicate: status IN (1, 2, 3)
        InPredicateOperator predicate = new InPredicateOperator(
                false, // not NOT IN
                Arrays.asList(
                        columnRef,
                        ConstantOperator.createInt(1),
                        ConstantOperator.createInt(2),
                        ConstantOperator.createInt(3)
                )
        );

        SQLNode result = converter.visitInPredicate(predicate, null);

        assertNotNull(result);
        assertNotNull(result.getConditionNode());

        ConditionNode conditionNode = result.getConditionNode();
        assertEquals("in", conditionNode.getOperator());
        assertEquals(3, conditionNode.getValues().size());
        assertTrue(conditionNode.getValues().contains("1"));
        assertTrue(conditionNode.getValues().contains("2"));
        assertTrue(conditionNode.getValues().contains("3"));

        ColumnItem column = conditionNode.getColumn();
        assertEquals("status", column.getColumnName());
    }

    @Test
    public void testExpression() {
        ColumnRefOperator column1 = new ColumnRefOperator(1, Type.VARCHAR, "city_id", true);
        ColumnRefOperator column2 = new ColumnRefOperator(1, Type.VARCHAR, "new_city_id", true);
        CallOperator nvlCall = new CallOperator("nvl", Type.VARCHAR, Arrays.asList(column1, column2));

        InPredicateOperator predicate = new InPredicateOperator(false,
                Arrays.asList(nvlCall,
                        ConstantOperator.createInt(1),
                        ConstantOperator.createInt(2),
                        ConstantOperator.createInt(3)));

        SQLNode result = converter.visitInPredicate(predicate, null);

        assertNotNull(result);
        assertNotNull(result.getExpressionNode());

        ExpressionNode expressionNode = result.getExpressionNode();
        List<ColumnItem> columns = expressionNode.getColumns();
        assertEquals(2, columns.size());
        List<String> values = expressionNode.getValues();
        assertEquals(3, values.size());
    }

}
