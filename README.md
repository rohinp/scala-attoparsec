scala-attoparsec is a port of Bryan O'Sullivan's Attoparsec library from Haskell to Scala.

scala-attoparsec is released under a BSD open source licence

Build Instructions
------------------

The project is built with `sbt`. To build the project:

1. `sbt update` (this step is required after a fresh checkout, after changing the version of SBT, Scala, or other dependencies)
2. `./sbt [compile | package | test-compile | test | publish-local | publish]`

For continuous compilation:

```
$ ./sbt
> ~ compile
```

## Using Attoparsec in your project ##

With SBT, add a resolver to your build.sbt:

```
resolvers += "Runar's Bintray" at "https://bintray.com/runarorama/maven"
```

Then add a library dependency:

```
libraryDependencies += "com.comonad" %% "scala-attoparsec" % "0.2"
```

