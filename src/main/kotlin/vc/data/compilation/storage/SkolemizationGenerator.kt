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

package vc.data.compilation.storage

import analysis.CommandWithRequiredDecls
import analysis.merge
import datastructures.stdcollections.*
import spec.cvlast.StorageBasis
import tac.MetaMap
import tac.Tag
import vc.data.*

/**
 * Generates map equality comparisons via skolemization. Note that this strategy *only* works if the storage comparison
 * is an equality comparison (it doesn't work for inequality) and if the equality is at a top level. It is the CVL
 * type checker's job to check this latter condition, and set the [spec.cvlast.ComparisonType.trySkolem]
 * field appropriately. However, even if that flag is set, if the storage comparison is an inequality, this class will
 * not be used.
 *
 * To assert two maps m1 and m2 are equal, this class generates the following (in pseudo-code):
 * ```
 * witness = *
 * e1 = m1[witness]
 * e2 = m2[witness]
 * assert e1 == e2
 * ```
 *
 * How does this work? Given the encoding of assertions as logical formulae, we ultimately assert that some property
 * of a program does *not* hold, and then look for unsatisfiability of that equation. In other words, we actually
 * check:
 * ```
 * witness = *
 * e1 = m1[witness]
 * e2 = m2[witness]
 * assert e1 != e2
 * ```
 * What does it mean if this formula is now unsatisfiable. That means that the SMT solver checked *every* possible
 * value of `witness` and coudln't find any possible value for which `m1[witness] != m2[witness]`. In other words,
 * at every single key/index, the value of m1 was the same at m2, that is, the maps were equal.
 *
 * This is not a novel encoding, and falls out of the fact that all top-level declarations are implicitly existentially
 * quantified, and from the fact that the negation of a forall is an exists. To come at this a different way,
 * we could encode the property directly as:
 * ```
 * assert forall witness.m1[witness] == m2[witness]`
 * ```
 * (and indeed, we do exactly this in the [QuantifierGenerator])
 * Recall however that we want the unsatisfiability of the negation of this assertion, so we get:
 * ```
 * assert !forall witness.m1[witness] == m2[witness]
 * ```
 * which is equivalent to
 * ```
 * assert exists witness.m1[witness] != m2[witness]
 * ```
 * An exists like this can be lifted to an assign havoc (this is the "skolemization" part), yielding:
 * ```
 * witness = *
 * assert m1[witness] != m2[witness]
 * ```
 * Which is what we are indeed generating.
 *
 * The benefit of doing this encoding directly ourselves (instead of relying on the quantifier grounding pass)
 * is twofold:
 * 1. It can work even if the user turns off grounding for some reason
 * 2. We can directly encode information into the generated code (via [SnippetCmd]) to show nicer counterexamples
 *
 * Q: Why doesn't this direct encoding work with inequality?
 * A: Short answer: because inequality ultimately turns into a forall (instead of an exists) which means
 * we can't use the same trick.
 *
 * Long answer: let's try it, and see what we're ultimately asking the solver to do:
 * ```
 * witness = *
 * e1 = m1[witness]
 * e2 = m2[witness]
 * assert e1 == e2
 * ```
 *
 * What does it mean if the solver gives us a SAT result here. It means that the solver was able to find a *single*
 * index/key at which the maps had the same value. Does that mean the assertion is violated? Not necesarily,
 * just because the maps had the same value at a single index, that doesn't mean that the maps aren't unequal. For example,
 * if every other index besides `witness` had different value, then clearly the maps are overall unequal. Put another way, to show two
 * maps are unequal you need only find a single index at which they differ, to show they are equal you have to
 * show they *must* be the same at *all* indices (this is the forall vs exists problem mentioned above);
 * recall that we invert the users condition in smt encoding, which is why for a user-level assertion about equality
 * we look for a proof of inequality, and vice versa for user-level assertion about inequality.
 */
