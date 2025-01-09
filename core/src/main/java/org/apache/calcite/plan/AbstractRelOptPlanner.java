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

import org.apache.calcite.plan.volcano.RelSubset;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.metadata.RelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexExecutor;
import org.apache.calcite.sql2rel.RelDecorrelator;
import org.apache.calcite.util.CancelFlag;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;
import org.apache.calcite.util.trace.CalciteTrace;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.initialization.qual.UnknownInitialization;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;
import org.slf4j.Logger;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static org.apache.calcite.util.Static.RESOURCE;

import static java.util.Objects.requireNonNull;

/**
 * {@link RelOptPlanner} 接口的抽象基类。
 * 提供通用功能，用于实现具体的优化器，包括规则管理、监听器处理和取消检查等。
 */
public abstract class AbstractRelOptPlanner implements RelOptPlanner {
  //~ 静态字段/初始化器 --------------------------------------------------------

  /**
   * 用于记录规则尝试信息的日志器。
   * 只有在启用调试级别日志时才会输出相关信息。
   */
  private static final Logger RULE_ATTEMPTS_LOGGER = CalciteTrace.getRuleAttemptsTracer();

  //~ 实例字段 --------------------------------------------------------

  /**
   * 将规则描述映射到规则实例，确保规则描述唯一性。
   * 使用 LinkedHashMap 保持规则插入顺序。
   */
  protected final Map<String, RelOptRule> mapDescToRule = new LinkedHashMap<>();

  /**
   * 成本工厂，用于生成和比较规则的执行成本。
   */
  protected final RelOptCostFactory costFactory;

  /**
   * 多播监听器，用于事件广播通知多个监听器。
   * 支持动态添加和移除监听器。
   */
  private @MonotonicNonNull MulticastRelOptListener listener;

  /**
   * 用于统计规则尝试次数的监听器，仅在调试模式下启用。
   */
  private @MonotonicNonNull RuleAttemptsListener ruleAttemptsListener;

  /**
   * 用于排除特定规则的正则表达式模式。
   * 如果匹配该模式的规则则会被排除。
   */
  private @Nullable Pattern ruleDescExclusionFilter;

  /**
   * 用于检查是否需要取消优化过程的标志。
   * 通过原子布尔值实现线程安全。
   */
  protected final AtomicBoolean cancelFlag;

  /**
   * 已注册的关系表达式类的集合。
   * 用于跟踪优化器中使用的所有关系表达式类型。
   */
  private final Set<Class<? extends RelNode>> classes = new HashSet<>();

  /**
   * 已注册的约定集合。
   * 每个约定代表一组特定的规则和约束。
   */
  private final Set<Convention> conventions = new HashSet<>();

  /**
   * 外部上下文对象，永不为空。
   * 提供优化器运行时的配置信息。
   */
  protected final Context context;

  /**
   * 执行器，用于评估表达式或查询计划。
   * 可以为空，如果为空则表示未设置执行器。
   */
  private @Nullable RexExecutor executor;

  /**
   * 去关联器，用于优化查询中的子查询。
   * 在某些查询场景下，可以减少子查询的冗余计算。
   */
  private @Nullable RelDecorrelator decorrelator;

  //~ 构造函数 -----------------------------------------------------------

  /**
   * 创建一个 AbstractRelOptPlanner 实例。
   *
   * @param costFactory 成本工厂，用于生成和比较规则执行成本。
   * @param context 外部上下文对象，可以为空。如果为空，则会使用默认的空上下文。
   */
  protected AbstractRelOptPlanner(RelOptCostFactory costFactory,
      @Nullable Context context) {
    // 确保成本工厂不为空，否则抛出异常。
    this.costFactory = requireNonNull(costFactory, "costFactory");

    // 如果上下文为空，则使用默认的空上下文。
    if (context == null) {
      context = Contexts.empty();
    }
    this.context = context;

    // 初始化取消标志。如果上下文中包含取消标志，则使用该标志；否则创建一个新的原子布尔值。
    this.cancelFlag =
        context.maybeUnwrap(CancelFlag.class)
            .map(flag -> flag.atomicBoolean)
            .orElseGet(AtomicBoolean::new);

    // 添加抽象的 RelNode 类。虽然这些类型不会被注册为具体的关系节点，
    // 但某些操作数可能会使用它们。
    classes.add(RelNode.class);
    classes.add(RelSubset.class);

    // 如果规则尝试记录器启用了调试模式，则创建一个规则尝试监听器并添加到监听器列表中。
    if (RULE_ATTEMPTS_LOGGER.isDebugEnabled()) {
      this.ruleAttemptsListener = new RuleAttemptsListener();
      addListener(this.ruleAttemptsListener);
    }

    // 默认添加一个规则事件记录器，用于记录规则相关事件。
    addListener(new RuleEventLogger());
  }

//~ 方法 ----------------------------------------------------------------

