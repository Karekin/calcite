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

import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptPredicateList;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelDistribution;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexTableInputRef;
import org.apache.calcite.rex.RexTableInputRef.RelTableRef;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.BuiltInMethod;
import org.apache.calcite.util.ImmutableBitSet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Set;

/**
 * 提供多个常见元数据（Metadata）接口的定义。
 *
 * <p>元数据通常用于查询优化，包括谓词选择性（Selectivity）、唯一键（Unique Keys）、列排序信息（Collation）、数据分布（Distribution）等。</p>
 */
public abstract class BuiltInMetadata {

  /**
   * 选择性（Selectivity）元数据接口，用于估算谓词（Predicate）在查询结果中的过滤比例。
   */
  public interface Selectivity extends Metadata {
    MetadataDef<Selectivity> DEF =
        MetadataDef.of(Selectivity.class, Selectivity.Handler.class,
            BuiltInMethod.SELECTIVITY.method);

    /**
     * 估算关系表达式的输出行中满足给定谓词（Predicate）的比例（选择性）。
     *
     * @param predicate 需要估算选择性的谓词
     * @return 选择性值（介于 0.0 和 1.0 之间），如果无法估算，则返回 null
     */
    @Nullable Double getSelectivity(@Nullable RexNode predicate);

    /** 处理器 API */
    @FunctionalInterface
    interface Handler extends MetadataHandler<Selectivity> {
      @Nullable Double getSelectivity(RelNode r, RelMetadataQuery mq, @Nullable RexNode predicate);

      @Override default MetadataDef<Selectivity> getDef() {
        return DEF;
      }
    }
  }

  /**
   * 唯一键（Unique Keys）元数据接口，提供关系表达式中唯一标识行的列组合信息。
   */
  public interface UniqueKeys extends Metadata {
    MetadataDef<UniqueKeys> DEF =
        MetadataDef.of(UniqueKeys.class, UniqueKeys.Handler.class,
            BuiltInMethod.UNIQUE_KEYS.method);

    /**
     * 获取关系表达式的最小唯一键集合。
     *
     * <p>唯一键使用 {@link ImmutableBitSet} 表示，其中每个位表示一个基于 0 的输出列索引。</p>
     *
     * @param ignoreNulls 是否忽略 null 值
     * @return 唯一键集合（每个键为列索引的 `ImmutableBitSet`），
     *         如果无法确定，则返回 null；空集表示没有唯一键
     */
    @Nullable Set<ImmutableBitSet> getUniqueKeys(boolean ignoreNulls);

    /** 处理器 API */
    @FunctionalInterface
    interface Handler extends MetadataHandler<UniqueKeys> {
      @Nullable Set<ImmutableBitSet> getUniqueKeys(RelNode r, RelMetadataQuery mq,
          boolean ignoreNulls);

      @Override default MetadataDef<UniqueKeys> getDef() {
        return DEF;
      }
    }
  }

  /**
   * 列唯一性（Column Uniqueness）元数据接口，提供特定列是否唯一的信息。
   */
  public interface ColumnUniqueness extends Metadata {
    MetadataDef<ColumnUniqueness> DEF =
        MetadataDef.of(ColumnUniqueness.class, ColumnUniqueness.Handler.class,
            BuiltInMethod.COLUMN_UNIQUENESS.method);

    /**
     * 判断关系表达式中的某些列是否能唯一标识一行。
     *
     * @param columns 需要检查的列集合（以 `ImmutableBitSet` 形式表示）
     * @param ignoreNulls 是否忽略 null 值
     * @return 如果列是唯一的，则返回 true；如果不是，则返回 false；
     *         如果无法确定，则返回 null
     */
    Boolean areColumnsUnique(ImmutableBitSet columns, boolean ignoreNulls);

    /** 处理器 API */
    @FunctionalInterface
    interface Handler extends MetadataHandler<ColumnUniqueness> {
      Boolean areColumnsUnique(RelNode r, RelMetadataQuery mq,
          ImmutableBitSet columns, boolean ignoreNulls);

      @Override default MetadataDef<ColumnUniqueness> getDef() {
        return DEF;
      }
    }
  }

