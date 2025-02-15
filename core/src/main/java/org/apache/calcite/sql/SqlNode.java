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
 * 表示 SQL 解析树中的一个节点（SqlNode）。
 *
 * <p>SQL 解析树由多个类型的节点组成，例如：
 * {@link SqlCall}（函数调用），
 * {@link SqlLiteral}（字面量），
 * {@link SqlIdentifier}（标识符）等。
 *
 * <p>该类是一个抽象类，所有 SQL 解析节点都继承自它。
 */
public abstract class SqlNode implements Cloneable {

  //~ 静态字段/初始化代码块 ------------------------------------------------

  /**
   * 空的 SqlNode 数组，通常用于返回空结果时使用。
   */
  public static final @Nullable SqlNode[] EMPTY_ARRAY = new SqlNode[0];

  //~ 实例字段 ------------------------------------------------------------

  /**
   * 该 SQL 节点在解析时的位置（行号和列号）。
   * 该信息用于错误报告或调试。
   */
  protected final SqlParserPos pos;

  //~ 构造方法 ------------------------------------------------------------

  /**
   * 创建一个 SQL 解析节点。
   *
   * @param pos 该节点在 SQL 解析树中的位置信息，不能为空。
   */
  SqlNode(SqlParserPos pos) {
    this.pos = requireNonNull(pos, "pos");
  }

  //~ 方法 ----------------------------------------------------------------

  // CHECKSTYLE: IGNORE 1
  /**
   * @deprecated 请使用 {@link #clone(SqlNode)} 方法进行克隆；
   * 这个方法是 Java 早期版本的遗留方法，不建议使用。
   */
  @Deprecated
  @SuppressWarnings({"MethodDoesntCallSuperMethod", "AmbiguousMethodReference"})
  @Override public Object clone() {
    return clone(getParserPosition());
  }

  /**
   * 克隆一个 `SqlNode` 对象。
   *
   * @param e 要克隆的 `SqlNode` 对象
   * @param <E> `SqlNode` 的具体子类
   * @return 克隆后的 `SqlNode` 对象
   */
  @SuppressWarnings("AmbiguousMethodReference")
  public static <E extends SqlNode> E clone(E e) {
    //noinspection unchecked
    return (E) e.clone(e.pos);
  }

  /**
   * 以不同的位置克隆 `SqlNode`。
   * 该方法需要在子类中具体实现。
   *
   * @param pos 新的 SQL 解析位置信息
   * @return 克隆后的 `SqlNode`
   */
  public abstract SqlNode clone(SqlParserPos pos);

  /**
   * 获取该 SQL 节点的类型。
   *
   * @return `SqlKind` 类型的枚举值，如果没有特殊类型，则返回 {@link SqlKind#OTHER}。
   * @see #isA
   */
  public SqlKind getKind() {
    return SqlKind.OTHER;
  }

  /**
   * 判断当前 SQL 节点是否属于某个 SQL 语法类别。
   *
   * <p>例如：
   * {@code node.isA(SqlKind.QUERY)} 如果 `node` 是 `SELECT`、`INSERT`、`UPDATE` 等查询语句，则返回 `true`。
   *
   * <p>该方法的快捷方式：{@code node.isA(category)} 等价于 {@code node.getKind().belongsTo(category)}。
   *
   * @param category SQL 语法类别
   * @return 该 SQL 节点是否属于指定的 SQL 语法类别
   */
  public final boolean isA(Set<SqlKind> category) {
    return getKind().belongsTo(category);
  }

  /**
   * @deprecated 该方法将在 2.0 版本前移除。
   * 建议使用 {@link #clone(SqlNode)} 方法进行克隆。
   */
  @Deprecated
  public static SqlNode[] cloneArray(SqlNode[] nodes) {
    SqlNode[] clones = nodes.clone();
    for (int i = 0; i < clones.length; i++) {
      SqlNode node = clones[i];
      if (node != null) {
        clones[i] = SqlNode.clone(node);
      }
    }
    return clones;
  }

  /**
   * 返回 SQL 解析树的字符串表示形式。
   *
   * <p>示例返回值：
   * <ul>
   *   <li>'It''s a bird!'</li>
   *   <li>NULL</li>
   *   <li>12.3</li>
   *   <li>DATE '1969-04-29'</li>
   * </ul>
   *
   * <p>该方法主要用于调试，在 IDE 里快速获取 SQL 解析树的文本表示。
   *
   * @return SQL 表达式的字符串表示
   */
  @Override public String toString() {
    return toSqlString(c -> c.withDialect(AnsiSqlDialect.DEFAULT)
        .withAlwaysUseParentheses(false)  // 是否始终使用括号，默认否
        .withSelectListItemsOnSeparateLines(false)  // 是否在 SELECT 语句中换行
        .withUpdateSetListNewline(false)  // 是否在 UPDATE 语句的 SET 部分换行
        .withIndentation(0))  // 代码缩进级别
        .getSql();
  }


