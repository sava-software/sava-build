import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.sava.build.hardening.PitestEvidence
import java.io.File

/** Mutation classpaths, evidence identity, recompilation, and retained-report rules. */
class HardeningToolMutationEvidenceFunctionalTest : HardeningToolExecFixture() {

  private companion object {
    const val KEPT_REPORT_NOTICE =
      "only recompiled Java sources changed and every recompiled class is byte-identical " +
        "to the recorded run: report retained (not an advisory finding)"

    /** The retry line under a refusal that decides nothing about whether a run is owed. */
    const val RULE_RETRY =
      "Retry: a fresh run of :pitestEncoding in a new Gradle invocation replaces this report; " +
        "whether this range owes one is the reachability rule's question"
  }

  /**
   * Reproduces the cold-cache shape used by modular multi-project consumers. The
   * extra-module-info plugin transforms the producer's project JAR on the consumer's
   * runtime classpath. It is runtime-only so compileForPitest does not schedule its
   * producer as a compile dependency. During configuration-cache storage that JAR is
   * deliberately absent: the PIT/evidence graph itself must schedule ':library:jar'.
   */
  private fun writeModularProjectDependencyFixture() {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "hardening-modular-project-dependency-smoke-test"
        include("library", "consumer")
      """.trimIndent() + "\n"
    )
    File(fixtureDir, "library/build.gradle.kts").also { file ->
      file.parentFile.mkdirs()
      file.writeText(
        """
          plugins {
            `java-library`
          }
        """.trimIndent() + "\n"
      )
    }
    File(fixtureDir, "library/src/main/java/module-info.java").also { file ->
      file.parentFile.mkdirs()
      file.writeText("module com.example.library { exports com.example.library; }\n")
    }
    File(fixtureDir, "library/src/main/java/com/example/library/Library.java").also { file ->
      file.parentFile.mkdirs()
      file.writeText(
        """
          package com.example.library;

          public final class Library {
            public static int value() { return 7; }
          }
        """.trimIndent() + "\n"
      )
    }
    File(fixtureDir, "consumer/build.gradle.kts").also { file ->
      file.parentFile.mkdirs()
      file.writeText(
        """
          plugins {
            java
            id("software.sava.build.feature.hardening")
          }

          // The dependency is already on the sava-build plugin's implementation
          // classpath; applying it this way avoids a second plugin-marker resolution.
          pluginManager.apply("org.gradlex.extra-java-module-info")

          repositories {
            mavenCentral()
          }

          dependencies {
            runtimeOnly(project(":library"))
          }

          hardening {
            mutation.register("encoding") {
              targetClasses = listOf("com.example.*")
              targetTests = "com.example.*Test*"
            }
          }

          tasks.named<JavaExec>("pitestEncoding") {
            mainClass = "com.example.consumer.FakePit"
            classpath = sourceSets["main"].output
          }
        """.trimIndent() + "\n"
      )
    }
    File(fixtureDir, "consumer/src/main/java/module-info.java").also { file ->
      file.parentFile.mkdirs()
      file.writeText(
        """
          module com.example.consumer {
            exports com.example.consumer;
          }
        """.trimIndent() + "\n"
      )
    }
    File(fixtureDir, "consumer/src/main/java/com/example/consumer/Consumer.java").also { file ->
      file.parentFile.mkdirs()
      file.writeText(
        """
          package com.example.consumer;

          public final class Consumer {
            public static int value() { return 7; }
          }
        """.trimIndent() + "\n"
      )
    }
    File(fixtureDir, "consumer/src/main/java/com/example/consumer/FakePit.java").also { file ->
      file.parentFile.mkdirs()
      file.writeText(
        """
          package com.example.consumer;

          import java.nio.file.Files;
          import java.nio.file.Path;

          public final class FakePit {
            public static void main(String[] args) throws Exception {
              String slowest = "Slowest test ([engine:junit-jupiter]/" +
                  "[class:com.example.consumer.ConsumerTest]/" +
                  "[method:roundTrip(java.lang.String)]) took 416 ms";
              System.out.println("12:00:00 PIT >> INFO : " + slowest);
              System.out.println("> " + slowest);
              String reportDir = null;
              for (String arg : args) {
                if (arg.startsWith("--reportDir=")) {
                  reportDir = arg.substring("--reportDir=".length());
                }
              }
              Path dir = Path.of(reportDir);
              Files.createDirectories(dir);
              Files.writeString(dir.resolve("mutations.csv"),
                  "Consumer.java,com.example.consumer.Consumer," +
                      "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
                      "value,7,KILLED,com.example.consumer.ConsumerTest." +
                      "[engine:junit-jupiter]/[class:com.example.consumer.ConsumerTest]/" +
                      "[method:roundTrip(java.lang.String)]\n");
            }
          }
        """.trimIndent() + "\n"
      )
    }
  }

  /** A report from a plain fake-PIT run over the real Codec, ready for direct verifies. */
  private fun writeKeptReportFixture(buildTail: String = ""): File {
    writeFixture(moneyMath = true, buildTail = buildTail)
    runner("pitestEncoding").build()
    return File(fixtureDir, "build/reports/pitest/encoding")
  }

  @Test
  fun `cold and standalone evidence graphs schedule transformed runtime project jars`() {
    writeModularProjectDependencyFixture()
    val libraryJar = File(fixtureDir, "library/build/libs/library.jar")
    assertFalse(libraryJar.exists(), "fixture producer JAR unexpectedly existed before the cold build")

    val result = runner(":consumer:pitestEncoding", "--warning-mode=fail").build()

    assertTrue(libraryJar.isFile, "producer task did not create the transformed project dependency JAR")
    assertTrue(
      result.output.contains("> Task :library:jar"),
      "PIT did not schedule the runtime-only project JAR producer:\n${result.output}",
    )
    assertTrue(result.output.contains("Configuration cache entry stored."), result.output)
    assertTrue(
      File(fixtureDir, "consumer/build/reports/pitest/encoding/.evidence.tsv").isFile,
      "PIT execution did not commit evidence:\n${result.output}",
    )
    assertTrue(
      result.output.contains(
        ":consumer pitest 'encoding': slowest PIT coverage-phase test " +
            "'[engine:junit-jupiter]/[class:com.example.consumer.ConsumerTest]/" +
            "[method:roundTrip(java.lang.String)]' took 416 ms",
      ),
      "the slow-test advisory was not project-qualified or lost the test identity:\n${result.output}",
    )
    assertTrue(
      result.output.contains("advisory threshold 250 ms") &&
          result.output.contains("current report names this test as a target mutant's killing test") &&
          result.output.contains("does not diagnose a timeout cause") &&
          result.output.contains("prescribe a remedy"),
      "the advisory did not explain the measurement's boundary:\n${result.output}",
    )
    assertTrue(
      result.output.contains("hardening: 1 advisory finding across 1 scope") &&
          result.output.contains(
            ":consumer pitest 'encoding': slowest PIT coverage-phase test took 416 ms",
          ),
      "duplicate PIT summary lines produced missing or duplicate advisory findings:\n${result.output}",
    )

    val reused = runner(":consumer:pitestEncoding", "--warning-mode=fail").build()
    assertTrue(reused.output.contains("Configuration cache entry reused."), reused.output)
    assertTrue(
      reused.output.contains(
        ":consumer pitest 'encoding': slowest PIT coverage-phase test " +
            "'[engine:junit-jupiter]/[class:com.example.consumer.ConsumerTest]/" +
            "[method:roundTrip(java.lang.String)]' took 416 ms",
      ),
      "the advisory disappeared after a configuration-cache round trip:\n${reused.output}",
    )

    assertTrue(libraryJar.delete(), "could not remove the runtime-only JAR before standalone verify")
    val verify = runnerWithoutConfigurationCache(
      ":consumer:pitestEncodingVerify",
      "--warning-mode=fail",
    ).build()
    assertTrue(libraryJar.isFile, "standalone verify did not rebuild its runtime-only evidence input")
    assertTrue(
      verify.output.contains("> Task :library:jar"),
      "standalone verify did not schedule the runtime-only JAR producer:\n${verify.output}",
    )

    assertTrue(libraryJar.delete(), "could not remove the runtime-only JAR before mode snapshot")
    val snapshot = runnerWithoutConfigurationCache(
      ":consumer:pitestModeSnapshot",
      "-PpitestMode=standalone",
      "--warning-mode=fail",
    ).build()
    assertTrue(libraryJar.isFile, "standalone mode snapshot did not rebuild its evidence input")
    assertTrue(
      snapshot.output.contains("> Task :library:jar"),
      "standalone mode snapshot did not schedule the runtime-only JAR producer:\n${snapshot.output}",
    )
    assertTrue(snapshot.output.contains("stashed as 'standalone'"), snapshot.output)
  }

  @Test
  fun `certification declares transformed classpaths before its task action`() {
    writeModularProjectDependencyFixture()
    val libraryJar = File(fixtureDir, "library/build/libs/library.jar")
    assertFalse(libraryJar.exists(), "fixture producer JAR unexpectedly existed before certification")

    // Gradle 9.7 still emits this as a deprecation; --warning-mode=fail gives the
    // fixture Gradle 10's effective fail-closed boundary before a 10.x RC exists.
    val certified = runnerWithoutConfigurationCache(
      ":consumer:hardeningCertify",
      "--warning-mode=fail",
    ).build()
    assertTrue(libraryJar.isFile, "certification did not build its transformed runtime input")
    assertTrue(certified.output.contains("> Task :library:jar"), certified.output)
    assertTrue(
      certified.output.contains("> Task :consumer:hardeningCertify"),
      certified.output,
    )
    assertFalse(
      certified.output.contains(
        "Querying the output of an artifact transform from a task action without declaring it " +
          "as a task input has been deprecated.",
      ),
      "hardeningCertify resolved an undeclared transform output from its task action:\n" +
        certified.output,
    )
    assertTrue(
      File(fixtureDir, "consumer/.pitest-history/pitest-certification.tsv").isFile,
      "certification did not publish a receipt:\n${certified.output}",
    )
  }

  @Test
  fun `certification receipt binds runtime dependency bytes replaced at the same path`() {
    writeFixture(buildTail = """
      dependencies {
        testRuntimeOnly(files("runtime-dependency.jar"))
      }
    """.trimIndent())
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    val dependency = File(fixtureDir, "runtime-dependency.jar")
    fun replaceDependency(contents: String) {
      java.util.jar.JarOutputStream(dependency.outputStream()).use { jar ->
        jar.putNextEntry(java.util.jar.JarEntry("dependency-version.txt").apply { time = 0L })
        jar.write(contents.toByteArray())
        jar.closeEntry()
      }
    }
    val reportDirectory = File(fixtureDir, "build/reports/pitest/encoding")
    val receipt = File(fixtureDir, ".pitest-history/pitest-certification.tsv")
    fun receiptFields(): Map<String, String> {
      val lines = receipt.readLines()
      val columns = lines.single { it.startsWith("suiteColumns\t") }.split('\t').drop(1)
      val values = lines.single { it.startsWith("suite\tencoding\t") }.split('\t').drop(1)
      assertEquals(certificationSuiteColumns, columns)
      assertEquals(columns.size, values.size)
      return columns.zip(values).toMap()
    }

    replaceDependency("version one")
    runner("hardeningCertify").build()
    val firstEvidence = PitestEvidence.parse(reportDirectory.resolve(".evidence.tsv").readText())
    val firstReceipt = receiptFields()
    assertEquals(firstEvidence.classpathSha256, firstReceipt.getValue("classpathSha256"))

    // Neither a new dependency coordinate/path nor a source/configuration change can
    // carry this distinction into the receipt. Only the runtime artifact bytes move.
    replaceDependency("version two")
    val stale = runner("pitestEncodingVerify").buildAndFail().output
    assertTrue(stale.contains("classpathSha256: recorded="), stale)

    runner("hardeningCertify").build()
    val secondEvidence = PitestEvidence.parse(reportDirectory.resolve(".evidence.tsv").readText())
    val secondReceipt = receiptFields()
    assertEquals(secondEvidence.classpathSha256, secondReceipt.getValue("classpathSha256"))
    assertNotEquals(firstReceipt.getValue("classpathSha256"), secondReceipt.getValue("classpathSha256"))
    listOf(
      "sourceSha256", "classesSha256", "configurationSha256", "toolClasspathSha256",
      "mutationToolchainSha256", "reportSha256",
    ).forEach { field ->
      assertEquals(firstReceipt.getValue(field), secondReceipt.getValue(field), field)
    }
  }

  @Test
  fun `certification writes bound evidence and stale source cannot reuse the report`() {
    writeFixture(moneyMath = true)
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    File(fixtureDir, "config/pitest").mkdirs()
    File(fixtureDir, "config/pitest/encoding-accepted.csv").writeText(
      "com.example.Codec,encode,MathMutator,SURVIVED # legacy accepted row\n")

    val certified = runner("clean", "hardeningCertify").build()
    val reportDir = File(fixtureDir, "build/reports/pitest/encoding")
    val evidence = reportDir.resolve(".evidence.tsv")
    val receipt = File(fixtureDir, ".pitest-history/pitest-certification.tsv")
    assertTrue(evidence.isFile, "completed PIT evidence missing:\n${certified.output}")
    assertTrue(receipt.isFile, "certification receipt missing:\n${certified.output}")
    val receiptText = receipt.readText()
    assertTrue(receiptText.contains("schema\t8"), receiptText)
    assertTrue(receiptText.contains("session\t"), receiptText)
    assertTrue(receiptText.contains("gitState\tunavailable"), receiptText)
    assertTrue(receiptText.contains("gitCommit\tunavailable"), receiptText)
    assertTrue(receiptText.contains("gitTree\tunavailable"), receiptText)
    assertTrue(receiptText.contains("gitStatusSha256\tunavailable"), receiptText)
    assertTrue(receiptText.contains("gitProjectDirectory\tunavailable"), receiptText)
    assertTrue(Regex("(?m)^pluginSha256\\t[0-9a-f]{64}$").containsMatchIn(receiptText), receiptText)
    assertTrue(receiptText.contains("toolClasspathSha256"), receiptText)
    assertTrue(receiptText.contains("mutationToolchainSha256"), receiptText)
    assertTrue(receiptText.contains("recordInputsSha256"), receiptText)
    assertTrue(receiptText.contains("recordPitestVersion"), receiptText)
    assertTrue(receiptText.contains("recordMutationToolchainSha256"), receiptText)
    assertTrue(receiptText.contains("mode\tfresh-full-strict"), receiptText)
    assertTrue(receiptText.contains("suite\tencoding"), receiptText)
    assertTrue(
      receiptText.contains("\tlegacy-unversioned\tlegacy-toolchain-unbound\n"),
      receiptText,
    )
    val columns = receiptText.lineSequence()
      .single { it.startsWith("suiteColumns\t") }
      .split('\t')
      .drop(1)
    val suiteValues = receiptText.lineSequence()
      .single { it.startsWith("suite\tencoding\t") }
      .split('\t')
      .drop(1)
    assertEquals(
      columns.size,
      suiteValues.size,
      "certification receipt header and suite row have different field counts:\n$receiptText",
    )
    assertEquals(certificationSuiteColumns, columns, "certification receipt suite schema drifted")
    val suiteFields = columns.zip(suiteValues).toMap()
    val recordedEvidence = PitestEvidence.parse(evidence.readText())
    val configDir = File(fixtureDir, "config/pitest")
    val recordInputsSha256 = PitestEvidence.fingerprint(
      configDir,
      listOf(
        File(configDir, "encoding-accepted.csv"),
        File(configDir, "encoding-timeouts.csv"),
        File(configDir, "encoding-pitest-version"),
        File(configDir, "encoding-pitest-toolchain.tsv"),
        File(configDir, "README.md"),
      ).filter { it.isFile },
    )
    assertEquals(
      mapOf(
        "name" to "encoding",
        "invocation" to recordedEvidence.invocationId,
        "reportSha256" to recordedEvidence.reportSha256,
        "sourceSha256" to recordedEvidence.sourceSha256,
        "classesSha256" to recordedEvidence.classesSha256,
        "classpathSha256" to recordedEvidence.classpathSha256,
        "configurationSha256" to recordedEvidence.configurationSha256,
        "pitestVersion" to recordedEvidence.pitestVersion,
        "pluginSha256" to recordedEvidence.pluginSha256,
        "toolClasspathSha256" to recordedEvidence.toolClasspathSha256,
        "mutationToolchainSha256" to recordedEvidence.mutationToolchainSha256,
        "recordInputsSha256" to recordInputsSha256,
        "recordPitestVersion" to "legacy-unversioned",
        "recordMutationToolchainSha256" to "legacy-toolchain-unbound",
      ),
      suiteFields,
      "certification receipt suite values do not match their declared columns",
    )
    val topLevelPlugin = receiptText.lineSequence()
      .single { it.startsWith("pluginSha256\t") }.substringAfter('\t')
    assertEquals(
      topLevelPlugin,
      suiteFields.getValue("pluginSha256"),
      "top-level plugin identity diverged from the suite evidence",
    )
    assertEquals(
      recordedEvidence.configurationSha256,
      suiteFields.getValue("configurationSha256"),
      "certification receipt shifted or omitted the configuration identity",
    )
    assertEquals(
      recordedEvidence.pitestVersion,
      suiteFields.getValue("pitestVersion"),
      "certification receipt shifted the PIT version under another heading",
    )
    assertEquals(
      recordInputsSha256,
      suiteFields.getValue("recordInputsSha256"),
      "certification receipt did not bind the committed records that decided the gate",
    )
    assertTrue(certified.output.contains("committed record is legacy-unversioned"), certified.output)
    assertEquals(
      PitestEvidence.fingerprint(fixtureDir, listOf(File(fixtureDir, "build/mutation-classes"))),
      recordedEvidence.classesSha256,
      "certification compared against a pre-build classes fingerprint",
    )
    assertFalse(
      recordedEvidence.classesSha256 == PitestEvidence.sha256(""),
      "compiled classes were recorded as SHA-256(empty)",
    )

    val transition = runner("clean", "hardeningCertify").build()
    assertTrue(
      transition.output.contains("Reusing configuration cache"),
      "completed evidence creation invalidated the invariant configuration-cache graph:\n${transition.output}",
    )
    val reused = runner("clean", "hardeningCertify").build()
    assertTrue(reused.output.contains("Reusing configuration cache"), reused.output)

    // A comment after the last code line moves no line table, so the recompile produces
    // byte-identical classes and a direct verify keeps the report: the incident's shape.
    val codec = File(fixtureDir, "src/main/java/com/example/Codec.java")
    val certifiedEvidence = PitestEvidence.parse(evidence.readText())
    val manifestBytes = evidence.readBytes().toList()
    // Deleted first: a replay of an exact match would rewrite it, a kept report must not.
    val statusStash = File(fixtureDir, ".pitest-history/encoding.statuses")
    assertTrue(statusStash.delete(), "certification left no status stash to delete")
    codec.appendText("\n// source changed after PIT\n")
    val kept = runner("pitestEncodingVerify").build().output
    assertTrue(kept.contains(KEPT_REPORT_NOTICE), kept)
    assertTrue(kept.contains("pitest 'encoding': 1/1 detected (100%) [kept report]"), kept)
    assertEquals(manifestBytes, evidence.readBytes().toList(), "a kept report never rewrites its manifest")
    assertFalse(statusStash.exists(), "a kept report advances no machine-local state")
    assertEquals(
      certifiedEvidence.classesSha256,
      PitestEvidence.fingerprint(fixtureDir, listOf(File(fixtureDir, "build/mutation-classes"))),
      "the comment changed a recompiled class",
    )
    // The recompile is up to date against these sources, so the stamp still vouches.
    val keptAgain = runner("pitestEncodingVerify").build().output
    assertTrue(keptAgain.contains(KEPT_REPORT_NOTICE), keptAgain)
    assertTrue(keptAgain.contains(":compileForPitest UP-TO-DATE"), keptAgain)

    // One token of executable code: the class differs, so the report is stale again.
    codec.writeText(codec.readText().replace("subtract(BigDecimal.ONE)", "add(BigDecimal.ONE)"))
    val stale = runner("pitestEncodingVerify").buildAndFail().output
    assertTrue(stale.contains("completed report evidence no longer matches the current build"), stale)
    assertTrue(stale.contains("sourceSha256"), stale)
    assertTrue(stale.contains("classesSha256: recorded="), stale)
    assertTrue(stale.contains("A moved line is enough: class files carry line tables"), stale)
    // The verify cannot tell this edit from a comment that moved a line, so its retry
    // line defers to the reachability rule instead of ordering a run.
    assertTrue(stale.contains(RULE_RETRY), stale)
    assertFalse(stale.contains("Retry: run :pitestEncoding in a new Gradle invocation"), stale)
    assertFalse(stale.contains(KEPT_REPORT_NOTICE), stale)
    assertFalse(stale.contains("receipt is project-atomic"), stale)
  }

  @Test
  fun `a comment that moves a code line changes the classes and is refused by name`() {
    writeKeptReportFixture()
    val codec = File(fixtureDir, "src/main/java/com/example/Codec.java")
    codec.writeText(
      codec.readText().replace(
        "public final class Codec {\n",
        "public final class Codec {\n  // every code line below moved down by one\n",
      )
    )

    val stale = runner("pitestEncodingVerify").buildAndFail().output

    assertTrue(stale.contains("sourceSha256: recorded="), stale)
    assertTrue(stale.contains("classesSha256: recorded="), stale)
    assertTrue(stale.contains("A moved line is enough: class files carry line tables"), stale)
    assertTrue(stale.contains(RULE_RETRY), stale)
    assertFalse(stale.contains("Retry: run :pitestEncoding in a new Gradle invocation"), stale)
    assertFalse(stale.contains(KEPT_REPORT_NOTICE), stale)
  }

  @Test
  fun `test sources follow the same rule as production sources`() {
    writeKeptReportFixture()
    val fuzzTarget = File(fixtureDir, "src/test/java/com/example/CodecFuzz.java")

    fuzzTarget.appendText("// a test-source comment after its last code line\n")
    val kept = runner("pitestEncodingVerify").build().output
    assertTrue(kept.contains(KEPT_REPORT_NOTICE), kept)

    // Test classes are inside the mutation recompile, so a changed test body is a changed
    // class even though the fake's tool classpath never sees it.
    fuzzTarget.writeText(
      fuzzTarget.readText().replace(
        "public static void fuzzerTestOneInput(byte[] data) {}",
        "public static void fuzzerTestOneInput(byte[] data) { if (data.length > 4) return; }",
      )
    )
    val stale = runner("pitestEncodingVerify").buildAndFail().output
    assertTrue(stale.contains("sourceSha256: recorded="), stale)
    assertTrue(stale.contains("classesSha256: recorded="), stale)
    assertFalse(stale.contains("toolClasspathSha256: recorded="), stale)
    assertFalse(stale.contains(KEPT_REPORT_NOTICE), stale)
  }

  @Test
  fun `the mutation recompile is never up to date against sources it did not compile`() {
    writeKeptReportFixture(
      buildTail =
        """
          if (providers.gradleProperty("tolerantCompile").isPresent) {
            tasks.named<JavaCompile>("compileForPitest") { options.isFailOnError = false }
          }
          if (providers.gradleProperty("incrementalRecompile").isPresent) {
            tasks.named<JavaCompile>("compileForPitest") { options.isIncremental = true }
          }
          if (providers.gradleProperty("skipRecompile").isPresent) {
            tasks.named<JavaCompile>("compileForPitest") { onlyIf { false } }
          }
          if (providers.gradleProperty("holdRecompileLock").isPresent) {
            // Another invocation using the tree, seen from this one: the checkout lock
            // (the one an agent's scratchpad Gradle home shares with the terminal's) is
            // taken and never released, which the build's own end tolerates.
            val holdRecompileLock = tasks.register("holdRecompileLock") {
              val lock = layout.projectDirectory.file(".pitest-history/mutation-recompile.lock").asFile
              doLast {
                lock.parentFile.mkdirs()
                // 'java' names the plugin extension in a build script, so no java.nio here.
                // An earlier run of this fixture in the same daemon still holds the lock;
                // that is the state wanted, and the JVM reports it as an overlap.
                try {
                  checkNotNull(lock.outputStream().channel.tryLock()) { "the fixture could not take the recompile lock" }
                } catch (e: Exception) {
                  if (e::class.simpleName != "OverlappingFileLockException") throw e
                }
              }
            }
            tasks.named("lockMutationClasses") { dependsOn(holdRecompileLock) }
            tasks.named("clean") { dependsOn(holdRecompileLock) }
            tasks.withType<Delete>().configureEach {
              if (name == "cleanCompileForPitest") dependsOn(holdRecompileLock)
            }
          }
        """.trimIndent(),
    )
    val stamp = File(fixtureDir, "build/mutation-recompile/sources.sha256")
    assertTrue(stamp.isFile, "the recompile published no stamp")
    val published = stamp.readText()
    assertTrue(Regex("sources\t[0-9a-f]{64}\nclasses\t[0-9a-f]{64}\n").matches(published), published)
    assertTrue(
      published.contains(PitestEvidence.fingerprint(fixtureDir, listOf(File(fixtureDir, "build/mutation-classes")))),
      "the stamp names a class tree other than the one on disk",
    )

    val upToDate = runner("pitestEncodingVerify").build().output
    assertTrue(upToDate.contains(":compileForPitest UP-TO-DATE"), upToDate)

    // Gradle fingerprints JavaCompile's sources with normalized line endings, so to it a
    // CRLF rewrite is UP-TO-DATE; the stamp reads bytes and makes the recompile run. javac
    // counts CRLF as one terminator, so every class is byte-identical and the report is kept.
    val codec = File(fixtureDir, "src/main/java/com/example/Codec.java")
    val lf = codec.readText()
    codec.writeText(lf.replace("\n", "\r\n"))
    val crlf = runner("pitestEncodingVerify").build().output
    assertFalse(crlf.contains(":compileForPitest UP-TO-DATE"), crlf)
    assertTrue(crlf.contains(KEPT_REPORT_NOTICE), crlf)
    assertNotEquals(published, stamp.readText(), "the stamp still names the LF bytes")
    codec.writeText(lf)
    runner("pitestEncodingVerify").build()
    assertEquals(published, stamp.readText(), "the same sources publish the same stamp")

    // A missing or foreign stamp reruns the task: the stamp is a declared output, so
    // Gradle owns it (its cleanup removes it before a full execution), and upToDateWhen
    // reads it either way.
    stamp.delete()
    val recompiled = runner("pitestEncodingVerify").build().output
    assertFalse(recompiled.contains(":compileForPitest UP-TO-DATE"), recompiled)
    assertEquals(published, stamp.readText(), "the same sources publish the same stamp")

    stamp.writeText("sources\t${"0".repeat(64)}\nclasses\t${"0".repeat(64)}\n")
    val recompiledAgain = runner("pitestEncodingVerify").build().output
    assertFalse(recompiledAgain.contains(":compileForPitest UP-TO-DATE"), recompiledAgain)
    assertEquals(published, stamp.readText())

    // A test source breaks only the mutation recompile (compileJava never sees it), so
    // the task runs, fails, and must leave no stamp behind.
    val fuzzTarget = File(fixtureDir, "src/test/java/com/example/CodecFuzz.java")
    val compilable = fuzzTarget.readText()
    fuzzTarget.writeText(compilable.replace("(byte[] data) {}", "(byte[] data) { return }"))
    val failed = runner("compileForPitest").buildAndFail().output
    assertTrue(failed.contains(":compileForPitest FAILED"), failed)
    assertFalse(stamp.exists(), "a failed compile left a stamp behind")
    fuzzTarget.writeText(compilable)

    listOf("tolerantCompile", "incrementalRecompile").forEach { property ->
      val refused = runner("compileForPitest", "-P$property").buildAndFail().output
      assertTrue(
        refused.contains("compileForPitest must stay a full recompile that fails on a compile error"),
        "$property:\n$refused",
      )
    }

    // A fresh PIT run refuses to mutate a class tree the recompile did not vouch for: the
    // casebook's stale-classes state, produced here by a recompile that is in the graph but
    // never runs, and then by excluding it, which leaves the compile classpath unchecked too.
    val report = File(fixtureDir, "build/reports/pitest/encoding")
    val evidenceBytes = report.resolve(".evidence.tsv").readBytes().toList()
    runner("compileForPitest").build()
    assertEquals(published, stamp.readText(), "a clean recompile of the same sources republishes the same stamp")
    // A recompile a consumer switch skips is asked nothing about its inputs, so the run
    // refuses whether or not the sources moved: the stamp says nothing about the compile
    // classpath, and only a checked or executed recompile does.
    val skippedUnchanged = runner("pitestEncoding", "-PskipRecompile").buildAndFail().output
    assertTrue(skippedUnchanged.contains("compileForPitest neither ran nor checked its inputs in this invocation"), skippedUnchanged)
    // The kept path asks the same: a comment edit is not kept over a skipped recompile.
    codec.writeText(lf + "\n// harmless comment\n")
    val keptOverSkipped = runner("pitestEncodingVerify", "-PskipRecompile").buildAndFail().output
    assertTrue(keptOverSkipped.contains("compileForPitest neither ran nor checked its inputs in this invocation"), keptOverSkipped)
    assertTrue(keptOverSkipped.contains("Retry: run :pitestEncodingVerify with compileForPitest in the graph and enabled"), keptOverSkipped)
    assertFalse(keptOverSkipped.contains(KEPT_REPORT_NOTICE), keptOverSkipped)
    // The exact path asks the same: an unchanged checkout is not verified over a skipped recompile.
    codec.writeText(lf)
    val exactOverSkipped = runner("pitestEncodingVerify", "-PskipRecompile").buildAndFail().output
    assertTrue(exactOverSkipped.contains("compileForPitest neither ran nor checked its inputs in this invocation"), exactOverSkipped)
    codec.writeText(lf + "\n// harmless comment\n")
    val keptAfterAll = runner("pitestEncodingVerify").build().output
    assertTrue(keptAfterAll.contains(KEPT_REPORT_NOTICE), keptAfterAll)
    codec.writeText(lf)
    runner("compileForPitest").build()
    assertEquals(published, stamp.readText(), "the same sources publish the same stamp")
    fuzzTarget.writeText(compilable.replace("(byte[] data) {}", "(byte[] data) { if (data.length > 4) return; }"))
    val skipped = runner("pitestEncoding", "-PskipRecompile").buildAndFail().output
    assertTrue(skipped.contains("compileForPitest neither ran nor checked its inputs in this invocation"), skipped)
    assertFalse(skipped.contains("--rerun-tasks"), skipped)
    val excluded = runner("pitestEncoding", "-x", "compileForPitest").buildAndFail().output
    assertTrue(excluded.contains("compileForPitest is not in this invocation's task graph"), excluded)
    listOf(skipped, excluded).forEach { refusal ->
      assertEquals(evidenceBytes, report.resolve(".evidence.tsv").readBytes().toList(), "the refusal touched the previous evidence:\n$refusal")
      assertFalse(report.resolve(".running").exists(), "the refusal reached the attempt lifecycle:\n$refusal")
    }
    fuzzTarget.writeText(compilable)
    // Nothing changed: the exclusion alone is refused, before the stamp is consulted.
    val excludedUnchanged = runner("pitestEncoding", "-x", "compileForPitest").buildAndFail().output
    assertTrue(excludedUnchanged.contains("compileForPitest is not in this invocation's task graph"), excludedUnchanged)

    // Running around the lock task is refused by the recompile itself, before Gradle
    // can empty its outputs: with the stamp intact and with it missing, the tree stays.
    runner("compileForPitest").build()
    assertEquals(published, stamp.readText(), "the same sources publish the same stamp")
    val treeBefore = PitestEvidence.fingerprint(fixtureDir, listOf(File(fixtureDir, "build/mutation-classes")))
    val unownedUpToDate = runner("pitestEncoding", "-x", "lockMutationClasses").buildAndFail().output
    assertTrue(unownedUpToDate.contains("compileForPitest ran without lockMutationClasses (-x lockMutationClasses?)"), unownedUpToDate)
    assertFalse(
      unownedUpToDate.lineSequence().any { it == "> Task :pitestEncoding" || it.startsWith("> Task :pitestEncoding ") },
      "PIT ran on an untaken tree:\n$unownedUpToDate",
    )
    assertEquals(published, stamp.readText(), "an unowned invocation touched the stamp")
    stamp.delete()
    val unowned = runner("pitestEncoding", "-x", "lockMutationClasses").buildAndFail().output
    assertTrue(unowned.contains("compileForPitest ran without lockMutationClasses (-x lockMutationClasses?)"), unowned)
    assertFalse(stamp.exists(), "an unowned compile published a stamp")
    assertEquals(
      treeBefore,
      PitestEvidence.fingerprint(fixtureDir, listOf(File(fixtureDir, "build/mutation-classes"))),
      "an unowned invocation emptied the class tree before it was refused",
    )

    // A clean takes the pending record with the tree, so a compile it lands on declines.
    runner("compileForPitest").build()
    assertTrue(File(fixtureDir, "build/mutation-recompile").isDirectory)
    runner("cleanCompileForPitest").build()
    assertFalse(File(fixtureDir, "build/mutation-recompile").exists(), "the clean left the recompile records behind")
    assertFalse(File(fixtureDir, "build/mutation-classes").exists(), "the clean left the class tree behind")

    // Another invocation using the tree: this one refuses before compileForPitest can
    // start, so neither Gradle's output cleanup nor the recompile's own touches the
    // tree, and no stamp appears; a clean refuses the same way and deletes nothing.
    // Last, because the fixture never releases the lock.
    runner("compileForPitest").build()
    assertEquals(published, stamp.readText(), "the same sources publish the same stamp")
    stamp.delete()
    val locked = runner("pitestEncoding", "-PholdRecompileLock").buildAndFail().output
    assertTrue(locked.contains("lockMutationClasses: another invocation is using the mutation classes of ':'"), locked)
    assertTrue(locked.contains(":lockMutationClasses FAILED"), locked)
    assertFalse(locked.contains("> Task :compileForPitest"), "the recompile started under another owner:\n$locked")
    assertEquals(
      treeBefore,
      PitestEvidence.fingerprint(fixtureDir, listOf(File(fixtureDir, "build/mutation-classes"))),
      "a refused invocation changed the class tree",
    )
    assertFalse(stamp.exists(), "a refused compile published a stamp")
    val cleanRefused = runner("cleanCompileForPitest", "-PholdRecompileLock").buildAndFail().output
    assertTrue(cleanRefused.contains("cleanCompileForPitest: another invocation is using the mutation classes of ':'"), cleanRefused)
    val wholeCleanRefused = runner("clean", "-PholdRecompileLock").buildAndFail().output
    assertTrue(wholeCleanRefused.contains("clean: another invocation is using the mutation classes of ':'"), wholeCleanRefused)
    assertEquals(
      treeBefore,
      PitestEvidence.fingerprint(fixtureDir, listOf(File(fixtureDir, "build/mutation-classes"))),
      "a refused clean deleted from the class tree",
    )
  }

  @Test
  fun `a kept report needs a complete task graph and a recompile of these very bytes`() {
    writeKeptReportFixture(
      buildTail =
        """
          if (providers.gradleProperty("editAfterRecompile").isPresent) {
            val editAfterRecompile by tasks.registering {
              mustRunAfter("compileForPitest")
              val codec = layout.projectDirectory.file("src/main/java/com/example/Codec.java").asFile
              doLast { codec.appendText("\n// landed after the recompile read the sources\n") }
            }
            tasks.named("pitestEncodingEvidenceValidate") { dependsOn(editAfterRecompile) }
          }
          if (providers.gradleProperty("tamperStampClasses").isPresent) {
            val tamperStampClasses by tasks.registering {
              mustRunAfter("compileForPitest")
              val stamp = layout.buildDirectory.file("mutation-recompile/sources.sha256").get().asFile
              doLast {
                stamp.writeText(stamp.readText().replace(Regex("classes\t[0-9a-f]{64}"), "classes\t" + "0".repeat(64)))
              }
            }
            tasks.named("pitestEncodingEvidenceValidate") { dependsOn(tamperStampClasses) }
          }
        """.trimIndent(),
    )
    val codec = File(fixtureDir, "src/main/java/com/example/Codec.java")
    val fuzzTarget = File(fixtureDir, "src/test/java/com/example/CodecFuzz.java")

    // A test-code edit hidden by excluding the recompile (a test class is inside the
    // mutation recompile but outside the fake's tool classpath): the stale tree still
    // matches the recorded classes. The exclusion rule refuses first; the stamp, whose
    // sources line predates the edit, would refuse too.
    val fuzzBody = fuzzTarget.readText()
    fuzzTarget.writeText(fuzzBody.replace("(byte[] data) {}", "(byte[] data) { if (data.length > 4) return; }"))
    val excluded = runner("pitestEncodingVerify", "-x", "compileForPitest").buildAndFail().output
    assertTrue(excluded.contains("sourceSha256: recorded="), excluded)
    assertFalse(excluded.contains("classesSha256: recorded="), excluded)
    // An excluded recompile was asked nothing: refused before any keep condition, and
    // the retry must not settle the debt the stale tree hides.
    assertTrue(excluded.contains("compileForPitest neither ran nor checked its inputs in this invocation"), excluded)
    assertTrue(excluded.contains("Retry: run :pitestEncodingVerify with compileForPitest in the graph and enabled"), excluded)
    assertFalse(excluded.contains("no PIT run is owed"), excluded)
    assertFalse(excluded.contains(KEPT_REPORT_NOTICE), excluded)
    fuzzTarget.writeText(fuzzBody)

    // An unrelated exclusion is refused the same way: nothing shows the producers ran.
    codec.appendText("\n// harmless comment\n")
    val unrelated = runner("pitestEncodingVerify", "-x", "processTestResources").buildAndFail().output
    assertTrue(unrelated.contains("tasks are excluded (-x processTestResources)"), unrelated)
    assertFalse(unrelated.contains("no PIT run is owed"), unrelated)
    val complete = runner("pitestEncodingVerify").build().output
    assertTrue(complete.contains(KEPT_REPORT_NOTICE), complete)

    // Every reader of the class tree walks it through one Gradle file tree, so a file
    // Gradle excludes by default neither reruns the recompile nor refuses the keep.
    File(fixtureDir, "build/mutation-classes/.DS_Store").writeText("finder")
    val excludedFile = runner("pitestEncodingVerify").build().output
    assertTrue(excludedFile.contains(":compileForPitest UP-TO-DATE"), excludedFile)
    assertTrue(excludedFile.contains(KEPT_REPORT_NOTICE), excludedFile)
    File(fixtureDir, "build/mutation-classes/.DS_Store").delete()

    // A class another build history left in the tree is not this compile's: the
    // recompile empties the tree, so the stamp and the report describe javac's output.
    val stray = File(fixtureDir, "build/mutation-classes/com/example/Stray.class")
    stray.writeText("left by another project cache directory")
    val cleaned = runner("pitestEncodingVerify").build().output
    assertFalse(cleaned.contains(":compileForPitest UP-TO-DATE"), cleaned)
    assertTrue(cleaned.contains(KEPT_REPORT_NOTICE), cleaned)
    assertFalse(stray.exists(), "the recompile stamped a class it did not compile")

    // The causes no re-run of the verify can clear are named first: a changed build
    // script under an exclusion is reported as the changed script, not as the exclusion.
    val buildScript = File(fixtureDir, "build.gradle.kts")
    buildScript.appendText("\n// a build-script comment\n")
    val ordered = runner("pitestEncodingVerify", "-x", "processTestResources").buildAndFail().output
    assertTrue(ordered.contains("A source the recompile does not compile changed"), ordered)
    assertTrue(ordered.contains(RULE_RETRY), ordered)
    assertFalse(ordered.contains("tasks are excluded"), ordered)
    buildScript.writeText(buildScript.readText().removeSuffix("\n// a build-script comment\n"))

    // A source edit that lands after the recompile read the sources but before the
    // validator did: the stamp describes other bytes, so nothing vouches for the tree.
    val raced = runner("pitestEncodingVerify", "-PeditAfterRecompile").buildAndFail().output
    assertTrue(raced.contains("sourceSha256: recorded="), raced)
    assertTrue(raced.contains("compileForPitest has published no stamp naming these source bytes and this class tree"), raced)
    assertTrue(raced.contains("Retry: run :pitestEncodingVerify again once nothing else is editing"), raced)
    assertFalse(raced.contains("no PIT run is owed"), raced)
    assertFalse(raced.contains(KEPT_REPORT_NOTICE), raced)

    // The stamp names the class tree as well: one that names these sources beside a tree
    // it does not name (another compile put the recorded tree back) vouches for nothing.
    codec.writeText(codec.readText().removeSuffix("\n// landed after the recompile read the sources\n"))
    val tampered = runner("pitestEncodingVerify", "-PtamperStampClasses").buildAndFail().output
    assertTrue(tampered.contains("compileForPitest has published no stamp naming these source bytes and this class tree"), tampered)
    assertFalse(tampered.contains(KEPT_REPORT_NOTICE), tampered)
    val restored = runner("pitestEncodingVerify").build().output
    assertTrue(restored.contains(KEPT_REPORT_NOTICE), restored)
  }

  @Test
  fun `only recompiled Java sources may change under a kept report`() {
    writeKeptReportFixture()
    val reportDir = File(fixtureDir, "build/reports/pitest/encoding")
    val buildScript = File(fixtureDir, "build.gradle.kts")

    buildScript.appendText("\n// a build-script comment\n")
    val script = runner("pitestEncodingVerify").buildAndFail().output
    assertTrue(script.contains("sourceSha256: recorded="), script)
    assertTrue(script.contains("A source the recompile does not compile changed"), script)
    assertFalse(script.contains(KEPT_REPORT_NOTICE), script)
    buildScript.writeText(buildScript.readText().removeSuffix("\n// a build-script comment\n"))

    val selection = File(fixtureDir, "config/pitest/encoding-prune-keys.csv")
    selection.parentFile.mkdirs()
    selection.writeText("com.example.Codec,encode,MathMutator,SURVIVED\n")
    val selected = runner(
      "pitestEncodingVerify", "-PpruneBaselineKeys.encoding=config/pitest/encoding-prune-keys.csv",
    ).buildAndFail().output
    assertTrue(selected.contains("A source the recompile does not compile changed"), selected)
    selection.delete()

    // A resource moves the processed classpath as well as the sources, so the refusal
    // names the other input instead of falling through to a run-prescribing retry.
    val resource = File(fixtureDir, "src/test/resources/fixture-note.txt")
    resource.parentFile.mkdirs()
    resource.writeText("a test resource\n")
    val resourceEdit = runner("pitestEncodingVerify").buildAndFail().output
    assertTrue(resourceEdit.contains("sourceSha256: recorded="), resourceEdit)
    assertTrue(resourceEdit.contains("classpathSha256: recorded="), resourceEdit)
    assertTrue(resourceEdit.contains("Beside the sources, another recorded input moved"), resourceEdit)
    assertTrue(resourceEdit.contains(RULE_RETRY), resourceEdit)
    assertFalse(resourceEdit.contains("Retry: run :pitestEncoding in a new Gradle invocation"), resourceEdit)
    resource.delete()

    File(fixtureDir, "src/main/java/com/example/Codec.java").appendText("\n// harmless comment\n")
    val sidecar = reportDir.resolve(".uncompiled-sources.tsv")
    val sidecarBytes = sidecar.readBytes()
    sidecar.delete()
    val unrecorded = runner("pitestEncodingVerify").buildAndFail().output
    assertTrue(unrecorded.contains("this report carries no record of them"), unrecorded)
    sidecar.writeBytes(sidecarBytes)
    val kept = runner("pitestEncodingVerify").build().output
    assertTrue(kept.contains(KEPT_REPORT_NOTICE), kept)
  }

  @Test
  fun `a licensed suite keeps the source rule for the tree ArcMutate reads and not for the tests`() {
    writeFixture(moneyMath = true)
    enableFakeArcMutate()
    runner("pitestEncoding", "-PnoMutationHistory").build()
    val sidecar = File(fixtureDir, "build/reports/pitest/encoding/.filter-sources.tsv")
    assertTrue(sidecar.isFile, "the run recorded no filter-source fingerprint")

    // Main source text is what the @Generated filter reads: a comment there refuses.
    val codec = File(fixtureDir, "src/main/java/com/example/Codec.java")
    val codecBytes = codec.readBytes()
    codec.appendText("\n// harmless comment\n")
    val stale = runner("pitestEncodingVerify").buildAndFail().output
    assertTrue(stale.contains("sourceSha256: recorded="), stale)
    assertTrue(stale.contains("ArcMutate is active for this suite"), stale)
    assertTrue(stale.contains("@Generated filter reads src/main/java, where a file changed"), stale)
    assertTrue(stale.contains("identical classes do not show an identical population here"), stale)
    assertFalse(stale.contains("owes a run here"), stale)
    assertTrue(stale.contains(RULE_RETRY), stale)
    assertFalse(stale.contains(KEPT_REPORT_NOTICE), stale)
    codec.writeBytes(codecBytes)

    // A test source is compiled by the same recompile but read by no filter: its
    // byte-identical class is the whole proof, so the report is kept.
    File(fixtureDir, "src/test/java/com/example/CodecFuzz.java").appendText("\n// test comment\n")
    val kept = runner("pitestEncodingVerify").build().output
    assertTrue(kept.contains(KEPT_REPORT_NOTICE), kept)

    // Without the record the verify cannot tell the two cases apart and refuses.
    val sidecarBytes = sidecar.readBytes()
    sidecar.delete()
    val unrecorded = runner("pitestEncodingVerify").buildAndFail().output
    assertTrue(unrecorded.contains("ArcMutate is active for this suite"), unrecorded)
    assertTrue(unrecorded.contains("or this report carries no record of that tree"), unrecorded)
    assertFalse(unrecorded.contains(KEPT_REPORT_NOTICE), unrecorded)
    sidecar.writeBytes(sidecarBytes)
    val restored = runner("pitestEncodingVerify").build().output
    assertTrue(restored.contains(KEPT_REPORT_NOTICE), restored)
  }

  @Test
  fun `a mode snapshot never stands on a kept report`() {
    writeKeptReportFixture(
      buildTail =
        """
          tasks.named("pitestEncodingModeEvidenceValidate") {
            val skipTwin = providers.gradleProperty("skipTwin").isPresent
            onlyIf { !skipTwin }
          }
        """.trimIndent(),
    )
    File(fixtureDir, "src/main/java/com/example/Codec.java").appendText("\n// harmless comment\n")
    val kept = runner("pitestEncodingVerify").build().output
    assertTrue(kept.contains(KEPT_REPORT_NOTICE), kept)

    // The strict twin refuses in the same invocation that keeps the report for the check.
    val strict = runner("pitestEncodingVerify", "pitestModeSnapshot", "-PpitestMode=solo")
      .buildAndFail().output
    assertTrue(strict.contains(KEPT_REPORT_NOTICE), strict)
    assertTrue(strict.contains("report/evidence pair no longer matches the current build"), strict)
    assertTrue(strict.contains("sourceSha256"), strict)
    // The strict twin refuses on its exact comparison, not by falling through the keep
    // conditions it does not evaluate.
    assertFalse(strict.contains("A source the recompile does not compile changed"), strict)
    assertFalse(strict.contains("Only the recompiled Java sources changed"), strict)
    assertFalse(strict.contains(RULE_RETRY), strict)
    assertFalse(File(fixtureDir, "build/pitest-modes/solo").exists(), strict)

    // A strict twin a consumer switch skips leaves the kept revalidation as the only one
    // in the session; the snapshot's own guard asks for an exact one and refuses.
    val skipped = runner(
      "pitestEncodingVerify", "pitestModeSnapshot", "-PpitestMode=solo", "-PskipTwin",
    ).buildAndFail().output
    assertTrue(skipped.contains(KEPT_REPORT_NOTICE), skipped)
    assertTrue(skipped.contains("report/evidence pair was not validated against the current build"), skipped)
    assertFalse(File(fixtureDir, "build/pitest-modes/solo").exists(), skipped)

    // Excluding the strict twin is refused by the exclusion rule before either guard.
    val excluded = runner(
      "pitestEncodingVerify", "pitestModeSnapshot", "-PpitestMode=solo",
      "-x", "pitestEncodingModeEvidenceValidate",
    ).buildAndFail().output
    assertTrue(excluded.contains("tasks are excluded (-x pitestEncodingModeEvidenceValidate)"), excluded)
    assertFalse(File(fixtureDir, "build/pitest-modes/solo").exists(), excluded)
  }

  @Test
  fun `a convergence snapshot never anchors on a kept report`() {
    // A consumer switch that skips the suite task leaves no attempt and no exclusion, so
    // the verify keeps the report; the round boundary that follows it must not take it.
    val reportDir = writeKeptReportFixture(
      buildTail =
        """
          tasks.named("pitestEncoding") {
            val skipPit = providers.gradleProperty("skipPit").isPresent
            onlyIf { !skipPit }
          }
        """.trimIndent(),
    )
    File(fixtureDir, "src/main/java/com/example/Codec.java").appendText("\n// harmless comment\n")
    val reportBytes = reportDir.resolve("mutations.csv").readBytes().toList()

    val refused = runner("pitestEncodingVerify", "pitestConvergeSnapshot", "-PskipPit").buildAndFail().output

    assertTrue(refused.contains(KEPT_REPORT_NOTICE), refused)
    assertTrue(refused.contains("a kept report serves the read-only check only and cannot anchor the diff"), refused)
    assertEquals(reportBytes, reportDir.resolve("mutations.csv").readBytes().toList(), "the canonical report was cleared:\n$refused")
    assertFalse(File(fixtureDir, "build/pitest-converge/round1").exists(), refused)
  }

  @Test
  fun `a kept report advances neither the status stash nor the timeout-quiet counter`() {
    writeFixture(moneyMath = true)
    File(fixtureDir, "config/pitest").mkdirs()
    File(fixtureDir, "config/pitest/encoding-timeouts.csv").writeText(
      "com.example.Codec,encode,MathMutator # cause:liveness\n",
    )
    File(fixtureDir, "config/pitest/README.md").writeText(
      "`com.example.Codec.encode`: removing progress makes the production path non-terminating.\n",
    )
    runner("pitestEncoding").build()
    val statusStash = File(fixtureDir, ".pitest-history/encoding.statuses")
    val quietStash = File(fixtureDir, ".pitest-history/encoding.timeout-quiet")
    assertTrue(statusStash.isFile, "a fresh run wrote no status stash")
    assertTrue(quietStash.isFile, "a fresh run with an audited timeout wrote no quiet counter")
    val quietBefore = quietStash.readText()

    // Deleted first: a replay of an exact match rewrites both, a kept report neither.
    assertTrue(statusStash.delete() && quietStash.delete())
    File(fixtureDir, "src/main/java/com/example/Codec.java").appendText("\n// harmless comment\n")
    val kept = runner("pitestEncodingVerify").build().output
    assertTrue(kept.contains(KEPT_REPORT_NOTICE), kept)
    assertFalse(statusStash.exists(), "a kept verify recreated the status stash")
    assertFalse(quietStash.exists(), "a kept verify counted itself as a quiet run")

    // The next fresh run counts itself once: the deleted counter starts again at one
    // quiet run, with nothing carried over from the kept verify.
    runner("pitestEncoding").build()
    assertTrue(quietStash.isFile)
    assertTrue(
      quietStash.readLines().contains("com.example.Codec,encode,MathMutator,1"),
      "the member's count is not one quiet run:\n${quietStash.readText()}\n(before: $quietBefore)",
    )
  }

  @Test
  fun `PIT evidence creation and removal do not invalidate the configuration cache`() {
    writeFixture()
    // `check` replays every declared corpus. Seed both corpus-backed fixture targets
    // so this test isolates configuration-graph stability instead of failing the
    // deliberately strict generated replay contract.
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")

    val cold = runner("check").build().output
    assertFalse(cold.contains("Reusing configuration cache"), cold)

    val reportDir = File(fixtureDir, "build/reports/pitest/encoding")
    val evidence = reportDir.resolve(".evidence.tsv").apply {
      parentFile.mkdirs()
      writeText("fixture evidence whose contents are irrelevant to the check graph\n")
    }
    val toolchain = reportDir.resolve(".toolchain.tsv").apply {
      writeText("fixture toolchain whose contents are irrelevant to the check graph\n")
    }
    val afterCreation = runner("check").build().output
    assertTrue(
      afterCreation.contains("Reusing configuration cache"),
      "creating PIT evidence selected a different configuration graph:\n$afterCreation",
    )

    assertTrue(evidence.delete(), "fixture could not remove its evidence manifest")
    assertTrue(toolchain.delete(), "fixture could not remove its toolchain manifest")
    val afterRemoval = runner("check").build().output
    assertTrue(
      afterRemoval.contains("Reusing configuration cache"),
      "removing PIT evidence selected a different configuration graph:\n$afterRemoval",
    )
  }

  @Test
  fun `mode snapshot refuses completed evidence after its inputs change`() {
    writeFixture()
    runner("pitestEncoding").build()

    File(fixtureDir, "src/main/java/com/example/FakePit.java")
        .appendText("\n// changed after the completed PIT observation\n")
    val stale = runner("pitestModeSnapshot", "-PpitestMode=stale").buildAndFail().output

    assertTrue(stale.contains("report/evidence pair no longer matches the current build"), stale)
    assertTrue(stale.contains("sourceSha256"), stale)
    assertTrue(
      stale.contains("re-run the affected suite in the intended mode") &&
          stale.contains(":pitestModeSnapshot with the same -PpitestMode label"),
      stale,
    )
    assertFalse(stale.contains("receipt is project-atomic"), stale)
  }

  @Test
  fun `PIT refuses to commit evidence when an input changes during execution`() {
    writeFixture()
    File(fixtureDir, "fake-pit-mode.txt").writeText("mutate-input\n")

    val failed = runner("pitestEncoding").buildAndFail().output
    val reportDir = File(fixtureDir, "build/reports/pitest/encoding")
    assertTrue(failed.contains("evidence inputs changed while PIT was running"), failed)
    assertTrue(failed.contains("sourceSha256"), failed)
    assertFalse(reportDir.resolve(".evidence.tsv").exists(), "drifting inputs committed evidence")
    assertTrue(reportDir.resolve(".running").isFile, "drifting inputs exposed the report")
  }

  @Test
  fun `PIT refuses to commit evidence when the recompile stamp is withdrawn during execution`() {
    writeFixture()
    File(fixtureDir, "fake-pit-mode.txt").writeText("withdraw-recompile-stamp\n")

    // Both captures agree, so only the completion-time stamp check can notice.
    val failed = runner("pitestEncoding").buildAndFail().output
    val reportDir = File(fixtureDir, "build/reports/pitest/encoding")
    assertTrue(failed.contains("compileForPitest has published no stamp naming the sources and classes now on disk"), failed)
    assertFalse(failed.contains("evidence inputs changed while PIT was running"), failed)
    assertFalse(reportDir.resolve(".evidence.tsv").exists(), "an unvouched tree committed evidence")
    assertTrue(reportDir.resolve(".running").isFile, "an unvouched tree exposed the report")
  }

  @Test
  fun `PIT refuses a plugin code path replaced after application`() {
    val privateRepo = fixtureDir.resolve("private-test-repo")
    check(File(savaBuildTestProperty("savaBuild.testRepo")).copyRecursively(privateRepo))
    val fixturePlugin = privateRepo.resolve(
      "software/sava/sava-build/$savaBuildTestRepoVersion/" +
        "sava-build-$savaBuildTestRepoVersion.jar"
    )
    val escapedRepo = privateRepo.absolutePath.replace("\\", "\\\\")
    val privatePluginManagement =
      "pluginManagement { repositories { maven(url = \"$escapedRepo\"); gradlePluginPortal() }; " +
        "resolutionStrategy.eachPlugin { if (requested.id.id.startsWith(\"software.sava.build\")) { " +
        "useModule(\"software.sava:sava-build:$savaBuildTestRepoVersion\") } } }\n" +
        "plugins { id(\"software.sava.build\") version \"$savaBuildTestRepoVersion\" }"
    val escapedPlugin = fixturePlugin.absolutePath.replace("\\", "\\\\")
    writeFixture(
      buildTail =
        """
          tasks.named<JavaExec>("pitestEncoding") {
            environment("FIXTURE_PLUGIN_ARTIFACT", "$escapedPlugin")
          }
        """.trimIndent(),
      pluginManagement = privatePluginManagement,
      projectRepositories = "",
    )
    File(fixtureDir, "fake-pit-mode.txt").writeText("mutate-plugin\n")
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")

    val failed = runner(
      "hardeningCertify", "--refresh-dependencies", "-PsavaBuildLocalRepo=$privateRepo",
    ).buildAndFail().output
    val reportDir = File(fixtureDir, "build/reports/pitest/encoding")

    assertTrue(failed.contains("local plugin artifact changed after plugin application"), failed)
    assertTrue(failed.contains("refusing evidence from mixed plugin bytes"), failed)
    assertFalse(reportDir.resolve(".evidence.tsv").exists(), "mixed plugin bytes committed evidence")
    assertTrue(reportDir.resolve(".running").isFile, "mixed plugin bytes exposed the report")
    assertFalse(
      File(fixtureDir, ".pitest-history/pitest-certification.tsv").exists(),
      "mixed plugin bytes committed a certification receipt",
    )
  }

  @Test
  fun `malformed attempt state is refused before any report leaf is changed`() {
    writeFixture()
    val reportDir = File(fixtureDir, "build/reports/pitest/encoding")
    val staleCsv = reportDir.resolve("mutations.csv").apply {
      parentFile.mkdirs()
      writeText("stale report\n")
    }
    val staleLog = reportDir.resolve("pitest.stdout.log").apply { writeText("stale log\n") }
    reportDir.resolve(".evidence.tsv/blocked").apply {
      parentFile.mkdirs()
      writeText("not a replaceable evidence file")
    }

    val failed = runner("pitestEncoding").buildAndFail().output

    assertFalse(
      reportDir.resolve(".running").exists(),
      "pre-attempt validation partially started a report lifecycle:\n$failed",
    )
    assertTrue(
      reportDir.resolve(".evidence.tsv/blocked").isFile,
      "pre-attempt validation changed the malformed leaf it refused",
    )
    assertEquals("stale report\n", staleCsv.readText(), "atomic validation deleted the old CSV")
    assertEquals("stale log\n", staleLog.readText(), "atomic validation truncated the old log")
    assertFalse(reportDir.resolve("arguments.txt").exists(), "PIT ran after pre-attempt refusal")
    assertTrue(
      failed.contains("partial population is not evidence") ||
          failed.contains(".evidence.tsv"),
      failed)
  }
}
