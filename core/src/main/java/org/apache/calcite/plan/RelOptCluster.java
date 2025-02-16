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

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.hint.HintStrategyTable;
import org.apache.calcite.rel.metadata.DefaultRelMetadataProvider;
import org.apache.calcite.rel.metadata.JaninoRelMetadataProvider;
import org.apache.calcite.rel.metadata.MetadataFactory;
import org.apache.calcite.rel.metadata.RelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.metadata.RelMetadataQueryBase;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;

import org.checkerframework.checker.initialization.qual.UnknownInitialization;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.apache.calcite.linq4j.Nullness.castNonNull;

import static java.util.Objects.requireNonNull;

/**
 * 在查询优化过程中，为相关的关系表达式提供环境。
 * 该类管理查询优化过程中的上下文，包括查询规划器、类型工厂、元数据提供程序等。
 */
public class RelOptCluster {
  //~ 实例字段 --------------------------------------------------------

  /** 用于创建和管理 SQL 类型的工厂 */
  private final RelDataTypeFactory typeFactory;
  /** 负责优化查询的规划器 */
  private final RelOptPlanner planner;
  /** 用于生成唯一的关联 ID */
  private final AtomicInteger nextCorrel;
  /** 维护关联 ID 与关系表达式的映射 */
  private final Map<String, RelNode> mapCorrelToRel;
  /** 存储查询的原始表达式 */
  private RexNode originalExpression;
  /** 用于构建关系表达式的工具类 */
  private final RexBuilder rexBuilder;
  /** 提供关系元数据的提供者 */
  private RelMetadataProvider metadataProvider;
  /** 过时的元数据工厂（将在 2.0 版本移除） */
  @Deprecated
  private MetadataFactory metadataFactory;
  /** 提供 SQL Hint 处理策略的表 */
  private @Nullable HintStrategyTable hintStrategies;
  /** 存储当前集群的默认 trait 集合 */
  private final RelTraitSet emptyTraitSet;
  /** 关系元数据查询实例 */
  private @Nullable RelMetadataQuery mq;
  /** 关系元数据查询实例的提供者 */
  private Supplier<RelMetadataQuery> mqSupplier;

  //~ 构造方法 -----------------------------------------------------------

  /**
   * 创建一个查询优化集群（已弃用，2.0 版本前移除）。
   */
  @Deprecated
  RelOptCluster(
      RelOptQuery query,
      RelOptPlanner planner,
      RelDataTypeFactory typeFactory,
      RexBuilder rexBuilder) {
    this(planner, typeFactory, rexBuilder, query.nextCorrel,
        query.mapCorrelToRel);
  }

  /**
   * 创建一个查询优化集群。
   * 仅供 {@link #create} 和 {@link RelOptQuery} 使用。
   */
  RelOptCluster(RelOptPlanner planner, RelDataTypeFactory typeFactory,
      RexBuilder rexBuilder, AtomicInteger nextCorrel,
      Map<String, RelNode> mapCorrelToRel) {
    this.nextCorrel = nextCorrel;
    this.mapCorrelToRel = mapCorrelToRel;
    this.planner = requireNonNull(planner, "planner");
    this.typeFactory = requireNonNull(typeFactory, "typeFactory");
    this.rexBuilder = rexBuilder;
    this.originalExpression = rexBuilder.makeLiteral("?");

    // 设置默认的关系元数据提供程序，并让规划器优先处理元数据
    setMetadataProvider(DefaultRelMetadataProvider.INSTANCE);
    setMetadataQuerySupplier(RelMetadataQuery::instance);
    this.emptyTraitSet = planner.emptyTraitSet();
    assert emptyTraitSet.size() == planner.getRelTraitDefs().size();
  }

  /**
   * 创建一个新的查询优化集群。
   */
  public static RelOptCluster create(RelOptPlanner planner,
      RexBuilder rexBuilder) {
    return new RelOptCluster(planner, rexBuilder.getTypeFactory(),
        rexBuilder, new AtomicInteger(0), new HashMap<>());
  }

  //~ 方法 ----------------------------------------------------------------

  /**
   * 获取查询优化规划器。
   */
  public RelOptPlanner getPlanner() {
    return planner;
  }

