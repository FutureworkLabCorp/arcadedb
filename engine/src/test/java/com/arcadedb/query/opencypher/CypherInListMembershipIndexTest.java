/*
 * Copyright © 2021-present Arcade Data Ltd (info@arcadedata.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.arcadedb.query.opencypher;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A large list parameter on the right of IN is answered from a hash index once the same list recurs on a second row.
 * The index must never change an answer: for every probe, `x IN $list` has to agree with the definition of
 * membership, `any(e IN $list WHERE e = x)`, including the pairs the index declines (RID strings against ids,
 * doubles against longs, null elements) and on the first row, before the index exists.
 */
class CypherInListMembershipIndexTest {
  private Database database;

  @BeforeEach
  void setUp() {
    database = new DatabaseFactory("./target/databases/testopencypher-in-index").create();
  }

  @AfterEach
  void tearDown() {
    if (database != null) {
      database.drop();
      database = null;
    }
  }

  private static List<Object> strings(final int n) {
    final List<Object> out = new ArrayList<>();
    for (int i = 0; i < n; i++)
      out.add("chunk-" + i);
    return out;
  }

  private static List<Object> longs(final int n) {
    final List<Object> out = new ArrayList<>();
    for (long i = 0; i < n; i++)
      out.add(i * 7);
    return out;
  }

  private static List<Object> with(final List<Object> base, final Object... extra) {
    final List<Object> out = new ArrayList<>(base);
    out.addAll(Arrays.asList(extra));
    return out;
  }

