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
import com.starrocks.analysis.FunctionCallExpr;
import com.starrocks.analysis.FunctionParams;
import com.starrocks.analysis.ParseNode;
import com.starrocks.analysis.StringLiteral;
import com.starrocks.catalog.FunctionSet;
import org.apache.parquet.Strings;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class AstToEncryptFunctionSQLBuilder {
    public static String toSQL(ParseNode statement) {
        return new AST2EncryptFunctionSQLBuilderVisitor().visit(statement);
    }

    private static class AST2EncryptFunctionSQLBuilderVisitor extends AstToStringBuilder.AST2StringBuilderVisitor {

        @Override
        public String visitFunctionCall(FunctionCallExpr node, Void context) {
            FunctionParams functionParams = node.getParams();
            StringBuilder sb = new StringBuilder();
            String functionName = node.getFnName().getFunction();
            sb.append(functionName);

            sb.append("(");
            if (functionParams.isStar()) {
                sb.append("*");
            }
            if (functionParams.isDistinct()) {
                sb.append("DISTINCT ");
            }

            if (functionName.equalsIgnoreCase(FunctionSet.TIME_SLICE)
                    || functionName.equalsIgnoreCase(FunctionSet.DATE_SLICE)) {
                sb.append(visit(node.getChild(0))).append(", ");
                sb.append("INTERVAL ");
                sb.append(visit(node.getChild(1)));
                StringLiteral ident = (StringLiteral) node.getChild(2);
                sb.append(" ").append(ident.getValue());
                StringLiteral boundary = (StringLiteral) node.getChild(3);
                sb.append(", ").append(boundary.getValue());
                sb.append(")");
            } else {
                List<String> p = node.getChildren().stream().map(this::visit).collect(Collectors.toList());
                if (functionName.equalsIgnoreCase("sm4_encrypt")
                        || functionName.equalsIgnoreCase("sm4_decrypt")) {
                    for (int i = 0; i < node.getChildren().size(); i++) {
                        if (node.getChildren().get(i) instanceof StringLiteral) {
                            p.set(i, getEncryptCode(p.get(i), 3, 3));
                        }
                    }
                }
                sb.append(Joiner.on(", ").join(p)).append(")");
            }
            return sb.toString();
        }
    }

    public static String getEncryptCode(String code, int head, int tail) {
        if (Strings.isNullOrEmpty(code)) {
            return code;
        }
        int bodyLength = code.length() - head - tail;
        if (bodyLength <= 0) {
            char[] newCharAry = new char[code.length()];
            Arrays.fill(newCharAry, '*');
            return new String(newCharAry);
        }
        char[] charAry = new char[bodyLength];
        Arrays.fill(charAry, '*');
        String bodyStr = new String(charAry);
        String entryCodeStr = code.substring(0, head) + bodyStr + code.substring(head + bodyLength, code.length());
        return entryCodeStr;
    }
}
