import NativePackagerHelper._

name := """dataport-akka"""

version := "0.9.10"

scalaVersion := "2.11.7"
ivyScala := ivyScala.value map { _.copy(overrideScalaVersion = true) }

lazy val akkaVersion = "2.4.10"

resolvers += "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test",
  "com.typesafe.akka" %% "akka-cluster-tools" % akkaVersion,
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "ch.qos.logback" % "logback-classic" % "1.1.3",
  "junit" % "junit" % "4.12" % "test",
  "com.novocode" % "junit-interface" % "0.11" % "test",
  "org.eclipse.paho" % "org.eclipse.paho.client.mqttv3" % "1.1.0",
  "joda-time" % "joda-time" % "2.9.4",
  "com.google.code.gson" % "gson" % "2.7",
  "com.fatboyindustrial.gson-jodatime-serialisers" % "gson-jodatime-serialisers" % "1.3.0",
  "net.gpedro.integrations.slack" % "slack-webhook" % "1.1.1",
  "com.mashape.unirest" % "unirest-java" % "1.4.9",
  "org.influxdb" % "influxdb-java" % "2.3",
  "org.jdom" % "jdom2" % "2.0.6"
)

enablePlugins(JavaServerAppPackaging)
mainClass in Compile := Some("no.ntnu.dataport.DataportMain")

licenses := Seq(("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.html")))
