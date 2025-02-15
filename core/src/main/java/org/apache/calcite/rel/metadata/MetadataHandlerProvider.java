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
import org.apache.calcite.util.ControlFlowException;

/**
 * 提供 {@link MetadataHandler} 的调用入口，用于 {@link RelMetadataQuery} 查询元数据。
 *
 * <p>该接口用于管理和提供特定类型的元数据处理器（Handler），
 * 这些处理器负责在 {@link RelMetadataQuery} 进行查询时更新缓存，
 * 以提高查询优化器的性能和效率。</p>
 */
public interface MetadataHandlerProvider {

  /**
   * 提供指定元数据类型的处理器（Handler）。
   *
   * <p>在查询关系表达式（RelNode）的元数据信息时，优化器需要依赖不同类型的元数据处理器来计算
   * 诸如行数、选择性、唯一性、成本估算等信息。本方法用于根据指定的处理器类型提供一个具体的处理器实例。</p>
   *
   * @param handlerClass 需要获取的处理器类
   * @param <MH> 处理器对应的元数据类型
   * @return 指定类型的处理器实例
   */
  <MH extends MetadataHandler<?>> MH handler(Class<MH> handlerClass);

  /**
   * 重新生成并返回某种元数据类型的处理器（Handler）。
   *
   * <p>该方法通常在已有的处理器抛出 {@link NoHandler} 异常时调用，
   * 以重新生成适用于该元数据类型的新处理器。通常用于动态扩展或在运行时修复缺失的元数据处理器。</p>
   *
   * <p>默认实现不支持处理器的重新生成，如需支持需要在具体实现类中覆盖该方法。</p>
   *
   * @param handlerClass 需要重新生成的处理器类
   * @param <MH> 处理器对应的元数据类型
   * @return 新的处理器实例，以替换之前的处理器
   * @throws UnsupportedOperationException 如果当前提供者不支持处理器重新生成
   */
  default <MH extends MetadataHandler<?>> MH revise(Class<MH> handlerClass) {
    throw new UnsupportedOperationException("该提供者不支持处理器重新生成。");
  }

  /**
   * 当查询某个元数据类型时，如果没有可用的处理器，则抛出该异常。
   *
   * <p>通常，这意味着需要重新生成对应的元数据处理器。</p>
   */
  class NoHandler extends ControlFlowException {
    /** 发生异常的关系表达式（RelNode）的类 */
    public final Class<? extends RelNode> relClass;

    /**
     * 构造函数，记录无法找到处理器的关系表达式类型。
     *
     * @param relClass 发生异常的关系表达式类型
     */
    public NoHandler(Class<? extends RelNode> relClass) {
      this.relClass = relClass;
    }
  }

}
