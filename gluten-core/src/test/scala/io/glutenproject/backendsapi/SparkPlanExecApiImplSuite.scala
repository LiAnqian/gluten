/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.glutenproject.backendsapi

import io.glutenproject.execution.{BroadcastHashJoinExecTransformer, FilterExecBaseTransformer, HashAggregateExecBaseTransformer, NativeColumnarToRowExec, RowToArrowColumnarExec, ShuffledHashJoinExecTransformer}
import io.glutenproject.expression.AliasBaseTransformer
import org.apache.spark.ShuffleDependency
import org.apache.spark.rdd.RDD
import org.apache.spark.serializer.Serializer
import org.apache.spark.shuffle.{GenShuffleWriterParameters, GlutenShuffleWriterWrapper}
import org.apache.spark.sql.{SparkSession, Strategy}
import org.apache.spark.sql.catalyst.expressions.{Attribute, Expression, ExprId, NamedExpression}
import org.apache.spark.sql.catalyst.expressions.aggregate.AggregateExpression
import org.apache.spark.sql.catalyst.optimizer.BuildSide
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.plans.physical.{BroadcastMode, Partitioning}
import org.apache.spark.sql.catalyst.plans.JoinType
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution.{ColumnarRule, SparkPlan}
import org.apache.spark.sql.execution.joins.BuildSideRelation
import org.apache.spark.sql.execution.metric.SQLMetric
import org.apache.spark.sql.types.{Metadata, StructType}
import org.apache.spark.sql.vectorized.ColumnarBatch

class SparkPlanExecApiImplSuite extends ISparkPlanExecApi {

  /**
   * Whether support gluten for current SparkPlan
   *
   * @return
   */
  override def supportedGluten(nativeEngineEnabled: Boolean, plan: SparkPlan): Boolean =
    nativeEngineEnabled

  /**
   * Generate NativeColumnarToRowExec.
   *
   * @param child
   * @return
   */
  override def genNativeColumnarToRowExec(child: SparkPlan): NativeColumnarToRowExec = null

  /**
   * Generate RowToColumnarExec.
   *
   * @param child
   * @return
   */
  override def genRowToColumnarExec(child: SparkPlan): RowToArrowColumnarExec = null

  /**
   * Generate FilterExecTransformer.
   *
   * @param condition : the filter condition
   * @param child     : the chid of FilterExec
   * @return the transformer of FilterExec
   */
  override def genFilterExecTransformer(condition: Expression, child: SparkPlan)
  : FilterExecBaseTransformer = null

  /**
   * Generate HashAggregateExecTransformer.
   */
  override def genHashAggregateExecTransformer(
     requiredChildDistributionExpressions: Option[Seq[Expression]],
     groupingExpressions: Seq[NamedExpression],
     aggregateExpressions: Seq[AggregateExpression],
     aggregateAttributes: Seq[Attribute],
     initialInputBufferOffset: Int,
     resultExpressions: Seq[NamedExpression],
     child: SparkPlan): HashAggregateExecBaseTransformer = null

  /**
   * Generate ShuffledHashJoinExecTransformer.
   */
  def genShuffledHashJoinExecTransformer(leftKeys: Seq[Expression],
                                         rightKeys: Seq[Expression],
                                         joinType: JoinType,
                                         buildSide: BuildSide,
                                         condition: Option[Expression],
                                         left: SparkPlan,
                                         right: SparkPlan): ShuffledHashJoinExecTransformer = null

  /**
   * Generate BroadcastHashJoinExecTransformer.
   */
  def genBroadcastHashJoinExecTransformer(leftKeys: Seq[Expression],
                                          rightKeys: Seq[Expression],
                                          joinType: JoinType,
                                          buildSide: BuildSide,
                                          condition: Option[Expression],
                                          left: SparkPlan,
                                          right: SparkPlan,
                                          isNullAwareAntiJoin: Boolean = false)
  : BroadcastHashJoinExecTransformer = null

  /**
   * Generate Alias transformer.
   *
   * @param child The computation being performed
   * @param name The name to be associated with the result of computing.
   * @param exprId
   * @param qualifier
   * @param explicitMetadata
   * @return a transformer for alias
   */
  def genAliasTransformer(child: Expression, name: String, exprId: ExprId,
                          qualifier: Seq[String], explicitMetadata: Option[Metadata])
  : AliasBaseTransformer = null

  /**
   * Generate ShuffleDependency for ColumnarShuffleExchangeExec.
   *
   * @return
   */
  // scalastyle:off argcount
  override def genShuffleDependency(rdd: RDD[ColumnarBatch], outputAttributes: Seq[Attribute],
                                    newPartitioning: Partitioning, serializer: Serializer,
                                    writeMetrics: Map[String, SQLMetric], dataSize: SQLMetric,
                                    bytesSpilled: SQLMetric, numInputRows: SQLMetric,
                                    computePidTime: SQLMetric, splitTime: SQLMetric,
                                    spillTime: SQLMetric, compressTime: SQLMetric,
                                    prepareTime: SQLMetric
                                   ): ShuffleDependency[Int, ColumnarBatch, ColumnarBatch] = null
  // scalastyle:off argcount

  /**
   * Generate ColumnarShuffleWriter for ColumnarShuffleManager.
   *
   * @return
   */
  override def genColumnarShuffleWriter[K, V](parameters: GenShuffleWriterParameters[K, V]
                                             ): GlutenShuffleWriterWrapper[K, V] = null

  /**
   * Generate ColumnarBatchSerializer for ColumnarShuffleExchangeExec.
   *
   * @return
   */
  override def createColumnarBatchSerializer(schema: StructType,
                                             readBatchNumRows: SQLMetric,
                                             numOutputRows: SQLMetric,
                                             dataSize: SQLMetric): Serializer = null


  /**
   * Generate extended DataSourceV2 Strategies.
   * Currently only for ClickHouse backend.
   *
   * @return
   */
  override def genExtendedDataSourceV2Strategies(): List[SparkSession => Strategy] = List()

  /**
   * Get the backend api name.
   *
   * @return
   */
  override def getBackendName: String = "default"

  /**
   * Create broadcast relation for BroadcastExchangeExec
   */
  override def createBroadcastRelation(mode: BroadcastMode,
                                       child: SparkPlan,
                                       numOutputRows: SQLMetric,
                                       dataSize: SQLMetric): BuildSideRelation = null

  /**
   * Generate extended Analyzers.
   * Currently only for ClickHouse backend.
   *
   * @return
   */
  override def genExtendedAnalyzers(): List[SparkSession => Rule[LogicalPlan]] =
    List()


  /**
   * Generate extended Strategies.
   * Currently only for Velox backend.
   *
   * @return
   */
  override def genExtendedStrategies(): List[SparkSession => Strategy] = List()

  /**
   * Generate extended columnar pre-rules.
   * Currently only for Velox backend.
   *
   * @return
   */
  override def genExtendedColumnarPreRules(): List[SparkSession => Rule[SparkPlan]] = List()

  /**
   * Generate extended columnar post-rules.
   * Currently only for Velox backend.
   *
   * @return
   */
  override def genExtendedColumnarPostRules(): List[SparkSession => Rule[SparkPlan]] = List()
}
