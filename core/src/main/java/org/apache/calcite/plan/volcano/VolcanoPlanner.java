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
package org.apache.calcite.plan.volcano;

import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.config.CalciteSystemProperty;
import org.apache.calcite.plan.AbstractRelOptPlanner;
import org.apache.calcite.plan.Context;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.ConventionTraitDef;
import org.apache.calcite.plan.RelDigest;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptCostFactory;
import org.apache.calcite.plan.RelOptLattice;
import org.apache.calcite.plan.RelOptMaterialization;
import org.apache.calcite.plan.RelOptMaterializations;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptRuleOperand;
import org.apache.calcite.plan.RelOptSchema;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelTrait;
import org.apache.calcite.plan.RelTraitDef;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.PhysicalNode;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.Converter;
import org.apache.calcite.rel.convert.ConverterRule;
import org.apache.calcite.rel.externalize.RelWriterImpl;
import org.apache.calcite.rel.metadata.CyclicMetadataException;
import org.apache.calcite.rel.metadata.RelMdUtil;
import org.apache.calcite.rel.metadata.RelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.rules.SubstitutionRule;
import org.apache.calcite.rel.rules.TransformationRule;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.runtime.Hook;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.util.Litmus;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;

import org.apiguardian.api.API;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.PolyNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.checkerframework.dataflow.qual.Pure;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;

import static org.apache.calcite.linq4j.Nullness.castNonNull;

import static java.util.Objects.requireNonNull;

/**
 * VolcanoPlanner 是一个基于动态规划算法的查询优化器，
 * 它通过选择性地转换表达式来优化查询计划。
 */
public class VolcanoPlanner extends AbstractRelOptPlanner {

  //~ 实例字段 --------------------------------------------------------

  protected @MonotonicNonNull RelSubset root;
  // 表示优化器的根节点，即查询的逻辑表达式的入口点。

  /**
   * 存储适用于特定类型的 RelNode 的操作数（Operand）。
   * 操作数是规则调用的“入口点”。
   * 当注册的 RelNode 匹配某个操作数时，就会触发规则调用。
   * 此映射允许根据 RelNode 的类型快速定位相关的操作数。
   */
  private final Multimap<Class<? extends RelNode>, RelOptRuleOperand>
      classOperands = LinkedListMultimap.create();

  /**
   * 所有 RelSet 的列表，仅用于调试目的。
   * RelSet 表示逻辑上等价的一组查询计划。
   */
  final List<RelSet> allSets = new ArrayList<>();

  /**
   * 存储每个已注册的 RelNode（关系表达式）的唯一标识（digest）到对应 RelNode 的映射。
   * digest 是表达式的规范化字符串表示，用于快速比较和查找等价表达式。
   */
  private final Map<RelDigest, RelNode> mapDigestToRel = new HashMap<>();

  /**
   * 将每个已注册的 RelNode 映射到其对应的等价类（RelSubset）。
   * 使用 IdentityHashMap 确保唯一性，简化 RelSet 合并操作。
   */
  private final IdentityHashMap<RelNode, RelSubset> mapRel2Subset =
      new IdentityHashMap<>();

  /**
   * 存储需要被剪枝的节点集合。
   * 如果一个 RelNode 被剪枝，那么所有与之相关的规则调用都会被忽略，
   * 并且未来也不会再对其进行新的规则调用。
   */
  final Set<RelNode> prunedNodes = new HashSet<>();

  /**
   * 存储所有已注册的 Schema（模式）。
   */
  private final Set<RelOptSchema> registeredSchemas = new HashSet<>();

  /**
   * 用于管理规则和规则匹配的驱动器。
   */
  RuleDriver ruleDriver;

  /**
   * 保存当前已注册的 RelTraitDef（属性定义）。
   * RelTraitDef 定义了 RelNode 的物理属性，例如排序或分布。
   */
  private final List<RelTraitDef> traitDefs = new ArrayList<>();

  private int nextSetId = 0; // 用于生成新的 RelSet 的唯一 ID。

  private @MonotonicNonNull RelNode originalRoot;
  // 初始查询计划的根节点。

  private @Nullable Convention rootConvention;
  // 根节点的调用约定（Convention），用于标识物理计划的类型。

  /**
   * 标志优化器是否锁定。
   * 如果锁定，优化器不会接受新的规则。
   */
  private boolean locked;

  /**
   * 是否将具有 Convention.NONE 的节点视为具有无限成本。
   */
  private boolean noneConventionHasInfiniteCost = true;

  private final List<RelOptMaterialization> materializations = new ArrayList<>();
  // 存储已注册的物化视图信息。

  /**
   * 存储每个星型表（star table）对应的 Lattice（晶格）对象。
   */
  private final Map<List<String>, RelOptLattice> latticeByName = new LinkedHashMap<>();

  final Map<RelNode, Provenance> provenanceMap;
  // 记录每个 RelNode 的来源信息。

  final Deque<VolcanoRuleCall> ruleCallStack = new ArrayDeque<>();
  // 规则调用栈，用于存储当前的规则调用上下文。

  /**
   * 零成本，根据 costFactory 的定义。
   * 不一定是 VolcanoCost 的实例。
   */
  final RelOptCost zeroCost;

  /**
   * 无限成本，根据 costFactory 的定义。
   * 不一定是 VolcanoCost 的实例。
   */
  final RelOptCost infCost;

  /**
   * 是否启用自顶向下优化。
   */
  boolean topDownOpt = CalciteSystemProperty.TOPDOWN_OPT.value();

  /**
   * 用于探索的额外根节点集合。
   */
  final Set<RelSubset> explorationRoots = new HashSet<>();


  //~ Constructors -----------------------------------------------------------

  //~ 构造函数 -----------------------------------------------------------

  /**
   * 创建一个未初始化的 VolcanoPlanner。
   * 调用者需要显式注册所需的关系表达式、规则和调用约定，才能完全初始化。
   */
  public VolcanoPlanner() {
    this(null, null); // 调用另一个构造函数并传入默认参数。
  }

  /**
   * 创建一个未初始化的 VolcanoPlanner，允许传入上下文。
   * 调用者需要显式注册所需的关系表达式、规则和调用约定，才能完全初始化。
   *
   * @param externalContext 外部上下文，用于提供配置或依赖。
   */
  public VolcanoPlanner(Context externalContext) {
    this(null, externalContext); // 调用另一个构造函数并传入默认参数。
  }

  /**
   * 创建一个 VolcanoPlanner，允许传入自定义的成本工厂。
   * 如果未提供成本工厂，则使用默认的 VolcanoCost.FACTORY。
   *
   * @param costFactory 成本工厂，用于定义查询计划的成本模型。
   * @param externalContext 外部上下文，用于提供配置或依赖。
   */
  @SuppressWarnings("method.invocation.invalid")
  public VolcanoPlanner(@Nullable RelOptCostFactory costFactory,
      @Nullable Context externalContext) {
    super(costFactory == null ? VolcanoCost.FACTORY : costFactory, externalContext);
    // 初始化零成本和无限成本，基于成本工厂的定义。
    this.zeroCost = this.costFactory.makeZeroCost();
    this.infCost = this.costFactory.makeInfiniteCost();

    // 如果日志记录器启用了调试模式，则启用来源信息的记录功能。
    this.provenanceMap = LOGGER.isDebugEnabled() ? new HashMap<>() : Util.blackholeMap();

    // 初始化规则队列，根据是否启用自顶向下优化选择合适的规则驱动器。
    initRuleQueue();
  }

  /**
   * 初始化规则队列。
   * 根据是否启用自顶向下优化选择规则驱动器。
   */
  @EnsuresNonNull("ruleDriver")
  private void initRuleQueue() {
    if (topDownOpt) {
      // 如果启用了自顶向下优化，使用 TopDownRuleDriver。
      ruleDriver = new TopDownRuleDriver(this);
    } else {
      // 否则，使用基于迭代的 IterativeRuleDriver。
      ruleDriver = new IterativeRuleDriver(this);
    }
  }


  //~ Methods ----------------------------------------------------------------

