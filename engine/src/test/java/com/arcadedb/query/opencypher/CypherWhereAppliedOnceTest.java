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
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A MATCH WHERE the physical plan has evaluated is not evaluated a second time by a filter step, and leaving the
 * step out never changes which rows come back: not for a predicate whose two answers differ (a non-boolean value
 * used as a condition), not for one that reads a variable bound before or between the MATCH clauses, and not for
 * any of the ordinary shapes.
 */
class CypherWhereAppliedOnceTest {
  private Database database;

  @BeforeEach
  void setUp() {
    database = new DatabaseFactory("./target/databases/testopencypher-where-once").create();
    database.transaction(() -> {
      database.getSchema().createVertexType("P");
      database.getSchema().createEdgeType("K");
      for (int i = 0; i < 40; i++) {
        final var v = database.newVertex("P").set("id", i).set("flag", i % 3 == 0);
        if (i % 4 != 0)
          v.set("name", "n" + i);
        if (i % 5 == 0)
          v.set("text", "t" + i);
        v.save();
      }
      database.command("opencypher", "MATCH (a:P), (b:P) WHERE b.id = (a.id + 1) % 40 CREATE (a)-[:K]->(b)");
    });
  }

  @AfterEach
  void tearDown() {
    if (database != null) {
      database.drop();
      database = null;
    }
  }

  private long count(final String query, final Map<String, Object> params) {
    try (final ResultSet rs = database.query("opencypher", query, params)) {
      return ((Number) rs.next().getProperty("c")).longValue();
    }
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', value = {
      // pushed into the scan
      "MATCH (n:P) WHERE n.name IS NOT NULL RETURN count(*) AS c | 30",
      "MATCH (n:P) WHERE n.id > 10 AND n.name IS NOT NULL RETURN count(*) AS c | 22",
      "MATCH (n:P) WHERE n.id < 5 OR n.name STARTS WITH 'n3' RETURN count(*) AS c | 13",
      "MATCH (n:P) WHERE NOT n.flag RETURN count(*) AS c | 26",
      "MATCH (n:P) WHERE n.id IN [1, 2, 3, 99] RETURN count(*) AS c | 3",
      // reads both ends, so it stays above the expansion
      "MATCH (a:P)-[:K]->(b:P) WHERE a.id + 1 = b.id AND b.name IS NULL RETURN count(*) AS c | 9",
      // a non-boolean value as the condition: the filter operator's and the step's answers differ for it
      "MATCH (a:P)-[:K]->(b:P) WHERE b.text RETURN count(*) AS c | 0",
      "MATCH (n:P) WHERE n.text RETURN count(*) AS c | 0",
      "MATCH (a:P)-[:K]->(b:P) WHERE b.text AND a.id >= 0 RETURN count(*) AS c | 8",
      // a variable bound before the MATCH, which the physical plan evaluates without
      "UNWIND [1, 2] AS x MATCH (n:P) WHERE n.id = x RETURN count(*) AS c | 2",
      "UNWIND [1, 2] AS x MATCH (n:P) WHERE x IS NULL RETURN count(*) AS c | 0",
      // and one bound between two MATCH clauses
      "MATCH (a:P) WHERE a.id < 3 UNWIND [a.id] AS x MATCH (a)-[:K]->(b:P) WHERE x IS NULL RETURN count(*) AS c | 0" })
  void rowsAreThoseTheWhereAdmits(final String query, final long expected) {
    assertThat(count(query, Map.of())).isEqualTo(expected);
  }

  @Test
  void aPushedPredicateIsNotFilteredAgain() {
    try (final ResultSet rs = database.query("opencypher",
        "EXPLAIN MATCH (n:P) WHERE n.name IS NOT NULL RETURN count(*) AS c")) {
      final String plan = rs.getExecutionPlan().orElseThrow().prettyPrint(0, 2);
      assertThat(plan).contains("[filter: n.name IS NOT NULL]").doesNotContain("FILTER WHERE");
    }
  }

  @Test
  void aCoercedConditionIsStillFilteredByTheStep() {
    try (final ResultSet rs = database.query("opencypher",
        "EXPLAIN MATCH (a:P)-[:K]->(b:P) WHERE b.text RETURN count(*) AS c")) {
      assertThat(rs.getExecutionPlan().orElseThrow().prettyPrint(0, 2)).contains("FILTER WHERE");
    }
  }
}
