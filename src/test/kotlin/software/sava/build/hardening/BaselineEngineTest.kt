package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Property tests over the baseline transition kernels. The fleet review's taxonomy
 * put ~40% of post-release incidents in state-transition edge cases that pointwise
 * fixtures kept missing one neighbor at a time — these assert the INVARIANTS over
 * seeded-random populations instead, so the neighbor cases are visited by the
 * thousands. Seeded [Random] only: a failure reproduces from its seed.
 */
class BaselineEngineTest {

  private val statuses = listOf("SURVIVED", "NO_COVERAGE")
  private val classes = listOf("com.example.A", "com.example.B")
  private val methods = listOf("m1", "m2", "m3")
  private val mutators = listOf("MathMutator", "IncrementsMutator")

  private class Population(
    val acceptedRows: List<BaselineNotes.Row>,
    val currentLines: Map<String, List<String>>,
    val timedOutLines: Map<String, List<Int?>>,
    val killedLines: Map<String, List<Int?>>,
  )

  /** Exhaustive oracle for the small per-key graphs generated below. */
  private fun maximumExactAffinity(
    rows: List<BaselineNotes.Row>,
    observedLines: List<String>,
  ): Int {
    val copies = observedLines.map { it.toIntOrNull() }
    val memo = HashMap<Pair<Int, Int>, Int>()
    fun search(copyIndex: Int, usedRows: Int): Int {
      if (copyIndex == copies.size) return 0
      val state = copyIndex to usedRows
      memo[state]?.let { return it }
      var best = search(copyIndex + 1, usedRows)
      val line = copies[copyIndex]
      if (line != null) {
        for (rowIndex in rows.indices) {
          val mask = 1 shl rowIndex
          if (usedRows and mask == 0 && line in rows[rowIndex].recordedLines) {
            best = maxOf(best, 1 + search(copyIndex + 1, usedRows or mask))
          }
        }
      }
      memo[state] = best
      return best
    }
    return search(0, 0)
  }

  /** The file-order exact matcher that the augmenting-path allocator superseded. */
  private fun greedyExactAffinity(
    rows: List<BaselineNotes.Row>,
    observedLines: List<String>,
  ): Int {
    val usedRows = HashSet<Int>()
    return observedLines.map { it.toIntOrNull() }
        .sortedWith(nullsLast(naturalOrder()))
        .count { line ->
          if (line == null) return@count false
          val match = rows.indices.firstOrNull {
            it !in usedRows && line in rows[it].recordedLines
          }
          match != null && usedRows.add(match)
        }
  }

  private fun exactAffinityInWrittenRows(
    written: List<String>,
    sourceByNote: Map<String?, BaselineNotes.Row>,
  ): Int = written.count { rendered ->
    val output = BaselineNotes.parse(rendered)
    val source = sourceByNote[output.note] ?: return@count false
    val line = output.recordedLines.singleOrNull() ?: return@count false
    line in source.recordedLines
  }

  private fun updateRewrite(
    acceptedRows: List<BaselineNotes.Row>,
    currentLines: Map<String, List<String>>,
    timedOutLines: Map<String, List<Int?>> = emptyMap(),
    killedLines: Map<String, List<Int?>> = emptyMap(),
  ): BaselineEngine.UpdateRewrite {
    val keepPlan = BaselineEngine.keepPlan(
      acceptedRows,
      currentLines,
      timedOutLines,
      killedLines,
    )
    return BaselineEngine.updateRewrite(acceptedRows, currentLines, keepPlan)
  }

  /** A random small population: sibling-heavy keys, partial tags, some insurance. */
  private fun population(rnd: Random): Population {
    fun key() = "${classes.random(rnd)},${methods.random(rnd)},${mutators.random(rnd)},${statuses.random(rnd)}"
    val acceptedRows = List(rnd.nextInt(0, 9)) {
      val note = when (rnd.nextInt(4)) {
        0 -> "# flip insurance (gate=KILLED, solo=SURVIVED)"
        1 -> "# race guard"
        else -> null
      }
      val lines = when (rnd.nextInt(3)) {
        0 -> emptyList()
        else -> buildSet {
          val target = rnd.nextInt(1, 4)
          while (size < target) add(rnd.nextInt(1, 60))
        }.sorted()
      }
      BaselineNotes.Row(key(), note, lines)
    }
    val currentLines = buildMap<String, MutableList<String>> {
      repeat(rnd.nextInt(0, 9)) {
        // bias toward accepted keys so matches, siblings, and flips all occur;
        // an occasional unparsable line exercises the null-line branches
        // (nullsLast copy ordering, the pairing skip, the empty line tag)
        val k = if (acceptedRows.isNotEmpty() && rnd.nextBoolean()) acceptedRows.random(rnd).key else key()
        val recordedAtKey = acceptedRows
            .asSequence()
            .filter { it.key == k }
            .flatMap { it.recordedLines.asSequence() }
            .toList()
        val line = when {
          rnd.nextInt(8) == 0 -> "?"
          recordedAtKey.isNotEmpty() && rnd.nextBoolean() -> recordedAtKey.random(rnd).toString()
          else -> rnd.nextInt(1, 60).toString()
        }
        getOrPut(k) { mutableListOf() }.add(line)
      }
    }
    fun coordLines() = buildMap<String, MutableList<Int?>> {
      repeat(rnd.nextInt(0, 4)) {
        val k = if (acceptedRows.isNotEmpty() && rnd.nextBoolean()) {
          acceptedRows.random(rnd).key.substringBeforeLast(',')
        } else {
          "${classes.random(rnd)},${methods.random(rnd)},${mutators.random(rnd)}"
        }
        getOrPut(k) { mutableListOf() }.add(if (rnd.nextInt(8) == 0) null else rnd.nextInt(1, 60))
      }
    }
    return Population(acceptedRows, currentLines, coordLines(), coordLines())
  }