  /**
   * 启用或禁用自顶向下优化。
   *
   * <p>注意：启用自顶向下优化会自动启用自顶向下的属性传播机制。
   *
   * @param value 如果为 true，则启用自顶向下优化；否则禁用。
   */
  public void setTopDownOpt(boolean value) {
    if (topDownOpt == value) {
      return; // 如果当前设置与目标相同，则无需更新。
    }
    topDownOpt = value;
    initRuleQueue(); // 根据新设置重新初始化规则队列。
  }


  // implement RelOptPlanner
  @Override public boolean isRegistered(RelNode rel) {
    return mapRel2Subset.get(rel) != null;
  }

  /**
   * 设置优化器的根节点。
   *
   * <p>根节点是查询计划的起点，表示最终需要优化的目标查询逻辑表达式。
   *
   * @param rel 查询逻辑表达式的根节点。
   */
  @Override
  public void setRoot(RelNode rel) {
    // 将根节点注册到优化器中，并确保它属于某个 RelSet。
    this.root = registerImpl(rel, null);

    // 如果 originalRoot 还未设置，将当前节点设置为初始根节点。
    if (this.originalRoot == null) {
      this.originalRoot = rel;
    }

    // 记录根节点的调用约定（Convention），用于后续物理计划生成。
    rootConvention = this.root.getConvention();

    // 确保根节点中存在所有必需的转换器，以支持不同的物理属性。
    ensureRootConverters();
  }

  /**
   * 获取优化器的根节点。
   *
   * @return 根节点，如果尚未设置根节点，则返回 null。
   */
  @Pure
  @Override
  public @Nullable RelNode getRoot() {
    return root;
  }


  /**
   * 获取所有注册的物化视图。
   *
   * @return 不可变的物化视图列表。
   */
  @Override
  public List<RelOptMaterialization> getMaterializations() {
    return ImmutableList.copyOf(materializations); // 返回不可变列表，防止外部修改。
  }

  /**
   * 添加一个物化视图。
   *
   * @param materialization 物化视图的定义。
   */
  @Override
  public void addMaterialization(RelOptMaterialization materialization) {
    materializations.add(materialization); // 将物化视图添加到列表中。
  }

  /**
   * 添加一个晶格（Lattice）。
   *
   * @param lattice 晶格对象，表示查询计划中的星型表优化信息。
   */
  @Override
  public void addLattice(RelOptLattice lattice) {
    latticeByName.put(lattice.starRelOptTable.getQualifiedName(), lattice);
    // 将晶格与其对应的星型表名称绑定。
  }

  /**
   * 获取指定表的晶格信息。
   *
   * @param table 查询计划中的表。
   * @return 对应的晶格对象，如果没有，则返回 null。
   */
  @Override
  public @Nullable RelOptLattice getLattice(RelOptTable table) {
    return latticeByName.get(table.getQualifiedName()); // 根据表名查找对应的晶格。
  }


  /**
   * 注册所有与查询相关的物化视图。
   *
   * <p>此方法会：
   * <ul>
   *   <li>尝试使用物化视图优化查询计划。</li>
   *   <li>为未匹配的物化视图注册其表表达式，以供后续优化使用。</li>
   *   <li>探索与晶格（Lattice）相关的优化机会。</li>
   * </ul>
   */
  protected void registerMaterializations() {
    // 获取配置，检查是否启用物化视图。
    final CalciteConnectionConfig config = context.unwrap(CalciteConnectionConfig.class);
    if (config == null || !config.materializationsEnabled()) {
      return; // 如果未启用物化视图，则直接返回。
    }

    requireNonNull(root, "root");
    requireNonNull(originalRoot, "originalRoot");

    // 使用物化视图优化查询计划。
    final List<Pair<RelNode, List<RelOptMaterialization>>> materializationUses =
        RelOptMaterializations.useMaterializedViews(originalRoot, materializations);
    for (Pair<RelNode, List<RelOptMaterialization>> use : materializationUses) {
      RelNode rel = use.left; // 替换后的查询计划。
      Hook.SUB.run(rel); // 钩子，提供自定义扩展点。
      registerImpl(rel, root.set); // 将替换后的计划注册到根节点的 RelSet 中。
    }

    // 注册未使用的物化视图的表表达式。
    final Set<RelOptMaterialization> applicableMaterializations =
        new HashSet<>(
            RelOptMaterializations.getApplicableMaterializations(
                originalRoot, materializations));
    for (Pair<RelNode, List<RelOptMaterialization>> use : materializationUses) {
      applicableMaterializations.removeAll(use.right); // 移除已匹配的物化视图。
    }
    for (RelOptMaterialization materialization : applicableMaterializations) {
      RelSubset subset = registerImpl(materialization.queryRel, null);
      explorationRoots.add(subset); // 将未匹配的物化视图添加到探索根节点。
      RelNode tableRel2 =
          RelOptUtil.createCastRel(
              materialization.tableRel,
              materialization.queryRel.getRowType(),
              true); // 创建类型兼容的表表达式。
      registerImpl(tableRel2, subset.set);
    }

    // 使用晶格优化查询计划。
    final List<Pair<RelNode, RelOptLattice>> latticeUses =
        RelOptMaterializations.useLattices(
            originalRoot, ImmutableList.copyOf(latticeByName.values()));
    if (!latticeUses.isEmpty()) {
      RelNode rel = latticeUses.get(0).left; // 使用晶格优化后的查询计划。
      Hook.SUB.run(rel);
      registerImpl(rel, root.set); // 注册优化后的查询计划。
    }
  }

  /**
   * 获取一个表达式所属的等价类（RelSet）。
   *
   * @param rel 要查询的表达式。
   * @return 表达式所属的等价类，如果未注册，则返回 null。
   */
  public @Nullable RelSet getSet(RelNode rel) {
    requireNonNull(rel, "rel");
    final RelSubset subset = getSubset(rel);
    if (subset != null) {
      return requireNonNull(subset.set, "subset.set");
    }
    return null; // 如果表达式未注册，返回 null。
  }

  /**
   * 向优化器中添加一个关系特性定义（RelTraitDef）。
   *
   * @param relTraitDef 要添加的关系特性定义。
   * @return 如果特性定义成功添加（即之前未包含），返回 true；否则返回 false。
   */
  @Override
  public boolean addRelTraitDef(RelTraitDef relTraitDef) {
    // 检查是否已经包含该特性定义，如果没有，则添加并返回 true。
    return !traitDefs.contains(relTraitDef) && traitDefs.add(relTraitDef);
  }

  /**
   * 清除所有关系特性定义。
   */
  @Override
  public void clearRelTraitDefs() {
    // 清空 traitDefs 列表。
    traitDefs.clear();
  }

  /**
   * 获取当前所有注册的关系特性定义。
   *
   * @return 当前的特性定义列表。
   */
  @Override
  public List<RelTraitDef> getRelTraitDefs() {
    // 返回 traitDefs 列表。
    return traitDefs;
  }

  /**
   * 创建一个空的关系特性集（RelTraitSet），并为每个特性定义添加默认值。
   *
   * @return 包含默认值的关系特性集。
   */
  @Override
  public RelTraitSet emptyTraitSet() {
    // 调用父类方法创建一个空的关系特性集。
    RelTraitSet traitSet = super.emptyTraitSet();
    // 遍历所有特性定义，为每个特性添加默认值。
    for (RelTraitDef traitDef : traitDefs) {
      if (traitDef.multiple()) {
        // TODO: 需要调整 RelTraitSet 的结构以支持同一特性定义的多个条目。
      }
      traitSet = traitSet.plus(traitDef.getDefault());
    }
    return traitSet;
  }

  /**
   * 清除规划器的内部状态，包括规则、特性定义、节点等。
   */
  @Override
  public void clear() {
    // 调用父类方法清除基础状态。
    super.clear();
    // 删除所有规则。
    for (RelOptRule rule : getRules()) {
      removeRule(rule);
    }
    // 清空内部数据结构。
    this.classOperands.clear();
    this.allSets.clear();
    this.mapDigestToRel.clear();
    this.mapRel2Subset.clear();
    this.prunedNodes.clear();
    this.ruleDriver.clear();
    this.materializations.clear();
    this.latticeByName.clear();
    this.provenanceMap.clear();
  }

