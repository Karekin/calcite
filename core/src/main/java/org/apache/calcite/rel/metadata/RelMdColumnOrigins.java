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

import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.Calc;
import org.apache.calcite.rel.core.Exchange;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sample;
import org.apache.calcite.rel.core.SetOp;
import org.apache.calcite.rel.core.Snapshot;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.TableFunctionScan;
import org.apache.calcite.rel.core.TableModify;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLocalRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexVisitor;
import org.apache.calcite.rex.RexVisitorImpl;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.PolyNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RelMdColumnOrigins 为标准逻辑代数提供了默认的列来源元数据查询实现。
 * 该类主要实现了 {@link RelMetadataQuery#getColumnOrigins} 方法，
 * 用于获取查询计划中列的来源信息。
 */
public class RelMdColumnOrigins
    implements MetadataHandler<BuiltInMetadata.ColumnOrigin> {

  /**
   * 提供用于访问此元数据处理器的元数据提供者。
   */
  public static final RelMetadataProvider SOURCE =
      ReflectiveRelMetadataProvider.reflectiveSource(
          new RelMdColumnOrigins(), BuiltInMetadata.ColumnOrigin.Handler.class);

  //~ 构造方法 -----------------------------------------------------------

  /**
   * 私有构造方法，防止外部实例化。
   */
  private RelMdColumnOrigins() {
  }

  //~ 方法 ----------------------------------------------------------------

  /**
   * 返回此元数据处理器的定义。
   */
  @Override
  public MetadataDef<BuiltInMetadata.ColumnOrigin> getDef() {
    return BuiltInMetadata.ColumnOrigin.DEF;
  }

  /**
   * 获取聚合算子中输出列的来源信息。
   *
   * @param rel           聚合算子
   * @param mq            元数据查询实例
   * @param iOutputColumn 输出列的索引
   * @return 输出列的来源信息集合
   */
  public @Nullable Set<RelColumnOrigin> getColumnOrigins(Aggregate rel,
      RelMetadataQuery mq, int iOutputColumn) {
    if (iOutputColumn < rel.getGroupCount()) {
      // 如果是分组列，直接获取对应输入列的来源信息
      return mq.getColumnOrigins(rel.getInput(), rel.getGroupSet().asList().get(iOutputColumn));
    }

    // 对于聚合列，从对应的输入列派生来源信息
    AggregateCall call =
        rel.getAggCallList().get(iOutputColumn - rel.getGroupCount());

    final Set<RelColumnOrigin> set = new HashSet<>();
    for (Integer iInput : call.getArgList()) {
      // 获取输入列的来源信息
      Set<RelColumnOrigin> inputSet =
          mq.getColumnOrigins(rel.getInput(), iInput);
      inputSet = createDerivedColumnOrigins(inputSet);
      if (inputSet != null) {
        set.addAll(inputSet);
      }
    }
    return set;
  }

  /**
   * 获取连接算子中输出列的来源信息。
   *
   * @param rel           连接算子
   * @param mq            元数据查询实例
   * @param iOutputColumn 输出列的索引
   * @return 输出列的来源信息集合
   */
  public @Nullable Set<RelColumnOrigin> getColumnOrigins(Join rel, RelMetadataQuery mq,
      int iOutputColumn) {
    int nLeftColumns = rel.getLeft().getRowType().getFieldList().size();
    Set<RelColumnOrigin> set;
    boolean derived = false;
    if (iOutputColumn < nLeftColumns) {
      // 输出列属于左输入
      set = mq.getColumnOrigins(rel.getLeft(), iOutputColumn);
      if (rel.getJoinType().generatesNullsOnLeft()) {
        derived = true;
      }
    } else {
      // 输出列属于右输入
      set = mq.getColumnOrigins(rel.getRight(), iOutputColumn - nLeftColumns);
      if (rel.getJoinType().generatesNullsOnRight()) {
        derived = true;
      }
    }
    if (derived) {
      // 如果连接类型为外连接，且可能生成 NULL，则认为列是派生的
      set = createDerivedColumnOrigins(set);
    }
    return set;
  }

  /**
   * 获取集合操作算子中输出列的来源信息。
   *
   * @param rel           集合操作算子
   * @param mq            元数据查询实例
   * @param iOutputColumn 输出列的索引
   * @return 输出列的来源信息集合
   */
  public @Nullable Set<RelColumnOrigin> getColumnOrigins(SetOp rel,
      RelMetadataQuery mq, int iOutputColumn) {
    final Set<RelColumnOrigin> set = new HashSet<>();
    for (RelNode input : rel.getInputs()) {
      // 聚合所有输入的来源信息
      Set<RelColumnOrigin> inputSet = mq.getColumnOrigins(input, iOutputColumn);
      if (inputSet == null) {
        return null;
      }
      set.addAll(inputSet);
    }
    return set;
  }

  /**
   * 获取投影算子中输出列的来源信息。
   *
   * @param rel           投影算子
   * @param mq            元数据查询实例
   * @param iOutputColumn 输出列的索引
   * @return 输出列的来源信息集合
   */
  public @Nullable Set<RelColumnOrigin> getColumnOrigins(Project rel,
      final RelMetadataQuery mq, int iOutputColumn) {
    final RelNode input = rel.getInput();
    RexNode rexNode = rel.getProjects().get(iOutputColumn);

    if (rexNode instanceof RexInputRef) {
      // Direct reference:  no derivation added.
      RexInputRef inputRef = (RexInputRef) rexNode;
      return mq.getColumnOrigins(input, inputRef.getIndex());
    }
    // Anything else is a derivation, possibly from multiple columns.
    final Set<RelColumnOrigin> set = getMultipleColumns(rexNode, input, mq);
    return createDerivedColumnOrigins(set);
  }

  /**
   * 获取 Calc 算子中输出列的来源信息。
   *
   * @param rel           Calc 算子实例
   * @param mq            元数据查询实例
   * @param iOutputColumn 输出列的索引
   * @return 输出列的来源信息集合，如果无法确定来源则返回 null
   */
  public @Nullable Set<RelColumnOrigin> getColumnOrigins(Calc rel,
      final RelMetadataQuery mq, int iOutputColumn) {
    // 获取 Calc 的输入节点
    final RelNode input = rel.getInput();

    // 定义一个 RexShuttle，用于处理局部引用
    final RexShuttle rexShuttle = new RexShuttle() {
      @Override
      public RexNode visitLocalRef(RexLocalRef localRef) {
        // 展开局部引用，获取实际的表达式
        return rel.getProgram().expandLocalRef(localRef);
      }
    };

    // 对 Calc 的投影列表应用 RexShuttle，得到完整的表达式列表
    final List<RexNode> projects =
        new ArrayList<>(rexShuttle.apply(rel.getProgram().getProjectList()));

    // 获取指定输出列的表达式
    final RexNode rexNode = projects.get(iOutputColumn);

    if (rexNode instanceof RexInputRef) {
      // 如果表达式是直接引用输入列，则无派生信息
      RexInputRef inputRef = (RexInputRef) rexNode;
      return mq.getColumnOrigins(input, inputRef.getIndex());
    }

    // 对于其他情况，可能是从多个列派生的
    final Set<RelColumnOrigin> set = getMultipleColumns(rexNode, input, mq);
    return createDerivedColumnOrigins(set);
  }

  /**
   * 获取 TableScan 算子中输出列的来源信息。
   *
   * @param scan          TableScan 算子实例
   * @param mq            元数据查询实例
   * @param iOutputColumn 输出列的索引
   * @return 输出列的来源信息集合，如果无法确定来源则返回 null
   */
  public @Nullable Set<RelColumnOrigin> getColumnOrigins(TableScan scan,
      RelMetadataQuery mq, int iOutputColumn) {
    // 尝试从表中获取自定义的列来源处理器
    final BuiltInMetadata.ColumnOrigin.Handler handler =
        scan.getTable().unwrap(BuiltInMetadata.ColumnOrigin.Handler.class);

    if (handler != null) {
      // 如果存在自定义处理器，调用其方法获取列来源
      return handler.getColumnOrigins(scan, mq, iOutputColumn);
    }

    // 默认情况下，调用通用方法处理
    return getColumnOrigins((RelNode) scan, mq, iOutputColumn);
  }

  /**
   * 获取 Filter 算子中输出列的来源信息。
   *
   * @param rel           Filter 算子实例
   * @param mq            元数据查询实例
   * @param iOutputColumn 输出列的索引
   * @return 输出列的来源信息集合
   */
  public @Nullable Set<RelColumnOrigin> getColumnOrigins(Filter rel,
      RelMetadataQuery mq, int iOutputColumn) {
    // Filter 不修改列来源信息，直接返回输入列的来源
    return mq.getColumnOrigins(rel.getInput(), iOutputColumn);
  }

  /**
   * 获取 Sort 算子中输出列的来源信息。
   *
   * @param rel           Sort 算子实例
   * @param mq            元数据查询实例
   * @param iOutputColumn 输出列的索引
   * @return 输出列的来源信息集合
   */
  public @Nullable Set<RelColumnOrigin> getColumnOrigins(Sort rel, RelMetadataQuery mq,
      int iOutputColumn) {
    // Sort 不修改列来源信息，直接返回输入列的来源
    return mq.getColumnOrigins(rel.getInput(), iOutputColumn);
  }

  /**
   * 获取 TableModify 算子中输出列的来源信息。
   *
   * @param rel           TableModify 算子实例
   * @param mq            元数据查询实例
   * @param iOutputColumn 输出列的索引
   * @return 输出列的来源信息集合
   */
  public @Nullable Set<RelColumnOrigin> getColumnOrigins(TableModify rel, RelMetadataQuery mq,
      int iOutputColumn) {
    // TableModify 不修改列来源信息，直接返回输入列的来源
    return mq.getColumnOrigins(rel.getInput(), iOutputColumn);
  }

  /**
   * 获取 Exchange 算子中输出列的来源信息。
   *
   * @param rel           Exchange 算子实例
   * @param mq            元数据查询实例
   * @param iOutputColumn 输出列的索引
   * @return 输出列的来源信息集合
   */
  public @Nullable Set<RelColumnOrigin> getColumnOrigins(Exchange rel,
      RelMetadataQuery mq, int iOutputColumn) {
    // Exchange 不修改列来源信息，直接返回输入列的来源
    return mq.getColumnOrigins(rel.getInput(), iOutputColumn);
  }

  /**
   * 获取 Sample 算子中输出列的来源信息。
   *
   * @param rel           Sample 算子实例
   * @param mq            元数据查询实例
   * @param iOutputColumn 输出列的索引
   * @return 输出列的来源信息集合
   */
  public @Nullable Set<RelColumnOrigin> getColumnOrigins(Sample rel,
      RelMetadataQuery mq, int iOutputColumn) {
    // Sample 不修改列来源信息，直接返回输入列的来源
    return mq.getColumnOrigins(rel.getInput(), iOutputColumn);
  }

  /**
   * 获取 Snapshot 算子中输出列的来源信息。
   *
   * @param rel           Snapshot 算子实例
   * @param mq            元数据查询实例
   * @param iOutputColumn 输出列的索引
   * @return 输出列的来源信息集合
   */
  public @Nullable Set<RelColumnOrigin> getColumnOrigins(Snapshot rel,
      RelMetadataQuery mq, int iOutputColumn) {
    // Snapshot 不修改列来源信息，直接返回输入列的来源
    return mq.getColumnOrigins(rel.getInput(), iOutputColumn);
  }

  /**
   * 获取 TableFunctionScan 算子中输出列的来源信息。
   *
   * @param rel           TableFunctionScan 算子实例
   * @param mq            元数据查询实例
   * @param iOutputColumn 输出列的索引
   * @return 输出列的来源信息集合
   */
  public @Nullable Set<RelColumnOrigin> getColumnOrigins(TableFunctionScan rel,
      RelMetadataQuery mq, int iOutputColumn) {
    final Set<RelColumnOrigin> set = new HashSet<>();
    Set<RelColumnMapping> mappings = rel.getColumnMappings();

    if (mappings == null) {
      if (!rel.getInputs().isEmpty()) {
        // 如果是非叶子变换，表示无法确定列的来源
        return null;
      } else {
        // 如果是叶子变换，明确表示无列来源
        return set;
      }
    }

    for (RelColumnMapping mapping : mappings) {
      if (mapping.iOutputColumn != iOutputColumn) {
        continue;
      }
      final RelNode input = rel.getInputs().get(mapping.iInputRel);
      final int column = mapping.iInputColumn;
      Set<RelColumnOrigin> origins = mq.getColumnOrigins(input, column);
      if (origins == null) {
        return null;
      }
      if (mapping.derived) {
        origins = createDerivedColumnOrigins(origins);
      }
      set.addAll(origins);
    }
    return set;
  }

  /**
   * 通用方法：获取其他未覆盖算子的输出列来源信息。
   *
   * @param rel           RelNode 算子实例
   * @param mq            元数据查询实例
   * @param iOutputColumn 输出列的索引
   * @return 输出列的来源信息集合
   */
  public @Nullable Set<RelColumnOrigin> getColumnOrigins(RelNode rel,
      RelMetadataQuery mq, int iOutputColumn) {
    if (!rel.getInputs().isEmpty()) {
      // 对于非叶子算子，无法通过通用逻辑处理来源信息
      return null;
    }

    final Set<RelColumnOrigin> set = new HashSet<>();
    RelOptTable table = rel.getTable();

    if (table == null) {
      // 如果表为空，例如 VALUES 子句，返回空集合
      return set;
    }

    // 检测表是否支持投影操作，如果支持则无法确定列来源
    if (table.getRowType() != rel.getRowType()) {
      return null;
    }

    // 添加列来源信息
    set.add(new RelColumnOrigin(table, iOutputColumn, false));
    return set;
  }

  /**
   * 创建派生列的来源信息集合。
   *
   * @param inputSet 输入列的来源信息集合
   * @return 包含派生标志的列来源信息集合，如果输入集合为 null，则返回 null
   */
  private static @PolyNull Set<RelColumnOrigin> createDerivedColumnOrigins(
      @PolyNull Set<RelColumnOrigin> inputSet) {
    if (inputSet == null) {
      // 如果输入集合为 null，直接返回 null
      return null;
    }
    // 创建一个新的集合，用于存储派生列的来源信息
    final Set<RelColumnOrigin> set = new HashSet<>();
    for (RelColumnOrigin rco : inputSet) {
      // 为每个来源列创建一个新的 RelColumnOrigin 实例，标记其为派生列
      RelColumnOrigin derived =
          new RelColumnOrigin(
              rco.getOriginTable(),
              rco.getOriginColumnOrdinal(),
              true);
      set.add(derived);
    }
    return set;
  }

  /**
   * 获取表达式涉及的多个列的来源信息。
   *
   * @param rexNode 表达式节点
   * @param input   输入的 RelNode 节点
   * @param mq      元数据查询实例
   * @return 表达式中涉及的列的来源信息集合
   */
  private static Set<RelColumnOrigin> getMultipleColumns(RexNode rexNode, RelNode input,
      final RelMetadataQuery mq) {
    // 创建一个集合用于存储列来源信息
    final Set<RelColumnOrigin> set = new HashSet<>();

    // 定义一个 RexVisitor，用于访问表达式中的列引用
    final RexVisitor<Void> visitor =
        new RexVisitorImpl<Void>(true) {
          @Override
          public Void visitInputRef(RexInputRef inputRef) {
            // 获取列引用的来源信息
            Set<RelColumnOrigin> inputSet =
                mq.getColumnOrigins(input, inputRef.getIndex());
            if (inputSet != null) {
              // 将来源信息添加到集合中
              set.addAll(inputSet);
            }
            return null;
          }
        };

    // 使用访问者模式遍历表达式节点
    rexNode.accept(visitor);
    return set;
  }

}

