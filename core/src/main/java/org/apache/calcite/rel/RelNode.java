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
package org.apache.calcite.rel;

import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelDigest;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptNode;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.metadata.Metadata;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.util.Litmus;

import org.apiguardian.api.API;
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

import java.util.List;
import java.util.Set;

/**
 * {@code RelNode} 是一个关系表达式（Relational Expression）。
 *
 * <p>关系表达式处理数据，因此它们的名称通常是动词，例如：
 * Sort（排序）、Join（连接）、Project（投影）、Filter（过滤）、Scan（扫描）、Sample（采样）。
 *
 * <p>关系表达式不同于标量表达式（Scalar Expression），有关标量表达式的更多信息，
 * 请参考 {@link org.apache.calcite.sql.SqlNode} 和 {@link RexNode}。
 *
 * <p>如果某种关系表达式类型具有特定的规划器规则（Planner Rules），
 * 则应实现一个 <em>public static</em> 方法 {@link AbstractRelNode#register} 来注册该规则。
 *
 * <p>当一个关系表达式需要被实现时，系统会分配 {@link org.apache.calcite.plan.RelImplementor}
 * 来管理该过程。每个可实现的关系表达式都会包含一个 {@link RelTraitSet}，用于描述其物理属性。
 * 该 `RelTraitSet` 始终包含一个 {@link Convention}，描述该表达式如何将数据传递给
 * 消费它的关系表达式。此外，它还可能包含其他特性（Traits），包括一些外部应用的特性。
 * 由于 `RelTraitSet` 可能包含外部应用的特性，因此 `RelNode` 的实现类不应假设 `RelTraitSet`
 * 的大小或内容，除了那些由 `RelNode` 自己配置的特性。
 *
 * <p>对于每种调用约定（Calling-Convention），都有一个相应的 `RelNode` 子接口。例如，
 * {@code org.apache.calcite.adapter.enumerable.EnumerableRel} 提供了转换到
 * {@code org.apache.calcite.adapter.enumerable.EnumerableConvention} 调用约定的操作，
 * 并与 {@code EnumerableRelImplementor} 进行交互。
 *
 * <p>只有在关系表达式实际被实现（即转换为执行计划或程序）时，它才需要实现其调用约定的接口。
 * 这意味着某些不可执行的关系表达式（例如转换器）不需要实现其调用约定的接口。
 *
 * <p>所有关系表达式都必须继承自 {@link AbstractRelNode}。
 * （那么为什么还要定义 `RelNode` 接口呢？这是因为 Java 接口只能继承自另一个接口，
 * 因此 `RelNode` 作为所有关系表达式的根接口是必要的）。
 */
public interface RelNode extends RelOptNode, Cloneable {
  //~ 方法定义 ----------------------------------------------------------------

  /**
   * 返回该 `RelNode` 的调用约定（Calling-Convention）特性，存储在 {@link #getTraitSet()} 中。
   *
   * @return 该 `RelNode` 的调用约定
   */
  @Pure
  @Nullable Convention getConvention();

  /**
   * 返回一个变量的名称，该变量在运行时被隐式设置，每次从该关系表达式的第一个输入返回一行时都会被更新。
   * 如果没有这样的变量，则返回 `null`。
   *
   * @return 关联变量的名称，或者 `null`
   */
  @Nullable String getCorrelVariable();

  /**
   * 返回该关系表达式的第 `i` 个输入。
   *
   * @param i 输入的序号（从 0 开始）
   * @return 第 `i` 个输入的关系表达式
   */
  RelNode getInput(int i);

  /**
   * 返回该关系表达式返回的行的类型（数据结构）。
   *
   * @return 行类型信息
   */
  @Override RelDataType getRowType();

  /**
   * 返回该关系表达式期望的输入行类型，默认实现返回 {@link #getRowType()}。
   *
   * @param ordinalInParent 在父 `RelNode` 中的输入索引（从 0 开始）
   * @return 期望的输入行类型
   */
  RelDataType getExpectedInputRowType(int ordinalInParent);

  /**
   * 返回该关系表达式的所有输入。如果没有输入，则返回一个空列表，而不是 `null`。
   *
   * @return 该关系表达式的输入列表
   */
  @Override List<RelNode> getInputs();

  /**
   * 估算该关系表达式返回的行数。
   *
   * <p><strong>注意：</strong>请不要直接调用此方法，而应使用 {@link RelMetadataQuery#getRowCount}，
   * 以允许插件覆盖关系表达式对行数的默认估算策略。
   *
   * @param mq 元数据查询对象
   * @return 估算的返回行数
   */
  double estimateRowCount(RelMetadataQuery mq);

  /**
   * 返回在该关系表达式中定义但也被使用，并因此对其父表达式不可用的变量集合。
   *
   * @return 该关系表达式定义但对父级不可用的变量集合
   */
  Set<CorrelationId> getVariablesSet();