  /**
   * 向优化器中添加一个规则。
   *
   * @param rule 要添加的优化规则。
   * @return 如果规则成功添加，返回 true；否则返回 false。
   */
  @Override
  public boolean addRule(RelOptRule rule) {
    // 如果规划器已锁定，不允许添加新规则。
    if (locked) {
      return false;
    }

    // 调用父类方法尝试添加规则。
    if (!super.addRule(rule)) {
      return false;
    }

    // 检查规则是否是转换规则（TransformationRule）。
    final boolean isTransFormRule = rule instanceof TransformationRule;
    // 遍历规则的所有操作数，将它们与可能匹配的子类关联。
    for (RelOptRuleOperand operand : rule.getOperands()) {
      for (Class<? extends RelNode> subClass : subClasses(operand.getMatchedClass())) {
        // 如果是转换规则且子类是物理节点，跳过。
        if (isTransFormRule && PhysicalNode.class.isAssignableFrom(subClass)) {
          continue;
        }
        classOperands.put(subClass, operand);
      }
    }

    // 如果规则是转换规则（ConverterRule），注册到相关的特性定义中。
    if (rule instanceof ConverterRule) {
      ConverterRule converterRule = (ConverterRule) rule;
      final RelTrait ruleTrait = converterRule.getInTrait();
      final RelTraitDef ruleTraitDef = ruleTrait.getTraitDef();
      if (traitDefs.contains(ruleTraitDef)) {
        ruleTraitDef.registerConverterRule(this, converterRule);
      }
    }

    return true;
  }

  /**
   * 从优化器中移除一个规则。
   *
   * @param rule 要移除的优化规则。
   * @return 如果规则成功移除，返回 true；否则返回 false。
   */
  @Override
  public boolean removeRule(RelOptRule rule) {
    // 调用父类方法尝试移除规则。
    if (!super.removeRule(rule)) {
      // 如果规则不存在，返回 false。
      return false;
    }

    // 移除规则的所有操作数。
    classOperands.values().removeIf(entry -> entry.getRule().equals(rule));

    // 如果规则是转换规则，移除与特性定义的映射。
    if (rule instanceof ConverterRule) {
      ConverterRule converterRule = (ConverterRule) rule;
      final RelTrait ruleTrait = converterRule.getInTrait();
      final RelTraitDef ruleTraitDef = ruleTrait.getTraitDef();
      if (traitDefs.contains(ruleTraitDef)) {
        ruleTraitDef.deregisterConverterRule(this, converterRule);
      }
    }
    return true;
  }

  /**
   * 处理新类型的关系节点（RelNode），为其创建匹配映射。
   *
   * @param node 新的关系节点。
   */
  @Override
  protected void onNewClass(RelNode node) {
    // 调用父类方法。
    super.onNewClass(node);

    // 判断节点是否是物理节点。
    final boolean isPhysical = node instanceof PhysicalNode;
    // 获取节点的具体类。
    final Class<? extends RelNode> clazz = node.getClass();
    // 遍历所有规则，尝试为当前节点匹配操作数。
    for (RelOptRule rule : mapDescToRule.values()) {
      if (isPhysical && rule instanceof TransformationRule) {
        continue;
      }
      for (RelOptRuleOperand operand : rule.getOperands()) {
        if (operand.getMatchedClass().isAssignableFrom(clazz)) {
          classOperands.put(clazz, operand);
        }
      }
    }
  }

  /**
   * 修改关系节点的特性集（RelTraitSet）。
   *
   * @param rel 要修改的关系节点。
   * @param toTraits 目标特性集。
   * @return 修改后的关系节点。
   */
  @Override
  public RelNode changeTraits(final RelNode rel, RelTraitSet toTraits) {
    // 确保目标特性集与原始特性集不同。
    assert !rel.getTraitSet().equals(toTraits);
    assert toTraits.allSimple();

    // 确保关系节点已注册。
    RelSubset rel2 = ensureRegistered(rel, null);
    // 如果目标特性集已经存在，直接返回。
    if (rel2.getTraitSet().equals(toTraits)) {
      return rel2;
    }

    // 创建或获取目标特性集的子集。
    return rel2.set.getOrCreateSubset(rel.getCluster(), toTraits, true);
  }

  /**
   * 选择规划器的委托实现（通常返回自身）。
   *
   * @return 当前的规划器实例。
   */
  @Override
  public RelOptPlanner chooseDelegate() {
    return this;
  }


