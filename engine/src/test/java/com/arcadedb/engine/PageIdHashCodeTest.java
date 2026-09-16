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
package com.arcadedb.engine;

import com.arcadedb.TestHelper;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The page id hash is computed without {@code Objects.hash}, and must still be the value {@code Objects.hash} gives:
 * equal ids have to hash alike, and the page cache's distribution should not move.
 */
class PageIdHashCodeTest extends TestHelper {
  @Test
  void hashIsTheOneObjectsHashGives() {
    final int[] values = { 0, 1, -1, 7, 31, 1024, Integer.MAX_VALUE, Integer.MIN_VALUE };
    for (final int fileId : values)
      for (final int pageNumber : values) {
        final PageId id = new PageId(database, fileId, pageNumber);
        assertThat(id.hashCode()).as("file %d page %d", fileId, pageNumber)
            .isEqualTo(Objects.hash(database, fileId, pageNumber))
            .isEqualTo(new PageId(database, fileId, pageNumber).hashCode());
      }
  }
}