  /**
   * 清除优化器的状态。此方法是一个空实现，供子类覆盖。
   */
  @Override
  public void clear() {}

  /**
   * 获取上下文对象。
   *
   * @return 当前优化器的上下文对象。
   */
  @Override
  public Context getContext() {
    return context;
  }

  /**
   * 获取成本工厂对象。
   *
   * @return 当前优化器使用的成本工厂。
   */
  @Override
  public RelOptCostFactory getCostFactory() {
    return costFactory;
  }

  /**
   * （已弃用）设置取消标志。此方法被忽略，无实际操作。
   *
   * @param cancelFlag 取消标志。
   */
  @SuppressWarnings("deprecation")
  @Override
  public void setCancelFlag(CancelFlag cancelFlag) {
    // 不执行任何操作。
  }

  /**
   * 检查是否请求了取消操作。如果请求了取消，则抛出异常。
   *
   * @throws RuntimeException 如果检测到取消请求。
   */
  public void checkCancel() {
    if (cancelFlag.get()) {
      throw RESOURCE.preparationAborted().ex();
    }
  }

  /**
   * 获取当前已注册的规则列表。
   *
   * @return 不可变规则列表。
   */
  @Override
  public List<RelOptRule> getRules() {
    return ImmutableList.copyOf(mapDescToRule.values());
  }

  /**
   * 向优化器中添加一条规则。
   *
   * @param rule 要添加的规则。
   * @return 如果规则成功添加，返回 true；如果规则已存在，返回 false。
   * @throws AssertionError 如果规则描述与已有规则重复，但规则对象不同。
   */
  @Override
  public boolean addRule(RelOptRule rule) {
    // 获取规则的描述，并确保其不为空。
    final String description = requireNonNull(rule.toString());

    // 将规则添加到描述到规则的映射中。
    RelOptRule existingRule = mapDescToRule.put(description, rule);
    if (existingRule != null) {
      if (existingRule.equals(rule)) {
        // 如果规则对象相同，则返回 false 表示规则已存在。
        return false;
      } else {
        // 如果描述相同但规则对象不同，则抛出异常，提示需要修复规则的 equals 和 hashCode 方法。
        throw new AssertionError("规则的描述必须唯一；"
            + "现有规则=" + existingRule + "; 新规则=" + rule);
      }
    }
    return true;
  }


  @Override
  public boolean removeRule(RelOptRule rule) {
    // 根据规则的描述字符串从规则映射中移除该规则。
    String description = rule.toString();
    RelOptRule removed = mapDescToRule.remove(description);
    // 如果成功移除，返回 true；否则返回 false。
    return removed != null;
  }

  /**
   * 根据描述字符串返回规则。
   *
   * @param description 规则的描述字符串
   * @return 匹配的规则，如果未找到则返回 null
   */
  protected @Nullable RelOptRule getRuleByDescription(String description) {
    return mapDescToRule.get(description);
  }

  @Override
  public void setRuleDescExclusionFilter(@Nullable Pattern exclusionFilter) {
    // 设置规则描述的排除过滤器，用于决定哪些规则应被排除。
    ruleDescExclusionFilter = exclusionFilter;
  }

  /**
   * 判断给定的规则是否被排除。
   *
   * @param rule 要测试的规则
   * @return 如果规则被排除则返回 true，否则返回 false
   */
  public boolean isRuleExcluded(RelOptRule rule) {
    // 如果排除过滤器不为空并且规则的描述匹配过滤器，则认为该规则被排除。
    return ruleDescExclusionFilter != null
        && ruleDescExclusionFilter.matcher(rule.toString()).matches();
  }

  @Override
  public RelOptPlanner chooseDelegate() {
    // 当前类作为默认的委托优化器，返回自身。
    return this;
  }

  @Override
  public void addMaterialization(RelOptMaterialization materialization) {
    // 此优化器不支持物化视图，因此忽略此操作。
  }

  @Override
  public List<RelOptMaterialization> getMaterializations() {
    // 返回空的物化视图列表，因为此优化器不支持物化视图。
    return ImmutableList.of();
  }

  @Override
  public void addLattice(RelOptLattice lattice) {
    // 此优化器不支持数据晶格，因此忽略此操作。
  }

