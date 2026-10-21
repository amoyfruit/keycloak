/*
 * Copyright 2025 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.protocol.oid4vc.issuance.mappers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Extracts the set of field (or alias) names a configured GraphQL query selects at a given Response Path,
 * without executing the query. GraphQL cannot return a field that wasn't requested, so the selection set in
 * the query text is both a reliable and an exhaustive description of what the response at that path can
 * contain — used by {@link OID4VCGraphQLClaimMapper} to reject reserved claim names and declare issuer
 * metadata before ever calling the external endpoint, instead of only finding out at issuance time.
 *
 * <p>Deliberately minimal: this only navigates selection sets (field names, aliases, and skipping over
 * arguments/variable-definitions as opaque parenthesized blocks) to answer "what does the response at this
 * path look like". It does not interpret argument values, directives, or fragments — a query using any of
 * those, or anything else outside this grammar, is reported as unparseable ({@link Optional#empty()}) rather
 * than guessed at. Callers fall back to runtime-only checks when that happens.
 */
final class GraphQLSelectionSetParser {

    private GraphQLSelectionSetParser() {
    }

    /**
     * The three things a caller might need to know about the response at a configured Response Path, without
     * running the query: {@code UNRESOLVABLE} (the query or path doesn't parse under this class's minimal
     * grammar — callers can't tell anything, so fall back to runtime-only checks), {@code SCALAR} (the path
     * resolves to a field with no nested selection set — a Scalar/Array response, where Claim Name applies),
     * or {@code OBJECT} (the field has a nested selection set — {@link #fieldNames} are its immediate
     * children's output keys). These are deliberately distinct: an {@code UNRESOLVABLE} query might still turn
     * out to be an Object response at runtime, so a caller declaring metadata must not treat it the same as a
     * confirmed {@code SCALAR}.
     */
    record Resolution(Kind kind, List<String> fieldNames) {
        enum Kind { UNRESOLVABLE, SCALAR, OBJECT }

        static final Resolution UNRESOLVABLE = new Resolution(Kind.UNRESOLVABLE, null);
        static final Resolution SCALAR = new Resolution(Kind.SCALAR, null);

        static Resolution object(List<String> fieldNames) {
            return new Resolution(Kind.OBJECT, fieldNames);
        }
    }

    static Resolution resolve(String query, String responsePath) {
        if (query == null) {
            return Resolution.UNRESOLVABLE;
        }
        var trimmed = query.stripLeading();
        var braceIndex = trimmed.indexOf('{');
        if (braceIndex < 0) {
            return Resolution.UNRESOLVABLE;
        }

        var segments = (responsePath == null || responsePath.isBlank())
                ? new String[0] : responsePath.split("\\.");

        var selectionSetStart = braceIndex;
        for (var i = 0; i < segments.length; i++) {
            var field = findField(trimmed, selectionSetStart, segments[i]);
            if (field == null) {
                return Resolution.UNRESOLVABLE;
            }
            var isLastSegment = i == segments.length - 1;
            if (field.nestedSelectionSetStart() < 0) {
                // A leaf field: a Scalar/Array response if this was the last segment, otherwise there's
                // nothing to descend into for the remaining segments — a genuine path/query mismatch.
                return isLastSegment ? Resolution.SCALAR : Resolution.UNRESOLVABLE;
            }
            selectionSetStart = field.nestedSelectionSetStart();
            if (isLastSegment) {
                return collectFieldNames(trimmed, selectionSetStart).<Resolution>map(Resolution::object)
                        .orElse(Resolution.UNRESOLVABLE);
            }
        }

        // No Response Path segments: the virtual root is the operation's own top-level selection set (same
        // as how navigating the runtime response with an empty/null path returns the 'data' object as-is).
        return collectFieldNames(trimmed, selectionSetStart).<Resolution>map(Resolution::object)
                .orElse(Resolution.UNRESOLVABLE);
    }

    /**
     * Convenience for callers that only care whether the response is an Object (and what its fields are),
     * treating an unparseable query the same as a confirmed Scalar/Array response. See {@link #resolve} for
     * callers (like metadata declaration) that need to tell those two apart.
     */
    static Optional<List<String>> resolveObjectFieldNames(String query, String responsePath) {
        var resolution = resolve(query, responsePath);
        return resolution.kind() == Resolution.Kind.OBJECT ? Optional.of(resolution.fieldNames()) : Optional.empty();
    }

    private record Field(String outputKey, int nestedSelectionSetStart) {
    }

