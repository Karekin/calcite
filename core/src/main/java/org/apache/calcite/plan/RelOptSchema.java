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

import org.apache.calcite.rel.type.RelDataTypeFactory;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * `RelOptSchema` 代表一个关系优化模式（Relational Optimization Schema），
 * 其作用是维护一组 {@link RelOptTable}（关系优化表）对象。
 *
 * <p>在查询优化过程中，`RelOptSchema` 提供对表的查找、类型工厂访问，以及注册优化规则的方法。</p>
 */
public interface RelOptSchema {
  //~ Methods ----------------------------------------------------------------

  /**
   * 根据成员访问路径（Qualified Name）获取 {@link RelOptTable} 表对象。
   *
   * <p>例如，对于 Saffron 查询表达式 <code>salesSchema.emps</code>，
   * 解析过程会调用 <code>salesSchema.getTableForMember(Arrays.asList("emps"))</code>。</p>
   *
   * <p>注意，`names.size()` 只有在 JDBC 查询的情况下可能大于 1，
   * 例如访问 <code>["database", "schema", "table"]</code> 这种多层级表名称时。</p>
   *
   * @param names 经过限定的表名称（Qualified Name），可能包含多个层级
   * @return 对应的 {@link RelOptTable}，如果找不到该表则返回 `null`
   */
  @Nullable RelOptTable getTableForMember(List<String> names);

  /**
   * 获取该模式（Schema）关联的 {@link RelDataTypeFactory} 类型工厂。
   *
   * <p>类型工厂用于创建数据类型（如 INTEGER、VARCHAR、DATE），
   * 并且在 SQL 解析、优化和执行过程中被广泛使用。</p>
   *
   * @return 该模式的类型工厂 {@link RelDataTypeFactory}
   */
  RelDataTypeFactory getTypeFactory();

  /**
   * 在优化器中注册该 Schema 相关的所有优化规则。
   *
   * <p>只有在 {@link RelOptPlanner#registerSchema} 方法调用时才会执行该方法，
   * 以确保该 Schema 所支持的优化规则能够被正确应用。</p>
   *
   * @param planner 关系优化器（`RelOptPlanner`），用于注册优化规则
   */
  void registerRules(RelOptPlanner planner);
}

