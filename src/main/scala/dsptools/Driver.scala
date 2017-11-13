// See LICENSE for license details.

package dsptools

import java.io.{File, PrintWriter}

import chisel3._
import chisel3.iotesters._
import dsptools.numbers.resizer.{BitReducer, ChangeWidthTransform}
import firrtl._
import numbers.DspRealFactory
import firrtl_interpreter._

import scala.util.DynamicVariable

//scalastyle:off regex

object Driver {

  private val optionsManagerVar = new DynamicVariable[Option[DspTesterOptionsManager]](None)
  def optionsManager: DspTesterOptionsManager = optionsManagerVar.value.getOrElse(new DspTesterOptionsManager)

  def execute[T <: Module](dutGenerator: () => T,
      optionsManager: TesterOptionsManager)(testerGen: T => PeekPokeTester[T]): Boolean = {

    val om = optionsManager match {
      case d: DspTesterOptionsManager => Some(d)
      case _ => None
    }

    optionsManagerVar.withValue(om) {
      optionsManager.interpreterOptions = optionsManager.interpreterOptions.copy(
          blackBoxFactories = optionsManager.interpreterOptions.blackBoxFactories :+ new DspRealFactory)
      iotesters.Driver.execute(dutGenerator, optionsManager)(testerGen)
    }

  }

  def execute[T <: Module](dutGenerator: () => T,
      args: Array[String] = Array.empty)(testerGen: T => PeekPokeTester[T]): Boolean = {

    val optionsManager = new DspTesterOptionsManager {
      interpreterOptions = interpreterOptions.copy(
          blackBoxFactories = interpreterOptions.blackBoxFactories :+ new DspRealFactory)
    }

    if (optionsManager.parse(args)) execute(dutGenerator, optionsManager)(testerGen)
    else {
      optionsManager.parser.showUsageAsError()
      false
    }
  }

  //scalastyle:off method.length
  def executeWithBitReduction[T <: Module](
    dutGenerator: () => T,
    optionsManager: TesterOptionsManager
  )(
      testerGen: T => PeekPokeTester[T]
  ): Boolean = {

    val om = optionsManager match {
      case d: DspTesterOptionsManager => Some(d)
      case _ => None
    }

    optionsManagerVar.withValue(om) {
      optionsManager.interpreterOptions = optionsManager.interpreterOptions.copy(
        blackBoxFactories = optionsManager.interpreterOptions.blackBoxFactories :+ new DspRealFactory)

      val maxPassNumber = om.getOrElse(new DspTesterOptionsManager()).dspTesterOptions.bitReduceMaxPasses
      val fudgeConstant = om.getOrElse(new DspTesterOptionsManager()).dspTesterOptions.bitReduceFudgeConstant

      val requestedName = optionsManager.interpreterOptions.monitorReportFileName
      if(requestedName.nonEmpty) {
        println(s"Warning: ignoring monitorReportFileName=$requestedName")
      }

      //TODO (chick) no improvement over earlier passes should also terminate the loop
      def bitReductionPass(firrtlSourceOption: Option[String] = None, passNumber: Int = 0): Option[String] = {

        println(s"Running bit reduction pass $passNumber")

        def makeReportFileName = s"signal-bitsizes-$passNumber.csv"

        //
        // Run the test
        //
        def runTheDUT(): Boolean = {
          optionsManager.interpreterOptions = optionsManager.interpreterOptions.copy(
            monitorBitUsage = true,
            monitorReportFileName = makeReportFileName,
            prettyPrintReport = false
          )

          iotesters.Driver.execute(dutGenerator, optionsManager, firrtlSourceOption)(testerGen)
        }

        //
        // Read in the signal instrumentation report generated by the interpreter
        //
        def runBitReducer(): BitReducer = {
          val reportFileName = optionsManager.interpreterOptions.getMonitorReportFile(optionsManager)
          val data = io.Source.fromFile(reportFileName).getLines().toList.drop(1)

          //
          // Construct a bitReducer and use it to create annotations to change appropriate signal sizes
          //
          val bitReducer = new BitReducer(
            data, om.getOrElse(new DspTesterOptionsManager()).dspTesterOptions.bitReduceBySigma, fudgeConstant
          )
          bitReducer.run()
          val report = bitReducer.getReportString
          println(report)

          val writer = new PrintWriter(new File(optionsManager.getBuildFileName(s"bitreport$passNumber.txt")))
          writer.println(report)
          writer.close()

          bitReducer
        }


        //
        // Apply the annotations to the previous firrtl to generate a more optimized firrtl
        //
        def runBitReduction(bitReducer: BitReducer, firrtlString: String): String = {
          val annotationMap = bitReducer.getAnnotationMap
//          annotationMap.annotations.foreach { anno =>
//            println(anno.serialize)
//          }

          val annoWriter = new PrintWriter(new File(optionsManager.getBuildFileName("anno.width")))
          annotationMap.annotations.foreach { anno =>
            annoWriter.println(anno.serialize)
          }
          annoWriter.close()

          val circuitState = firrtl.CircuitState(Parser.parse(firrtlString), LowForm, Some(annotationMap))

          val transform = new ChangeWidthTransform

          val newCircuitState = transform.execute(circuitState)

          val newFirrtlString = newCircuitState.circuit.serialize

          // println("Bit-reduced Firrtl\n" + newFirrtlString)


          val newFirrtlFileName = {
            optionsManager.firrtlOptions
              .getTargetFile(optionsManager)
              .replaceFirst(""".lo.fir$""", s".bit-reduced-$passNumber.fir")
          }
          val writer = new PrintWriter(newFirrtlFileName)
          writer.write(newFirrtlString)
          writer.close()

          newFirrtlString
        }

        if(! runTheDUT()) {
          println(s"Error: executeWithBitReduction failed in pass $passNumber")
          None
        }
        else {
          val bitReducer = runBitReducer
          val firrtlFilename = optionsManager.firrtlOptions.getTargetFile(optionsManager)
          val firrtlString = io.Source.fromFile(firrtlFilename).getLines().mkString("\n")

          if(bitReducer.bitsRemoved > 0 && passNumber < maxPassNumber) {

            val newFirrtlSource = runBitReduction(bitReducer, firrtlString)

            bitReductionPass(Some(newFirrtlSource), passNumber + 1)
          }
          else {
            // return what we just processed if nothing changed or maxed out on looping
            firrtlSourceOption match {
              case Some(_) => firrtlSourceOption
              case _ => Some(firrtlString)
            }
          }
        }
      }

      bitReductionPass() match {
        case Some(finalFirrtlSource) =>
          optionsManager.testerOptions = optionsManager.testerOptions.copy(backendName = "verilator")

          iotesters.Driver.execute(dutGenerator, optionsManager, Some(finalFirrtlSource))(testerGen)
        case _ =>
          println(s"Error: executeWithBitReduction failed to produce final bit-reduced Firrtl source")
          false
      }
    }
  }

