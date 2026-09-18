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

import com.arcadedb.database.BasicDatabase;

import java.util.Objects;

/**
 * Immutable.
 */
public class PageId implements Comparable<PageId> {
  private final BasicDatabase database;
  private final int           fileId;
  private final int           pageNumber;
  private       int           cachedHashCode;

  public PageId(final BasicDatabase database, final int fileId, final int pageNumber) {
    if (database == null)
      throw new IllegalArgumentException("database is null");
    this.database = database;
    this.fileId = fileId;
    this.pageNumber = pageNumber;
  }

  public BasicDatabase getDatabase() {
    return database;
  }

  public int getFileId() {
    return fileId;
  }

  public int getPageNumber() {
    return pageNumber;
  }

  @Override
  public boolean equals(final Object value) {
    if (this == value)
      return true;
    if (!(value instanceof PageId pageId))
      return false;
    return fileId == pageId.fileId && pageNumber == pageId.pageNumber && Objects.equals(database, pageId.database);
  }

  /**
   * The value {@code Objects.hash(database, fileId, pageNumber)} gives, computed without its varargs array and the two
   * boxed ints. A page id is created for every page read and looked up in the page cache at once, so the cache kept
   * here rarely helps: this ran on almost every read, and on a scan of 14k multi-page records it was a quarter of
   * the samples.
   */
  @Override
  public int hashCode() {
    if (cachedHashCode == 0) {
      int hash = 31 + Objects.hashCode(database);
      hash = 31 * hash + fileId;
      hash = 31 * hash + pageNumber;
      cachedHashCode = hash;
    }
    return cachedHashCode;
  }

  @Override
  public String toString() {
    return "PageId(" + database.getName() + "/" + fileId + "/" + pageNumber + ")";
  }

  @Override
  public int compareTo(final PageId o) {
    if (o == this)
      return 0;

    final int cmp = database.getName().compareTo(o.database.getName());
    if (cmp != 0)
      return cmp;

    if (fileId > o.fileId)
      return 1;
    else if (fileId < o.fileId)
      return -1;

    if (pageNumber > o.pageNumber)
      return 1;
    else if (pageNumber < o.pageNumber)
      return -1;
    return 0;
  }
}
