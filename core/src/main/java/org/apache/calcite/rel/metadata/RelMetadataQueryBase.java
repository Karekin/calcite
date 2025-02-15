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

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.function.Supplier;

import static org.apache.calcite.linq4j.Nullness.castNonNull;

import static java.util.Objects.requireNonNull;

/**
 * 基础类 `RelMetadataQueryBase`，用于 `RelMetadataQuery`，该类使用 Janino 生成的元数据处理器类。
 *
 * <p>如果要向该接口添加新的实现，请按照以下步骤操作：
 *
 * <ol>
 * <li>扩展 {@link RelMetadataQuery}（例如，命名为 `MyRelMetadataQuery`），
 *     以复用 Calcite 内置的元数据查询接口。在该类中，定义所有扩展的处理器（Handler）
 *     并实现元数据查询接口。
 * <li>编写自定义的提供者类，例如 `RelMdXyz`。
 *     参照 {@link RelMdColumnOrigins} 的模式，对所有适用的逻辑关系表达式进行重载。
 * <li>在每个提供者类中添加一个 `SOURCE` 静态成员，类似于 {@link RelMdColumnOrigins#SOURCE}。
 * <li>扩展 {@link DefaultRelMetadataProvider}（例如命名为 `MyRelMetadataProvider`），
 *     并将 `SOURCE` 补充到内置列表中。
 *     （这不是强制要求的，你也可以使用 {@link ChainedRelMetadataProvider}
 *     将自定义 `SOURCE` 与默认 `SOURCE` 进行链接）。
 * <li>将 `MyRelMetadataProvider` 设置到 `cluster` 实例中。
 * <li>使用 {@link org.apache.calcite.plan.RelOptCluster#setMetadataQuerySupplier(Supplier)}
 *     将元数据查询的 {@link Supplier} 设置到 `cluster` 实例中。
 *     该 {@link Supplier} 应返回一个<strong>全新</strong>的实例。
 * <li>使用 `cluster` 实例创建 {@link org.apache.calcite.sql2rel.SqlToRelConverter}。
 * <li>在 {@link org.apache.calcite.plan.RelOptRuleCall} 内使用你在 `MyRelMetadataQuery`
 *     中定义的接口查询元数据。
 * </ol>
 */
public class RelMetadataQueryBase {
  //~ 成员变量 --------------------------------------------------------

  /** 记录活跃的元数据查询集合，并缓存之前的查询结果。 */
  public final Table<RelNode, Object, Object> map = HashBasedTable.create();

  /** 处理元数据的提供者，支持 `MetadataHandler` 机制。 */
  private final @Nullable MetadataHandlerProvider metadataHandlerProvider;

  @Deprecated // 计划在 2.0 版本中移除
  /** 旧版的元数据提供者 `JaninoRelMetadataProvider`，用于兼容旧代码。 */
  public final @Nullable JaninoRelMetadataProvider metadataProvider;

  //~ 静态字段/初始化 ---------------------------------------------

  /** 线程局部变量，存储 `JaninoRelMetadataProvider` 实例，用于缓存。 */
  public static final ThreadLocal<@Nullable JaninoRelMetadataProvider> THREAD_PROVIDERS =
      new ThreadLocal<>();

  //~ 构造方法 -----------------------------------------------------------

  @Deprecated // 计划在 2.0 版本中移除
  protected RelMetadataQueryBase(@Nullable JaninoRelMetadataProvider metadataProvider) {
    this((MetadataHandlerProvider) metadataProvider);
  }

  @SuppressWarnings("deprecation")
  protected RelMetadataQueryBase(@Nullable MetadataHandlerProvider provider) {
    this.metadataHandlerProvider = provider;
    this.metadataProvider = provider instanceof JaninoRelMetadataProvider
        ? (JaninoRelMetadataProvider) provider : null;
  }

  /**
   * 创建一个初始的处理器代理，用于捕获所有 `MetadataHandler` 的调用，
   * 如果未找到合适的处理器，则抛出 `NoHandler` 异常。
   */
  @Deprecated
  protected static <H> H initialHandler(Class<H> handlerClass) {
    return handlerClass.cast(
        Proxy.newProxyInstance(RelMetadataQuery.class.getClassLoader(),
            new Class[] {handlerClass}, (proxy, method, args) -> {
              final RelNode r = requireNonNull((RelNode) args[0], "(RelNode) args[0]");
              throw new JaninoRelMetadataProvider.NoHandler(r.getClass());
            }));
  }

  //~ 方法 ----------------------------------------------------------------

  /**
   * 重新生成指定 `RelNode` 类型的元数据处理器，并添加对 `class_` 的支持（如果尚未存在）。
   */
  @Deprecated // 计划在 2.0 版本中移除
  protected <M extends Metadata, H extends MetadataHandler<M>> H
  revise(Class<? extends RelNode> class_, MetadataDef<M> def) {
    return (H) revise(def.handlerClass);
  }

  /**
   * 重新生成指定类型的元数据处理器。
   */
  protected <H extends MetadataHandler<?>> H revise(Class<H> def) {
    return getMetadataHandlerProvider().revise(def);
  }

  /**
   * 获取当前的 `MetadataHandlerProvider`，如果为空则抛出异常。
   */
  private MetadataHandlerProvider getMetadataHandlerProvider() {
    requireNonNull(metadataHandlerProvider, "metadataHandlerProvider");
    return castNonNull(metadataHandlerProvider);
  }

  /**
   * 提供对请求的 `MetadataHandler` 类的处理器。
   *
   * @param handlerClass 需要的处理器接口
   * @param <MH> 处理的元数据类型
   * @return 处理器实例
   */
  protected <MH extends MetadataHandler<?>> MH handler(Class<MH> handlerClass) {
    return getMetadataHandlerProvider().handler(handlerClass);
  }

  /**
   * 清除指定 `RelNode` 的缓存元数据值。
   *
   * @param rel 需要清理缓存的 `RelNode`
   * @return 如果 `RelNode` 之前存在缓存，则返回 `true`
   */
  public boolean clearCache(RelNode rel) {
    Map<Object, Object> row = map.row(rel);
    if (row.isEmpty()) {
      return false;
    }

    row.clear();
    return true;
  }
}