  @Test
  fun `keep plan invariants hold over random populations`() {
    repeat(1000) { seed ->
      val rnd = Random(seed)
      val p = population(rnd)
      val plan = BaselineEngine.keepPlan(p.acceptedRows, p.currentLines, p.timedOutLines, p.killedLines)
      assertEquals(p.acceptedRows.size, plan.size, "seed $seed: one disposition per row")

      // per key, matched rows hold exactly the surviving budget
      for ((key, indices) in p.acceptedRows.indices.groupBy { p.acceptedRows[it].key }) {
        val matched = indices.count { plan[it] == BaselineEngine.Disposition.MATCHED }
        assertEquals(
          minOf(indices.size, p.currentLines[key].orEmpty().size), matched,
          "seed $seed: matched budget at $key"
        )
      }

      // per coordinate, timeout keeps never exceed the mutants that timed out —
      // the invariant whose presence-check violation shipped in 21.5.x
      for ((coord, indices) in p.acceptedRows.indices.groupBy {
        p.acceptedRows[it].key.substringBeforeLast(',')
      }) {
        val timeoutKeeps = indices.count { plan[it] == BaselineEngine.Disposition.TIMEOUT }
        assertTrue(
          timeoutKeeps <= p.timedOutLines[coord].orEmpty().size,
          "seed $seed: $timeoutKeeps timeout keeps exceed budget at $coord"
        )
      }

      // an insured-key row never spends the timeout budget and is never dropped
      val insuredKeys = p.acceptedRows
        .filter { BaselineNotes.hasFlipInsurance(it.note) }
        .mapTo(HashSet()) { it.key }
      for (index in p.acceptedRows.indices) {
        if (p.acceptedRows[index].key in insuredKeys) {
          assertTrue(
            plan[index] == BaselineEngine.Disposition.MATCHED ||
                plan[index] == BaselineEngine.Disposition.INSURED,
            "seed $seed: insured row ${p.acceptedRows[index].key} got ${plan[index]}"
          )
        }
      }

      // per coordinate and row status, flip keeps never exceed the unmatched
      // different-status counterparts — the b6291af bug class as a law: one
      // counterpart vouches for one row, a presence check vouched for a pile
      val rowsPerKey = p.acceptedRows.groupingBy { it.key }.eachCount()
      val excessByKey = p.currentLines.mapValues { (key, lines) ->
        maxOf(0, lines.size - (rowsPerKey[key] ?: 0))
      }
      for ((coord, indices) in p.acceptedRows.indices.groupBy {
        p.acceptedRows[it].key.substringBeforeLast(',')
      }) {
        for (status in statuses) {
          val flipKeeps = indices.count {
            plan[it] == BaselineEngine.Disposition.FLIP &&
                p.acceptedRows[it].key.substringAfterLast(',') == status
          }
          val counterparts = excessByKey.entries.sumOf { (key, excess) ->
            if (key.substringBeforeLast(',') == coord && key.substringAfterLast(',') != status) excess else 0
          }
          assertTrue(
            flipKeeps <= counterparts,
            "seed $seed: $flipKeeps flip keeps at $coord/$status exceed $counterparts counterparts"
          )
        }
      }

      // prune is idempotent: pruning what prune kept drops nothing more
      val kept = p.acceptedRows.filterIndexed { i, _ -> plan[i] != BaselineEngine.Disposition.DROP }
      val replan = BaselineEngine.keepPlan(kept, p.currentLines, p.timedOutLines, p.killedLines)
      assertTrue(
        replan.none { it == BaselineEngine.Disposition.DROP },
        "seed $seed: prune of pruned output dropped again — refresh ping-pong"
      )
    }
  }

