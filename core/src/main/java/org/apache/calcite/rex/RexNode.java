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
package org.apache.calcite.rex;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlKind;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collection;

import static java.util.Objects.requireNonNull;

/**
 * 行表达式（Row expression）。
 *
 * <p>每个 `RexNode`（Row EXpression Node）都代表 SQL 查询中的一个表达式节点，
 * 并且具有确定的类型（`RelDataType`）。不同于 {@link org.apache.calcite.sql.SqlNode}，
 * 后者在 SQL 解析阶段就已生成，因此可能尚未进行类型推导。</p>
 *
 * <p>一些常见的 `RexNode` 子类包括：
 * <ul>
 *   <li>{@link RexLiteral} - 常量值，例如 `TRUE`、`123`、`'abc'`</li>
 *   <li>{@link RexVariable} - 变量，例如字段引用</li>
 *   <li>{@link RexCall} - 操作符调用，例如 `a + b`、`MAX(x)`</li>
 * </ul>
 * </p>
 *
 * <p>通常，`RexNode` 及其子类实例由 {@link RexBuilder} 工厂类创建。</p>
 *
 * <p>所有 `RexNode` 子类都是不可变的（Immutable）。</p>
 */
public abstract class RexNode {

  //~ 实例字段 --------------------------------------------------------

  /**
   * 该 `RexNode` 的唯一字符串表示（digest）。
   * 该字段在子类构造函数中设置后，不会被修改。
   */
  protected @MonotonicNonNull String digest;

  //~ 方法 --------------------------------------------------------

  /**
   * 获取该表达式的类型（`RelDataType`）。
   *
   * @return 该 `RexNode` 的类型
   */
  public abstract RelDataType getType();

  /**
   * 判断该表达式是否恒等于 `TRUE`。
   *
   * <p>例如，若该表达式是 `TRUE`，则返回 `true`；否则返回 `false`。</p>
   *
   * @return 若该表达式总是返回 `true`，则返回 `true`，否则返回 `false`
   */
  public boolean isAlwaysTrue() {
    return false;
  }

  /**
   * 判断该表达式是否恒等于 `FALSE`。
   *
   * <p>例如，若该表达式是 `FALSE`，则返回 `true`；否则返回 `false`。</p>
   *
   * @return 若该表达式总是返回 `false`，则返回 `true`，否则返回 `false`
   */
  public boolean isAlwaysFalse() {
    return false;
  }

  /**
   * 判断当前节点是否属于指定的 `SqlKind` 类型。
   *
   * @param kind SQL 语法类型
   * @return 若当前节点的类型等于 `kind`，则返回 `true`，否则返回 `false`
   */
  public boolean isA(SqlKind kind) {
    return getKind() == kind;
  }

  /**
   * 判断当前节点是否属于指定的 `SqlKind` 类型集合中的某一种。
   *
   * @param kinds SQL 语法类型集合
   * @return 若当前节点的类型属于 `kinds`，则返回 `true`，否则返回 `false`
   */
  public boolean isA(Collection<SqlKind> kinds) {
    return getKind().belongsTo(kinds);
  }

  /**
   * 返回该表达式的类型（`SqlKind`）。
   *
   * @return SQL 语法类型 {@link SqlKind}，不会返回 `null`
   */
  public SqlKind getKind() {
    return SqlKind.OTHER;
  }

  /**
   * 返回该 `RexNode` 的字符串表示。
   *
   * @return 该 `RexNode` 的 `digest` 值（不会为 `null`）
   */
  @Override public String toString() {
    return requireNonNull(digest, "digest");
  }

  /**
   * 计算当前表达式的节点数量（复杂度）。
   *
   * <p>叶子节点（例如 {@link RexInputRef} 或 {@link RexLiteral}）的计数为 1。
   * 对于 `RexCall`（操作符调用），其计数为 `1 + 所有操作数的计数总和`。</p>
   *
   * <p>此计数用于优化器，以防止生成过于复杂的深度嵌套表达式。</p>
   *
   * @return 该表达式的节点数量
   */
  public int nodeCount() {
    return 1;
  }

  /**
   * 接受 `RexVisitor` 访问者，并调用相应的 `visitXxx` 方法进行处理。
   *
   * <p>也可参见 {@link RexUtil#apply(RexVisitor, java.util.List, RexNode)}
   * 方法，该方法可同时对多个表达式进行访问。</p>
   *
   * @param visitor 访问者
   * @param <R> 访问者返回值类型
   * @return 访问结果
   */
  public abstract <R> R accept(RexVisitor<R> visitor);

  /**
   * 接受 `RexBiVisitor` 访问者（带额外参数），并调用相应的 `visitXxx` 方法。
   *
   * @param visitor 访问者
   * @param arg 额外参数
   * @param <R> 访问者返回值类型
   * @param <P> 额外参数类型
   * @return 访问结果
   */
  public abstract <R, P> R accept(RexBiVisitor<R, P> visitor, P arg);

  /**
   * 判断当前 `RexNode` 是否与另一个对象相等。
   *
   * <p>子类必须基于 `RexNode` 的内容实现该方法。</p>
   *
   * @param obj 另一个对象
   * @return 若 `obj` 与当前 `RexNode` 结构相同，则返回 `true`，否则返回 `false`
   */
  @Override public abstract boolean equals(@Nullable Object obj);

  /**
   * 计算当前 `RexNode` 的哈希值。
   *
   * <p>子类必须确保 `hashCode` 与 `equals` 方法一致。</p>
   *
   * @return 该 `RexNode` 的哈希值
   */
  @Override public abstract int hashCode();
}

