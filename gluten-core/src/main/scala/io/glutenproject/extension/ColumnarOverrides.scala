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

package io.glutenproject.extension

import org.apache.spark.internal.Logging
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.SparkSessionExtensions
import org.apache.spark.sql.catalyst.rules.{PlanChangeLogger, Rule}
import org.apache.spark.sql.execution._
import org.apache.spark.sql.execution.adaptive._
import org.apache.spark.sql.execution.aggregate.HashAggregateExec
import org.apache.spark.sql.execution.columnar.InMemoryTableScanExec
import org.apache.spark.sql.execution.datasources.v2.BatchScanExec
import org.apache.spark.sql.execution.exchange._
import org.apache.spark.sql.execution.joins._
import org.apache.spark.sql.execution.window.WindowExec
import org.apache.spark.sql.internal.SQLConf
import io.glutenproject.GlutenConfig
import io.glutenproject.GlutenSparkExtensionsInjector
import io.glutenproject.backendsapi.BackendsApiManager
import io.glutenproject.execution._
import io.glutenproject.expression.ExpressionConverter
import io.glutenproject.extension.columnar.AddTransformHintRule
import io.glutenproject.extension.columnar.TransformHints
import io.glutenproject.extension.columnar.RemoveTransformHintRule
import io.glutenproject.extension.columnar.TransformHint

import org.apache.spark.sql.catalyst.optimizer.{BuildLeft, BuildRight, BuildSide}
import org.apache.spark.sql.catalyst.plans.{LeftOuter, LeftSemi, RightOuter}

