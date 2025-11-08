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

package com.starrocks.connector.parser.trino;

import java.util.HashMap;
import java.util.Map;

public class TimeFormatConvert {
    private static final Map<String, String> FORMAT_MAP = new HashMap<>();
    static {
        FORMAT_MAP.put("yyyy", "%Y");
        FORMAT_MAP.put("MM", "%m");
        FORMAT_MAP.put("dd", "%d");
        FORMAT_MAP.put("HH", "%H");
        FORMAT_MAP.put("mm", "%i");
        FORMAT_MAP.put("ss", "%s");
    }

    public static String convertFormat(String format) {
        StringBuilder result = new StringBuilder();
        int pos = 0;

        while (pos < format.length()) {
            boolean matched = false;

            for (Map.Entry<String, String> entry : FORMAT_MAP.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();

                if (pos + key.length() <= format.length() &&
                        format.startsWith(key, pos)) {
                    result.append(value);
                    pos += key.length();
                    matched = true;
                    break;
                }
            }

            if (!matched) {
                result.append(format.charAt(pos));
                pos++;
            }
        }
        if (result.length() == 0) {
            return format;
        }
        return result.toString();
    }
}