  /**
   * 列排序信息（Collation）元数据接口，提供关系表达式中哪些列是排序的。
   */
  public interface Collation extends Metadata {
    MetadataDef<Collation> DEF =
        MetadataDef.of(Collation.class, Collation.Handler.class,
            BuiltInMethod.COLLATIONS.method);

    /**
     * 获取关系表达式的列排序信息。
     *
     * @return 排序列的 `RelCollation` 列表
     */
    ImmutableList<RelCollation> collations();

    /** 处理器 API */
    @FunctionalInterface
    interface Handler extends MetadataHandler<Collation> {
      ImmutableList<RelCollation> collations(RelNode r, RelMetadataQuery mq);

      @Override default MetadataDef<Collation> getDef() {
        return DEF;
      }
    }
  }

  /**
   * 数据分布（Distribution）元数据接口，提供关系表达式的分布信息。
   *
   * <p>数据分布用于优化查询计划，例如：
   * - `BROADCAST`（广播）或 `SINGLETON`（单点）表示所有数据都可见；
   * - `HASH`（哈希分布）表示数据按哈希值分布；
   * - `RANGE`（范围分布）表示数据按值范围分布。</p>
   *
   * <p>数据可能被分布到多个节点或多个线程中。</p>
   */
  public interface Distribution extends Metadata {
    MetadataDef<Distribution> DEF =
        MetadataDef.of(Distribution.class, Distribution.Handler.class,
            BuiltInMethod.DISTRIBUTION.method);

    /**
     * 获取数据的分布方式。
     *
     * @return `RelDistribution`，表示数据如何在计算资源之间分布
     */
    RelDistribution distribution();

    /** 处理器 API */
    @FunctionalInterface
    interface Handler extends MetadataHandler<Distribution> {
      RelDistribution distribution(RelNode r, RelMetadataQuery mq);

      @Override default MetadataDef<Distribution> getDef() {
        return DEF;
      }
    }
  }


  /**
   * 关系表达式中节点类型的元数据。
   *
   * <p>对于每个关系表达式，它返回一个从类到实例化该类的节点的多重映射。
   * 每个节点在多重映射中只会出现一次。
   */
  public interface NodeTypes extends Metadata {
    // 定义了 NodeTypes 的元数据定义，包含类、处理器类型和方法。
    MetadataDef<NodeTypes> DEF =
        MetadataDef.of(NodeTypes.class, NodeTypes.Handler.class,
            BuiltInMethod.NODE_TYPES.method);

    /**
     * 返回从类到实例化该类的节点的多重映射。默认实现将节点分类为 {@link RelNode}。
     */
    @Nullable Multimap<Class<? extends RelNode>, RelNode> getNodeTypes();

    /** 处理器 API。 */
    @FunctionalInterface
    interface Handler extends MetadataHandler<NodeTypes> {
      // 返回一个多重映射，从类到实例化该类的节点
      @Nullable Multimap<Class<? extends RelNode>, RelNode> getNodeTypes(RelNode r,
          RelMetadataQuery mq);

      @Override default MetadataDef<NodeTypes> getDef() {
        return DEF;
      }
    }
  }

  /** 关系表达式返回的行数的元数据。 */
  public interface RowCount extends Metadata {
    // 定义了 RowCount 的元数据定义，包含类、处理器类型和方法。
    MetadataDef<RowCount> DEF =
        MetadataDef.of(RowCount.class, RowCount.Handler.class,
            BuiltInMethod.ROW_COUNT.method);

    /**
     * 估算关系表达式将返回的行数。默认实现通过 {@link RelNode#estimateRowCount} 请求 rel 本身，
     * 但元数据提供者可以通过自己的成本模型覆盖此方法。
     *
     * @return 估算的行数，如果无法确定可靠的估算值则返回 null
     */
    @Nullable Double getRowCount();

    /** 处理器 API。 */
    @FunctionalInterface
    interface Handler extends MetadataHandler<RowCount> {
      // 返回估算的行数
      @Nullable Double getRowCount(RelNode r, RelMetadataQuery mq);

      @Override default MetadataDef<RowCount> getDef() {
        return DEF;
      }
    }
  }

