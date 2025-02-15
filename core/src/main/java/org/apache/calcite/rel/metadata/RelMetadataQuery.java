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
   * 通过 {@link JaninoRelMetadataProvider} 实例创建 `RelMetadataQuery`。
   *
   * <p>该构造方法使用 {@link #THREAD_PROVIDERS} 线程局部变量获取 `JaninoRelMetadataProvider`，
   * 并使用 {@link #EMPTY} 作为原型，以确保元数据查询的正确初始化。
   */
  protected RelMetadataQuery() {
    this(castNonNull(THREAD_PROVIDERS.get()), EMPTY.get());
  }

  /**
   * 使用指定的 {@link MetadataHandlerProvider} 创建 `RelMetadataQuery`。
   *
   * <p>在 `MetadataHandlerProvider` 之上构造 `RelMetadataQuery`，并初始化所有的内置元数据处理器。
   * 这些处理器用于执行不同类型的元数据查询。
   *
   * @param provider 用于元数据查询的处理器提供者
   */
  public RelMetadataQuery(MetadataHandlerProvider provider) {
    super(provider);

    // 初始化各种内置元数据查询处理器
    this.collationHandler = provider.handler(BuiltInMetadata.Collation.Handler.class);
    this.columnOriginHandler = provider.handler(BuiltInMetadata.ColumnOrigin.Handler.class);
    this.expressionLineageHandler = provider.handler(BuiltInMetadata.ExpressionLineage.Handler.class);
    this.tableReferencesHandler = provider.handler(BuiltInMetadata.TableReferences.Handler.class);
    this.columnUniquenessHandler = provider.handler(BuiltInMetadata.ColumnUniqueness.Handler.class);
    this.cumulativeCostHandler = provider.handler(BuiltInMetadata.CumulativeCost.Handler.class);
    this.distinctRowCountHandler = provider.handler(BuiltInMetadata.DistinctRowCount.Handler.class);
    this.distributionHandler = provider.handler(BuiltInMetadata.Distribution.Handler.class);
    this.explainVisibilityHandler = provider.handler(BuiltInMetadata.ExplainVisibility.Handler.class);
    this.maxRowCountHandler = provider.handler(BuiltInMetadata.MaxRowCount.Handler.class);
    this.minRowCountHandler = provider.handler(BuiltInMetadata.MinRowCount.Handler.class);
    this.memoryHandler = provider.handler(BuiltInMetadata.Memory.Handler.class);
    this.measureHandler = provider.handler(BuiltInMetadata.Measure.Handler.class);
    this.nonCumulativeCostHandler = provider.handler(BuiltInMetadata.NonCumulativeCost.Handler.class);
    this.parallelismHandler = provider.handler(BuiltInMetadata.Parallelism.Handler.class);
    this.percentageOriginalRowsHandler = provider.handler(BuiltInMetadata.PercentageOriginalRows.Handler.class);
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

  /**
   * 创建 `RelMetadataQuery` 实例，该实例作为 Janino 动态编译生成的其他实例的原型。
   *
   * <p>该构造方法不会绑定 `MetadataHandlerProvider`，而是为所有的处理器创建一个默认的初始实例，
   * 这些处理器会在后续的 `revise()` 方法调用中进行动态替换。
   */
  @SuppressWarnings("deprecation")
  private RelMetadataQuery(@SuppressWarnings("unused") boolean dummy) {
    super(null);

    // 初始化所有元数据查询处理器为默认的初始处理器
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
    this.percentageOriginalRowsHandler = initialHandler(BuiltInMetadata.PercentageOriginalRows.Handler.class);
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

  /**
   * 创建 `RelMetadataQuery` 的实例，并从现有的 `prototype` 复制所有处理器实例。
   *
   * @param metadataHandlerProvider 提供元数据处理器的对象
   * @param prototype 现有的 `RelMetadataQuery` 实例，作为新实例的原型
   */
  private RelMetadataQuery(
      MetadataHandlerProvider metadataHandlerProvider,
      RelMetadataQuery prototype) {
    super(metadataHandlerProvider);

    // 复制 `prototype` 的所有处理器实例
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

// ------------------------------------------------------------------

  /**
   * 获取 `RelMetadataQuery` 的新实例，确保在计算元数据时不会发生循环调用。
   *
   * <p>此方法用于创建新的 `RelMetadataQuery` 实例，并确保不会产生元数据查询的递归循环问题。
   * 通过 `THREAD_PROVIDERS` 线程局部变量维护的 `JaninoRelMetadataProvider` 进行初始化。
   *
   * @return 一个新的 `RelMetadataQuery` 实例
   */
  public static RelMetadataQuery instance() {
    return new RelMetadataQuery();
  }

  /**
   * 获取 `BuiltInMetadata.NodeTypes#getNodeTypes()` 统计信息。
   *
   * <p>该方法查询某个 `RelNode` 在执行计划中的具体类型。
   *
   * @param rel 需要查询的关系表达式
   * @return 返回 `Multimap<Class<? extends RelNode>, RelNode>`，表示关系表达式的类型分布
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
   * 获取关系表达式的估算行数。
   *
   * <p>该方法会调用 `rowCountHandler` 处理器来计算 `RelNode` 的行数，并确保返回有效的结果。
   * 如果没有可用的处理器，会动态更新 `rowCountHandler` 处理器以适配新情况。
   *
   * @param rel 需要计算行数的关系表达式
   * @return 估算的行数，如果无法估算，则返回 `null`
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
   * 获取关系表达式的最大行数。
   *
   * <p>该方法通过 `maxRowCountHandler` 处理器获取 `RelNode` 的最大可能行数。
   *
   * @param rel 需要计算最大行数的关系表达式
   * @return 最大可能的行数，如果无法估算，则返回 `null`
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
   * 获取关系表达式的最小行数。
   *
   * <p>该方法通过 `minRowCountHandler` 处理器获取 `RelNode` 的最小可能行数。
   *
   * @param rel 需要计算最小行数的关系表达式
   * @return 最小可能的行数，如果无法估算，则返回 `null`
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
   * 判断关系表达式的返回行是否为空。
   *
   * <p>如果 `minRowCount` 大于 0，则表示不为空，返回 `false`。
   * 如果 `maxRowCount` 小于等于 0，则表示为空，返回 `true`。
   * 如果无法确定，则返回 `null`。
   *
   * @param relNode 需要检查的关系表达式
   * @return `true` 表示为空，`false` 表示不为空，`null` 表示无法确定
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
   * 获取关系表达式的累计执行成本。
   *
   * <p>累计成本是整个 `RelNode` 及其所有子节点的执行成本之和。
   * 该方法会调用 `cumulativeCostHandler` 处理器计算成本，并在必要时更新处理器。
   *
   * @param rel 需要计算成本的关系表达式
   * @return 累计执行成本，如果无法估算，则返回 `null`
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
   * 获取关系表达式的非累计执行成本。
   *
   * <p>非累计成本是 `RelNode` 自身的计算成本，不包括子节点的成本。
   * 该方法会调用 `nonCumulativeCostHandler` 处理器计算成本，并在必要时更新处理器。
   *
   * @param rel 需要计算成本的关系表达式
   * @return 非累计执行成本，如果无法估算，则返回 `null`
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
   * 获取关系表达式保留的原始行比例。
   *
   * <p>该方法会返回 `RelNode` 在执行过程中保留的原始数据比例，范围在 `0.0` 到 `1.0` 之间。
   *
   * @param rel 需要计算原始行比例的关系表达式
   * @return 估算的原始行比例，如果无法估算，则返回 `null`
   */
  public @Nullable Double getPercentageOriginalRows(RelNode rel) {
    for (;;) {
      try {
        Double result = percentageOriginalRowsHandler.getPercentageOriginalRows(rel, this);
        return RelMdUtil.validatePercentage(result);
      } catch (MetadataHandlerProvider.NoHandler e) {
        percentageOriginalRowsHandler = revise(BuiltInMetadata.PercentageOriginalRows.Handler.class);
      }
    }
  }

  /**
   * 获取关系表达式某列的来源信息。
   *
   * <p>返回该列的所有可能来源表及字段信息。多个来源可能来自 `UNION` 或 `JOIN` 等操作。
   *
   * @param rel 需要查询的关系表达式
   * @param column 列索引（从 0 开始）
   * @return 该列的来源集合，如果无法确定，则返回 `null`
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
   * 获取关系表达式的唯一表来源（如果适用）。
   *
   * <p>如果 `RelNode` 直接映射到一个基础表（可能带有筛选和投影），则返回该表。
   * 否则，返回 `null`。
   *
   * @param rel 需要查询的关系表达式
   * @return `RelOptTable` 实例，如果 `RelNode` 不是简单表，则返回 `null`
   */
  public @Nullable RelOptTable getTableOrigin(RelNode rel) {
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
   * 获取关系表达式的表达式血缘信息。
   *
   * <p>返回 `RexNode` 表达式的来源信息，帮助追踪字段计算过程中的依赖关系。
   *
   * @param rel 需要查询的关系表达式
   * @param expression 需要追踪血缘的表达式
   * @return 该表达式的血缘信息，如果无法确定，则返回 `null`
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
   * 获取 `RelNode` 计划涉及的表集合。
   *
   * <p>此方法返回 `RelNode` 计划中所有涉及的基础表（`TableScan` 操作）。
   *
   * @param rel 需要查询的关系表达式
   * @return `RelTableRef` 表集合，如果无法确定，则返回 `null`
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
   * 获取关系表达式的唯一键集合。
   *
   * <p>该方法调用 `getUniqueKeys(rel, false)`，默认情况下不忽略 `NULL` 值。
   *
   * @param rel 需要查询的关系表达式
   * @return 唯一键的集合，如果无法确定，则返回 `null`
   */
  public @Nullable Set<ImmutableBitSet> getUniqueKeys(RelNode rel) {
    return getUniqueKeys(rel, false);
  }

  /**
   * 获取关系表达式的唯一键集合。
   *
   * <p>唯一键是一组列，这组列的组合可以唯一标识一行。
   *
   * @param rel         需要查询的关系表达式
   * @param ignoreNulls 如果为 `true`，则忽略 `NULL` 值来判断唯一性
   * @return 唯一键的集合，如果无法确定，则返回 `null`
   */
  public @Nullable Set<ImmutableBitSet> getUniqueKeys(RelNode rel, boolean ignoreNulls) {
    for (;;) {
      try {
        return uniqueKeysHandler.getUniqueKeys(rel, this, ignoreNulls);
      } catch (MetadataHandlerProvider.NoHandler e) {
        uniqueKeysHandler = revise(BuiltInMetadata.UniqueKeys.Handler.class);
      }
    }
  }

  /**
   * 判断关系表达式的行是否唯一（可选忽略 `NULL` 值）。
   *
   * <p>如果 `MaxRowCount` 小于等于 `1`，则直接返回 `true`，因为最多只有一行数据，必然是唯一的。
   * 否则，调用 `areColumnsUnique` 方法检查所有列是否唯一。
   *
   * @param rel         需要查询的关系表达式
   * @param ignoreNulls 是否忽略 `NULL` 值来判断唯一性
   * @return `true` 表示所有行都是唯一的，`false` 表示有重复行，`null` 表示无法确定
   */
  public @Nullable Boolean areRowsUnique(RelNode rel, boolean ignoreNulls) {
    Double maxRowCount = this.getMaxRowCount(rel);
    if (maxRowCount != null && maxRowCount <= 1D) {
      return true;
    }
    final ImmutableBitSet columns = ImmutableBitSet.range(rel.getRowType().getFieldCount());
    return areColumnsUnique(rel, columns, ignoreNulls);
  }

  /**
   * 判断关系表达式的行是否唯一（默认不忽略 `NULL` 值）。
   *
   * <p>该方法调用 `areRowsUnique(rel, false)`，默认情况下不忽略 `NULL` 值。
   *
   * @param rel 需要查询的关系表达式
   * @return `true` 表示所有行都是唯一的，`false` 表示有重复行，`null` 表示无法确定
   */
  public @Nullable Boolean areRowsUnique(RelNode rel) {
    return areRowsUnique(rel, false);
  }

  /**
   * 判断关系表达式的某些列是否唯一。
   *
   * <p>该方法调用 `areColumnsUnique(rel, columns, false)`，默认情况下不忽略 `NULL` 值。
   *
   * @param rel     需要查询的关系表达式
   * @param columns 需要检查唯一性的列索引集合
   * @return `true` 表示这些列是唯一的，`false` 表示有重复值，`null` 表示无法确定
   */
  public @Nullable Boolean areColumnsUnique(RelNode rel, ImmutableBitSet columns) {
    return areColumnsUnique(rel, columns, false);
  }

  /**
   * 判断关系表达式的某些列是否唯一（可选忽略 `NULL` 值）。
   *
   * <p>如果 `columns` 的组合可以唯一标识 `rel` 中的一行，则返回 `true`，否则返回 `false`。
   *
   * @param rel         需要查询的关系表达式
   * @param columns     需要检查唯一性的列索引集合
   * @param ignoreNulls 是否忽略 `NULL` 值来判断唯一性
   * @return `true` 表示这些列是唯一的，`false` 表示有重复值，`null` 表示无法确定
   */
  public @Nullable Boolean areColumnsUnique(RelNode rel, ImmutableBitSet columns, boolean ignoreNulls) {
    for (;;) {
      try {
        return columnUniquenessHandler.areColumnsUnique(rel, this, columns, ignoreNulls);
      } catch (MetadataHandlerProvider.NoHandler e) {
        columnUniquenessHandler = revise(BuiltInMetadata.ColumnUniqueness.Handler.class);
      }
    }
  }

  /**
   * 获取关系表达式的排序信息（Collation）。
   *
   * <p>Collation 描述了 `RelNode` 的排序方式，例如某些列是否已经按照升序或降序排列。
   *
   * @param rel 需要查询的关系表达式
   * @return 排序信息列表，如果无法确定，则返回 `null`
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
   * 获取关系表达式的数据分布方式（Distribution）。
   *
   * <p>数据分布描述了 `RelNode` 结果在计算节点上的分布方式，例如：
   * - 复制（REPLICATED）：数据在所有节点上均可用
   * - 哈希分布（HASH）：数据根据哈希函数分布到不同节点
   * - 分区（RANGE）：数据按照范围进行分区
   *
   * @param rel 需要查询的关系表达式
   * @return 数据分布信息，如果无法确定，则返回 `RelDistributions.ANY`
   */
  public RelDistribution distribution(RelNode rel) {
    for (;;) {
      try {
        RelDistribution distribution = distributionHandler.distribution(rel, this);
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
   * 获取关系表达式中某些列的去重行数（基数）。
   *
   * <p>基数（Population Size）表示指定列集合的不同值的数量，
   * 也就是如果对 `groupKey` 进行 `GROUP BY` 操作，理论上会有多少组。
   *
   * @param rel      需要查询的关系表达式
   * @param groupKey 需要计算基数的列集合（使用 `ImmutableBitSet` 表示）
   * @return 估算的去重行数（基数），如果无法确定，则返回 `null`
   */
  public @Nullable Double getPopulationSize(RelNode rel, ImmutableBitSet groupKey) {
    for (;;) {
      try {
        Double result = populationSizeHandler.getPopulationSize(rel, this, groupKey);
        return RelMdUtil.validateResult(result);
      } catch (MetadataHandlerProvider.NoHandler e) {
        populationSizeHandler = revise(BuiltInMetadata.PopulationSize.Handler.class);
      }
    }
  }

  /**
   * 获取关系表达式的平均行大小（以字节为单位）。
   *
   * <p>行大小用于估算数据存储和内存使用情况，通常由多个列的大小累加计算得到。
   *
   * @param rel 需要查询的关系表达式
   * @return 估算的行大小（字节），如果无法确定，则返回 `null`
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
   * 获取关系表达式中各列的平均大小（以字节为单位）。
   *
   * <p>该方法返回一个列表，每个元素表示该列的平均大小。
   * 通常用于估算存储需求或查询优化（如选择索引）。
   *
   * @param rel 需要查询的关系表达式
   * @return 每列的平均大小（字节）的列表，如果无法确定，则返回 `null`
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

  /**
   * 获取关系表达式中各列的平均大小（以字节为单位），保证返回非空列表。
   *
   * <p>如果 `getAverageColumnSizes(rel)` 返回 `null`，则返回一个全 `null` 的列表，
   * 确保不会因为 `null` 结果而影响后续计算逻辑。
   *
   * @param rel 需要查询的关系表达式
   * @return 每列的平均大小（字节）的列表，如果无法确定，则返回一个全 `null` 的列表
   */
  public List<@Nullable Double> getAverageColumnSizesNotNull(RelNode rel) {
    final @Nullable List<@Nullable Double> averageColumnSizes = getAverageColumnSizes(rel);
    return averageColumnSizes == null
        ? Collections.nCopies(rel.getRowType().getFieldCount(), null)
        : averageColumnSizes;
  }

  /**
   * 判断某个 `RelNode` 是否是阶段转换（Phase Transition）。
   *
   * <p>阶段转换（Phase Transition）通常表示计算过程中的关键变换点，
   * 例如 `Exchange` 算子引起的数据重新分布，或者某些需要全局同步的操作。
   *
   * @param rel 需要查询的关系表达式
   * @return `true` 表示是阶段转换，`false` 表示不是，`null` 表示无法确定
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
   * 获取数据的分片数量（Split Count）。
   *
   * <p>数据分片（Split Count）表示查询执行时，数据被分割成多少个独立的子集，
   * 例如并行计算任务的分片数量。这在优化并行计算时至关重要。
   *
   * @param rel 需要查询的关系表达式
   * @return 数据分片数量，如果无法确定，则返回 `null`
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
   * 获取某个关系表达式的内存使用估算值（以字节为单位）。
   *
   * <p>该方法估算 `rel` 计算时可能使用的内存大小，通常用于查询优化器决策，
   * 例如是否选择基于内存的执行计划或是否需要溢写到磁盘。
   *
   * @param rel 需要查询的关系表达式
   * @return 估算的内存使用量（字节），如果无法确定，则返回 `null`
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
   * 获取当前关系表达式（RelNode）及其所在的计算阶段（phase）中所有操作符的累计内存使用量（以字节为单位）。
   *
   * <p>该方法会遍历所有分裂（split），计算整个阶段内的累计内存需求，通常适用于计算任务中需要评估
   * 内存占用情况的场景，例如流处理中的状态存储或批处理中的数据缓存需求。</p>
   *
   * @param rel 关系表达式（RelNode）
   * @return 该表达式及其阶段内所有操作符的总内存需求（字节单位），如果未知则返回 null
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
   * 获取当前关系表达式（RelNode）及其阶段内所有操作符在每个分裂（split）中的累计内存需求（字节单位）。
   *
   * <p>基本计算公式如下：
   * <blockquote> cumulativeMemoryWithinPhaseSplit = cumulativeMemoryWithinPhase / Parallelism.splitCount() </blockquote>
   *
   * 该方法适用于计算任务被划分为多个并行执行的子任务（例如分区处理），用于估算每个分区的内存使用情况。
   *
   * @param rel 关系表达式（RelNode）
   * @return 该表达式及其阶段内所有操作符在每个分裂中的预计累计内存需求（字节单位），如果未知则返回 null
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
   * 判断某个列是否为度量值（Measure）。
   *
   * <p>度量值通常用于聚合计算，例如 SUM、AVG 等，区分于维度列（Dimension）。</p>
   *
   * @param rel 关系表达式（RelNode）
   * @param column 需要检查的列索引（基于 0）
   * @return 如果该列是度量值，则返回 true，否则返回 false；如果无法确定，则返回 null
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
   * 获取某个度量列在给定上下文中的具体计算表达式。
   *
   * <p>通常用于查询优化器对聚合查询进行重写，例如在多维分析（OLAP）场景下，通过度量展开（Measure Expansion）
   * 来优化查询执行计划。</p>
   *
   * @param rel 关系表达式（RelNode）
   * @param column 需要展开的度量列索引（基于 0）
   * @param context 度量展开的计算上下文
   * @return 该列在当前上下文中的计算表达式
   */
  public RexNode expand(RelNode rel, int column, BuiltInMetadata.Measure.Context context) {
    for (;;) {
      try {
        return measureHandler.expand(rel, this, column, context);
      } catch (MetadataHandlerProvider.NoHandler e) {
        measureHandler = revise(BuiltInMetadata.Measure.Handler.class);
      }
    }
  }

  /**
   * 估算某个 GROUP BY 语句在特定列集上的去重行数（Distinct Row Count）。
   *
   * <p>例如，在 SQL 语句 `SELECT COUNT(DISTINCT col1) FROM table` 中，本方法可以用于估算
   * `col1` 的不同值的数量（即去重后的行数）。</p>
   *
   * @param rel 关系表达式（RelNode）
   * @param groupKey 需要进行去重统计的列集合（ImmutableBitSet）
   * @param predicate 预过滤的谓词（RexNode），用于在去重前应用某些筛选条件
   * @return 该列集合去重后的行数估算值，如果无法估算则返回 null
   */
  public @Nullable Double getDistinctRowCount(RelNode rel, ImmutableBitSet groupKey, @Nullable RexNode predicate) {
    for (;;) {
      try {
        Double result = distinctRowCountHandler.getDistinctRowCount(rel, this, groupKey, predicate);
        return RelMdUtil.validateResult(result);
      } catch (MetadataHandlerProvider.NoHandler e) {
        distinctRowCountHandler = revise(BuiltInMetadata.DistinctRowCount.Handler.class);
      }
    }
  }

  /**
   * 获取可以提升（pulled up）的谓词（Predicates）。
   *
   * <p>谓词提升（Predicate Pull-Up）是一种优化技术，能够将某些筛选条件上推，以减少数据处理的规模，
   * 从而提高查询效率。</p>
   *
   * @param rel 关系表达式（RelNode）
   * @return 该关系表达式可以提升的谓词列表（RelOptPredicateList）
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
   * 获取关系表达式及其子节点中的所有谓词（All Predicates）。
   *
   * <p>该方法比 {@code getPulledUpPredicates()} 更全面，包含了当前节点及其子节点中的所有谓词信息，
   * 适用于需要全局优化或查询改写的场景。</p>
   *
   * @param rel 关系表达式（RelNode）
   * @return 该关系表达式及其子节点中的所有谓词信息（RelOptPredicateList），如果无法确定则返回 null
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
   * 判断某个关系表达式在 EXPLAIN 计划中是否可见。
   *
   * <p>在 SQL 执行计划（EXPLAIN PLAN）中，某些操作可能会被优化器隐藏，本方法用于判断某个关系表达式
   * 是否应该在 EXPLAIN 输出中可见。</p>
   *
   * @param rel 关系表达式（RelNode）
   * @param explainLevel EXPLAIN 计划的详细级别
   * @return 如果该关系表达式应当显示在 EXPLAIN 计划中，则返回 true，否则返回 false
   */
  public Boolean isVisibleInExplain(RelNode rel, SqlExplainLevel explainLevel) {
    for (;;) {
      try {
        Boolean b = explainVisibilityHandler.isVisibleInExplain(rel, this, explainLevel);
        return b == null || b;
      } catch (MetadataHandlerProvider.NoHandler e) {
        explainVisibilityHandler = revise(BuiltInMetadata.ExplainVisibility.Handler.class);
      }
    }
  }

  /**
   * 获取关系表达式的物理分布信息（Distribution）。
   *
   * <p>数据分布（Distribution）描述了数据如何在不同计算节点之间划分，例如 Hash 分区、广播（Broadcast）
   * 或者随机分布（Any）。</p>
   *
   * @param rel 关系表达式（RelNode）
   * @return 该关系表达式的数据分布描述（RelDistribution），如果未知则返回 null
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
   * 计算给定关系表达式的最低执行成本（Lower Bound Cost）。
   *
   * <p>下界成本用于优化器评估查询计划时的最小开销，例如最少的 I/O 或 CPU 计算量。</p>
   *
   * @param rel 关系表达式（RelNode）
   * @param planner 规划器（VolcanoPlanner）
   * @return 该关系表达式的最小执行成本（RelOptCost），如果未知则返回 null
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
