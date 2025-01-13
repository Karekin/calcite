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
import org.apache.calcite.plan.RelOptPredicateList;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelDistribution;
import org.apache.calcite.rel.RelDistributions;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexTableInputRef.RelTableRef;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.util.ImmutableBitSet;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.apache.calcite.linq4j.Nullness.castNonNull;

/**
 * RelMetadataQuery 是一个强类型的门面接口（facade），基于 {@link RelMetadataProvider} 提供了一套标准的
 * 关系表达式元数据查询接口，这些接口在 Calcite 中被定义为标准查询。
 * 方法的 Javadoc 文档提供了它们的主要规范说明。
 *
 * <p>如果需要向此接口添加新的标准查询 <code>Xyz</code>，请按照以下步骤操作：
 *
 * <ol>
 * <li>在此类中添加一个静态方法 <code>getXyz</code> 的规范声明。
 *     这是新元数据查询的入口方法。
 * <li>在 {@code org.apache.calcite.test.RelMetadataTest} 中添加对应的单元测试。
 *     确保新功能具有充分的测试覆盖率。
 * <li>在当前包下编写一个新的提供者类 <code>RelMdXyz</code>。
 *     可参考已有的类，例如 {@link RelMdColumnOrigins}，为所有适用的逻辑关系表达式重载相关方法。
 * <li>为新类添加一个静态成员 {@code SOURCE}，类似于 {@link RelMdColumnOrigins#SOURCE}。
 *     这个成员用于提供元数据的查询逻辑实现。
 * <li>在 {@link DefaultRelMetadataProvider} 中注册该 {@code SOURCE} 对象。
 *     确保新元数据提供者能够被默认的元数据提供者使用。
 * <li>确保所有单元测试通过。
 * </ol>
 *
 * <p>由于关系表达式元数据具有可扩展性，扩展项目可以定义类似的门面接口，
 * 以便为自定义的元数据查询提供访问入口。请不要在此类（或 {@link RelNode} 类）中添加
 * 仅在您的扩展项目中有意义的查询。
 *
 * <p>除了添加新的元数据查询以外，扩展项目可能需要为标准查询添加自定义的元数据提供者，
 * 以支持额外的关系表达式（无论是逻辑表达式还是物理表达式）。
 * 无论是哪种情况，处理流程相同：编写一个反射型的元数据提供者类，并将其链接到
 * {@link DefaultRelMetadataProvider} 的一个实例上，将其优先于默认提供者使用。
 * 然后通过合适的插件机制将该实例提供给规划器（planner）。
 */

public class RelMetadataQuery extends RelMetadataQueryBase {
  // 一个空的原型，仅在首次使用时初始化。
  private static final Supplier<RelMetadataQuery> EMPTY =
      Suppliers.memoize(() -> new RelMetadataQuery(false));
// 通过 Suppliers.memoize 方法确保 RelMetadataQuery 的实例在首次调用时初始化，之后会返回同一个实例，
// 避免多次初始化带来的开销。

  // 以下是各种内置元数据查询处理器的声明，每个处理器负责具体类型的元数据查询逻辑。
  private BuiltInMetadata.Collation.Handler collationHandler;
// 用于处理关系表达式的排序信息（Collation）元数据查询的处理器。

  private BuiltInMetadata.ColumnOrigin.Handler columnOriginHandler;
// 用于处理列来源（Column Origin）元数据查询的处理器，
// 例如确定某个列源自哪个基础表及其字段。

  private BuiltInMetadata.ExpressionLineage.Handler expressionLineageHandler;
// 用于处理表达式血缘（Expression Lineage）元数据查询的处理器，
// 追踪表达式中的字段和计算关系。

  private BuiltInMetadata.TableReferences.Handler tableReferencesHandler;
// 用于处理表引用（Table References）元数据查询的处理器，
// 确定一个关系表达式中涉及到哪些基础表。

  private BuiltInMetadata.ColumnUniqueness.Handler columnUniquenessHandler;
// 用于处理列唯一性（Column Uniqueness）元数据查询的处理器，
// 判断特定列或列集是否能够唯一标识记录。

  private BuiltInMetadata.CumulativeCost.Handler cumulativeCostHandler;
// 用于处理累计成本（Cumulative Cost）元数据查询的处理器，
// 获取某个关系表达式的执行总成本。

  private BuiltInMetadata.DistinctRowCount.Handler distinctRowCountHandler;
// 用于处理不同行数（Distinct Row Count）元数据查询的处理器，
// 估算某个列或列集的去重行数。

  private BuiltInMetadata.Distribution.Handler distributionHandler;
// 用于处理分布（Distribution）元数据查询的处理器，
// 获取数据在计算节点之间的分布方式。

  private BuiltInMetadata.ExplainVisibility.Handler explainVisibilityHandler;
// 用于处理解释可见性（Explain Visibility）元数据查询的处理器，
// 确定某个关系表达式是否应该出现在 EXPLAIN 计划中。