  @Test
  fun `assignment reaches maximum exact affinity over wider random graphs`() {
    val key = "com.example.Codec,encode,MathMutator,SURVIVED"
    var displacementCases = 0
    var fallbackSelectionCases = 0
    repeat(1000) { seed ->
      val rnd = Random(seed)
      val rows = List(rnd.nextInt(0, 7)) { rowIndex ->
        val tags = buildSet {
          val target = rnd.nextInt(0, 4)
          while (size < target) add(rnd.nextInt(1, 8))
        }.sorted()
        BaselineNotes.Row(key, "# oracle row $rowIndex", tags)
      }
      val observed = List(rnd.nextInt(0, 8)) {
        if (rnd.nextInt(9) == 0) "?" else rnd.nextInt(1, 8).toString()
      }
      val expected = maximumExactAffinity(rows, observed)
      if (expected > greedyExactAffinity(rows, observed)) displacementCases++
      val sourceByNote = rows.associateBy { it.note }

      val update = updateRewrite(rows, mapOf(key to observed))
      assertEquals(
        expected,
        exactAffinityInWrittenRows(update.written, sourceByNote),
        "seed $seed: update did not reach the true maximum exact affinity",
      )
      val permuted = observed.shuffled(Random(seed + 10_000))
      assertEquals(
        update.written,
        updateRewrite(rows, mapOf(key to permuted)).written,
        "seed $seed: report order changed a deterministic assignment",
      )

      // Recover the exact-match rows from update's observable note-to-line
      // assignments, then independently apply the documented fallback: among
      // rows left by a maximum exact match, live anchors precede bare/stale rows
      // and file order breaks ties. A tag-counter subtraction is not a valid
      // oracle here because one production row can carry several historical tags.
      val assignments = update.written.mapNotNull { rendered ->
        val output = BaselineNotes.parse(rendered)
        val rowIndex = output.note
            ?.removePrefix("# oracle row ")
            ?.toIntOrNull()
            ?: return@mapNotNull null
        rowIndex to output.recordedLines.singleOrNull()
      }
      val exactRows = assignments.mapNotNullTo(HashSet()) { (rowIndex, line) ->
        rowIndex.takeIf { line != null && line in rows[rowIndex].recordedLines }
      }
      val liveLines = observed.mapNotNullTo(HashSet()) { it.toIntOrNull() }
      val unmatchedRows = rows.indices.filter { it !in exactRows }
      val fallbackSlots = minOf(rows.size, observed.size) - expected
      val expectedFallbackRows = unmatchedRows
          .sortedBy { rowIndex ->
            if (rows[rowIndex].recordedLines.any { it in liveLines }) 0 else 1
          }
          .take(fallbackSlots)
          .toSet()
      val fileOrderFallbackRows = unmatchedRows.take(fallbackSlots).toSet()
      if (expectedFallbackRows != fileOrderFallbackRows) fallbackSelectionCases++
      val expectedMatchedRows = exactRows + expectedFallbackRows
      val updateMatchedRows = rows.indices.filterTo(HashSet()) { it !in update.droppedIdx }
      assertEquals(
        expectedMatchedRows,
        updateMatchedRows,
        "seed $seed: update fallback did not retain live anchors before bare/stale rows",
      )

      val plan = BaselineEngine.keepPlan(rows, mapOf(key to observed), emptyMap(), emptyMap())
      val planMatchedRows = rows.indices.filterTo(HashSet()) {
        plan[it] == BaselineEngine.Disposition.MATCHED
      }
      assertEquals(
        expectedMatchedRows,
        planMatchedRows,
        "seed $seed: keep fallback did not retain live anchors before bare/stale rows",
      )

      // A green prune has at most one accepted row per current copy; in that
      // shape its note-to-line assignment must reach the same optimum as update.
      if (observed.size <= rows.size) {
        val prune = BaselineEngine.pruneRewrite(rows, plan, mapOf(key to observed))
        assertEquals(
          expected,
          exactAffinityInWrittenRows(prune.written, sourceByNote),
          "seed $seed: prune and update disagreed on maximum exact affinity",
        )
      }
    }
    assertTrue(
      displacementCases > 0,
      "seeded population never exercised a graph where augmenting displacement beats greedy matching",
    )
    assertTrue(
      fallbackSelectionCases > 0,
      "seeded population never distinguished live-priority fallback from raw file order",
    )
  }

  @Test
  fun `flip insurance is a literal persistent marker and annotation preserves row evidence`() {
    val key = "com.example.Codec,encode,MathMutator,SURVIVED"
    val plain = BaselineNotes.Row(key, "# handled-flag family", listOf(12, 40))

    assertEquals(false, BaselineNotes.hasFlipInsurance(plain.note))
    assertEquals(false, BaselineNotes.hasFlipInsurance("# earlier insurance"))
    assertEquals(
      listOf(BaselineEngine.Disposition.DROP),
      BaselineEngine.keepPlan(listOf(plain), emptyMap(), emptyMap(), emptyMap()),
      "a family note or the word 'insurance' alone must not create persistent flip evidence",
    )

    val annotated = plain.copy(
      note = BaselineNotes.withFlipInsurance(
        plain.note,
        "gate=SURVIVED, solo=KILLED",
      ),
    )
    assertEquals(
      "# handled-flag family (flip insurance: gate=SURVIVED, solo=KILLED)",
      annotated.note,
    )
    assertTrue(BaselineNotes.hasFlipInsurance(annotated.note))
    assertEquals(
      "$key # handled-flag family (flip insurance: gate=SURVIVED, solo=KILLED) # lines 12, 40",
      BaselineNotes.render(annotated),
      "annotating insurance must preserve the family label and every recorded line",
    )
    assertEquals(
      listOf(BaselineEngine.Disposition.INSURED),
      BaselineEngine.keepPlan(listOf(annotated), emptyMap(), emptyMap(), emptyMap()),
      "the same literal marker mode compare writes must be the one prune keeps",
    )
    assertEquals(
      annotated.note,
      BaselineNotes.withFlipInsurance(annotated.note, "later observation"),
      "annotating an already-insured row must not stack or replace its evidence",
    )
  }

  @Test
  fun `retirement prose never changes matching authority or prune classification`() {
    val retiredKey = "com.example.Codec,decode,NullReturnValsMutator,NO_COVERAGE"
    val refactorKey = "com.example.Codec,verify,RemoveConditionalMutator_EQUAL_IF,SURVIVED"
    val rows = listOf(
      BaselineNotes.parse("$retiredKey # retired"),
      BaselineNotes.parse("$refactorKey # rebase refactor"),
    )
    val observed = mapOf(retiredKey to listOf("10"), refactorKey to listOf("20"))

    assertEquals(
      listOf(BaselineEngine.Disposition.MATCHED, BaselineEngine.Disposition.MATCHED),
      BaselineEngine.keepPlan(rows, observed, emptyMap(), emptyMap()),
      "comment prose must not disable an accepted row that still matches a mutant",
    )

    val unmatched = BaselineEngine.keepPlan(rows, emptyMap(), emptyMap(), emptyMap())
    assertEquals(
      listOf(BaselineEngine.Disposition.DROP, BaselineEngine.Disposition.DROP),
      unmatched,
      "comment prose must not create a hidden retired state outside guarded Prune",
    )
    assertEquals(
      emptyList<String>(),
      BaselineEngine.pruneRewrite(rows, unmatched, emptyMap()).written,
      "unmatched rows with retirement prose remain ordinary reviewed Prune candidates",
    )
  }

