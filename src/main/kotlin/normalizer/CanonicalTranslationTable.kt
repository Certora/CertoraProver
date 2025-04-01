/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package normalizer

import allocator.Allocator
import analysis.TACCommandGraph
import com.certora.collect.*
import config.ReportTypes
import datastructures.ArrayHashMap
import datastructures.stdcollections.*
import instrumentation.transformers.CodeRemapper
import log.*
import spec.CVLKeywords
import tac.*
import tac.BlockIdentifier.Companion.fromCanon
import utils.*
import vc.data.*
import vc.data.tacexprutil.QuantDefaultTACExprTransformer
import java.io.Serializable
import kotlin.math.absoluteValue

const val ERASED = "erased"
private fun <T : Serializable> MetaKey<T>.toErased(): MetaKey<String> =
    MetaKey<String>("$ERASED-${this}", erased = false)

private fun <@Treapable T : Serializable> TACCmd.Simple.AnnotationCmd.Annotation<T>.erase() =
    TACCmd.Simple.AnnotationCmd.Annotation(
        k.toErased(),
        ERASED
    )

private fun MetaMap.erase(): MetaMap = this.map {
    if (it.key.isCanonical) {
        it.toPair()
    } else {
        it.key.toErased() to ERASED
    }
}
/**
 * given a [TACExpr.QuantifiedFormula] - just traverse its body
 * under the assumption that any variables in [TACExpr.QuantifiedFormula.quantifiedVars] should appear in its body
 **/
private fun quantifiedExprTransformer(mapper: DefaultTACCmdSpecMapper) =
    object : QuantDefaultTACExprTransformer() {
        override fun transformQuantifiedFormula(acc: QuantVars, exp: TACExpr.QuantifiedFormula) =
            TACExpr.QuantifiedFormula(
                isForall = exp.isForall,
                tag = exp.tag,
                quantifiedVars = exp.quantifiedVars.map {
                    mapper.mapVar(it)
                },
                body = transformArg(acc, exp.body, 0)
            )

        override fun transformSym(acc: QuantVars, exp: TACExpr.Sym): TACExpr =
            when (exp) {
                is TACExpr.Sym.Var -> exp.copy(s = mapper.mapSymbol(exp.s))
                is TACExpr.Sym.Const -> exp // nothing to canonicalize with consts, and we don't want to lose tags
            }

        override fun <@Treapable T : Serializable> transformAnnotationExp(
            acc: QuantVars, o: TACExpr, k: MetaKey<T>, v: T
        ) = TACExpr.AnnotationExp(transform(acc, o), k, mapper.mapMetaPair(k, v))

        override fun transformMapDefinition(acc: QuantVars, exp: TACExpr.MapDefinition) =
            TACExpr.MapDefinition(
                tag = exp.tag,
                definition = transformArg(acc, exp.definition, 0),
                defParams = exp.defParams.map {
                    this.transformSym(acc, it) as TACExpr.Sym.Var
                }
            )
    }

/** erase messages and non-canonical meta-data **/
private val erasureMapper = object : DefaultTACCmdSpecMapper() {
    override val exprMapper: QuantDefaultTACExprTransformer = quantifiedExprTransformer(this)

    override fun mapAnnotationCmd(t: TACCmd.Simple.AnnotationCmd): TACCmd.Simple =
        super.mapAnnotationCmd(t.copy(annot = t.annot.takeIf { it.k.isCanonical }
            ?: t.annot.erase()))

    override fun mapAssertCmd(t: TACCmd.Simple.AssertCmd): TACCmd.Simple =
        super.mapAssertCmd(t.copy(description = ERASED))

    override fun mapLabelCmd(t: TACCmd.Simple.LabelCmd): TACCmd.Simple =
        super.mapLabelCmd(t.copy(_msg = ERASED))

    override fun mapMeta(t: MetaMap): MetaMap = super.mapMeta(t.erase())
}

private fun Int.toPositive(): Int = if (this < 0) { this + Int.MIN_VALUE.absoluteValue } else { this }

private fun NBId.toPositive(): BlockIdentifier = when (this) {
    is CanonBlockIdentifier -> fromCanon(this, Int::toPositive)
    is BlockIdentifier -> this
}

private val positiveMapper = CodeRemapper<Unit>(
    blockRemapper = { nbId, _, _, _ -> nbId.toPositive() },
    idRemapper = { { _, id, _ -> id.toPositive() } },
    callIndexStrategy = { _, callId, _ -> callId.toPositive() },
    variableMapper = { _, v -> v },
).mapper(Unit, ::quantifiedExprTransformer)

