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

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;

import com.google.common.collect.ImmutableList;

import java.util.List;

/**
 * 关系表达式（Relational Expression）的抽象基类，该类用于表示具有单一输入的关系运算符。
 *
 * <p>并非所有单输入关系表达式都必须继承该类，但该类提供了一些默认方法实现，可以简化开发工作。</p>
 */
public abstract class SingleRel extends AbstractRelNode {

  //~ Instance fields --------------------------------------------------------

  /** 该关系表达式的输入节点 */
  protected RelNode input;

  //~ Constructors -----------------------------------------------------------

  /**
   * 构造一个 <code>SingleRel</code> 实例。
   *
   * @param cluster 该关系表达式所属的计算集群
   * @param traits  该关系表达式的物理属性（如执行策略）
   * @param input   该关系表达式的输入关系节点
   */
  protected SingleRel(
      RelOptCluster cluster,
      RelTraitSet traits,
      RelNode input) {
    super(cluster, traits);
    this.input = input;
  }

  //~ Methods ----------------------------------------------------------------

  /**
   * 获取该关系表达式的输入节点。
   *
   * @return 关系节点（RelNode）输入
   */
  public RelNode getInput() {
    return input;
  }

  /**
   * 获取该关系表达式的所有输入节点（此类仅支持单一输入）。
   *
   * @return 仅包含一个元素的输入列表
   */
  @Override
  public List<RelNode> getInputs() {
    return ImmutableList.of(input);
  }

  /**
   * 估算该关系表达式的行数（默认使用输入节点的行数）。
   *
   * <p>这个估算不一定准确，但比 `AbstractRelNode` 默认的 1.0 更合理。</p>
   *
   * @param mq 元数据查询接口
   * @return 估算的行数
   */
  @Override
  public double estimateRowCount(RelMetadataQuery mq) {
    return mq.getRowCount(input);
  }

  /**
   * 递归访问子节点，并调用访问者的 `visit` 方法。
   *
   * @param visitor 关系表达式访问者
   */
  @Override
  public void childrenAccept(RelVisitor visitor) {
    visitor.visit(input, 0, this);
  }

  /**
   * 用于解释（序列化）该关系表达式的基本属性。
   *
   * @param pw 关系表达式写入器
   * @return 关系写入器，包含输入信息
   */
  @Override
  public RelWriter explainTerms(RelWriter pw) {
    return super.explainTerms(pw)
        .input("input", getInput());
  }

  /**
   * 替换该关系表达式的输入节点。
   *
   * @param ordinalInParent 该节点在父节点中的索引（对于 `SingleRel` 必须为 0）
   * @param rel 替换的新输入节点
   */
  @Override
  public void replaceInput(int ordinalInParent, RelNode rel) {
    assert ordinalInParent == 0; // 该类只允许单一输入，索引必须为 0
    this.input = rel;
    recomputeDigest(); // 重新计算摘要信息，确保查询计划的正确性
  }

  /**
   * 推导该关系表达式的输出数据类型。
   *
   * <p>由于 `SingleRel` 只是单纯地包装了 `input`，所以其数据类型与输入节点的数据类型相同。</p>
   *
   * @return 继承输入的行类型
   */
  @Override
  protected RelDataType deriveRowType() {
    return input.getRowType();
  }
}