  @Override
  public @Nullable RelOptLattice getLattice(RelOptTable table) {
    // 返回 null，因为此优化器不支持数据晶格。
    return null;
  }

  @Override
  public void registerSchema(RelOptSchema schema) {
    // 空实现。子类可以覆盖此方法以支持注册模式。
  }

  @Deprecated // 此方法将在 2.0 版本之前移除。
  @Override
  public long getRelMetadataTimestamp(RelNode rel) {
    // 返回 0，因为此实现未提供元数据时间戳。
    return 0;
  }

  @Override
  public void prune(RelNode rel) {
    // 空实现。子类可以覆盖此方法以支持修剪无用的关系表达式。
  }

  @Override
  public void registerClass(RelNode node) {
    // 获取关系表达式的类。
    final Class<? extends RelNode> clazz = node.getClass();
    // 如果这是一个新的类，则触发回调方法。
    if (classes.add(clazz)) {
      onNewClass(node);
    }
    // 注册关系表达式的约定。
    Convention convention = node.getConvention();
    if (convention != null && conventions.add(convention)) {
      convention.register(this);
    }
  }

  /**
   * 当发现新的 {@link RelNode} 类时调用此方法。
   *
   * @param node 关系表达式
   */
  protected void onNewClass(RelNode node) {
    // 注册关系表达式及其规则。
    node.register(this);
  }

  @Override
  public RelTraitSet emptyTraitSet() {
    // 返回一个空的 TraitSet。
    return RelTraitSet.createEmpty();
  }

  @Override
  public @Nullable RelOptCost getCost(RelNode rel, RelMetadataQuery mq) {
    // 通过元数据查询获取关系表达式的累积成本。
    return mq.getCumulativeCost(rel);
  }

  @Deprecated // 此方法将在 2.0 版本之前移除。
  @Override
  public @Nullable RelOptCost getCost(RelNode rel) {
    // 使用元数据查询获取关系表达式的成本。
    final RelMetadataQuery mq = rel.getCluster().getMetadataQuery();
    return getCost(rel, mq);
  }

  @Override
  public void addListener(
      @UnknownInitialization AbstractRelOptPlanner this,
      RelOptListener newListener) {
    // 如果当前没有监听器，则初始化为多播监听器。
    if (listener == null) {
      listener = new MulticastRelOptListener();
    }
    // 将新的监听器添加到监听器列表中。
    listener.addListener(newListener);
  }

  @Deprecated // 此方法将在 2.0 版本之前移除。
  @Override
  public void registerMetadataProviders(List<RelMetadataProvider> list) {
    // 空实现。子类可以覆盖此方法以支持元数据提供者注册。
  }

  @Override
  public boolean addRelTraitDef(RelTraitDef relTraitDef) {
    // 默认返回 false，表示不支持添加新的 TraitDef。
    return false;
  }

  @Override
  public void clearRelTraitDefs() {
    // 空实现。子类可以覆盖此方法以支持清除 TraitDef。
  }

  @Override
  public List<RelTraitDef> getRelTraitDefs() {
    // 返回空的 TraitDef 列表。
    return ImmutableList.of();
  }

  @Override
  public void setExecutor(@Nullable RexExecutor executor) {
    // 设置 RexExecutor，用于表达式计算。
    this.executor = executor;
  }

  @Override
  public @Nullable RexExecutor getExecutor() {
    // 返回当前设置的 RexExecutor。
    return executor;
  }

  @Override
  public void setDecorrelator(@Nullable RelDecorrelator decorrelator) {
    // 设置去相关化器，用于优化查询中的相关子查询。
    this.decorrelator = decorrelator;
  }

  @Override
  public RelDecorrelator getDecorrelator() {
    // 如果未设置去相关化器，则抛出异常。
    if (decorrelator == null) {
      throw new IllegalStateException("RelDecorrelator has not been set");
    }
    // 返回当前的去相关化器。
    return decorrelator;
  }


  @Override
  public void onCopy(RelNode rel, RelNode newRel) {
    // 当一个关系表达式被复制时的回调方法。
    // 此方法在基类中没有具体实现，子类可以覆盖以提供自定义逻辑。
    // 当前实现为空。
  }

  protected void dumpRuleAttemptsInfo() {
    // 打印规则尝试的信息，用于调试目的。
    if (this.ruleAttemptsListener != null) {
      // 输出规则尝试信息日志。
      RULE_ATTEMPTS_LOGGER.debug("Rule Attempts Info for " + this.getClass().getSimpleName());
      RULE_ATTEMPTS_LOGGER.debug(this.ruleAttemptsListener.dump());
    }
  }