fun<T: TACCmd.Spec, TAC> TAC.canonicalDump(suffix: String, dump: (DebuggableProgram<ReportTypes>) -> Unit,)
    where
    TAC : TACProgram<T>,
    TAC : InstrumentedTAC,
    TAC : IProcedural
{
    dump(CanonicalTACProgram(this, shouldErase = ShouldErase.Dont).copy(name = "$name-$suffix"))
}

/**
 * please note the sorting by [CVLTACProgram.name] performed in this function doesn't guarantee any real ordering
 * between runs since they contain [Range]:
 *
 * The name is built in [spec.CVLExpressionCompiler.compileExp]
 * ```
 * it.transformCode { c -> c.copy(name = "CVLExp_${getDisplayName(exp)}") }
 * ```
 * where
 * ```
 *  fun getDisplayName(exp: CVLExp) = "${exp.tag.range}: ${exp}"
 * ```
 **/
fun <C : Comparable<C>> InstrumentedTAC.forEachAxiom(
    keyComparator: (FunctionInScope.UF) -> C,
    visitUfAxioms: (FunctionInScope.UF, List<TACAxiom>) -> Unit,
) = instrumentationTAC.ufAxioms.mapTo { it }.toSortedMap(compareBy(keyComparator))
    .forEachEntry { (uf, axiom) -> visitUfAxioms(uf, axiom) }

/**
 * Given [TACCommandGraph], and the root-block to scan from,
 * get an object representing a canonical representation of the [TACCommandGraph].
 *
 * In this canonical representation,
 * * block-ids
 * * meta-data with ids [AllocatorIdEntity]
 * * symbols [TACSymbol.Var]
 * are assigned new values defined by a DFS scan of the command graph.
 *
 * [CanonicalTranslationTable] is implemented as a set of "mappers", which are updated as we DFS-scan
 * the command graph.
 *
 * A mapper is an object implementing the [CanonMapper] interface, and has two roles:
 * 1) defining the logic by which to compute these canonical values
 * 2) storing canonical values of items we have visited
 *
 * For more details, see [CanonMapper]
 * **/
class CanonicalTranslationTable private constructor(
    private val logger: Logger,
    private val varNameMapper: VarNameMapper,
    private val metaIdMapper: MetaIdMapper,
    private val nbidsMapper: NbidMapper,
    private val shouldErase : Boolean
) {
    private fun forEachCanonMapper(f: (CanonMapper<*>) -> Unit) {
        f(varNameMapper)
        f(metaIdMapper)
        f(nbidsMapper)
    }

    private val cmdMapper = CodeRemapper<Unit>(
        blockRemapper = { id, _, _, _ -> nbidsMapper.map(id) },
        idRemapper = { { aId, id, _ -> metaIdMapper.unguardedId(aId as Allocator.Id, id) } },
        callIndexStrategy = { _, idx, _ -> metaIdMapper.unguardedId(Allocator.Id.CALL_ID, idx) },
        variableMapper = { _, v -> varNameMapper.map(v) }
    ).mapper(Unit, ::quantifiedExprTransformer)

    private inline fun<reified T> apply(t: T, f : DefaultTACCmdSpecMapper.(T) -> T) =
        cmdMapper.f(t).let { positiveMapper.f(it) }.let {
            if (shouldErase) {
                erasureMapper.f(it)
            } else {
                it
            }
        }

    fun mapVar(t: TACSymbol.Var): TACSymbol.Var = apply(t, DefaultTACCmdMapper::mapVar)
    fun map(t: TACCmd.Simple): TACCmd.Simple = apply(t, DefaultTACCmdMapper::map)
    fun mapSpec(t: TACCmd.Spec): TACCmd.Spec = apply(t, DefaultTACCmdSpecMapper::mapSpec)
    fun mapExpr(t: TACExpr): TACExpr = apply(t, DefaultTACCmdMapper::mapExpr)
    fun mapNBId(id: NBId): NBId = nbidsMapper.map(id).let(NBId::toPositive)
    fun mapCallIndex(index: CallId): CallId? = metaIdMapper.getCallIndex(index)?.toPositive()
    fun mapUF(uf: FunctionInScope.UF) = varNameMapper.map(uf)


    fun show() {
        if (logger.isEnabled && logger.isTraceEnabled) {
            logger.trace { "START CANONICAL TRANSLATION TABLE" }
            forEachCanonMapper(CanonMapper<*>::show)
            logger.trace { "END CANONICAL TRANSLATION TABLE" }
        }
    }

    private fun freeze() = forEachCanonMapper { it.frozen = true }

    fun <T : TACCmd.Spec, TAC> build(ctp: TAC)
        where TAC : TACProgram<T>, TAC : InstrumentedTAC {
        ctp.dfs { nbid, commands ->
            mapNBId(nbid).also { logger.trace { "Visiting block-id $nbid -> $it" } }
            for (cmd in commands) {
                varNameMapper.unorderedVarAccess(cmd)
                    ?.also { logger.info { "visit $it as part of $cmd: canonical order is not guaranteed" } }
                mapSpec(cmd)
            }
        }

        ctp.symbolTable.uninterpretedFunctions()
            .sortedBy { it.copy(name = "").toString() }
            .forEach { mapUF(it) }

        ctp.forEachAxiom(keyComparator = { mapUF(it).name }) { _, axioms ->
            axioms.forEach { mapExpr(it.exp) }
        }

        // please note - these are unordered
        ctp.symbolTable.tags.keys.forEach(::mapVar)
    }

    companion object {
        val GLOBAL_KEYWORDS = TACKeyword.values().map { it.getName() }.toSet() + CVLKeywords.values().map { it.keyword }

        private operator fun invoke(logger: Logger, shouldErase : Boolean): CanonicalTranslationTable {
            val metaIdMapper = MetaIdMapper(logger)
            return CanonicalTranslationTable(
                logger,
                varNameMapper = VarNameMapper(logger),
                metaIdMapper,
                nbidsMapper = NbidMapper(logger, metaIdMapper),
                shouldErase = shouldErase
            )
        }

        operator fun <T : TACCmd.Spec, TAC> invoke(
            ctp: TAC,
            log: Logger? = null,
            shouldErase: Boolean
        ): CanonicalTranslationTable
            where TAC : TACProgram<T>, TAC : InstrumentedTAC =
            CanonicalTranslationTable(log ?: Logger(LoggerTypes.NORMALIZER), shouldErase).apply {
                build(ctp)
                freeze()
            }
    }
}