  /** 关系表达式返回的最大行数的元数据。 */
  public interface MaxRowCount extends Metadata {
    // 定义了 MaxRowCount 的元数据定义，包含类、处理器类型和方法。
    MetadataDef<MaxRowCount> DEF =
        MetadataDef.of(MaxRowCount.class, MaxRowCount.Handler.class,
            BuiltInMethod.MAX_ROW_COUNT.method);

    /**
     * 估算关系表达式将返回的最大行数。
     *
     * <p>默认实现返回 {@link Double#POSITIVE_INFINITY}，
     * 但元数据提供者可以通过自己的成本模型覆盖此方法。
     *
     * @return 返回的行数的上限
     */
    @Nullable Double getMaxRowCount();

    /** 处理器 API。 */
    @FunctionalInterface
    interface Handler extends MetadataHandler<MaxRowCount> {
      // 返回估算的最大行数
      @Nullable Double getMaxRowCount(RelNode r, RelMetadataQuery mq);

      @Override default MetadataDef<MaxRowCount> getDef() {
        return DEF;
      }
    }
  }

  /** 关系表达式返回的最小行数的元数据。 */
  public interface MinRowCount extends Metadata {
    // 定义了 MinRowCount 的元数据定义，包含类、处理器类型和方法。
    MetadataDef<MinRowCount> DEF =
        MetadataDef.of(MinRowCount.class, MinRowCount.Handler.class,
            BuiltInMethod.MIN_ROW_COUNT.method);

    /**
     * 估算关系表达式将返回的最小行数。
     *
     * <p>默认实现返回 0，
     * 但元数据提供者可以通过自己的成本模型覆盖此方法。
     *
     * @return 返回的行数的下限
     */
    @Nullable Double getMinRowCount();

    /** 处理器 API。 */
    @FunctionalInterface
    interface Handler extends MetadataHandler<MinRowCount> {
      // 返回估算的最小行数
      @Nullable Double getMinRowCount(RelNode r, RelMetadataQuery mq);

      @Override default MetadataDef<MinRowCount> getDef() {
        return DEF;
      }
    }
  }

  /** 关系表达式中一组列返回的不同值的行数的元数据。 */
  public interface DistinctRowCount extends Metadata {
    // 定义了 DistinctRowCount 的元数据定义，包含类、处理器类型和方法。
    MetadataDef<DistinctRowCount> DEF =
        MetadataDef.of(DistinctRowCount.class, DistinctRowCount.Handler.class,
            BuiltInMethod.DISTINCT_ROW_COUNT.method);

    /**
     * 估算在指定列的 GROUP BY 操作中会返回的行数，该操作的输入已通过谓词预过滤。
     * 这个数量（忽略谓词）通常被称为基数（例如，性别是一个“低基数列”）。
     *
     * @param groupKey  表示 GROUP BY 列的列掩码
     * @param predicate 预过滤的谓词
     * @return 经过谓词过滤的 groupKey 的不同值的行数，如果无法确定可靠的估算值则返回 null
     */
    @Nullable Double getDistinctRowCount(ImmutableBitSet groupKey, @Nullable RexNode predicate);

    /** 处理器 API。 */
    @FunctionalInterface
    interface Handler extends MetadataHandler<DistinctRowCount> {
      // 返回估算的不同值行数
      @Nullable Double getDistinctRowCount(RelNode r, RelMetadataQuery mq,
          ImmutableBitSet groupKey, @Nullable RexNode predicate);

      @Override default MetadataDef<DistinctRowCount> getDef() {
        return DEF;
      }
    }
  }

  /** 关系表达式中原始行数占比的元数据。 */
  public interface PercentageOriginalRows extends Metadata {
    // 定义了 PercentageOriginalRows 的元数据定义，包含类、处理器类型和方法。
    MetadataDef<PercentageOriginalRows> DEF =
        MetadataDef.of(PercentageOriginalRows.class,
            PercentageOriginalRows.Handler.class,
            BuiltInMethod.PERCENTAGE_ORIGINAL_ROWS.method);

    /**
     * 估算关系表达式实际返回的行数占去除所有单表过滤条件后会返回的行数的百分比。
     *
     * @return 估算的百分比（介于 0.0 到 1.0 之间），如果无法确定可靠的估算值则返回 null
     */
    @Nullable Double getPercentageOriginalRows();

