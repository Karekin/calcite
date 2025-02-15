/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.rel.metadata;

import org.apache.calcite.plan.RelOptTable;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * `RelColumnOrigin` 表示关系表达式输出列的一个来源信息。
 *
 * <p>在 SQL 查询优化过程中，了解输出列的来源（如是否直接来自于基础表，还是由计算得出）
 * 对于数据血缘分析、权限控制和查询优化都至关重要。</p>
 *
 * <p>例如，在 SQL 语句 `SELECT a+b AS c, d AS e FROM t` 中：</p>
 * <ul>
 *     <li>输出列 `c` 的来源是 `a` 和 `b`，它们都是派生列（`isDerived=true`）。</li>
 *     <li>输出列 `e` 的来源是 `d`，它是直接从 `t` 表中获取的（`isDerived=false`）。</li>
 * </ul>
 */
public class RelColumnOrigin {
  //~ Instance fields --------------------------------------------------------

  /** 该列来源的原始表。 */
  private final RelOptTable originTable;

  /** 该列在原始表中的索引（0-based）。 */
  private final int iOriginColumn;

  /** 该列是否是派生列（由计算得出，而非直接从原始表获取）。 */
  private final boolean isDerived;

  //~ Constructors -----------------------------------------------------------

  /**
   * 构造 `RelColumnOrigin` 实例。
   *
   * @param originTable 该列来源的原始表
   * @param iOriginColumn 该列在原始表中的索引（0-based）
   * @param isDerived 该列是否是派生列（由计算得出，则为 `true`；否则为 `false`）
   */
  public RelColumnOrigin(
      RelOptTable originTable,
      int iOriginColumn,
      boolean isDerived) {
    this.originTable = originTable;
    this.iOriginColumn = iOriginColumn;
    this.isDerived = isDerived;
  }

  //~ Methods ----------------------------------------------------------------

  /**
   * 获取该列来源的原始表。
   *
   * @return 原始表 `RelOptTable` 对象
   */
  public RelOptTable getOriginTable() {
    return originTable;
  }

  /**
   * 获取该列在原始表中的索引（0-based）。
   *
   * <p>是否经过 UDT（用户定义类型）展开处理，取决于生成该 `RelColumnOrigin` 的关系表达式是否已进行 UDT 展开。</p>
   *
   * @return 该列在原始表中的索引
   */
  public int getOriginColumnOrdinal() {
    return iOriginColumn;
  }

  /**
   * 判断该列是否是派生列。
   *
   * <p>派生列是指由计算或操作（如 `a + b`）得到的列，而非直接从原始表获取的列。</p>
   *
   * <p>例如，对于查询 `SELECT a + b AS c, d AS e FROM t`：</p>
   * <ul>
   *     <li>列 `c` 由 `a + b` 计算得出，因此 `isDerived()` 返回 `true`。</li>
   *     <li>列 `e` 直接来自于 `d`，因此 `isDerived()` 返回 `false`。</li>
   * </ul>
   *
   * @return 若该列是派生列，则返回 `true`；否则返回 `false`
   */
  public boolean isDerived() {
    return isDerived;
  }

  /**
   * 判断两个 `RelColumnOrigin` 对象是否相等。
   *
   * <p>如果两个对象的原始表名、列索引和派生属性均相同，则认为它们相等。</p>
   *
   * @param obj 要比较的对象
   * @return 若相等，则返回 `true`；否则返回 `false`
   */
  @Override public boolean equals(@Nullable Object obj) {
    if (!(obj instanceof RelColumnOrigin)) {
      return false;
    }
    RelColumnOrigin other = (RelColumnOrigin) obj;
    return originTable.getQualifiedName().equals(
        other.originTable.getQualifiedName())
        && (iOriginColumn == other.iOriginColumn)
        && (isDerived == other.isDerived);
  }

  /**
   * 计算 `RelColumnOrigin` 对象的哈希值。
   *
   * <p>哈希值由原始表的名称、列索引和派生属性共同决定。</p>
   *
   * @return 计算得到的哈希值
   */
  @Override public int hashCode() {
    return originTable.getQualifiedName().hashCode()
        + iOriginColumn + (isDerived ? 313 : 0);
  }
}

