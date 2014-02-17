name := "Attoparsec"

organization := "com.comonad"

version := "0.3"

scalaVersion := "2.10.1"

scalacOptions += "-deprecation"

scalacOptions += "-feature"

resolvers += "Sonatype snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/" 

libraryDependencies += "org.scalaz" %% "scalaz-core" % "7.0.5"

libraryDependencies += "org.scalacheck" %% "scalacheck" % "1.10.1" % "test"

seq(bintrayPublishSettings:_*)

licenses += ("BSD", url("http://opensource.org/licenses/BSD"))

