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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ORDER BY evaluates each row's keys once and sorts on them. The order it produces must be the one the keys define:
 * several keys, mixed directions, ties broken by the next key, nulls last ascending and first descending - with and
 * without a LIMIT, which takes the top-K path.
 */
class CypherOrderByKeysOnceTest {
  private Database database;

  @BeforeEach
  void setUp() {
    database = new DatabaseFactory("./target/databases/testopencypher-orderby-keys").create();
    database.transaction(() -> {
      database.getSchema().createVertexType("Item");
      for (int i = 0; i < 500; i++) {
        final var v = database.newVertex("Item").set("id", i).set("name", "n" + (i * 37 % 101));
        if (i % 11 != 0)
          v.set("group", (long) (i % 7));
        v.save();
        if (i > 0 && i % 3 == 0)
          database.command("opencypher", "MATCH (a:Item {id: $a}), (b:Item {id: $b}) CREATE (a)-[:L]->(b)",
              Map.of("a", i, "b", i / 3));
      }
    });
  }

  @AfterEach
  void tearDown() {
    if (database != null) {
      database.drop();
      database = null;
    }
  }

  private record Row(int id, String name, Long group, long degree) {
  }

  private List<Row> rows(final String query) {
    final List<Row> out = new ArrayList<>();
    try (final ResultSet rs = database.query("opencypher", query)) {
      while (rs.hasNext()) {
        final var r = rs.next();
        final Number group = r.getProperty("group");
        out.add(new Row(((Number) r.getProperty("id")).intValue(), r.getProperty("name"),
            group == null ? null : group.longValue(), ((Number) r.getProperty("degree")).longValue()));
      }
    }
    return out;
  }

  @ParameterizedTest
  @ValueSource(strings = { "", " LIMIT 40" })
  void keysDefineTheOrder(final String limit) {
    final String base = "MATCH (i:Item) WITH i, COUNT { (i)-[:L]-() } AS degree "
        + "RETURN i.id AS id, i.name AS name, i.group AS group, degree";
    final List<Row> all = rows(base);
    final List<Row> sorted = rows(base + " ORDER BY group DESC, degree DESC, name ASC, id ASC" + limit);

    // Cypher: null sorts last ascending, so first descending.
    final Comparator<Row> expected = Comparator
        .comparing(Row::group, Comparator.nullsLast(Comparator.<Long>naturalOrder())).reversed()
        .thenComparing(Row::degree, Comparator.reverseOrder())
        .thenComparing(Row::name)
        .thenComparingInt(Row::id);
    all.sort(expected);
    final List<Row> want = limit.isEmpty() ? all : all.subList(0, 40);

    assertThat(sorted).containsExactlyElementsOf(want);
  }
}