  private BuiltInMetadata.MaxRowCount.Handler maxRowCountHandler;
// 用于处理最大行数（Max Row Count）元数据查询的处理器，
// 估算某个关系表达式的最大可能行数。

  private BuiltInMetadata.MinRowCount.Handler minRowCountHandler;
// 用于处理最小行数（Min Row Count）元数据查询的处理器，
// 估算某个关系表达式的最小可能行数。

  private BuiltInMetadata.Memory.Handler memoryHandler;
// 用于处理内存使用（Memory）元数据查询的处理器，
// 估算某个关系表达式的内存需求。

  private BuiltInMetadata.Measure.Handler measureHandler;
// 用于处理度量（Measure）元数据查询的处理器，
// 针对特定度量标准评估关系表达式。

  private BuiltInMetadata.NonCumulativeCost.Handler nonCumulativeCostHandler;
// 用于处理非累计成本（Non-Cumulative Cost）元数据查询的处理器，
// 获取某个关系表达式的直接执行成本，不包括其子表达式的成本。

  private BuiltInMetadata.Parallelism.Handler parallelismHandler;
// 用于处理并行度（Parallelism）元数据查询的处理器，
// 获取关系表达式的并行度信息。

  private BuiltInMetadata.PercentageOriginalRows.Handler percentageOriginalRowsHandler;
// 用于处理原始行比例（Percentage Original Rows）元数据查询的处理器，
// 估算某个关系表达式中保留的原始行的比例。

  private BuiltInMetadata.PopulationSize.Handler populationSizeHandler;
// 用于处理数据集大小（Population Size）元数据查询的处理器，
// 估算关系表达式的总数据集大小。

  private BuiltInMetadata.Predicates.Handler predicatesHandler;
// 用于处理谓词（Predicates）元数据查询的处理器，
// 提取关系表达式中应用的过滤条件。

  private BuiltInMetadata.AllPredicates.Handler allPredicatesHandler;
// 用于处理所有谓词（All Predicates）元数据查询的处理器，
// 获取关系表达式中所有可能的过滤条件。

  private BuiltInMetadata.NodeTypes.Handler nodeTypesHandler;
// 用于处理节点类型（Node Types）元数据查询的处理器，
// 获取关系表达式树中各个节点的类型分布。

  private BuiltInMetadata.RowCount.Handler rowCountHandler;
// 用于处理行数（Row Count）元数据查询的处理器，
// 估算某个关系表达式的输出行数。

  private BuiltInMetadata.Selectivity.Handler selectivityHandler;
// 用于处理选择性（Selectivity）元数据查询的处理器，
// 计算谓词的选择性，即过滤掉的行比例。

  private BuiltInMetadata.Size.Handler sizeHandler;
// 用于处理大小（Size）元数据查询的处理器，
// 估算关系表达式的输出数据大小。

  private BuiltInMetadata.UniqueKeys.Handler uniqueKeysHandler;
// 用于处理唯一键（Unique Keys）元数据查询的处理器，
// 获取关系表达式中可能的唯一键集合。

  private BuiltInMetadata.LowerBoundCost.Handler lowerBoundCostHandler;
// 用于处理下界成本（Lower Bound Cost）元数据查询的处理器，
// 估算执行关系表达式所需的最低成本。


  /**
   * Creates the instance with {@link JaninoRelMetadataProvider} instance
   * from {@link #THREAD_PROVIDERS} and {@link #EMPTY} as a prototype.
   */
  protected RelMetadataQuery() {
    this(castNonNull(THREAD_PROVIDERS.get()), EMPTY.get());
  }

