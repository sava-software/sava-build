package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Golden-corpus tests over the consumer fleet's committed mutation-baseline files.
 *
 * `src/test/resources/golden-fleet/` holds byte-identical snapshots of the
 * `config/pitest/` directories enumerated by its `MANIFEST.txt` (source repo,
 * module path, and HEAD commit). This historical parser corpus is deliberately
 * independent of the release runner's current fleet inventory; its own manifest
 * is nevertheless exact, so an omitted snapshot cannot turn these checks vacuous.
 * Where [BaselineNotesTest]
 * and [TimeoutAuditTest] argue the format's edge cases with constructed lines,
 * these tests hold the parsers to the data the fleet actually shipped: a format
 * regression that would only surface in a consumer's verify after release fails
 * here first, against the exact rows it would break on.
 *
 * The corpus must stay byte-identical to the fleet's committed state — a failure
 * here is fixed in the parser (or recorded as a named known-bad row below), never
 * by editing the snapshot.
 */
class GoldenFleetTest {

  private companion object {
    data class ManifestEntry(val repo: String, val modulePath: String, val commit: String) {
      val corpusPath: String =
          "$repo/${modulePath.replace("/", "__")}"
    }

    /**
     * The corpus root, resolved through the classpath so the tests exercise the
     * same resource-processing path a packaging change would break.
     */
    val corpusRoot: File = GoldenFleetTest::class.java.getResource("/golden-fleet/MANIFEST.txt")
        ?.let { File(it.toURI()).parentFile }
        ?: error("golden-fleet corpus is missing from the test classpath")

    val manifestEntries: List<ManifestEntry> = File(corpusRoot, "MANIFEST.txt").readLines()
        .withIndex()
        .filter { (_, line) -> line.isNotBlank() && !line.trimStart().startsWith("#") }
        .map { (index, line) ->
          val fields = line.split('\t')
          require(fields.size == 3) {
            "golden-fleet/MANIFEST.txt:${index + 1}: expected repo, module path, and commit"
          }
          ManifestEntry(fields[0], fields[1], fields[2])
        }

    /**
     * Corpus files whose committed content genuinely fails an assertion, keyed by
     * corpus-relative path. Empty is the goal: an entry here records a real fleet
     * defect (named in the entry's comment) without weakening the assertion for
     * every other file. Currently none.
     */
    val knownMalformedAccepted: Set<String> = emptySet()
    val knownMalformedTimeouts: Set<String> = emptySet()
    val knownUndocumentedCauses: Set<String> = emptySet()

    /**
     * These byte-identical historical snapshots predate the cause-category field.
     * Pin the debt exactly instead of rewriting their provenance or letting a new
     * unclassified member disappear into a count. Current consumer records must
     * carry an admissible `cause:liveness`; the parser's unit tests cover that
     * post-schema shape independently.
     */
    val expectedHistoricalCauseFindings: List<String> = listOf(
      "http-servers/http-servers-core/handlers-timeouts.csv: software.sava.http_servers.core.handlers.PathCanonicalizer,canonicalize,IncrementsMutator # missing cause:liveness/resource/harness/untriaged",
      "http-servers/http-servers-core/handlers-timeouts.csv: software.sava.http_servers.core.handlers.HandlerUtil,indexOfParam,MathMutator # missing cause:liveness/resource/harness/untriaged",
      "http-servers/http-servers-core/handlers-timeouts.csv: software.sava.http_servers.core.handlers.HandlerUtil,parseIntParams,IncrementsMutator # missing cause:liveness/resource/harness/untriaged",
      "http-servers/http-servers-core/logging-timeouts.csv: software.sava.http_servers.core.logging.BaseJulLogger,formatPlaceholders,IncrementsMutator # missing cause:liveness/resource/harness/untriaged",
      "http-servers/http-servers-jdk/dispatch-timeouts.csv: software.sava.http_servers.jdk.JdkQueryHandler,handle,VoidMethodCallMutator # missing cause:liveness/resource/harness/untriaged",
      "http-servers/http-servers-jdk/dispatch-timeouts.csv: software.sava.http_servers.jdk.JdkQueryHandler,lambda\$handle\$0,VoidMethodCallMutator # missing cause:liveness/resource/harness/untriaged",
      "http-servers/http-servers-jdk/dispatch-timeouts.csv: software.sava.http_servers.jdk.JdkController,handle,VoidMethodCallMutator # missing cause:liveness/resource/harness/untriaged",
      "http-servers/http-servers-jdk/dispatch-timeouts.csv: software.sava.http_servers.jdk.JdkHttpServer,start,VoidMethodCallMutator # missing cause:liveness/resource/harness/untriaged",
      "http-servers/http-servers-jetty/dispatch-timeouts.csv: software.sava.http_servers.jetty.JettyController,handle,VoidMethodCallMutator # missing cause:liveness/resource/harness/untriaged",
      "http-servers/http-servers-jetty/dispatch-timeouts.csv: software.sava.http_servers.jetty.JettyQueryHandler,handle,VoidMethodCallMutator # missing cause:liveness/resource/harness/untriaged",
      "http-servers/http-servers-jetty/dispatch-timeouts.csv: software.sava.http_servers.jetty.JettyCachedJsonResponseHandler,handle,VoidMethodCallMutator # missing cause:liveness/resource/harness/untriaged",
      "idl-src-gen/idl-src-gen/jsonParse-timeouts.csv: software.sava.idl.generator.LegacyAnchorIdlConverter,convert,RemoveConditionalMutator_EQUAL_ELSE # missing cause:liveness/resource/harness/untriaged",
      "ix-proxy/ix-proxy/ixProxy-timeouts.csv: systems.glam.ix.proxy.ConfigLoader\$Worker,get,MathMutator # missing cause:liveness/resource/harness/untriaged",
      "ix-proxy/ix-proxy/ixProxy-timeouts.csv: systems.glam.ix.proxy.ConfigLoader\$Worker,get,RemoveConditionalMutator_ORDER_ELSE # missing cause:liveness/resource/harness/untriaged",
      "json-iterator/json-iterator/iterator-timeouts.csv: systems.comodal.jsoniter.BaseJsonIterator,reduceScale,IncrementsMutator # missing cause:liveness/resource/harness/untriaged",
      "json-iterator/json-iterator/iterator-timeouts.csv: systems.comodal.jsoniter.BaseJsonIterator,reduceScale,RemoveConditionalMutator_ORDER_IF # missing cause:liveness/resource/harness/untriaged",
      "json-iterator/json-iterator/iterator-timeouts.csv: systems.comodal.jsoniter.BaseJsonIterator,skipObject,IncrementsMutator # missing cause:liveness/resource/harness/untriaged",
      "json-iterator/json-iterator/iterator-timeouts.csv: systems.comodal.jsoniter.BytesJsonIterator,parseMultiByteString,MathMutator # missing cause:liveness/resource/harness/untriaged",
      "json-iterator/json-iterator/iterator-timeouts.csv: systems.comodal.jsoniter.CharsJsonIterator,parse,IncrementsMutator # missing cause:liveness/resource/harness/untriaged",
      "json-iterator/json-iterator/iterator-timeouts.csv: systems.comodal.jsoniter.CharsJsonIterator,skipPastEndQuote,MathMutator # missing cause:liveness/resource/harness/untriaged",
      "json-iterator/json-iterator/util-timeouts.csv: systems.comodal.jsoniter.JIUtil,escapeQuotesChecked,IncrementsMutator # missing cause:liveness/resource/harness/untriaged",
      "json-iterator/json-iterator/util-timeouts.csv: systems.comodal.jsoniter.FieldMatcher,of,MathMutator # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-core/backoff-timeouts.csv: software.sava.services.core.remote.call.Backoff,fibonacci,RemoveConditionalMutator_ORDER_ELSE # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-core/backoff-timeouts.csv: software.sava.services.core.remote.call.ExponentialBackoffErrorHandler,<init>,RemoveConditionalMutator_ORDER_IF # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-core/backoff-timeouts.csv: software.sava.services.core.remote.call.ExponentialBackoffErrorHandler,<init>,ConditionalsBoundaryMutator # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-core/calls-timeouts.csv: software.sava.services.core.remote.call.ComposedCall,get,MathMutator # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-core/calls-timeouts.csv: software.sava.services.core.remote.call.ComposedCall,get,RemoveConditionalMutator_ORDER_ELSE # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-core/calls-timeouts.csv: software.sava.services.core.remote.call.CourteousBalancedCall,call,ConditionalsBoundaryMutator # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-core/calls-timeouts.csv: software.sava.services.core.remote.call.CourteousBalancedCall,call,IncrementsMutator # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-core/calls-timeouts.csv: software.sava.services.core.remote.call.CourteousBalancedCall,call,RemoveConditionalMutator_EQUAL_IF # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-core/calls-timeouts.csv: software.sava.services.core.remote.call.CourteousBalancedCall,call,RemoveConditionalMutator_ORDER_ELSE # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-core/calls-timeouts.csv: software.sava.services.core.remote.call.CourteousCall,call,ConditionalsBoundaryMutator # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-core/calls-timeouts.csv: software.sava.services.core.remote.call.CourteousCall,call,RemoveConditionalMutator_ORDER_ELSE # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-core/calls-timeouts.csv: software.sava.services.core.remote.call.CourteousCall,call,RemoveConditionalMutator_ORDER_IF # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-core/calls-timeouts.csv: software.sava.services.core.remote.call.UncheckedBalancedCall,get,RemoveConditionalMutator_ORDER_ELSE # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-core/capacity-timeouts.csv: software.sava.services.core.request_capacity.CapacityStateVal,hasCapacity,BooleanTrueReturnValsMutator # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-core/catchAll-timeouts.csv: software.sava.services.core.exceptions.ExceptionUtil,containsException,NakedReceiverMutator # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-core/catchAll-timeouts.csv: software.sava.services.core.exceptions.ExceptionUtil,containsIOException,NakedReceiverMutator # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-core/catchAll-timeouts.csv: software.sava.services.core.exceptions.ExceptionUtil,getException,NakedReceiverMutator # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-core/config-timeouts.csv: software.sava.services.core.remote.load_balance.LoadBalancerConfig\$Parser,lambda\$parseProperties\$3,BooleanTrueReturnValsMutator # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-core/config-timeouts.csv: software.sava.services.core.remote.load_balance.LoadBalancerConfig\$Parser,parseProperties,RemoveConditionalMutator_EQUAL_ELSE # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-core/loadBalance-timeouts.csv: software.sava.services.core.remote.load_balance.ArrayLoadBalancer,peek,RemoveConditionalMutator_EQUAL_ELSE # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-core/loadBalance-timeouts.csv: software.sava.services.core.remote.load_balance.LoadBalancerConfig\$Parser,lambda\$parseProperties\$3,BooleanTrueReturnValsMutator # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-core/loadBalance-timeouts.csv: software.sava.services.core.remote.load_balance.LoadBalancerConfig\$Parser,parseProperties,RemoveConditionalMutator_EQUAL_ELSE # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-core/loadBalance-timeouts.csv: software.sava.services.core.remote.load_balance.SortedLoadBalancer,nextNoSkip,RemoveConditionalMutator_EQUAL_ELSE # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-solana/catchAll-timeouts.csv: software.sava.services.solana.alt.LookupTableCacheMap,getOrFetchTables,MathMutator # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-solana/catchAll-timeouts.csv: software.sava.services.solana.alt.LookupTableCacheMap,refreshStaleAccounts,ConditionalsBoundaryMutator # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-solana/catchAll-timeouts.csv: software.sava.services.solana.alt.LookupTableCacheMap,refreshStaleAccounts,RemoveConditionalMutator_ORDER_IF # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-solana/catchAll-timeouts.csv: software.sava.services.solana.transactions.BaseTxMonitorService,run,RemoveConditionalMutator_EQUAL_IF # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-solana/catchAll-timeouts.csv: software.sava.services.solana.transactions.TxExpirationMonitorService,lambda\$processTransactions\$1,RemoveConditionalMutator_EQUAL_IF # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-solana/catchAll-timeouts.csv: software.sava.services.solana.transactions.TxExpirationMonitorService,lambda\$processTransactions\$1,NullReturnValsMutator # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-solana/catchAll-timeouts.csv: software.sava.services.solana.transactions.TxCommitmentMonitorService,lambda\$tryAwaitCommitmentViaWebSocket\$1,NakedReceiverMutator # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-solana/catchAll-timeouts.csv: software.sava.services.solana.transactions.TxCommitmentMonitorService,validateResponseAndAwaitCommitmentViaWebSocket,RemoveConditionalMutator_EQUAL_IF # missing cause:liveness/resource/harness/untriaged",
      "ravina/ravina-solana/epochService-timeouts.csv: software.sava.services.solana.epoch.EpochInfoServiceImpl,awaitInitialized,VoidMethodCallMutator # missing cause:liveness/resource/harness/untriaged",
      "sava/sava-core/ed25519-timeouts.csv: software.sava.core.crypto.ed25519.Ed25519Util,pack25519,RemoveConditionalMutator_ORDER_IF # missing cause:liveness/resource/harness/untriaged",
      "sava/sava-core/ed25519-timeouts.csv: software.sava.core.crypto.ed25519.Ed25519Util,pow2523,RemoveConditionalMutator_ORDER_IF # missing cause:liveness/resource/harness/untriaged",
      "sava/sava-core/ed25519-timeouts.csv: software.sava.core.crypto.ed25519.Ed25519Util,scalarMultBase,IncrementsMutator # missing cause:liveness/resource/harness/untriaged",
      "sava/sava-core/ed25519-timeouts.csv: software.sava.core.crypto.ed25519.Ed25519Util,scalarMultBase,RemoveConditionalMutator_ORDER_ELSE # missing cause:liveness/resource/harness/untriaged",
      "sava/sava-core/encoding-timeouts.csv: software.sava.core.encoding.Jex,isValid,IncrementsMutator # missing cause:liveness/resource/harness/untriaged",
      "sava/sava-core/encoding-timeouts.csv: software.sava.core.encoding.Base58,limbsLength,MathMutator # missing cause:liveness/resource/harness/untriaged",
      "sava/sava-core/tx-timeouts.csv: software.sava.core.accounts.lookup.AddressLookupTableOverlay,lambda\$keysToString\$1,PrimitiveReturnsMutator # missing cause:liveness/resource/harness/untriaged",
      "sava/sava-core/vanity-timeouts.csv: software.sava.core.accounts.vanity.SubsequenceRecord,formatCharOptions,RemoveConditionalMutator_ORDER_IF # missing cause:liveness/resource/harness/untriaged",
      "sava/sava-rpc/ws-timeouts.csv: software.sava.rpc.json.http.ws.SolanaJsonRpcWebsocket,run,RemoveConditionalMutator_EQUAL_IF # missing cause:liveness/resource/harness/untriaged",
      "sava/sava-rpc/ws-timeouts.csv: software.sava.rpc.json.http.ws.SolanaJsonRpcWebsocket,checkCycle,VoidMethodCallMutator # missing cause:liveness/resource/harness/untriaged",
      "sava/sava-rpc/ws-timeouts.csv: software.sava.rpc.json.http.ws.SolanaJsonRpcWebsocket,run,VoidMethodCallMutator # missing cause:liveness/resource/harness/untriaged",
      "sava/sava-rpc/ws-timeouts.csv: software.sava.rpc.json.http.ws.SolanaJsonRpcWebsocket,close,VoidMethodCallMutator # missing cause:liveness/resource/harness/untriaged",
      "sava/sava-rpc/ws-timeouts.csv: software.sava.rpc.json.http.ws.SolanaJsonRpcWebsocket,lambda\$connect\$2,RemoveConditionalMutator_EQUAL_ELSE # missing cause:liveness/resource/harness/untriaged",
      "sava/sava-rpc/ws-timeouts.csv: software.sava.rpc.json.http.ws.SolanaJsonRpcWebsocket,lambda\$connect\$1,NakedReceiverMutator # missing cause:liveness/resource/harness/untriaged",
      "sava/sava-rpc/ws-timeouts.csv: software.sava.rpc.json.http.ws.SolanaJsonRpcWebsocket,closed,RemoveConditionalMutator_ORDER_ELSE # missing cause:liveness/resource/harness/untriaged",
      "vault-stat-service/vault-stat-service/service-timeouts.csv: systems.glam.services.vault.stats.rest.handlers.glam.tables.BaseGlamVaultTableHandler,extendAndCreateTables,RemoveConditionalMutator_EQUAL_IF # missing cause:liveness/resource/harness/untriaged",
      "vault-stat-service/vault-stat-service/service-timeouts.csv: systems.glam.services.vault.stats.rest.handlers.glam.tables.BaseGlamVaultTableHandler,extendAndCreateTables,RemoveConditionalMutator_EQUAL_ELSE # missing cause:liveness/resource/harness/untriaged",
      "vault-stat-service/vault-stat-service/service-timeouts.csv: systems.glam.services.vault.stats.rest.handlers.glam.sparkle.SparkleHandler,generatePNG,IncrementsMutator # missing cause:liveness/resource/harness/untriaged",
      "vault-stat-service/vault-stat-service/service-timeouts.csv: systems.glam.services.vault.stats.rest.handlers.glam.sparkle.SparkleHandler,generatePNG,RemoveConditionalMutator_ORDER_IF # missing cause:liveness/resource/harness/untriaged",
      "vault-stat-service/vault-stat-service/service-timeouts.csv: systems.glam.services.vault.stats.rest.handlers.glam.sparkle.SparkleHandler,generatePNG,MathMutator # missing cause:liveness/resource/harness/untriaged",
      "vault-stat-service/vault-stat-service/service-timeouts.csv: systems.glam.services.logging.BaseJulLogger,formatPlaceholders,IncrementsMutator # missing cause:liveness/resource/harness/untriaged",
      "vault-stat-service/vault-stat-service/service-timeouts.csv: systems.glam.services.vault.stats.rest.handlers.kamino.KaminoVaultContextHandler,httpResponse,RemoveConditionalMutator_EQUAL_ELSE # missing cause:liveness/resource/harness/untriaged",
      "vault-stat-service/vault-stat-service/service-timeouts.csv: systems.glam.services.vault.stats.rest.handlers.kamino.KaminoVaultContextHandler,httpResponse,VoidMethodCallMutator # missing cause:liveness/resource/harness/untriaged",
      "vault-stat-service/vault-stat-service/service-timeouts.csv: systems.glam.services.vault.stats.rest.handlers.kamino.KaminoReserveContextsHandler,httpResponse,VoidMethodCallMutator # missing cause:liveness/resource/harness/untriaged",
      "vault-stat-service/vault-stat-service/service-timeouts.csv: systems.glam.services.monitor.IncidentNotifierImpl,queueResponse,VoidMethodCallMutator # missing cause:liveness/resource/harness/untriaged",
      "vault-stat-service/vault-stat-service/service-timeouts.csv: systems.glam.services.monitor.IncidentNotifierImpl,run,RemoveConditionalMutator_EQUAL_ELSE # missing cause:liveness/resource/harness/untriaged",
      "vault-stat-service/vault-stat-service/service-timeouts.csv: systems.glam.services.monitor.IncidentNotifierImpl,run,VoidMethodCallMutator # missing cause:liveness/resource/harness/untriaged",
      "vault-stat-service/vault-stat-service/valuationManager-timeouts.csv: systems.glam.services.vault.stats.value.GlamVaultValuationManagerImpl,glamVaultTableUpdate,RemoveConditionalMutator_EQUAL_IF # missing cause:liveness/resource/harness/untriaged",
      "vault-stat-service/vault-stat-service/value-timeouts.csv: systems.glam.services.vault.stats.value.GlamVaultExecutorImpl,refreshStateContext,RemoveConditionalMutator_EQUAL_ELSE # missing cause:liveness/resource/harness/untriaged",
      "vault-stat-service/vault-stat-service/value-timeouts.csv: systems.glam.services.vault.stats.value.GlamStateContextCacheImpl,run,VoidMethodCallMutator # missing cause:liveness/resource/harness/untriaged",
    )

    fun corpusFiles(suffix: String): List<File> = corpusRoot.walkTopDown()
        .filter { it.isFile && it.name.endsWith(suffix) }
        .sortedBy { it.relativeTo(corpusRoot).path }
        .toList()

    /**
     * Record lines presented by either supported baseline document schema. Parsing
     * through [BaselineDocument] keeps this fleet corpus sensitive to schema headers
     * while retaining malformed records for the dedicated assertion below.
     */
    fun rowLines(file: File): List<String> =
        if (file.name.endsWith("-accepted.csv")) {
          BaselineDocument.parse(file.readText()).entries.mapNotNull {
            when (it) {
              is BaselineDocument.Entry.Row -> it.raw
              is BaselineDocument.Entry.MalformedRow -> it.raw
              is BaselineDocument.Entry.Blank,
              is BaselineDocument.Entry.Comment -> null
            }
          }
        } else {
          file.readLines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        }

    fun relative(file: File): String = file.relativeTo(corpusRoot).invariantSeparatorsPath
  }