    /** 处理器 API。 */
    @FunctionalInterface
    interface Handler extends MetadataHandler<PercentageOriginalRows> {
      // 返回估算的百分比
      @Nullable Double getPercentageOriginalRows(RelNode r, RelMetadataQuery mq);

      @Override default MetadataDef<PercentageOriginalRows> getDef() {
        return DEF;
      }
    }
  }


  /**
   * 关系表达式中某一列或一组列的原始来源中不同值的元数据。
   */
  public interface PopulationSize extends Metadata {
    // 定义了 PopulationSize 的元数据定义，包含类、处理器类型和方法。
    MetadataDef<PopulationSize> DEF =
        MetadataDef.of(PopulationSize.class, PopulationSize.Handler.class,
            BuiltInMethod.POPULATION_SIZE.method);

    /**
     * 估算给定 {@code groupKey} 在原始来源中不同的行数，忽略当前表达式应用的任何过滤。
     * 通常，“原始来源”指的是基础表，但对于衍生列，估算可能来自非叶节点关系表达式，如 LogicalProject。
     *
     * @param groupKey 表示列子集的列掩码，这些列的行数将被确定
     * @return 给定 groupKey 的不同值行数，若无法可靠估算，则返回 null
     */
    @Nullable Double getPopulationSize(ImmutableBitSet groupKey);

    /** 处理器 API。 */
    @FunctionalInterface
    interface Handler extends MetadataHandler<PopulationSize> {
      // 返回估算的不同值行数
      @Nullable Double getPopulationSize(RelNode r, RelMetadataQuery mq,
          ImmutableBitSet groupKey);

      @Override default MetadataDef<PopulationSize> getDef() {
        return DEF;
      }
    }
  }

  /**
   * 关系表达式中行和列大小的元数据。
   */
  public interface Size extends Metadata {
    // 定义了 Size 的元数据定义，包含类、处理器类型和方法。
    MetadataDef<Size> DEF =
        MetadataDef.of(Size.class, Size.Handler.class,
            BuiltInMethod.AVERAGE_ROW_SIZE.method,
            BuiltInMethod.AVERAGE_COLUMN_SIZES.method);

    /**
     * 确定此关系表达式中一行的平均大小（字节数）。
     *
     * @return 行的平均大小（字节），如果未知则返回 null
     */
    @Nullable Double averageRowSize();

    /**
     * 确定此关系表达式中一列的值的平均大小（字节数）。
     *
     * <p>包括空值（假设它们占用接近 0 字节）。
     *
     * <p>调用者需要自行决定大小是压缩后的大小、未压缩的大小，还是值在 Java 堆中封装成对象时的内存分配。未压缩大小可能是一个好的折衷方案。
     *
     * @return 一个不可变的列表，包含每列值的平均大小（字节），如果元数据不可用，则该值或整个列表可能为 null
     */
    List<@Nullable Double> averageColumnSizes();

    /** 处理器 API。 */
    interface Handler extends MetadataHandler<Size> {
      // 返回估算的平均行大小
      @Nullable Double averageRowSize(RelNode r, RelMetadataQuery mq);
      // 返回估算的平均列值大小
      @Nullable List<@Nullable Double> averageColumnSizes(RelNode r, RelMetadataQuery mq);

      @Override default MetadataDef<Size> getDef() {
        return DEF;
      }
    }
  }

  /**
   * 关系表达式中列的来源的元数据。
   */
  public interface ColumnOrigin extends Metadata {
    // 定义了 ColumnOrigin 的元数据定义，包含类、处理器类型和方法。
    MetadataDef<ColumnOrigin> DEF =
        MetadataDef.of(ColumnOrigin.class, ColumnOrigin.Handler.class,
            BuiltInMethod.COLUMN_ORIGIN.method);

    /**
     * 对于给定的表达式输出列，确定所有贡献结果值的基础表中的列。
     * 输出列可能有多个来源，尤其是在像 Union 和 LogicalProject 这样的表达式中。
     * 优化器可以使用此信息进行目录访问（例如索引可用性）。
     *
     * @param outputColumn 输出列的 0 基序号
     * @return 来源列的集合，如果无法确定此信息则返回 null（空集合表示绝对没有来源列）
     */
    @Nullable Set<RelColumnOrigin> getColumnOrigins(int outputColumn);