  @Test
  fun `prune refreshes matched lines by affinity and preserves unmatched evidence`() {
    val key = "com.example.Codec,encode,MathMutator,SURVIVED"
    val accepted = listOf(
      BaselineNotes.Row(key, "# moved", listOf(10)),
      BaselineNotes.Row(key, "# stationary sibling", listOf(20)),
      BaselineNotes.Row(
        "com.example.Codec,decode,MathMutator,SURVIVED",
        "# flip insurance (gate=KILLED, solo=SURVIVED)",
        listOf(50),
      ),
      BaselineNotes.Row("com.example.Codec,dead,MathMutator,SURVIVED", "# killed", listOf(70)),
    )

    val plan = BaselineEngine.keepPlan(
      accepted,
      mapOf(key to listOf("20", "12")),
      emptyMap(),
      emptyMap(),
    )
    assertEquals(
      listOf(
        BaselineEngine.Disposition.MATCHED,
        BaselineEngine.Disposition.MATCHED,
        BaselineEngine.Disposition.INSURED,
        BaselineEngine.Disposition.DROP,
      ),
      plan,
    )

    val rewrite = BaselineEngine.pruneRewrite(
      accepted,
      plan,
      mapOf(key to listOf("20", "12")),
    )

    assertEquals(
      listOf(
        "$key # moved # line 12",
        "$key # stationary sibling # line 20",
        "com.example.Codec,decode,MathMutator,SURVIVED # flip insurance (gate=KILLED, solo=SURVIVED) # line 50",
      ),
      rewrite.written,
    )
    assertEquals(1, rewrite.refreshedLineTags)
  }

  @Test
  fun `retag refreshes matched lines and preserves every unmatched row in place`() {
    val key = "com.example.Codec,encode,MathMutator,SURVIVED"
    val absent = "com.example.Codec,decode,MathMutator,SURVIVED"
    val accepted = listOf(
      BaselineNotes.Row(key, "# moved", listOf(10)),
      BaselineNotes.Row(key, "# stationary sibling", listOf(20)),
      BaselineNotes.Row(absent, "# licensed subsumption", listOf(50)),
    )

    val rewrite = BaselineEngine.retagRewrite(
      accepted,
      mapOf(key to listOf("20", "12")),
    )

    assertEquals(
      listOf(
        "$key # moved # line 12",
        "$key # stationary sibling # line 20",
        "$absent # licensed subsumption # line 50",
      ),
      rewrite.written,
    )
    assertEquals(1, rewrite.refreshedLineTags)
    assertEquals(listOf(0, 1, 2), rewrite.sourceRowIndices)
  }

  @Test
  fun `retag kernel refuses a new key or an extra sibling`() {
    val key = "com.example.Codec,encode,MathMutator,SURVIVED"
    val accepted = listOf(BaselineNotes.Row(key, "# family", listOf(10)))

    val sibling = assertThrows(IllegalArgumentException::class.java) {
      BaselineEngine.retagRewrite(accepted, mapOf(key to listOf("10", "20")))
    }
    assertTrue(sibling.message.orEmpty().contains("$key x1"), sibling.message.orEmpty())

    val newKey = "com.example.Codec,decode,MathMutator,SURVIVED"
    val unknown = assertThrows(IllegalArgumentException::class.java) {
      BaselineEngine.retagRewrite(accepted, mapOf(newKey to listOf("30")))
    }
    assertTrue(unknown.message.orEmpty().contains("$newKey x1"), unknown.message.orEmpty())
  }

  @Test
  fun `duplicate line affinity drops the dead copy without laundering a live sibling`() {
    val key = "systems.comodal.jsoniter.DoubleParser,computeFloat,ConditionalsBoundaryMutator,SURVIVED"
    val accepted = listOf(273, 275, 283, 308, 319, 319, 320).mapIndexed { index, line ->
      BaselineNotes.Row(key, "# sibling ${index + 1}", listOf(line))
    }
    val currentLines = mapOf(key to listOf("273", "275", "283", "308", "319", "320"))

    val plan = BaselineEngine.keepPlan(accepted, currentLines, emptyMap(), emptyMap())

    assertEquals(
      listOf(
        BaselineEngine.Disposition.MATCHED,
        BaselineEngine.Disposition.MATCHED,
        BaselineEngine.Disposition.MATCHED,
        BaselineEngine.Disposition.MATCHED,
        BaselineEngine.Disposition.MATCHED,
        BaselineEngine.Disposition.DROP,
        BaselineEngine.Disposition.MATCHED,
      ),
      plan,
      "the second # line 319 row, not the live # line 320 row, must consume the missing copy",
    )

    val rewrite = BaselineEngine.pruneRewrite(accepted, plan, currentLines)
    assertEquals(
      accepted.filterIndexed { index, _ -> index != 5 }.map(BaselineNotes::render),
      rewrite.written,
    )
    assertEquals(0, rewrite.refreshedLineTags, "prune must not retag the surviving 319 row as 320")
  }

