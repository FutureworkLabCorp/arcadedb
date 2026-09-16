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

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.TestHelper;
import com.arcadedb.database.Document;
import com.arcadedb.query.opencypher.temporal.CypherDateTime;
import com.arcadedb.query.opencypher.temporal.CypherDuration;
import com.arcadedb.query.opencypher.temporal.CypherLocalTime;
import com.arcadedb.query.opencypher.temporal.CypherTemporalValue;
import com.arcadedb.query.opencypher.temporal.CypherTime;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.Type;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A duration, time, local time or zoned datetime written by Cypher used to be stored as its ISO string, and every
 * property read turned any string of that shape back into a temporal - so a plain string such as {@code '12:30'} or
 * {@code '2024-01-01T10:00Z'} came back as a TIME or a DATETIME, and compared unequal to the very string that was
 * stored. The temporal values now carry their own binary type, and the string reading is left only for data an older
 * version wrote, behind {@link GlobalConfiguration#OPENCYPHER_READ_TEMPORAL_STRINGS}.
 */
class CypherTemporalStorageRoundTripTest extends TestHelper {
  private static final String[] TEMPORAL_LOOKING_STRINGS = { "12:30", "10:35:00-08:00", "2024-01-01T10:00Z",
      "1984-10-11T12:31:14", "P1D", "PT5M", "2024-01-01", "Product", "12:30 meeting" };

  @Override
  protected void beginTest() {
    database.getSchema().createVertexType("T");
  }

  @AfterEach
  void restoreConfiguration() {
    GlobalConfiguration.OPENCYPHER_READ_TEMPORAL_STRINGS.reset();
  }

  private List<Result> rows(final String query, final Map<String, Object> params) {
    final List<Result> out = new ArrayList<>();
    try (final ResultSet rs = database.query("opencypher", query, params)) {
      while (rs.hasNext())
        out.add(rs.next());
    }
    return out;
  }

  @Test
  void aStringThatLooksTemporalIsReadBackAsTheString() {
    GlobalConfiguration.OPENCYPHER_READ_TEMPORAL_STRINGS.setValue(false);
    database.transaction(() -> {
      for (final String value : TEMPORAL_LOOKING_STRINGS)
        database.command("opencypher", "CREATE (:T {s: $v, via: 'create'})", Map.of("v", value));
      for (final String value : TEMPORAL_LOOKING_STRINGS)
        database.command("opencypher", "CREATE (n:T {via: 'set'}) SET n.s = $v", Map.of("v", value));
      for (final String value : TEMPORAL_LOOKING_STRINGS)
        database.command("opencypher", "MERGE (n:T {via: 'merge', s: $v})", Map.of("v", value));
    });

    for (final String value : TEMPORAL_LOOKING_STRINGS) {
      final List<Result> found = rows(
          "MATCH (n:T) WHERE n.s = $v RETURN n.s AS s, n.s STARTS WITH $v AS prefix, [n.s][0] AS inList ORDER BY n.s",
          Map.of("v", value));
      assertThat(found).as("rows equal to %s", value).hasSize(3);
      for (final Result row : found) {
        assertThat((Object) row.getProperty("s")).as("n.s for %s", value).isEqualTo(value);
        assertThat((Object) row.getProperty("prefix")).isEqualTo(true);
        assertThat((Object) row.getProperty("inList")).isEqualTo(value);
      }
    }

    final List<Result> ordered = rows("MATCH (n:T {via: 'create'}) RETURN n.s AS s ORDER BY n.s", Map.of());
    final List<Object> expected = new ArrayList<>(List.of(TEMPORAL_LOOKING_STRINGS));
    expected.sort((a, b) -> ((String) a).compareTo((String) b));
    assertThat(ordered.stream().map(r -> (Object) r.getProperty("s")).toList()).isEqualTo(expected);
  }

  @Test
  void aStringAnOlderVersionWroteForATemporalIsStillReadAsOneByDefault() {
    // What a release before this one stored for `SET n.d = duration('P1D')`: the plain string.
    database.transaction(() -> database.command("opencypher",
        "CREATE (:T {d: 'P1DT2H', t: '12:30:00+01:00', lt: '12:30', dt: '2015-07-21T21:40:32.142+01:00'})"));

    final Result row = rows("MATCH (n:T) RETURN n.d AS d, n.t AS t, n.lt AS lt, n.dt AS dt", Map.of()).getFirst();
    assertThat((Object) row.getProperty("d")).isInstanceOf(CypherDuration.class);
    assertThat((Object) row.getProperty("t")).isInstanceOf(CypherTime.class);
    assertThat((Object) row.getProperty("lt")).isInstanceOf(CypherLocalTime.class);
    assertThat((Object) row.getProperty("dt")).isInstanceOf(CypherDateTime.class);
    assertThat(((CypherDuration) row.getProperty("d")).getDays()).isEqualTo(1L);
  }

  @Test
  void mergeFindsANodeAnOlderVersionStoredTheTemporalOfAsAString() {
    // The strings an older version wrote for duration('P1DT2H') and time('12:30:00+01:00'): their toString() forms.
    database.transaction(() -> database.command("opencypher", "CREATE (:T {k: 'a', d: 'P1DT2H', t: '12:30+01:00'})"));
    database.transaction(() -> database.command("opencypher",
        "MERGE (n:T {k: 'a', d: duration('P1DT2H'), t: time('12:30:00+01:00')}) ON CREATE SET n.created = true"));
    assertThat(rows("MATCH (n:T) RETURN n.created AS created", Map.of())).hasSize(1)
        .allSatisfy(row -> assertThat((Object) row.getProperty("created")).isNull());

    // and a node written by this version is found by the same MERGE whatever the setting
    GlobalConfiguration.OPENCYPHER_READ_TEMPORAL_STRINGS.setValue(false);
    database.transaction(() -> database.command("opencypher", "MATCH (n:T) DELETE n"));
    for (int i = 0; i < 2; i++)
      database.transaction(() -> database.command("opencypher",
          "MERGE (n:T {k: 'b', d: duration('P1DT2H'), t: time('12:30:00+01:00')})"));
    assertThat(rows("MATCH (n:T) RETURN n", Map.of())).hasSize(1);
  }

  @ParameterizedTest
  @ValueSource(strings = { "duration('P1Y2M3DT4H5M6.007S')", "duration({days: -3, nanoseconds: 7})", "localtime('12:31:14.645')",
      "localtime('00:00')", "time('12:31:14.645876123+01:00')", "time('23:59Z')",
      "datetime('2015-07-21T21:40:32.142+01:00[Europe/Stockholm]')", "datetime('2015-07-21T21:40:32Z')",
      "datetime('1900-01-01T00:00:00.000000001-05:00')", "date('2020-01-02')",
      "localdatetime('2015-07-21T21:40:32.142')" })
  void aTemporalValueIsReadBackAsTheSameValue(final String expression) {
    // With the string reading off, only the stored type can bring the value back.
    GlobalConfiguration.OPENCYPHER_READ_TEMPORAL_STRINGS.setValue(false);
    database.transaction(() -> {
      database.command("opencypher", "CREATE (:T {via: 'create', v: " + expression + ", l: [" + expression + "]})");
      database.command("opencypher", "CREATE (n:T {via: 'set'}) SET n.v = " + expression + ", n.l = [" + expression + "]");
      database.command("opencypher", "MERGE (n:T {via: 'merge'}) ON CREATE SET n.v = " + expression + ", n.l = [" + expression + "]");
    });
    assertReadBack(expression);

    reopenDatabase();
    assertReadBack(expression);
  }

  private void assertReadBack(final String expression) {
    final List<Result> found = rows("MATCH (n:T) RETURN n.via AS via, n.v AS v, n.v = " + expression + " AS same, "
        + "n.l[0] = " + expression + " AS sameInList, n.v < " + expression + " AS less ORDER BY via", Map.of());
    assertThat(found).hasSize(3);
    for (final Result row : found) {
      assertThat((Object) row.getProperty("v")).as("%s via %s", expression, row.getProperty("via"))
          .isInstanceOf(CypherTemporalValue.class);
      assertThat((Object) row.getProperty("same")).as("%s via %s", expression, row.getProperty("via")).isEqualTo(true);
      assertThat((Object) row.getProperty("sameInList")).isEqualTo(true);
      if (!expression.startsWith("duration"))
        assertThat((Object) row.getProperty("less")).isEqualTo(false);
    }
  }

  @Test
  void aTemporalReadsItsComponentsAfterStorage() {
    GlobalConfiguration.OPENCYPHER_READ_TEMPORAL_STRINGS.setValue(false);
    database.transaction(() -> database.command("opencypher",
        "CREATE (:T {d: duration('P1Y2M3DT4H'), t: time('12:31:14+01:00'), lt: localtime('07:08'), "
            + "dt: datetime('2015-07-21T21:40:32.142+01:00[Europe/Stockholm]')})"));
    reopenDatabase();

    final Result row = rows("MATCH (n:T) WITH n.d AS d, n.t AS t, n.lt AS lt, n.dt AS dt RETURN d.months AS months, "
        + "d.days AS days, t.hour AS hour, t.offset AS offset, lt.minute AS minute, dt.timezone AS zone, "
        + "dt.millisecond AS ms", Map.of()).getFirst();
    assertThat(((Number) row.getProperty("months")).longValue()).isEqualTo(14L);
    assertThat(((Number) row.getProperty("days")).longValue()).isEqualTo(3L);
    assertThat(((Number) row.getProperty("hour")).longValue()).isEqualTo(12L);
    assertThat((Object) row.getProperty("offset")).isEqualTo("+01:00");
    assertThat(((Number) row.getProperty("minute")).longValue()).isEqualTo(8L);
    assertThat((Object) row.getProperty("zone")).isEqualTo("Europe/Stockholm");
    assertThat(((Number) row.getProperty("ms")).longValue()).isEqualTo(142L);
  }

  @Test
  void aDeclaredPropertyStillConvertsTheTemporalToItsType() {
    database.getSchema().getType("T").createProperty("s", Type.STRING);
    database.getSchema().getType("T").createProperty("when", Type.DATETIME);
    database.transaction(() -> database.command("opencypher",
        "CREATE (:T {s: duration('P1D'), when: datetime('2015-07-21T21:40:32Z')})"));

    final Document stored = database.query("sql", "SELECT FROM T").next().getElement().orElseThrow();
    assertThat(stored.get("s")).isEqualTo("P1D");
    assertThat(stored.get("when")).isNotInstanceOf(CypherTemporalValue.class).isNotNull();
  }

  @Test
  void aStoredTemporalIsItsIsoStringOutsideCypher() {
    database.transaction(() -> database.command("opencypher",
        "CREATE (:T {d: duration('P1DT2H'), t: time('12:31:14+01:00'), lt: localtime('07:08'), "
            + "dt: datetime('2015-07-21T21:40:32.142+01:00[Europe/Stockholm]')})"));
    reopenDatabase();

    final Object dateTime = rows("MATCH (n:T) RETURN n.dt AS dt", Map.of()).getFirst().getProperty("dt");
    final Document stored = database.query("sql", "SELECT FROM T").next().getElement().orElseThrow();
    assertThat(stored.toJSON().getString("d")).isEqualTo("P1DT2H");
    assertThat(stored.toJSON().getString("t")).isEqualTo("12:31:14+01:00");
    assertThat(stored.toJSON().getString("lt")).isEqualTo("07:08");
    assertThat(stored.toJSON().getString("dt")).isEqualTo(dateTime.toString()).contains("[Europe/Stockholm]");
  }
}
