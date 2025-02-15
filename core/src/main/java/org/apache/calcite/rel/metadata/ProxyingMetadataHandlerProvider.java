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

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * 基于 `RelMetadataProvider` 构建的 `MetadataHandlerProvider` 实现类。
 *
 * <p>该类使用 Java 代理机制（Proxy）来调用底层的 `RelMetadataProvider`，从而提供元数据处理功能。</p>
 */
public class ProxyingMetadataHandlerProvider implements MetadataHandlerProvider {

  /** 关联的 `RelMetadataProvider`，用于提供元数据处理能力。 */
  private final RelMetadataProvider provider;

  /**
   * 创建一个代理元数据处理器提供者。
   *
   * @param provider 关联的 `RelMetadataProvider`，用于提供元数据查询能力
   */
  public ProxyingMetadataHandlerProvider(RelMetadataProvider provider) {
    this.provider = provider;
  }

  /**
   * 获取指定类型的 `MetadataHandler` 处理器。
   *
   * <p>该方法使用 Java 反射和动态代理机制，通过 `RelMetadataProvider` 获取 `MetadataHandler` 的具体实现，
   * 并在运行时对其方法进行代理调用。</p>
   *
   * @param handlerClass 处理器的类型（必须是 `MetadataHandler` 的子类）
   * @param <MH> 元数据处理器类型
   * @return 代理 `MetadataHandler` 实例
   * @throws UnsupportedOperationException 如果 `handlerClass` 不是预期的 `MetadataHandler` 类型
   * @throws RuntimeException 如果 `MetadataDef` 无法正确解析
   */
  @SuppressWarnings("deprecation")
  @Override public <MH extends MetadataHandler<?>> MH handler(Class<MH> handlerClass) {

    // 获取该处理器实现的接口类型
    Type[] types = handlerClass.getGenericInterfaces();
    if (types.length != 1 || !(types[0] instanceof ParameterizedType)) {
      throw new UnsupportedOperationException("Unexpected failure. " + handlerClass);
    }

    // 获取泛型参数中的元数据类型
    ParameterizedType pType = (ParameterizedType) types[0];
    if (pType.getRawType() != MetadataHandler.class) {
      throw new UnsupportedOperationException("Unexpected failure. " + handlerClass);
    }
    Class<?> metadataType = (Class<?>) pType.getActualTypeArguments()[0];

    // 解析 `DEF` 静态字段，获取元数据定义
    final Field field;
    final MetadataDef<?> def;
    try {
      field = metadataType.getField("DEF");
      def =
          requireNonNull((MetadataDef<?>) field.get(null),
              () -> "Unexpected failure. " + handlerClass);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }

    // 获取 `MetadataDef` 定义的方法，并构建方法映射表
    List<Method> methods = def.methods;
    Map<String, Method> methodMap = methods.stream()
        .collect(Collectors.toMap(Method::getName, f -> f));

    // 使用 Java 代理机制创建 `MetadataHandler`
    InvocationHandler handler = (proxy, method, args) -> {
      // 获取目标方法
      Method metadataMethod =
          requireNonNull(methodMap.get(method.getName()),
              () -> "Not supported: " + method);

      // 获取 `RelNode` 和 `RelMetadataQuery` 参数
      RelNode rel = requireNonNull((RelNode) args[0], "rel must be non null");
      RelMetadataQuery mq =
          requireNonNull((RelMetadataQuery) args[1], "mq must be non null");

      // 使用 `RelMetadataProvider` 获取未绑定的 `Metadata` 实例
      @SuppressWarnings({"unchecked", "rawtypes"})
      UnboundMetadata metadata =
          provider.apply(rel.getClass(),
              (Class<? extends Metadata>) metadataType);

      // 若 `metadata` 为空，则抛出异常提示用户需要提供 `RelNode` 级别的默认处理器
      if (metadata == null) {
        Method handlerMethod =
            Arrays.stream(handlerClass.getMethods())
                .filter(m -> m.getName().equals(metadataMethod.getName()))
                .findFirst()
                .orElseThrow(()
                    -> new IllegalArgumentException("Unable to find method."));
        throw new IllegalArgumentException(
            String.format(Locale.ROOT, "No handler for method [%s] applied to "
                + "argument of type [%s]; we recommend you create a catch-all "
                + "(RelNode) handler", handlerMethod, rel.getClass()));
      }

      // 绑定 `Metadata` 处理器
      Metadata bound =
          requireNonNull(metadata, "expected defined metadata")
              .bind(rel, mq);

      // 处理传入的参数（去除 `rel` 和 `mq`）
      Object[] abbreviatedArgs = new Object[args.length - 2];
      System.arraycopy(args, 2, abbreviatedArgs, 0, abbreviatedArgs.length);

      try {
        // 通过反射调用 `metadataMethod` 方法
        return metadataMethod.invoke(bound, abbreviatedArgs);
      } catch (InvocationTargetException ex) {
        // 处理 `CyclicMetadataException`（循环依赖异常）
        if (ex.getCause() instanceof CyclicMetadataException) {
          throw (CyclicMetadataException) ex.getCause();
        }

        throw new RuntimeException(ex.getCause());
      }
    };

    // 返回代理对象
    return (MH) Proxy.newProxyInstance(
        handlerClass.getClassLoader(),
        new Class[]{handlerClass},
        handler);
  }

}