  @Test
  fun `moved anchor fallback drops a stale tag before a duplicate live anchor`() {
    val key = "com.example.Codec,encode,MathMutator,SURVIVED"
    val accepted = listOf(
      BaselineNotes.Row(key, "# stale anchor", listOf(99)),
      BaselineNotes.Row(key, "# first live sibling", listOf(10)),
      BaselineNotes.Row(key, "# duplicate live sibling", listOf(10)),
    )
    val currentLines = mapOf(key to listOf("10", "20"))

    val plan = BaselineEngine.keepPlan(accepted, currentLines, emptyMap(), emptyMap())

    assertEquals(
      listOf(
        BaselineEngine.Disposition.DROP,
        BaselineEngine.Disposition.MATCHED,
        BaselineEngine.Disposition.MATCHED,
      ),
      plan,
      "the # line 99 row, not a still-live # line 10 sibling, is the deterministic absent-sibling preference",
    )
    assertEquals(
      listOf(
        "$key # first live sibling # line 10",
        "$key # duplicate live sibling # line 20",
      ),
      BaselineEngine.pruneRewrite(accepted, plan, currentLines).written,
    )
    val update = updateRewrite(accepted, currentLines)
    assertEquals(listOf(0), update.droppedIdx)
    assertEquals(
      listOf(
        "$key # first live sibling # line 10",
        "$key # duplicate live sibling # line 20",
      ),
      update.written,
      "update must make the same note-to-line choice as keep/prune",
    )
  }

  @Test
  fun `moved anchor fallback drops a bare row before duplicate live anchors`() {
    val key = "com.example.Codec,encode,MathMutator,SURVIVED"
    val accepted = listOf(
      BaselineNotes.Row(key, "# bare oldest", emptyList()),
      BaselineNotes.Row(key, "# first live sibling", listOf(10)),
      BaselineNotes.Row(key, "# duplicate live sibling", listOf(10)),
      BaselineNotes.Row(key, "# second duplicate live sibling", listOf(10)),
    )
    val currentLines = mapOf(key to listOf("10", "20", "30"))

    val plan = BaselineEngine.keepPlan(accepted, currentLines, emptyMap(), emptyMap())
    assertEquals(
      listOf(
        BaselineEngine.Disposition.DROP,
        BaselineEngine.Disposition.MATCHED,
        BaselineEngine.Disposition.MATCHED,
        BaselineEngine.Disposition.MATCHED,
      ),
      plan,
      "a bare row must not consume a moved copy while a duplicate live anchor remains",
    )
    val expected = listOf(
      "$key # first live sibling # line 10",
      "$key # duplicate live sibling # line 20",
      "$key # second duplicate live sibling # line 30",
    )
    assertEquals(expected, BaselineEngine.pruneRewrite(accepted, plan, currentLines).written)

    val update = updateRewrite(accepted, currentLines)
    assertEquals(listOf(0), update.droppedIdx)
    assertEquals(expected, update.written)
  }

  @Test
  fun `maximum affinity does not let a multi-line row steal a narrow sibling anchor`() {
    val key = "com.example.Codec,encode,MathMutator,SURVIVED"
    val accepted = listOf(
      BaselineNotes.Row(key, "# broad history", listOf(10, 20)),
      BaselineNotes.Row(key, "# narrow history", listOf(10)),
    )
    val currentLines = mapOf(key to listOf("10", "20"))
    val plan = BaselineEngine.keepPlan(accepted, currentLines, emptyMap(), emptyMap())

    assertEquals(
      listOf(BaselineEngine.Disposition.MATCHED, BaselineEngine.Disposition.MATCHED),
      plan,
    )
    assertEquals(
      listOf(
        "$key # broad history # line 20",
        "$key # narrow history # line 10",
      ),
      BaselineEngine.pruneRewrite(accepted, plan, currentLines).written,
    )
    assertEquals(
      listOf(
        "$key # broad history # line 20",
        "$key # narrow history # line 10",
      ),
      updateRewrite(accepted, currentLines).written,
    )
  }

  @Test
  fun `drift comparison of a run against itself is silent`() {
    repeat(500) { seed ->
      val rnd = Random(seed)
      val tally = buildMap<String, Map<String, Int>> {
        repeat(rnd.nextInt(0, 6)) {
          put(
            "${classes.random(rnd)},${methods.random(rnd)},${mutators.random(rnd)}",
            listOf("KILLED", "SURVIVED", "NO_COVERAGE", "TIMED_OUT")
              .filter { rnd.nextBoolean() }
              .associateWith { rnd.nextInt(1, 4) }
          )
        }
      }
      val drift = BaselineEngine.driftCompare(tally, tally)
      assertTrue(
        drift.fromSurvived.isEmpty() && drift.fromNoCoverage.isEmpty() &&
            drift.newlyTimedOut == 0 && drift.firstObserved == 0 && drift.resolved == 0,
        "seed $seed: an unchanged population reported drift — the flip that fires forever"
      )
    }
  }

