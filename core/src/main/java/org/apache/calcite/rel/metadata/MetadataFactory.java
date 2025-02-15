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

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * 关系表达式（Relational Expression）元数据的来源。
 *
 * <p>元数据通常是用于估算计算成本（Cost Estimation）的各种统计信息，例如选择性（Selectivity）、列唯一性（Column Uniqueness）等。</p>
 *
 * <p>每种元数据类型都有一个接口，该接口继承自 {@link Metadata}，并定义了相应的方法。
 * 一些示例包括：
 * - {@link BuiltInMetadata.Selectivity}：计算特定谓词（Predicate）的选择性；
 * - {@link BuiltInMetadata.ColumnUniqueness}：检查某列在给定上下文中是否唯一。</p>
 */
public interface MetadataFactory {

  /**
   * 返回一个元数据接口，用于从特定的关系表达式（Relational Expression）中获取特定类型的元数据。
   * 如果该类型的元数据不可用，则返回 null。
   *
   * <p>该方法的作用是将关系表达式（RelNode）、元数据查询（RelMetadataQuery）和元数据类型（Metadata Class）
   * 绑定在一起，以获取相应的元数据对象。</p>
   *
   * @param <M> 元数据类型（Metadata Type）
   * @param rel 关系表达式（Relational Expression），即查询的目标对象
   * @param mq 元数据查询对象（Metadata Query），用于查询和计算元数据信息
   * @param metadataClazz 需要获取的元数据类（Metadata Class）
   * @return 绑定到 {@code rel} 和 {@code mq} 的元数据实例；如果该类型的元数据不可用，则返回 null
   */
  <@Nullable M extends @Nullable Metadata> M query(RelNode rel, RelMetadataQuery mq,
      Class<M> metadataClazz);
}

