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
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.runtime.PairList;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.util.Pair;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * 变量，表示引用输入关系表达式中的一个字段。
 *
 * <p>输入的字段是从 0 开始编号的。如果有多个输入，则字段会依次编号。例如，若一个连接操作的输入是：
 *
 * <ul>
 * <li>输入 #0: EMP(EMPNO, ENAME, DEPTNO) 和</li>
 * <li>输入 #1: DEPT(DEPTNO AS DEPTNO2, DNAME)</li>
 * </ul>
 *
 * <p>则这些字段分别是：
 *
 * <ul>
 * <li>字段 #0: EMPNO</li>
 * <li>字段 #1: ENAME</li>
 * <li>字段 #2: DEPTNO（来自 EMP 表）</li>
 * <li>字段 #3: DEPTNO2（来自 DEPT 表）</li>
 * <li>字段 #4: DNAME</li>
 * </ul>
 *
 * <p>因此，<code>RexInputRef(3, Integer)</code> 就是对字段 DEPTNO2 的正确引用。
 */
public class RexInputRef extends RexSlot {
  //~ Static fields/initializers ---------------------------------------------

  // 用于减少内存分配的常见名称列表
  @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
  private static final List<String> NAMES = new SelfPopulatingList("$", 30);

  //~ Constructors -----------------------------------------------------------

  /**
   * 创建一个输入变量。
   *
   * @param index 字段在底层行类型中的索引
   * @param type  字段的类型
   */
  public RexInputRef(int index, RelDataType type) {
    super(createName(index), index, type);  // 调用父类构造方法，创建字段名称、索引和类型
  }

  //~ Methods ----------------------------------------------------------------

  /**
   * 重写 equals 方法，比较两个 `RexInputRef` 对象是否相等。
   *
   * @param obj 要比较的对象
   * @return 如果两个对象相同或是同一字段引用，则返回 true
   */
  @Override
  public boolean equals(@Nullable Object obj) {
    return this == obj
        || obj instanceof RexInputRef
        && index == ((RexInputRef) obj).index;
  }

  /**
   * 重写 hashCode 方法，根据索引值生成哈希值。
   *
   * @return 返回索引值的哈希码
   */
  @Override
  public int hashCode() {
    return index;
  }

  /**
   * 创建对指定行类型中字段的引用。
   *
   * @param index 目标字段的索引
   * @param rowType 字段所在的行类型
   * @return 返回 `RexInputRef` 对象，引用指定字段
   */
  public static RexInputRef of(int index, RelDataType rowType) {
    return of(index, rowType.getFieldList());  // 通过字段列表创建引用
  }

  /**
   * 创建对指定字段列表中字段的引用。
   *
   * @param index 字段的索引
   * @param fields 字段列表
   * @return 返回 `RexInputRef` 对象，引用指定字段
   */
  public static RexInputRef of(int index, List<RelDataTypeField> fields) {
    return new RexInputRef(index, fields.get(index).getType());  // 通过字段列表和索引获取字段类型并创建引用
  }

  /**
   * 创建对指定字段列表中字段的引用，并返回字段名称。
   *
   * @param index 字段的索引
   * @param fields 字段列表
   * @return 返回一个 `Pair` 对象，其中包含 `RexInputRef` 和字段名称
   */
  public static Pair<RexNode, String> of2(
      int index,
      List<RelDataTypeField> fields) {
    final RelDataTypeField field = fields.get(index);
    return Pair.of(new RexInputRef(index, field.getType()),
        field.getName());  // 返回字段引用和字段名称的 Pair 对象
  }

  /**
   * 向 PairList 中添加对指定字段的引用及其名称。
   *
   * @param list PairList，存储字段引用及其名称
   * @param index 字段的索引
   * @param fields 字段列表
   */
  public static void add2(PairList<RexNode, String> list,
      int index,
      List<RelDataTypeField> fields) {
    final RelDataTypeField field = fields.get(index);
    list.add(new RexInputRef(index, field.getType()), field.getName());
  }

  /**
   * 获取该字段引用的 SQL 类型。
   *
   * @return 返回 SQL 类型，这里返回 `SqlKind.INPUT_REF`
   */
  @Override
  public SqlKind getKind() {
    return SqlKind.INPUT_REF;
  }

  /**
   * 接受一个访问者，用于访问该字段引用对象。
   *
   * @param visitor 访问者
   * @param <R> 访问者的返回类型
   * @return 访问者的访问结果
   */
  @Override
  public <R> R accept(RexVisitor<R> visitor) {
    return visitor.visitInputRef(this);
  }

  /**
   * 接受一个双参数访问者，用于访问该字段引用对象。
   *
   * @param visitor 双参数访问者
   * @param arg 访问者的第二个参数
   * @param <R> 访问者的返回类型
   * @param <P> 访问者的第二个参数类型
   * @return 访问者的访问结果
   */
  @Override
  public <R, P> R accept(RexBiVisitor<R, P> visitor, P arg) {
    return visitor.visitInputRef(this, arg);
  }

  /**
   * 创建输入引用的名称，格式为 "$index"。
   * 如果索引较小，使用常见名称缓存，减少垃圾回收压力。
   *
   * @param index 字段索引
   * @return 返回生成的字段名称
   */
  public static String createName(int index) {
    return NAMES.get(index);  // 从缓存中获取名称
  }
}

