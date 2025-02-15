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
package org.apache.calcite.sql;

import org.apache.calcite.sql.dialect.AnsiSqlDialect;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.pretty.SqlPrettyWriter;
import org.apache.calcite.sql.util.SqlString;
import org.apache.calcite.sql.util.SqlVisitor;
import org.apache.calcite.sql.validate.SqlMoniker;
import org.apache.calcite.sql.validate.SqlMonotonicity;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorScope;
import org.apache.calcite.util.Litmus;
import org.apache.calcite.util.Util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collector;

import static java.util.Objects.requireNonNull;

/**
 * <code>SqlNode</code> 代表 SQL 解析树（SQL parse tree）的一个节点。
 *
 * <p>它可以是以下类型的 SQL 结构：
 * <ul>
 *     <li>{@link SqlCall} - SQL 调用，例如函数调用、操作符表达式等</li>
 *     <li>{@link SqlLiteral} - SQL 字面量，例如字符串、数字、布尔值等</li>
 *     <li>{@link SqlIdentifier} - SQL 标识符，例如表名、列名等</li>
 * </ul>
 */
public abstract class SqlNode implements Cloneable {
  //~ 静态字段和初始化器 ---------------------------------------------

  /**
   * 代表空的 `SqlNode` 数组，用于避免创建不必要的对象。
   */
  public static final @Nullable SqlNode[] EMPTY_ARRAY = new SqlNode[0];

  //~ 实例字段 --------------------------------------------------------

  /**
   * 该节点在 SQL 解析树中的位置，不能为空。
   */
  protected final SqlParserPos pos;

  //~ 构造方法 -----------------------------------------------------------

  /**
   * 创建一个 `SqlNode` 实例。
   *
   * @param pos 解析器位置（SQL 语句中的位置信息），不能为空。
   */
  SqlNode(SqlParserPos pos) {
    this.pos = requireNonNull(pos, "pos");
  }

  //~ 方法 ----------------------------------------------------------------

  /** @deprecated 请使用 {@link #clone(SqlNode)}，此方法继承自 Java 早期版本，可能会带来不必要的负担。 */
  @Deprecated
  @SuppressWarnings({"MethodDoesntCallSuperMethod", "AmbiguousMethodReference"})
  @Override public Object clone() {
    return clone(getParserPosition());
  }

  /**
   * 克隆一个 `SqlNode` 节点。
   *
   * @param e 需要克隆的 `SqlNode`
   * @return 克隆后的 `SqlNode` 副本
   */
  @SuppressWarnings("AmbiguousMethodReference")
  public static <E extends SqlNode> E clone(E e) {
    //noinspection unchecked
    return (E) e.clone(e.pos);
  }

  /**
   * 克隆当前 `SqlNode`，并使用指定的 `SqlParserPos` 作为新的位置信息。
   *
   * @param pos 解析器位置信息
   * @return 克隆后的 `SqlNode`
   */
  public abstract SqlNode clone(SqlParserPos pos);

  /**
   * 返回该节点的类型，若无特殊类型，则返回 {@link org.apache.calcite.sql.SqlKind#OTHER}。
   *
   * @return SQL 语法类型 {@link SqlKind}，不会返回 `null`
   */
  public SqlKind getKind() {
    return SqlKind.OTHER;
  }

  /**
   * 判断当前节点是否属于某个 SQL 语法类别。
   *
   * <p>例如，`node.isA(SqlKind.QUERY)` 如果 `node` 是 `SELECT`、`INSERT`、`UPDATE` 等查询类型，则返回 `true`。</p>
   *
   * <p>此方法等价于 `node.getKind().belongsTo(category)`。</p>
   *
   * @param category SQL 语法类别集合
   * @return 如果该节点属于指定类别，则返回 `true`，否则返回 `false`
   */
  public final boolean isA(Set<SqlKind> category) {
    return getKind().belongsTo(category);
  }

  /**
   * 生成当前 `SqlNode` 的 SQL 表达式字符串。
   *
   * @param transform 用于配置 SQL 输出格式的转换器
   * @return 该 `SqlNode` 对应的 SQL 语句字符串
   */
  public SqlString toSqlString(UnaryOperator<SqlWriterConfig> transform) {
    final SqlWriterConfig config = transform.apply(SqlPrettyWriter.config());
    SqlPrettyWriter writer = new SqlPrettyWriter(config);
    unparse(writer, 0, 0);
    return writer.toSqlString();
  }

