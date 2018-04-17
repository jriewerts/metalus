package com.acxiom.pipeline.utils

import java.lang.reflect.InvocationTargetException

import com.acxiom.pipeline.{PipelineContext, PipelineStep, PipelineStepResponse}
import org.apache.log4j.Logger

import scala.annotation.tailrec
import scala.reflect.runtime.{universe => ru}
import scala.util.{Failure, Success, Try}

object ReflectionUtils {
  private val logger = Logger.getLogger(getClass)

  /**
    * This function will attempt to find and instantiate the named class with the given parameters.
    *
    * @param className The fully qualified class name
    * @param parameters The parameters to pass to the constructor or None
    * @return An instantiated class.
    */
  def loadClass(className: String, parameters: Option[Map[String, Any]] = None): Any = {
    val mirror = ru.runtimeMirror(getClass.getClassLoader)
    val moduleClass = mirror.staticClass(className)
    val module = mirror.staticModule(className)
    val classMirror = mirror.reflectClass(moduleClass)
    // Get constructor
    val symbol = classMirror.symbol.info.decls.find(s => s.isConstructor)
    if (symbol.isDefined) {
      val method = getMethodBySymbol(symbol.get, parameters.getOrElse(Map[String, Any]()))
      classMirror.reflectConstructor(method)
      classMirror.reflectConstructor(method)(mapMethodParameters(method.paramLists.head, parameters.getOrElse(Map[String, Any]()),
        mirror.reflect(mirror.reflectModule(module)), symbol.get.asTerm.fullName, method.typeSignature, None)
        : _*)
    } else {
      throw new RuntimeException("Unable to find matching constructor")
    }
  }

  /**
    * This function will execute the PipelineStep function using the provided parameter values.
    *
    * @param step            The step to execute
    * @param parameterValues A map of named parameter values to map to the step function parameters.
    * @return The result of the step function execution.
    */
  def processStep(step: PipelineStep,
                  parameterValues: Map[String, Any],
                  pipelineContext: PipelineContext): Any = {
    logger.debug(s"processing step,stepObject=$step")
    // Get the step directive which should follow the pattern "Object.function"
    val executionObject = step.engineMeta.get.spark.get
    // Get the object and function
    val directives = executionObject.split('.')
    val objName = directives(0)
    val funcName = directives(1)
    logger.info(s"Preparing to run step $objName.$funcName")
    // Get the reflection information for the object and method
    val mirror = ru.runtimeMirror(getClass.getClassLoader)
    val stepPackage = pipelineContext.stepPackages.get.find(pkg => {
      Try(mirror.staticModule(s"$pkg.$objName")) match {
        case Success(_) => true
        case Failure(_) => false
      }
    })
    val module = mirror.staticModule(s"${stepPackage.getOrElse("")}.$objName")
    val im = mirror.reflectModule(module)
    val method = getMethod(funcName, im, parameterValues)
    // Get a handle to the actual object
    val stepObject = mirror.reflect(im.instance)
    // Get the list of methods generated by the compiler
    val ts = stepObject.symbol.typeSignature
    // Get the parameters this method requires
    val parameters = method.paramLists.head
    val params = mapMethodParameters(parameters, parameterValues, stepObject, funcName, ts, Some(pipelineContext))
    logger.info(s"Executing step $objName.$funcName")
    logger.debug(s"Parameters: $params")
    // Invoke the method
    try {
      stepObject.reflectMethod(method)(params: _*) match {
        case response: PipelineStepResponse => response
        case response => PipelineStepResponse(response match {
          case value: Option[_] =>
            value
          case _ =>
            Some(response)
        }, None)
      }
    } catch {
      case it: InvocationTargetException => throw it.getTargetException
      case t: Throwable => throw t
    }
  }

  /**
    * This function will attempt to extract the value assigned to the field name from the given entity. The field can
    * contain "." character to denote sub objects. If the field does not exist or is empty a None object will be
    * returned. This function does not handle collections.
    *
    * @param entity            The entity containing the value.
    * @param fieldName         The name of the field to extract
    * @param extractFromOption Setting this to true will see if the value is an option and extract the sub value.
    * @return The value from the field or an empty string
    */
  def extractField(entity: Any, fieldName: String, extractFromOption: Boolean = true): Any = {
    val value = getField(entity, fieldName)
    if (extractFromOption) {
      value match {
        case option: Option[_] if option.isDefined => option.get
        case _ => value
      }
    } else {
      value
    }
  }