    /** 处理器 API。 */
    @FunctionalInterface
    interface Handler extends MetadataHandler<ColumnOrigin> {
      // 返回给定输出列的来源列集合
      @Nullable Set<RelColumnOrigin> getColumnOrigins(RelNode r, RelMetadataQuery mq,
          int outputColumn);

      @Override default MetadataDef<ColumnOrigin> getDef() {
        return DEF;
      }
    }
  }

  /**
   * 关系表达式中表达式的来源链（血缘）的元数据。
   */
  public interface ExpressionLineage extends Metadata {
    // 定义了 ExpressionLineage 的元数据定义，包含类、处理器类型和方法。
    MetadataDef<ExpressionLineage> DEF =
        MetadataDef.of(ExpressionLineage.class, ExpressionLineage.Handler.class,
            BuiltInMethod.EXPRESSION_LINEAGE.method);

    /**
     * 给定在给定 {@link RelNode} 上应用的输入表达式，返回解析了血缘信息的表达式。
     *
     * <p>特别地，结果将是一个节点集合，可能包含引用 TableScan 操作符中的列（{@link RexTableInputRef}）。
     * 由于 Union 操作符的存在，一个表达式可能有多个来源链。需要注意的是，我们不检查过滤谓词中的列相等性。
     * 每个 TableScan 操作符在节点下是通过其限定名和实体编号唯一标识的。
     *
     * @param expression 要解析血缘信息的表达式
     *
     * @return 已解析血缘信息的表达式集合，如果无法确定此信息（例如表达式的来源是聚合操作符中的聚合）则返回 null
     */
    @Nullable Set<RexNode> getExpressionLineage(RexNode expression);

    /** 处理器 API。 */
    @FunctionalInterface
    interface Handler extends MetadataHandler<ExpressionLineage> {
      // 返回给定表达式的血缘信息
      @Nullable Set<RexNode> getExpressionLineage(RelNode r, RelMetadataQuery mq,
          RexNode expression);

      @Override default MetadataDef<ExpressionLineage> getDef() {
        return DEF;
      }
    }
  }

  /**
   * 获取给定表达式使用的表的引用的元数据。
   */
  public interface TableReferences extends Metadata {
    // 定义了 TableReferences 的元数据定义，包含类、处理器类型和方法。
    MetadataDef<TableReferences> DEF =
        MetadataDef.of(TableReferences.class, TableReferences.Handler.class,
            BuiltInMethod.TABLE_REFERENCES.method);

    /**
     * 该提供程序返回给定计划中使用的表。
     *
     * <p>特别地，结果将是每个 TableScan 操作符的唯一表引用集合（{@link RelTableRef}）。
     * 这些表引用由表的限定名和实体编号组成。
     *
     * <p>重要的是，返回的表标识符将与 {@link ExpressionLineage} 提供程序使用的唯一标识符一致，
     * 这意味着保证相同的表在两者中使用相同的唯一标识符。
     *
     * @return 唯一的表标识符集合，如果无法确定此信息则返回 null
     */
    Set<RelTableRef> getTableReferences();

    /** 处理器 API。 */
    @FunctionalInterface
    interface Handler extends MetadataHandler<TableReferences> {
      // 返回给定关系表达式使用的表的引用
      Set<RelTableRef> getTableReferences(RelNode r, RelMetadataQuery mq);

      @Override default MetadataDef<TableReferences> getDef() {
        return DEF;
      }
    }
  }


  /**
   * 关系表达式的评估成本的元数据，包括所有输入的成本。
   */
  public interface CumulativeCost extends Metadata {
    // 定义了 CumulativeCost 的元数据定义，包含类、处理器类型和方法。
    MetadataDef<CumulativeCost> DEF =
        MetadataDef.of(CumulativeCost.class, CumulativeCost.Handler.class,
            BuiltInMethod.CUMULATIVE_COST.method);

    /**
     * 估算执行一个关系表达式的成本，包括它所有输入的成本。
     * 默认实现会将 {@link NonCumulativeCost#getNonCumulativeCost} 的值加到每个输入的累计成本上，
     * 但元数据提供者可以通过自己的成本模型覆盖此方法，例如考虑表达式之间的交互。
     *
     * @return 估算的成本，如果无法可靠估算则返回 null
     */
    RelOptCost getCumulativeCost();