/**
 * A common interface for maintaining a mapping of keys to their canonical representation
 *
 * takes a key and maps it to its canonical representation,
 * canonical-values are must be mapped to themselves or raise an exception.
 *
 * all inserts guarded for
 * ```
 * frozen == false
 * ```
 * so only when building a [CanonicalTranslationTable] the mapping it updated
 **/
private sealed class CanonMapper<K> {
    abstract val table: MutableMap<K, K>
    var frozen: Boolean = false
        set(value) {
            check(value) { "CanonMapper cannot be unfrozen" }
            field = value
        }
    abstract val logger: Logger

    // we don't want to the logs to interleave with each over, but it will be nice to avoid an OOM too
    // So we chunk all the tables into 1_000 sized sub-lists
    fun show() {
        val seq = sequenceOf(table.javaClass.simpleName + ": ") + table.entries.asSequence()
        for (chunk in seq.chunked(1_000)) {
            logger.trace { chunk.joinToString("\n") }
        }
    }

    protected fun msg(k: K): String =
        "trying to remap $k, but it is from the mapped-domain"

    protected fun checkFrozen(k: K) = check(!frozen) { "cannot insert $k when frozen" }
}

/**
 * Builds a closure that takes [BlockIdentifier] and outputs [CanonBlockIdentifier]
 *
 * validates that `id: [NBId]`s are always [BlockIdentifier]
 * [BlockIdentifier.origStartPc] mappings is not defined by [mapIdMapper] as it should be in the future
 **/
private class NbidMapper(
    override val logger: Logger,
    val mapIdMapper: MetaIdMapper,
    override val table: MutableMap<NBId, NBId> = ArrayHashMap()
) :
    CanonMapper<NBId>() {
    fun map(k: NBId): NBId {
        check(k is BlockIdentifier) { msg(k) }
        return table.getOrPut(k) {
            checkFrozen(k)
            k.toCanon(mapIdMapper::guardedId).also { logger.trace { "Mapped $k to $it" } }
        }
    }
}


