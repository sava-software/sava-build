package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
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

  /** A random small population: sibling-heavy keys, partial tags, some insurance. */
  private fun population(rnd: Random): Population {
    fun key() = "${classes.random(rnd)},${methods.random(rnd)},${mutators.random(rnd)},${statuses.random(rnd)}"
    val acceptedRows = List(rnd.nextInt(0, 9)) {
      val note = when (rnd.nextInt(4)) {
        0 -> "# flip insurance (gate=KILLED, solo=SURVIVED)"
        1 -> "# race guard"
        else -> null
      }
      val lines = if (rnd.nextBoolean()) listOf(rnd.nextInt(1, 60)) else emptyList()
      BaselineNotes.Row(key(), note, lines)
    }
    val currentLines = buildMap<String, MutableList<String>> {
      repeat(rnd.nextInt(0, 9)) {
        // bias toward accepted keys so matches, siblings, and flips all occur;
        // an occasional unparsable line exercises the null-line branches
        // (nullsLast copy ordering, the pairing skip, the empty line tag)
        val k = if (acceptedRows.isNotEmpty() && rnd.nextBoolean()) acceptedRows.random(rnd).key else key()
        getOrPut(k) { mutableListOf() }
            .add(if (rnd.nextInt(6) == 0) "?" else rnd.nextInt(1, 60).toString())
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
        .filter { it.note?.contains("flip insurance") == true }
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
        "$key # narrow history # line 10",
        "$key # broad history # line 20",
      ),
      BaselineEngine.updateRewrite(accepted, currentLines).written,
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
      assertEquals(0, drift.newlyTimedOut, "seed $seed: dangerous flip counted as previously detected")
    }
  }

  @Test
  fun `update rewrites are exact and idempotent`() {
    repeat(1000) { seed ->
      val rnd = Random(seed)
      val p = population(rnd)
      val rewrite = BaselineEngine.updateRewrite(p.acceptedRows, p.currentLines)

      // every unkilled copy gets exactly one row
      val expected = p.currentLines.entries.sumOf { it.value.size }
      assertEquals(expected, rewrite.written.size, "seed $seed: one row per unkilled mutant")
      assertEquals(
        p.currentLines.entries.flatMap { (k, v) -> List(v.size) { k } }.sorted(),
        rewrite.written.map { BaselineNotes.parse(it).key }.sorted(),
        "seed $seed: written keys must be the report's unkilled multiset"
      )

      // a rewrite of the rewrite against the same run changes nothing: the exact
      // refresh ping-pong class (fix-needed-a-fix c8464b5) as a law, not a case
      val again = BaselineEngine.updateRewrite(rewrite.written.map { BaselineNotes.parse(it) }, p.currentLines)
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
      // idempotence: union of the union adds nothing
      val again = BaselineEngine.unionMerge(merge.merged.map { BaselineNotes.parse(it) }, current, p.currentLines)
      assertTrue(again.added.isEmpty(), "seed $seed: union not idempotent")
    }
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
      accepted.map(BaselineNotes::render) + "$key # line 10",
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