  /**
   * 触发规则，同时处理跟踪和监听器通知。
   *
   * @param ruleCall 规则调用的描述
   */
  protected void fireRule(RelOptRuleCall ruleCall) {
    // 检查是否有取消标志，如果已取消则抛出异常。
    checkCancel();

    // 确认规则与当前调用匹配。
    assert ruleCall.getRule().matches(ruleCall);

    // 如果规则被排除，则记录日志并返回。
    if (isRuleExcluded(ruleCall.getRule())) {
      LOGGER.debug("call#{}: Rule [{}] not fired due to exclusion filter",
          ruleCall.id, ruleCall.getRule());
      return;
    }

    // 如果规则被排除（基于规则提示），则记录日志并返回。
    if (ruleCall.isRuleExcluded()) {
      LOGGER.debug("call#{}: Rule [{}] not fired due to exclusion hint",
          ruleCall.id, ruleCall.getRule());
      return;
    }

    // 如果有监听器，则通知规则尝试事件（之前）。
    if (listener != null) {
      RelOptListener.RuleAttemptedEvent event =
          new RelOptListener.RuleAttemptedEvent(
              this,
              ruleCall.rel(0),
              ruleCall,
              true);
      listener.ruleAttempted(event);
    }

    // 调用规则匹配方法。
    ruleCall.getRule().onMatch(ruleCall);

    // 如果有监听器，则通知规则尝试事件（之后）。
    if (listener != null) {
      RelOptListener.RuleAttemptedEvent event =
          new RelOptListener.RuleAttemptedEvent(
              this,
              ruleCall.rel(0),
              ruleCall,
              false);
      listener.ruleAttempted(event);
    }
  }

  /**
   * 在应用规则转换时处理跟踪和监听器通知。
   *
   * @param ruleCall 规则调用的描述
   * @param newRel   转换的结果
   * @param before   在注册新关系表达式之前为 true，之后为 false
   */
  protected void notifyTransformation(RelOptRuleCall ruleCall, RelNode newRel, boolean before) {
    // 如果有监听器，通知规则转换事件。
    if (listener != null) {
      RelOptListener.RuleProductionEvent event =
          new RelOptListener.RuleProductionEvent(
              this,
              newRel,
              ruleCall,
              before);
      listener.ruleProductionSucceeded(event);
    }
  }

  /**
   * 当关系表达式被选为最终计划的一部分时，处理跟踪和监听器通知。
   *
   * @param rel 被选中的关系表达式
   */
  protected void notifyChosen(RelNode rel) {
    // 输出被选中计划的日志。
    LOGGER.debug("For final plan, using {}", rel);

    // 如果有监听器，通知关系表达式被选中事件。
    if (listener != null) {
      RelOptListener.RelChosenEvent event =
          new RelOptListener.RelChosenEvent(
              this,
              rel);
      listener.relChosen(event);
    }
  }

  /**
   * 当检测到关系表达式的等价性时，处理跟踪和监听器通知。
   *
   * @param rel 被检测的关系表达式
   * @param equivalenceClass 等价类对象
   * @param physical 是否为物理计划
   */
  protected void notifyEquivalence(RelNode rel, Object equivalenceClass, boolean physical) {
    // 如果有监听器，通知关系表达式等价性事件。
    if (listener != null) {
      RelOptListener.RelEquivalenceEvent event =
          new RelOptListener.RelEquivalenceEvent(
              this,
              rel,
              equivalenceClass,
              physical);
      listener.relEquivalenceFound(event);
    }
  }

  /**
   * 当一个关系表达式被丢弃时，处理跟踪和监听器通知。
   *
   * @param rel 被丢弃的关系表达式
   */
  protected void notifyDiscard(RelNode rel) {
    // 如果有监听器，通知关系表达式被丢弃事件。
    if (listener != null) {
      RelOptListener.RelDiscardedEvent event =
          new RelOptListener.RelDiscardedEvent(
              this,
              rel);
      listener.relDiscarded(event);
    }
  }

  @Pure
  public @Nullable RelOptListener getListener() {
    // 返回当前注册的监听器，如果没有监听器，则返回 null。
    return listener;
  }

  /**
   * 返回关系表达式的子类。
   *
   * @param clazz 关系表达式的类
   * @return 关系表达式子类的迭代器
   */
  public Iterable<Class<? extends RelNode>> subClasses(final Class<? extends RelNode> clazz) {
    // 过滤并返回子类。
    return Util.filter(classes, c -> {
      // RelSubset 必须是精确类型，而不是其子类。
      if (c == RelSubset.class) {
        return c == clazz;
      }
      // 检查是否为子类。
      return clazz.isAssignableFrom(c);
    });
  }


