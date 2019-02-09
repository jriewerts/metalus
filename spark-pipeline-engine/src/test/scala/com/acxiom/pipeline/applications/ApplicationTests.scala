package com.acxiom.pipeline.applications

import java.io.{File, FileOutputStream, OutputStreamWriter}
import java.nio.file.Files

import com.acxiom.pipeline.drivers.DriverSetup
import com.acxiom.pipeline.utils.DriverUtils
import com.acxiom.pipeline.{PipelineExecution, PipelineListener, PipelineSecurityManager, PipelineStepMapper}
import org.apache.commons.io.FileUtils
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.hdfs.{HdfsConfiguration, MiniDFSCluster}
import org.apache.hadoop.io.LongWritable
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.log4j.{Level, Logger}
import org.json4s.{DefaultFormats, Formats}
import org.json4s.native.Serialization
import org.scalatest.{BeforeAndAfterAll, FunSpec, Suite}

import scala.io.Source

class ApplicationTests extends FunSpec with BeforeAndAfterAll with Suite {
  override def beforeAll() {
    Logger.getLogger("org.apache.spark").setLevel(Level.WARN)
    Logger.getLogger("org.apache.hadoop").setLevel(Level.WARN)
    Logger.getLogger("com.acxiom.pipeline").setLevel(Level.DEBUG)
  }

  describe("ApplicationUtils - Parsing") {
    // Load the application for all the tests
    val application = ApplicationUtils.parseApplication(Source.fromInputStream(getClass.getResourceAsStream("/application-test.json")).mkString)
    it("Should create an execution plan") {
      val sparkConf = DriverUtils.createSparkConf(Array(classOf[LongWritable], classOf[UrlEncodedFormEntity]))
        .setMaster("local")
      val executionPlan = ApplicationUtils.createExecutionPlan(application, Some(Map[String, Any]("root" -> true)), sparkConf)
      verifyApplication(executionPlan)
      executionPlan.head.pipelineContext.sparkSession.get.stop()
    }
  }

  describe("ApplicationDriverSetup") {
    val applicationJson = Source.fromInputStream(getClass.getResourceAsStream("/application-test.json")).mkString
    it("Should load and create an Application from the parameters") {
      val setup = ApplicationDriverSetup(Map[String, Any]("applicationJson" -> applicationJson, "root" -> true))
      verifyDriverSetup(setup)
      verifyApplication(setup.executionPlan.get)
      assert(!setup.pipelineContext.globals.get.contains("applicationJson"))
      assert(!setup.pipelineContext.globals.get.contains("applicationConfigPath"))
      assert(!setup.pipelineContext.globals.get.contains("applicationConfigurationLoader"))
      setup.pipelineContext.sparkSession.get.stop()
    }

    it("Should load and create an Application from a local file") {
      val testDirectory = Files.createTempDirectory("localApplicationTests")
      val file = new File(testDirectory.toFile, "application-test.json")
      // Create the JSON file
      val outputStream = new OutputStreamWriter(new FileOutputStream(file))
      outputStream.write(applicationJson)
      outputStream.flush()
      outputStream.close()

      val setup = ApplicationDriverSetup(Map[String, Any](
        "applicationConfigPath" -> file.getAbsolutePath,
        "root" -> true))
      verifyDriverSetup(setup)
      verifyApplication(setup.executionPlan.get)
      assert(!setup.pipelineContext.globals.get.contains("applicationJson"))
      assert(!setup.pipelineContext.globals.get.contains("applicationConfigPath"))
      assert(!setup.pipelineContext.globals.get.contains("applicationConfigurationLoader"))

      file.delete()
      FileUtils.deleteDirectory(testDirectory.toFile)
    }

    it("Should load and create an Application from an HDFS file") {
      val testDirectory = Files.createTempDirectory("hdfsApplicationTests")
      val config = new HdfsConfiguration()
      config.set(MiniDFSCluster.HDFS_MINIDFS_BASEDIR, testDirectory.toFile.getAbsolutePath)
      val fs = FileSystem.get(config)
      val miniCluster = new MiniDFSCluster.Builder(config).build()

      // Create the JSON file on HDFS
      val outputStream = new OutputStreamWriter(fs.create(new Path("application-test.json")))
      outputStream.write(applicationJson)
      outputStream.flush()
      outputStream.close()

      val setup = ApplicationDriverSetup(Map[String, Any](
        "applicationConfigPath" -> "application-test.json",
        "applicationConfigurationLoader" -> "com.acxiom.pipeline.utils.HDFSFileManager",
        "root" -> true))
      verifyDriverSetup(setup)
      verifyApplication(setup.executionPlan.get)
      assert(!setup.pipelineContext.globals.get.contains("applicationJson"))
      assert(!setup.pipelineContext.globals.get.contains("applicationConfigPath"))
      assert(!setup.pipelineContext.globals.get.contains("applicationConfigurationLoader"))

      miniCluster.shutdown(true)
      FileUtils.deleteDirectory(testDirectory.toFile)
      setup.pipelineContext.sparkSession.get.stop()
    }

    it ("Should refresh an application") {
      val setup = ApplicationDriverSetup(Map[String, Any]("applicationJson" -> applicationJson, "root" -> true))
      val executionPlan = setup.executionPlan.get
      verifyApplication(setup.refreshExecutionPlan(executionPlan))
      setup.pipelineContext.sparkSession.get.stop()
    }
  }