  /**
   * Create a RelMetadataQuery with a given {@link MetadataHandlerProvider}.
   *
   * @param provider The provider to use for construction.
   */
  public RelMetadataQuery(MetadataHandlerProvider provider) {
    super(provider);
    this.collationHandler = provider.handler(BuiltInMetadata.Collation.Handler.class);
    this.columnOriginHandler = provider.handler(BuiltInMetadata.ColumnOrigin.Handler.class);
    this.expressionLineageHandler =
        provider.handler(BuiltInMetadata.ExpressionLineage.Handler.class);
    this.tableReferencesHandler = provider.handler(BuiltInMetadata.TableReferences.Handler.class);
    this.columnUniquenessHandler = provider.handler(BuiltInMetadata.ColumnUniqueness.Handler.class);
    this.cumulativeCostHandler = provider.handler(BuiltInMetadata.CumulativeCost.Handler.class);
    this.distinctRowCountHandler = provider.handler(BuiltInMetadata.DistinctRowCount.Handler.class);
    this.distributionHandler = provider.handler(BuiltInMetadata.Distribution.Handler.class);
    this.explainVisibilityHandler =
        provider.handler(BuiltInMetadata.ExplainVisibility.Handler.class);
    this.maxRowCountHandler = provider.handler(BuiltInMetadata.MaxRowCount.Handler.class);
    this.minRowCountHandler = provider.handler(BuiltInMetadata.MinRowCount.Handler.class);
    this.memoryHandler = provider.handler(BuiltInMetadata.Memory.Handler.class);
    this.measureHandler =
        provider.handler(BuiltInMetadata.Measure.Handler.class);
    this.nonCumulativeCostHandler =
        provider.handler(BuiltInMetadata.NonCumulativeCost.Handler.class);
    this.parallelismHandler = provider.handler(BuiltInMetadata.Parallelism.Handler.class);
    this.percentageOriginalRowsHandler =
        provider.handler(BuiltInMetadata.PercentageOriginalRows.Handler.class);
    this.populationSizeHandler = provider.handler(BuiltInMetadata.PopulationSize.Handler.class);
    this.predicatesHandler = provider.handler(BuiltInMetadata.Predicates.Handler.class);
    this.allPredicatesHandler = provider.handler(BuiltInMetadata.AllPredicates.Handler.class);
    this.nodeTypesHandler = provider.handler(BuiltInMetadata.NodeTypes.Handler.class);
    this.rowCountHandler = provider.handler(BuiltInMetadata.RowCount.Handler.class);
    this.selectivityHandler = provider.handler(BuiltInMetadata.Selectivity.Handler.class);
    this.sizeHandler = provider.handler(BuiltInMetadata.Size.Handler.class);
    this.uniqueKeysHandler = provider.handler(BuiltInMetadata.UniqueKeys.Handler.class);
    this.lowerBoundCostHandler = provider.handler(BuiltInMetadata.LowerBoundCost.Handler.class);
  }

  /** Creates and initializes the instance that will serve as a prototype for
   * all other instances in the Janino case. */
  @SuppressWarnings("deprecation")
  private RelMetadataQuery(@SuppressWarnings("unused") boolean dummy) {
    super(null);
    this.collationHandler = initialHandler(BuiltInMetadata.Collation.Handler.class);
    this.columnOriginHandler = initialHandler(BuiltInMetadata.ColumnOrigin.Handler.class);
    this.expressionLineageHandler = initialHandler(BuiltInMetadata.ExpressionLineage.Handler.class);
    this.tableReferencesHandler = initialHandler(BuiltInMetadata.TableReferences.Handler.class);
    this.columnUniquenessHandler = initialHandler(BuiltInMetadata.ColumnUniqueness.Handler.class);
    this.cumulativeCostHandler = initialHandler(BuiltInMetadata.CumulativeCost.Handler.class);
    this.distinctRowCountHandler = initialHandler(BuiltInMetadata.DistinctRowCount.Handler.class);
    this.distributionHandler = initialHandler(BuiltInMetadata.Distribution.Handler.class);
    this.explainVisibilityHandler = initialHandler(BuiltInMetadata.ExplainVisibility.Handler.class);
    this.maxRowCountHandler = initialHandler(BuiltInMetadata.MaxRowCount.Handler.class);
    this.minRowCountHandler = initialHandler(BuiltInMetadata.MinRowCount.Handler.class);
    this.memoryHandler = initialHandler(BuiltInMetadata.Memory.Handler.class);
    this.measureHandler = initialHandler(BuiltInMetadata.Measure.Handler.class);
    this.nonCumulativeCostHandler = initialHandler(BuiltInMetadata.NonCumulativeCost.Handler.class);
    this.parallelismHandler = initialHandler(BuiltInMetadata.Parallelism.Handler.class);
    this.percentageOriginalRowsHandler =
        initialHandler(BuiltInMetadata.PercentageOriginalRows.Handler.class);
    this.populationSizeHandler = initialHandler(BuiltInMetadata.PopulationSize.Handler.class);
    this.predicatesHandler = initialHandler(BuiltInMetadata.Predicates.Handler.class);
    this.allPredicatesHandler = initialHandler(BuiltInMetadata.AllPredicates.Handler.class);
    this.nodeTypesHandler = initialHandler(BuiltInMetadata.NodeTypes.Handler.class);
    this.rowCountHandler = initialHandler(BuiltInMetadata.RowCount.Handler.class);
    this.selectivityHandler = initialHandler(BuiltInMetadata.Selectivity.Handler.class);
    this.sizeHandler = initialHandler(BuiltInMetadata.Size.Handler.class);
    this.uniqueKeysHandler = initialHandler(BuiltInMetadata.UniqueKeys.Handler.class);
    this.lowerBoundCostHandler = initialHandler(BuiltInMetadata.LowerBoundCost.Handler.class);
  }

