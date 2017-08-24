/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.internal.cypher.acceptance

import org.neo4j.cypher.NewRuntimeMonitor.{NewPlanSeen, UnableToCompileQuery}
import org.neo4j.cypher.internal.compatibility.v3_3.runtime.planDescription.InternalPlanDescription
import org.neo4j.cypher.internal.compatibility.v3_3.runtime.planDescription.InternalPlanDescription.Arguments.{Planner => IPDPlanner, Runtime => IPDRuntime}
import org.neo4j.cypher.internal.compatibility.v3_3.runtime.{CRS, CartesianPoint, GeographicPoint}
import org.neo4j.cypher.internal.compiler.v3_1.{CartesianPoint => CartesianPointv3_1, GeographicPoint => GeographicPointv3_1}
import org.neo4j.cypher.internal.compiler.v3_2.{CartesianPoint => CartesianPointv3_2, GeographicPoint => GeographicPointv3_2}
import org.neo4j.cypher.internal.frontend.v3_3.helpers.Eagerly
import org.neo4j.cypher.internal.frontend.v3_3.test_helpers.CypherTestSupport
import org.neo4j.cypher.internal.{InternalExecutionResult, RewindableExecutionResult}
import org.neo4j.cypher.javacompat.internal.GraphDatabaseCypherService
import org.neo4j.cypher.{CypherException, ExecutionEngineFunSuite, NewPlannerMonitor, NewRuntimeMonitor}
import org.neo4j.graphdb.Result
import org.neo4j.graphdb.config.Setting
import org.neo4j.graphdb.factory.GraphDatabaseSettings
import org.neo4j.test.TestEnterpriseGraphDatabaseFactory
import org.scalatest.Assertions

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

trait CypherComparisonSupport extends CypherTestSupport {
  self: ExecutionEngineFunSuite =>

  import CypherComparisonSupport._

  override def databaseConfig(): collection.Map[Setting[_], String] = {
    Map(GraphDatabaseSettings.cypher_hints_error -> "true")
  }

  override protected def createGraphDatabase(config: collection.Map[Setting[_], String] = databaseConfig()): GraphDatabaseCypherService = {
    new GraphDatabaseCypherService(new TestEnterpriseGraphDatabaseFactory().newImpermanentDatabase(config.asJava))
  }

  /**
    * Get rid of Arrays and java.util.Map to make it easier to compare results by equality.
    */
  implicit class RichInternalExecutionResults(res: InternalExecutionResult) {
    def toComparableResultWithOptions(replaceNaNs: Boolean): Seq[Map[String, Any]] = res.toList.toComparableSeq(replaceNaNs)

    def toComparableResult: Seq[Map[String, Any]] = res.toList.toComparableSeq(replaceNaNs = false)
  }

  implicit class RichMapSeq(res: Seq[Map[String, Any]]) {

    import scala.collection.JavaConverters._

    object NanReplacement

    def toComparableSeq(replaceNaNs: Boolean): Seq[Map[String, Any]] = {
      def convert(v: Any): Any = v match {
        case p: GeographicPointv3_1 => GeographicPoint(p.longitude, p.latitude, CRS(p.crs.name, p.crs.code, p.crs.url))
        case p: CartesianPointv3_1 => CartesianPoint(p.x, p.y, CRS(p.crs.name, p.crs.code, p.crs.url))
        case p: GeographicPointv3_2 => GeographicPoint(p.longitude, p.latitude, CRS(p.crs.name, p.crs.code, p.crs.url))
        case p: CartesianPointv3_2 => CartesianPoint(p.x, p.y, CRS(p.crs.name, p.crs.code, p.crs.url))
        case a: Array[_] => a.toList.map(convert)
        case m: Map[_, _] =>
          Eagerly.immutableMapValues(m, convert)
        case m: java.util.Map[_, _] =>
          Eagerly.immutableMapValues(m.asScala, convert)
        case l: java.util.List[_] => l.asScala.map(convert)
        case d: java.lang.Double if replaceNaNs && java.lang.Double.isNaN(d) => NanReplacement
        case m => m
      }

      res.map((map: Map[String, Any]) => map.map {
        case (k, v) => k -> convert(v)
      })
    }
  }