  @Test
  fun `a dangerous flip is never read as previously detected`() {
    repeat(500) { seed ->
      val rnd = Random(seed)
      val key = "${classes.random(rnd)},${methods.random(rnd)},${mutators.random(rnd)}"
      val origin = statuses.random(rnd)
      val previous = mapOf(key to mapOf(origin to 1, "KILLED" to rnd.nextInt(0, 3)))
      val current = mapOf(key to mapOf("TIMED_OUT" to 1, "KILLED" to (previous.getValue(key)["KILLED"] ?: 0)))
      val drift = BaselineEngine.driftCompare(previous, current)
      assertTrue(
        (if (origin == "SURVIVED") drift.fromSurvived else drift.fromNoCoverage).contains(key),
        "seed $seed: $origin -> TIMED_OUT not flagged dangerous"
      )
      assertEquals(
        mapOf(key to 1),
        drift.positiveTimedOutByCoordinate,
        "seed $seed: dangerous positive delta lost its coordinate/multiplicity",
      )
      assertEquals(0, drift.newlyTimedOut, "seed $seed: dangerous flip counted as previously detected")
    }
  }

  @Test
  fun `timeout drift retains each changed coordinate without promoting killed flips`() {
    val benign = "com.example.Codec,encode,MathMutator"
    val first = "com.example.Codec,decode,IncrementsMutator"
    val resolved = "com.example.Codec,close,VoidMethodCallMutator"
    val previous = linkedMapOf(
      benign to mapOf("KILLED" to 2),
      first to mapOf("SURVIVED" to 1),
      resolved to mapOf("TIMED_OUT" to 2),
    )
    val current = linkedMapOf(
      benign to mapOf("KILLED" to 1, "TIMED_OUT" to 1),
      // The existing survivor remains: this is a new sibling, not a
      // SURVIVED -> TIMED_OUT transition at the aggregate coordinate.
      first to mapOf("SURVIVED" to 1, "TIMED_OUT" to 2),
      resolved to mapOf("TIMED_OUT" to 1, "KILLED" to 1),
    )

    val drift = BaselineEngine.driftCompare(previous, current)

    assertTrue(drift.fromSurvived.isEmpty())
    assertTrue(drift.fromNoCoverage.isEmpty())
    assertEquals(mapOf(benign to 1, first to 2), drift.positiveTimedOutByCoordinate)
    assertEquals(mapOf(benign to 1), drift.newlyTimedOutByCoordinate)
    assertEquals(mapOf(first to 2), drift.firstObservedByCoordinate)
    assertEquals(mapOf(resolved to 1), drift.resolvedByCoordinate)
    assertEquals(1, drift.newlyTimedOut)
    assertEquals(2, drift.firstObserved)
    assertEquals(1, drift.resolved)
  }

  @Test
  fun `update preserves timeout budgets and literal insurance but drops unprotected rows`() {
    val timeoutKey = "com.example.Codec,encode,MathMutator,SURVIVED"
    val timeoutCoord = timeoutKey.substringBeforeLast(',')
    val insuredKey = "com.example.Codec,decode,MathMutator,SURVIVED"
    val deadKey = "com.example.Codec,gone,MathMutator,SURVIVED"
    val accepted = listOf(
      BaselineNotes.Row(timeoutKey, "# timeout family", listOf(12)),
      BaselineNotes.Row(timeoutKey, "# killed sibling", listOf(20)),
      BaselineNotes.Row(
        insuredKey,
        "# handled flag (flip insurance: gate=KILLED, solo=SURVIVED)",
        listOf(30),
      ),
      BaselineNotes.Row(deadKey, "# removed population", listOf(40)),
    )
    val timedOut = mapOf(timeoutCoord to listOf<Int?>(12))
    val killed = mapOf(
      timeoutCoord to listOf<Int?>(20),
      insuredKey.substringBeforeLast(',') to listOf<Int?>(30),
      deadKey.substringBeforeLast(',') to listOf<Int?>(40),
    )
    val plan = BaselineEngine.keepPlan(accepted, emptyMap(), timedOut, killed)

    assertEquals(
      listOf(
        BaselineEngine.Disposition.TIMEOUT,
        BaselineEngine.Disposition.DROP,
        BaselineEngine.Disposition.INSURED,
        BaselineEngine.Disposition.DROP,
      ),
      plan,
    )

    val rewrite = BaselineEngine.updateRewrite(accepted, emptyMap(), plan)
    assertEquals(0, rewrite.copies)
    assertEquals(setOf(0), rewrite.preservedTimeoutIdx)
    assertEquals(setOf(2), rewrite.preservedInsuredIdx)
    assertEquals(listOf(1, 3), rewrite.droppedIdx)
    assertEquals(listOf(0, 2), rewrite.sourceRowIndices)
    assertEquals(
      listOf(
        BaselineNotes.render(accepted[0]),
        BaselineNotes.render(accepted[2]),
      ),
      rewrite.written,
      "zero unkilled copies must not erase timeout or insurance evidence",
    )
  }

  @Test
  fun `update preserves every sibling at a flip-insured key`() {
    // Insurance is key-level: on a duplicate-mutant family, the literal marker may
    // live on one accepted row while a different sibling is the copy that reads
    // detected in this run. Preserving only the marked row would pass the simple
    // one-row insurance fixture while deleting real fleet evidence beside it.
    val key = "com.example.Codec,encode,MathMutator,SURVIVED"
    val accepted = listOf(
      BaselineNotes.Row(
        key,
        "# handled flag (flip insurance: gate=KILLED, solo=SURVIVED)",
        listOf(10),
      ),
      BaselineNotes.Row(key, "# sibling in the same measured family", listOf(20)),
    )
    val plan = BaselineEngine.keepPlan(
      accepted,
      emptyMap(),
      emptyMap(),
      mapOf(key.substringBeforeLast(',') to listOf<Int?>(10, 20)),
    )

    assertEquals(
      listOf(BaselineEngine.Disposition.INSURED, BaselineEngine.Disposition.INSURED),
      plan,
    )
    val rewrite = BaselineEngine.updateRewrite(accepted, emptyMap(), plan)

    assertEquals(setOf(0, 1), rewrite.preservedInsuredIdx)
    assertTrue(rewrite.droppedIdx.isEmpty())
    assertEquals(accepted.map(BaselineNotes::render), rewrite.written)
  }

