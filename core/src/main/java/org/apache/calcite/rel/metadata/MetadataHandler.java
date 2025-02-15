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

import com.google.common.collect.ImmutableSortedMap;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.SortedMap;

import static org.apache.calcite.util.ReflectUtil.isStatic;

/**
 * 元数据处理器（Metadata Handler）的标记接口（Marker Interface）。
 * 该接口用于定义特定类型的元数据处理逻辑。
 *
 * @param <M> 元数据的类型（Metadata Type）
 */
public interface MetadataHandler<M extends Metadata> {

  /**
   * 获取该处理器支持的元数据定义（Metadata Definition）。
   *
   * @return 该处理器支持的元数据定义
   */
  MetadataDef<M> getDef();

  /**
   * 查找 {@link MetadataHandler} 中定义的处理方法，并返回一个以方法名为键的映射（Map）。
   *
   * <p>该方法会：
   * - 忽略 `static`（静态）方法；
   * - 忽略 `synthetic`（合成）方法（编译器生成的方法，如 lambda 表达式）；
   * - 忽略 `getDef()` 方法（此方法不属于元数据处理逻辑）。</p>
   *
   * <p>该方法要求：
   * - 处理器中的方法名称必须唯一，否则可能会导致映射冲突。</p>
   *
   * @param handlerClass 需要检查的元数据处理器类
   * @return 处理方法的映射（SortedMap），键为方法名，值为对应的 `Method` 对象
   */
  static SortedMap<String, Method> handlerMethods(
      Class<? extends MetadataHandler<?>> handlerClass) {
    // 创建一个按字典顺序排序的不可变映射构造器
    final ImmutableSortedMap.Builder<String, Method> map =
        ImmutableSortedMap.naturalOrder();

    // 遍历 `handlerClass` 的所有声明方法
    Arrays.stream(handlerClass.getDeclaredMethods())
        // 过滤掉 `getDef()` 方法
        .filter(m -> !m.getName().equals("getDef"))
        // 过滤掉 `synthetic` 方法（合成方法，如 lambda 生成的方法）
        .filter(m -> !m.isSynthetic())
        // 过滤掉 `static` 静态方法
        .filter(m -> !isStatic(m))
        // 将符合条件的方法添加到映射中（方法名作为键）
        .forEach(m -> map.put(m.getName(), m));

    // 返回构造的不可变映射
    return map.build();
  }
}