  @Test
  fun `the manifest exactly describes the corpus inventory`() {
    val malformedEntries = manifestEntries.filter {
      it.repo.isBlank() || it.modulePath.isBlank() || !it.commit.matches(Regex("[0-9a-f]{40}"))
    }
    assertEquals(emptyList<ManifestEntry>(), malformedEntries, "malformed golden-fleet manifest row(s)")
    assertEquals(
        manifestEntries.size,
        manifestEntries.distinctBy { it.corpusPath }.size,
        "golden-fleet manifest maps more than one row to the same corpus directory")

    val expectedModules = manifestEntries.map { it.corpusPath }.sorted()
    val actualModules = corpusRoot.listFiles { file -> file.isDirectory }.orEmpty()
        .flatMap { repo ->
          repo.listFiles { file -> file.isDirectory }.orEmpty()
              .map { module -> "${repo.name}/${module.name}" }
        }
        .sorted()
    assertEquals(expectedModules, actualModules,
        "golden-fleet module directories diverged from MANIFEST.txt")

    val expectedRepos = manifestEntries.map { it.repo }.distinct().sorted()
    val actualRepos = corpusRoot.listFiles { file -> file.isDirectory }.orEmpty()
        .map { it.name }.sorted()
    assertEquals(expectedRepos, actualRepos,
        "golden-fleet repository directories diverged from MANIFEST.txt")

    expectedModules.forEach { modulePath ->
      val module = File(corpusRoot, modulePath)
      assertTrue(File(module, "README.md").isFile, "$modulePath has no snapshotted README.md")
      assertTrue(
          module.listFiles { file -> file.isFile && file.name.endsWith("-accepted.csv") }
              .orEmpty().isNotEmpty(),
          "$modulePath has no snapshotted accepted baseline",
      )
    }

    assertTrue(corpusFiles("-accepted.csv").isNotEmpty(), "no accepted baselines in the corpus")
    assertTrue(corpusFiles("-timeouts.csv").isNotEmpty(), "no timeout memberships in the corpus")
    assertTrue(corpusFiles("README.md").isNotEmpty(), "no READMEs in the corpus")
  }