  /**
   * 用于统计每个规则尝试次数的监听器。
   * 仅在 DEBUG 级别启用。
   */
  private static class RuleAttemptsListener implements RelOptListener {
    // 记录规则尝试开始时间的时间戳
    private long beforeTimestamp;
    // 用于存储规则尝试信息的映射表，键为规则名，值为尝试次数和耗时的配对
    private final Map<String, Pair<Long, Long>> ruleAttempts;

    // 构造方法，初始化规则尝试映射表
    RuleAttemptsListener() {
      ruleAttempts = new HashMap<>();
    }

    /**
     * 当发现关系等价性时的回调（未实现具体逻辑）。
     *
     * @param event 等价性事件
     */
    @Override
    public void relEquivalenceFound(RelEquivalenceEvent event) {
      // 当前没有操作
    }

    /**
     * 当规则被尝试时的回调。
     *
     * @param event 规则尝试事件
     */
    @Override
    public void ruleAttempted(RuleAttemptedEvent event) {
      if (event.isBefore()) {
        // 如果是规则尝试前的事件，记录当前时间戳
        this.beforeTimestamp = System.nanoTime();
      } else {
        // 如果是规则尝试后的事件，计算规则执行耗时
        long elapsed = (System.nanoTime() - this.beforeTimestamp) / 1000; // 转换为微秒
        String rule = event.getRuleCall().getRule().toString(); // 获取规则的描述
        // 更新规则尝试计数和耗时
        ruleAttempts.compute(rule, (k, p) ->
            p == null
                ? Pair.of(1L, elapsed) // 如果没有记录过此规则，初始化计数为 1，耗时为当前耗时
                : Pair.of(p.left + 1, p.right + elapsed)); // 累加计数和耗时
      }
    }

    /**
     * 当规则成功生产结果时的回调（未实现具体逻辑）。
     *
     * @param event 规则生产事件
     */
    @Override
    public void ruleProductionSucceeded(RuleProductionEvent event) {
      // 当前没有操作
    }

    /**
     * 当关系被丢弃时的回调（未实现具体逻辑）。
     *
     * @param event 关系丢弃事件
     */
    @Override
    public void relDiscarded(RelDiscardedEvent event) {
      // 当前没有操作
    }

    /**
     * 当选择关系作为最终计划时的回调（未实现具体逻辑）。
     *
     * @param event 关系选择事件
     */
    @Override
    public void relChosen(RelChosenEvent event) {
      // 当前没有操作
    }

    /**
     * 打印规则尝试的统计信息。
     *
     * @return 规则尝试统计信息的字符串
     */
    public String dump() {
      // 将规则按照尝试次数降序、耗时降序、规则名称升序进行排序
      List<Map.Entry<String, Pair<Long, Long>>> list =
          new ArrayList<>(this.ruleAttempts.entrySet());
      list.sort((left, right) -> {
        int res = right.getValue().left.compareTo(left.getValue().left); // 比较尝试次数
        if (res == 0) {
          res = right.getValue().right.compareTo(left.getValue().right); // 比较耗时
        }
        if (res == 0) {
          res = left.getKey().compareTo(right.getKey()); // 比较规则名称
        }
        return res;
      });

      // 构建规则尝试统计信息的字符串
      StringBuilder sb = new StringBuilder();
      sb.append(String.format(Locale.ROOT, "%n%-60s%20s%20s%n", "Rules", "Attempts", "Time (us)"));
      NumberFormat usFormat = NumberFormat.getNumberInstance(Locale.US);
      long totalAttempts = 0; // 总尝试次数
      long totalTime = 0; // 总耗时
      for (Map.Entry<String, Pair<Long, Long>> entry : list) {
        sb.append(
            String.format(Locale.ROOT, "%-60s%20s%20s%n",
                entry.getKey(),
                usFormat.format(entry.getValue().left), // 尝试次数
                usFormat.format(entry.getValue().right))); // 总耗时
        totalAttempts += entry.getValue().left;
        totalTime += entry.getValue().right;
      }
      sb.append(
          String.format(Locale.ROOT, "%-60s%20s%20s%n",
              "* Total",
              usFormat.format(totalAttempts), // 输出总尝试次数
              usFormat.format(totalTime))); // 输出总耗时

      return sb.toString();
    }
  }

}