    /**
     * Finds the immediate child field of the selection set starting at {@code s.charAt(selectionSetStart) ==
     * '{'} whose output key (alias, or name if unaliased) equals {@code targetOutputKey}. Returns {@code
     * null} if not found, or if the selection set can't be parsed under this class's minimal grammar.
     */
    private static Field findField(String s, int selectionSetStart, String targetOutputKey) {
        var result = new Field[1];
        var ok = iterateSelectionSet(s, selectionSetStart, field -> {
            if (field.outputKey().equals(targetOutputKey)) {
                result[0] = field;
                return true;
            }
            return false;
        });
        return ok ? result[0] : null;
    }

    private static Optional<List<String>> collectFieldNames(String s, int selectionSetStart) {
        var names = new ArrayList<String>();
        var ok = iterateSelectionSet(s, selectionSetStart, field -> {
            names.add(field.outputKey());
            return false;
        });
        return ok ? Optional.of(names) : Optional.empty();
    }

    private interface FieldVisitor {
        /** @return true to stop iterating (the field being looked for was found). */
        boolean visit(Field field);
    }

    /**
     * Iterates the immediate field children of the selection set starting at {@code s.charAt(start) == '{'},
     * skipping over (not descending into) each field's own arguments and nested selection set.
     *
     * @return true if the selection set was fully parsed (whether or not the visitor stopped early); false
     *         if it uses a construct outside this class's minimal grammar (e.g. a fragment spread or
     *         directive) or is malformed.
     */
    private static boolean iterateSelectionSet(String s, int start, FieldVisitor visitor) {
        var len = s.length();
        var i = skipTrivia(s, start + 1);
        while (i < len) {
            var c = s.charAt(i);
            if (c == '}') {
                return true;
            }
            if (!isNameStart(c)) {
                return false;
            }

            var nameEnd = scanName(s, i);
            var firstName = s.substring(i, nameEnd);
            i = skipTrivia(s, nameEnd);

            var outputKey = firstName;
            if (i < len && s.charAt(i) == ':') {
                i = skipTrivia(s, i + 1);
                if (i >= len || !isNameStart(s.charAt(i))) {
                    return false;
                }
                var fieldNameEnd = scanName(s, i);
                i = skipTrivia(s, fieldNameEnd);
            }

            if (i < len && s.charAt(i) == '(') {
                i = skipBalanced(s, i, '(', ')');
                if (i < 0) {
                    return false;
                }
                i = skipTrivia(s, i);
            }

            var nestedStart = -1;
            if (i < len && s.charAt(i) == '{') {
                nestedStart = i;
                i = skipBalanced(s, i, '{', '}');
                if (i < 0) {
                    return false;
                }
                i = skipTrivia(s, i);
            }

            if (visitor.visit(new Field(outputKey, nestedStart))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static int scanName(String s, int start) {
        var i = start + 1;
        while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
            i++;
        }
        return i;
    }

    /** Skips whitespace, commas, and '#'-to-end-of-line comments. */
    private static int skipTrivia(String s, int i) {
        var len = s.length();
        while (i < len) {
            var c = s.charAt(i);
            if (Character.isWhitespace(c) || c == ',') {
                i++;
            } else if (c == '#') {
                while (i < len && s.charAt(i) != '\n') {
                    i++;
                }
            } else {
                break;
            }
        }
        return i;
    }

    /**
     * Skips from {@code s.charAt(openIndex) == open} to just past its matching {@code close}, treating
     * string and block-string contents as opaque so a brace/paren inside a quoted value isn't counted.
     *
     * @return the index just past the matching closer, or -1 if unterminated.
     */
    private static int skipBalanced(String s, int openIndex, char open, char close) {
        var len = s.length();
        var depth = 0;
        var i = openIndex;
        while (i < len) {
            if (s.startsWith("\"\"\"", i)) {
                var end = s.indexOf("\"\"\"", i + 3);
                if (end < 0) {
                    return -1;
                }
                i = end + 3;
                continue;
            }
            var c = s.charAt(i);
            if (c == '"') {
                var j = i + 1;
                while (j < len && s.charAt(j) != '"' && s.charAt(j) != '\n') {
                    if (s.charAt(j) == '\\') {
                        j++;
                    }
                    j++;
                }
                if (j >= len || s.charAt(j) != '"') {
                    return -1;
                }
                i = j + 1;
                continue;
            }
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
            i++;
        }
        return -1;
    }
}