  @Test
  fun `every golden accepted row parses as well-formed`() {
    val failures = corpusFiles("-accepted.csv")
        .filterNot { relative(it) in knownMalformedAccepted }
        .flatMap { file ->
          rowLines(file).filter { BaselineNotes.malformed(it) }
              .map { "${relative(file)}: $it" }
        }
    assertEquals(emptyList<String>(), failures,
        "malformed accepted row(s) in fleet data the parser must handle")
  }

  @Test
  fun `every golden accepted row canonicalizes to a render fixed point`() {
    // The first round-trip may change a row — a legacy five-field row migrates its
    // line field to a '# line' tag, and spacing or '# lines 137/141' separators
    // normalize — but the canonical form must be a fixed point: a second
    // parse/render that changes anything would churn every refresh forever.
    val failures = corpusFiles("-accepted.csv").flatMap { file ->
      rowLines(file).mapNotNull { line ->
        val canonical = BaselineNotes.render(BaselineNotes.parse(line))
        val again = BaselineNotes.render(BaselineNotes.parse(canonical))
        if (again == canonical) null
        else "${relative(file)}:\n    original:  $line\n    canonical: $canonical\n    again:     $again"
      }
    }
    assertEquals(emptyList<String>(), failures, "canonical form is not a render fixed point")
  }