    /** 处理器 API。 */
    @FunctionalInterface
    interface Handler extends MetadataHandler<CumulativeCost> {
      // 返回估算的累计成本
      RelOptCost getCumulativeCost(RelNode r, RelMetadataQuery mq);

      @Override default MetadataDef<CumulativeCost> getDef() {
        return DEF;
      }
    }
  }

  /**
   * 关系表达式的评估成本的元数据，不包括输入的成本。
   */
  public interface NonCumulativeCost extends Metadata {
    // 定义了 NonCumulativeCost 的元数据定义，包含类、处理器类型和方法。
    MetadataDef<NonCumulativeCost> DEF =
        MetadataDef.of(NonCumulativeCost.class, NonCumulativeCost.Handler.class,
            BuiltInMethod.NON_CUMULATIVE_COST.method);

    /**
     * 估算执行一个关系表达式的成本，不包括它输入的成本。
     * （然而，非累计成本通常还是依赖于输入的行数。）
     *
     * <p>默认实现会通过 {@link RelNode#computeSelfCost(RelOptPlanner, RelMetadataQuery)} 请求 rel 本身，
     * 但元数据提供者可以通过自己的成本模型覆盖此方法。
     *
     * @return 估算的成本，如果无法可靠估算则返回 null
     */
    RelOptCost getNonCumulativeCost();

    /** 处理器 API。 */
    @FunctionalInterface
    interface Handler extends MetadataHandler<NonCumulativeCost> {
      // 返回估算的非累计成本
      RelOptCost getNonCumulativeCost(RelNode r, RelMetadataQuery mq);

      @Override default MetadataDef<NonCumulativeCost> getDef() {
        return DEF;
      }
    }
  }

  /**
   * 关系表达式是否应出现在执行计划中的元数据。
   */
  public interface ExplainVisibility extends Metadata {
    // 定义了 ExplainVisibility 的元数据定义，包含类、处理器类型和方法。
    MetadataDef<ExplainVisibility> DEF =
        MetadataDef.of(ExplainVisibility.class, ExplainVisibility.Handler.class,
            BuiltInMethod.EXPLAIN_VISIBILITY.method);

    /**
     * 确定一个关系表达式是否应该在 EXPLAIN PLAN 输出中在特定的详细级别下可见。
     *
     * @param explainLevel 详细级别
     * @return 如果可见则返回 true，若不可见则返回 false
     */
    Boolean isVisibleInExplain(SqlExplainLevel explainLevel);

    /** 处理器 API。 */
    @FunctionalInterface
    interface Handler extends MetadataHandler<ExplainVisibility> {
      // 返回该表达式在给定详细级别下是否可见
      Boolean isVisibleInExplain(RelNode r, RelMetadataQuery mq,
          SqlExplainLevel explainLevel);

      @Override default MetadataDef<ExplainVisibility> getDef() {
        return DEF;
      }
    }
  }

  /**
   * 关系表达式发出的行中持有的谓词的元数据。
   */
  public interface Predicates extends Metadata {
    // 定义了 Predicates 的元数据定义，包含类、处理器类型和方法。
    MetadataDef<Predicates> DEF =
        MetadataDef.of(Predicates.class, Predicates.Handler.class,
            BuiltInMethod.PREDICATES.method);

    /**
     * 推导出在关系表达式发出的行上持有的谓词。
     *
     * @return 谓词列表
     */
    RelOptPredicateList getPredicates();

    /** 处理器 API。 */
    @FunctionalInterface
    interface Handler extends MetadataHandler<Predicates> {
      // 返回推导出的谓词列表
      RelOptPredicateList getPredicates(RelNode r, RelMetadataQuery mq);

      @Override default MetadataDef<Predicates> getDef() {
        return DEF;
      }
    }
  }

