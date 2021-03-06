# Metalus Pipeline Core
This project provides the tools necessary for building applications based on reusable components that are assembled at
runtime using external configurations. Using the concepts of *steps*, *pipelines*, *executions* and *applications*, 
developers are able to deploy a single 'uber-jar' (metalus-application) to the cluster and then use the 'spark-submit' 
command as a gateway to assembling the application. Application developers can achieve this by creating reusable *steps* 
that are packaged in a jar and an application JSON configuration or a *DriverSetup* implementation that is responsible 
for parsing the external configuration information to construct an *execution plan* and *pipelines*.

## Concepts
This section will attempt to provide a high level idea of how the framework achieves the project goals.

### [Steps](../metalus-core/docs/steps.md)
The step is the smallest unit of work in the application. A step is a single reusable code function that can be executed
by a pipeline. There are two parts to a step, the actual function and the *PipelineStep* metadata. The function should 
define the parameters that are required to execute properly and the metadata is used by the pipeline to define how those
parameters are populated. The return type may be anything including *Unit*, but it is recommended that a 
*PipelineStepResponse* be returned.

### Pipelines
A pipeline is a collection of steps that should be executed in a predefined order. An application may execute one or 
more pipelines as part of an application and are useful when there may be a need to restart processing in an application
without needing to run all of the same logic again.

![Pipeline Overview](../docs/images/Pipeline_Overview.png "Pipeline Overview")

Each pipeline contains one or more step mappings that will be executed. The pipeline is the most basic construct that 
can be represented as an external configuration. Using the *DriverUtils.parsePipelineJson* function, it is easy to 
convert a JSON representation into a list of *Pipeline* case classes which can then be executed as part of the execution 
plan. There are two categories pipeline:

* pipeline: this is the default
* step-group: this indicates a pipeline designed to be embedded within a [step-group](docs/steps.md) in another pipeline

This diagram describes the flow of an executing pipeline:

![Default Pipeline Execution](../docs/images/Default_Pipeline_Execution.png "Default Pipeline Execution")

#### Pipeline Chaining
Pipeline chaining is the ability to execute more than one pipeline sequentially. When pipelines are chained together, 
the application has the ability to restart a specific pipeline skipping over other pipelines. It must be noted that 
when a restart happens, the pipeline that was restarted and any subsequent pipelines will be executed to completion.

#### Branching
When the flow of the pipeline execution needs to be determined conditionally, a step may be set with a *type* of **branch**.
The return value of the branch should match the name of a parameter in the step params metadata. The value of the matched
parameter will be used as the id of the next step to execute.

#### Conditional Step Execution
Each step has an attribute named *executeIfEmpty* which takes a value just like the parameters of the step. If the value
is empty, then the step will be executed. This is useful in pipeline chaining to aid with sharing resources such as a 
DataFrame. 

This feature is useful for optimizing applications that run multiple pipelines that could benefit from reducing the 
number of times data is read from disk. Consider an application that runs two pipelines, during the execution of the 
first pipeline a DataFrame is created that reads from a parquet table and performs some operations. The second pipeline 
also needs to read data from the parquet table. However, since the second pipeline may be restarted without the first 
pipeline being executed, it will need a step that reads the data from the parquet table. By passing the DataFrame from 
the first pipeline into the *executeIfEmpty* attribute, the step will only be executed if the the DataFrame is missing.
This allows sharing the DAG across pipelines which will also allow Spark to perform optimizations.

#### Step Groups
A step-group is a step that executes another pipeline. The embedded pipeline will be executed in an isolated manner from 
the main pipeline. A mappings parameter is used to populate the execution globals.

##### Pipeline Manager
The PipelineManager is an abstraction that provides access to pipelines that are used by step groups.

#### Flow Control
There are two ways to stop pipelines:

* **PipelineStepMessage** - A step may register a message and set the type of either *pause* or *error* that will prevent
additional pipelines from executing. The current pipeline will complete execution.
* **PipelineStepException** - A step may throw an exception based on the *PipelineStepException* which will stop the
execution of the current pipeline.