  def executeWithBitReduction[T <: Module](
                                            dutGenerator: () => T,
                                            args: Array[String] = Array.empty
                                          )
                                          (
                                            testerGen: T => PeekPokeTester[T]
                                          ): Boolean = {

    val optionsManager = new DspTesterOptionsManager {
      interpreterOptions = interpreterOptions.copy(
        blackBoxFactories = interpreterOptions.blackBoxFactories :+ new DspRealFactory)
    }

    if (optionsManager.parse(args)) {
      executeWithBitReduction(dutGenerator, optionsManager)(testerGen)
    }
    else {
      optionsManager.parser.showUsageAsError()
      false
    }
  }


  def executeFirrtlRepl[T <: Module](dutGenerator: () => T,
      optionsManager: ReplOptionsManager = new ReplOptionsManager): Boolean = {

    optionsManager.chiselOptions = optionsManager.chiselOptions.copy(runFirrtlCompiler = false)
    optionsManager.firrtlOptions = optionsManager.firrtlOptions.copy(compilerName = "low")
    optionsManager.interpreterOptions = optionsManager.interpreterOptions.copy(
        blackBoxFactories = optionsManager.interpreterOptions.blackBoxFactories :+ new DspRealFactory)

    logger.Logger.makeScope(optionsManager) {
      val chiselResult: ChiselExecutionResult = chisel3.Driver.execute(optionsManager, dutGenerator)
      chiselResult match {
        case ChiselExecutionSuccess(_, emitted, _) =>
          optionsManager.replConfig = ReplConfig(firrtlSource = emitted)
          FirrtlRepl.execute(optionsManager)
          true
        case ChiselExecutionFailure(message) =>
          println("Failed to compile circuit")
          false
      }
    }
  }

}

class ReplOptionsManager
  extends InterpreterOptionsManager
    with HasInterpreterOptions
    with HasChiselExecutionOptions
    with HasFirrtlOptions
    with HasReplConfig
