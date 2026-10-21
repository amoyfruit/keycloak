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

import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GraphQLSelectionSetParserTest {

    @Test
    public void testExampleQueryResolvesFieldNames() {
        var query = "query($id: String!) { booking(ticketRef: $id) { ticketRef flightNumber route "
                + "departureDateTime passengerName } }";
        var result = GraphQLSelectionSetParser.resolveObjectFieldNames(query, "booking");
        assertTrue(result.isPresent());
        assertEquals(List.of("ticketRef", "flightNumber", "route", "departureDateTime", "passengerName"), result.get());
    }

    @Test
    public void testMultiSegmentResponsePath() {
        var query = "query { typedData { count active } }";
        var result = GraphQLSelectionSetParser.resolveObjectFieldNames(query, "typedData");
        assertTrue(result.isPresent());
        assertEquals(List.of("count", "active"), result.get());
    }

    @Test
    public void testEmptyResponsePathUsesTopLevelSelectionSet() {
        var query = "query { roles }";
        var result = GraphQLSelectionSetParser.resolveObjectFieldNames(query, null);
        assertTrue(result.isPresent());
        assertEquals(List.of("roles"), result.get());
    }

    @Test
    public void testBlankResponsePathUsesTopLevelSelectionSet() {
        var query = "query { roles }";
        var result = GraphQLSelectionSetParser.resolveObjectFieldNames(query, "");
        assertTrue(result.isPresent());
        assertEquals(List.of("roles"), result.get());
    }

    @Test
    public void testScalarFieldHasNoSelectionSet() {
        // "count" has no nested selection set, so it's a scalar/array leaf — Claim Name applies, not spread.
        var query = "query { typedData { count } }";
        var result = GraphQLSelectionSetParser.resolveObjectFieldNames(query, "typedData.count");
        assertFalse(result.isPresent());
    }

    @Test
    public void testAliasIsUsedAsOutputKey() {
        var query = "query { flightNum: flightNumber }";
        var result = GraphQLSelectionSetParser.resolveObjectFieldNames(query, null);
        assertTrue(result.isPresent());
        assertEquals(List.of("flightNum"), result.get());
    }

    @Test
    public void testAliasOnNavigatedField() {
        var query = "query { b: booking(ticketRef: $id) { flightNumber } }";
        var result = GraphQLSelectionSetParser.resolveObjectFieldNames(query, "b");
        assertTrue(result.isPresent());
        assertEquals(List.of("flightNumber"), result.get());
    }

    @Test
    public void testArgumentsAreSkipped() {
        var query = "query($id: String!) { booking(ticketRef: $id, extra: \"ignored\") { flightNumber } }";
        var result = GraphQLSelectionSetParser.resolveObjectFieldNames(query, "booking");
        assertTrue(result.isPresent());
        assertEquals(List.of("flightNumber"), result.get());
    }

    @Test
    public void testBracesInsideStringArgumentDoNotConfuseBalancing() {
        var query = "query { booking(ref: \"a{b}c)\") { flightNumber } }";
        var result = GraphQLSelectionSetParser.resolveObjectFieldNames(query, "booking");
        assertTrue(result.isPresent());
        assertEquals(List.of("flightNumber"), result.get());
    }

    @Test
    public void testCommentsAreSkipped() {
        var query = "query {\n  # a comment\n  booking { flightNumber }\n}";
        var result = GraphQLSelectionSetParser.resolveObjectFieldNames(query, "booking");
        assertTrue(result.isPresent());
        assertEquals(List.of("flightNumber"), result.get());
    }

    @Test
    public void testFragmentSpreadIsUnparseable() {
        var query = "query { booking { ...BookingFields } }";
        var result = GraphQLSelectionSetParser.resolveObjectFieldNames(query, "booking");
        assertFalse(result.isPresent());
    }

    @Test
    public void testUnknownResponsePathSegmentIsUnresolvable() {
        var query = "query { booking { flightNumber } }";
        var result = GraphQLSelectionSetParser.resolveObjectFieldNames(query, "booking.nonExistent");
        assertFalse(result.isPresent());
    }

    @Test
    public void testMalformedQueryIsUnparseable() {
        var result = GraphQLSelectionSetParser.resolveObjectFieldNames("query { booking { ", "booking");
        assertFalse(result.isPresent());
    }

    @Test
    public void testNullQueryIsUnparseable() {
        assertFalse(GraphQLSelectionSetParser.resolveObjectFieldNames(null, "booking").isPresent());
    }

    // resolve() distinguishes SCALAR (confirmed, Claim Name is accurate) from UNRESOLVABLE (unknown, a caller
    // declaring metadata must not guess) — resolveObjectFieldNames() collapses both to empty, which is fine
    // for its callers (the reserved-claim check), but metadata declaration needs the distinction.

    @Test
    public void testResolveDistinguishesScalarFromUnresolvable() {
        var scalarQuery = "query { typedData { count } }";
        assertEquals(GraphQLSelectionSetParser.Resolution.Kind.SCALAR,
                GraphQLSelectionSetParser.resolve(scalarQuery, "typedData.count").kind());

        var fragmentQuery = "query { booking { ...BookingFields } }";
        assertEquals(GraphQLSelectionSetParser.Resolution.Kind.UNRESOLVABLE,
                GraphQLSelectionSetParser.resolve(fragmentQuery, "booking").kind());
    }

    @Test
    public void testResolveObjectKindCarriesFieldNames() {
        var query = "query { booking { flightNumber route } }";
        var resolution = GraphQLSelectionSetParser.resolve(query, "booking");
        assertEquals(GraphQLSelectionSetParser.Resolution.Kind.OBJECT, resolution.kind());
        assertEquals(List.of("flightNumber", "route"), resolution.fieldNames());
    }
}