  /**
   * 获取 SQL 类型工厂。
   */
  public RelDataTypeFactory getTypeFactory() {
    return typeFactory;
  }

  /**
   * 获取用于构建表达式的 RexBuilder。
   */
  public RexBuilder getRexBuilder() {
    return rexBuilder;
  }

  /**
   * 获取当前的元数据提供程序。
   */
  public @Nullable RelMetadataProvider getMetadataProvider() {
    return metadataProvider;
  }

  /**
   * 设置元数据提供程序。
   *
   * @param metadataProvider 自定义的元数据提供程序
   */
  @EnsuresNonNull({"this.metadataProvider", "this.metadataFactory"})
  @SuppressWarnings("deprecation")
  public void setMetadataProvider(
      @UnknownInitialization RelOptCluster this,
      RelMetadataProvider metadataProvider) {
    this.metadataProvider = metadataProvider;
    this.metadataFactory =
        new org.apache.calcite.rel.metadata.MetadataFactoryImpl(metadataProvider);
    // 将元数据提供程序封装为 JaninoRelMetadataProvider，并设置为线程本地变量
    // 以便 RelMetadataQuery 能够正常运行
    RelMetadataQueryBase.THREAD_PROVIDERS
        .set(JaninoRelMetadataProvider.of(metadataProvider));
  }

  /**
   * 获取元数据工厂（已弃用，2.0 版本前移除）。
   */
  @Deprecated
  public MetadataFactory getMetadataFactory() {
    return metadataFactory;
  }

  /**
   * 设置元数据查询实例提供者。
   *
   * <p>注意，{@code mqSupplier} 应返回一个新的 {@link RelMetadataQuery} 实例，
   * 因为该实例可能会在规则匹配期间被缓存，并在 {@link RelOptRuleCall} 过程中重新生成。</p>
   */
  @EnsuresNonNull("this.mqSupplier")
  public void setMetadataQuerySupplier(
      @UnknownInitialization RelOptCluster this,
      Supplier<RelMetadataQuery> mqSupplier) {
    this.mqSupplier = mqSupplier;
  }

  /**
   * 获取当前的 {@link RelMetadataQuery} 实例。
   *
   * <p>该方法用于在查询优化过程中访问 {@link RelMetadataQuery}，它提供了各种元数据查询功能，
   * 例如行数估计（row count）、唯一键（unique keys）、分区信息（distribution）等。
   *
   * <p>注意：未来该方法可能会发生变更或迁移。如果在规则匹配（rule matching）过程中，
   * 例如在 {@link RelOptRule#onMatch(RelOptRuleCall)} 方法中，
   * 建议使用 {@link RelOptRuleCall#getMetadataQuery()} 方法来获取元数据查询实例。
   *
   * @return 当前的 {@link RelMetadataQuery} 实例
   */
  public RelMetadataQuery getMetadataQuery() {
    if (mq == null) {
      // 如果当前的元数据查询实例为空，则通过供应器（Supplier）获取一个新的实例
      mq = castNonNull(mqSupplier).get();
    }
    return mq;
  }

  /**
   * 获取 {@link RelMetadataQuery} 的供应器（Supplier）。
   *
   * <p>该方法用于返回一个可以创建 {@link RelMetadataQuery} 实例的供应器对象，
   * 以便在需要时提供新的元数据查询实例。
   *
   * @return 一个提供 {@link RelMetadataQuery} 的供应器对象
   */
  public Supplier<RelMetadataQuery> getMetadataQuerySupplier() {
    return this.mqSupplier;
  }

  /**
   * 当当前的 {@link RelMetadataQuery} 实例失效时，应调用此方法进行重置。
   *
   * <p>通常该方法在规则转换（rule transformation）过程中被调用，例如
   * {@link RelOptRuleCall#transformTo} 方法可能会引起元数据查询失效，
   * 这时需要重新生成新的元数据查询实例。
   */
  public void invalidateMetadataQuery() {
    mq = null;
  }