  /**
   * 收集该表达式或其子表达式中使用的变量。
   *
   * <p>默认情况下，不会提供这些信息，而是需要通过分析子表达式来推导。
   * 但某些优化器实现可能会插入特殊的表达式，以存储这些信息。
   *
   * @param variableSet 存储收集到的变量
   */
  void collectVariablesUsed(Set<CorrelationId> variableSet);

  /**
   * 收集该表达式所设置的变量。
   * TODO: 是否必须提供该方法？
   *
   * @param variableSet 存储收集到的变量
   */
  void collectVariablesSet(Set<CorrelationId> variableSet);

  /**
   * 采用访问者模式（Visitor Pattern）遍历关系表达式树。
   *
   * @param visitor 访问者对象
   */
  void childrenAccept(RelVisitor visitor);

  /**
   * 计算该执行计划的成本（不包括其子节点）。
   * 该方法的默认实现会抛出异常，子类需要进行覆盖。
   *
   * <p><strong>注意：</strong>请不要直接调用此方法，而应使用
   * {@link RelMetadataQuery#getNonCumulativeCost}，以允许插件覆盖关系表达式的默认成本估算逻辑。
   *
   * @param planner 规划器对象
   * @param mq      元数据查询对象
   * @return 计算出的成本（不包括子节点）
   */
  @Nullable RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq);


  /**
   * 返回元数据接口。
   *
   * @deprecated 建议通过 {@link #getCluster()} 使用 {@link RelMetadataQuery} 进行元数据查询。
   *
   * @param <M> 元数据的类型
   * @param metadataClass 元数据接口类
   * @param mq 元数据查询对象
   * @return 提供所需元数据的元数据对象（永不为 `null`，但如果信息不可用，则对象的所有方法可能返回 `null`）
   */
  @Deprecated // 计划在 2.0 版本之前移除
  <@Nullable M extends @Nullable Metadata> M metadata(Class<M> metadataClass, RelMetadataQuery mq);

  /**
   * 描述该关系表达式的输入和属性。
   *
   * <p>每个 `RelNode` 需要调用 `super.explain`，然后调用
   * {@link org.apache.calcite.rel.externalize.RelWriterImpl#input(String, RelNode)}
   * 和 {@link RelWriter#item(String, Object)} 方法，为每个输入和属性添加信息。
   *
   * @param pw 计划写入器（Plan Writer）
   */
  void explain(RelWriter pw);

  /**
   * 返回当前 `RelNode` 的关系表达式字符串表示形式。
   *
   * <p>该方法返回的字符串与 {@link RelOptUtil#toString(org.apache.calcite.rel.RelNode)} 相同。
   * 主要用于在 IDE 中调试时方便查看 `RelNode` 的表示形式。
   * 建议实现此接口的类不要重写该方法。
   *
   * @return 当前 `RelNode` 的关系表达式字符串表示
   */
  default String explain() {
    return RelOptUtil.toString(this);
  }

  /**
   * 当该关系表达式即将被注册时收到通知。
   *
   * <p>该方法的实现必须至少注册所有子表达式。
   *
   * @param planner 规划器（Planner），用于规划该 `RelNode`
   * @return 规划器应使用的关系表达式
   */
  RelNode onRegister(RelOptPlanner planner);

  /**
   * 返回当前 `RelNode` 的摘要字符串（Digest String）。
   *
   * <p>每次调用都会生成一个新的摘要字符串，因此如果需要，可以对结果进行缓存。
   *
   * @return 当前 `RelNode` 的摘要字符串
   * @see #getRelDigest()
   */
  @Override default String getDigest() {
    return getRelDigest().toString();
  }

  /**
   * 返回当前 `RelNode` 的摘要信息（Digest）。
   *
   * <p><strong>仅限内部使用</strong>，供规划器（Planner）使用。
   *
   * @return 当前 `RelNode` 的摘要信息
   * @see #getDigest()
   */
  @API(since = "1.24", status = API.Status.INTERNAL)
  RelDigest getRelDigest();

  /**
   * 重新计算该 `RelNode` 的摘要信息（Digest）。
   *
   * <p><strong>仅限内部使用</strong>，供规划器（Planner）使用。
   *
   * @see #getDigest()
   */
  @API(since = "1.24", status = API.Status.INTERNAL)
  void recomputeDigest();

  /**
   * 深度相等检查，判断两个 `RelNode` 是否等价或具有相同的摘要信息（Digest）。
   *
   * <p>默认实现会从 `explain` 方法收集摘要属性，并逐个比较属性值。
   *
   * @param obj 另一个对象
   * @return 如果两个 `RelNode` 等价或具有相同摘要信息，则返回 `true`
   * @see #deepHashCode()
   */
  @EnsuresNonNullIf(expression = "#1", result = true)
  boolean deepEquals(@Nullable Object obj);

  /**
   * 计算 `RelNode` 摘要信息的深度哈希码（Deep Hash Code）。
   *
   * @see #deepEquals(Object)
   */
  int deepHashCode();

  /**
   * 替换该 `RelNode` 的第 `ordinalInParent` 个输入。
   *
   * <p>如果重写了 {@link #getInputs()}，则必须重写该方法。
   *
   * @param ordinalInParent 子输入的位置索引（从 0 开始）
   * @param p 替换的新 `RelNode`
   */
  void replaceInput(int ordinalInParent, RelNode p);

  /**
   * 如果该关系表达式表示对表的访问，则返回该表；否则返回 `null`。
   *
   * @return 如果该 `RelNode` 访问表，则返回该表对象，否则返回 `null`
   */
  @Nullable RelOptTable getTable();

  /**
   * 返回该关系表达式所属类的名称，不包含包名。
   *
   * <p>例如，对于 `org.apache.calcite.rel.ArrayRel.ArrayReader`，该方法返回 `"ArrayReader"`。
   *
   * @return 该 `RelNode` 的类名（不含包路径），用于 `explain` 方法
   */
  String getRelTypeName();

  /**
   * 检查当前 `RelNode` 是否有效。
   *
   * <p>如果启用了断言（assertions），通常会使用 `litmus = THROW` 进行调用，如：
   * <blockquote>
   * <pre>assert rel.isValid(Litmus.THROW)</pre>
   * </blockquote>
   * 这样，如果 `RelNode` 无效，该方法会抛出 {@link AssertionError}。
   *
   * @param litmus 无效时的处理方式
   * @param context 用于校验有效性的上下文信息
   * @return 关系表达式是否有效
   * @throws AssertionError 如果 `RelNode` 无效且 `litmus = THROW`
   */
  boolean isValid(Litmus litmus, @Nullable Context context);


  /**
   * 创建当前关系表达式（RelNode）的一个副本，并可能更改其属性（traits）和输入（inputs）。
   *
   * <p>子类如果有其他重要的属性，建议创建此方法的变体，并添加更多参数来支持这些属性的拷贝。</p>
   *
   * @param traitSet 需要应用的新特征集合（TraitSet）
   * @param inputs   该关系表达式的新输入列表
   * @return 具有新特征和输入的关系表达式副本
   */
  RelNode copy(
      RelTraitSet traitSet,
      List<RelNode> inputs);

  /**
   * 注册该关系表达式（RelNode）特有的优化规则。
   *
   * <p>当规划器（Planner）首次遇到该类型的关系表达式时，会调用此方法。</p>
   * <p>派生类应在该方法内调用 {@link org.apache.calcite.plan.RelOptPlanner#addRule}
   * 来注册每个适用的规则，并最终调用 {@code super.register}。</p>
   *
   * @param planner 用于注册额外关系表达式的规划器（Planner）
   */
  void register(RelOptPlanner planner);

  /**
   * 指示该关系表达式是否为 "Enforcer" 操作符，例如 `PhysicalSort`、`PhysicalHashDistribute` 等。
   *
   * <p>作为 "Enforcer"（强制转换算子），该操作符仅在其输入不满足所需的 `traitSet` 时创建。</p>
   *
   * @return 如果该操作符为 "Enforcer"，返回 `true`；否则返回 `false`
   */
  default boolean isEnforcer() {
    return false;
  }

  /**
   * 接受 `RelShuttle` 访问者模式的访问。
   *
   * <p>该方法用于遍历和修改关系表达式的树结构，应用 `RelShuttle` 进行遍历和变换。</p>
   *
   * @param shuttle 访问该 `RelNode` 的 `RelShuttle`
   * @return 一个包含 `shuttle` 对当前节点子节点修改后的 `RelNode` 副本
   */
  RelNode accept(RelShuttle shuttle);

  /**
   * 接受 `RexShuttle` 访问者模式的访问。
   *
   * <p>如果 `shuttle` 修改了表达式（expression），则应创建关系表达式的副本。
   * 该新关系表达式可能会具有不同的行类型（Row Type）。</p>
   *
   * @param shuttle 访问该 `RelNode` 的 `RexShuttle`
   * @return 一个包含 `shuttle` 变更后的 `RelNode` 副本
   */
  RelNode accept(RexShuttle shuttle);

  /**
   * 判断指定索引的字段是否可为空。
   *
   * @param i 字段索引（基于 0）
   * @return 如果字段可为空，则返回 `true`，否则返回 `false`
   */
  default boolean fieldIsNullable(int i) {
    return getRowType().getFieldList().get(i).getType().isNullable();
  }

  /**
   * 返回当前 `RelNode`，但去除了规划器可能添加的任何包装（Wrapper）。
   *
   * <p>此方法的默认实现返回 `this`，子类可以重写以移除可能存在的额外包装。</p>
   *
   * @return 经过去包装的 `RelNode`
   */
  default RelNode stripped() {
    return this;
  }

  /**
   * 关系表达式（Relational Expression）的上下文接口，用于校验有效性。
   */
  interface Context {
    /**
     * 返回当前上下文中的相关性 ID（Correlation IDs）。
     *
     * @return 相关性 ID 集合
     */
    Set<CorrelationId> correlationIds();
  }

}
