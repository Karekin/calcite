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

import org.apache.calcite.config.CalciteSystemProperty;
import org.apache.calcite.interpreter.JaninoRexCompiler;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.metadata.janino.RelMetadataHandlerGeneratorUtil;
import org.apache.calcite.util.Util;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.UncheckedExecutionException;

import org.apiguardian.api.API;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.codehaus.commons.compiler.CompileException;
import org.codehaus.commons.compiler.CompilerFactoryFactory;
import org.codehaus.commons.compiler.ICompilerFactory;
import org.codehaus.commons.compiler.ISimpleCompiler;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * 实现了 {@link RelMetadataProvider} 接口的类，生成一个类，该类分发给底层提供者。
 */
public class JaninoRelMetadataProvider implements RelMetadataProvider, MetadataHandlerProvider {
  private final RelMetadataProvider provider;

  // 常量和静态字段

  // 默认的 JaninoRelMetadataProvider 实例
  public static final JaninoRelMetadataProvider DEFAULT =
      JaninoRelMetadataProvider.of(DefaultRelMetadataProvider.INSTANCE);

  /** 存储由提供者和元数据类型生成的处理器的缓存。
   * 为了使缓存有效，提供者应该正确实现身份方法。 */
  private static final LoadingCache<Key, MetadataHandler<?>> HANDLERS =
      maxSize(CacheBuilder.newBuilder(),
          CalciteSystemProperty.METADATA_HANDLER_CACHE_MAXIMUM_SIZE.value())
          .build(
              CacheLoader.from(key ->
                  generateCompileAndInstantiate(key.handlerClass,
                      key.provider.handlers(key.handlerClass))));

  /** 私有构造函数；使用 {@link #of} 方法。 */
  private JaninoRelMetadataProvider(RelMetadataProvider provider) {
    this.provider = provider;
  }

  /** 创建一个 JaninoRelMetadataProvider 实例。
   *
   * @param provider 底层的提供者
   */
  public static JaninoRelMetadataProvider of(RelMetadataProvider provider) {
    if (provider instanceof JaninoRelMetadataProvider) {
      return (JaninoRelMetadataProvider) provider;
    }
    return new JaninoRelMetadataProvider(provider);
  }

  // 初始化帮助方法
  private static <K, V> CacheBuilder<K, V> maxSize(CacheBuilder<K, V> builder,
      int size) {
    if (size >= 0) {
      builder.maximumSize(size); // 设置缓存的最大大小
    }
    return builder;
  }

  @Override public boolean equals(@Nullable Object obj) {
    // 判断是否与其他对象相等
    return obj == this
        || obj instanceof JaninoRelMetadataProvider
        && ((JaninoRelMetadataProvider) obj).provider.equals(provider);
  }

  @Override public int hashCode() {
    // 返回对象的哈希值
    return 109 + provider.hashCode();
  }

  @Deprecated // 计划在 2.0 版本中移除
  @Override public <@Nullable M extends @Nullable Metadata> UnboundMetadata<M> apply(
      Class<? extends RelNode> relClass, Class<? extends M> metadataClass) {
    throw new UnsupportedOperationException();
  }

  @Deprecated // 计划在 2.0 版本中移除
  @Override public <M extends Metadata> Multimap<Method, MetadataHandler<M>>
  handlers(MetadataDef<M> def) {
    return provider.handlers(def); // 委托给底层提供者
  }

  @Override public List<MetadataHandler<?>> handlers(
      Class<? extends MetadataHandler<?>> handlerClass) {
    return provider.handlers(handlerClass); // 委托给底层提供者
  }

  /**
   * 生成并编译处理器实例。
   * @param handlerClass 处理器类
   * @param handlers 处理器列表
   * @param <MH> 处理器类型
   * @return 生成并编译的处理器
   */
  private static <MH extends MetadataHandler<?>> MH generateCompileAndInstantiate(
      Class<MH> handlerClass,
      List<? extends MetadataHandler<? extends Metadata>> handlers) {

    // 移除重复的处理器
    final List<? extends MetadataHandler<? extends Metadata>> uniqueHandlers = handlers.stream()
        .distinct()
        .collect(Collectors.toList());
    // 生成处理器的名称和代码
    RelMetadataHandlerGeneratorUtil.HandlerNameAndGeneratedCode handlerNameAndGeneratedCode =
        RelMetadataHandlerGeneratorUtil.generateHandler(handlerClass, uniqueHandlers);

    try {
      // 编译并实例化处理器
      return compile(handlerNameAndGeneratedCode.getHandlerName(),
          handlerNameAndGeneratedCode.getGeneratedCode(), handlerClass, uniqueHandlers);
    } catch (CompileException e) {
      throw new RuntimeException("编译错误:\n"
          + handlerNameAndGeneratedCode.getGeneratedCode(), e);
    }
  }