  @tailrec
  private def getField(entity: Any, fieldName: String): Any = {
    val obj = entity match {
      case option: Option[_] if option.isDefined =>
        option.get
      case _ => if (!entity.isInstanceOf[Option[_]]) {
        entity
      } else {
        ""
      }
    }
    val embedded = fieldName.contains(".")
    val name = if (embedded) {
      fieldName.substring(0, fieldName.indexOf("."))
    } else {
      fieldName
    }

    val value = entity match {
      case map: Map[_, _] => map.asInstanceOf[Map[String, Any]](name)
      case _ => getFieldValue(obj, name)
    }

    if (value != None && embedded) {
      getField(value, fieldName.substring(fieldName.indexOf(".") + 1))
    } else {
      value
    }
  }

  private def getFieldValue(obj: Any, fieldName: String): Any = {
    val mirror = ru.runtimeMirror(getClass.getClassLoader)
    val im = mirror.reflect(obj)
    val term = im.symbol.typeSignature.member(ru.TermName(fieldName))
    if (term.isTerm) {
      val field = term.asTerm
      val fieldVal = im.reflectField(field)
      fieldVal.get
    } else {
      None
    }
  }

  private def mapMethodParameters(parameters: List[ru.Symbol],
                                  parameterValues: Map[String, Any],
                                  stepObject: ru.InstanceMirror,
                                  funcName: String,
                                  ts: ru.Type,
                                  pipelineContext: Option[PipelineContext]) = {
    parameters.zipWithIndex.map { case (param, pos) =>
      val name = param.name.toString
      logger.debug(s"Mapping parameter name to method: $name")
      val optionType = param.asTerm.typeSignature.toString.contains("Option[")
      val value = if (parameterValues.contains(name)) {
        logger.debug("Mapping parameter from parameterValues")
        parameterValues(name)
      } else if (param.asTerm.isParamWithDefault) {
        logger.debug("Mapping parameter from function default parameter value")
        // Locate the generated method that will provide the default value for this parameter
        // Name follows Scala spec --> {functionName}$$default$${parameterPosition} + 1
        val defaultGetterMethod = ts.member(ru.TermName(s"$funcName$$default$$${pos + 1}")).asMethod
        // Execute the method to get the default value for this parameter
        stepObject.reflectMethod(defaultGetterMethod)()
      } else {
        logger.debug("Using built in pipeline variable")
        name match {
          case "pipelineContext" => if (pipelineContext.isDefined) pipelineContext.get
        }
      }

      if (pipelineContext.isDefined) {
        pipelineContext.get.security.secureParameter(getFinalValue(optionType, value))
      } else {
        getFinalValue(optionType, value)
      }
    }
  }

  private def getFinalValue(optionType: Boolean, value: Any): Any = {
    if (optionType && !value.isInstanceOf[Option[_]]) {
      Some(value)
    } else if (!optionType && value.isInstanceOf[Option[_]] && value.asInstanceOf[Option[_]].isDefined) {
      value.asInstanceOf[Option[_]].get
    } else {
      value
    }
  }

  private def getMethod(funcName: String, im: ru.ModuleMirror, parameterValues: Map[String, Any]): ru.MethodSymbol = {
    val symbol = im.symbol.info.decl(ru.TermName(funcName))
    getMethodBySymbol(symbol, parameterValues)
  }

  private def getMethodBySymbol(symbol: ru.Symbol, parameterValues: Map[String, Any]): ru.MethodSymbol = {
    val alternatives = symbol.asTerm.alternatives

    // See if more than one method has the same name and use the parameters to determine which to use
    if (alternatives.lengthCompare(1) > 0) {
      // Iterate through the functions matching the number of parameters that have the same typed as the provided parameters
      val method = alternatives.reduce((alt1, alt2) => {
        val params1 = getMatches(alt1.asMethod.paramLists.head, parameterValues)
        val params2 = getMatches(alt2.asMethod.paramLists.head, parameterValues)
        if (params1 > params2) {
          alt1
        } else {
          alt2
        }
      })

      method.asMethod
    } else {

      // There was only one method matching the name so return it.
      symbol.asMethod
    }
  }

  private def getMatches(symbols: List[ru.Symbol], parameterValues: Map[String, Any]): Int = {
    // Filter out the parameters returning only parameters that are compatible
    val matches = symbols.filter(param => {
      val name = param.name.toString
      if (parameterValues.contains(name)) {
        val instanceType = parameterValues(name).getClass
        val paramType = Class.forName(param.typeSignature.typeSymbol.fullName)
        parameterValues.contains(name) && paramType.isAssignableFrom(instanceType)
      } else {
        false
      }
    })

    matches.length
  }
}