  /**
   * 设置查询优化过程中用于 Hint 传播（Hint Propagation）的策略表。
   *
   * <p>在 Calcite 查询优化中，Hint（提示信息）可用于指导 SQL 执行计划的选择，
   * 例如：
   * <ul>
   *   <li>强制使用 Hash Join (+ USE_HASH_JOIN)</li>
   *   <li>指定数据的 广播模式 (+ BROADCAST)</li>
   * </ul>
   *
   * <p>用户可通过 {@code RelOptNode.getCluster().getHintStrategies()} 获取当前的 Hint 传播策略表。
   *
   * <p><strong>注意：</strong>此方法主要用于内部配置，通常在
   * {@link org.apache.calcite.sql2rel.SqlToRelConverter.Config} 解析阶段进行初始化，
   * 并不建议在查询优化过程中动态修改。</p>
   *
   * @param hintStrategies 需要应用的 Hint 传播策略表
   */
  public void setHintStrategies(HintStrategyTable hintStrategies) {
    requireNonNull(hintStrategies, "hintStrategies");
    this.hintStrategies = hintStrategies;
  }

  /**
   * 获取当前集群中的 Hint 传播策略表（Hint Strategy Table）。
   *
   * <p>在整个优化阶段，该策略表是不可变（immutable）的，意味着在优化期间不会动态修改 Hint 传播规则。
   *
   * <p>如果 `hintStrategies` 为空，则返回一个默认的空 Hint 传播表 `HintStrategyTable.EMPTY`。
   *
   * @return 当前的 Hint 传播策略表
   */
  public HintStrategyTable getHintStrategies() {
    if (this.hintStrategies == null) {
      this.hintStrategies = HintStrategyTable.EMPTY;
    }
    return this.hintStrategies;
  }

  /**
   * 生成一个新的相关变量（correlating variable）的 ID，并确保该 ID 在整个查询中是唯一的。
   *
   * <p>相关变量（CorrelationId）通常用于 **相关子查询（correlated subquery）** 或
   * **LATERAL JOIN**，这些查询模式需要在查询优化过程中使用唯一标识符进行变量绑定。
   *
   * <p>每次调用该方法时，都会递增 `nextCorrel` 计数器，以确保生成的 ID 唯一。
   *
   * @return 一个新的 {@link CorrelationId}，用于标识相关变量
   */
  public CorrelationId createCorrel() {
    return new CorrelationId(nextCorrel.getAndIncrement());
  }

  /**
   * 获取当前集群的默认 TraitSet（关系代数算子的执行特性集合）。
   *
   * <p>`TraitSet` 是 Calcite 查询优化框架中的一个核心概念，它用于描述查询执行的 **物理特性**，
   * 例如：
   * - **并行性**（是否支持分布式执行）
   * - **数据排序**（是否有序）
   * - **数据分区**（数据如何分布在不同的节点上）
   *
   * <p>该方法返回该 `RelOptCluster` 关联的默认 `TraitSet`，用于优化过程中进行 Trait 传递。
   *
   * @return 当前集群的默认 TraitSet
   */
  public RelTraitSet traitSet() {
    return emptyTraitSet;
  }

  /**
   * @deprecated 此方法已被弃用，即将在 Calcite 2.0 版本前移除。
   *
   * <p>请使用 {@link #traitSet}().replace(t1).replace(t2) 替代。
   *
   * @param traits 需要应用的 Trait 列表
   * @return 具有指定 Trait 的 TraitSet
   */
  @Deprecated
  public RelTraitSet traitSetOf(RelTrait... traits) {
    RelTraitSet traitSet = emptyTraitSet;
    for (RelTrait trait : traits) {
      traitSet = traitSet.replace(trait);
    }
    return traitSet;
  }

  /**
   * 获取一个新的 TraitSet，其中包含指定的 Trait 信息。
   *
   * <p>该方法用于替换默认的 TraitSet，并在查询优化过程中用于物理优化规则匹配，
   * 例如，可以指定查询是否可以并行执行或是否适用于流式计算（streaming）。
   *
   * @param trait 需要添加到 TraitSet 中的 Trait
   * @return 具有指定 Trait 的 TraitSet
   */
  public RelTraitSet traitSetOf(RelTrait trait) {
    return emptyTraitSet.replace(trait);
  }

}
