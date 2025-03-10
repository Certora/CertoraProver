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

package smt.solverscript

import config.Config
import datastructures.EnumSet
import datastructures.Memoized
import datastructures.Memoized2
import datastructures.enumSetOf
import datastructures.stdcollections.*
import evm.*
import log.*
import smt.HashingScheme
import smt.axiomgenerators.LExpDefineFun
import smt.axiomgenerators.fullinstantiation.expnormalizer.ExpNormalizerIA.Companion.atMostOneNonConstant
import smt.solverscript.functionsymbols.*
import smtlibutils.data.*
import tac.*
import utils.*
import utils.ModZm.Companion.asBigInteger
import utils.ModZm.Companion.from2s
import vc.data.FunctionInScope
import vc.data.LExpression
import vc.data.Quantifier
import vc.data.ToSmtLibData
import java.io.Serializable
import java.math.BigInteger


private val logger = Logger(LoggerTypes.VC_TO_SMT_CONVERTER)

/**
 * TODO (CERT-8094): it would be nice to hide the constructors of [LExpression] in order to enforce use of the build methods in
 * here, but Kotlin doesn't make that easy...
 * A factory for LExpressions.
 * When LExpressions are created through this class, it will track certain things for us
 *  - which function symbols are in use/need to be declared in SMT
 *  -
 *
 *
 * The exact outlines of the functionality of this class are still somewhat fluid...
 *
 * Functionalities we want (in this class or some other, maybe start here, split later):
 *  - provide an interface for creation of smt terms
 *    - this means, if needed, we can do deduplication of terms
 *  - management of symbols that need to be declared in an SMT script
 *
 *
 * To easily create [LExpression], use the invoke operator to create and expression as if the code is within
 * [LExpressionFactory]. For example, if `lxf` is an instance of [LExpressionFactory], then:
 *
 * lxf {
 *    val (a,b) = getQuantifiedArgs(1, 2)
 *    return { forall(a, b) {
 *        ((a gt ZERO) and (b gt TWO)) implies (((a uninterpMul b ge a)) and (a uninterpMul b ge b))
 * }}}
 *
 * will create two variables named x&1 and x&2, and return the corresponding LExpression.
 * Note that infix functions have all have the same precedence, and so the default interpretation is left to right.
 */