  @Test
  fun `summarize speaks for every non-empty golden baseline`() {
    corpusFiles("-accepted.csv").forEach { file ->
      val rows = rowLines(file).map { BaselineNotes.parse(it) }
      val summary = BaselineNotes.summarize(
          rows.mapNotNull { it.note }, rows.count { it.note == null })
      if (rows.isNotEmpty()) {
        assertNotNull(summary, "${relative(file)}: a non-empty baseline summarized to nothing")
      }
    }
  }

  @Test
  fun `every golden timeout membership parses as well-formed`() {
    val failures = corpusFiles("-timeouts.csv")
        .filterNot { relative(it) in knownMalformedTimeouts }
        .flatMap { file ->
          TimeoutAudit.parse(file.readLines()).malformed.map { "${relative(file)}: $it" }
        }
    assertEquals(emptyList<String>(), failures,
        "malformed timeout row(s) in fleet data the parser must handle")
  }

  @Test
  fun `the historical timeout corpus exposes its exact pre-schema cause debt`() {
    val findings = corpusFiles("-timeouts.csv").flatMap { file ->
      TimeoutAudit.parse(file.readLines()).causeFindings.map { finding ->
        "${relative(file)}: ${finding.member} # ${finding.detail}"
      }
    }
    assertEquals(
        expectedHistoricalCauseFindings.sorted(),
        findings.sorted(),
        "historical cause-classification debt changed; keep snapshots byte-identical and " +
            "classify the corresponding live consumer record instead",
    )
  }