  /**
   * 关系表达式发出的行中持有的所有谓词的元数据。
   *
   * <p>与 {@link Predicates} 提供者的区别在于，此提供者尝试提取所有谓词，
   * 即使它们不是应用于关系表达式的输出表达式；我们依赖于 {@link RexTableInputRef} 来引用结果谓词中的源列。
   */
  public interface AllPredicates extends Metadata {
    // 定义了 AllPredicates 的元数据定义，包含类、处理器类型和方法。
    MetadataDef<AllPredicates> DEF =
        MetadataDef.of(AllPredicates.class, AllPredicates.Handler.class,
            BuiltInMethod.ALL_PREDICATES.method);

    /**
     * 推导出在关系表达式发出的行上持有的所有谓词。
     *
     * @return 谓词列表，如果提供者无法推导出任何谓词的来源链，则返回 null
     */
    @Nullable RelOptPredicateList getAllPredicates();

    /** 处理器 API。 */
    @FunctionalInterface
    interface Handler extends MetadataHandler<AllPredicates> {
      // 返回推导出的所有谓词列表
      @Nullable RelOptPredicateList getAllPredicates(RelNode r, RelMetadataQuery mq);

      @Override default MetadataDef<AllPredicates> getDef() {
        return DEF;
      }
    }
  }

  /**
   * 关系表达式的并行度和如何将其操作符分配到具有独立资源池的进程中的元数据。
   */
  public interface Parallelism extends Metadata {
    // 定义了 Parallelism 的元数据定义，包含类、处理器类型和方法。
    MetadataDef<Parallelism> DEF =
        MetadataDef.of(Parallelism.class, Parallelism.Handler.class,
            BuiltInMethod.IS_PHASE_TRANSITION.method,
            BuiltInMethod.SPLIT_COUNT.method);

    /**
     * 返回每个物理操作符是否属于与其输入不同的进程。
     *
     * <p>处理查询管道中所有拆分的操作符集合被称为一个“阶段”。一个阶段从一个叶子节点（如 {@link org.apache.calcite.rel.core.TableScan}）
     * 或者一个阶段变更节点（如 {@link org.apache.calcite.rel.core.Exchange}）开始。Hadoop 的 shuffle 操作符（即排序交换）会导致数据跨网络传输。
     */
    Boolean isPhaseTransition();

    /**
     * 返回数据的不同拆分数量。
     *
     * <p>注意拆分必须是不同的。对于广播，每个副本相同时返回 1。
     *
     * <p>因此，拆分数量是每个操作符实例所看到的数据的“比例”。
     */
    Integer splitCount();

    /** 处理器 API。 */
    interface Handler extends MetadataHandler<Parallelism> {
      // 返回是否属于阶段变更
      Boolean isPhaseTransition(RelNode r, RelMetadataQuery mq);
      // 返回拆分数量
      Integer splitCount(RelNode r, RelMetadataQuery mq);

      @Override default MetadataDef<Parallelism> getDef() {
        return DEF;
      }
    }
  }


  /**
   * 获取 RelNode 的下界成本的元数据。
   */
  public interface LowerBoundCost extends Metadata {
    // 定义了 LowerBoundCost 的元数据定义，包含类、处理器类型和方法。
    MetadataDef<LowerBoundCost> DEF =
        MetadataDef.of(LowerBoundCost.class, LowerBoundCost.Handler.class,
            BuiltInMethod.LOWER_BOUND_COST.method);

    /** 返回 RelNode 的下界成本。 */
    RelOptCost getLowerBoundCost(VolcanoPlanner planner);

    /** 处理器 API。 */
    @FunctionalInterface
    interface Handler extends MetadataHandler<LowerBoundCost> {
      // 返回 RelNode 的下界成本
      RelOptCost getLowerBoundCost(
          RelNode r, RelMetadataQuery mq, VolcanoPlanner planner);

      @Override default MetadataDef<LowerBoundCost> getDef() {
        return DEF;
      }
    }
  }

  /**
   * 关系表达式操作符的内存使用元数据。
   */
  public interface Memory extends Metadata {
    // 定义了 Memory 的元数据定义，包含类、处理器类型和方法。
    MetadataDef<Memory> DEF =
        MetadataDef.of(Memory.class, Memory.Handler.class,
            BuiltInMethod.MEMORY.method,
            BuiltInMethod.CUMULATIVE_MEMORY_WITHIN_PHASE.method,
            BuiltInMethod.CUMULATIVE_MEMORY_WITHIN_PHASE_SPLIT.method);

