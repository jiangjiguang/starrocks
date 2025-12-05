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
            // Roll back to the Java engine
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

    /**
     * Make sure the regular expression pattern has capture groups.
     * If there are no capture groups () in the pattern, add them on the outermost layer
     * @param pattern String
     * @return String
     */
    public static String ensureCaptureGroup(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return pattern;
        }

        // Check if there is already a capture group
        // The parentheses that need to be escaped and the parentheses in the character class should be ignored
        int depth = 0;
        boolean inCharClass = false;
        boolean escaped = false;
        boolean hasCaptureGroup = false;

        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\') {
                escaped = true;
                continue;
            }

            if (c == '[' && !escaped) {
                inCharClass = true;
                continue;
            }

            if (c == ']' && !escaped && inCharClass) {
                inCharClass = false;
                continue;
            }

            if (!inCharClass) {
                if (c == '(') {
                    depth++;
                    // // Check if it is a capture group ( not a non-capture group (?:) or atomic groups  (?>) )
                    if (i + 2 < pattern.length() && pattern.charAt(i + 1) == '?' &&
                            (pattern.charAt(i + 2) == ':' || pattern.charAt(i + 2) == '>')) {
                        // Non-capture groups or atomic groups are not counted as capture groups
                    } else if (depth == 1) {
                        hasCaptureGroup = true;
                    }
                } else if (c == ')') {
                    if (depth > 0) {
                        depth--;
                    }
                }
            }
        }

        // If there is no capture group, add one
        if (!hasCaptureGroup) {
            // Check if parentheses need to be added
            // and also consider whether the pattern already begins with ^ or ends with $
            String wrappedPattern;
            if (pattern.startsWith("^") && pattern.endsWith("$")) {
                // If it is already a complete match mode, add parentheses in the middle
                wrappedPattern = "^(" + pattern.substring(1, pattern.length() - 1) + ")$";
            } else {
                // In normal mode, add parentheses directly on the outermost layer
                wrappedPattern = "(" + pattern + ")";
            }
            return wrappedPattern;
        }

        return pattern;
    }

    /**
     * Convert the Unicode escape sequence \\uXXXX to the format \\x{XXXX} recognized by the RE2 engine
     * @param pattern String
     * @return String
     */
    public static String convertUnicodeEscapes(String pattern) {
        if (pattern == null || pattern.isEmpty() || !pattern.contains("\\u")) {
            return pattern;
        }

        StringBuilder result = new StringBuilder();
        int i = 0;
        int length = pattern.length();

        while (i < length) {
            char c = pattern.charAt(i);

            // Check if it is a \\u escape sequence
            if (c == '\\' && i + 1 < length && pattern.charAt(i + 1) == 'u') {
                // Handle \\u escape
                // Skip \\u
                i += 2;

                // skip {
                if (i < length && pattern.charAt(i) == '{') {
                    // \\u{XXXXXX} format
                    i++;
                    StringBuilder hex = new StringBuilder();
                    while (i < length && isHexDigit(pattern.charAt(i))) {
                        hex.append(pattern.charAt(i));
                        i++;
                    }

                    // skip }
                    if (i < length && pattern.charAt(i) == '}') {
                        i++;
                        result.append("\\x{").append(hex).append("}");
                        continue;
                    } else {
                        // format error, Keep the original string output
                        result.append("\\u{").append(hex);
                        continue;
                    }
                } else {
                    // \\uXXXX format (4-digit hexadecimal)
                    if (i + 3 < length &&
                            isHexDigit(pattern.charAt(i)) &&
                            isHexDigit(pattern.charAt(i + 1)) &&
                            isHexDigit(pattern.charAt(i + 2)) &&
                            isHexDigit(pattern.charAt(i + 3))) {

                        String hex = pattern.substring(i, i + 4);
                        result.append("\\x{").append(hex).append("}");
                        i += 4;
                        continue;
                    } else {
                        // format error, Keep the original string output
                        result.append("\\u");
                        continue;
                    }
                }
            }

            // Ordinary characters
            result.append(c);
            i++;
        }

        return result.toString();
    }

    /**
     * Check whether the characters are hexadecimal digits
     * @param c char
     * @return boolean
     */
    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') ||
                (c >= 'a' && c <= 'f') ||
                (c >= 'A' && c <= 'F');
    }


}
