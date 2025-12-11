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


package com.starrocks.connector.hive;

import com.starrocks.common.Config;
import com.starrocks.connector.exception.StarRocksConnectorException;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.SessionVariable;
import com.starrocks.thrift.TTextFileDesc;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class TextFileFormatDesc {
    private static final Logger LOG = LogManager.getLogger(TextFileFormatDesc.class);

    private final String fieldDelim;

    private final String lineDelim;

    // Control hive array's element delimiter.
    private final String collectionDelim;

    // mapkey_delimiter is the separator between key and value in map.
    // For example, {"smith": age} mapkey_delimiter is ':'.
    private final String mapkeyDelim;

    private final int skipHeaderLineCount;

    private final String quoteChar;

    private final String escapeChar;

    public TextFileFormatDesc(String fieldDelim, String lineDelim, String collectionDelim, String mapkeyDelim) {
        this(fieldDelim, lineDelim, collectionDelim, mapkeyDelim, 0, null, null);
    }

    public TextFileFormatDesc(String fieldDelim, String lineDelim, String collectionDelim, String mapkeyDelim,
                              int skipHeaderLineCount) {
        this(fieldDelim, lineDelim, collectionDelim, mapkeyDelim, skipHeaderLineCount, null, null);
    }

    public TextFileFormatDesc(String fieldDelim, String lineDelim, String collectionDelim, String mapkeyDelim,
                              int skipHeaderLineCount, String quoteChar, String escapeChar) {
        this.fieldDelim = parseFieldDelim(fieldDelim);
        this.lineDelim = lineDelim;
        this.collectionDelim = collectionDelim;
        this.mapkeyDelim = mapkeyDelim;
        this.skipHeaderLineCount = skipHeaderLineCount;
        this.quoteChar = quoteChar;
        this.escapeChar = escapeChar;
    }

    public String getFieldDelim() {
        return fieldDelim;
    }

    public String getLineDelim() {
        return lineDelim;
    }

    public String getCollectionDelim() {
        return collectionDelim;
    }

    public String getMapkeyDelim() {
        return mapkeyDelim;
    }

    public int getSkipHeaderLineCount() {
        return skipHeaderLineCount;
    }

    public TTextFileDesc toThrift() {
        TTextFileDesc desc = new TTextFileDesc();
        String newFieldDelimiter = rewriteHiveTablePartitionCustomDelimiter(fieldDelim);
        if (newFieldDelimiter != null) {
            desc.setField_delim(newFieldDelimiter);
        }
        if (lineDelim != null) {
            desc.setLine_delim(lineDelim);
        }
        if (collectionDelim != null) {
            desc.setCollection_delim(collectionDelim);
        }
        if (mapkeyDelim != null) {
            desc.setMapkey_delim(mapkeyDelim);
        }
        if (!StringUtils.isBlank(quoteChar)) {
            byte[] bs = quoteChar.getBytes();
            desc.setEnclose(bs[0]);
        }
        if (!StringUtils.isBlank(escapeChar)) {
            byte[] bs = escapeChar.getBytes();
            desc.setEscape(bs[0]);
        }
        desc.setSkip_header_line_count(skipHeaderLineCount);
        return desc;
    }

    public String rewriteHiveTablePartitionCustomDelimiter(String fieldDelimiter) {
        if (ConnectContext.get() == null) {
            return fieldDelimiter;
        }
        SessionVariable sessionVariable = ConnectContext.get().getSessionVariable();
        if (sessionVariable == null) {
            return fieldDelimiter;
        }
        if (!sessionVariable.isEnableHiveUDFCustomFormat()) {
            return fieldDelimiter;
        }
        if (sessionVariable.getHiveUdfCustomFormatFieldDelimiter() == null) {
            return fieldDelimiter;
        }
        return parseFieldDelim(sessionVariable.getHiveUdfCustomFormatFieldDelimiter());
    }

    public String parseFieldDelim(String fieldDelim) {
        if (!Config.enable_presto_field_delim) {
            return fieldDelim;
        }
        try {
            return HiveFieldDelimiter.convert(fieldDelim);
        } catch (StarRocksConnectorException e) {
            LOG.debug("hive field.delim convert fail, reason is: ", e);
            return fieldDelim;
        }
    }

    @Override
    public String toString() {
        final StringBuffer sb = new StringBuffer("TextFileFormatDesc{");
        sb.append("fieldDelim='").append(fieldDelim).append('\'');
        sb.append(", lineDelim='").append(lineDelim).append('\'');
        sb.append(", collectionDelim='").append(collectionDelim).append('\'');
        sb.append(", mapkeyDelim='").append(mapkeyDelim).append('\'');
        sb.append(", skipHeaderLineCount='").append(skipHeaderLineCount).append('\'');
        sb.append('}');
        return sb.toString();
    }
}
