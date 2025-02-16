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

/**
 * 表示引用输入关系表达式字段的变量。
 *
 * 该类扩展自 `RexInputRef`，用于表示查询中的模式匹配表达式中的字段引用，通常在模式匹配操作中使用。
 */
public class RexPatternFieldRef extends RexInputRef {
  /** 模式的名称，用于标识字段 */
  private final String alpha;

  /**
   * 构造一个 `RexPatternFieldRef` 实例。
   *
   * @param alpha 模式的名称，用于标识字段
   * @param index 字段在输入关系中的索引
   * @param type 字段的数据类型
   */
  public RexPatternFieldRef(String alpha, int index, RelDataType type) {
    super(index, type);  // 调用父类的构造方法，初始化字段索引和类型
    this.alpha = alpha;  // 初始化模式名称
    digest = alpha + ".$" + index;  // 生成该字段的摘要信息，形式如 "alpha.$index"
  }

  /**
   * 获取模式的名称（alpha）。
   *
   * @return 模式的名称
   */
  public String getAlpha() {
    return alpha;
  }

  /**
   * 创建一个新的 `RexPatternFieldRef` 实例。
   *
   * @param alpha 模式的名称
   * @param index 字段索引
   * @param type 字段的数据类型
   * @return 新的 `RexPatternFieldRef` 实例
   */
  public static RexPatternFieldRef of(String alpha, int index, RelDataType type) {
    return new RexPatternFieldRef(alpha, index, type);
  }

  /**
   * 从 `RexInputRef` 创建一个新的 `RexPatternFieldRef` 实例。
   *
   * @param alpha 模式的名称
   * @param ref `RexInputRef` 实例，提供字段的索引和类型
   * @return 新的 `RexPatternFieldRef` 实例
   */
  public static RexPatternFieldRef of(String alpha, RexInputRef ref) {
    return new RexPatternFieldRef(alpha, ref.getIndex(), ref.getType());
  }

  /**
   * 接受一个访问者，访问该字段引用对象。
   *
   * @param visitor 访问者
   * @param <R> 访问者的返回类型
   * @return 访问者访问结果
   */
  @Override
  public <R> R accept(RexVisitor<R> visitor) {
    return visitor.visitPatternFieldRef(this);
  }

  /**
   * 接受一个双参数访问者，访问该字段引用对象。
   *
   * @param visitor 双参数访问者
   * @param arg 访问者的第二个参数
   * @param <R> 访问者的返回类型
   * @param <P> 访问者的第二个参数类型
   * @return 访问者访问结果
   */
  @Override
  public <R, P> R accept(RexBiVisitor<R, P> visitor, P arg) {
    return visitor.visitPatternFieldRef(this, arg);
  }

  /**
   * 获取该字段引用的 SQL 类型。
   *
   * @return 字段引用的 SQL 类型，这里返回 `SqlKind.PATTERN_INPUT_REF`。
   */
  @Override
  public SqlKind getKind() {
    return SqlKind.PATTERN_INPUT_REF;
  }
}