  static Stream<Arguments> lists() {
    final List<Object> probes = Arrays.asList("chunk-3", "chunk-99", "", "#1:0", 21L, 22L, 21, 21.0, 1.5, true, null);
    return Stream.of(
        Arguments.of("strings", strings(40), probes),
        Arguments.of("longs", longs(40), probes),
        Arguments.of("integers", new ArrayList<>(List.of(0, 7, 14, 21, 28, 35, 42, 49, 56, 63, 70, 77, 84, 91, 98, 105)), probes),
        Arguments.of("strings with a null", with(strings(40), (Object) null), probes),
        Arguments.of("longs with a double", with(longs(40), 1.5), probes),
        Arguments.of("strings and longs", with(strings(20), 21L), probes),
        Arguments.of("RID strings", with(strings(20), "#1:0", "#3:5"), probes),
        Arguments.of("short list", strings(5), probes));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("lists")
  void indexAgreesWithEqualityOnEveryRow(final String name, final List<Object> list, final List<Object> probes) {
    // Each probe three times, so the index is absent on the first rows and consulted on the later ones.
    final List<Object> rows = new ArrayList<>();
    for (int round = 0; round < 3; round++)
      rows.addAll(probes);

    final Map<String, Object> params = new HashMap<>();
    params.put("list", list);
    params.put("rows", rows);
    final ResultSet rs = database.query("opencypher",
        "UNWIND $rows AS x RETURN x, x IN $list AS viaIn, any(e IN $list WHERE e = x) AS viaEquality", params);

    int seen = 0;
    while (rs.hasNext()) {
      final Result row = rs.next();
      final Object x = row.getProperty("x");
      assertThat((Object) row.getProperty("viaIn"))
          .as("%s: %s IN list (row %d)", name, x, seen)
          .isEqualTo(row.getProperty("viaEquality"));
      seen++;
    }
    assertThat(seen).isEqualTo(rows.size());
  }

  @Test
  void aListChangedBetweenExecutionsIsNotAnsweredFromItsOldContents() {
    // The statement is cached, so both executions run the same AST node. The caller reuses its list object and
    // changes one element without changing the size: nothing but the contents tells the two executions apart.
    final List<Object> list = strings(40);
    final Map<String, Object> params = new HashMap<>();
    params.put("list", list);
    params.put("rows", List.of("chunk-3", "chunk-3", "changed", "changed"));
    final String query = "UNWIND $rows AS x RETURN x IN $list AS found";

    assertThat(found(query, params)).containsExactly(true, true, false, false);
    list.set(3, "changed");
    assertThat(found(query, params)).containsExactly(false, false, true, true);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {
      // a list of vertices, collected once and carried to every row
      "MATCH (k:Item) WHERE k.id % 3 = 0 WITH collect(k) AS kept MATCH (a:Item)",
      // edges, and a vertex probed against them: a different kind of element, never equal
      "MATCH ()-[k:L]->() WITH collect(k) AS kept MATCH (a:Item)",
      "MATCH ()-[k:L]->() WITH collect(k) AS kept MATCH ()-[a:L]->()",
      // vertices and a string: not one kind, so the walk answers
      "MATCH (k:Item) WHERE k.id % 3 = 0 WITH collect(k) + ['#1:0'] AS kept MATCH (a:Item)",
      // vertices and a null: the walk's 3VL answer
      "MATCH (k:Item) WHERE k.id % 3 = 0 WITH collect(k) + [null] AS kept MATCH (a:Item)" })
  void graphElementsAgreeWithEquality(final String head) {
    database.transaction(() -> {
      database.getSchema().createVertexType("Item");
      database.getSchema().createEdgeType("L");
      for (int i = 0; i < 90; i++)
        database.newVertex("Item").set("id", i).save();
      database.command("opencypher", "MATCH (a:Item), (b:Item) WHERE b.id = (a.id * 7) % 90 AND a.id % 2 = 0 CREATE (a)-[:L]->(b)");
    });
    int seen = 0;
    try (final ResultSet rs = database.query("opencypher",
        head + " RETURN a IN kept AS viaIn, any(e IN kept WHERE e = a) AS viaEquality")) {
      while (rs.hasNext()) {
        final Result row = rs.next();
        assertThat((Object) row.getProperty("viaIn")).as("row %d", seen).isEqualTo(row.getProperty("viaEquality"));
        seen++;
      }
    }
    assertThat(seen).isGreaterThan(40);
  }

  @Test
  void lightRagWholeGraphShapeKeepsTheEdgesAmongTheKeptNodes() {
    // LightRAG's label='*' query: the top nodes by degree, then every relationship with both ends among them.
    database.transaction(() -> {
      database.getSchema().createVertexType("Item");
      database.getSchema().createEdgeType("L");
      for (int i = 0; i < 90; i++)
        database.newVertex("Item").set("id", i).save();
      database.command("opencypher", "MATCH (a:Item), (b:Item) WHERE b.id = (a.id * 7) % 90 AND a.id <> b.id CREATE (a)-[:L]->(b)");
      database.command("opencypher", "MATCH (a:Item), (b:Item) WHERE b.id = (a.id + 1) % 90 AND a.id % 4 = 0 CREATE (a)-[:L]->(b)");
    });
    final String kept = "MATCH (n:Item) OPTIONAL MATCH (n)-[r]-() WITH n, count(r) AS degree "
        + "ORDER BY degree DESC, n.id ASC LIMIT 30 WITH collect(n) AS kept_nodes ";
    final long viaIn = database.query("opencypher", kept
        + "OPTIONAL MATCH (a)-[r]-(b) WHERE a IN kept_nodes AND b IN kept_nodes RETURN count(DISTINCT r) AS c")
        .next().<Number>getProperty("c").longValue();
    final long viaEquality = database.query("opencypher", kept
        + "OPTIONAL MATCH (a)-[r]-(b) WHERE any(x IN kept_nodes WHERE x = a) AND any(y IN kept_nodes WHERE y = b) "
        + "RETURN count(DISTINCT r) AS c").next().<Number>getProperty("c").longValue();
    assertThat(viaIn).isPositive().isEqualTo(viaEquality);
  }

  private List<Object> found(final String query, final Map<String, Object> params) {
    final List<Object> out = new ArrayList<>();
    try (final ResultSet rs = database.query("opencypher", query, params)) {
      while (rs.hasNext())
        out.add(rs.next().getProperty("found"));
    }
    return out;
  }
}