  override protected def initTest() {
    super.initTest()
    self.kernelMonitors.addMonitorListener(newPlannerMonitor)
    self.kernelMonitors.addMonitorListener(newRuntimeMonitor)
  }

  private def extractFirstScenario(config: TestConfiguration): TestScenario = {
    val preferredScenario = TestScenario(DefaultVersion, DefaultPlanner, Interpreted)
    if (config.scenarios.contains(preferredScenario))
      preferredScenario
    else
      config.scenarios.head
  }

  protected def updateWithAndExpectPlansToBeSimilar(expectedSuccessFrom: TestConfiguration,
                                                    query: String,
                                                    checkPlans: Boolean,
                                                    params: (String, Any)*): InternalExecutionResult = {
    val firstScenario = extractFirstScenario(expectedSuccessFrom)

    val positiveResults = (Configs.AbsolutelyAll.scenarios - firstScenario).flatMap {
      thisScenario =>
        thisScenario.prepare()
        val tryResult = graph.rollback(Try(innerExecute(s"CYPHER ${thisScenario.preparserOptions} $query", params.toMap)))

        val expectedToSucceed = expectedSuccessFrom.scenarios.contains(thisScenario)

        if (expectedToSucceed) {
          val thisResult = tryResult.get
          thisScenario.checkStateForSuccess(query)
          thisScenario.checkResultForSuccess(query, thisResult)
          Some(thisResult -> thisScenario.name)
        } else {
          thisScenario.checkStateForFailure(query)
          thisScenario.checkResultForFailure(query, tryResult)
          None
        }

    }

    firstScenario.prepare()
    val lastResult = innerExecute(s"CYPHER ${firstScenario.preparserOptions} $query", params.toMap)
    firstScenario.checkStateForSuccess(query)
    firstScenario.checkResultForSuccess(query, lastResult)

    positiveResults.foreach {
      case (result, name) =>
        if (checkPlans && !Configs.AllRulePlanners.scenariosNames.contains(name)) {
          assertPlansSimilar(lastResult, result, s"$name did not equal ${firstScenario.name}")
        }
        assertResultsAreSame(result, lastResult, query, s"$name returned different results than ${firstScenario.name}")
    }

    lastResult
  }

  protected def updateWithAndExpectPlansToBeSimilar(expectedSuccessFrom: TestConfiguration, query: String, params: (String, Any)*): InternalExecutionResult = updateWithAndExpectPlansToBeSimilar(expectedSuccessFrom, query, true, params: _*)

  protected def updateWith(expectedSuccessFrom: TestConfiguration, query: String, params: (String, Any)*): InternalExecutionResult = updateWithAndExpectPlansToBeSimilar(expectedSuccessFrom, query, false, params: _*)

  protected def succeedWithAndExpectPlansToBeSimilar(expectedSuccessFrom: TestConfiguration, query: String, params: (String, Any)*): InternalExecutionResult = succeedWithAndMaybeCheckPlans(expectedSuccessFrom, query, true, params: _*)

  protected def succeedWith(expectedSuccessFrom: TestConfiguration, query: String, params: (String, Any)*): InternalExecutionResult = succeedWithAndMaybeCheckPlans(expectedSuccessFrom, query, false, params: _*)

  protected def succeedWithAndMaybeCheckPlans(expectedSuccessFrom: TestConfiguration,
                                              query: String,
                                              checkPlans: Boolean,
                                              params: (String, Any)*):
  InternalExecutionResult = {
    if (expectedSuccessFrom.scenarios.isEmpty) {
      for (thisScenario <- Configs.AbsolutelyAll.scenarios) {
        thisScenario.prepare()
        val tryResult = Try(innerExecute(s"CYPHER ${thisScenario.preparserOptions} $query", params.toMap))
        thisScenario.checkStateForFailure(query)
        thisScenario.checkResultForFailure(query, tryResult)
      }
      null
    } else {
      val firstScenario = extractFirstScenario(expectedSuccessFrom)
      firstScenario.prepare()
      val firstResult: InternalExecutionResult = innerExecute(s"CYPHER ${firstScenario.preparserOptions} $query", params.toMap)
      firstScenario.checkStateForSuccess(query)

      for (thisScenario <- Configs.AbsolutelyAll.scenarios if thisScenario != firstScenario) {
        thisScenario.prepare()
        val tryResult = Try(innerExecute(s"CYPHER ${thisScenario.preparserOptions} $query", params.toMap))

        val expectedToSucceed = expectedSuccessFrom.scenarios.contains(thisScenario)

        if (expectedToSucceed) {
          val thisResult = tryResult.get
          thisScenario.checkStateForSuccess(query)
          thisScenario.checkResultForSuccess(query, thisResult)
          if (checkPlans && !Configs.AllRulePlanners.scenarios.contains(thisScenario)) {
            assertPlansSimilar(firstResult, thisResult, "${firstScenario.name} returned different results than ${thisScenario.name}")
          }
          assertResultsAreSame(thisResult, firstResult, query, s"${thisScenario.name} returned different results than ${firstScenario.name}", replaceNaNs = true)
        } else {
          thisScenario.checkStateForFailure(query)
          thisScenario.checkResultForFailure(query, tryResult)
        }
      }

      firstResult
    }
  }