    /**
     * 返回实现该关系表达式的物理操作符所需的预期内存量（以字节为单位），跨所有拆分。
     * <p>内存使用量取决于算法；例如，某些实现将所有数据加载到哈希表中，内存需求为 {@code rowCount * averageRowSize} 字节，
     * 而假设输入已排序的实现则只需要 {@code averageRowSize} 字节来维持每个聚合函数的累加器。
     */
    @Nullable Double memory();

    /**
     * 返回实现该关系表达式的物理操作符及同一阶段内所有其他操作符所需的累计内存量（以字节为单位），跨所有拆分。
     *
     * @see Parallelism#splitCount()
     */
    @Nullable Double cumulativeMemoryWithinPhase();

    /**
     * 返回实现该关系表达式的物理操作符及同一阶段内所有操作符所需的预期累计内存量（以字节为单位），
     * 在每个拆分内。
     *
     * <p>基本公式：
     * <blockquote>cumulativeMemoryWithinPhaseSplit
     *     = cumulativeMemoryWithinPhase / Parallelism.splitCount</blockquote>
     */
    @Nullable Double cumulativeMemoryWithinPhaseSplit();

    /** 处理器 API。 */
    interface Handler extends MetadataHandler<Memory> {
      // 返回预期的内存量
      @Nullable Double memory(RelNode r, RelMetadataQuery mq);
      // 返回同一阶段内的累计内存量
      @Nullable Double cumulativeMemoryWithinPhase(RelNode r, RelMetadataQuery mq);
      // 返回同一阶段内每个拆分的累计内存量
      @Nullable Double cumulativeMemoryWithinPhaseSplit(RelNode r, RelMetadataQuery mq);

      @Override default MetadataDef<Memory> getDef() {
        return DEF;
      }
    }
  }

  /**
   * 关于列是否为度量以及在当前上下文中评估该度量的表达式的元数据。
   */
  public interface Measure extends Metadata {
    // 定义了 Measure 的元数据定义，包含类、处理器类型和方法。
    MetadataDef<Measure> DEF =
        MetadataDef.of(Measure.class, Measure.Handler.class,
            BuiltInMethod.MEASURE_EXPAND.method,
            BuiltInMethod.IS_MEASURE.method);

    /**
     * 返回给定列是否为度量。
     *
     * @param column 列的序号（从 0 开始）
     */
    Boolean isMeasure(int column);

    /**
     * 将度量扩展为表达式。
     *
     * @param column 列的序号（从 0 开始）
     * @param context 评估上下文
     */
    RexNode expand(int column, Context context);

    /** 处理器 API。 */
    interface Handler extends MetadataHandler<Measure> {
      // 返回给定列是否为度量
      Boolean isMeasure(RelNode r, RelMetadataQuery mq, int column);

      // 返回将度量扩展为表达式
      RexNode expand(RelNode r, RelMetadataQuery mq, int column,
          Context context);

      @Override default MetadataDef<Measure> getDef() {
        return DEF;
      }
    }

    /** 度量在调用站点使用时的上下文。 */
    interface Context {
      // 返回用于构建关系的 RelBuilder
      RelBuilder getRelBuilder();

      default RexBuilder getRexBuilder() {
        return getRelBuilder().getRexBuilder();
      }

      default RelDataTypeFactory getTypeFactory() {
        return getRelBuilder().getTypeFactory();
      }

      /** 返回过滤器的（合取）列表。
       *
       * <p>这些过滤器表示“过滤上下文”，并将成为子查询的 {@code WHERE} 子句。
       *
       * <p>如果定义度量的关系有 {@code N} 个维度，那么维度可以通过
       * {@link org.apache.calcite.rex.RexInputRef} 从 0 到 N-1 引用。
       */
      List<RexNode> getFilters(RelBuilder b);

      /** 返回维度列的数量。 */
      int getDimensionCount();
    }
  }

  /**
   * 内置的元数据形式。
   */
  interface All extends Selectivity, UniqueKeys, RowCount, DistinctRowCount,
      PercentageOriginalRows, ColumnUniqueness, ColumnOrigin, Predicates,
      Collation, Distribution, Size, Parallelism, Memory, AllPredicates,
      ExpressionLineage, TableReferences, NodeTypes {
  }

}