  private def verifyDriverSetup(driverSetup: DriverSetup): Unit = {
    assert(driverSetup.executionPlan.isDefined)
    assert(driverSetup.pipelines == List())
    assert(driverSetup.initialPipelineId == "")
    assert(driverSetup.pipelineContext == driverSetup.executionPlan.get.head.pipelineContext)
  }

  private def verifyApplication(executionPlan: List[PipelineExecution]) = {
    assert(executionPlan.length == 2)
    // First execution
    val execution1 = executionPlan.head
    verifyFirstExecution(execution1)

    // Second execution
    val execution2 = executionPlan(1)
    verifySecondExecution(execution2)
  }

  private def verifySecondExecution(execution2: PipelineExecution) = {
    val ctx2 = execution2.pipelineContext
    // Ensure the id is set properly
    assert(execution2.id == "1")
    // Second execution uses the listener defined for it
    assert(ctx2.pipelineListener.get.isInstanceOf[TestPipelineListener])
    assert(ctx2.pipelineListener.get.asInstanceOf[TestPipelineListener].name == "Sub Pipeline Listener")
    // Use a global security manager
    assert(ctx2.security.asInstanceOf[TestPipelineSecurityManager].name == "Test Security Manager")
    // Use the custom pipeline parameters
    assert(ctx2.parameters.parameters.length == 1)
    assert(ctx2.parameters.parameters.head.parameters.getOrElse("howard", "") == "johnson")
    // Use the custom step mapper
    assert(ctx2.parameterMapper.asInstanceOf[TestPipelineStepMapper].name == "Sub Step Mapper")
    // Validate the correct pipeline is set
    assert(execution2.pipelines.head.name.getOrElse("") == "Pipeline 2")
    verifyMappedParameter(execution2)
    // Ensure the correct parent
    assert(execution2.parents.isDefined)
    assert(execution2.parents.get.head == "0")
    // Verify the globals object was properly constructed
    val globals1 = ctx2.globals.get
    assert(globals1.size == 5)
    assert(globals1.contains("root"))
    assert(globals1("root").asInstanceOf[Boolean])
    assert(globals1.contains("number"))
    assert(globals1("number").asInstanceOf[BigInt] == 1)
    assert(globals1.contains("float"))
    assert(globals1("float").asInstanceOf[Double] == 1.5)
    assert(globals1.contains("string"))
    assert(globals1("string").asInstanceOf[String] == "some string")
    assert(globals1.contains("mappedObject"))
    val subGlobalObject1 = globals1("mappedObject").asInstanceOf[TestGlobalObject]
    assert(subGlobalObject1.name.getOrElse("") == "Global Mapped Object")
    assert(subGlobalObject1.subObjects.isDefined)
    assert(subGlobalObject1.subObjects.get.length == 2)
    assert(subGlobalObject1.subObjects.get.head.name.getOrElse("") == "Sub Object 1")
    assert(subGlobalObject1.subObjects.get(1).name.getOrElse("") == "Sub Object 2")
    // Verify the PipelineParameters object was set properly
    assert(ctx2.parameters.parameters.length == 1)
    assert(ctx2.parameters.parameters.length == 1)
    assert(ctx2.parameters.hasPipelineParameters("Pipeline2"))
    assert(ctx2.parameters.getParametersByPipelineId("Pipeline2").get.parameters.size == 1)
    assert(ctx2.parameters.getParametersByPipelineId("Pipeline2").get.parameters("howard").asInstanceOf[String] == "johnson")
  }