// This rule will conduct the conversion from Spark plan to the plan transformer.
// The plan with a row guard on the top of it will not be converted.
case class TransformPreOverrides() extends Rule[SparkPlan] {
  val columnarConf: GlutenConfig = GlutenConfig.getSessionConf
  @transient private val logOnLevel: ( => String) => Unit =
    columnarConf.transformPlanLogLevel match {
      case "TRACE" => logTrace(_)
      case "DEBUG" => logDebug(_)
      case "INFO" => logInfo(_)
      case "WARN" => logWarning(_)
      case "ERROR" => logError(_)
      case _ => logDebug(_)
    }
  @transient private val planChangeLogger = new PlanChangeLogger[SparkPlan]()

  def replaceWithTransformerPlan(plan: SparkPlan, isSupportAdaptive: Boolean): SparkPlan = {
    TransformHints.getHint(plan) match {
      case TransformHint.TRANSFORM_SUPPORTED =>
      // supported, break
      case TransformHint.TRANSFORM_UNSUPPORTED =>
        logDebug(s"Columnar Processing for ${plan.getClass} is under row guard.")
        plan match {
          case plan: ShuffledHashJoinExec =>
            if (columnarConf.isVeloxBackend) {
              // Because we manually removed the build side limitation for LeftOuter, LeftSemi and
              // RightOuter, need to change the build side back if this join fallback into vanilla
              // Spark for execution.
              return ShuffledHashJoinExec(
                plan.leftKeys,
                plan.rightKeys,
                plan.joinType,
                getSparkSupportedBuildSide(plan),
                plan.condition,
                replaceWithTransformerPlan(plan.left, isSupportAdaptive),
                replaceWithTransformerPlan(plan.right, isSupportAdaptive),
                plan.isSkewJoin)
            } else {
              return plan.withNewChildren(
                plan.children.map(replaceWithTransformerPlan(_, isSupportAdaptive)))
            }
          case _ =>
            return plan.withNewChildren(
              plan.children.map(replaceWithTransformerPlan(_, isSupportAdaptive)))
        }
    }
    plan match {
      case plan: BatchScanExec =>
        logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
        val newPartitionFilters =
          ExpressionConverter.transformDynamicPruningExpr(plan.runtimeFilters)
        new BatchScanExecTransformer(plan.output, plan.scan, newPartitionFilters)
      case plan: FileSourceScanExec =>
        logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
        new FileSourceScanExecTransformer(
          plan.relation,
          plan.output,
          plan.requiredSchema,
          ExpressionConverter.transformDynamicPruningExpr(plan.partitionFilters),
          plan.optionalBucketSet,
          plan.optionalNumCoalescedBuckets,
          plan.dataFilters,
          plan.tableIdentifier,
          plan.disableBucketedScan)
      case plan: CoalesceExec =>
        logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
        CoalesceExecTransformer(
          plan.numPartitions, replaceWithTransformerPlan(plan.child, isSupportAdaptive))
      case plan: InMemoryTableScanExec =>
        logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
        ColumnarInMemoryTableScanExec(plan.attributes, plan.predicates, plan.relation)
      case plan: ProjectExec =>
        val columnarChild = replaceWithTransformerPlan(plan.child, isSupportAdaptive)
        logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
        ProjectExecTransformer(plan.projectList, columnarChild)
      case plan: FilterExec =>
        // Push down the left conditions in Filter into Scan.
        val newChild =
          if (plan.child.isInstanceOf[FileSourceScanExec] ||
            plan.child.isInstanceOf[BatchScanExec]) {
            FilterHandler.applyFilterPushdownToScan(plan)
          } else {
            replaceWithTransformerPlan(plan.child, isSupportAdaptive)
          }
        logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
        BackendsApiManager.getSparkPlanExecApiInstance
          .genFilterExecTransformer(plan.condition, newChild)
      case plan: HashAggregateExec =>
        val child = replaceWithTransformerPlan(plan.child, isSupportAdaptive)
        logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
        BackendsApiManager.getSparkPlanExecApiInstance
          .genHashAggregateExecTransformer(
            plan.requiredChildDistributionExpressions,
            plan.groupingExpressions,
            plan.aggregateExpressions,
            plan.aggregateAttributes,
            plan.initialInputBufferOffset,
            plan.resultExpressions,
            child)
      case plan: UnionExec =>
        val children = plan.children.map(
          replaceWithTransformerPlan(_, isSupportAdaptive))
        logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
        UnionExecTransformer(children)
      case plan: ExpandExec =>
        val child = replaceWithTransformerPlan(plan.child, isSupportAdaptive)
        logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
        ExpandExecTransformer(plan.projections, plan.output, child)
      case plan: SortExec =>
        val child = replaceWithTransformerPlan(plan.child, isSupportAdaptive)
        logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
        SortExecTransformer(plan.sortOrder, plan.global, child, plan.testSpillFrequency)
      case plan: ShuffleExchangeExec =>
        val child = replaceWithTransformerPlan(plan.child, isSupportAdaptive)
        logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
        if ((child.supportsColumnar || columnarConf.enablePreferColumnar) &&
          columnarConf.enableColumnarShuffle) {
          if (isSupportAdaptive) {
            ColumnarShuffleExchangeAdaptor(plan.outputPartitioning, child)
          } else {
            CoalesceBatchesExec(ColumnarShuffleExchangeExec(plan.outputPartitioning, child))
          }
        } else {
          plan.withNewChildren(Seq(child))
        }
      case plan: ShuffledHashJoinExec =>
        val left = replaceWithTransformerPlan(plan.left, isSupportAdaptive)
        val right = replaceWithTransformerPlan(plan.right, isSupportAdaptive)
        logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
        BackendsApiManager.getSparkPlanExecApiInstance
          .genShuffledHashJoinExecTransformer(
            plan.leftKeys,
            plan.rightKeys,
            plan.joinType,
            plan.buildSide,
            plan.condition,
            left,
            right)
      case plan: SortMergeJoinExec =>
        val left = replaceWithTransformerPlan(plan.left, isSupportAdaptive)
        val right = replaceWithTransformerPlan(plan.right, isSupportAdaptive)
        logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
        SortMergeJoinExecTransformer(
          plan.leftKeys,
          plan.rightKeys,
          plan.joinType,
          plan.condition,
          left,
          right,
          plan.isSkewJoin)
      case plan: BroadcastExchangeExec =>
        val child = replaceWithTransformerPlan(plan.child, isSupportAdaptive)
        logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
        if (isSupportAdaptive) {
          ColumnarBroadcastExchangeAdaptor(plan.mode, child)
        } else {
          ColumnarBroadcastExchangeExec(plan.mode, child)
        }
      case plan: BroadcastHashJoinExec =>
        val left = replaceWithTransformerPlan(plan.left, isSupportAdaptive)
        val right = replaceWithTransformerPlan(plan.right, isSupportAdaptive)
        BackendsApiManager.getSparkPlanExecApiInstance
          .genBroadcastHashJoinExecTransformer(
            plan.leftKeys,
            plan.rightKeys,
            plan.joinType,
            plan.buildSide,
            plan.condition,
            left,
            right,
            isNullAwareAntiJoin = plan.isNullAwareAntiJoin)
      case plan: AQEShuffleReadExec if columnarConf.enableColumnarShuffle =>
        plan.child match {
          case shuffle: ColumnarShuffleExchangeAdaptor =>
            logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
            CoalesceBatchesExec(ColumnarAQEShuffleReadExec(plan.child, plan.partitionSpecs))
          case ShuffleQueryStageExec(_, shuffle: ColumnarShuffleExchangeAdaptor, _) =>
            logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
            CoalesceBatchesExec(ColumnarAQEShuffleReadExec(plan.child, plan.partitionSpecs))
          case ShuffleQueryStageExec(_, reused: ReusedExchangeExec, _) =>
            reused match {
              case ReusedExchangeExec(_, shuffle: ColumnarShuffleExchangeAdaptor) =>
                logDebug(s"Columnar Processing for ${plan.getClass} is currently supported.")
                CoalesceBatchesExec(
                  ColumnarAQEShuffleReadExec(plan.child, plan.partitionSpecs))
              case _ =>
                plan
            }
          case _ =>
            plan
        }
      case plan: WindowExec =>
        WindowExecTransformer(
          plan.windowExpression,
          plan.partitionSpec,
          plan.orderSpec,
          replaceWithTransformerPlan(plan.child, isSupportAdaptive))
      case p =>
        logDebug(s"Transformation for ${p.getClass} is currently not supported.")
        val children = plan.children.map(replaceWithTransformerPlan(_, isSupportAdaptive))
        p.withNewChildren(children)
    }
  }

  /**
   * Get the build side supported by the execution of vanilla Spark.
   * @param plan: shuffled hash join plan
   * @return the supported build side
   */
  private def getSparkSupportedBuildSide(plan: ShuffledHashJoinExec): BuildSide = {
    plan.joinType match {
      case LeftOuter | LeftSemi => BuildRight
      case RightOuter => BuildLeft
      case _ => plan.buildSide
    }
  }

  def apply(plan: SparkPlan): SparkPlan = {
    logOnLevel(s"${ruleName} before plan ${plan.toString()}")
    val newPlan = replaceWithTransformerPlan(plan, ColumnarOverrides.supportAdaptive(plan))
    planChangeLogger.logRule(ruleName, plan, newPlan)
    logOnLevel(s"${ruleName} after plan ${plan.toString()}")
    newPlan
  }
}

