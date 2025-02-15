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
package org.apache.calcite.plan;

import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelDistribution;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelReferentialConstraint;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.schema.ColumnStrategy;
import org.apache.calcite.schema.Wrapper;
import org.apache.calcite.util.ImmutableBitSet;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * `RelOptTable` 代表 {@link RelOptSchema} 中的一个关系型数据表。
 *
 * <p>该接口提供了描述表结构、统计信息、物理特性的方法，并支持将表转换为 {@link RelNode} 关系表达式，
 * 供查询优化器进行优化。</p>
 */
public interface RelOptTable extends Wrapper {
  //~ Methods ----------------------------------------------------------------

  /**
   * 获取表的唯一标识符（限定名称）。
   *
   * <p>表的限定名称通常由数据库名、模式名、表名组成，以唯一标识该表。</p>
   *
   * @return 表的限定名称（`List<String>` 类型），例如 `["database", "schema", "table"]`
   */
  List<String> getQualifiedName();

  /**
   * 获取表的行数估算值。
   *
   * <p>查询优化器可以利用此信息进行优化，例如选择更合适的执行计划。</p>
   *
   * @return 估算的表行数（`double` 类型）
   */
  double getRowCount();

  /**
   * 获取表返回的行数据类型。
   *
   * <p>该方法描述表的列结构，包括列名、数据类型、是否可为空等信息。</p>
   *
   * @return 表的行数据类型（`RelDataType`）
   */
  RelDataType getRowType();

  /**
   * 获取该表所属的 {@link RelOptSchema}（关系优化模式）。
   *
   * @return 该表的 `RelOptSchema`，如果不可用则返回 `null`
   */
  @Nullable RelOptSchema getRelOptSchema();

  /**
   * 将该表转换为 {@link RelNode} 关系表达式。
   *
   * <p>查询优化器会调用此方法，将表转换为初始的逻辑查询树（如 {@link org.apache.calcite.rel.logical.LogicalTableScan}）。
   * 随后，优化器会应用 {@link org.apache.calcite.plan.RelOptRule} 规则，将其优化为更高效的执行计划。</p>
   *
   * @param context 转换上下文，提供必要的信息以进行转换
   * @return 生成的关系表达式（`RelNode`）
   */
  RelNode toRel(ToRelContext context);

  /**
   * 获取该表返回的物理排序（Collation）描述信息。
   *
   * <p>Collation 描述表中数据的物理存储顺序，例如按照某些列升序或降序排列。</p>
   *
   * @return 物理排序列表（`List<RelCollation>`），如果没有排序信息则返回 `null`
   */
  @Nullable List<RelCollation> getCollationList();

  /**
   * 获取该表数据的物理分布（Distribution）信息。
   *
   * <p>数据分布用于描述表的数据在集群或分区中的存储方式，例如哈希分布、范围分布等。</p>
   *
   * @return 物理分布信息（`RelDistribution`），如果未知则返回 `null`
   */
  @Nullable RelDistribution getDistribution();

  /**
   * 判断指定的列集合是否为该表的唯一键或唯一键的超集。
   *
   * <p>唯一键是指可以唯一标识表中某一行的列集合。</p>
   *
   * @param columns 需要检查的列索引集合（`ImmutableBitSet`）
   * @return 若该列集合是唯一键或唯一键的超集，则返回 `true`，否则返回 `false`
   */
  boolean isKey(ImmutableBitSet columns);

  /**
   * 获取该表的所有唯一键。
   *
   * <p>若表没有唯一键，则返回空列表。</p>
   *
   * @return 该表的唯一键列表（`List<ImmutableBitSet>`），如果没有唯一键则返回 `null`
   */
  @Nullable List<ImmutableBitSet> getKeys();

  /**
   * 获取该表的所有外键约束（Referential Constraints）。
   *
   * <p>外键约束表示该表中的某些列引用了其他表的唯一键。</p>
   *
   * @return 该表的外键约束列表（`List<RelReferentialConstraint>`），如果没有外键约束则返回 `null`
   */
  @Nullable List<RelReferentialConstraint> getReferentialConstraints();

  /**
   * 生成该表的代码表达式（通常用于代码生成）。
   *
   * <p>该方法可用于生成 Java 代码以访问该表，通常在 SQL 转换为程序化查询（如 `Queryable`）时使用。</p>
   *
   * @param clazz 目标集合类，例如 `Queryable.class`
   * @return 代码表达式（`Expression`），如果不支持代码生成则返回 `null`
   */
  @Nullable Expression getExpression(Class clazz);

  /**
   * 返回一个扩展了额外字段（Extended Fields）的新表。
   *
   * <p>新表包含原始表的所有字段，并添加 `extendedFields` 中不存在于原始表的字段。</p>
   *
   * @param extendedFields 需要扩展的额外字段列表（`List<RelDataTypeField>`）
   * @return 具有扩展字段的新 `RelOptTable`
   */
  RelOptTable extend(List<RelDataTypeField> extendedFields);

  /**
   * 获取表中各列的填充策略（Column Strategy）。
   *
   * <p>该方法返回一个不可变列表，其大小与表的字段数相同，每个元素表示对应列的填充策略。</p>
   *
   * @return 列填充策略列表（`List<ColumnStrategy>`）
   */
  List<ColumnStrategy> getColumnStrategies();

  /**
   * 视图扩展器接口，用于将视图展开为关系表达式。
   */
  interface ViewExpander {
    /**
     * 将 SQL 视图展开为关系表达式。
     *
     * @param rowType 视图的行类型
     * @param queryString 视图的 SQL 查询体
     * @param schemaPath 视图所在的模式路径
     * @param viewPath 视图的路径，包含视图名称，可为空
     * @return 展开的关系表达式（`RelRoot`）
     */
    RelRoot expandView(RelDataType rowType, String queryString,
        List<String> schemaPath, @Nullable List<String> viewPath);
  }

  /**
   * `ToRelContext` 提供了将表转换为关系表达式所需的上下文信息。
   */
  interface ToRelContext extends ViewExpander {
    /**
     * 获取优化器集群（RelOptCluster）。
     *
     * @return `RelOptCluster` 实例
     */
    RelOptCluster getCluster();

    /**
     * 获取该表的查询提示（Table Hints）。
     *
     * <p>查询提示可用于传递动态参数，影响查询优化决策。</p>
     *
     * @return 该表的查询提示（`List<RelHint>`），不会返回 `null`
     */
    List<RelHint> getTableHints();
  }
}