  private RelMetadataQuery(
      MetadataHandlerProvider metadataHandlerProvider,
      RelMetadataQuery prototype) {
    super(metadataHandlerProvider);
    this.collationHandler = prototype.collationHandler;
    this.columnOriginHandler = prototype.columnOriginHandler;
    this.expressionLineageHandler = prototype.expressionLineageHandler;
    this.tableReferencesHandler = prototype.tableReferencesHandler;
    this.columnUniquenessHandler = prototype.columnUniquenessHandler;
    this.cumulativeCostHandler = prototype.cumulativeCostHandler;
    this.distinctRowCountHandler = prototype.distinctRowCountHandler;
    this.distributionHandler = prototype.distributionHandler;
    this.explainVisibilityHandler = prototype.explainVisibilityHandler;
    this.maxRowCountHandler = prototype.maxRowCountHandler;
    this.minRowCountHandler = prototype.minRowCountHandler;
    this.memoryHandler = prototype.memoryHandler;
    this.measureHandler = prototype.measureHandler;
    this.nonCumulativeCostHandler = prototype.nonCumulativeCostHandler;
    this.parallelismHandler = prototype.parallelismHandler;
    this.percentageOriginalRowsHandler = prototype.percentageOriginalRowsHandler;
    this.populationSizeHandler = prototype.populationSizeHandler;
    this.predicatesHandler = prototype.predicatesHandler;
    this.allPredicatesHandler = prototype.allPredicatesHandler;
    this.nodeTypesHandler = prototype.nodeTypesHandler;
    this.rowCountHandler = prototype.rowCountHandler;
    this.selectivityHandler = prototype.selectivityHandler;
    this.sizeHandler = prototype.sizeHandler;
    this.uniqueKeysHandler = prototype.uniqueKeysHandler;
    this.lowerBoundCostHandler = prototype.lowerBoundCostHandler;
  }

  //~ Methods ----------------------------------------------------------------