  private def assertPlansSimilar(firstResult: InternalExecutionResult, thisResult: InternalExecutionResult, hint: String) = {
    val currentOps = firstResult.executionPlanDescription().flatten.map(simpleName)
    val otherOps = thisResult.executionPlanDescription().flatten.map(simpleName)
    withClue(hint + "\n" + thisResult.executionPlanDescription() + "\n Did not equal \n" + firstResult.executionPlanDescription()) {
      assert(currentOps equals otherOps)
    }
  }

  private def simpleName(plan: InternalPlanDescription): String = plan.name.replace("SetNodeProperty", "SetProperty").toLowerCase

  protected def failWithError(expectedSpecificFailureFrom: TestConfiguration, query: String, message: String, params: (String, Any)*):
  Unit = {
    for (thisScenario <- Configs.AbsolutelyAll.scenarios) {
      thisScenario.prepare()
      val expectedToFailWithSpecificMessage = expectedSpecificFailureFrom.scenarios.contains(thisScenario)

      try {
        innerExecute(s"CYPHER ${thisScenario.preparserOptions} $query", params.toMap)
        if (expectedToFailWithSpecificMessage) {
          fail("Unexpectedly Succeeded in " + thisScenario.name + " instead of failing with this message:'" + message + "'")
        } else {
          fail("Unexpectedly Succeeded in " + thisScenario.name)
        }
      } catch {
        case e: CypherException =>
          if (expectedToFailWithSpecificMessage) {
            if (!e.getMessage.contains(message)) {
              fail("Correctly failed in " + thisScenario.name + " but instead of '" + message +
                "' the error message was '" + e.getMessage + "'")
            }
          } else {
            if (e.getMessage.contains(message)) {
              fail("Unexpectedly (but correctly!) failed in " + thisScenario.name + " with the correct message. Did you forget to add this config?")
            }
            // It failed like expected, and we did not specify any message for this config
          }

        case e: Throwable => throw e
      }
    }
  }

  protected def assertResultsAreSame(result1: InternalExecutionResult, result2: InternalExecutionResult, queryText: String, errorMsg: String, replaceNaNs: Boolean = false) {
    withClue(errorMsg) {
      if (queryText.toLowerCase contains "order by") {
        result1.toComparableResultWithOptions(replaceNaNs) should contain theSameElementsInOrderAs result2.toComparableResultWithOptions(replaceNaNs)
      } else {
        result1.toComparableResultWithOptions(replaceNaNs) should contain theSameElementsAs result2.toComparableResultWithOptions(replaceNaNs)
      }
    }
  }

  private def innerExecute(queryText: String, params: Map[String, Any]): InternalExecutionResult = {
    val innerResult = eengine.execute(queryText, params, graph.transactionalContext(query = queryText -> params))
    rewindableResult(innerResult)
  }

  private def rewindableResult(result: Result): InternalExecutionResult = RewindableExecutionResult(result)

  object Configs {

    def CompiledSource: TestConfiguration = TestScenario(V3_3, CypherComparisonSupport.Cost, CypherComparisonSupport.CompiledSource)

    def CompiledByteCode: TestConfiguration = TestScenario(V3_3, CypherComparisonSupport.Cost, CompiledBytecode)

    def Compiled: TestConfiguration = CompiledByteCode + CompiledSource

    def Interpreted: TestConfiguration = CommunityInterpreted + SlottedInterpreted