  /**
   * 编译生成的代码并返回处理器实例。
   * @param className 生成的类名
   * @param generatedCode 生成的代码
   * @param handlerClass 处理器类
   * @param argList 传递给构造函数的参数列表
   * @param <MH> 处理器类型
   * @return 处理器实例
   * @throws CompileException 编译异常
   */
  static <MH extends MetadataHandler<?>> MH compile(String className,
      String generatedCode, Class<MH> handlerClass,
      List<? extends Object> argList) throws CompileException {
    final ICompilerFactory compilerFactory;
    ClassLoader classLoader =
        requireNonNull(JaninoRelMetadataProvider.class.getClassLoader(),
            "classLoader");
    try {
      compilerFactory = CompilerFactoryFactory.getDefaultCompilerFactory(classLoader);
    } catch (Exception e) {
      throw new IllegalStateException(
          "无法实例化 Java 编译器", e);
    }

    final ISimpleCompiler compiler = compilerFactory.newSimpleCompiler();
    compiler.setParentClassLoader(JaninoRexCompiler.class.getClassLoader());

    if (CalciteSystemProperty.DEBUG.value()) {
      // 在生成的 Janino 类中添加行号信息
      compiler.setDebuggingInformation(true, true, true);
      System.out.println(generatedCode);
    }

    compiler.cook(generatedCode);
    final Constructor constructor;
    final Object o;
    try {
      constructor = compiler.getClassLoader().loadClass(className)
          .getDeclaredConstructors()[0];
      o = constructor.newInstance(argList.toArray());
    } catch (InstantiationException
             | IllegalAccessException
             | InvocationTargetException
             | ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
    return handlerClass.cast(o); // 将生成的对象转换为指定的处理器类型
  }

  @Override public synchronized <H extends MetadataHandler<?>> H revise(Class<H> handlerClass) {
    try {
      final Key key = new Key(handlerClass, provider);
      //noinspection unchecked
      return handlerClass.cast(HANDLERS.get(key)); // 获取缓存中的处理器
    } catch (UncheckedExecutionException | ExecutionException e) {
      throw Util.throwAsRuntime(Util.causeOrSelf(e));
    }
  }

  /**
   * 注册一些类。此方法不会刷新提供者，但下一次生成提供者时，它将处理这些类。
   * 因此，调用此方法可以减少重新生成的次数。
   */
  @Deprecated
  public void register(Iterable<Class<? extends RelNode>> classes) {
  }

  /**
   * 指示应该为该类提供处理器的异常类，且当前没有处理器。
   * 可能的操作是重新生成处理器类。请使用 {@link MetadataHandlerProvider.NoHandler} 替代。
   */
  @Deprecated
  public static class NoHandler extends MetadataHandlerProvider.NoHandler {
    public NoHandler(Class<? extends RelNode> relClass) {
      super(relClass);
    }
  }

  /** 缓存的键 */
  private static class Key {
    final Class<? extends MetadataHandler<? extends Metadata>> handlerClass;
    final RelMetadataProvider provider;

    private Key(Class<? extends MetadataHandler<?>> handlerClass,
        RelMetadataProvider provider) {
      this.handlerClass = handlerClass;
      this.provider = provider;
    }

    @Override public int hashCode() {
      return (handlerClass.hashCode() * 37
          + provider.hashCode()) * 37; // 根据处理器类和提供者生成哈希码
    }

    @Override public boolean equals(@Nullable Object obj) {
      return this == obj
          || obj instanceof Key
          && ((Key) obj).handlerClass.equals(handlerClass)
          && ((Key) obj).provider.equals(provider);
    }
  }

  @SuppressWarnings("deprecation")
  @Override public <MH extends MetadataHandler<?>> MH handler(final Class<MH> handlerClass) {
    // 为没有处理器的情况创建代理
    return handlerClass.cast(
        Proxy.newProxyInstance(RelMetadataQuery.class.getClassLoader(),
            new Class[] {handlerClass}, (proxy, method, args) -> {
              final RelNode r = requireNonNull((RelNode) args[0], "(RelNode) args[0]");
              throw new NoHandler(r.getClass());
            }));
  }

  @API(status = API.Status.INTERNAL)
  @VisibleForTesting
  public static void clearStaticCache() {
    HANDLERS.invalidateAll(); // 清空缓存
  }
}

