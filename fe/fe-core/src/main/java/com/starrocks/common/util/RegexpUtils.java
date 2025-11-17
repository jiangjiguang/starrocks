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

package com.starrocks.common.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RegexpUtils {
    private static final Logger LOG = LogManager.getLogger(RegexpUtils.class);

    private static boolean containsUnsupportedSyntax(String pattern) {
        // Features not supported by RE2
        String[] unsupportedFeatures = {
                "(?<!", // Negative backward
                "(?<=", // Forward and backward
                "(?!",  // Negative foresight
                "(?=",  // Positive outlook
                "\\G",  // The position where the last match ended
                "\\b{2,}", // Boundary repetition
                "(?|",  // Branch reset group
                "(?<",  // Name the capture group
                "(?P<", // Python-style named groups
                "(?'",  //.NET style named group
                "(*",   // Backtracking control verbs
                "(?C)", // PCRE call
                "\\Q",  // Start the reference
                "\\E"   // The reference ends
        };

        for (String feature : unsupportedFeatures) {
            if (pattern.contains(feature)) {
                return true;
            }
        }
        return false;
    }

    private static boolean evaluateWithJavaEngine(String expr, String pattern) {
        try {
            Pattern compiledPattern = Pattern.compile(pattern);
            Matcher matcher = compiledPattern.matcher(expr);
            return matcher.matches();
        } catch (Exception e) {
            throw new IllegalArgumentException("Java Regular expression error: " + e.getMessage());
        }
    }

    private static boolean evaluateWithRE2Engine(String expr, String pattern) {
        try {
            // Use the RE2J library (if available)
            com.google.re2j.Pattern re2Pattern = com.google.re2j.Pattern.compile(pattern);
            boolean isMatch = re2Pattern.matcher(expr).matches();
            if (isMatch) {
                return true;
            }
            // 回退到 Java 引擎
            return evaluateWithJavaEngine(expr, pattern);
        } catch (Exception e) {
            throw new IllegalArgumentException("RE2 Regular expression error: " + e.getMessage());
        }
    }

    public static boolean tryRegexp(String expr, String patternStr) {
        // Check if it contains syntax that is not supported by RE2
        if (containsUnsupportedSyntax(patternStr)) {
            // Use the Java standard regular expression engine
            return evaluateWithJavaEngine(expr, patternStr);
        } else {
            // Use the RE2 engine (if the environment supports it)
            return evaluateWithRE2Engine(expr, patternStr);
        }
    }



}