  /**
   * Returns an instance of RelMetadataQuery. It ensures that cycles do not
   * occur while computing metadata.
   */
  public static RelMetadataQuery instance() {
    return new RelMetadataQuery();
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.NodeTypes#getNodeTypes()}
   * statistic.
   *
   * @param rel the relational expression
   */
  public @Nullable Multimap<Class<? extends RelNode>, RelNode> getNodeTypes(RelNode rel) {
    for (;;) {
      try {
        return nodeTypesHandler.getNodeTypes(rel, this);
      } catch (MetadataHandlerProvider.NoHandler e) {
        nodeTypesHandler = revise(BuiltInMetadata.NodeTypes.Handler.class);
      } catch (CyclicMetadataException e) {
        return null;
      }
    }
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.RowCount#getRowCount()}
   * statistic.
   *
   * @param rel the relational expression
   * @return estimated row count, or null if no reliable estimate can be
   * determined
   */
  public /* @Nullable: CALCITE-4263 */ Double getRowCount(RelNode rel) {
    for (;;) {
      try {
        Double result = rowCountHandler.getRowCount(rel, this);
        return RelMdUtil.validateResult(castNonNull(result));
      } catch (MetadataHandlerProvider.NoHandler e) {
        rowCountHandler = revise(BuiltInMetadata.RowCount.Handler.class);
      }
    }
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.MaxRowCount#getMaxRowCount()}
   * statistic.
   *
   * @param rel the relational expression
   * @return max row count
   */
  public @Nullable Double getMaxRowCount(RelNode rel) {
    for (;;) {
      try {
        return maxRowCountHandler.getMaxRowCount(rel, this);
      } catch (MetadataHandlerProvider.NoHandler e) {
        maxRowCountHandler = revise(BuiltInMetadata.MaxRowCount.Handler.class);
      }
    }
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.MinRowCount#getMinRowCount()}
   * statistic.
   *
   * @param rel the relational expression
   * @return min row count
   */
  public @Nullable Double getMinRowCount(RelNode rel) {
    for (;;) {
      try {
        return minRowCountHandler.getMinRowCount(rel, this);
      } catch (MetadataHandlerProvider.NoHandler e) {
        minRowCountHandler = revise(BuiltInMetadata.MinRowCount.Handler.class);
      }
    }
  }

  /**
   * Returns whether the return rows of a given relational expression are empty.
   *
   * @param relNode the relational expression
   * @return true or false depending on whether the return rows are empty, or
   * null if not enough information is available to make that determination
   */
  public @Nullable Boolean isEmpty(RelNode relNode) {
    Double minRowCount = getMinRowCount(relNode);
    if (minRowCount != null && minRowCount > 0D) {
      return Boolean.FALSE;
    }
    Double maxRowCount = getMaxRowCount(relNode);
    if (maxRowCount != null && maxRowCount <= 0D) {
      return Boolean.TRUE;
    }
    return null;
  }


  /**
   * Returns the
   * {@link BuiltInMetadata.CumulativeCost#getCumulativeCost()}
   * statistic.
   *
   * @param rel the relational expression
   * @return estimated cost, or null if no reliable estimate can be determined
   */
  public @Nullable RelOptCost getCumulativeCost(RelNode rel) {
    for (;;) {
      try {
        return cumulativeCostHandler.getCumulativeCost(rel, this);
      } catch (MetadataHandlerProvider.NoHandler e) {
        cumulativeCostHandler = revise(BuiltInMetadata.CumulativeCost.Handler.class);
      }
    }
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.NonCumulativeCost#getNonCumulativeCost()}
   * statistic.
   *
   * @param rel the relational expression
   * @return estimated cost, or null if no reliable estimate can be determined
   */
  public @Nullable RelOptCost getNonCumulativeCost(RelNode rel) {
    for (;;) {
      try {
        return nonCumulativeCostHandler.getNonCumulativeCost(rel, this);
      } catch (MetadataHandlerProvider.NoHandler e) {
        nonCumulativeCostHandler = revise(BuiltInMetadata.NonCumulativeCost.Handler.class);
      }
    }
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.PercentageOriginalRows#getPercentageOriginalRows()}
   * statistic.
   *
   * @param rel the relational expression
   * @return estimated percentage (between 0.0 and 1.0), or null if no
   * reliable estimate can be determined
   */
  public @Nullable Double getPercentageOriginalRows(RelNode rel) {
    for (;;) {
      try {
        Double result =
            percentageOriginalRowsHandler.getPercentageOriginalRows(rel, this);
        return RelMdUtil.validatePercentage(result);
      } catch (MetadataHandlerProvider.NoHandler e) {
        percentageOriginalRowsHandler =
            revise(BuiltInMetadata.PercentageOriginalRows.Handler.class);
      }
    }
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.ColumnOrigin#getColumnOrigins(int)}
   * statistic.
   *
   * @param rel           the relational expression
   * @param column 0-based ordinal for output column of interest
   * @return set of origin columns, or null if this information cannot be
   * determined (whereas empty set indicates definitely no origin columns at
   * all)
   */
  public @Nullable Set<RelColumnOrigin> getColumnOrigins(RelNode rel, int column) {
    for (;;) {
      try {
        return columnOriginHandler.getColumnOrigins(rel, this, column);
      } catch (MetadataHandlerProvider.NoHandler e) {
        columnOriginHandler = revise(BuiltInMetadata.ColumnOrigin.Handler.class);
      }
    }
  }

  /**
   * Determines the origin of a column.
   *
   * @see #getColumnOrigins(org.apache.calcite.rel.RelNode, int)
   *
   * @param rel the RelNode of the column
   * @param column the offset of the column whose origin we are trying to
   * determine
   *
   * @return the origin of a column
   */
  public @Nullable RelColumnOrigin getColumnOrigin(RelNode rel, int column) {
    final Set<RelColumnOrigin> origins = getColumnOrigins(rel, column);
    if (origins == null || origins.size() != 1) {
      return null;
    }
    final RelColumnOrigin origin = Iterables.getOnlyElement(origins);
    return origin;
  }

  /**
   * Determines the origin of a column.
   */
  public @Nullable Set<RexNode> getExpressionLineage(RelNode rel, RexNode expression) {
    for (;;) {
      try {
        return expressionLineageHandler.getExpressionLineage(rel, this, expression);
      } catch (MetadataHandlerProvider.NoHandler e) {
        expressionLineageHandler = revise(BuiltInMetadata.ExpressionLineage.Handler.class);
      }
    }
  }

  /**
   * Determines the tables used by a plan.
   */
  public @Nullable Set<RelTableRef> getTableReferences(RelNode rel) {
    for (;;) {
      try {
        return tableReferencesHandler.getTableReferences(rel, this);
      } catch (MetadataHandlerProvider.NoHandler e) {
        tableReferencesHandler = revise(BuiltInMetadata.TableReferences.Handler.class);
      }
    }
  }

  /**
   * Determines the origin of a {@link RelNode}, provided it maps to a single
   * table, optionally with filtering and projection.
   *
   * @param rel the RelNode
   *
   * @return the table, if the RelNode is a simple table; otherwise null
   */
  public @Nullable RelOptTable getTableOrigin(RelNode rel) {
    // Determine the simple origin of the first column in the
    // RelNode.  If it's simple, then that means that the underlying
    // table is also simple, even if the column itself is derived.
    if (rel.getRowType().getFieldCount() == 0) {
      return null;
    }
    final Set<RelColumnOrigin> colOrigins = getColumnOrigins(rel, 0);
    if (colOrigins == null || colOrigins.isEmpty()) {
      return null;
    }
    return colOrigins.iterator().next().getOriginTable();
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.Selectivity#getSelectivity(RexNode)}
   * statistic.
   *
   * @param rel       the relational expression
   * @param predicate predicate whose selectivity is to be estimated against
   *                  {@code rel}'s output
   * @return estimated selectivity (between 0.0 and 1.0), or null if no
   * reliable estimate can be determined
   */
  public @Nullable Double getSelectivity(RelNode rel, @Nullable RexNode predicate) {
    for (;;) {
      try {
        Double result = selectivityHandler.getSelectivity(rel, this, predicate);
        return RelMdUtil.validatePercentage(result);
      } catch (MetadataHandlerProvider.NoHandler e) {
        selectivityHandler = revise(BuiltInMetadata.Selectivity.Handler.class);
      }
    }
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.UniqueKeys#getUniqueKeys(boolean)}
   * statistic.
   *
   * @param rel the relational expression
   * @return set of keys, or null if this information cannot be determined
   * (whereas empty set indicates definitely no keys at all)
   */
  public @Nullable Set<ImmutableBitSet> getUniqueKeys(RelNode rel) {
    return getUniqueKeys(rel, false);
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.UniqueKeys#getUniqueKeys(boolean)}
   * statistic.
   *
   * @param rel         the relational expression
   * @param ignoreNulls if true, ignore null values when determining
   *                    whether the keys are unique
   *
   * @return set of keys, or null if this information cannot be determined
   * (whereas empty set indicates definitely no keys at all)
   */
  public @Nullable Set<ImmutableBitSet> getUniqueKeys(RelNode rel,
      boolean ignoreNulls) {
    for (;;) {
      try {
        return uniqueKeysHandler.getUniqueKeys(rel, this, ignoreNulls);
      } catch (MetadataHandlerProvider.NoHandler e) {
        uniqueKeysHandler = revise(BuiltInMetadata.UniqueKeys.Handler.class);
      }
    }
  }

  /**
   * Returns whether the rows of a given relational expression are distinct,
   * optionally ignoring NULL values.
   *
   * <p>This is derived by applying the
   * {@link BuiltInMetadata.ColumnUniqueness#areColumnsUnique(org.apache.calcite.util.ImmutableBitSet, boolean)}
   * statistic over all columns. If
   * {@link BuiltInMetadata.MaxRowCount#getMaxRowCount()}
   * is less than or equal to one, we shortcut the process and declare the rows
   * unique.
   *
   * @param rel     the relational expression
   * @param ignoreNulls if true, ignore null values when determining column
   *                    uniqueness
   *
   * @return whether the rows are unique, or
   * null if not enough information is available to make that determination
   */
  public @Nullable Boolean areRowsUnique(RelNode rel, boolean ignoreNulls) {
    Double maxRowCount = this.getMaxRowCount(rel);
    if (maxRowCount != null && maxRowCount <= 1D) {
      return true;
    }
    final ImmutableBitSet columns =
        ImmutableBitSet.range(rel.getRowType().getFieldCount());
    return areColumnsUnique(rel, columns, ignoreNulls);
  }

  /**
   * Returns whether the rows of a given relational expression are distinct.
   *
   * <p>Derived by calling {@link #areRowsUnique(RelNode, boolean)}.
   *
   * @param rel     the relational expression
   *
   * @return whether the rows are unique, or
   * null if not enough information is available to make that determination
   */
  public @Nullable Boolean areRowsUnique(RelNode rel) {
    return areRowsUnique(rel, false);
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.ColumnUniqueness#areColumnsUnique(ImmutableBitSet, boolean)}
   * statistic.
   *
   * @param rel     the relational expression
   * @param columns column mask representing the subset of columns for which
   *                uniqueness will be determined
   *
   * @return true or false depending on whether the columns are unique, or
   * null if not enough information is available to make that determination
   */
  public @Nullable Boolean areColumnsUnique(RelNode rel, ImmutableBitSet columns) {
    return areColumnsUnique(rel, columns, false);
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.ColumnUniqueness#areColumnsUnique(ImmutableBitSet, boolean)}
   * statistic.
   *
   * @param rel         the relational expression
   * @param columns     column mask representing the subset of columns for which
   *                    uniqueness will be determined
   * @param ignoreNulls if true, ignore null values when determining column
   *                    uniqueness
   * @return true or false depending on whether the columns are unique, or
   * null if not enough information is available to make that determination
   */
  public @Nullable Boolean areColumnsUnique(RelNode rel, ImmutableBitSet columns,
      boolean ignoreNulls) {
    for (;;) {
      try {
        return columnUniquenessHandler.areColumnsUnique(rel, this, columns,
            ignoreNulls);
      } catch (MetadataHandlerProvider.NoHandler e) {
        columnUniquenessHandler = revise(BuiltInMetadata.ColumnUniqueness.Handler.class);
      }
    }
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.Collation#collations()}
   * statistic.
   *
   * @param rel         the relational expression
   * @return List of sorted column combinations, or
   * null if not enough information is available to make that determination
   */
  public @Nullable ImmutableList<RelCollation> collations(RelNode rel) {
    for (;;) {
      try {
        return collationHandler.collations(rel, this);
      } catch (MetadataHandlerProvider.NoHandler e) {
        collationHandler = revise(BuiltInMetadata.Collation.Handler.class);
      }
    }
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.Distribution#distribution()}
   * statistic.
   *
   * @param rel         the relational expression
   * @return List of sorted column combinations, or
   * null if not enough information is available to make that determination
   */
  public RelDistribution distribution(RelNode rel) {
    for (;;) {
      try {
        RelDistribution distribution = distributionHandler.distribution(rel, this);
        //noinspection ConstantConditions
        if (distribution == null) {
          return RelDistributions.ANY;
        }
        return distribution;
      } catch (MetadataHandlerProvider.NoHandler e) {
        distributionHandler = revise(BuiltInMetadata.Distribution.Handler.class);
      }
    }
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.PopulationSize#getPopulationSize(ImmutableBitSet)}
   * statistic.
   *
   * @param rel      the relational expression
   * @param groupKey column mask representing the subset of columns for which
   *                 the row count will be determined
   * @return distinct row count for the given groupKey, or null if no reliable
   * estimate can be determined
   *
   */
  public @Nullable Double getPopulationSize(RelNode rel,
      ImmutableBitSet groupKey) {
    for (;;) {
      try {
        Double result =
            populationSizeHandler.getPopulationSize(rel, this, groupKey);
        return RelMdUtil.validateResult(result);
      } catch (MetadataHandlerProvider.NoHandler e) {
        populationSizeHandler = revise(BuiltInMetadata.PopulationSize.Handler.class);
      }
    }
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.Size#averageRowSize()}
   * statistic.
   *
   * @param rel      the relational expression
   * @return average size of a row, in bytes, or null if not known
     */
  public @Nullable Double getAverageRowSize(RelNode rel) {
    for (;;) {
      try {
        return sizeHandler.averageRowSize(rel, this);
      } catch (MetadataHandlerProvider.NoHandler e) {
        sizeHandler = revise(BuiltInMetadata.Size.Handler.class);
      }
    }
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.Size#averageColumnSizes()}
   * statistic.
   *
   * @param rel      the relational expression
   * @return a list containing, for each column, the average size of a column
   * value, in bytes. Each value or the entire list may be null if the
   * metadata is not available
   */
  public @Nullable List<@Nullable Double> getAverageColumnSizes(RelNode rel) {
    for (;;) {
      try {
        return sizeHandler.averageColumnSizes(rel, this);
      } catch (MetadataHandlerProvider.NoHandler e) {
        sizeHandler = revise(BuiltInMetadata.Size.Handler.class);
      }
    }
  }

  /** As {@link #getAverageColumnSizes(org.apache.calcite.rel.RelNode)} but
   * never returns a null list, only ever a list of nulls. */
  public List<@Nullable Double> getAverageColumnSizesNotNull(RelNode rel) {
    final @Nullable List<@Nullable Double> averageColumnSizes = getAverageColumnSizes(rel);
    return averageColumnSizes == null
        ? Collections.nCopies(rel.getRowType().getFieldCount(), null)
        : averageColumnSizes;
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.Parallelism#isPhaseTransition()}
   * statistic.
   *
   * @param rel      the relational expression
   * @return whether each physical operator implementing this relational
   * expression belongs to a different process than its inputs, or null if not
   * known
   */
  public @Nullable Boolean isPhaseTransition(RelNode rel) {
    for (;;) {
      try {
        return parallelismHandler.isPhaseTransition(rel, this);
      } catch (MetadataHandlerProvider.NoHandler e) {
        parallelismHandler = revise(BuiltInMetadata.Parallelism.Handler.class);
      }
    }
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.Parallelism#splitCount()}
   * statistic.
   *
   * @param rel      the relational expression
   * @return the number of distinct splits of the data, or null if not known
   */
  public @Nullable Integer splitCount(RelNode rel) {
    for (;;) {
      try {
        return parallelismHandler.splitCount(rel, this);
      } catch (MetadataHandlerProvider.NoHandler e) {
        parallelismHandler = revise(BuiltInMetadata.Parallelism.Handler.class);
      }
    }
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.Memory#memory()}
   * statistic.
   *
   * @param rel      the relational expression
   * @return the expected amount of memory, in bytes, required by a physical
   * operator implementing this relational expression, across all splits,
   * or null if not known
   */
  public @Nullable Double memory(RelNode rel) {
    for (;;) {
      try {
        return memoryHandler.memory(rel, this);
      } catch (MetadataHandlerProvider.NoHandler e) {
        memoryHandler = revise(BuiltInMetadata.Memory.Handler.class);
      }
    }
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.Memory#cumulativeMemoryWithinPhase()}
   * statistic.
   *
   * @param rel      the relational expression
   * @return the cumulative amount of memory, in bytes, required by the
   * physical operator implementing this relational expression, and all other
   * operators within the same phase, across all splits, or null if not known
   */
  public @Nullable Double cumulativeMemoryWithinPhase(RelNode rel) {
    for (;;) {
      try {
        return memoryHandler.cumulativeMemoryWithinPhase(rel, this);
      } catch (MetadataHandlerProvider.NoHandler e) {
        memoryHandler = revise(BuiltInMetadata.Memory.Handler.class);
      }
    }
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.Memory#cumulativeMemoryWithinPhaseSplit()}
   * statistic.
   *
   * @param rel      the relational expression
   * @return the expected cumulative amount of memory, in bytes, required by
   * the physical operator implementing this relational expression, and all
   * operators within the same phase, within each split, or null if not known
   */
  public @Nullable Double cumulativeMemoryWithinPhaseSplit(RelNode rel) {
    for (;;) {
      try {
        return memoryHandler.cumulativeMemoryWithinPhaseSplit(rel, this);
      } catch (MetadataHandlerProvider.NoHandler e) {
        memoryHandler = revise(BuiltInMetadata.Memory.Handler.class);
      }
    }
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.Measure#isMeasure(int)}
   * statistic.
   *
   * @param rel      The relational expression
   * @param column   Output column of the relational expression
   * @return whether column is a measure
   */
  public @Nullable Boolean isMeasure(RelNode rel, int column) {
    for (;;) {
      try {
        return measureHandler.isMeasure(rel, this, column);
      } catch (MetadataHandlerProvider.NoHandler e) {
        measureHandler = revise(BuiltInMetadata.Measure.Handler.class);
      }
    }
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.Measure#expand(int, BuiltInMetadata.Measure.Context)}
   * statistic.
   *
   * @param rel      The relational expression
   * @param column   Output column of the relational expression
   * @param context  Context of the use of the measure
   * @return expression for measure in the context
   */
  public RexNode expand(RelNode rel, int column,
      BuiltInMetadata.Measure.Context context) {
    for (;;) {
      try {
        return measureHandler.expand(rel, this, column, context);
      } catch (MetadataHandlerProvider.NoHandler e) {
        measureHandler = revise(BuiltInMetadata.Measure.Handler.class);
      }
    }
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.DistinctRowCount#getDistinctRowCount(ImmutableBitSet, RexNode)}
   * statistic.
   *
   * @param rel       the relational expression
   * @param groupKey  column mask representing group by columns
   * @param predicate pre-filtered predicates
   * @return distinct row count for groupKey, filtered by predicate, or null
   * if no reliable estimate can be determined
   */
  public @Nullable Double getDistinctRowCount(
      RelNode rel,
      ImmutableBitSet groupKey,
      @Nullable RexNode predicate) {
    for (;;) {
      try {
        Double result =
            distinctRowCountHandler.getDistinctRowCount(rel, this, groupKey,
                predicate);
        return RelMdUtil.validateResult(result);
      } catch (MetadataHandlerProvider.NoHandler e) {
        distinctRowCountHandler = revise(BuiltInMetadata.DistinctRowCount.Handler.class);
      }
    }
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.Predicates#getPredicates()}
   * statistic.
   *
   * @param rel the relational expression
   * @return Predicates that can be pulled above this RelNode
   */
  public RelOptPredicateList getPulledUpPredicates(RelNode rel) {
    for (;;) {
      try {
        RelOptPredicateList result = predicatesHandler.getPredicates(rel, this);
        return result != null ? result : RelOptPredicateList.EMPTY;
      } catch (MetadataHandlerProvider.NoHandler e) {
        predicatesHandler = revise(BuiltInMetadata.Predicates.Handler.class);
      }
    }
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.AllPredicates#getAllPredicates()}
   * statistic.
   *
   * @param rel the relational expression
   * @return All predicates within and below this RelNode
   */
  public @Nullable RelOptPredicateList getAllPredicates(RelNode rel) {
    for (;;) {
      try {
        return allPredicatesHandler.getAllPredicates(rel, this);
      } catch (MetadataHandlerProvider.NoHandler e) {
        allPredicatesHandler = revise(BuiltInMetadata.AllPredicates.Handler.class);
      }
    }
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.ExplainVisibility#isVisibleInExplain(SqlExplainLevel)}
   * statistic.
   *
   * @param rel          the relational expression
   * @param explainLevel level of detail
   * @return true for visible, false for invisible; if no metadata is available,
   * defaults to true
   */
  public Boolean isVisibleInExplain(RelNode rel,
      SqlExplainLevel explainLevel) {
    for (;;) {
      try {
        Boolean b =
            explainVisibilityHandler.isVisibleInExplain(rel, this, explainLevel);
        return b == null || b;
      } catch (MetadataHandlerProvider.NoHandler e) {
        explainVisibilityHandler = revise(BuiltInMetadata.ExplainVisibility.Handler.class);
      }
    }
  }

  /**
   * Returns the
   * {@link BuiltInMetadata.Distribution#distribution()}
   * statistic.
   *
   * @param rel the relational expression
   *
   * @return description of how the rows in the relational expression are
   * physically distributed
   */
  public @Nullable RelDistribution getDistribution(RelNode rel) {
    for (;;) {
      try {
        return distributionHandler.distribution(rel, this);
      } catch (MetadataHandlerProvider.NoHandler e) {
        distributionHandler = revise(BuiltInMetadata.Distribution.Handler.class);
      }
    }
  }

  /**
   * Returns the lower bound cost of a RelNode.
   */
  public @Nullable RelOptCost getLowerBoundCost(RelNode rel, VolcanoPlanner planner) {
    for (;;) {
      try {
        return lowerBoundCostHandler.getLowerBoundCost(rel, this, planner);
      } catch (MetadataHandlerProvider.NoHandler e) {
        lowerBoundCostHandler = revise(BuiltInMetadata.LowerBoundCost.Handler.class);
      }
    }
  }
}
