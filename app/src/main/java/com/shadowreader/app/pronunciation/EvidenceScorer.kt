package com.shadowreader.app.pronunciation

import kotlin.math.*

/** Experimental acoustic evidence, independently ported from the desktop reference. */
internal class EvidenceScorer(private val logp: Array<DoubleArray>, private val targets: List<IntArray>,
    private val blank: Int, private val phoneIds: IntArray, private val checkCancelled: () -> Unit = {}) {
    private val tCount = logp.size
    private val sCount = targets.size * 2 + 1
    private val a = Array(tCount) { DoubleArray(sCount) { Double.NEGATIVE_INFINITY } }
    private val h = Array(tCount) { DoubleArray(sCount) { Double.NEGATIVE_INFINITY } }
    private val back = Array(tCount) { ByteArray(sCount) }
    private val skips = BooleanArray(sCount)
    data class Path(val score: Double, val start: Int, val end: Int, val ambiguous: Boolean)
    data class Phone(val status: PronunciationStatus, val confidence: Double, val reason: String,
        val gop: Double?, val frameMargin: Double?, val pathMargin: Double?, val competitor: Int?,
        val startFrame: Int?, val endFrame: Int?)
    private fun emission(t: Int, s: Int): Double = if (s % 2 == 0) logp[t][blank]
        else targets[(s-1)/2].maxOf { logp[t][it] }
    private fun disjoint(x: IntArray, y: IntArray) = x.none { it in y }
    init {
        require(tCount > 0 && targets.isNotEmpty() && targets.size <= 400)
        require(logp.all { row -> row.isNotEmpty() && row.all { it.isFinite() } })
        require(targets.all { it.isNotEmpty() && it.all { p -> p != blank && p in logp[0].indices } })
        for (i in 1 until targets.size) skips[2*i+1] = disjoint(targets[i],targets[i-1])
        a[0][0] = emission(0,0); a[0][1] = emission(0,1)
        for (t in 1 until tCount) {
            checkCancelled()
            for (s in 0 until sCount) {
                var best = a[t-1][s]; var step = 0
                if (s > 0 && a[t-1][s-1] > best) { best = a[t-1][s-1]; step = 1 }
                if (s > 1 && skips[s] && a[t-1][s-2] > best) { best = a[t-1][s-2]; step = 2 }
                a[t][s] = best + emission(t,s); back[t][s] = step.toByte()
            }
        }
        h[tCount-1][sCount-1] = 0.0; h[tCount-1][sCount-2] = 0.0
        for (t in tCount-2 downTo 0) {
            checkCancelled()
            for (s in 0 until sCount) {
                var best = emission(t+1,s) + h[t+1][s]
                if (s+1 < sCount) best = max(best,emission(t+1,s+1)+h[t+1][s+1])
                if (s+2 < sCount && skips[s+2]) best = max(best,emission(t+1,s+2)+h[t+1][s+2])
                h[t][s] = best
            }
        }
    }
    val score: Double get() = max(a.last()[sCount-1],a.last()[sCount-2])
    fun replace(i: Int, allowed: IntArray): Path {
        val c = 2*i+1
        val enterSkip = i > 0 && disjoint(allowed,targets[i-1])
        val exitSkip = i+1 < targets.size && disjoint(allowed,targets[i+1])
        var prev = Double.NEGATIVE_INFINITY; var minStart = 0; var maxStart = 0
        var best = Double.NEGATIVE_INFINITY; var bestMin = 0; var bestMax = 0; var endMin = 0; var endMax = 0
        for (t in 0 until tCount) {
            if (t % 128 == 0) checkCancelled()
            val choices = ArrayList<Triple<Double,Int,Int>>(3)
            choices.add(Triple(prev,minStart,maxStart))
            if (t > 0) {
                choices.add(Triple(a[t-1][c-1],t,t))
                if (enterSkip) choices.add(Triple(a[t-1][c-2],t,t))
            } else if (c == 1) choices.add(Triple(0.0,0,0))
            val value = choices.maxOf { it.first }
            prev = value + allowed.maxOf { logp[t][it] }
            if (!value.isFinite()) continue
            val winners = choices.filter { abs(it.first-value) <= 1e-9 }
            minStart = winners.minOf { it.second }; maxStart = winners.maxOf { it.third }
            var tail = if (t+1 == tCount) { if (c == sCount-2) 0.0 else Double.NEGATIVE_INFINITY }
                else emission(t+1,c+1) + h[t+1][c+1]
            if (t+1 < tCount && exitSkip) tail = max(tail,emission(t+1,c+2)+h[t+1][c+2])
            val total = prev+tail
            if (!total.isFinite()) continue
            if (total > best+1e-9) {
                best = total; bestMin = minStart; bestMax = maxStart; endMin = t+1; endMax = t+1
            } else if (abs(total-best) <= 1e-9) {
                best = max(best,total); bestMin = min(bestMin,minStart); bestMax = max(bestMax,maxStart)
                endMin = min(endMin,t+1); endMax = max(endMax,t+1)
            }
        }
        return Path(best,bestMin,endMin,bestMin != bestMax || endMin != endMax)
    }
    fun assess(ipas: List<String>, byId: Map<Int,String>): List<Phone> {
        check(score.isFinite()) { "ALIGNMENT_FAILED" }
        val occurrences = Array(targets.size) { ArrayList<Int>() }
        var s = if (a.last()[sCount-1] >= a.last()[sCount-2]) sCount-1 else sCount-2
        for (t in tCount-1 downTo 0) {
            if (s % 2 == 1) occurrences[(s-1)/2].add(t)
            s -= back[t][s].toInt()
        }
        check(occurrences.all { it.isNotEmpty() }) { "ALIGNMENT_FAILED" }
        val frames = occurrences.map { it.asReversed().toIntArray() }
        val centers = frames.map { (it[(it.size-1)/2]+it[it.size/2])/2 }
        val cuts = intArrayOf(frames.first().first(), *centers.zipWithNext { x,y -> (x+y+1)/2 }.toIntArray(), frames.last().last()+1)
        val gap = (logp.sumOf { it.max() }-score)/tCount
        return targets.indices.map { i ->
            checkCancelled()
            val lo = min(cuts[i],frames[i].first()); val hi = max(cuts[i+1],frames[i].last()+1)
            if (gap > .50) return@map Phone(PronunciationStatus.UNKNOWN,0.0,"TARGET_INCOMPATIBLE",null,null,null,null,null,null)
            val other = phoneIds.filter { it !in targets[i] }.sortedWith(compareByDescending<Int> { p -> frames[i].sumOf { exp(logp[it][p]) }/frames[i].size }.thenBy { it }).take(3)
            val original = replace(i,targets[i])
            val best = other.map { replace(i,intArrayOf(it)) to it }.maxByOrNull { it.first.score }
                ?: return@map Phone(PronunciationStatus.UNKNOWN,0.0,"ALIGNMENT_FAILED",null,null,null,null,lo,hi)
            val candidate = best.first
            val union = (frames[i].toList()+(candidate.start until candidate.end)).distinct().sorted()
            if (union.isEmpty()) return@map Phone(PronunciationStatus.UNKNOWN,0.0,"ALIGNMENT_FAILED",null,null,null,null,lo,hi)
            val confidence = union.sumOf { t ->
                val probs = phoneIds.map { exp(logp[t][it]) }; val mass = probs.sum()
                val entropy = -probs.sumOf { p -> val q = p/max(mass,1e-12); q*ln(max(q,1e-12)) }/ln(max(2,phoneIds.size).toDouble())
                mass*(1-entropy)
            }.div(union.size).coerceIn(0.0,1.0)
            val margin = union.sumOf { t -> ln(targets[i].sumOf { exp(logp[t][it]) }.coerceIn(1e-12,1.0))-
                ln(max(1e-12,phoneIds.filter { it !in targets[i] }.maxOf { exp(logp[t][it]) })) }/union.size
            val pathMargin = (score-candidate.score)/union.size
            val raw = frames[i].sumOf { t -> ln(targets[i].sumOf { exp(logp[t][it]) }.coerceIn(1e-12,1.0)) }/frames[i].size
            val ambiguous = original.ambiguous || candidate.ambiguous || candidate.start < lo || candidate.end > hi || !candidate.score.isFinite()
            val (status,reason) = classify(confidence,margin,pathMargin,ambiguous,ipas[i] in listOf("t","d") && byId[best.second] == "ɾ")
            Phone(status,confidence,reason,raw,margin,pathMargin,best.second,lo,hi)
        }
    }
    companion object {
        fun classify(confidence: Double, frame: Double, path: Double, ambiguous: Boolean = false, flap: Boolean = false): Pair<PronunciationStatus,String> = when {
            ambiguous -> PronunciationStatus.UNKNOWN to "ALIGNMENT_AMBIGUOUS"
            confidence < .70 -> PronunciationStatus.UNKNOWN to "LOW_RELIABILITY"
            frame >= ln(3.0) && path >= ln(3.0) -> PronunciationStatus.GREEN to "TARGET_SUPPORTED"
            confidence >= .80 && frame <= -ln(10.0) && path <= -ln(10.0) -> if (flap) PronunciationStatus.YELLOW to "POSSIBLE_ALLOPHONE" else PronunciationStatus.RED to "COMPETING_EVIDENCE"
            else -> PronunciationStatus.YELLOW to "MIXED_EVIDENCE"
        }
        fun summarize(states: List<PronunciationStatus>): Pair<PronunciationStatus,Double> {
            val coverage = states.count { it != PronunciationStatus.UNKNOWN }.toDouble()/max(1,states.size)
            val state = when {
                PronunciationStatus.RED in states -> PronunciationStatus.RED
                states.isEmpty() || coverage < .8 -> PronunciationStatus.UNKNOWN
                states.all { it == PronunciationStatus.GREEN } -> PronunciationStatus.GREEN
                else -> PronunciationStatus.YELLOW
            }
            return state to coverage
        }
    }
}