  /**
   * Finds the most efficient expression to implement the query given via
   * {@link org.apache.calcite.plan.RelOptPlanner#setRoot(org.apache.calcite.rel.RelNode)}.
   *
   * @return the most efficient RelNode tree found for implementing the given
   * query
   */
  @Override public RelNode findBestExp() {
    requireNonNull(root, "root");
    ensureRootConverters();
    registerMaterializations();

    ruleDriver.drive();

    if (LOGGER.isTraceEnabled()) {
      StringWriter sw = new StringWriter();
      final PrintWriter pw = new PrintWriter(sw);
      dump(pw);
      pw.flush();
      LOGGER.info(sw.toString());
    }
    dumpRuleAttemptsInfo();
    RelNode cheapest = root.buildCheapestPlan(this);
    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug(
          "Cheapest plan:\n{}", RelOptUtil.toString(cheapest, SqlExplainLevel.ALL_ATTRIBUTES));

      if (!provenanceMap.isEmpty()) {
        LOGGER.debug("Provenance:\n{}", Dumpers.provenance(provenanceMap, cheapest));
      }
    }
    return cheapest;
  }

  @Override public void checkCancel() {
    if (cancelFlag.get()) {
      throw new VolcanoTimeoutException();
    }
  }

  /** Ensures that the subset that is the root relational expression contains
   * converters to all other subsets in its equivalence set.
   *
   * <p>Thus the planner tries to find cheap implementations of those other
   * subsets, which can then be converted to the root. This is the only place
   * in the plan where explicit converters are required; elsewhere, a consumer
   * will be asking for the result in a particular convention, but the root has
   * no consumers. */
  @RequiresNonNull("root")
  void ensureRootConverters() {
    final Set<RelSubset> subsets = new HashSet<>();
    for (RelNode rel : root.getRels()) {
      if (rel instanceof AbstractConverter) {
        subsets.add((RelSubset) ((AbstractConverter) rel).getInput());
      }
    }
    for (RelSubset subset : root.set.subsets) {
      final ImmutableList<RelTrait> difference =
          root.getTraitSet().difference(subset.getTraitSet());
      if (difference.size() == 1 && subsets.add(subset)) {
        register(
            new AbstractConverter(subset.getCluster(), subset,
                difference.get(0).getTraitDef(), root.getTraitSet()),
            root);
      }
    }
  }

  /**
   * 注册一个 {@link RelNode}，并将其与等价类相关联。
   * 如果已存在等价的表达式，则不会重复注册。
   *
   * @param rel 要注册的关系表达式。
   * @param equivRel 与之等价的表达式（如果已知），可以为空。
   * @return 表达式所属的 {@link RelSubset}。
   */
  @Override
  public RelSubset register(RelNode rel, @Nullable RelNode equivRel) {
    // 确保 rel 不为空。
    assert !isRegistered(rel) : "pre: isRegistered(rel)";

    // 找到与 equivRel 对应的 RelSet（如果提供了 equivRel）。
    final RelSet set;
    if (equivRel == null) {
      set = null; // 如果没有等价表达式，则当前表达式需要创建一个新 RelSet。
    } else {
      // 验证 rel 和 equivRel 的行类型是否一致。
      final RelDataType relType = rel.getRowType();
      final RelDataType equivRelType = equivRel.getRowType();
      if (!RelOptUtil.areRowTypesEqual(relType, equivRelType, false)) {
        throw new IllegalArgumentException(
            RelOptUtil.getFullTypeDifferenceString(
                "rel rowtype", relType, "equiv rowtype", equivRelType));
      }

      // 确保等价表达式已注册。
      equivRel = ensureRegistered(equivRel, null);

      // 获取等价表达式的 RelSet。
      set = getSet(equivRel);
    }

    // 将表达式注册到指定的 RelSet 或创建新 RelSet。
    return registerImpl(rel, set);
  }

  /**
   * 确保一个表达式已被注册。
   * 如果表达式尚未注册，会将其注册到等价类。
   *
   * @param rel 要注册的表达式。
   * @param equivRel 等价表达式（可选）。
   * @return 表达式所属的 {@link RelSubset}。
   */
  @Override
  public RelSubset ensureRegistered(RelNode rel, @Nullable RelNode equivRel) {
    RelSubset result;

    // 如果表达式已经被注册，返回其对应的子集。
    final RelSubset subset = getSubset(rel);
    if (subset != null) {
      if (equivRel != null) {
        // 如果提供了等价表达式，合并其所属的 RelSet。
        final RelSubset equivSubset = getSubsetNonNull(equivRel);
        if (subset.set != equivSubset.set) {
          merge(equivSubset.set, subset.set); // 合并两个 RelSet。
        }
      }
      result = canonize(subset);
    } else {
      // 如果尚未注册，则注册表达式。
      result = register(rel, equivRel);
    }

    // 验证注册后的计划是否有效。
    if (LOGGER.isDebugEnabled()) {
      assert isValid(Litmus.THROW);
    }

    return result;
  }


  /**
   * Checks internal consistency.
   */
  protected boolean isValid(Litmus litmus) {
    RelNode root = getRoot();
    if (root == null) {
      return true;
    }

    RelMetadataQuery metaQuery = root.getCluster().getMetadataQuerySupplier().get();
    for (RelSet set : allSets) {
      if (set.equivalentSet != null) {
        return litmus.fail("set [{}] has been merged: it should not be in the list", set);
      }
      for (RelSubset subset : set.subsets) {
        if (subset.set != set) {
          return litmus.fail("subset [{}] is in wrong set [{}]",
              subset, set);
        }

        if (subset.best != null) {

          // Make sure best RelNode is valid
          if (!subset.set.rels.contains(subset.best)) {
            return litmus.fail("RelSubset [{}] does not contain its best RelNode [{}]",
                    subset, subset.best);
          }

          // Make sure bestCost is up-to-date
          try {
            RelOptCost bestCost = getCostOrInfinite(subset.best, metaQuery);
            if (!subset.bestCost.equals(bestCost)) {
              return litmus.fail("RelSubset [" + subset
                      + "] has wrong best cost "
                      + subset.bestCost + ". Correct cost is " + bestCost);
            }
          } catch (CyclicMetadataException e) {
            // ignore
          }
        }

        for (RelNode rel : subset.getRels()) {
          try {
            RelOptCost relCost = getCost(rel, metaQuery);
            if (relCost != null && relCost.isLt(subset.bestCost)) {
              return litmus.fail("rel [{}] has lower cost {} than "
                      + "best cost {} of subset [{}]",
                      rel, relCost, subset.bestCost, subset);
            }
          } catch (CyclicMetadataException e) {
            // ignore
          }
        }
      }
    }
    return litmus.succeed();
  }

  public void registerAbstractRelationalRules() {
    RelOptUtil.registerAbstractRelationalRules(this);
  }

  @Override public void registerSchema(RelOptSchema schema) {
    if (registeredSchemas.add(schema)) {
      try {
        schema.registerRules(this);
      } catch (Exception e) {
        throw new AssertionError("While registering schema " + schema, e);
      }
    }
  }

  /**
   * Sets whether this planner should consider rel nodes with Convention.NONE
   * to have infinite cost or not.
   *
   * @param infinite Whether to make none convention rel nodes infinite cost
   */
  public void setNoneConventionHasInfiniteCost(boolean infinite) {
    this.noneConventionHasInfiniteCost = infinite;
  }

  /**
   * Returns cost of a relation or infinite cost if the cost is not known.
   *
   * @param rel relation t
   * @param mq metadata query
   * @return cost of the relation or infinite cost if the cost is not known
   * @see org.apache.calcite.plan.volcano.RelSubset#bestCost
   */
  private RelOptCost getCostOrInfinite(RelNode rel, RelMetadataQuery mq) {
    RelOptCost cost = getCost(rel, mq);
    return cost == null ? infCost : cost;
  }

  @Override public @Nullable RelOptCost getCost(RelNode rel, RelMetadataQuery mq) {
    requireNonNull(rel, "rel");
    if (rel instanceof RelSubset) {
      return ((RelSubset) rel).bestCost;
    }
    if (noneConventionHasInfiniteCost
        && rel.getTraitSet().getTrait(ConventionTraitDef.INSTANCE) == Convention.NONE) {
      return costFactory.makeInfiniteCost();
    }
    RelOptCost cost = mq.getNonCumulativeCost(rel);
    if (cost == null) {
      return null;
    }
    if (!zeroCost.isLt(cost)) {
      // cost must be positive, so nudge it
      cost = costFactory.makeTinyCost();
    }
    for (RelNode input : rel.getInputs()) {
      RelOptCost inputCost = getCost(input, mq);
      if (inputCost == null) {
        return null;
      }
      cost = cost.plus(inputCost);
    }
    return cost;
  }

  /**
   * 获取一个表达式所属的子集（RelSubset）。
   *
   * @param rel 要查询的表达式。
   * @return 表达式所属的子集，如果未注册，则返回 null。
   */
  public @Nullable RelSubset getSubset(RelNode rel) {
    requireNonNull(rel, "rel");
    if (rel instanceof RelSubset) {
      return (RelSubset) rel; // 如果表达式本身就是一个子集，直接返回。
    } else {
      return mapRel2Subset.get(rel); // 查找映射表中的子集。
    }
  }

  /**
   * 获取一个表达式所属的子集（RelSubset），
   * 如果表达式未注册，则抛出异常。
   *
   * @param rel 要查询的表达式。
   * @return 表达式所属的子集。
   * @throws AssertionError 如果未找到子集。
   */
  @API(since = "1.26", status = API.Status.EXPERIMENTAL)
  public RelSubset getSubsetNonNull(RelNode rel) {
    return requireNonNull(getSubset(rel), () -> "Subset is not found for " + rel);
  }

  public @Nullable RelSubset getSubset(RelNode rel, RelTraitSet traits) {
    if ((rel instanceof RelSubset) && rel.getTraitSet().equals(traits)) {
      return (RelSubset) rel;
    }
    RelSet set = getSet(rel);
    if (set == null) {
      return null;
    }
    return set.getSubset(traits);
  }

  /**
   * 使用转换器将给定的关系表达式（RelNode）的特性集（TraitSet）更改为目标特性集。
   *
   * @param rel       要转换的关系表达式
   * @param toTraits  目标特性集
   * @return 转换后的关系表达式，如果转换失败则返回 null
   */
  @Nullable
  RelNode changeTraitsUsingConverters(RelNode rel, RelTraitSet toTraits) {
    // 获取关系表达式的当前特性集
    final RelTraitSet fromTraits = rel.getTraitSet();

    // 确保当前特性集的大小不小于目标特性集
    assert fromTraits.size() >= toTraits.size();

    // 是否允许使用无限代价的转换器
    final boolean allowInfiniteCostConverters =
        CalciteSystemProperty.ALLOW_INFINITE_COST_CONVERTERS.value();

    /**
     * 遍历目标特性集，逐步将当前特性集转换为目标特性集。
     * - 特性可能具有层级关系（例如分布特性可能影响排序特性）。
     * - 目标特性集可能比当前特性集少，未指定的特性保持不变。
     * - 如果目标特性集中包含 null 值，直接跳过该特性。
     */
    RelNode converted = rel;
    for (int i = 0; (converted != null) && (i < toTraits.size()); i++) {
      // 获取当前特性集和目标特性集中的特性
      RelTrait fromTrait = converted.getTraitSet().getTrait(i);
      final RelTraitDef traitDef = fromTrait.getTraitDef();
      RelTrait toTrait = toTraits.getTrait(i);

      // 如果目标特性为 null，跳过当前特性
      if (toTrait == null) {
        continue;
      }

      // 确保当前特性和目标特性具有相同的定义
      assert traitDef == toTrait.getTraitDef();

      // 如果当前特性已经满足目标特性，跳过转换
      if (fromTrait.satisfies(toTrait)) {
        continue;
      }

      // 使用特性定义的转换器将当前特性转换为目标特性
      RelNode convertedRel =
          traitDef.convert(
              this,
              converted,
              toTrait,
              allowInfiniteCostConverters);
      if (convertedRel != null) {
        // 确保转换后的特性满足目标特性
        assert castNonNull(convertedRel.getTraitSet().getTrait(traitDef)).satisfies(toTrait);
        // 将转换后的表达式注册到规划器
        register(convertedRel, converted);
      }

      // 更新已转换的表达式
      converted = convertedRel;
    }

    // 确保最终转换后的特性集满足目标特性集
    if (converted != null) {
      assert converted.getTraitSet().satisfies(toTraits);
    }

    return converted;
  }

  /**
   * 将指定的关系表达式标记为已修剪。
   * @param rel 要修剪的关系表达式
   */
  @Override
  public void prune(RelNode rel) {
    prunedNodes.add(rel);
  }

  /**
   * 将规划器的内部状态输出到指定的打印流中。
   * @param pw 打印流
   */
  public void dump(PrintWriter pw) {
    pw.println("Root: " + root);
    pw.println("Original rel:");

    if (originalRoot != null) {
      // 输出根节点的详细信息
      originalRoot.explain(
          new RelWriterImpl(pw, SqlExplainLevel.ALL_ATTRIBUTES, false));
    }

    try {
      // 如果启用了输出集合的配置，则打印所有集合信息
      if (CalciteSystemProperty.DUMP_SETS.value()) {
        pw.println();
        pw.println("Sets:");
        Dumpers.dumpSets(this, pw);
      }
      // 如果启用了输出 Graphviz 的配置，则打印 Graphviz 格式的信息
      if (CalciteSystemProperty.DUMP_GRAPHVIZ.value()) {
        pw.println();
        pw.println("Graphviz:");
        Dumpers.dumpGraphviz(this, pw);
      }
    } catch (Exception | AssertionError e) {
      // 如果输出过程中发生异常，打印错误信息
      pw.println("Error when dumping plan state: \n" + e);
    }
  }

  /**
   * 将规划器的状态以 Graphviz 格式输出为字符串。
   * @return 以 Graphviz 格式表示的规划器状态
   */
  public String toDot() {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    Dumpers.dumpGraphviz(this, pw);
    pw.flush();
    return sw.toString();
  }

  /**
   * 重新计算指定关系表达式的摘要（digest）。
   *
   * <p>由于摘要包含子表达式的标识符，如果子表达式被重命名（例如子集被合并），
   * 则需要重新计算摘要。
   *
   * @param rel 要重新计算摘要的关系表达式
   */
  void rename(RelNode rel) {
    String oldDigest = "";
    if (LOGGER.isTraceEnabled()) {
      oldDigest = rel.getDigest(); // 保存旧的摘要信息用于调试
    }

    // 修复关系表达式的输入并重新计算摘要
    if (fixUpInputs(rel)) {
      final RelDigest newDigest = rel.getRelDigest(); // 获取新的摘要
      LOGGER.trace("Rename #{} from '{}' to '{}'", rel.getId(), oldDigest, newDigest);

      // 更新摘要到表达式的映射
      final RelNode equivRel = mapDigestToRel.put(newDigest, rel);
      if (equivRel != null) {
        // 如果已存在具有相同摘要的等价表达式，则将其恢复
        assert equivRel != rel;
        LOGGER.trace("After renaming rel#{} it is now equivalent to rel#{}",
            rel.getId(), equivRel.getId());

        mapDigestToRel.put(newDigest, equivRel);
        checkPruned(equivRel, rel); // 检查修剪状态

        RelSubset equivRelSubset = getSubsetNonNull(equivRel);

        // 移除子表达式的反向链接
        for (RelNode input : rel.getInputs()) {
          ((RelSubset) input).set.parents.remove(rel);
        }

        // 将表达式从当前子集中移除
        final RelSubset subset = requireNonNull(mapRel2Subset.put(rel, equivRelSubset));
        boolean existed = subset.set.rels.remove(rel);
        checkArgument(existed, "rel was not known to its set");

        final RelSubset equivSubset = getSubsetNonNull(equivRel);
        for (RelSubset s : subset.set.subsets) {
          if (s.best == rel) {
            s.best = equivRel;
            // 如果最佳表达式发生变化，传播成本改进
            propagateCostImprovements(equivRel);
          }
        }

        // 如果等价表达式属于不同的子集，合并两个集合
        if (equivSubset != subset) {
          assert equivSubset.getTraitSet().equals(subset.getTraitSet());
          assert equivSubset.set != subset.set;
          merge(equivSubset.set, subset.set);
        }
      }
    }
  }


  /**
   * 检查某个关系表达式是否降低了其所属子集的成本，
   * 如果降低了，则将新的成本传播到其父关系表达式中。
   *
   * @param rel 成本降低的关系表达式
   */
  void propagateCostImprovements(RelNode rel) {
    // 获取元数据查询接口，用于计算表达式的成本
    RelMetadataQuery mq = rel.getCluster().getMetadataQuery();

    // 用于存储传播中的关系表达式及其成本的映射
    Map<RelNode, RelOptCost> propagateRels = new HashMap<>();

    // 优先队列，用于按照成本顺序处理关系表达式
    PriorityQueue<RelNode> propagateHeap = new PriorityQueue<>((o1, o2) -> {
      RelOptCost c1 = propagateRels.get(o1);
      RelOptCost c2 = propagateRels.get(o2);
      if (c1 == null) {
        return c2 == null ? 0 : -1;
      }
      if (c2 == null) {
        return 1;
      }
      if (c1.equals(c2)) {
        return 0;
      } else if (c1.isLt(c2)) {
        return -1;
      }
      return 1;
    });

    // 初始化优先队列，将传入的关系表达式及其成本放入
    propagateRels.put(rel, getCostOrInfinite(rel, mq));
    propagateHeap.offer(rel);

    RelNode relNode;
    // 遍历优先队列，逐个处理关系表达式
    while ((relNode = propagateHeap.poll()) != null) {
      // 获取当前节点的成本
      RelOptCost cost = requireNonNull(propagateRels.get(relNode), "propagateRels.get(relNode)");

      // 遍历当前节点所属集合中的所有子集
      for (RelSubset subset : getSubsetNonNull(relNode).set.subsets) {
        // 如果当前节点的特性不满足子集的特性要求，跳过
        if (!relNode.getTraitSet().satisfies(subset.getTraitSet())) {
          continue;
        }

        // 如果当前节点不是子集的最佳表达式且成本没有降低，跳过
        if (relNode != subset.best && !cost.isLt(subset.bestCost)) {
          continue;
        }

        /**
         * 更新子集的最佳成本和最佳表达式。
         * 根据测试，有时成本可能会增加，因此需要执行更新。
         */
        if (relNode == subset.best && cost.equals(subset.bestCost)) {
          continue;
        }

        subset.timestamp++;
        LOGGER.trace("Subset cost changed: subset [{}] cost was {} now {}",
            subset, subset.bestCost, cost);

        subset.bestCost = cost;
        subset.best = relNode;

        // 清除子集的元数据缓存
        mq.clearCache(subset);

        // 将成本传播到子集的父节点
        for (RelNode parent : subset.getParents()) {
          mq.clearCache(parent);
          RelOptCost newCost = getCostOrInfinite(parent, mq);
          RelOptCost existingCost = propagateRels.get(parent);
          if (existingCost == null || newCost.isLt(existingCost)) {
            propagateRels.put(parent, newCost);
            if (existingCost != null) {
              // 如果成本降低，强制调整队列顺序
              propagateHeap.remove(parent);
            }
            propagateHeap.offer(parent);
          }
        }
      }
    }
  }

  /**
   * 重新注册已注册的 {@link RelNode} 到新的 {@link RelSet} 中。
   *
   * @param set 新的关系集合
   * @param rel 要重新注册的关系表达式
   */
  void reregister(RelSet set, RelNode rel) {
    // 检查是否存在等价的关系表达式
    RelNode equivRel = mapDigestToRel.get(rel.getRelDigest());
    if (equivRel != null && equivRel != rel) {
      // 确保等价表达式的类型和特性集与当前表达式一致
      assert equivRel.getClass() == rel.getClass();
      assert equivRel.getTraitSet().equals(rel.getTraitSet());

      // 如果等价表达式已被修剪，则将当前表达式也标记为已修剪
      checkPruned(equivRel, rel);
      return;
    }

    // 将关系表达式添加到指定集合中
    if (!prunedNodes.contains(rel)) {
      addRelToSet(rel, set);
    }
  }

  /**
   * 检查重复的关系表达式是否已被修剪，如果已被修剪，则标记当前关系表达式为已修剪。
   *
   * @param rel 当前关系表达式
   * @param duplicateRel 与当前表达式重复的表达式
   */
  private void checkPruned(RelNode rel, RelNode duplicateRel) {
    if (prunedNodes.contains(duplicateRel)) {
      prunedNodes.add(rel);
    }
  }

  /**
   * 如果根子集与其他子集合并，找到新的根子集。
   */
  @RequiresNonNull("root")
  void canonize() {
    root = canonize(root);
  }

  /**
   * 如果子集有一个或多个等价子集（例如集合已与另一个集合合并），
   * 返回等价类中的主子集。
   *
   * @param subset 子集
   * @return 等价类的主子集
   */
  private static RelSubset canonize(final RelSubset subset) {
    RelSet set = subset.set;
    if (set.equivalentSet == null) {
      return subset;
    }
    do {
      set = set.equivalentSet;
    } while (set.equivalentSet != null);
    return set.getOrCreateSubset(
        subset.getCluster(), subset.getTraitSet(), subset.isRequired());
  }


  /**
   * 触发与给定关系表达式匹配的所有规则。
   *
   * @param rel 刚刚创建的关系表达式（或可能来自队列）
   */
  void fireRules(RelNode rel) {
    // 遍历与关系表达式的类匹配的所有规则操作数
    for (RelOptRuleOperand operand : classOperands.get(rel.getClass())) {
      // 如果操作数匹配关系表达式
      if (operand.matches(rel)) {
        // 创建一个延迟的规则调用
        final VolcanoRuleCall ruleCall;
        ruleCall = new DeferringRuleCall(this, operand);
        // 尝试匹配规则
        ruleCall.match(rel);
      }
    }
  }

  /**
   * 修正关系表达式的输入，将其更新为最新的等价子集。
   *
   * @param rel 要修正的关系表达式
   * @return 如果输入发生变化，则返回 true；否则返回 false
   */
  private boolean fixUpInputs(RelNode rel) {
    List<RelNode> inputs = rel.getInputs(); // 获取当前表达式的输入
    List<RelNode> newInputs = new ArrayList<>(inputs.size()); // 存储更新后的输入
    int changeCount = 0; // 记录输入变化的次数

    // 遍历每个输入表达式
    for (RelNode input : inputs) {
      assert input instanceof RelSubset; // 确保输入是 RelSubset 类型
      final RelSubset subset = (RelSubset) input;
      RelSubset newSubset = canonize(subset); // 获取最新的等价子集
      newInputs.add(newSubset);
      if (newSubset != subset) { // 如果子集发生了变化
        if (subset.set != newSubset.set) {
          subset.set.parents.remove(rel); // 从旧子集的父节点列表中移除当前表达式
          newSubset.set.parents.add(rel); // 将当前表达式添加到新子集的父节点列表中
        }
        changeCount++;
      }
    }

    if (changeCount > 0) { // 如果输入发生了变化
      RelMdUtil.clearCache(rel); // 清除元数据缓存
      RelNode removed = mapDigestToRel.remove(rel.getRelDigest()); // 移除旧的表达式摘要
      assert removed == rel;
      for (int i = 0; i < inputs.size(); i++) {
        rel.replaceInput(i, newInputs.get(i)); // 替换为更新后的输入
      }
      rel.recomputeDigest(); // 重新计算摘要
      return true;
    }
    return false;
  }

  /**
   * 合并两个关系集合（RelSet）。
   *
   * @param set1 第一个关系集合
   * @param set2 第二个关系集合
   * @return 合并后的关系集合
   */
  private RelSet merge(RelSet set1, RelSet set2) {
    assert set1 != set2 : "pre: set1 != set2"; // 确保两个集合不同

    // 找到每个集合的等价根
    set1 = equivRoot(set1);
    set2 = equivRoot(set2);

    // 如果两个集合已等价，无需合并
    if (set2 == set1) {
      return set1;
    }

    // 判断是否需要交换集合以确保合并顺序
    final boolean swap;
    final Set<RelSet> childrenOf1 = set1.getChildSets(this);
    final Set<RelSet> childrenOf2 = set2.getChildSets(this);
    final boolean set2IsParentOfSet1 = childrenOf2.contains(set1);
    final boolean set1IsParentOfSet2 = childrenOf1.contains(set2);
    if (set2IsParentOfSet1 && set1IsParentOfSet2) {
      // 两个集合互为父子，合并较小的集合到较大的集合中
      swap = isSmaller(set1, set2);
    } else if (set2IsParentOfSet1) {
      swap = false; // 将 set2 合并到 set1
    } else if (set1IsParentOfSet2) {
      swap = true; // 将 set1 合并到 set2
    } else {
      swap = isSmaller(set1, set2); // 默认合并较小的集合到较大的集合
    }

    if (swap) {
      RelSet t = set1;
      set1 = set2;
      set2 = t;
    }

    // 执行合并操作
    set1.mergeWith(this, set2);

    if (root == null) {
      throw new IllegalStateException("root must not be null");
    }

    // 如果合并的集合是根集合，则更新根
    if (set2 == getSet(root)) {
      root = set1.getOrCreateSubset(root.getCluster(), root.getTraitSet(), root.isRequired());
      ensureRootConverters();
    }

    // 通知规则驱动器集合已合并
    if (ruleDriver != null) {
      ruleDriver.onSetMerged(set1);
    }

    return set1;
  }

  /**
   * 判断集合 set1 是否比集合 set2 更小或更年轻。
   *
   * @param set1 第一个集合
   * @param set2 第二个集合
   * @return 如果 set1 更小或更年轻，返回 true；否则返回 false
   */
  private static boolean isSmaller(RelSet set1, RelSet set2) {
    if (set1.parents.size() != set2.parents.size()) {
      return set1.parents.size() < set2.parents.size(); // 根据父节点数量比较
    }
    if (set1.rels.size() != set2.rels.size()) {
      return set1.rels.size() < set2.rels.size(); // 根据表达式数量比较
    }
    return set1.id > set2.id; // 根据 ID 比较，ID 较大的集合更年轻
  }

  /**
   * 获取集合的等价根。
   *
   * @param s 关系集合
   * @return 集合的等价根
   */
  static RelSet equivRoot(RelSet s) {
    RelSet p = s; // 用于检测循环的指针
    while (s.equivalentSet != null) {
      p = forward2(s, p); // 前进两步检测循环
      s = s.equivalentSet;
    }
    return s;
  }

  /**
   * 向前移动两步，同时检测循环。
   *
   * @param s 当前集合
   * @param p 循环检测指针
   * @return 移动后的指针
   */
  private static @Nullable RelSet forward2(RelSet s, @Nullable RelSet p) {
    p = forward1(s, p);
    p = forward1(s, p);
    return p;
  }

  /** Moves forward one link, checking for a cycle. */
  private static @Nullable RelSet forward1(RelSet s, @Nullable RelSet p) {
    if (p != null) {
      p = p.equivalentSet;
      if (p == s) {
        throw new AssertionError("cycle in equivalence tree");
      }
    }
    return p;
  }

  /**
   * 注册一个新的关系表达式 <code>rel</code> 并触发与其匹配的规则。
   * 如果 <code>set</code> 不为 null，则将该表达式添加到指定的等价集合中。
   * 如果已存在等价的表达式，则不会重复注册，并直接返回已存在的子集。
   *
   * @param rel 要注册的关系表达式，必须是 {@link RelSubset} 或未注册的 {@link RelNode}
   * @param set 表示关系表达式所属的集合，可以为 null
   * @return 表达式所属的等价集合 {@link RelSubset}
   */
  private RelSubset registerImpl(RelNode rel, @Nullable RelSet set) {
    // 如果 rel 已经是一个 RelSubset 类型，直接将其注册到对应的集合
    if (rel instanceof RelSubset) {
      return registerSubset(set, (RelSubset) rel);
    }

    // 确保表达式尚未被注册
    assert !isRegistered(rel) : "该表达式已注册: " + rel;

    // 确保该表达式属于当前的规划器
    if (rel.getCluster().getPlanner() != this) {
      throw new AssertionError("关系表达式 " + rel + " 不属于当前规划器。");
    }

    // 验证表达式的调用约定是否符合要求
    final RelTraitSet traits = rel.getTraitSet();
    final Convention convention = requireNonNull(traits.getTrait(ConventionTraitDef.INSTANCE));
    if (!convention.getInterface().isInstance(rel) && !(rel instanceof Converter)) {
      throw new AssertionError("关系表达式 " + rel + " 的调用约定 " + convention
          + " 不符合其要求的接口 '" + convention.getInterface() + "'");
    }

    // 检查表达式的特性数量是否正确
    if (traits.size() != traitDefs.size()) {
      throw new AssertionError("关系表达式 " + rel + " 的特性数量不正确: " + traits.size()
          + " != " + traitDefs.size());
    }

    // 确保表达式的子节点已经注册
    rel = rel.onRegister(this);

    // 记录表达式的来源（规则调用可能为空）
    final VolcanoRuleCall ruleCall = ruleCallStack.peek();
    if (ruleCall == null) {
      provenanceMap.put(rel, Provenance.EMPTY);
    } else {
      provenanceMap.put(rel, new RuleProvenance(ruleCall.rule, ImmutableList.copyOf(ruleCall.rels), ruleCall.id));
    }

    // 检查是否已存在等价表达式
    RelDigest digest = rel.getRelDigest();
    RelNode equivExp = mapDigestToRel.get(digest);
    if (equivExp != null) {
      if (equivExp == rel) {
        // 如果等价表达式已经存在，直接返回其对应的子集
        return getSubsetNonNull(equivExp);
      } else {
        // 如果表达式行类型不匹配，抛出异常
        if (!RelOptUtil.areRowTypesEqual(equivExp.getRowType(), rel.getRowType(), false)) {
          throw new IllegalArgumentException(RelOptUtil.getFullTypeDifferenceString(
              "等价表达式行类型", equivExp.getRowType(), "当前表达式行类型", rel.getRowType()));
        }
        checkPruned(equivExp, rel);
        RelSet equivSet = getSet(equivExp);
        if (equivSet != null) {
          LOGGER.trace("注册: rel#{} 等价于 {}", rel.getId(), equivExp);
          return registerSubset(set, getSubsetNonNull(equivExp));
        }
      }
    }

    // 如果表达式是转换器，将其放入与子节点相同的集合中
    if (rel instanceof Converter) {
      final RelNode input = ((Converter) rel).getInput();
      final RelSet childSet = castNonNull(getSet(input));
      if (set != null && set != childSet && set.equivalentSet == null) {
        LOGGER.trace("注册 #{} {}（合并集合，因为它是转换器）", rel.getId(), rel.getRelDigest());
        merge(set, childSet);

        // 检查当前表达式是否已等价于其他已注册的表达式
        if (fixUpInputs(rel)) {
          digest = rel.getRelDigest();
          RelNode equivRel = mapDigestToRel.get(digest);
          if (equivRel != null && equivRel != rel) {
            set.obliterateRelNode(rel);
            return getSubsetNonNull(equivRel);
          }
        }
      } else {
        set = childSet;
      }
    }

    // 如果没有提供集合，则创建一个新的集合
    if (set == null) {
      set = new RelSet(nextSetId++,
          Util.minus(RelOptUtil.getVariablesSet(rel), rel.getVariablesSet()),
          RelOptUtil.getVariablesUsed(rel));
      this.allSets.add(set);
    }

    // 确保集合被更新到最新的等价集合
    while (set.equivalentSet != null) {
      set = set.equivalentSet;
    }

    // 注册与表达式相关的规则
    registerClass(rel);

    final int subsetBeforeCount = set.subsets.size();
    RelSubset subset = addRelToSet(rel, set);

    // 如果表达式已被注册到 map 中，则直接返回
    final RelNode xx = mapDigestToRel.putIfAbsent(digest, rel);
    if (xx != null) {
      return subset;
    }

    // 将当前表达式添加为其子节点的父节点
    for (RelNode input : rel.getInputs()) {
      RelSubset childSubset = (RelSubset) input;
      childSubset.set.parents.add(rel);
    }

    // 触发与表达式匹配的规则
    fireRules(rel);

    // 如果创建了新的子集或触发了规则，则触发子集的规则
    if (set.subsets.size() > subsetBeforeCount || subset.triggerRule) {
      fireRules(subset);
    }

    return subset;
  }

  /**
   * 将表达式添加到集合中，同时更新映射关系和成本改进。
   *
   * @param rel 表达式
   * @param set 目标集合
   * @return 表达式所属的子集
   */
  private RelSubset addRelToSet(RelNode rel, RelSet set) {
    RelSubset subset = set.add(rel);
    mapRel2Subset.put(rel, subset);

    // 改进子集的成本
    try {
      propagateCostImprovements(rel);
    } catch (CyclicMetadataException e) {
      // 忽略元数据异常
    }

    // 通知规则驱动器
    if (ruleDriver != null) {
      ruleDriver.onProduce(rel, subset);
    }

    return subset;
  }

  /**
   * 注册子集，并在需要时合并集合。
   *
   * @param set 目标集合
   * @param subset 子集
   * @return 子集的最新状态
   */
  private RelSubset registerSubset(@Nullable RelSet set, RelSubset subset) {
    if (set != null && set != subset.set && set.equivalentSet == null) {
      LOGGER.trace("注册 #{} {}, 并合并集合", subset.getId(), subset);
      merge(set, subset.set);
    }
    return canonize(subset);
  }


  // implement RelOptPlanner
  @Deprecated // to be removed before 2.0
  @Override public void registerMetadataProviders(List<RelMetadataProvider> list) {
    list.add(0, new VolcanoRelMetadataProvider());
  }

  // implement RelOptPlanner
  @Deprecated // to be removed before 2.0
  @Override public long getRelMetadataTimestamp(RelNode rel) {
    RelSubset subset = getSubset(rel);
    if (subset == null) {
      return 0;
    } else {
      return subset.timestamp;
    }
  }

  /**
   * Normalizes references to subsets within the string representation of a
   * plan.
   *
   * <p>This is useful when writing tests: it helps to ensure that tests don't
   * break when an extra rule is introduced that generates a new subset and
   * causes subsequent subset numbers to be off by one.
   *
   * <p>For example,
   *
   * <blockquote>
   * FennelAggRel.FENNEL_EXEC(child=Subset#17.FENNEL_EXEC,groupCount=1,
   * EXPR$1=COUNT())<br>
   * &nbsp;&nbsp;FennelSortRel.FENNEL_EXEC(child=Subset#2.FENNEL_EXEC,
   * key=[0], discardDuplicates=false)<br>
   * &nbsp;&nbsp;&nbsp;&nbsp;FennelCalcRel.FENNEL_EXEC(
   * child=Subset#4.FENNEL_EXEC, expr#0..8={inputs}, expr#9=3456,
   * DEPTNO=$t7, $f0=$t9)<br>
   * &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;MockTableImplRel.FENNEL_EXEC(
   * table=[CATALOG, SALES, EMP])</blockquote>
   *
   * <p>becomes
   *
   * <blockquote>
   * FennelAggRel.FENNEL_EXEC(child=Subset#{0}.FENNEL_EXEC, groupCount=1,
   * EXPR$1=COUNT())<br>
   * &nbsp;&nbsp;FennelSortRel.FENNEL_EXEC(child=Subset#{1}.FENNEL_EXEC,
   * key=[0], discardDuplicates=false)<br>
   * &nbsp;&nbsp;&nbsp;&nbsp;FennelCalcRel.FENNEL_EXEC(
   * child=Subset#{2}.FENNEL_EXEC,expr#0..8={inputs},expr#9=3456,DEPTNO=$t7,
   * $f0=$t9)<br>
   * &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;MockTableImplRel.FENNEL_EXEC(
   * table=[CATALOG, SALES, EMP])</blockquote>
   *
   * <p>Returns null if and only if {@code plan} is null.
   *
   * @param plan Plan
   * @return Normalized plan
   */
  public static @PolyNull String normalizePlan(@PolyNull String plan) {
    if (plan == null) {
      return null;
    }
    final Pattern poundDigits = Pattern.compile("Subset#[0-9]+\\.");
    int i = 0;
    while (true) {
      final Matcher matcher = poundDigits.matcher(plan);
      if (!matcher.find()) {
        return plan;
      }
      final String token = matcher.group(); // e.g. "Subset#23."
      plan = plan.replace(token, "Subset#{" + i++ + "}.");
    }
  }

  /**
   * Sets whether this planner is locked. A locked planner does not accept
   * new rules. {@link #addRule(org.apache.calcite.plan.RelOptRule)} will do
   * nothing and return false.
   *
   * @param locked Whether planner is locked
   */
  public void setLocked(boolean locked) {
    this.locked = locked;
  }

  /**
   * 判断一个规则是否是逻辑规则。
   *
   * @param rel 指定的关系节点（RelNode）
   * @return 如果是逻辑节点，返回 true；否则返回 false
   */
  @API(since = "1.24", status = API.Status.EXPERIMENTAL)
  public boolean isLogical(RelNode rel) {
    // 判断条件：
    // 1. 关系节点不是 PhysicalNode 的实例；
    // 2. 该节点的 Convention 不等于根节点的 Convention。
    return !(rel instanceof PhysicalNode)
        && rel.getConvention() != rootConvention;
  }

  /**
   * 检查一个规则匹配是否是替换规则匹配。
   *
   * @param match 要检查的规则匹配对象
   * @return 如果规则匹配是一个替换规则匹配，则返回 true；否则返回 false
   */
  @API(since = "1.24", status = API.Status.EXPERIMENTAL)
  protected boolean isSubstituteRule(VolcanoRuleCall match) {
    // 判断规则是否为 SubstitutionRule 类型
    return match.getRule() instanceof SubstitutionRule;
  }

  /**
   * 检查一个规则匹配是否是转换规则匹配。
   *
   * @param match 要检查的规则匹配对象
   * @return 如果规则匹配是一个转换规则匹配，则返回 true；否则返回 false
   */
  @API(since = "1.24", status = API.Status.EXPERIMENTAL)
  protected boolean isTransformationRule(VolcanoRuleCall match) {
    // 判断规则是否为 TransformationRule 类型
    return match.getRule() instanceof TransformationRule;
  }

  /**
   * 获取关系操作符的下界成本。
   *
   * @param rel 关系节点（RelNode）
   * @return 给定关系节点的下界成本。如果无法获取下界成本，返回 zeroCost
   */
  @API(since = "1.24", status = API.Status.EXPERIMENTAL)
  protected RelOptCost getLowerBound(RelNode rel) {
    // 获取元数据查询对象
    RelMetadataQuery mq = rel.getCluster().getMetadataQuery();
    // 从元数据中查询关系节点的下界成本
    RelOptCost lowerBound = mq.getLowerBoundCost(rel, this);
    // 如果未能获取到下界成本，则返回默认值 zeroCost
    if (lowerBound == null) {
      return zeroCost;
    }
    return lowerBound;
  }

  /**
   * 获取其输入的上界成本。
   * 允许用户覆盖此方法，因为某些实现可能对某些 RelNodes（如 Spool）有不同的成本模型。
   *
   * @param mExpr 关系表达式节点
   * @param upperBound 输入的初始上界成本
   * @return 更新后的上界成本
   */
  @API(since = "1.24", status = API.Status.EXPERIMENTAL)
  protected RelOptCost upperBoundForInputs(
      RelNode mExpr, RelOptCost upperBound) {
    // 如果上界成本不是无穷大
    if (!upperBound.isInfinite()) {
      // 获取节点的非累积成本
      RelOptCost rootCost = mExpr.getCluster()
          .getMetadataQuery().getNonCumulativeCost(mExpr);
      // 如果非累积成本有效且不是无穷大，则用上界减去非累积成本
      if (rootCost != null && !rootCost.isInfinite()) {
        return upperBound.minus(rootCost);
      }
    }
    // 返回原始上界
    return upperBound;
  }


  //~ Inner Classes ----------------------------------------------------------

  /**
   * 一个延迟执行规则调用的类。
   * 与 {@link RelOptRuleCall} 不同，{@link RelOptRuleCall} 在找到匹配时立即执行规则，
   * 而 <code>DeferringRuleCall</code> 会创建一个 {@link VolcanoRuleMatch}，该匹配可以在稍后调用。
   */
  private static class DeferringRuleCall extends VolcanoRuleCall {

    /**
     * 构造函数，初始化延迟规则调用。
     *
     * @param planner 关联的 VolcanoPlanner 对象，用于管理规则的应用。
     * @param operand 当前规则操作数，用于匹配的条件。
     */
    DeferringRuleCall(
        VolcanoPlanner planner,
        RelOptRuleOperand operand) {
      super(planner, operand);
    }

    /**
     * 重写基类方法。在找到匹配时，不立即执行规则，而是创建一个
     * {@link VolcanoRuleMatch} 对象，将匹配信息存储到规则队列中。
     */
    @Override
    protected void onMatch() {
      // 创建一个 VolcanoRuleMatch 实例，包含匹配的信息
      final VolcanoRuleMatch match =
          new VolcanoRuleMatch(
              volcanoPlanner, // 当前的规划器实例
              getOperand0(),  // 根操作数
              rels,           // 匹配的关系节点列表
              nodeInputs);    // 匹配的输入节点列表

      // 将匹配信息加入到规划器的规则队列中，等待后续执行
      volcanoPlanner.ruleDriver.getRuleQueue().addMatch(match);
    }
  }

  /**
   * 表示 {@link RelNode} 的来源信息。
   */
  abstract static class Provenance {
    /**
     * 一个空的来源信息，表示无法追溯来源的 RelNode。
     */
    public static final Provenance EMPTY = new UnknownProvenance();
  }

  /**
   * 表示一个无法确定来源的 {@link RelNode}。
   * 这种节点可能是手动创建的，或者通过 SQL 转换为 RelNode 时产生的。
   */
  private static class UnknownProvenance extends Provenance {
  }

  /**
   * 表示一个通过复制另一个 {@link RelNode} 直接生成的 {@link RelNode}。
   */
  static class DirectProvenance extends Provenance {
    // 来源的 RelNode 节点
    final RelNode source;

    /**
     * 构造函数，初始化来源信息。
     *
     * @param source 来源的关系节点
     */
    DirectProvenance(RelNode source) {
      this.source = source;
    }
  }

  /**
   * 表示一个通过规则执行生成的 {@link RelNode}。
   */
  static class RuleProvenance extends Provenance {
    // 触发生成该节点的规则
    final RelOptRule rule;
    // 规则匹配时的关系节点列表
    final ImmutableList<RelNode> rels;
    // 规则调用的唯一标识符
    final int callId;

    /**
     * 构造函数，初始化规则来源信息。
     *
     * @param rule 触发生成节点的规则
     * @param rels 规则匹配时涉及的关系节点列表
     * @param callId 规则调用的唯一标识符
     */
    RuleProvenance(RelOptRule rule, ImmutableList<RelNode> rels, int callId) {
      this.rule = rule;
      this.rels = rels;
      this.callId = callId;
    }
  }

}