// This rule will try to convert the row-to-columnar and columnar-to-row
// into columnar implementations.
case class TransformPostOverrides() extends Rule[SparkPlan] {
  val columnarConf = GlutenConfig.getSessionConf
  @transient private val logOnLevel: ( => String) => Unit =
    columnarConf.transformPlanLogLevel match {
      case "TRACE" => logTrace(_)
      case "DEBUG" => logDebug(_)
      case "INFO" => logInfo(_)
      case "WARN" => logWarning(_)
      case "ERROR" => logError(_)
      case _ => logDebug(_)
    }
  @transient private val planChangeLogger = new PlanChangeLogger[SparkPlan]()

  def replaceWithTransformerPlan(plan: SparkPlan): SparkPlan = plan match {
    case plan: RowToColumnarExec =>
      val child = replaceWithTransformerPlan(plan.child)
      logDebug(s"ColumnarPostOverrides RowToArrowColumnarExec(${child.getClass})")
      BackendsApiManager.getSparkPlanExecApiInstance.genRowToColumnarExec(child)
    case ColumnarToRowExec(child: ColumnarShuffleExchangeAdaptor) =>
      replaceWithTransformerPlan(child)
    case ColumnarToRowExec(child: ColumnarBroadcastExchangeAdaptor) =>
      replaceWithTransformerPlan(child)
    case ColumnarToRowExec(child: BroadcastQueryStageExec) =>
      replaceWithTransformerPlan(child)
    case ColumnarToRowExec(child: CoalesceBatchesExec) =>
      plan.withNewChildren(Seq(replaceWithTransformerPlan(child.child)))
    case plan: ColumnarToRowExec =>
      if (columnarConf.enableNativeColumnarToRow) {
        val child = replaceWithTransformerPlan(plan.child)
        logDebug(s"ColumnarPostOverrides NativeColumnarToRowExec(${child.getClass})")
        val nativeConversion =
          BackendsApiManager.getSparkPlanExecApiInstance.genNativeColumnarToRowExec(child)
        if (nativeConversion.doValidate()) {
          nativeConversion
        } else {
          logDebug("NativeColumnarToRow : Falling back to ColumnarToRow...")
          plan.withNewChildren(plan.children.map(replaceWithTransformerPlan))
        }
      } else {
        val children = plan.children.map(replaceWithTransformerPlan)
        plan.withNewChildren(children)
      }
    case r: SparkPlan
      if !r.isInstanceOf[QueryStageExec] && !r.supportsColumnar && r.children.exists(c =>
        c.isInstanceOf[ColumnarToRowExec]) =>
      // This is a fix for when DPP and AQE both enabled,
      // ColumnarExchange maybe child as a Row SparkPlan
      val children = r.children.map {
        case c: ColumnarToRowExec =>
          if (columnarConf.enableNativeColumnarToRow) {
            val child = replaceWithTransformerPlan(c.child)
            val nativeConversion =
              BackendsApiManager.getSparkPlanExecApiInstance.genNativeColumnarToRowExec(child)
            if (nativeConversion.doValidate()) {
              nativeConversion
            } else {
              logInfo("NativeColumnarToRow : Falling back to ColumnarToRow...")
              c.withNewChildren(c.children.map(replaceWithTransformerPlan))
            }
          } else {
            c.withNewChildren(c.children.map(replaceWithTransformerPlan))
          }
        case other =>
          replaceWithTransformerPlan(other)
      }
      r.withNewChildren(children)
    case p =>
      val children = p.children.map(replaceWithTransformerPlan)
      p.withNewChildren(children)
  }

  // apply for the physical not final plan
  def apply(plan: SparkPlan): SparkPlan = {
    logOnLevel(s"${ruleName} before plan ${plan.toString()}")
    val newPlan = replaceWithTransformerPlan(plan)
    planChangeLogger.logRule(ruleName, plan, newPlan)
    logOnLevel(s"${ruleName} after plan ${plan.toString()}")
    newPlan
  }
}