  @Test
  fun `every golden audited timeout has its cause documented in the module README`() {
    // The fleet's discipline, held as a fixture: each audited member's structural
    // cause lives in the module's config/pitest/README.md, and undocumentedCauses
    // is the check that keeps it that way — so against shipped data it must find
    // nothing (a hit is either a fleet defect to record above, or a resolver
    // regression that would nag every consumer about causes they already wrote).
    val failures = corpusFiles("-timeouts.csv")
        .filterNot { relative(it) in knownUndocumentedCauses }
        .flatMap { file ->
          val readme = File(file.parentFile, "README.md")
          assertTrue(readme.isFile,
              "${relative(file)}: no adjacent README.md — the fleet always pairs them")
          TimeoutAudit.undocumentedCauses(
              TimeoutAudit.parse(file.readLines()).members) { readme.readText() }
              .map { "${relative(file)}: cause? $it" }
        }
    assertEquals(emptyList<String>(), failures,
        "audited timeout member(s) whose README cause did not resolve")
  }

  @Test
  fun `the corpus canary retains every snapshotted baseline row`() {
    // This is deliberately exact. The manifest inventory catches a missing module,
    // while the row total catches partial resource loss within a still-present one.
    // A deliberate corpus refresh updates this number alongside its provenance.
    val totalRows = corpusFiles("-accepted.csv")
        .sumOf { BaselineDocument.parse(it.readText()).rows.size } +
        corpusFiles("-timeouts.csv").sumOf { rowLines(it).size }
    assertEquals(1564, totalRows,
        "golden-fleet baseline row count changed; restore lost resources or record the intentional refresh")
  }
}