  private def verifyMappedParameter(execution2: PipelineExecution) = {
    implicit val formats: Formats = DefaultFormats
    assert(execution2.pipelines.head.steps.isDefined)
    assert(execution2.pipelines.head.steps.get.head.params.isDefined)
    assert(execution2.pipelines.head.steps.get.head.params.get.length == 2)
    assert(execution2.pipelines.head.steps.get.head.params.get(1).name.getOrElse("") == "parameterObject")
    assert(execution2.pipelines.head.steps.get.head.params.get(1).className.getOrElse("") == "com.acxiom.pipeline.applications.TestGlobalObject")
    assert(execution2.pipelines.head.steps.get.head.params.get(1).value.isDefined)
    assert(execution2.pipelines.head.steps.get.head.params.get(1).value.get.isInstanceOf[Map[String, Any]])
    val mappedParameter = DriverUtils.parseJson(
      Serialization.write(execution2.pipelines.head.steps.get.head.params.get(1).value.get.asInstanceOf[Map[String, Any]]),
      execution2.pipelines.head.steps.get.head.params.get(1).className.getOrElse("")).asInstanceOf[TestGlobalObject]
    assert(mappedParameter.name.getOrElse("") == "Parameter Mapped Object")
    assert(mappedParameter.subObjects.isDefined)
    assert(mappedParameter.subObjects.get.length == 2)
    assert(mappedParameter.subObjects.get.head.name.getOrElse("") == "Param Object 1")
    assert(mappedParameter.subObjects.get(1).name.getOrElse("") == "Param Object 2")
  }

  private def verifyFirstExecution(execution1: PipelineExecution) = {
    val ctx1 = execution1.pipelineContext
    // Ensure the id is set properly
    assert(execution1.id == "0")
    // Use the global listener
    assert(ctx1.pipelineListener.get.isInstanceOf[TestPipelineListener])
    assert(ctx1.pipelineListener.get.asInstanceOf[TestPipelineListener].name == "Test Pipeline Listener")
    // Use a custom security manager
    assert(ctx1.security.asInstanceOf[TestPipelineSecurityManager].name == "Sub Security Manager")
    // Use the global pipeline parameters
    assert(ctx1.parameters.parameters.length == 1)
    assert(ctx1.parameters.parameters.head.parameters.getOrElse("fred", "") == "johnson")
    // Use the global step mapper
    assert(ctx1.parameterMapper.asInstanceOf[TestPipelineStepMapper].name == "Test Step Mapper")
    // Validate the correct pipeline is set
    assert(execution1.pipelines.head.name.getOrElse("") == "Pipeline 1")
    // Ensure no parents
    assert(execution1.parents.isEmpty)
    // Verify the globals object was properly constructed
    val globals = ctx1.globals.get
    assert(globals.size == 5)
    assert(globals.contains("root"))
    assert(globals("root").asInstanceOf[Boolean])
    assert(globals.contains("number"))
    assert(globals("number").asInstanceOf[BigInt] == 2)
    assert(globals.contains("float"))
    assert(globals("float").asInstanceOf[Double] == 3.5)
    assert(globals.contains("string"))
    assert(globals("string").asInstanceOf[String] == "sub string")
    assert(globals.contains("mappedObject"))
    val subGlobalObject = globals("mappedObject").asInstanceOf[TestGlobalObject]
    assert(subGlobalObject.name.getOrElse("") == "Execution Mapped Object")
    assert(subGlobalObject.subObjects.isDefined)
    assert(subGlobalObject.subObjects.get.length == 3)
    assert(subGlobalObject.subObjects.get.head.name.getOrElse("") == "Sub Object 1a")
    assert(subGlobalObject.subObjects.get(1).name.getOrElse("") == "Sub Object 2a")
    assert(subGlobalObject.subObjects.get(2).name.getOrElse("") == "Sub Object 3")
    // Verify the PipelineParameters object was set properly
    assert(ctx1.parameters.parameters.length == 1)
    assert(ctx1.parameters.parameters.length == 1)
    assert(ctx1.parameters.hasPipelineParameters("Pipeline1"))
    assert(ctx1.parameters.getParametersByPipelineId("Pipeline1").get.parameters.size == 1)
    assert(ctx1.parameters.getParametersByPipelineId("Pipeline1").get.parameters("fred").asInstanceOf[String] == "johnson")
  }
}

case class TestPipelineListener(name: String) extends PipelineListener
case class TestPipelineSecurityManager(name: String) extends PipelineSecurityManager
case class TestPipelineStepMapper(name: String) extends PipelineStepMapper
case class TestGlobalObject(name: Option[String], subObjects: Option[List[TestSubGlobalObject]])
case class TestSubGlobalObject(name: Option[String])