    def CommunityInterpreted: TestConfiguration = AbsolutelyAll - Compiled - Procs - SlottedInterpreted

    def SlottedInterpreted: TestConfiguration = TestScenario(DefaultVersion, DefaultPlanner, Slotted)

    def Cost2_3: TestConfiguration = TestScenario(V2_3, CypherComparisonSupport.Cost, DefaultRuntime)

    def Version2_3: TestConfiguration = TestScenario(V2_3, Rule, DefaultRuntime) + Cost2_3

    def Version3_1: TestConfiguration = TestScenario(V3_1, Rule, DefaultRuntime) + TestScenario(V3_1, CypherComparisonSupport.Cost, DefaultRuntime)

    def Version3_2: TestConfiguration = TestScenario(V3_2, CypherComparisonSupport.Cost, DefaultRuntime)

    def Version3_3: TestConfiguration = Compiled + TestScenario(DefaultVersion, DefaultPlanner, CypherComparisonSupport.Interpreted) + TestScenario(DefaultVersion, Rule, DefaultRuntime) +
      SlottedInterpreted

    def AllRulePlanners: TestConfiguration = TestScenario(V3_1, Rule, DefaultRuntime) + TestScenario(V2_3, Rule, DefaultRuntime) + TestScenario(DefaultVersion, Rule, DefaultRuntime)

    def Cost: TestConfiguration = Compiled + TestScenario(V3_1, CypherComparisonSupport.Cost, DefaultRuntime) + TestScenario(V2_3, CypherComparisonSupport.Cost, DefaultRuntime) + TestScenario(DefaultVersion, CypherComparisonSupport.Cost, DefaultRuntime)

    def BackwardsCompatibility: TestConfiguration = Version2_3 + Version3_1 + Version3_2

    def Procs: TestConfiguration = TestScenario(DefaultVersion, DefaultPlanner, ProcedureOrSchema)

    /*
    If you are unsure what you need, this is a good start. It's not really all scenarios, but this is testing all
    interesting scenarios.
     */
    def All: TestConfiguration = AbsolutelyAll - Procs

    def AllExceptSlotted: TestConfiguration = All - SlottedInterpreted

    def AbsolutelyAll: TestConfiguration = Version3_3 + BackwardsCompatibility + Procs

    def Empty: TestConfiguration = TestConfig(Set.empty)

  }

}

/**
  * Will run a query across versions and configurations, making sure they all agree on the results and/or errors.
  *
  * For every query tested using `testWith`, the query will be run against all configurations. Every configuration
  * is expected to either succeed or fail. When new features are added that enable queries in new configurations,
  * acceptance tests will start failing because now a configuration is succeeding that was not successful before.
  *
  * This is expected and useful - it let's us know how a change impacts how many acceptance tests now start
  * succeeding where they weren't earlier.
  */
object CypherComparisonSupport {

  val newPlannerMonitor = NewPlannerMonitor

  val newRuntimeMonitor = new NewRuntimeMonitor

  // TODO Versions, ... should contain multiple entries
  case class Versions(version: Version)

  case class Version(name: String)

  object V2_3 extends Version("2.3")

  object V3_1 extends Version("3.1")

  object V3_2 extends Version("3.2")

  object V3_3 extends Version("3.3")

  object DefaultVersion extends Version("")

  case class Planners(planner: Planner)

  case class Planner(acceptedPlannerNames: Set[String], preparserOption: String)

  object Cost extends Planner(Set("COST", "IDP"), "planner=cost")

  object Rule extends Planner(Set("RULE"), "planner=rule")

  object DefaultPlanner extends Planner(Set(), "")

  case class Runtimes(runtime: Runtime)

  case class Runtime(acceptedRuntimeName: String, preparserOption: String)

  object CompiledSource extends Runtime("COMPILED", "runtime=compiled debug=generate_java_source")

  object CompiledBytecode extends Runtime("COMPILED", "runtime=compiled")

  object Slotted extends Runtime("ENTERPRISE-INTERPRETED", "runtime=enterprise-interpreted")

  object Interpreted extends Runtime("INTERPRETED", "runtime=interpreted")

  object ProcedureOrSchema extends Runtime("PROCEDURE", "")

  object DefaultRuntime extends Runtime("", "")