@InternalCVLCompilationAPI
internal object SkolemizationGenerator : StorageComparisonDispatcher {
    /**
     * Generic version of skolemized map comparison.
     * [p] is a list of the tags to be used for the skolemized parameters to the maps being compared, which are
     * passed in via [m1] and [m2]. The result of these map lookup must have the same "type", which is
     * given by [elemTag]. The result of the comparison (which must be equality, see above) is stored in [outputVar].
     * The actual reading of an element out of [m1] and [m2] are delegated to [gen]. This function takes:
     * 1. a list of skolemized variables (which is guanranteed to be the same artiy as [p], and where
     * each variable has the tag in the corresponding position in [p])
     * 2. the base map (one of [m1] or [m2]), and
     * 3. the output variable to hold the value read out of the map. This output variable is guaranteed to have
     * the tag [elemTag].
     *
     * Then [gen] function must return a single command: it may not introduce new variables or other commands.
     *
     * Generating snippet commands for counter example display is delegated to [genAnnot], a function which
     * takes:
     * 1. The list of skolemized parameters (guaranteed to be the same as those passed to [gen] and satisfying the same properties)
     * 2. The variable which holds the value read out of [m1]
     * 3. The variable which holds the value read out of [m2]
     *
     * We don't pass along [outputVar] or [m1] or [m2] because it is assumed that the caller, having passed those parameters
     * to this function, has access to those variables in the scope which declares the callback function.
     *
     * The callback should return whatever commands are necessary to record counter example information in snippets,
     * introducing new variables/BIFs as necessary.
     *
     * The result of these operations are concatenated together and returned.
     */
    private fun generateMapComparison(
        p: List<Tag>,
        m1: TACSymbol.Var,
        m2: TACSymbol.Var,
        elemTag: Tag,
        outputVar: TACSymbol.Var,
        gen: (params: List<TACSymbol.Var>, base: TACSymbol.Var, lhs: TACSymbol.Var) -> TACCmd.Simple,
        genAnnot: (params: List<TACSymbol.Var>, e1: TACSymbol.Var, e2: TACSymbol.Var) -> CommandWithRequiredDecls<TACCmd.Simple>
    ) : CommandWithRequiredDecls<TACCmd.Simple> {
        check(m1.tag == m2.tag)
        val skolemVars = p.mapIndexed { ind, tag ->
            TACKeyword.TMP(tag, "!witness$ind").toUnique("!")
        }
        val e1 = TACKeyword.TMP(tag = elemTag, "!elem1").toUnique("!")
        val e2 = TACKeyword.TMP(tag = elemTag, "elem2").toUnique("!")
        return CommandWithRequiredDecls(skolemVars.map {
            TACCmd.Simple.AssigningCmd.AssignHavocCmd(lhs = it)
        } + gen(skolemVars, m1, e1) + gen(skolemVars, m2, e2) + listOf(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = outputVar,
                rhs = TACExpr.BinRel.Eq(e1.asSym(), e2.asSym())
            )
        ), skolemVars.toSet() + e1 + e2 + outputVar + m1 + m2).merge(genAnnot(
            skolemVars, e1, e2
        ))
    }

    /**
     * Generate a ghost map comparison via skolemization. The variables in [fv1] and [fv2] are the ghost maps
     * at the different storages.
     */
    override fun generateGhostMapComparison(
        eq: Boolean,
        output: TACSymbol.Var,
        fv1: Pair<String, TACSymbol.Var>,
        fv2: Pair<String, TACSymbol.Var>,
        storageBasis: StorageBasis.Ghost
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        check(eq) {
            "Attempted to use skolemization version for inequality of $fv1 and $fv2"
        }
        val v1 = fv1.second
        val v2 = fv2.second
        val vTag = v1.tag
        check(vTag is Tag.GhostMap && v1.tag == v2.tag) {
            "Unexpected shape of tags for ghost map comparison $v1 with ${v1.tag} vs $v2 with ${v2.tag}"
        }
        return generateMapComparison(
            p = vTag.paramSorts,
            m1 = v1,
            m2 = v2,
            outputVar = output,
            elemTag = vTag.resultSort,
            gen = { params, base, lhs ->
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = lhs,
                    rhs = TACExpr.Select.buildMultiDimSelect(
                        base = base.asSym(),
                        params.map {
                            it.asSym()
                        }
                    )
                )
            }
        ) { params: List<TACSymbol.Var>, e1: TACSymbol.Var, e2: TACSymbol.Var ->
            check(params.size == storageBasis.params.size) {
                "Mismatched tag sizes $params vs ${storageBasis.params}"
            }
            CommandWithRequiredDecls(
                SnippetCmd.CVLSnippetCmd.GhostWitnessComparison(
                    parameters = params,
                    resultSymbol = output,
                    p1 = fv1.first to e1,
                    p2 = fv2.first to e2,
                    basis = storageBasis
                ).toAnnotation()
            )
        }
    }

    /**
     * Generates a word map comparison.
     */
    override fun generateWordMapComparison(
        eq: Boolean,
        output: TACSymbol.Var,
        fv1: Pair<String, TACSymbol.Var>,
        fv2: Pair<String, TACSymbol.Var>,
        storageBasis: StorageBasis.VMStorageBasis
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        val v1 = fv1.second
        val v2 = fv2.second
        check(eq) {
            "Attempted to use skolemization version on inequality check on $fv1 and $fv2"
        }
        check(v1.tag == Tag.WordMap && v2.tag == Tag.WordMap)
        return generateMapComparison(
            p = listOf(Tag.Bit256),
            m1 = fv1.second,
            m2 = fv2.second,
            elemTag = Tag.Bit256,
            outputVar = output,
            gen = { params, base, lhs ->
                check(params.size == 1) {
                    "Unexpected arity (!= 1) of parameters for $fv1 vs $fv2: $params"
                }
                TACCmd.Simple.AssigningCmd.WordLoad(
                    lhs = lhs,
                    base = base,
                    loc = params.first(),
                    meta = MetaMap(TACMeta.CVL_STORAGE_COMPARISON)
                )
            }
        ) { params: List<TACSymbol.Var>, e1: TACSymbol.Var, e2: TACSymbol.Var ->
            /**
             * This is a little more complicated than the ghost map case. Here, we want to capture the witness
             * storage key (for parsing out counterexample paths):
             *
             * ```
             * witness = *
             * to_capture = ToStorageKey(witness)
             * e1 = m1[witness]
             * e2 = m2[witness]
             * ```
             *
             * [vc.data.TACBuiltInFunction.ToStorageKey] is translated as the identity function; it only exists so
             * that counterexample-related code can recognize `to_capture` as a storage-comparison witness.
             *
             * Q: Why not use the value of `ToStorageKey` directly as the key to `m1` and `m2`?
             * A: I was not courageous enough to try that.
             */
            check(params.size == 1) {
                "Unexpected arity (!= 1) of parameters for $fv1 vs $fv2: $params"
            }
            val skolemVar = params.single()
            val skeyWitness = TACKeyword.TMP(tag = Tag.Bit256, "!witnessSkey").toUnique("!")
            CommandWithRequiredDecls(
                listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = skeyWitness,
                        rhs = TACExpr.Apply.invoke(
                            TACBuiltInFunction.ToStorageKey,
                            listOf(skolemVar.asSym()),
                            Tag.Bit256
                        )
                    ),
                    SnippetCmd.CVLSnippetCmd.StorageWitnessComparison(
                        typeValue = v1.meta.find(TACMeta.STORAGE_TYPE),
                        p1 = fv1.first to e1,
                        p2 = fv2.first to e2,
                        resultSymbol = output,
                        basis = storageBasis,
                        baseMap = v1,
                        witnessSymbol = skeyWitness
                    ).toAnnotation(),
                ), skeyWitness, e1, e2, output, skolemVar
            )
        }
    }
}