  /**
   * 返回当前 SQL 解析树（以此 `SqlNode` 为根）的 SQL 文本表示。
   *
   * <p>典型的返回值示例：
   * <ul>
   *   <li>'It''s a bird!'</li>
   *   <li>NULL</li>
   *   <li>12.3</li>
   *   <li>DATE '1969-04-29'</li>
   * </ul>
   *
   * @param transform 提供 SQL 格式化配置的转换函数
   * @return 格式化后的 SQL 语句
   */
  public SqlString toSqlString(UnaryOperator<SqlWriterConfig> transform) {
    // 应用转换函数获取配置
    final SqlWriterConfig config = transform.apply(SqlPrettyWriter.config());
    // 使用格式化配置创建 SQL Pretty Writer
    SqlPrettyWriter writer = new SqlPrettyWriter(config);
    // 进行 SQL 解析并写入 Writer
    unparse(writer, 0, 0);
    return writer.toSqlString();
  }

  /**
   * 返回当前 SQL 解析树（以此 `SqlNode` 为根）的 SQL 文本表示。
   *
   * <p>典型的返回值示例：
   * <ul>
   *   <li>'It''s a bird!'</li>
   *   <li>NULL</li>
   *   <li>12.3</li>
   *   <li>DATE '1969-04-29'</li>
   * </ul>
   *
   * @param dialect     SQL 方言（如果为 `null`，则使用 ANSI SQL）
   * @param forceParens 是否强制使用括号（通常用于解析测试，默认为 `false`）
   * @return 格式化后的 SQL 语句
   */
  public SqlString toSqlString(@Nullable SqlDialect dialect, boolean forceParens) {
    return toSqlString(c ->
        c.withDialect(Util.first(dialect, AnsiSqlDialect.DEFAULT))  // 使用指定的 SQL 方言
            .withAlwaysUseParentheses(forceParens)  // 是否强制括号
            .withSelectListItemsOnSeparateLines(false)  // 是否在 SELECT 列表中换行
            .withUpdateSetListNewline(false)  // 是否在 UPDATE 语句的 SET 部分换行
            .withIndentation(0));  // 设置代码缩进级别
  }

  /**
   * 返回当前 SQL 解析树（以此 `SqlNode` 为根）的 SQL 文本表示。
   *
   * <p>使用默认不强制括号的格式化方式。
   *
   * @param dialect SQL 方言（如果为 `null`，则使用 ANSI SQL）
   * @return 格式化后的 SQL 语句
   */
  public SqlString toSqlString(@Nullable SqlDialect dialect) {
    return toSqlString(dialect, false);
  }

  /**
   * 将当前 SQL 解析节点转换为 SQL 代码并写入到 `SqlWriter` 中。
   *
   * <p>`leftPrec` 和 `rightPrec` 参数用于确定是否需要括号。
   * 例如，在 `5 * (2 + 3)` 这样的表达式中，需要括号是因为 `*` 的优先级高于 `+`。
   *
   * <p>此方法采用的优先级算法如下：
   * <ul>
   *   <li>左结合运算符：左操作数的优先级略高，右操作数的优先级略低。</li>
   *   <li>右结合运算符：右操作数的优先级略高，左操作数的优先级略低。</li>
   * </ul>
   *
   * <p>如果 {@link SqlWriter#isAlwaysUseParentheses()} 返回 `true`，即使优先级规则不需要括号，也会强制加上括号。
   *
   * @param writer    目标 SQL Writer
   * @param leftPrec  解析树中左侧节点的优先级
   * @param rightPrec 解析树中右侧节点的优先级
   */
  public abstract void unparse(
      SqlWriter writer,
      int leftPrec,
      int rightPrec);

  /**
   * 将当前 SQL 解析节点转换为 SQL 代码，并根据 `parentheses` 参数决定是否强制添加括号。
   *
   * @param writer      目标 SQL Writer
   * @param leftPrec    左侧优先级
   * @param rightPrec   右侧优先级
   * @param parentheses 是否强制使用括号
   */
  public void unparseWithParentheses(SqlWriter writer, int leftPrec,
      int rightPrec, boolean parentheses) {
    if (parentheses) {
      // 强制使用括号
      final SqlWriter.Frame frame = writer.startList("(", ")");
      unparse(writer, 0, 0);
      writer.endList(frame);
    } else {
      // 按照正常优先级解析 SQL
      unparse(writer, leftPrec, rightPrec);
    }
  }

  /**
   * 获取 SQL 解析节点的位置信息（行号、列号）。
   *
   * @return 该 SQL 节点的位置信息
   */
  public SqlParserPos getParserPosition() {
    return pos;
  }

  /**
   * 验证当前 SQL 解析节点的正确性。
   *
   * <p>该方法通常会回调 SQL 语法检查器（Validator）的适当方法，例如：
   * {@link SqlValidator#validateLiteral}（检查 SQL 字面量是否合法）。
   *
   * @param validator SQL 语法检查器
   * @param scope     语法检查的作用域
   */
  public abstract void validate(
      SqlValidator validator,
      SqlValidatorScope scope);