  @Test
  fun `update resolves a gated status flip instead of turning it into insurance`() {
    val oldKey = "com.example.Codec,encode,MathMutator,NO_COVERAGE"
    val currentKey = "com.example.Codec,encode,MathMutator,SURVIVED"
    val accepted = listOf(
      BaselineNotes.Row(oldKey, "# structural absence", listOf(12)),
    )
    val currentLines = mapOf(currentKey to listOf("12"))
    val plan = BaselineEngine.keepPlan(accepted, currentLines, emptyMap(), emptyMap())

    assertEquals(listOf(BaselineEngine.Disposition.FLIP), plan)
    val rewrite = BaselineEngine.updateRewrite(accepted, currentLines, plan)

    assertEquals(
      listOf("$currentKey # structural absence (carried across NO_COVERAGE -> SURVIVED) # line 12"),
      rewrite.written,
    )
    assertEquals(listOf(0), rewrite.droppedIdx)
    assertEquals(setOf(0), rewrite.carriedIdx)
    assertTrue(rewrite.preservedTimeoutIdx.isEmpty())
    assertTrue(rewrite.preservedInsuredIdx.isEmpty())
  }

  @Test
  fun `status flip note carry uses maximum line affinity across siblings`() {
    val oldKey = "com.example.Codec,encode,MathMutator,NO_COVERAGE"
    val currentKey = "com.example.Codec,encode,MathMutator,SURVIVED"
    val accepted = listOf(
      BaselineNotes.Row(oldKey, "# broad old sibling", listOf(10, 20)),
      BaselineNotes.Row(oldKey, "# narrow old sibling", listOf(10)),
    )
    val currentLines = mapOf(currentKey to listOf("10", "20"))
    val plan = BaselineEngine.keepPlan(accepted, currentLines, emptyMap(), emptyMap())

    assertEquals(List(2) { BaselineEngine.Disposition.FLIP }, plan)
    val rewrite = BaselineEngine.updateRewrite(accepted, currentLines, plan)

    assertEquals(listOf(0, 1), rewrite.sourceRowIndices)
    assertEquals(
      listOf(
        "$currentKey # broad old sibling (carried across NO_COVERAGE -> SURVIVED) # line 20",
        "$currentKey # narrow old sibling (carried across NO_COVERAGE -> SURVIVED) # line 10",
      ),
      rewrite.written,
      "the broad row must move to line 20 so the narrow row keeps its sole line-10 affinity",
    )
  }

  @Test
  fun `update rewrites are exact and idempotent`() {
    repeat(1000) { seed ->
      val rnd = Random(seed)
      val p = population(rnd)
      val plan = BaselineEngine.keepPlan(
        p.acceptedRows,
        p.currentLines,
        p.timedOutLines,
        p.killedLines,
      )
      val rewrite = BaselineEngine.updateRewrite(p.acceptedRows, p.currentLines, plan)

      // Every unkilled copy gets exactly one row, and every timeout/insurance keep
      // gets one additional persistent row. No other accepted row survives.
      val protectedIndices = p.acceptedRows.indices.filter {
        plan[it] == BaselineEngine.Disposition.TIMEOUT ||
            plan[it] == BaselineEngine.Disposition.INSURED
      }
      val expectedCopies = p.currentLines.entries.sumOf { it.value.size }
      assertEquals(
        expectedCopies + protectedIndices.size,
        rewrite.written.size,
        "seed $seed: current copies plus protected evidence",
      )
      assertEquals(
        protectedIndices.filterTo(HashSet()) { plan[it] == BaselineEngine.Disposition.TIMEOUT },
        rewrite.preservedTimeoutIdx,
        "seed $seed: wrong timeout preservation set",
      )
      assertEquals(
        protectedIndices.filterTo(HashSet()) { plan[it] == BaselineEngine.Disposition.INSURED },
        rewrite.preservedInsuredIdx,
        "seed $seed: wrong insurance preservation set",
      )
      val expectedKeys = p.currentLines.entries.flatMap { (k, v) -> List(v.size) { k } } +
          protectedIndices.map { p.acceptedRows[it].key }
      assertEquals(
        expectedKeys.sorted(),
        rewrite.written.map { BaselineNotes.parse(it).key }.sorted(),
        "seed $seed: written keys must be current copies plus protected evidence",
      )

      // a rewrite of the rewrite against the same run changes nothing: the exact
      // refresh ping-pong class (fix-needed-a-fix c8464b5) as a law, not a case
      val rewrittenRows = rewrite.written.map { BaselineNotes.parse(it) }
      val againPlan = BaselineEngine.keepPlan(
        rewrittenRows,
        p.currentLines,
        p.timedOutLines,
        p.killedLines,
      )
      val again = BaselineEngine.updateRewrite(rewrittenRows, p.currentLines, againPlan)
      assertEquals(rewrite.written, again.written, "seed $seed: update not a fixed point")
      assertTrue(again.droppedIdx.isEmpty(), "seed $seed: second update dropped rows")
    }
  }