#### Exceptions
Throwing an exception that is not a *PipelineStepException* will result in the application stopping and possibly being
restarted by the resource manager such as Yarn. This should only be done when the error is no longer recoverable.

### Execution Plan
An execution plan allows control over how pipelines are executed. An [application](docs/application.md) 
may choose to only have a single execution that runs one or more pipelines or several executions that run pipelines in 
parallel or based on a dependency structure.

![Execution Overview](../docs/images/Execution_Overview.png "Execution Overview")

The execution plan allows pipelines to execute in parallel and express dependencies between pipelines or groups of 
pipelines. Each *PipelineExecution* in the plan has the ability to execute multiple *pipelines* as well as express
a dependency on other executions. Each execution runs the pipelines as described in the *Pipelines* section above.

When one execution is dependent on another, the *globals* and *parameters* objects will be taken from the final 
**PipelineContext** and injected into the globals object as a map keyed by the id from the parent *PipelineExecution*.
In the map these objects may be referenced by the names *globals* and *pipelineParameters*.

In the event that the result of an execution plan results in an exception or one of the pipelines being paused or errored,
then downstream executions will not run. 

Below is an example of an execution plan demonstrating a single root pipeline chain that has three child dependencies.
A final pipeline chain has a dependency on two of the three child pipeline chains. In this example, pipeline chain *A* will
execute first. Once it is complete, then pipeline chains *B*, *C* and *D* can be executed in parallel depending on resources
available. The final pipeline chain *E* will only execute once both *B* and *D* have completed successfully.:

![Pipeline Execution Plan Example](../docs/images/Execution_Plan_Example.png "Pipeline Execution Dependencies")

This next diagram shows how an execution plan is processed:

![Pipeline Execution Plan Flow](../docs/images/Execution_Plan_Flow.png "Pipeline Execution Flow")

### Drivers
Drivers are the entry point into the application. The driver is responsible for processing the input parameters and
initializing the application.

See the [pipeline driver](docs/pipeline-drivers.md) documentation for more information.

### [Application](docs/application.md)
The *Application* framework is a configuration based method of describing the Spark application. This includes defining 
the execution plan, pipelines, pipeline context overrides (*pipeline listener*, *security manager*, *step mapper*) and 
global values as JSON.

![Application Overview](../docs/images/Application_Overview.png "Application Overview")

## Getting Started
There is some preparation and understanding that needs to happen in order to create dynamically assembled applications.

### Utilities
There are some utility libraries provided to make writing steps easier.

#### [FileManager](docs/filemanager.md)
The FileManger utility provides an abstraction of different file systems. Step authors may use this to work directly
with files on different file systems such as HDFS.

#### DriverUtils
This utility provides several functions to help in the creation of a custom *DriverSetup*

### [Execution Audits](docs/executionaudits.md)
Basic timing audits are captured during execution that can be useful for troubleshooting or
establishing trends. The *PipelineListener* allows adding additional information to each audit.

## Pipeline Step Mapping
*Pipelines* represent the reusable construct of the application. In order to abstract the pipeline definition in a way that
allows reuse without having to duplicate the metadata, this library has the concept of pipeline step mapping. The pipeline
is constructed from *PipelineStep* objects that contain the *id* of the step to execute and a list of parameters that
will be mapped to the step function at runtime. 

This flow demonstrates the mapping flow:

![Default Pipeline Mapping FLow](../docs/images/Default_Parameter_Mapping.png "Default Parameter Mapping Flow")

Special characters are allowed in the *value* of the parameter that the executor will use to determine where to pull 
the mapped value. The value may be static or dynamic. Below is a list of characters to use when the value should be 
dynamic:

* **!** - When the value begins with this character, the system will search the PipelineContext.globals for the named parameter and pass that value to the step function.
* **$** - When the value begins with this character, the system will search the PipelineContext.parameters for the named parameter and pass that value to the step function.
* **@** - When the value begins with this character, the system will search the PipelineContext.parameters for the named parameter and pass the primaryReturn value to the step function.
* **#** - When the value begins with this character, the system will search the PipelineContext.parameters for the named parameter and pass the namedReturns value to the step function.
* **&** - When the value begins with this character, the system will search the PipelineContext.pipelineManager for the named parameter and pass the pipeline or None to the step function. This is usually used in a step-group.

The **@** and **#** symbols are shortcuts that assume the value in PipelineContext.parameters is a PipelineStepResponse.
 
In addition to searching the parameters for the current pipeline, the user has the option of specifying a pipelineId in 
the syntax for *@* and *$* to specify any previous pipeline value. *Example: @p1.StepOne*

Values may also be embedded. The user has the option to reference properties embedded in top level objects. Given an 
object (obj) that contains a sub-object (subObj) which contains a name, the user could access the name field using this
syntax:
```
$obj.subObj.name
```
	
Here is the object descried as JSON:
```json
{
	"subObj": {
		"name": "Spark"
	}
} 
```

**Embedded Values and String Concatenation**

The mapper also allows special values to be concatenated together in a parameter value. The special value must be wrapped
in curly braces "{}". As an example, given the following string 
```
"some_string-${pipelineId.subObj.name}-another_string"
 ```
would return 
```
"some_string-Spark-another_string"
```
Multiple values may be embedded as long as the resulting value is a string, boolean
or number. A return value of an object will log a warning and ignore string concatenation to return the object value.

**Embedded Values in JSON**

JSON object values may also be embedded as a pipeline step value. Two attributes must be provided in the JSON, 
*className* and *object*. The *className* must be the fully qualified name of the case class to initialize and
it must be on the classpath. *object* is the JSON object to use to initialize the case class. 

Below is the syntax:

```json
{
  "className": "com.acxiom.pipeleine.ParameterTest",
  "object": {
  	"string": "some string",
  	"num": 5
  }
}
```

List values may be embedded as a pipeline step value. Support for variable expansion is available for maps and objects 
if the *className* property has been set.

Syntax for a list of objects:

```json
{
	"className": "com.acxiom.pipeleine.ParameterTest",
	"value": [
		{
			"string": "some string",
			"num": 5
		},
		{
        	"string": "some other string",
        	"num": 10
        }
	]
}
```

Syntax for a list of maps:

```json
{
	"value": [
		{
			"string": "some string",
			"num": 5
		},
		{
        	"string": "some other string",
        	"num": 10
        }
	]
}
```

JSON objects, maps and list of maps/objects can use the special characters defined above. This allows referencing dynamic 
values in predefined objects. Using the application framework, note that globals cannot use *@* or *#* since steps will 
not have values prior to initialization.

**Validation**

Step parameter type checking can be enabled by providing the passing the option "validateStepParameterTypes true" as a global parameter.
This validation is disabled by default.

### PipelineStep
The PipelineStep describes the step functions that need to be called including how data is passed between steps. When 
creating a PipelineStep, these values need to be populated:

* **id** - This is the step id. This should be unique for the step within the pipeline. This id is how other steps can reference the result of executing the step or how to indicate which step to execute next in the pipeline.
* **displayName** - This is used for logging purposes.
* **description** - This is used to explain what the step is for. When step metadata is stored as another format such as JSON or XML, the description is useful for display in a UI.
* **type** - This describes the type of step and is useful for categorization. Most steps should default to *Pipeline*. There is a *branch* type that is used for branching the pipeline conditionally.
* **params** - This is a list of **Parameter** objects that describe how to populate the step function parameters. 
* **engineMeta** - This contains the name of the object and function to execute for this step. The format is *StepObject.StepFunction*. Note that parameters are not supplied as the *params* attribute handles that.
* **nextStepId** - Tells the system which step to execute next. *branch* steps handle this differently and passing in *None* tells the system to stop executing this pipeline.
* **executeIfEmpty** - This field is used to determine if the current step should execute. Passing in a value skips execution and returns the value as the step response. Fields can use any values that are supported by *params*.
