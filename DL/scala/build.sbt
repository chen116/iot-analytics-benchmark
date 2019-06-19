name := "iotstreamdl"

version := "0.0.1"

scalaVersion := "2.11.8"
val sparkVersion = "2.4.3"
val BigDLVersion = "0.8.0"

// additional libraries
libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-core" % sparkVersion % "provided",
  "org.apache.spark" %% "spark-mllib" % sparkVersion % "provided", 
  "org.apache.spark" %% "spark-streaming" % sparkVersion % "provided",
  "com.intel.analytics.bigdl" % "bigdl-SPARK_2.4" % BigDLVersion,
  "org.apache.hadoop" % "hadoop-common" % "3.0.0",
  "org.apache.spark" %% "spark-streaming-kafka-0-8" % sparkVersion % "provided",
  "org.apache.spark" %% "spark-streaming-kafka-assembly" % "1.6.3",
  "org.apache.kafka" % "kafka-clients" % "0.10.0.0",
  "org.eclipse.paho" % "org.eclipse.paho.client.mqttv3" % "1.2.0",
)
assemblyMergeStrategy in assembly := {
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case x => MergeStrategy.first
}