case class ColumnarOverrideRules(session: SparkSession) extends ColumnarRule with Logging {
  var isSupportAdaptive: Boolean = true

  @transient private lazy val logOnLevel: ( => String) => Unit =
  GlutenConfig.getSessionConf.transformPlanLogLevel match {
    case "TRACE" => logTrace(_)
    case "DEBUG" => logDebug(_)
    case "INFO" => logInfo(_)
    case "WARN" => logWarning(_)
    case "ERROR" => logError(_)
    case _ => logDebug(_)
  }
  @transient private lazy val planChangeLogger = new PlanChangeLogger[SparkPlan]()
  // Do not create rules in class initialization as we should access SQLConf
  // while creating the rules. At this time SQLConf may not be there yet.

  def preOverrides: List[SparkSession => Rule[SparkPlan]] =
    List((_: SparkSession) => AddTransformHintRule(),
      (_: SparkSession) => TransformPreOverrides(),
      (_: SparkSession) => RemoveTransformHintRule()) :::
      BackendsApiManager.getSparkPlanExecApiInstance.genExtendedColumnarPreRules()

  def postOverrides: List[SparkSession => Rule[SparkPlan]] =
    List((_: SparkSession) => TransformPostOverrides()) :::
      BackendsApiManager.getSparkPlanExecApiInstance.genExtendedColumnarPostRules() :::
      List((_: SparkSession) => ColumnarCollapseCodegenStages(GlutenConfig.getSessionConf))

  override def preColumnarTransitions: Rule[SparkPlan] = plan => {
    val supportedGluten = BackendsApiManager.getSparkPlanExecApiInstance.supportedGluten(
      nativeEngineEnabled,
      plan)

    if (supportedGluten) {
      var overridden: SparkPlan = plan
      val startTime = System.nanoTime()
      logOnLevel(s"preColumnarTransitions preOverriden plan ${plan.toString}")
      preOverrides.foreach { r =>
        overridden = r(session)(overridden)
        planChangeLogger.logRule(r(session).ruleName, plan, overridden)
      }
      logOnLevel(s"preColumnarTransitions afterOverriden plan ${plan.toString}")
      logInfo(
        s"preTransform SparkPlan took: ${(System.nanoTime() - startTime) / 1000000.0} ms.")
      overridden
    } else {
      plan
    }
  }

  override def postColumnarTransitions: Rule[SparkPlan] = plan => {
    val supportedGluten = BackendsApiManager.getSparkPlanExecApiInstance.supportedGluten(
      nativeEngineEnabled,
      plan)

    logOnLevel(s"postColumnarTransitions preOverriden plan ${plan.toString}")
    if (supportedGluten) {
      var overridden: SparkPlan = plan
      val startTime = System.nanoTime()
      postOverrides.foreach { r =>
        overridden = r(session)(overridden)
        planChangeLogger.logRule(r(session).ruleName, plan, overridden)
      }
      logOnLevel(s"postColumnarTransitions afterOverriden plan ${overridden.toString}")
      logInfo(
        s"postTransform SparkPlan took: ${(System.nanoTime() - startTime) / 1000000.0} ms.")
      overridden
    } else {
      plan
    }
  }

  def nativeEngineEnabled: Boolean = GlutenConfig.getSessionConf.enableNativeEngine

}

object ColumnarOverrides extends GlutenSparkExtensionsInjector {

  def sanityCheck(plan: SparkPlan): Boolean =
    plan.logicalLink.isDefined

  def supportAdaptive(plan: SparkPlan): Boolean = {
    // TODO migrate dynamic-partition-pruning onto adaptive execution.
    // Only QueryStage will have Exchange as Leaf Plan
    val isLeafPlanExchange = plan match {
      case e: Exchange => true
      case other => false
    }
    isLeafPlanExchange || (SQLConf.get.adaptiveExecutionEnabled && (sanityCheck(plan) &&
      !plan.logicalLink.exists(_.isStreaming) &&
      plan.children.forall(supportAdaptive)))
  }

  override def inject(extensions: SparkSessionExtensions): Unit = {
    extensions.injectColumnar(ColumnarOverrideRules)
  }
}
