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

import org.apache.calcite.rel.RelNode;

import com.google.common.collect.Multimap;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Method;
import java.util.List;

/**
 * RelMetadataProvider 定义了一个接口，用于获取关系表达式（Relational Expression）的元数据（Metadata）。
 * 该接口是弱类型的（Weakly-Typed），通常不建议直接调用，而是使用更强类型（Strongly-Typed）的外观模式（Facade），
 * 例如 {@link RelMetadataQuery}。
 *
 * <p>关于背景和动机，可以参考 <a
 * href="http://www.hydromatic.net/wiki/RelationalExpressionMetadata">wiki</a>。</p>
 *
 * <p>如果提供者（Provider）不是单例（Singleton），建议实现 {@link Object#equals(Object)} 和
 * {@link Object#hashCode()} 方法，以提高 {@link JaninoRelMetadataProvider} 缓存的有效性。</p>
 */
public interface RelMetadataProvider {
  //~ Methods ----------------------------------------------------------------

  /**
   * 获取特定类型的元数据（Metadata），并适用于特定子类的关系表达式（Relational Expression）。
   *
   * <p>返回的对象是一个函数（Function），它可以应用于指定类型的关系表达式，以创建元数据对象。</p>
   *
   * <p>例如，你可以使用如下方式调用：</p>
   *
   * <blockquote><pre>
   * RelMetadataProvider provider;
   * LogicalFilter filter;
   * RexNode predicate;
   * Function&lt;RelNode, Metadata&gt; function =
   *   provider.apply(LogicalFilter.class, Selectivity.class};
   * Selectivity selectivity = function.apply(filter);
   * Double d = selectivity.selectivity(predicate);
   * </pre></blockquote>
   *
   * @deprecated 请使用 {@link RelMetadataQuery} 代替。
   *
   * @param relClass 关系表达式的类型（Class 类型）
   * @param metadataClass 元数据类型（Class 类型）
   * @return 一个函数对象，该函数可用于获取元数据实例；如果当前提供者无法提供此类元数据，则返回 null。
   */
  @Deprecated // 计划在 2.0 版本之前移除
  <@Nullable M extends @Nullable Metadata> @Nullable UnboundMetadata<M> apply(
      Class<? extends RelNode> relClass, Class<? extends M> metadataClass);

  /**
   * 获取特定元数据类型（Metadata Type）的处理程序（Metadata Handler）映射。
   *
   * <p>该方法返回一个 Multimap（多值映射），它将 {@link Method} 映射到一个或多个
   * {@link MetadataHandler} 处理程序。</p>
   *
   * @deprecated 计划在 2.0 版本之前移除。
   *
   * @param def 元数据定义（Metadata Definition）
   * @return 多值映射，映射元数据处理方法到相应的元数据处理程序
   */
  @Deprecated // 计划在 2.0 版本之前移除
  <M extends Metadata> Multimap<Method, MetadataHandler<M>> handlers(
      MetadataDef<M> def);

  /**
   * 获取实现特定 {@link MetadataHandler} 的处理程序列表。
   *
   * <p>解析（Resolution）顺序按照关系表达式节点（RelNode Class）的特异性（Specificity）排序，
   * 优先选择在列表中出现较早的处理程序。</p>
   *
   * <p>例如，假设返回的列表为 {A, B, C}：
   * - A 实现了 RelNode 和 Scan；
   * - B 实现了 Scan；
   * - C 实现了 LogicalScan 和 Filter。</p>
   *
   * <p>当分派（Dispatch）处理时：
   * - Scan 调用 A 处理的方法 a.method(Scan)；
   * - LogicalFilter 调用 C 处理的方法 c.method(Filter)；
   * - LogicalScan 调用 C 处理的方法 c.method(LogicalScan)；
   * - Aggregate 调用 A 处理的方法 a.method(RelNode)。</p>
   *
   * <p>如果类层次结构不是树形，则行为未定义。</p>
   *
   * @param handlerClass 处理程序类的类型
   * @return 处理程序列表
   */
  List<MetadataHandler<?>> handlers(Class<? extends MetadataHandler<?>> handlerClass);
}