/**
 *  Builds a mapper between a [TACSymbol.Var] to a `canonical` [TACSymbol.Var],
 *  based on [CanonMapper] that is incrementally build with the prefix `CANON` and a running index starting from `0`
 * Note:
 *  It is assumed that values with [TACSymbol.Var.namePrefix] prefixed with `CANON` will not be given,
 *  unless a variable is part of a [TACExpr.QuantifiedFormula]
 *
 *  i.e. variables will not be remapped, once they are part of a [TACExpr.QuantifiedFormula]
 **/
private class VarNameMapper(
    override val logger: Logger,
    override val table: MutableMap<String, String> = ArrayHashMap<String, String>().apply {
        putAll(CanonicalTranslationTable.GLOBAL_KEYWORDS.zip(CanonicalTranslationTable.GLOBAL_KEYWORDS))
    }
) :
    CanonMapper<String>() {

    companion object {
        private const val VAR_BASE = "CANON"
    }
    private val tacKeywordIndices = TACKeyword.entries.map { 0 }.toTypedArray()
    private val tacKeywordBaseNames = TACKeyword.entries.map { it.getName() + VAR_BASE }.toTypedArray()

    private val nonTACKeywordIndices = ArrayHashMap<String, Int>()

    private fun map(k: String, keyword: TACSymbol.Var.KeywordEntry? = null): String =
        table.getOrPut(k) {
            checkFrozen(k)
            keyword?.maybeTACKeywordOrdinal?.let {
                "${tacKeywordBaseNames[it]}${tacKeywordIndices[it]++}"
            } ?: run {
                val sanitizedKeywordBase = if (keyword == null) { VAR_BASE } else { keyword.name + VAR_BASE }
                val index = nonTACKeywordIndices.getOrDefault(sanitizedKeywordBase, 0)
                nonTACKeywordIndices[sanitizedKeywordBase] = index + 1
                if (index == 0) { sanitizedKeywordBase } else { "$sanitizedKeywordBase$index" }
            }
        }

    fun unorderedVarAccess(cmd: TACCmd.Spec): List<String>? = unorderedVarAccess(cmd, table.keys)

    fun map(v: TACSymbol.Var): TACSymbol.Var = v.copy(namePrefix = map(v.namePrefix, v.meta[TACSymbol.Var.KEYWORD_ENTRY]))
    fun map(uf: FunctionInScope.UF) = map(uf.name, null).let { uf.copy(name = it, declarationName = it) }
}

internal fun unorderedVarAccess(cmd: TACCmd.Spec, visitedVarPrefixes: Set<String> = emptySet()): List<String>? =
    cmd
        // the order of `AnnotationCmd` and `SummaryCmd` is not guaranteed between different runs
        // since their implementation of `getFreeVarsOfRhs` is defined elsewhere
        .takeIf { it is TACCmd.Simple.AnnotationCmd || it is TACCmd.Simple.SummaryCmd }
        ?.getFreeVarsOfRhs()
        ?.map { it.namePrefix }
        ?.mapNotNull { rhs -> rhs.takeIf { it !in visitedVarPrefixes } }
        // if there is just one element not in table the traversal order is well-defined
        ?.takeIf { it.size > 1 }

/**
 * Builds a closure that takes ([Allocator.Id], [Int]) and outputs a canonical `Id`
 *
 * validates that `id` are always positive, since they are allocated by [Allocator.getFreshId]
 **/
private class MetaIdMapper(
    override val logger: Logger,
    override val table: MutableMap<Pair<Allocator.Id, Int>, Pair<Allocator.Id, Int>> = ArrayHashMap()
) :
    CanonMapper<Pair<Allocator.Id, Int>>() {
    private val runningIndices = ArrayHashMap<Allocator.Id, Int>()
    private fun guard(k: Int) = k >= 0

    private fun map(allocatorId: Allocator.Id, id: Int): Int =
        table.getOrPut(allocatorId to id) {
            checkFrozen(allocatorId to id)
            val index = runningIndices.getOrDefault(allocatorId, Int.MIN_VALUE)
            runningIndices[allocatorId] = index + 1
            (allocatorId to index).also { logger.trace { "Mapped $allocatorId and $id to $index" } }
        }.second


    fun guardedId(allocatorId: Allocator.Id, id: Int): Int {
        check(guard(id)) { msg(allocatorId to id) }
        return map(allocatorId, id)
    }

    fun unguardedId(allocatorId: Allocator.Id, id: Int): Int = if (guard(id)) {
        map(allocatorId, id)
    } else {
        id
    }

    fun getCallIndex(id: CallId): CallId? =
        id.takeIf(::guard)?.let { table[Allocator.Id.CALL_ID to it] }?.second
}