class LExpressionFactory private constructor(
    private var constantsFrozen: Boolean,
    private var arraysFrozen: Boolean,
    private var nonArrayAxiomatizedSymbolsFrozen: Boolean,
    private var userDefinedFunctionsFrozen: Boolean,
    private var userDefinedAxiomsFrozen: Boolean,
    private var uninterpretedSortsFrozen: Boolean,
    private val uninterpretedSymbolManager: UninterpretedSymbolManager,
    private val usedInterpretedFunctionSymbols: MutableSet<InterpretedFunctionSymbol>,
    private val datatypeDeclarations: MutableSet<DatatypeDeclaration>,
    private var usedFeatures: EnumSet<VcFeature>,
    private val defFuncs: MutableMap<String, LExpDefineFun>,
) {
    constructor() : this(
        constantsFrozen = false,
        arraysFrozen = false,
        nonArrayAxiomatizedSymbolsFrozen = false,
        userDefinedFunctionsFrozen = false,
        userDefinedAxiomsFrozen = false,
        uninterpretedSortsFrozen = false,
        uninterpretedSymbolManager = UninterpretedSymbolManager(),
        usedInterpretedFunctionSymbols = mutableSetOf(),
        datatypeDeclarations = mutableSetOf(),
        usedFeatures = enumSetOf(),
        defFuncs = LinkedHashMap<String, LExpDefineFun>()
    )

    fun commonTag(vararg exp: LExpression) = exp.map { it.tag }.commonTag()
    fun commonBitsOrInt(vararg exp: LExpression) = exp.map { it.tag }.commonBitsOrInt() ?: error("not bits or int: $exp")
    fun commonBitsTag(exps: List<LExpression>) = exps.map { it.tag }.commonTag() as Tag.Bits
    fun commonBitsTag(vararg exp: LExpression) = commonBitsTag(exp.toList())

    operator fun <T> invoke(expr: LExpressionFactory.() -> T) = this.expr()

    /**
     * Create and return a copy of [this] where [LExpression.meta] is replaced by [newMeta].
     */
    private fun <LXP: LExpression> LXP.setMeta(newMeta: MetaMap): LXP {
        return when (this) {
            is LExpression.ApplyExpr -> this.copy(meta = newMeta).uncheckedAs()
            is LExpression.Identifier -> this.copy(meta = newMeta).uncheckedAs()
            else -> this
        }
    }

    /**
     * Create and return a copy of [this] where [newMeta] is added to the stored [LExpression.meta].
     */
    fun <LXP: LExpression> LXP.addMeta(newMeta: MetaMap?): LXP {
        return newMeta?.let { setMeta(meta + newMeta) } ?: this
    }

    /**
     * Create and return a copy of [this] where `key = value` is added to the stored [LExpression.meta].
     */
    fun <T: Serializable, LXP: LExpression> LXP.addMeta(key: MetaKey<T>, value: T): LXP {
        return setMeta(meta + (key to value))
    }

    fun addDefFunc(defFunc: LExpDefineFun) {
        check(!defFuncs.containsKey(defFunc.name)) {
            "Defining ${defFunc.name} when there is already a definition by the same name"
        }
        defFuncs[defFunc.name] = defFunc
    }

    fun getDefFuncs() = defFuncs.values

    fun getDefFunc(name : String) = defFuncs[name]

    fun isUsed(fs: InterpretedFunctionSymbol) = usedInterpretedFunctionSymbols.contains(fs)

    // TODO CERT-3171: note that we probably do not always track non-linear operations correctly (do not add them).
    // see https://certora.atlassian.net/browse/CERT-3171
    // We used to use the non-lin ops detection to run LIA_only_race; this is disabled now. However,
    // one might want to use the non-lin ops detection also for other scenarios (e.g. limiting solvers in parallel splitter).
    // In such case, double check that the non-lin ops detection is correct.
    fun getUsedFeatures(): EnumSet<VcFeature> = usedFeatures

    /** when this flag is set, no new [UninterpretedFunctionSymbol]s may be introduced */

    private fun buildAppExp(
        functionSymbol: FunctionSymbol,
        arguments: List<LExpression>,
        meta: MetaMap?
    ): LExpression.ApplyExpr =
        buildAppExp(functionSymbol, *arguments.toTypedArray(), meta = meta)

    /**
     * base builder -- all other buildApp* functions need to call this one
     */
    private fun buildAppExp(
        fs: FunctionSymbol,
        vararg arguments: LExpression,
        meta: MetaMap?
    ): LExpression.ApplyExpr {
        check(fs.signature.nrOfParameters.let {
            it is NrOfParameters.Flexible ||
                it is NrOfParameters.Fixed && it.n == arguments.size
        })
        { "Number of arguments ${arguments.size} does not match signature of $fs" }

        check(
            fs !is UninterpretedFunctionSymbol ||
                    !nonArrayAxiomatizedSymbolsFrozen ||
                    uninterpretedSymbolManager.isFunctionSymbolInUse(fs)
        )
        { "Registering a fresh FunctionSymbol (here: $fs) is forbidden once LExpressionFactory has been frozen." }

        registerFunctionSymbol(fs)

        updateUsedFeatures(fs, arguments)

        return LExpression.ApplyExpr(fs, *arguments, meta = meta ?: MetaMap())
    }

    /**
     * Creates an apply expression with the special wrappers for optimizing the resulting LExpression.
     * As opposed [buildAppExp], may return any kind of [LExpression] and not just [LExpression.ApplyExpr]
     */
    fun applyExp(fs: FunctionSymbol, arguments: List<LExpression>, meta: MetaMap? = null): LExpression =
        applyExp(fs, *arguments.toTypedArray(), meta = meta)

    fun applyExp(fs: FunctionSymbol, vararg arguments: LExpression, meta: MetaMap? = null): LExpression =
        when (fs) {
            // future optimizations in wrappers should be included here
            is TheoryFunctionSymbol.Vec.LAnd -> and(*arguments, meta = meta)
            is TheoryFunctionSymbol.Vec.LOr -> or(*arguments, meta = meta)
            is NonSMTInterpretedFunctionSymbol.Vec.Add -> add(fs.tag, *arguments, meta = meta)
            is TheoryFunctionSymbol.Vec.IntAdd -> intAdd(*arguments, meta = meta)
            is TheoryFunctionSymbol.Vec.IntMul, is NonSMTInterpretedFunctionSymbol.Vec.Mul ->
                buildAppExp(fs, *arguments, meta = meta)

            else -> buildAppExp(fs, *arguments, meta = meta)
        }

    private fun updateUsedFeatures(fs: FunctionSymbol, arguments: Array<out LExpression>) {
        if (AxiomatizedFunctionSymbol.isHashFunctionSymbol(fs)) {
            usedFeatures += VcFeature.Hashing
        }
        if (fs is AxiomatizedFunctionSymbol.Bitwise) {
            usedFeatures += VcFeature.BitwiseOperations
        }
        if (fs is AxiomatizedFunctionSymbol.LongStore) {
            usedFeatures += VcFeature.LongStore
        }
        if (fs is NonSMTInterpretedFunctionSymbol.MultiDimArray.MultiSelect ||
            fs is NonSMTInterpretedFunctionSymbol.MultiDimArray.MultiStore
        ) {
            usedFeatures += VcFeature.MultiDimGhost
        }

        /*
         * Nb this is soundness critical, because if we don't add this feature, we consider models from the LIA
         * axiomatization as valid models and report them without running NIA in addition.
         *
         * This is really not the best way to do this. It would be better to make a separate run just on the exact
         * vc and see if it contains any of these.
         */
        if (
            when (fs) {
                is TheoryFunctionSymbol.Vec.IntMul,
                is NonSMTInterpretedFunctionSymbol.Binary.NoMulOverflow,
                is NonSMTInterpretedFunctionSymbol.Binary.NoSMulOverUnderflow,
                is NonSMTInterpretedFunctionSymbol.Binary.ShiftLeft,
                is NonSMTInterpretedFunctionSymbol.Binary.ShiftRightLogical,
                -> !atMostOneNonConstant(arguments.toList())

                is TheoryFunctionSymbol.Binary.IntDiv,
                is TheoryFunctionSymbol.Binary.IntMod,
                -> !arguments[1].isConst //  7/x is already non-linear.

                is NonSMTInterpretedFunctionSymbol.Ternary.AddMod,
                -> !arguments[2].isConst

                is NonSMTInterpretedFunctionSymbol.Ternary.MulMod,
                -> !arguments[2].isConst || !atMostOneNonConstant(listOf(arguments[0], arguments[1]))

                is NonSMTInterpretedFunctionSymbol.Vec.Mul,
                    // because of implicit mod 2^256, but in the common case where there is no actual overflow
                    // the constrained nia "takeAll" should verify a counter example easily.
                -> true

                else -> false
            }
        ) {
            usedFeatures += VcFeature.NonLinearArithmetic
        }

        if (fs is NonSMTInterpretedFunctionSymbol.Binary.NoMulOverflow ||
            fs is NonSMTInterpretedFunctionSymbol.Binary.NoSMulOverUnderflow
        ) {
            usedFeatures += VcFeature.MulOverflowChecks
        }

        // In case we decide to lower bitwise precision in LIA, then our counter example may be wrong. We therefore
        // signal here (hackishly), that then bitwise operations are considered non linear, so that NIA will run if
        // LIA did not prove the VC. We hope that in this case the counter-example can be lifted easily by the constrained
        // solvers.
        if (Config.Smt.bitwisePrecisionLIAoverride.get() != -1 &&
            Config.Smt.bitwisePrecisionLIAoverride.get() < Config.Smt.bitwisePrecision.get() &&
            (fs is NonSMTInterpretedFunctionSymbol.Binary.BitwiseAnd ||
                    fs is NonSMTInterpretedFunctionSymbol.Binary.BitwiseOr ||
                    fs is NonSMTInterpretedFunctionSymbol.Binary.BitwiseXor ||
                    fs is AxiomatizedFunctionSymbol.Bitwise.SignExtend)
        ) {
            usedFeatures += VcFeature.NonLinearArithmetic
        }
    }

    fun buildAppExpFoldVecToBinary(fs: FunctionSymbol, args: List<LExpression>, meta: MetaMap?): LExpression =
        when (args.size) {
            0 -> error { "unexpected case" }
            1 -> error { "unexpected case" }
            2 -> buildAppExp(fs, args, meta)
            else -> buildAppExpFoldVecToBinary(
                fs,
                listOf(buildAppExp(fs, args.subList(0, 2), meta)) + args.subList(2, args.size),
                meta
            )
        }

    /** Construct and register an LExpression identifier of type ghost (~aka UF) from the given data.
     *  (Or just return it if it already exists.)
     * If the given function is nullary, this will construct a constant, otherwise it will construct a "proper" function
     *  (/map/ghost map) identifier. */
    fun getOrConstructGhostIdentifier(name: String, uf: FunctionInScope.UF, meta: MetaMap?): LExpression.Identifier =
        uninterpretedSymbolManager.getOrConstructGhostIdentifier(name, uf, meta)

    fun const(name: String, sort: Tag, meta: MetaMap? = null) =
        uninterpretedSymbolManager.getOrConstructConstant(name, sort, meta)

    private var skolemConstantCounter = 0
    fun createSkolemConstant(exVar: LExpression.Identifier): LExpression.Identifier {
        skolemConstantCounter++
        return const("${exVar.id}$skolemConstantCounter", exVar.tag, exVar.meta)
    }

    // quantifier builders

    /** base case, every quantified formula is ultimately constructed here */
    fun buildQuantifiedExpr(
        quantifier: Quantifier,
        quantifiedVars: List<LExpression.Identifier>,
        body: LExpression
    ): LExpression {

        val res = if (body is LExpression.QuantifiedExpr && body.quantifier == quantifier) {
            // flatten the quantifier
            LExpression.QuantifiedExpr(quantifier, quantifiedVars + body.quantifiedVar, body.body)
        } else {
            LExpression.QuantifiedExpr(quantifier, quantifiedVars, body)
        }
        /** `res` may not be a `QuantifiedExpr` because the set of quantifiers turned out to be empty
         * (even after flattening). */
        if (res is LExpression.QuantifiedExpr) {
            usedFeatures += VcFeature.Quantification
        }
        return res
    }

    /*************************** convenience functions *******************
     *  - used for writing lExpressions nicely (mostly in the AxiomGenerators)
     *  - note that most of these ignore meta, i.e. everything built with them won't
     *    have any [LExpMeta] annotation.
     *   (some of them are used in the toLExpression step; those can pass along meta)
     *********************************************************************/

    /** Convenience function for array select
     * @param storageKeySort see [TheoryFunctionSymbol.Array.Select] for an explanation. */
    fun select(array: LExpression, index: LExpression, mapSort: Tag, storageKeySort: Tag?) =
        applyExp(TheoryFunctionSymbol.Array.Select(mapSort, storageKeySort), array, index) as LExpression.ApplyExpr

    fun multiSelect(array: LExpression, index: List<LExpression>, mapSort: Tag) =
        applyExp(
            NonSMTInterpretedFunctionSymbol.MultiDimArray.MultiSelect(mapSort as Tag.GhostMap),
            array,
            *index.toTypedArray(),
        ) as LExpression.ApplyExpr

    /** Convenience function for array store */
    fun store(
        array: LExpression,
        index: LExpression,
        value: LExpression,
        mapSort: Tag,
        storageKeySort: Tag?,
    ) = applyExp(
        TheoryFunctionSymbol.Array.Store(mapSort, storageKeySort),
        array,
        index,
        value,
    ) as LExpression.ApplyExpr


    /**
     * Convenience function for disjunction
     */
    fun or(vararg disjuncts: LExpression, meta: MetaMap? = null, simplify: Boolean = false): LExpression =
        or(disjuncts.toList(), meta, simplify)

    /** warning: if [simplify] is set to `true`, this might drop meta annotations through flattening or absorption  */
    fun or(disjuncts: List<LExpression>, meta: MetaMap? = null, simplify: Boolean = false): LExpression =
        disjuncts.letIf(simplify) {
            disjuncts.flatMap { disjunct ->
                when {
                    disjunct.isTrue -> return@letIf listOf(TRUE)
                    disjunct.isFalse -> listOf()
                    disjunct.isApplyOf<TheoryFunctionSymbol.Vec.LOr>() -> disjunct.args
                    else -> listOf(disjunct)
                }
            }
        }.let {
            when (it.size) {
                0 -> FALSE.addMeta(meta)
                1 -> it[0].addMeta(meta)
                else -> buildAppExp(TheoryFunctionSymbol.Vec.LOr, it, meta)
            }
        }

    /** convenience function for (non-modulo) addition; deals with 0 and 1 summand cases */
    fun intAdd(vararg summands: LExpression, meta: MetaMap? = null): LExpression =
        intAdd(summands.toList(), meta = meta)

    /** convenience function for (non-modulo) addition; deals with 0 and 1 summand cases */
    fun intAdd(summands: List<LExpression>, meta: MetaMap? = null): LExpression = when (summands.size) {
        0 -> litInt(0)
        1 -> summands[0]
        else -> buildAppExp(TheoryFunctionSymbol.Vec.IntAdd, summands, meta = meta)
    }

    /** convenience function for modulo addition - folds away mod on inside expressions; deals with 0 and 1 summand cases */
    fun add(tag: Tag.Bits, vararg summands: LExpression, meta: MetaMap? = null): LExpression = add(tag, summands.toList(), meta)
    fun add(tag: Tag.Bits, summands: List<LExpression>, meta: MetaMap? = null): LExpression = when (summands.size) {
        0 -> lit(0, tag)
        1 -> summands[0]
        else -> {
            val flatSummands = summands.flatMap { s ->
                if (s is LExpression.ApplyExpr && s.f is NonSMTInterpretedFunctionSymbol.Vec.Add) {
                    s.args
                } else {
                    listOf(s)
                }
            }

            buildAppExp(
                NonSMTInterpretedFunctionSymbol.Vec.Add(tag),
                flatSummands,
                meta = meta
            )
        }
    }

    /**
     * Convenience function for conjunction
     */
    fun and(vararg conjuncts: LExpression, meta: MetaMap? = null, simplify: Boolean = false): LExpression =
        and(conjuncts.toList(), meta, simplify)

    /** warning: if [simplify] is set to `true`, this might drop meta annotations through flattening or absorption  */
    fun and(conjuncts: List<LExpression>, meta: MetaMap? = null, simplify: Boolean = false): LExpression =
        conjuncts.letIf(simplify) {
            conjuncts.flatMap { conjunct ->
                when {
                    conjunct.isTrue -> listOf()
                    conjunct.isFalse -> return@letIf listOf(FALSE)
                    conjunct.isApplyOf<TheoryFunctionSymbol.Vec.LAnd>() -> conjunct.args
                    else -> listOf(conjunct)
                }
            }
        }.let {
            when (it.size) {
                0 -> TRUE.addMeta(meta)
                1 -> it[0].addMeta(meta)
                else -> buildAppExp(TheoryFunctionSymbol.Vec.LAnd, it, meta)
            }
        }

    fun buildConstantNewUFLIAwud(arrayName: String, tag: Tag): LExpression.Identifier =
        LExpression.Identifier(arrayName, tag)

    fun buildVarForDef(name: String, tag: Tag): LExpression.Identifier =
        LExpression.Identifier(name, tag)

    fun freezeConstants() {
        constantsFrozen = true
        uninterpretedSymbolManager.freezeConstants()
    }

    fun areConstantsFrozen() = constantsFrozen

    fun freezeAxiomatized() {
        nonArrayAxiomatizedSymbolsFrozen = true
        uninterpretedSymbolManager.freezeAxiomatized()
    }

    fun freezeArrays() {
        arraysFrozen = true
        uninterpretedSymbolManager.freezeArraySelects()
    }


    fun freezeUserDefinedFunctions() {
        userDefinedFunctionsFrozen = true
        uninterpretedSymbolManager.freezeUserDefinedFunctions()
    }

    fun freezeUninterpretedSorts() {
        uninterpretedSortsFrozen = true
        uninterpretedSymbolManager.freezeUninterpretedSorts()
    }

    fun changeTypeOfConstantSymbol(name: String, newType: Tag) {
        if (uninterpretedSymbolManager.getTagOfConstantSymbol(name) != newType) {
            check(!constantsFrozen) { "cannot change type of a constant once they're frozen" }
            uninterpretedSymbolManager.changeTypeOfConstantSymbol(name, newType)
        }
    }

    /** The corresponding warning (see usage of this) is likely to trigger very often if it triggers once, swamping our
     * output, so we use this flag to suppress all but the first warning per function symbol. */
    private val warnedAboutReuseOfName = mutableSetOf<FunctionSymbol>()

    fun registerFunctionSymbol(fs: FunctionSymbol) {
        if (fs !in warnedAboutReuseOfName &&
            uninterpretedSymbolManager.getInUseFunctionSymbol(fs.name)?.let { it != fs } == true) {
            logger.warn { "a different function symbol has already been registered under the same name. " +
                "Name: \"${fs.name}\", " +
                "registered signature: \"${uninterpretedSymbolManager.getInUseFunctionSymbol(fs.name)?.signature}\" " +
                "signature of fs we're attempting to register: \"${fs.signature}\" (this is only reported once " +
                "per function symbol)" }
            warnedAboutReuseOfName += fs
        }

        if (fs !is NonSMTInterpretedFunctionSymbol) {
            // [NonSMTInterpretedFunctionSymbol] won't show up in the final formula
            // for all others function symbols we register the sorts they require
            fs.signature.allOccurringSorts.filterIsInstance<Tag.UserDefined.UninterpretedSort>()
                .forEach { sort: Tag.UserDefined.UninterpretedSort -> registerUninterpretedSort(sort) }
        }

        when (fs) {
            is UninterpretedFunctionSymbol -> {
                if (uninterpretedSymbolManager.isFunctionSymbolInUse(fs)) {
                    return // nothing to do
                }
                @Suppress("REDUNDANT_ELSE_IN_WHEN")
                when (fs) {
                    is ConstantSymbol -> {
                        check(!constantsFrozen)
                        { "Constants already frozen but trying to register $fs." }
                        uninterpretedSymbolManager.registerUseOfFunctionSymbol(fs)
                    }

                    is AxiomatizedFunctionSymbol -> {
                        check(!nonArrayAxiomatizedSymbolsFrozen)
                        { "Axiomatized function symbols (not including array_selects) already frozen but trying to register $fs." }
                        uninterpretedSymbolManager.registerUseOfFunctionSymbol(fs)
                    }

                    is ArraySelectFunctionSymbol -> {
                        check(!arraysFrozen)
                        { "Array select function symbols already frozen but trying to register $fs." }
                        uninterpretedSymbolManager.registerUseOfFunctionSymbol(fs)
                    }

                    is UserDefinedFunctionSymbol -> {
                        check(!userDefinedFunctionsFrozen)
                        { "User defined function symbols already frozen but trying to register $fs." }
                        uninterpretedSymbolManager.registerUseOfFunctionSymbol(fs)
                    }
                    // leaving this redundant [else] to get an error when we miss something here
                    else -> {
                        error(
                            "Failed to register function symbol " +
                                    "-- untracked kind of function symbol? $fs / ${fs.javaClass}"
                        )
                    }
                }
            }

            is InterpretedFunctionSymbol -> {
                usedInterpretedFunctionSymbols.add(fs)
            }
        }
    }

    fun registerUninterpretedSort(sort: Tag.UserDefined.UninterpretedSort) {
        if (sort in uninterpretedSymbolManager.getUninterpSorts()) {
            return
        }
        check(!uninterpretedSortsFrozen)
        uninterpretedSymbolManager.registerUninterpretedSort(sort)
    }

    /**
     * Create a variable with the given name and tag. If a variable of that name has already been declared, we check
     * that its sort is the same.
     */
    fun variable(id: String, sort: Tag) =
        uninterpretedSymbolManager.getOrConstructConstant(id, sort, meta = null)

    /**
     * Create a fresh variable with the given name prefix and tag. The name prefix is made unique by appending "!$id"
     * for increasing ids until the resulting name is unused.
     */
    fun freshVariable(prefix: String, sort: Tag): LExpression.Identifier {
        val seq = sequenceOf(prefix) + (generateSequence(1) { it + 1 }.map { "$prefix!$it" })
        return variable(seq.first { !uninterpretedSymbolManager.isConstantNameInUse(it) }, sort)
    }

    var ssaConstantCounter = mutableMapOf<String, Int>()
    private fun getSsaConstantIndex(name: String): Int {
        val old = ssaConstantCounter[name]
        return if (old == null) {
            ssaConstantCounter[name] = 0
            0
        } else {
            ssaConstantCounter[name] = old + 1
            old + 1
        }
    }

    fun getSsaConstant(sym: LExpression.Identifier, meta: MetaMap?): LExpression.Identifier {
        return const("${sym.id}${getSsaConstantIndex(sym.id)}", sym.tag, meta)
    }

    /** Create an independent copy of `this`, which retains the state. (I.e. create a "deep-copy" of `this`) */
    fun copy(): LExpressionFactory {
        return LExpressionFactory(
            constantsFrozen,
            arraysFrozen,
            nonArrayAxiomatizedSymbolsFrozen,
            userDefinedFunctionsFrozen,
            userDefinedAxiomsFrozen,
            uninterpretedSortsFrozen,
            uninterpretedSymbolManager.copy(),
            usedInterpretedFunctionSymbols.toMutableSet(), // makes a copy
            datatypeDeclarations.toMutableSet(),
            usedFeatures,
            LinkedHashMap(defFuncs)
        )
    }

    data class DatatypeDeclaration(
        val sortDecs: List<SortDec>,
        val constructorDecListList: List<List<ConstructorDec>>
    ) {
        data class SortDec(val name: String) {
            fun toSmtLib(
                @Suppress("UNUSED_PARAMETER") toSmtLibData: ToSmtLibData,
                @Suppress("UNUSED_PARAMETER") script: ISmtScript
            ): smtlibutils.data.SortDec =
                toSmtLib()

            fun toSmtLib(): smtlibutils.data.SortDec =
                SortDec(name, 0)
        }

        data class SelectorDec(val fs: FunctionSymbol) {
            val name: String = fs.name
            val sort: Tag = fs.signature.resultSort
        }

        data class ConstructorDec(val fs: FunctionSymbol, val selectors: List<SelectorDec>) {
            val name = fs.name
            fun toSmtLib(toSmtLibData: ToSmtLibData, @Suppress("UNUSED_PARAMETER") script: ISmtScript): DatatypeConstructorDec =
                DatatypeConstructorDec(name,
                    selectors.map {
                        SortedVar(
                            it.name,
                            tagToSort(it.sort, toSmtLibData)
                        )
                    }
                )
        }

        fun toSmtLib(toSmtLibData: ToSmtLibData, script: ISmtScript): Cmd.DeclareDatatypes {
            return script.declareDatatypes(
                sortDecs = sortDecs.map { it.toSmtLib(toSmtLibData, script) },
                constructorDecListList =
                constructorDecListList.map { li -> li.map { dec -> dec.toSmtLib(toSmtLibData, script) } }
            )
        }

        fun transform(f: (FunctionSymbol) -> FunctionSymbol) = copy(
            constructorDecListList = constructorDecListList.map { cdl ->
                cdl.map { cdec ->
                    cdec.copy(
                        fs = f(cdec.fs),
                        selectors = cdec.selectors.map { it.copy(fs = f(it.fs)) }
                    )
                }
            }
        )
    }

    fun registerDatatypeDeclaration(dtDecl: DatatypeDeclaration) {

        dtDecl.constructorDecListList.forEach { constructorDecList ->
            constructorDecList.forEach { constructorDec ->
                registerFunctionSymbol(constructorDec.fs)
                constructorDec.selectors.forEach { sel -> registerFunctionSymbol(sel.fs) }
            }
        }

        datatypeDeclarations.add(dtDecl)
    }

    fun getDatatypeDeclarations(): Set<DatatypeDeclaration> {
        return datatypeDeclarations
    }

    companion object {
        /**
         * What [Sort] we want to translate values that have an integer type in TAC to
         * (corresponding to uint256, int256, etc).
         */
        fun getSortOfIntValues(targetTheory: SmtTheory): Sort =
            if (targetTheory.arithmeticOperations == SmtTheory.ArithmeticOperations.BitVector) {
                Sort.BV256Sort
            } else {
                Sort.IntSort
            }

        fun getSortOfStorageKeyValues(targetTheory: SmtTheory, hashingScheme: HashingScheme): Sort =
            when (hashingScheme) {
                is HashingScheme.Legacy,
                HashingScheme.PlainInjectivity -> getSortOfIntValues(targetTheory)

                HashingScheme.Datatypes -> Sort.SKeySort
            }


        fun tagToSort(tag: Tag, toSmtLibData: ToSmtLibData): Sort =
            tagToSort(
                tag,
                getSortOfIntValues(toSmtLibData.targetTheory),
                getSortOfStorageKeyValues(toSmtLibData.targetTheory, toSmtLibData.hashingScheme)
            )

        /**
         * This is the place where the decision between [Sort.IntSort] and [Sort.BV256Sort] is made.
         * (In particular the decision should not be made as a conversion between [Tag]s, as might be tempting.
         */
        fun tagToSort(tag: Tag, intSort: Sort, storageKeySort: Sort): Sort {
            check(intSort == Sort.BV256Sort || intSort == Sort.IntSort)
            return when (tag) {
                is Tag.Bits -> intSort
                Tag.Bool -> Sort.BoolSort
                Tag.ByteMap -> Sort.arraySort(intSort, intSort)
                Tag.WordMap -> Sort.arraySort(storageKeySort, intSort)
                Tag.Int -> intSort
                is Tag.UserDefined.UninterpretedSort -> Sort.Symbol(
                    SortSymbol.UserDefined(
                        Identifier.Simple(tag.name),
                        0
                    )
                )
                is Tag.UserDefined.Struct,
                is Tag.TransientTag -> error("should not be treating $tag as values in SMT")
                is Tag.GhostMap ->
                    Sort.arraySort(
                        tag.paramSorts.map { tagToSort(it, intSort, storageKeySort) },
                        tagToSort(tag.resultSort, intSort, storageKeySort)
                    )
            }
        }
    }


    // quantifiers

    fun forall(vs: List<LExpression.Identifier>, exp: () -> LExpression) =
        buildQuantifiedExpr(Quantifier.FORALL, vs, exp())

    fun forall(vararg vs: LExpression.Identifier, exp: () -> LExpression) =
        forall(vs.asList(), exp)

    fun forall(vs: List<LExpression.Identifier>, exp: LExpression) =
        buildQuantifiedExpr(Quantifier.FORALL, vs, exp)

    fun exists(vs: List<LExpression.Identifier>, exp: () -> LExpression) =
        buildQuantifiedExpr(Quantifier.EXISTS, vs, exp())

    fun exists(vararg vs: LExpression.Identifier, exp: () -> LExpression) =
        exists(vs.asList(), exp)

    // non-apply

    infix fun LExpression.eq(other: LExpression) =
        eq(this, other)

    @JvmName("eq1") // otherwise there is a name clash with the infix version
    fun eq(l: LExpression, r: LExpression, meta: MetaMap? = null): LExpression {
        return applyExp(TheoryFunctionSymbol.Binary.Eq(commonSuperTag(l.tag, r.tag)), listOf(l, r), meta)
    }

    infix fun LExpression.neq(other: LExpression) =
        !(this eq other)

    infix fun LExpression.implies(other: LExpression): LExpression =
        applyExp(TheoryFunctionSymbol.Binary.LImplies, this, other)

    @JvmName("implies1")
    fun implies(l: LExpression, r: LExpression) = l implies r

    fun impliesFlatten(l: LExpression, r: LExpression): LExpression =
        if (r.isApplyOf<TheoryFunctionSymbol.Binary.LImplies>()) {
            // r is an implication (`rl => rr`); return the implication `l /\ rl => rr`
            val (rl, rr) = r.args
            // note that in case rl is already a conjunction it is also flattened
            (l andSimp rl) implies rr
        } else {
            l implies r
        }


    infix fun LExpression.andSimp(other: LExpression) =
        and(this, other, meta = null, simplify = true)

    infix fun LExpression.and(other: LExpression) =
        and(this, other, meta = null)

    infix fun LExpression.or(other: LExpression) =
        or(this, other, meta = null)


    fun assignEq(lhs: LExpression, rhs: LExpression) =
        applyExp(NonSMTInterpretedFunctionSymbol.Binary.AssignEq(commonSuperTag(lhs.tag, rhs.tag)), lhs, rhs)

    @Suppress("unused")
    @JvmName("assignEq1")
    infix fun LExpression.assignEq(other: LExpression) = assignEq(this, other)

    fun ite(i: LExpression, t: LExpression, e: LExpression, meta : MetaMap? = null) =
        applyExp(TheoryFunctionSymbol.Ternary.Ite(
            listOf(t.tag, e.tag).let { it.commonBitsOrInt() ?: it.commonTag() }
        ), i, t, e, meta = meta)

    infix fun LExpression.safeMathNarrow(to: Tag.Bits) =
        applyExp(NonSMTInterpretedFunctionSymbol.Unary.SafeMathNarrow(to), this)

    // override operators
    // Unfortunately we cannot override other operators because they are quite limited in Kotlin.

    operator fun LExpression.not() =
        applyExp(TheoryFunctionSymbol.Unary.LNot, this)

    operator fun LExpression.plus(other: LExpression) =
        applyExp(TheoryFunctionSymbol.Vec.IntAdd, this, other)

    operator fun LExpression.plus(other: Int) = this + litInt(other)
    operator fun Int.plus(other: LExpression) = litInt(this) + other

    operator fun LExpression.minus(other: LExpression) =
        applyExp(TheoryFunctionSymbol.Binary.IntSub, this, other)

    operator fun LExpression.minus(other: Int) = this - litInt(other)
    operator fun Int.minus(other: LExpression) = litInt(this) - other

    operator fun LExpression.times(other: LExpression) =
        applyExp(TheoryFunctionSymbol.Vec.IntMul.IntMulAllowNormalize, this, other)

    operator fun LExpression.times(other: Int) = this * litInt(other)
    operator fun Int.times(other: LExpression) = litInt(this) * other

    operator fun LExpression.div(other: LExpression) =
        applyExp(TheoryFunctionSymbol.Binary.IntDiv.IntDivAllowNormalize, this, other)

    operator fun LExpression.div(other: Int) = this / litInt(other)
    operator fun Int.div(other: LExpression) = litInt(this) / other

    operator fun LExpression.rem(other: LExpression) =
        applyExp(TheoryFunctionSymbol.Binary.IntMod.IntModAllowNormalize, this, other)

    operator fun LExpression.rem(other: Int) = this % litInt(other)
    operator fun Int.rem(other: LExpression) = litInt(this) % other

    @JvmName("not1")
    fun not(exp: LExpression) = !exp

    // apply

    @Suppress("unused")
    infix fun LExpression.uninterpDiv(other: LExpression) =
        applyExp(AxiomatizedFunctionSymbol.UninterpDiv, this, other)

    infix fun LExpression.uninterpMod(other: LExpression) =
        applyExp(AxiomatizedFunctionSymbol.UninterpMod, this, other)

    infix fun LExpression.uninterpMul(other: LExpression) =
        applyExp(AxiomatizedFunctionSymbol.UninterpMul, this, other)

    infix fun LExpression.uninterpExp(other: LExpression) =
        applyExp(AxiomatizedFunctionSymbol.UninterpExp(commonBitsOrInt(this, other)), this, other)

    infix fun LExpression.mul(other: LExpression) =
        applyExp(NonSMTInterpretedFunctionSymbol.Vec.Mul(commonBitsTag(this, other)), this, other)

    infix fun LExpression.sub(other: LExpression) =
        applyExp(NonSMTInterpretedFunctionSymbol.Binary.Sub(commonBitsTag(this, other)), this, other)

    infix fun LExpression.add(other: LExpression) =
        applyExp(NonSMTInterpretedFunctionSymbol.Vec.Add(commonBitsTag(this, other)), this, other)

    infix fun LExpression.intSub(other: LExpression) =
        applyExp(TheoryFunctionSymbol.Binary.IntSub, this, other)

    infix fun LExpression.intAdd(other: LExpression) =
        applyExp(TheoryFunctionSymbol.Vec.IntAdd, this, other)

    @Suppress("unused")
    fun intMul(exps: List<LExpression>) =
        applyExp(TheoryFunctionSymbol.Vec.IntMul.IntMulAllowNormalize, exps)

    @Suppress("unused")
    fun intMul(vararg exps: LExpression) =
        applyExp(TheoryFunctionSymbol.Vec.IntMul.IntMulAllowNormalize, *exps)

    @Suppress("unused")
    infix fun LExpression.intMul(other: LExpression) =
        applyExp(TheoryFunctionSymbol.Vec.IntMul.IntMulAllowNormalize, this, other)

    infix fun LExpression.intMulDontNorm(other: LExpression) =
        applyExp(TheoryFunctionSymbol.Vec.IntMul.IntMulDontNormalize, this, other)

    infix fun LExpression.intModDontNorm(other: LExpression) =
        applyExp(TheoryFunctionSymbol.Binary.IntMod.IntModDontNormalize, this, other)

    infix fun LExpression.intDivDontNorm(other: LExpression) =
        applyExp(TheoryFunctionSymbol.Binary.IntDiv.IntDivDontNormalize, this, other)

    @Suppress("unused")
    infix fun LExpression.intDiv(other: LExpression) =
        applyExp(TheoryFunctionSymbol.Binary.IntDiv.IntDivAllowNormalize, this, other)

    infix fun LExpression.intMod(other: LExpression) =
        applyExp(TheoryFunctionSymbol.Binary.IntMod.IntModAllowNormalize, this, other)

    infix fun LExpression.intLe(other: LExpression) =
        applyExp(TheoryFunctionSymbol.Binary.IntLe, this, other)

    infix fun LExpression.intLt(other: LExpression) =
        applyExp(TheoryFunctionSymbol.Binary.IntLt, this, other)

    infix fun LExpression.intGe(other: LExpression) =
        applyExp(TheoryFunctionSymbol.Binary.IntGe, this, other)

    infix fun LExpression.intGt(other: LExpression) =
        applyExp(TheoryFunctionSymbol.Binary.IntGt, this, other)

    infix fun LExpression.slt(other: LExpression) =
        applyExp(NonSMTInterpretedFunctionSymbol.Binary.Slt(commonBitsTag(this, other)), this, other)

    infix fun LExpression.sle(other: LExpression) =
        applyExp(NonSMTInterpretedFunctionSymbol.Binary.Sle(commonBitsTag(this, other)), this, other)

    infix fun LExpression.sgt(other: LExpression) =
        applyExp(NonSMTInterpretedFunctionSymbol.Binary.Sgt(commonBitsTag(this, other)), this, other)

    infix fun LExpression.sge(other: LExpression) =
        applyExp(NonSMTInterpretedFunctionSymbol.Binary.Sge(commonBitsTag(this, other)), this, other)

    infix fun LExpression.lt(other: LExpression) =
        applyExp(NonSMTInterpretedFunctionSymbol.Binary.Lt, this, other)

    infix fun LExpression.le(other: LExpression) =
        applyExp(NonSMTInterpretedFunctionSymbol.Binary.Le, this, other)

    infix fun LExpression.gt(other: LExpression) =
        applyExp(NonSMTInterpretedFunctionSymbol.Binary.Gt, this, other)

    infix fun LExpression.ge(other: LExpression) =
        applyExp(NonSMTInterpretedFunctionSymbol.Binary.Ge, this, other)

    /** Returns the LExpression for checking if [this] is a non-negative signed int w.r.t. [tag] */
    fun LExpression.is2sNonNeg(tag: Tag.Bits) =
        this intLe litInt(tag.maxSigned)

    fun LExpression.is2sPos(tag: Tag.Bits) =
        is2sNonNeg(tag) and not(this eq ZERO)

    /** Returns the LExpression for checking if [this] is a signed int w.r.t. [tag] */
    fun LExpression.is2sNeg(tag: Tag.Bits) =
        this intGe litInt(tag.minSigned2s)

    fun areOfSameSign(e1: LExpression, e2: LExpression, tag: Tag.Bits) =
        e1.is2sNonNeg(tag) eq e2.is2sNonNeg(tag)

    fun LExpression.from2s(tag: Tag.Bits) =
        if (isConst) {
            litInt(asConst.from2s(tag))
        } else {
            ite(is2sNonNeg(tag), this, this - litInt(tag.modulus))
        }

    fun uninterpTagMod(other: LExpression, tag : Tag.Bits) =
        applyExp(AxiomatizedFunctionSymbol.UninterpTagMod(tag), other)

    // bitvectors

    @Suppress("unused")
    fun bvNot(exp: LExpression) =
        applyExp(TheoryFunctionSymbol.BV.BvNot(exp.tag as Tag.Bits), exp)

    fun bvNeg(exp: LExpression) =
        applyExp(TheoryFunctionSymbol.BV.BvNeg(exp.tag as Tag.Bits), exp)

    infix fun LExpression.bvAnd(other: LExpression) =
        applyExp(TheoryFunctionSymbol.BV.BvAnd(commonBitsTag(this, other)), this, other)

    infix fun LExpression.bvConcat(other: LExpression) =
        applyExp(TheoryFunctionSymbol.BV.BvConcatTwo256s, this, other)

    infix fun LExpression.bvOr(other: LExpression) =
        applyExp(TheoryFunctionSymbol.BV.BvOr(commonBitsTag(this, other)), this, other)

    @Suppress("unused")
    infix fun LExpression.bvXOr(other: LExpression) =
        applyExp(TheoryFunctionSymbol.BV.BvXOr(commonBitsTag(this, other)), this, other)

    infix fun LExpression.bvAdd(other: LExpression) =
        applyExp(TheoryFunctionSymbol.BV.BvAdd(commonBitsTag(this, other)), this, other)

    infix fun LExpression.bvMul(other: LExpression) =
        applyExp(TheoryFunctionSymbol.BV.BvMul(commonBitsTag(this, other)), this, other)

    infix fun LExpression.bvUDiv(other: LExpression) =
        applyExp(TheoryFunctionSymbol.BV.BvUDiv(commonBitsTag(this, other)), this, other)

    infix fun LExpression.bvSDiv(other: LExpression) =
        applyExp(TheoryFunctionSymbol.BV.BvSDiv(commonBitsTag(this, other)), this, other)

    infix fun LExpression.bvUGe(other: LExpression) =
        applyExp(TheoryFunctionSymbol.BV.BvUGe(commonBitsTag(this, other)), this, other)

    @Suppress("unused")
    infix fun LExpression.bvULe(other: LExpression) =
        applyExp(TheoryFunctionSymbol.BV.BvULe(commonBitsTag(this, other)), this, other)

    @Suppress("unused")
    infix fun LExpression.bvUGt(other: LExpression) =
        applyExp(TheoryFunctionSymbol.BV.BvUGt(commonBitsTag(this, other)), this, other)

    infix fun LExpression.bvULt(other: LExpression) =
        applyExp(TheoryFunctionSymbol.BV.BvULt(commonBitsTag(this, other)), this, other)

    infix fun LExpression.bvSGe(other: LExpression) =
        applyExp(TheoryFunctionSymbol.BV.BvSGe(commonBitsTag(this, other)), this, other)

    infix fun LExpression.bvSGt(other: LExpression) =
        applyExp(TheoryFunctionSymbol.BV.BvSGt(commonBitsTag(this, other)), this, other)

    infix fun LExpression.bvSLe(other: LExpression) =
        applyExp(TheoryFunctionSymbol.BV.BvSLe(commonBitsTag(this, other)), this, other)

    infix fun LExpression.bvSLt(other: LExpression) =
        applyExp(TheoryFunctionSymbol.BV.BvSLt(commonBitsTag(this, other)), this, other)

    @Suppress("unused")
    infix fun LExpression.bvURem(other: LExpression) =
        applyExp(TheoryFunctionSymbol.BV.BvURem(commonBitsTag(this, other)), this, other)

    infix fun LExpression.bvShl(other: LExpression) =
        applyExp(TheoryFunctionSymbol.BV.BvShl(commonBitsTag(this, other)), this, other)

    infix fun LExpression.bitwiseOr(other: LExpression) =
        applyExp(NonSMTInterpretedFunctionSymbol.Binary.BitwiseOr(commonBitsOrInt(this, other)), this, other)

    infix fun LExpression.bitwiseAnd(other: LExpression) =
        applyExp(NonSMTInterpretedFunctionSymbol.Binary.BitwiseAnd(commonBitsOrInt(this, other)), this, other)

    infix fun LExpression.bitwiseXor(other: LExpression) =
        applyExp(NonSMTInterpretedFunctionSymbol.Binary.BitwiseXor(commonBitsOrInt(this, other)), this, other)

    fun bitwiseNot(expr: LExpression) =
        applyExp(NonSMTInterpretedFunctionSymbol.Unary.BitwiseNot(expr.tag as Tag.Bits), expr)

    infix fun LExpression.signExt(i: Int) =
        applyExp(AxiomatizedFunctionSymbol.Bitwise.SignExtend(i, this.tag), this)

    infix fun LExpression.signExt(other: LExpression) =
        other.asConstOrNull?.toIntOrNull()
            ?.let { this signExt it }
            ?: applyExp(AxiomatizedFunctionSymbol.Bitwise.BinarySignExtend(tag), this, other)

    infix fun LExpression.shl(other: LExpression) =
        applyExp(NonSMTInterpretedFunctionSymbol.Binary.ShiftLeft(tag as Tag.Bits), this, other)

    /** Use this to give the shift amount in number of bytes rather than bits. */
    infix fun LExpression.shlBytes(other: LExpression) = shl(8 * other)

    infix fun LExpression.shr(other: LExpression) =
        applyExp(NonSMTInterpretedFunctionSymbol.Binary.ShiftRightLogical(tag), this, other)

    /** Use this to give the shift amount in number of bytes rather than bits. */
    infix fun LExpression.shrBytes(other: LExpression) = shr(8 * other)

    fun extract(l: Int, r: Int, op: LExpression): LExpression {
        check(l >= 0 && r >= 0) { "can't initialize `extract` with negative indices (got \"$l\", \"$r\")" }
        return applyExp(NonSMTInterpretedFunctionSymbol.Unary.Extract(l, r), op)
    }

    // datatypes

    infix fun LExpression.`is`(fs: AxiomatizedFunctionSymbol.SKeyDt) =
        applyExp(TheoryFunctionSymbol.Unary.Is(fs), this)

    // extra convenience methods

    /**
     * including [lowerBound] and excluding [upperBound]
     */
    fun LExpression.within(lowerBound: LExpression, upperBound: LExpression) =
        (this intGe lowerBound) and (this intLt upperBound)

    /** Includes both [lowerBound] and [upperBound] */
    fun LExpression.inclusiveWithin(lowerBound: LExpression, upperBound: LExpression) =
        (this intGe lowerBound) and (this intLe upperBound)


    /**
     * A convenience function for creating switch-case expression (`when` in kotlin). It translates to a series of ite
     * expressions.
     *
     * @param elseExpr Since it is after the vararg, it must be used with its name, e.g., switch(..., elseExpr = NUM(3))
     */
    fun switch(vararg condsToExprs: Pair<LExpression, LExpression>, elseExpr: LExpression): LExpression {
        var result = elseExpr
        condsToExprs.reversed().forEach { (cond, expr) ->
            result = ite(cond, expr, result)
        }
        return result
    }


    // constants

    /** Create a literal with the given value and tag (cached). */
    val litCache = Memoized2(concurrent = false) { value: BigInteger, tag: Tag ->
        LExpression.Literal(value, tag)
    }

    fun lit(value : BigInteger, tag: Tag) = litCache(value, tag)
    fun lit(value: Long, tag: Tag) = litCache(value.toBigInteger(), tag)
    fun lit(value: Int, tag: Tag) = litCache(value.toBigInteger(), tag)

    fun litInt(value : BigInteger) = lit(value, Tag.Int)
    fun litInt(value : String) = lit(value.toBigInteger(), Tag.Int)
    fun litInt(value : Long) = lit(value, Tag.Int)
    fun litInt(value : Int) = lit(value, Tag.Int)

    fun lit256(value: BigInteger) = lit(value, Tag.Bit256)
    fun lit256(value: Long) = lit(value, Tag.Bit256)
    fun lit256(value: Int) = lit(value, Tag.Bit256)

    /** Create a boolean literal */
    fun litBool(value: BigInteger) = litCache(value, Tag.Bool)
    fun litBool(value: Boolean) = litBool(value.asBigInteger)

    val ZERO = litInt(0)
    val ONE = litInt(1)
    val TWO = litInt(2)

    val TwoTo256Tagged = Memoized { tag: Tag -> lit(EVM_MOD_GROUP256, tag) }
    val TwoTo256 = TwoTo256Tagged(Tag.Int)

    val MAX_UINT = litInt(MAX_EVM_UINT256)
    val MAX_INT = litInt(MAX_EVM_INT256)
    val MIN_INT = litInt(MIN_EVM_INT256_AS_MATH_INT)
    val MIN_INT_ABS = litInt(MIN_EVM_INT256_2S_COMPLEMENT)

    fun twoTo(i: Int) = litInt(BigInteger.TWO.pow(i))

    val TRUE = LExpression.Literal(true)

    val FALSE = LExpression.Literal(false)
}


/**
 * Features of an verification condition in [LExpression] format that may help e.g. with solver selection.
 *
 * NB, the feature definitions are made with the specific purpose of SMT solving in mind -- for example bitwise
 * operations are non linear arithmetic operations for many purposes but in the SMT context, bitwise operations aren't
 * best treated by a NIA solver but by a BV solver, so we separate the two here.
 */
enum class VcFeature {
    Hashing,
    /** constants larger than max_uint256 */
    NonLinearArithmetic,
    BitwiseOperations,
    Quantification,
    MultiDimGhost,
    LongStore,
    /** We track multiplicative overflow checks using special function symbols
     * [NonSMTInterpretedFunctionSymbol.Binary.NoMulOverflow] etc.
     * Z3 has primitives for these in the BV case, the others don't.
     * NB: we don't include additive overflow checks here because those are easy to check, even in BV mode. */
    MulOverflowChecks
}