  /**
   * 解析 SQL 语法树中的有效替代选项。
   *
   * <p>如果当前 SQL 解析节点的位置与 `pos` 位置匹配，则列出所有有效的替代选项。
   * 目前仅对 `SqlCall` 和 `SqlOperator` 进行实现。
   *
   * @param validator SQL 解析器验证器
   * @param scope 作用域信息
   * @param pos 指定的 SQL 解析位置
   * @param hintList 存储解析提示信息的集合
   */
  public void findValidOptions(
      SqlValidator validator,
      SqlValidatorScope scope,
      SqlParserPos pos,
      Collection<SqlMoniker> hintList) {
    // 目前不提供任何有效选项
  }

  /**
   * 在表达式上下文中验证当前 SQL 节点的合法性。
   *
   * <p>通常，该方法与 {@link #validate} 方法类似，
   * 但 `SqlIdentifier` 既可以出现在表达式上下文，也可以出现在非表达式上下文中。
   *
   * @param validator SQL 验证器
   * @param scope 作用域信息
   */
  public void validateExpr(
      SqlValidator validator,
      SqlValidatorScope scope) {
    validate(validator, scope);
    Util.discard(validator.deriveType(scope, this));
  }

  /**
   * 接受访问者对象并进行相应的访问操作。
   *
   * <p>不同类型的 `SqlNode` 会调用 `SqlVisitor` 访问器的相应方法。
   *
   * @param visitor 访问器对象
   * @param <R> 访问返回的结果类型
   * @return 访问器返回的结果
   */
  public abstract <R> R accept(SqlVisitor<R> visitor);

  /**
   * 深度比较两个 SQL 语法树节点是否结构上相等。
   *
   * <p>示例：
   * <ul>
   * <li>`1 + 2` 在结构上等同于 `1 + 2`</li>
   * <li>`1 + 2 + 3` 在结构上等同于 `(1 + 2) + 3`，但不同于 `1 + (2 + 3)`，
   * 因为 `+` 运算符是左结合的。</li>
   * </ul>
   *
   * @param node 另一个 SQL 语法树节点
   * @param litmus 用于处理检测到的错误
   * @return 是否结构相等
   */
  public abstract boolean equalsDeep(@Nullable SqlNode node, Litmus litmus);

  /**
   * 判断两个 SQL 语法树节点是否结构上相等，或两者是否都为 `null`。
   *
   * @param node1 第一个 SQL 语法树节点
   * @param node2 第二个 SQL 语法树节点
   * @param litmus 用于处理错误情况
   * @return 如果两个节点结构相等或都为 `null`，则返回 `true`，否则返回 `false`
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
   * 判断表达式的单调性（是否单调递增、递减或常量）。
   *
   * <p>该属性可用于确定某些 SQL 查询是否可以安全地进行流式聚合。
   *
   * <p>默认情况下，返回 `SqlMonotonicity.NOT_MONOTONIC`（非单调）。
   *
   * @param scope 作用域信息
   * @return SQL 表达式的单调性
   */
  public SqlMonotonicity getMonotonicity(SqlValidatorScope scope) {
    return SqlMonotonicity.NOT_MONOTONIC;
  }

  /**
   * 判断两个 SQL 语法树节点列表是否结构上等价。
   *
   * @param operands0 第一个节点列表
   * @param operands1 第二个节点列表
   * @param litmus 处理错误的方式
   * @return 如果两个列表中的所有节点结构一致，则返回 `true`
   */
  public static boolean equalDeep(
      List<? extends @Nullable SqlNode> operands0,
      List<? extends @Nullable SqlNode> operands1,
      Litmus litmus) {
    if (operands0.size() != operands1.size()) {
      return litmus.fail(null);
    }
    for (int i = 0; i < operands0.size(); i++) {
      if (!SqlNode.equalDeep(operands0.get(i), operands1.get(i), litmus)) {
        return litmus.fail(null);
      }
    }
    return litmus.succeed();
  }

  /**
   * 返回一个 `Collector`，用于将输入元素累积到 `SqlNodeList` 中，并使用默认位置。
   *
   * @param <T> 输入元素的类型
   * @return 收集所有输入元素的 `Collector`
   */
  public static <T extends SqlNode> Collector<T, ArrayList<@Nullable SqlNode>, SqlNodeList>
  toList() {
    return toList(SqlParserPos.ZERO);
  }

  /**
   * 返回一个 `Collector`，用于将输入元素累积到 `SqlNodeList` 中。
   *
   * @param <T> 输入元素的类型
   * @param pos 解析器位置
   * @return 收集所有输入元素的 `Collector`
   */
  public static <T extends @Nullable SqlNode> Collector<T,
      ArrayList<@Nullable SqlNode>, SqlNodeList> toList(SqlParserPos pos) {
    return Collector.<T, ArrayList<@Nullable SqlNode>, SqlNodeList>of(
        ArrayList::new, ArrayList::add, Util::combine,
        (ArrayList<@Nullable SqlNode> list) -> SqlNodeList.of(pos, list));
  }
}