  @Test
  fun `union adds the missing multiset exactly once`() {
    repeat(1000) { seed ->
      val rnd = Random(seed)
      val p = population(rnd)
      val current = p.currentLines.entries.flatMap { (k, v) -> List(v.size) { k } }
      val merge = BaselineEngine.unionMerge(p.acceptedRows, current, p.currentLines)
      if (merge.added.isEmpty()) return@repeat

      // per key, the merged count is the larger of the two — never a dedup shrink
      // (casebook: the union write that deduped siblings)
      val mergedCounts = merge.merged.map { BaselineNotes.parse(it).key }.groupingBy { it }.eachCount()
      val acceptedCounts = p.acceptedRows.groupingBy { it.key }.eachCount()
      val currentCounts = current.groupingBy { it }.eachCount()
      for (key in acceptedCounts.keys + currentCounts.keys) {
        assertEquals(
          maxOf(acceptedCounts[key] ?: 0, currentCounts[key] ?: 0), mergedCounts[key] ?: 0,
          "seed $seed: merged count at $key"
        )
      }
      // existing rows survive verbatim
      p.acceptedRows.forEach { row ->
        assertTrue(
          merge.merged.contains(BaselineNotes.render(row)),
          "seed $seed: existing row lost by union — ${BaselineNotes.render(row)}"
        )
      }
      assertEquals(
        p.acceptedRows.map(BaselineNotes::render),
        merge.merged.take(p.acceptedRows.size),
        "seed $seed: union moved an existing row out of its document slot",
      )
      assertTrue(
        merge.merged.drop(p.acceptedRows.size)
            .map(BaselineNotes::parse)
            .all { it.note == "# untriaged" },
        "seed $seed: union accepted a new row without an explicit triage marker",
      )
      // idempotence: union of the union adds nothing
      val again = BaselineEngine.unionMerge(merge.merged.map { BaselineNotes.parse(it) }, current, p.currentLines)
      assertTrue(again.added.isEmpty(), "seed $seed: union not idempotent")
    }
  }

  @Test
  fun `rebase preserves old evidence and marks every new toolchain row untriaged`() {
    val retained = "com.example.Codec,oldPath,MathMutator,SURVIVED"
    val current = "com.example.Codec,newPath,MathMutator,SURVIVED"
    val accepted = listOf(
      BaselineNotes.Row(retained, "# licensed-only family", listOf(10)),
    )

    val rebased = BaselineEngine.rebaseMerge(
      accepted,
      listOf(current, current),
      mapOf(current to listOf("20", "30")),
    )

    assertEquals(listOf(current, current), rebased.added)
    assertEquals(
      listOf(
        "$retained # licensed-only family # line 10",
        "$current # untriaged # line 20",
        "$current # untriaged # line 30",
      ),
      rebased.merged,
    )
    val again = BaselineEngine.rebaseMerge(
      rebased.merged.map(BaselineNotes::parse),
      listOf(current, current),
      mapOf(current to listOf("20", "30")),
    )
    assertTrue(again.added.isEmpty())
    assertTrue(again.merged.isEmpty(), "a no-op rebase should leave the caller's document byte-identical")
  }

  @Test
  fun `union leaves the genuinely new line after maximum affinity consumption`() {
    val key = "com.example.Codec,encode,MathMutator,SURVIVED"
    val accepted = listOf(
      BaselineNotes.Row(key, "# sibling A", listOf(20, 30)),
      BaselineNotes.Row(key, "# sibling B", listOf(20, 40)),
      BaselineNotes.Row(key, "# sibling C", listOf(20, 40)),
    )

    val merge = BaselineEngine.unionMerge(
      accepted,
      List(4) { key },
      mapOf(key to listOf("10", "20", "30", "40")),
    )

    assertEquals(listOf(key), merge.added)
    assertEquals(
      accepted.map(BaselineNotes::render) + "$key # untriaged # line 10",
      merge.merged,
      "20, 30, and 40 all have an exact existing sibling, so only 10 is new",
    )
  }

  @Test
  fun `churn accounts for every fresh row exactly once`() {
    repeat(1000) { seed ->
      val rnd = Random(seed)
      val p = population(rnd)
      val current = p.currentLines.entries.flatMap { (k, v) -> List(v.size) { k } }
      val accepted = p.acceptedRows.map { it.key }
      val fresh = BaselineEngine.multisetDiff(current, accepted)
      val stale = BaselineEngine.multisetDiff(accepted, current)
      val churn = BaselineEngine.classifyChurn(fresh, stale, accepted.toSet())
      // the sum identity is definitional; the REAL partition invariants are that
      // no fresh row is double-counted (pairs + siblings never exceed fresh) and
      // nothing goes negative — a dropped early-exit in the pairing loop counts
      // a row as both newly covered and surfaced, and only these two catch it
      assertTrue(churn.unexplained >= 0, "seed $seed: negative unexplained — a fresh row counted twice")
      assertTrue(
        churn.newlyCoveredPairs.size + churn.surfacedSiblings.size <= fresh.size,
        "seed $seed: partition overlap — pairs + siblings exceed the fresh rows"
      )
      churn.newlyCoveredPairs.forEach { (freshRow, staleRow) ->
        assertEquals(
          freshRow.substringBeforeLast(','), staleRow.substringBeforeLast(','),
          "seed $seed: newly-covered pair crosses coordinates"
        )
      }
      // a stale counterpart is consumed at most once
      val staleCounts = stale.groupingBy { it }.eachCount()
      churn.newlyCoveredPairs.map { it.second }.groupingBy { it }.eachCount().forEach { (row, used) ->
        assertTrue(used <= (staleCounts[row] ?: 0), "seed $seed: counterpart $row over-consumed")
      }
    }
  }
}