  /**
   * 生成当前 `SqlNode` 的 SQL 表达式字符串，并支持指定 SQL 方言（Dialect）。
   *
   * @param dialect SQL 方言，若为 `null`，则使用 ANSI SQL
   * @param forceParens 是否强制使用括号
   * @return 该 `SqlNode` 对应的 SQL 语句字符串
   */
  public SqlString toSqlString(@Nullable SqlDialect dialect, boolean forceParens) {
    return toSqlString(c ->
        c.withDialect(Util.first(dialect, AnsiSqlDialect.DEFAULT))
            .withAlwaysUseParentheses(forceParens)
            .withSelectListItemsOnSeparateLines(false)
            .withUpdateSetListNewline(false)
            .withIndentation(0));
  }

  /**
   * 以特定格式将当前 `SqlNode` 转换为 SQL 语句字符串，并写入 `SqlWriter`。
   *
   * @param writer    SQL 输出目标
   * @param leftPrec  当前 `SqlNode` 左侧的优先级
   * @param rightPrec 当前 `SqlNode` 右侧的优先级
   */
  public abstract void unparse(
      SqlWriter writer,
      int leftPrec,
      int rightPrec);

  /**
   * 获取当前 `SqlNode` 在 SQL 解析树中的位置信息。
   *
   * @return SQL 解析位置
   */
  public SqlParserPos getParserPosition() {
    return pos;
  }

  /**
   * 校验该 `SqlNode` 是否符合 SQL 语法规则。
   *
   * @param validator SQL 语法校验器
   * @param scope SQL 作用域
   */
  public abstract void validate(
      SqlValidator validator,
      SqlValidatorScope scope);

  /**
   * 访问者模式（Visitor Pattern），用于遍历 `SqlNode` 结构并执行相关操作。
   *
   * @param visitor SQL 访问者
   * @param <R> 访问者返回值类型
   * @return 访问结果
   */
  public abstract <R> R accept(SqlVisitor<R> visitor);

  /**
   * 判断两个 `SqlNode` 结构是否相等（深度比较）。
   *
   * @param node 另一个 `SqlNode`
   * @param litmus 若不相等，指定如何处理
   * @return 是否相等
   */
  public abstract boolean equalsDeep(@Nullable SqlNode node, Litmus litmus);

  /**
   * 比较两个 `SqlNode` 是否相等，如果两个对象均为 `null`，则认为相等。
   *
   * @param node1 第一个 `SqlNode`
   * @param node2 第二个 `SqlNode`
   * @param litmus 处理不匹配的方式
   * @return `true` 表示相等，`false` 表示不相等
   */
  public static boolean equalDeep(
      @Nullable SqlNode node1,
      @Nullable SqlNode node2,
      Litmus litmus) {
    if (node1 == null) {
      return node2 == null;
    } else if (node2 == null) {
      return false;
    } else {
      return node1.equalsDeep(node2, litmus);
    }
  }

  /**
   * 返回该 SQL 表达式的单调性（是否递增、递减或常数）。
   *
   * @param scope SQL 作用域
   * @return SQL 表达式的单调性
   */
  public SqlMonotonicity getMonotonicity(SqlValidatorScope scope) {
    return SqlMonotonicity.NOT_MONOTONIC;
  }

  /**
   * 提供一个 `Collector`，用于收集 SQL 节点并返回 `SqlNodeList`。
   *
   * @param <T> `SqlNode` 类型
   * @param pos SQL 解析位置信息
   * @return `Collector`，可用于 `Stream` API
   */
  public static <T extends @Nullable SqlNode> Collector<T,
      ArrayList<@Nullable SqlNode>, SqlNodeList> toList(SqlParserPos pos) {
    return Collector.<T, ArrayList<@Nullable SqlNode>, SqlNodeList>of(
        ArrayList::new, ArrayList::add, Util::combine,
        (ArrayList<@Nullable SqlNode> list) -> SqlNodeList.of(pos, list));
  }
}

