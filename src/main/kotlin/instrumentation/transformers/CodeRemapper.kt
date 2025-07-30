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

package instrumentation.transformers

import allocator.Allocator
import com.certora.collect.*
import instrumentation.transformers.CodeRemapper.*
import instrumentation.transformers.CodeRemapper.BlockRemappingStrategy.RemappingContext
import instrumentation.transformers.CodeRemapper.BlockRemappingStrategy.RemappingContext.BLOCK_ID
import instrumentation.transformers.CodeRemapper.BlockRemappingStrategy.RemappingContext.SUCCESSOR
import instrumentation.transformers.CodeRemapper.IdRemapperGenerator.Companion.forId
import tac.CallId
import tac.MetaMap
import tac.NBId
import utils.uncheckedAs
import vc.data.*
import vc.data.tacexprutil.DefaultTACExprTransformer
import java.io.Serializable

/**
 * Utility class for consistently remapping variables, IDs, blocks, etc.
 *
 * The exact strategy for remapping these entities is parameteried by four strategies,
 * the [blockRemapper], [idRemapper], [callIndexStrategy], and [variableMapper]. Each of these remappers are "functional"
 * interfaces, and all receive a "state" parameter of type [T]. It is the implementor's responsibility to ensure that remapping
 * is consistent w.r.t. a single state: indeed, this is the purpose of the remapping state.
 *
 * NB that remapping operations in different states will not necessarily yield the same result.
 *
 * ## On the handling of variables
 *
 * For convenience, variables are mapped via the cooperation of the [VariableRemapper], [CallIndexStrategy], and the [idRemapper].
 * A variable v encountered during remapping is transformed according to the following pipeline:
 * 1. The variable's call index is updated according to [CallIndexStrategy.remapVariableCallIndex]
 * 2. The (call index remapped) variable is mapped according to the custom remapper specified in [VariableRemapper]
 * 3. The metamap of the variable produced by step 2 is remapped according to meta mapping rules of this class.
 *
 * Note that this class does not make any attempt to account for fresh variables generated during this process: if
 * the [TACSymbolTable] of a [TACProgram] needs to be updated to reflect these new variables, it is the user's responsibility
 * to record that information during remapping (typically with a mutable set in the state type and a custome variable mapper)
 *
 * ## Remapping Meta/Annotations/Summaries
 *
 * By default, this implementation will remap variables within instances of [TransformableEntity] and [TACSummary]
 * according to the variable mapping scheme above. In addition, the blocks within a [TACSummary] are mapped according to
 * the [BlockRemappingStrategy]. Custom summary remapping can be achieved by overriding the [mapSummary] function.
 *
 * In addition, this class will automatically remap any instance of [UniqueIdEntity] according to the [IdRemapperGenerator].
 * Further, meta values that can be remapped according to the [MetaRemapper] are also automatically remapped.
 *
 * ## Remapping Call Ids
 *
 * Unlike all other unique IDs, Call ids are the only "built-in" id sequence that [CodeRemapper] reasons about. Further,
 * it has traditionally been the ID sequence where the most special handling logic is required. This class promises that
 * *every* call index, those that appear in Metas and block IDs and variables will be remapped via the [callIndexStrategy]
 * fields [CallIndexStrategy.remapCallIndex] class. The "fresh ID generation" callback passed to the
 * [CallIndexStrategy.remapCallIndex] function itself delegates to the remap id function of the [idRemapper] object.
 * In other words, when remapping the IDs of a [UniqueIdEntity] this class uses the following conceptual mapper:
 *
 * ```
 * { sequenceId, id, gen ->
 * if(sequenceId == CALL_ID) {
 *    callIndexStrategy.remapCallIndex(state, id, { toRemap -> idRemapper.createRemapper(state)(CALL_ID, toRemap) {
 *       Allocator.getFreshId(CALL_ID)
 *    }
 *    }
 * } else {
 *    idRemapper.createRemapper(state)(sequenceId, id, gen)
 * }
 * }
 * ```
 */