  case class TestScenario(version: Version, planner: Planner, runtime: Runtime) extends Assertions with TestConfiguration {

    override def name: String = {
      val versionName = if (version == DefaultVersion) "<default version>" else version.name
      val plannerName = if (planner == DefaultPlanner) "<default planner>" else planner.preparserOption
      val runtimeName = if (runtime == DefaultRuntime) "<default runtime>" else runtime.preparserOption
      s"${versionName} ${plannerName} ${runtimeName}"
    }

    def preparserOptions: String = s"${version.name} ${planner.preparserOption} ${runtime.preparserOption}"

    def prepare(): Unit = newRuntimeMonitor.clear()

    def checkResultForSuccess(query: String, internalExecutionResult: InternalExecutionResult): Unit = {
      internalExecutionResult.executionPlanDescription().arguments.collect {
        case IPDRuntime(reportedRuntime) if (reportedRuntime != runtime.acceptedRuntimeName && runtime != DefaultRuntime) =>
          fail(s"did not use ${runtime.acceptedRuntimeName} runtime - instead $reportedRuntime was used. Scenario $name")
        case IPDPlanner(reportedPlanner) if (!planner.acceptedPlannerNames.contains(reportedPlanner) && planner != DefaultPlanner) =>
          fail(s"did not use ${planner.acceptedPlannerNames} planner - instead $reportedPlanner was used. Scenario $name")
      }
    }

    def checkResultForFailure(query: String, internalExecutionResult: Try[InternalExecutionResult]): Unit = {

      internalExecutionResult match {
        case Failure(_) => // not unexpected
        case Success(result) =>
          val maybeRuntime = result.executionPlanDescription().arguments.collectFirst {
            case IPDRuntime(reportedRuntime) if (reportedRuntime == runtime.acceptedRuntimeName || runtime == DefaultRuntime) => reportedRuntime
          }
          val maybePlanner = result.executionPlanDescription().arguments.collectFirst {
            case IPDPlanner(reportedPlanner) if planner.acceptedPlannerNames.contains(reportedPlanner) => reportedPlanner
          }
          if (maybeRuntime.isDefined && maybePlanner.isDefined) {
            fail(s"unexpectedly succeeded using $name for query $query, with ${maybeRuntime.get} & ${maybePlanner.get}")
          }
      }
    }

    def checkStateForSuccess(query: String): Unit = newRuntimeMonitor.trace.collect {
      case UnableToCompileQuery(stackTrace) => fail(s"Failed to use the ${runtime.acceptedRuntimeName} runtime on: $query\n$stackTrace")
    }

    def checkStateForFailure(query: String): Unit = {
      // Default and Procedure scenarios do not specify a particular runtime.
      // We therefore expect a fallback, so do NOT check for a failure here
      if (runtime == ProcedureOrSchema || runtime == DefaultRuntime)
        return

      val attempts: Option[NewPlanSeen] = newRuntimeMonitor.trace.collectFirst {
        case event: NewPlanSeen => event
      }
      attempts.foreach(_ => {
        val failures = newRuntimeMonitor.trace.collectFirst {
          case failure: UnableToCompileQuery => failure
        }
        failures.orElse(fail(s"Unexpectedly used the ${runtime.acceptedRuntimeName} runtime on: $query"))
      })
    }

    override def scenarios: Set[TestScenario] = Set(this)

    override def scenariosNames: Set[String] = Set(this).map(_.name)
  }

  trait TestConfiguration {
    def scenarios: Set[TestScenario]

    def scenariosNames: Set[String]

    def name: String

    def +(other: TestConfiguration): TestConfiguration = TestConfig(scenarios ++ other.scenarios)

    def -(other: TestConfiguration): TestConfiguration = TestConfig(scenarios -- other.scenarios)
  }

  case class TestConfig(scenarios: Set[TestScenario]) extends TestConfiguration {
    override def name: String = scenarios.map(_.name).mkString(" + ")

    override def scenariosNames: Set[String] = scenarios.map(_.name)

    override def +(other: TestConfiguration): TestConfig = TestConfig(scenarios ++ other.scenarios)

    override def -(other: TestConfiguration): TestConfig = TestConfig(scenarios -- other.scenarios)
  }


//  def testConfigFor(versions: Versions, planners: Planners, runtimes: Runtimes): TestConfiguration = {
//    // TODO
//    ???
//  }



}