open class CodeRemapper<T> (
    private val blockRemapper: BlockRemappingStrategy<T>,
    private val idRemapper: IdRemapperGenerator<T>,
    private val callIndexStrategy: CallIndexStrategy<T>,
    private val variableMapper: VariableRemapper<T>,
) {

    /**
     * Describes how to remap blocks within some state and *context* [RemappingContext]
     */
    fun interface BlockRemappingStrategy<in W> {
        /**
         * The context describes what "role" the block id
         * being remapped serves: if it is the ID of a block (i.e. in a jumpdest command) the context is [BLOCK_ID].
         * If the block id is a successor (i.e., the destination of a jump cmd) then the context is [SUCCESSOR].
         *
         * The following is a complete accounting of the locations a BlockID can appear during and the context associated
         * with that remapping operation
         * 1. As the target of a JumpiCmd or JumpCmd: [SUCCESSOR]
         * 2. Within a tac summary: [SUCCESSOR]
         * 3. Within a jumpdest command: [BLOCK_ID]
         * 4. Within a [MetaMap]: [SUCCESSOR]
         */
        enum class RemappingContext {
            BLOCK_ID,
            SUCCESSOR
        }

        /**
         * Remaps the block [nbId] in the context [ctxt] with state [state]. [remapCallIndex] is a convenience function,
         * which maps its argument call index according to the [CallIndexStrategy.remapCallIndex] function.
         * The call id remapper function passed to the [remapCallIndex] function is a consistent remapper for the
         * [Allocator.Id.CALL_ID] sequence as generated by the [idRemapper] strategy.
         */
        fun remapInState(nbId: NBId, remapCallIndex: (CallId) -> CallId, ctxt: RemappingContext, state: W): NBId

        /**
         * As [remapInState], but may return null
         */
        fun remapOptional(nbId: NBId, remapCallIndex: (CallId) -> CallId, ctxt: RemappingContext, state: W): NBId? =
            remapInState(nbId, remapCallIndex, ctxt, state)
    }

    fun interface CallIndexStrategy<in U> {
        /**
         * In [state], remap the call index associated with [v]. By default, this uses [remapCallIndex] to compute the
         * new call index for the variable [v]. [computeFreshId] is an id remapper for the allocator sequence [Allocator.Id.CALL_ID]
         * as derived from the id remapper returned by [idRemapper]
         */
        fun remapVariableCallIndex(state: U, v: TACSymbol.Var, computeFreshId: (CallId) -> CallId) : TACSymbol.Var {
            return TACSymbol.Var(namePrefix = v.namePrefix, callIndex = remapCallIndex(state, v.callIndex, computeFreshId), tag = v.tag, meta = v.meta)
        }

        /**
         * Given a [state], [callIndex], and a consistent fresh remapper [computeFreshId], return the
         * id to associate with [callIndex]. [computeFreshId] will be the id remapper for the allocator
         * sequence [Allocator.Id.CALL_ID] as derived from the remapper returned by [idRemapper]
         */
        fun remapCallIndex(state: U, callIndex: CallId, computeFreshId: (CallId) -> CallId) : CallId
    }

    fun interface VariableRemapper<in W> {
        /**
         * Remap a variable [v] according to a state [state]. See the [CodeRemapper] class documentation for a description of
         * the variable remapping pipeline
         */
        fun remapVariable(state: W, v: TACSymbol.Var) : TACSymbol.Var
    }

    fun interface IdRemapperGenerator<in W> {
        /**
         * In some [state], return a consistent remapper function as described in the [UniqueIdEntity.mapId]
         * documentation.
         */
        fun createRemapper(state: W) : (Any, Int, () -> Int) -> Int

        companion object {
            /**
             * Given a function [extract] which projects out  from a state of type [T] a mutable map
             * from (sequence name, id) pairs to new ids, return an ID remapper
             * for states of type [T].
             */
            fun <T> generatorFor(extract: (T) -> MutableMap<Pair<Any, Int>, Int>) : IdRemapperGenerator<T> {
                return IdRemapperGenerator<T> { st: T ->
                    val m : MutableMap<Pair<Any, Int>, Int> = extract(st);
                     { p: Any, i: Int, gen ->
                        synchronized(m) {
                            m.getOrPut(p to i, gen)
                        }
                    }
                }
            }

            /**
             * Given a remapper function, give back a remapper function over Ints that remps
             * ids within the sequence [id].
             */
            fun ((Any, Int, () -> Int) -> Int).forId(id: Allocator.Id) : (Int) -> Int = { i ->
                this(id, i) {
                    Allocator.getFreshId(id)
                }
            }
        }
    }

    private inner class Mapper(
        val state: T,
        buildExprMapper: ((DefaultTACCmdSpecMapper) -> DefaultTACExprTransformer)? = null
    ) : DefaultTACCmdSpecMapper() {
        override val exprMapper = buildExprMapper?.let { it(this) } ?: super.exprMapper

        private val baseGenerator = idRemapper.createRemapper(state)
        val generator = { flg: Any, curr: Int, regen: () -> Int ->
            if(flg == Allocator.Id.CALL_ID) {
                callIndexStrategy.remapCallIndex(state, curr, baseGenerator.forId(Allocator.Id.CALL_ID))
            } else {
                baseGenerator.invoke(flg, curr, regen)
            }
        }

        override fun mapAnnotationCmd(
            annotationCmd: TACCmd.Simple.AnnotationCmd,
            annotationPair: TACCmd.Simple.AnnotationCmd.Annotation<*>,
            metaMap: MetaMap,
        ): TACCmd.Simple {
            var v : Serializable = this@CodeRemapper.mapAnnotation(state, annotationPair)
            v = applyTransEntityMappers(v, annotationPair.k, this::mapSymbol, this::mapVar)
            if (v is UniqueIdEntity<*>) {
                v = v.mapId(generator)
            }
            if(v is Int) {
                v = MetaRemapper.remapMeta(mk = annotationPair.k, t = v, f = generator)
            }
            return TACCmd.Simple.AnnotationCmd(
                remakePair(annotationPair, v),
                meta = mapMeta(metaMap)
            )
        }

        private fun <@Treapable T: Serializable> remakePair(p: TACCmd.Simple.AnnotationCmd.Annotation<T>, v: Any) : TACCmd.Simple.AnnotationCmd.Annotation<*> {
            if(!p.k.typ.isInstance(v)) {
                throw IllegalStateException("In transforming a value ${p.v} of type ${p.k.typ}, got ill-typed value $v")
            }
            return TACCmd.Simple.AnnotationCmd.Annotation(
                p.k,
                v.uncheckedAs<T>()
            )
        }

        override fun mapSummary(t: TACSummary): TACSummary {
            /*
             * super.mapSummary will transform the variables.
             */
            return this@CodeRemapper.mapSummary(state, t).let {
                super.mapSummary(it)
            }.let {
                /*
                 * remap the blocks according to the block remapper strat
                 */
                if (it is ConditionalBlockSummary) {
                    it.remapBlocks {
                        blockRemapper.remapOptional(it, state = state, remapCallIndex = getCallIndexRemapper(state), ctxt = SUCCESSOR)
                    }
                } else {
                    it
                }
            }.let {
                /*
                 * if this can be ID remapped, do so
                 */
                if(it is UniqueIdEntity<*>) {
                    it.mapId(generator) as TACSummary
                } else {
                    it
                }
            }.let {
                // custom mapping
                this@CodeRemapper.mapSummary(state, it)
            }
        }

        override fun mapJumpDstCmd(startPC: NBId, metaMap: MetaMap): TACCmd.Simple {
            return TACCmd.Simple.JumpdestCmd(
                startPC = remapBlockId(state, BLOCK_ID, startPC),
                meta = mapMeta(metaMap)
            )
        }

        override fun mapJumpICmd(dst: NBId, cond: TACSymbol, elseDst: NBId, metaMap: MetaMap): TACCmd.Simple {
            return TACCmd.Simple.JumpiCmd(
                dst = remapBlockId(state, SUCCESSOR, dst),
                elseDst = remapBlockId(state, SUCCESSOR, elseDst),
                cond = mapSymbol(cond),
                meta = mapMeta(metaMap)
            )
        }

        override fun mapJumpCmd(dst: NBId, metaMap: MetaMap): TACCmd.Simple {
            return super.mapJumpCmd(remapBlockId(state, SUCCESSOR, dst), mapMeta(metaMap))
        }

        override fun mapVar(t: TACSymbol.Var): TACSymbol.Var {
            return callIndexStrategy.remapVariableCallIndex(state, t, generator.forId(Allocator.Id.CALL_ID)).let {
                variableMapper.remapVariable(state, it)
            }.withMeta(this::mapMeta)
        }

        override fun mapMeta(t: MetaMap): MetaMap {
            return t.updateValues { (k, v) ->
                if (v is Int) {
                    return@updateValues MetaRemapper.remapMeta(k, v, generator)
                }
                if (v is NBId) {
                    return@updateValues remapBlockId(state, SUCCESSOR, v)
                }
                val tr = applyTransEntityMappers(v, k, this::mapSymbol, this::mapVar)
                if (tr is UniqueIdEntity<*>) {
                    tr.mapId(generator)
                } else {
                    tr
                }
            }
        }
    }

    protected open fun <@Treapable U: Serializable> mapAnnotation(state: T, k: TACCmd.Simple.AnnotationCmd.Annotation<U>): U {
        return k.v
    }

    private fun getCallIndexRemapper(state: T) : (CallId) -> CallId = idRemapper.createRemapper(state).let { rm ->
        { id: CallId ->
            callIndexStrategy.remapCallIndex(state, id, rm.forId(Allocator.Id.CALL_ID))
        }
    }

    /**
     * In [state] and [ctxt], return the block id to which [nbId] is remapped
     */
    fun remapBlockId(state: T, ctxt: RemappingContext, nbId: NBId) : NBId {
        return blockRemapper.remapInState(nbId, getCallIndexRemapper(state), ctxt, state)
    }

    /**
     * In [state], give a remappr for [TACCmd.Simple]
     */
    fun commandMapper(state: T) : (TACCmd.Simple) -> TACCmd.Simple = mapper(state)::map

    fun mapper(
        state: T,
        buildExprMapper: ((DefaultTACCmdSpecMapper) -> DefaultTACExprTransformer)? = null
    ): DefaultTACCmdSpecMapper = Mapper(state, buildExprMapper)


    open protected fun mapSummary(state: T, summary: TACSummary) : TACSummary  {
        return summary
    }

    /**
     * Remap ids in [procedure] according to [state].
     */
    fun remapProcedure(state: T, procedure: Procedure): Procedure {
        val f: (Any, Int, () -> Int) -> Int = idRemapper.createRemapper(state)
        return procedure.mapId { k, curr, gen ->
            if(k == Allocator.Id.CALL_ID) {
                callIndexStrategy.remapCallIndex(state, curr, f.forId(Allocator.Id.CALL_ID))
            } else {
                f(k, curr, gen)
            }
        }
    }


}
