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

package vc.data

import allocator.Allocator
import analysis.*
import com.certora.collect.*
import config.Config
import config.ReportTypes
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE_INT
import instrumentation.transformers.GhostSaveRestoreInstrumenter
import log.*
import scene.IContractClass
import scene.IScene
import spec.CVLCompiler
import spec.CVLKeywords
import spec.SafeMathCodeGen
import spec.cvlast.CVLType
import spec.cvlast.ComparisonBasis
import spec.cvlast.SolidityContract
import spec.cvlast.StorageBasis
import tac.*
import testing.TacPipelineDebuggers.oneStateInvariant
import utils.*
import vc.data.SimplePatchingProgram.Companion.patchForEach
import vc.data.compilation.storage.InternalCVLCompilationAPI
import vc.data.compilation.storage.QuantifierGenerator
import vc.data.compilation.storage.SkolemizationGenerator
import vc.data.compilation.storage.StorageComparisonDispatcher
import vc.data.tacexprutil.ExprUnfolder
import vc.data.tacexprutil.TACCmdStructFlattener
import vc.data.tacexprutil.TACCmdStructFlattener.Companion.flattenStructs
import vc.data.tacexprutil.TACUnboundedHashingUtils
import java.math.BigInteger

class CVLToSimpleCompiler(private val scene: IScene) : SafeMathCodeGen {
    fun compile(c: CVLTACProgram) : CoreTACProgram {
        ArtifactManagerFactory().dumpMandatoryCodeArtifacts(
            c,
            ReportTypes.PRECOMPILE_TO_SIMPLE,
            StaticArtifactLocation.Outputs,
            DumpTime.AGNOSTIC
        )
        val blockGraph = c.blockgraph
        val code = mutableMapOf<NBId, List<TACCmd.Simple>>()
        val symbolTable = c.symbolTable
        val newVars = mutableSetOf<TACSymbol.Var>()
        for((blk, cmds) in c.code) {
            val res = translateBlock(cmds, symbolTable)
            val transientTags = res.varDecls.filter { it.tag is Tag.TransientTag }
            check(transientTags.isEmpty()) {
                "Found declarations of vars with transient tags after they should have been removed: $transientTags"
            }
            newVars += res.varDecls
            code[blk] = res.cmds
        }
        val compiled = CoreTACProgram(
            blockgraph = blockGraph,
            code = code,
            symbolTable = symbolTable.copy(
                userDefinedTypes = symbolTable.userDefinedTypes.filterTo(mutableSetOf()) { it !is Tag.TransientTag }.toSet(),
                vars = symbolTable.vars.removeAll { it.tag is Tag.TransientTag }
            ).mergeDecls(newVars),
            instrumentationTAC = InstrumentationTAC(c.ufAxiomBuilder()),
            procedures = c.procedures,
            name = c.name,
            check = true
        )
        ArtifactManagerFactory().dumpMandatoryCodeArtifacts(
            compiled,
            ReportTypes.POSTCOMPILE_TO_SIMPLE,
            StaticArtifactLocation.Outputs,
            DumpTime.AGNOSTIC
        )
        oneStateInvariant(compiled, ReportTypes.POSTCOMPILE_TO_SIMPLE)
        return compiled
    }

    fun compile(cmds: CommandWithRequiredDecls<TACCmd.Spec>, symbolTable: TACSymbolTable) : CommandWithRequiredDecls<TACCmd.Simple> =
        translateBlock(
            cmds.cmds,
            symbolTable
                .mergeDecls(cmds.varDecls))
            .merge(*cmds.varDecls.toTypedArray())

    /**
     * Represents the identity of one of the variables in the exploded representation of an array
     */
    private sealed interface ArrayReprKey {
        /**
         * The distinguished (scalar) length variable
         */
        data object Length : ArrayReprKey

        /**
         * The bytemap at the data path [l]
         */
        data class DataPath(val l: List<DataField>) : ArrayReprKey
    }

    @KSerializable
    @Treapable
    sealed interface StorageMovement : AmbiSerializable {
        @KSerializable
        @Treapable
        data class CopyCurrent(val lhs: String) : StorageMovement
        @Treapable
        @KSerializable
        data class SetCurrent(val rhs: String) : StorageMovement
        @Treapable
        @KSerializable
        data class Move(val lhs: String, val rhs: String) : StorageMovement
    }


    companion object {

        /**
         * Not currently used, documentary only
         */
        private val IsReferenceValueMap = MetaKey<Boolean>("cvl.data.is-reference")

        @InternalCVLCompilationAPI
        fun exposeArrayLengthVar(v: TACSymbol.Var): TACSymbol.Var =
            getArrayLengthVar(v)


        @InternalCVLCompilationAPI
        fun exposeArrayDataVar(out: TACSymbol.Var): TACSymbol.Var {
            return getRawArrayDataVar(out)
        }

        /** Replace all [TACCmd.CVL.AssignVMParam]s within `program` with programs given by [conversions] */
        fun compileVMParamAssignments(program: CVLTACProgram, conversions: TACCmd.CVL.VMParamConverter) =
            program.patching { p ->
                program.ltacStream().forEach { lc ->
                    val cmd = lc.cmd
                    if (cmd is TACCmd.CVL.AssignVMParam) {
                        p.replaceCommand(lc.ptr, conversions.convert(cmd))
                    }
                }
            }

        @InternalCVLCompilationAPI
        fun decodePrimitiveBufferRepresentation(
            lhs: TACSymbol.Var,
            bufferValue: TACSymbol,
            enc: Tag.CVLArray.UserArray.ElementEncoding
        ) : TACCmd.Simple {
            return when(enc) {
                Tag.CVLArray.UserArray.ElementEncoding.Signed -> {
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs, TACBuiltInFunction.TwosComplement.Unwrap(Tag.Bit256).toTACFunctionSym(), listOf(bufferValue.asSym())
                        )
                }

                Tag.CVLArray.UserArray.ElementEncoding.Unsigned ->
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = lhs,
                        rhs = bufferValue.asSym()
                    )

                Tag.CVLArray.UserArray.ElementEncoding.Boolean -> TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = lhs,
                    rhs = with(TACExprFactTypeCheckedOnlyPrimitives) {
                        TACExpr.TernaryExp.Ite(
                            TACExpr.BinRel.Eq(bufferValue.asSym(), TACSymbol.lift(0).asSym()),
                            False,
                            True
                        )
                    }
                )
            }

        }

        private fun getArrayLengthVar(v: TACSymbol.Var) : TACSymbol.Var {
            return v.updateTag(
                suffix = "length",
                sep = "_",
                tag = Tag.Bit256
            ).withMeta(TACMeta.CVL_TYPE, CVLType.PureCVLType.Primitive.UIntK(256))
                .letIf(TACMeta.CVL_DISPLAY_NAME in v.meta) {
                    val displayName = v.meta[TACMeta.CVL_DISPLAY_NAME] ?: error("just checked that the CVL_DISPLAY_NAME meta is present -- now it's not??")
                    it.withMeta(TACMeta.CVL_DISPLAY_NAME, "$displayName.length")
                        .withMeta(TACMeta.CVL_LENGTH_OF, displayName)
            }
        }

        /**
         * Recursively traverse the type [t], recording in [l] the struct fields traversed and the expression [s] represents the sequence
         * of [vc.data.TACExpr.StructAccess] required to reach that value with type t.
         * For primitive fields `f` within the struct [t], the scalar variable associated with `StructAccess(f, [s])` (via [vc.data.TACExpr.StructAccess.toSymbol])
         * is recorded as the variable associated with [l] + DataField.StrucTfield(f).
         *
         * Struct fields within [t] are simply recursively handled. NB that no *intermediate* entry is created for
         * these structs.
         *
         * Finally, for a field `f` with an array type, the variable associated with `StructAccess(f, [s])` (via [vc.data.TACExpr.StructAccess.toSymbol])
         * is used as the input to [arrayRepresentationVars]. The result of this call, which is a mapping of [ArrayReprKey] to
         * variables, is elaborated with the path in `l` + f (transforming [ArrayReprKey.Length] into the singleton list of [DataField.ArrayLength]
         * as described in the documentation for [spec.CVLCompiler.Companion.TraceMeta.ExternalArg].
         *
         * To explain, consider the following example:
         *
         * ```
         * struct S {
         *    B b;
         *    A[] a;
         * }
         *
         * struct B {
         *    uint f1;
         *    uint f2;
         * }
         *
         * struct A {
         *    uint g1;
         *    uint g2;
         * }
         * ```
         *
         * and we start this function with `x` and the type `S`. Then we will have the following result:
         * ```
         * DataField.StructField(b), DataField.StructField(f1) => x_b_f1
         * DataField.StructField(b), DataField.StructField(f2) => x_b_f2
         * DataField.StructField(a), DataField.Length => x_a_length
         * DataField.StructField(a), DataField.ArrayElem => x_a_data
         * DataField.StructField(a), DataField.ArrayElem, DataField.StructField(g1) => x_a_data_fieldg1
         * DataField.StructField(a), DataField.ArrayElem, DataField.StructField(g2) => x_a_data_fieldg2
         * ```
         *
         * NB that we *do* have an entry for the struct type A (DataField.StructField(a), DataField.ArrayElem), but only
         * because it is reachable via an array. Unlike "top-level" structs, which are completely flattened, structs
         * within arrays have pointer values, and thus there is bytemap which holds those values.
         */
        private fun traverseStruct(l: List<DataField.StructField>, s: TACExpr, t: Tag.UserDefined.Struct) : TreapMap<List<DataField>, TACSymbol.Var>? {
            return t.fields.monadicMap { f ->
                val fTy = f.type
                val fieldPath = l + DataField.StructField(f.name)
                val childStructAccess = TACExpr.StructAccess(struct = s, tag = f.type, fieldName = f.name)
                if(fTy is Tag.UserDefined.Struct) {
                    traverseStruct(fieldPath, childStructAccess, fTy)
                } else if(fTy is Tag.CVLArray.UserArray) {
                    val sa = childStructAccess.toSymbol() as? TACSymbol.Var ?: throw IllegalStateException("Expected struct access to yield only variables")
                    arrayRepresentationVars(sa, fTy).mapKeysTo(treapMapBuilderOf()) { (k, _) ->
                        when(k) {
                            is ArrayReprKey.DataPath -> fieldPath + k.l
                            ArrayReprKey.Length -> fieldPath + DataField.ArrayLength
                        }
                    }.build()
                } else {
                    val sa = childStructAccess.toSymbol() as? TACSymbol.Var ?: throw IllegalStateException("Expected struct access to yield only variables")
                    treapMapOf(fieldPath as List<DataField> to sa)
                }
            }?.reduceOrNull { acc, map ->
                acc + map
            }
        }

        /**
         * Compute the family of variables used to represent the complex value at [s] or null if the value
         * is not represented this way.
         */
        private fun toFieldVars(s: CVLCompiler.Companion.TraceMeta.ValueIdentity) : Map<List<DataField>, TACSymbol.Var>? {
            if(s !is CVLCompiler.Companion.TraceMeta.ValueIdentity.TACVar) {
                return null
            }
            val t = s.t.tag
            return if(t is Tag.UserDefined.Struct) {
                traverseStruct(listOf(), s.t.asSym(), t)
            } else if(t is Tag.CVLArray.UserArray) {
                arrayRepresentationVars(s.t, t).mapKeys { (k, _) ->
                    when(k) {
                        is ArrayReprKey.DataPath -> k.l
                        /**
                         * As described in the documentation for [spec.CVLCompiler.Companion.TraceMeta.ExternalArg],
                         * we don't use a separate type constructor to represent the "top-level" length, just
                         * the singleton list.
                         */
                        ArrayReprKey.Length -> listOf(DataField.ArrayLength)
                    }
                }
            } else {
                null
            }
        }

        private fun arrayRepresentationVars(
            v: TACSymbol.Var,
            t: Tag.CVLArray.RawArray
        ) : Map<ArrayReprKey, TACSymbol.Var> {
            /**
             * [t] is included just for symmetry with the [tac.Tag.CVLArray.UserArray] variant of [arrayRepresentationVars]
             * and to make the type signature more descriptive.
             */
            unused(t)
            return mapOf(
                ArrayReprKey.Length to getArrayLengthVar(v),
                varWithSuffix(v, listOf(DataField.ArrayData), holdsReferences = false)
            )
        }

        /**
         * Returns for a [Tag.CVLArray] variable [v] the family of variables
         * that together hold the data for [v].
         */
        private fun arrayRepresentationVars(
            v: TACSymbol.Var,
            t: Tag.CVLArray
        ) : Map<ArrayReprKey, TACSymbol.Var> {
            return when(t) {
                Tag.CVLArray.RawArray -> arrayRepresentationVars(v, t as Tag.CVLArray.RawArray)
                is Tag.CVLArray.UserArray -> arrayRepresentationVars(v, t)
            }
        }

        /**
         * "Explodes" the [tac.Tag.CVLArray.UserArray] at
         * [v] into a family of variables. Builds up a map by finding all paths through the [tac.Tag.CVLArray.UserArray.elementType]
         * of [t], and associating each with a bytemap variable.
         */
        private fun arrayRepresentationVars(
            v: TACSymbol.Var,
            t: Tag.CVLArray.UserArray
        ) : Map<ArrayReprKey, TACSymbol.Var> {
            return mapOf(
                ArrayReprKey.Length to getArrayLengthVar(v),
                varWithSuffix(v, listOf(DataField.ArrayData), t.elementType)
            ) + traversal(v, listOf(DataField.ArrayData), t.elementType)
        }

        private val DataField.suffix get() : String = when(this) {
            DataField.ArrayData -> "_data"
            DataField.ArrayLength -> "_length"
            is DataField.StructField -> "_field${this.field}"
        }

        private fun varWithSuffix(v: TACSymbol.Var, suff: List<DataField>, t: ObjectShape) : Pair<ArrayReprKey, TACSymbol.Var> {
            return varWithSuffix(v, suff, t !is ObjectShape.Primitive)
        }


        /**
         * Gets the distinguished variable for the data path [suff] from [v]. Secretly uses dirty dirty string concatenation.
         */
        private fun varWithSuffix(v: TACSymbol.Var, suff: List<DataField>, holdsReferences: Boolean) : Pair<ArrayReprKey, TACSymbol.Var> {
            return ArrayReprKey.DataPath(suff) to v.updateTag(
                suffix = suff.joinToString("") { it.suffix },
                sep = "",
                tag = Tag.ByteMap,
                meta = v.meta.plus(IsReferenceValueMap to holdsReferences).let { vMeta ->
                    val displayName = v.meta[TACMeta.CVL_DISPLAY_NAME]
                    if (displayName != null && suff.last() is DataField.ArrayData) {
                        vMeta.plus(TACMeta.CVL_DATA_OF to displayName)
                    } else {
                        vMeta
                    }
                }
            )
        }

        private fun traversal(v: TACSymbol.Var, suff: List<DataField>, t: ObjectShape) : Map<ArrayReprKey, TACSymbol.Var> {
            return t.toFields().entries.fold(mapOf()) { acc, (fld, shape) ->
                acc + varWithSuffix(v, suff + fld, shape) + traversal(v, suff + fld, shape)
            }
        }

        fun arrayDataVar(v: TACSymbol.Var, path: List<DataField>) : TACSymbol.Var {
            if(path.firstOrNull() != DataField.ArrayData) {
                throw IllegalArgumentException("Illegal path $path for array $v")
            }
            return when(val t = v.tag) {
                is Tag.CVLArray.RawArray -> if(path.size == 1) {
                    getRawArrayDataVar(v)
                } else {
                    throw IllegalArgumentException("Illegal path $path for array $v")
                }
                is Tag.CVLArray.UserArray -> {
                    arrayRepresentationVars(v, t)[ArrayReprKey.DataPath(path)] ?: throw IllegalArgumentException("Could not find path $path in user array $v with type $t")
                }
                else -> throw IllegalArgumentException("Cannot get array data var for non-array value")
            }
        }

        private fun getRawArrayDataVar(v: TACSymbol.Var) : TACSymbol.Var {
            check((v.tag as? Tag.CVLArray.UserArray)?.elementType is ObjectShape.Primitive ||
                v.tag is Tag.CVLArray.RawArray)
            return varWithSuffix(v, listOf(DataField.ArrayData), false).second
        }

        /**
         * Transformation to be run after delegate call inlining is completed, and the states of
         * contracts have been extended via [stateExtensions]. This pass will find the [TACMeta.CVL_STATE_RESIDUAL]
         * left by the [CVLToSimpleCompiler] indicating the places where state movement has occurred, and elaborate
         * those movements to account for the new state variables added by delegate inlining.
         */
        fun finalizeStorageMovement(code: CoreTACProgram): CoreTACProgram {
            val extendedState = code.stateExtensions.instanceToExtendedVars
            if(extendedState.isEmpty()) {
                return code
            }
            return code.parallelLtacStream().mapNotNull {
                it `to?` it.maybeAnnotation(TACMeta.CVL_STATE_RESIDUAL)
            }.map { (cmd, movement) ->
                val toAssign = extendedState.values.flatMap {
                    it.values.flatten()
                }.map {
                    when(movement) {
                        is StorageMovement.CopyCurrent -> {
                            // returns stateVar x familyVar, and we turn this into the expected
                            // lhs x rhs, aka familyVar x stateVar
                            stateVarToFamily(src = it, baseName = movement.lhs).let { (rhs, lhs) ->
                                lhs to rhs
                            }
                        }
                        is StorageMovement.Move -> {
                            val lhs = stateVarToFamily(src = it, baseName = movement.lhs).second
                            val rhs = stateVarToFamily(src = it, baseName = movement.rhs).second
                            lhs to rhs
                        }

                        is StorageMovement.SetCurrent -> {
                            stateVarToFamily(src = it, baseName = movement.rhs)
                        }
                    }

                }.map { (lhs, rhs) ->
                    CommandWithRequiredDecls(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = lhs,
                        rhs = rhs.asSym()
                    ), setOf(rhs, lhs))
                }.let(CommandWithRequiredDecls.Companion::mergeMany)
                cmd.ptr to toAssign
            }.patchForEach(code, check = true) {
                replaceCommand(it.first, it.second)
            }
        }

        private fun stateVarToFamily(baseName: String, src: TACSymbol.Var) : Pair<TACSymbol.Var, TACSymbol.Var> {
            return src to TACSymbol.Var("$baseName!${src.namePrefix}", tag = src.tag, meta = run {
                var meta = MetaMap()
                src.meta.find(TACMeta.BIT_WIDTH)?.let {
                    meta += (TACMeta.BIT_WIDTH to it)
                }
                src.meta.find(TACMeta.STORAGE_TYPE)?.let {
                    meta += (TACMeta.STORAGE_TYPE to it)
                }
                src.meta.find(TACMeta.SCALARIZATION_SORT)?.let {
                    meta += (TACMeta.SCALARIZATION_SORT to it)
                }
                meta
            })
        }

    }

    private val globals = listOf(
        TACKeyword.NONCE.toVar(),
        TACKeyword.CONTRACT_COUNT.toVar(),
        TACKeyword.BALANCE.toVar(),
        TACKeyword.EXTCODESIZE.toVar()
    )


    private fun contractFamily(baseName: String, contractClass: IContractClass) : Map<TACSymbol.Var, TACSymbol.Var> {
        return (contractClass.storage as StorageInfoWithReadTracker).storageVariables.entries.associate { (it, _) ->
            stateVarToFamily(baseName, it)
        }
    }

    private fun blockchainFamily(base: TACSymbol.Var) : Map<TACSymbol.Var, TACSymbol.Var> {
        // current design is that transient storage is havoc'd at the beginning of a rule and there's a single transaction throughout.
        // so it will require special handling once the design changes to allow for multiple transactions.
        // for now, "at" syntax should roll it back https://www.notion.so/certora/Transient-storage-support-1e95707fb68f46ba8781908550256cce?pvs=4 (16 March 2024)
        val baseName = base.namePrefix
        val ret = scene.getUnifiedStorageInfoViewWithReadTrackers().flatMap { (_, s) ->
            (s as StorageInfoWithReadTracker).storageVariables.flatMap { (stateVar, readTracker) ->
                listOfNotNull(
                    stateVarToFamily(baseName, stateVar),
                    readTracker?.let { r ->
                        stateVarToFamily(baseName, r)
                    }
                )
            }
        }.toMap().toMutableMap()
        for(g in globals) {
            val (k, v) = stateVarToFamily(baseName, g)
            ret[k] = v
        }
        return ret.mapValues { (_, v) -> base.meta[TACSymbol.Var.KEYWORD_ENTRY]?.let { v.withMeta(TACSymbol.Var.KEYWORD_ENTRY, it) } ?: v }
    }

    private fun translateDataMovement(cmd: TACCmd.Simple, vars: MutableSet<TACSymbol.Var>, toReturn: MutableList<TACCmd.Simple>) {
        if(cmd !is TACCmd.Simple.AssigningCmd) {
            toReturn.add(cmd)
            return
        }
        val (subCmds, subDecls) = when(val t = cmd.lhs.tag) {
            Tag.BlockchainState -> {
                // Hold on to your butts
                val rhs = narrowCmdRhs(cmd)
                val srcState = blockchainFamily(rhs.s)
                val lhsState = blockchainFamily(cmd.lhs)
                vars.addAll(srcState.values)
                vars.addAll(lhsState.values)
                for((key, lhs) in lhsState) {
                    toReturn.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = lhs,
                        rhs = srcState[key]!!,
                        meta = MetaMap(TACMeta.LAST_STORAGE_UPDATE)
                    ))
                }
                toReturn.add(TACCmd.Simple.AnnotationCmd(TACMeta.CVL_STATE_RESIDUAL, CVLToSimpleCompiler.StorageMovement.Move(lhs = cmd.lhs.namePrefix, rhs = rhs.s.namePrefix)))
                toReturn.add(SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageTakeSnapshot(cmd.lhs, rhs.s).toAnnotation())
                // We want to display only user assignments, and user can't assign to lastStorage.
                if (cmd.lhs.namePrefix != CVLKeywords.lastStorage.keyword && TACMeta.CVL_DEF_SITE in cmd.lhs.meta) {
                    toReturn.add(SnippetCmd.CVLSnippetCmd.StorageDisplay(cmd.lhs).toAnnotation())
                }
                // this is going to get weird:
                // our goal is to assign blockchain state i.e. we have lhs := rhs
                // we use the current storage as a temp variable for that, and thus we have to
                // keep it good-good or we'll lose our state! So:
                // we denote our current state by S.
                // 1. save current ghost state S into a new checkpoint C
                // 2. restore ghost state from rhs to S
                // 3. save ghost state S (actually rhs) into the lhs here
                // 4. restore original state S via saved checkpoint C
                // peace
                val tmpName = "ghostSwap!${Allocator.getFreshNumber()}" // unique tmp variable and not the GENERIC_SERIES to avoid collisions
                listOf(
                    TACCmd.Simple.AnnotationCmd(TACMeta.GHOST_SAVE, VariableCheckpoint.ghostCheckpointByName(tmpName)),
                    TACCmd.Simple.AnnotationCmd(TACMeta.GHOST_RESTORE, VariableCheckpoint.ghostCheckpointByVar(rhs.s)),
                    TACCmd.Simple.AnnotationCmd(TACMeta.GHOST_SAVE, VariableCheckpoint.ghostCheckpointByVar(cmd.lhs)),
                    TACCmd.Simple.AnnotationCmd(TACMeta.GHOST_RESTORE, VariableCheckpoint.ghostCheckpointByName(tmpName))
                ) to setOf<TACSymbol.Var>()
            }
            is Tag.CVLArray -> {
                if(cmd is TACCmd.Simple.AssigningCmd.AssignHavocCmd) {
                    /**
                     * Havoc all bytemaps in the result
                     */
                    val repr = arrayRepresentationVars(cmd.lhs, t = t)
                    val tmpVar = TACKeyword.TMP(Tag.Bit256, "!ind").toUnique("!")
                    val commands = mutableListOf<TACCmd.Simple>()
                    val decls = mutableSetOf<TACSymbol.Var>()
                    repr.entries.forEach { (k, s) ->
                        if(k is ArrayReprKey.Length) {
                            commands.add(TACCmd.Simple.AssigningCmd.AssignHavocCmd(
                                lhs = s
                            ))
                        } else {
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                lhs = s,
                                rhs = TACExpr.MapDefinition(
                                    defParams = listOf(tmpVar.asSym()),
                                    tag = Tag.ByteMap,
                                    definition = TACExpr.Unconstrained(tag = Tag.Bit256)
                                )
                            )
                        }
                        decls.add(s)
                    }
                    commands to decls
                } else {
                    /**
                     * Explode the LHS and RHS, and then pointwise assign.
                     */
                    val rhs = narrowCmdRhs(cmd)
                    val rSyms = arrayRepresentationVars(rhs.s, t)
                    val lSyms = arrayRepresentationVars(cmd.lhs, t)
                    check(rSyms.keys == lSyms.keys) {
                        "Mismatched domain, our types don't work"
                    }
                    val commands = mutableListOf<TACCmd.Simple>()
                    val decls = mutableSetOf<TACSymbol.Var>()
                    rSyms.forEachEntry { (k, rSym) ->
                        val lSym = lSyms[k]!! // safe by the check above
                        commands.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = lSym,
                            rhs = rSym.asSym()
                        ))
                        decls.add(rSym)
                        decls.add(lSym)
                    }
                    commands to decls
                }
            }
            else -> {
                toReturn.add(cmd)
                return
            }
        }
        toReturn.addAll(subCmds)
        vars.addAll(subDecls)
    }

    private fun narrowCmdRhs(cmd: TACCmd.Simple.AssigningCmd) =
        (cmd as? TACCmd.Simple.AssigningCmd.AssignExpCmd ?: throw IllegalCompilerOutputException("Only AssignExpCmd may assign to type ${cmd.lhs.tag} not $cmd"))
            .rhs as? TACExpr.Sym.Var
            ?: throw IllegalCompilerOutputException("Cannot use complex type outside of simple value movement at $cmd")

    /**
     * Maps a [spec.CVLCompiler.Companion.TraceMeta.ValueIdentity] (strongly expected to be a [spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.TACVar]
     * into a [spec.CVLCompiler.Companion.TraceMeta.ValueIdentity] that can persist in a tac Simple program, i.e.,
     * [spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.TACVar] with tag [Tag.TransientTag] are transformed into a
     * [spec.CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar].
     */
    private fun CVLCompiler.Companion.TraceMeta.ValueIdentity.toSimpleRepr() = when(this) {
        is CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar -> this
        is CVLCompiler.Companion.TraceMeta.ValueIdentity.TACVar -> if(this.t.tag is Tag.TransientTag) {
            CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar(this.t.namePrefix)
        } else {
            this
        }
    }

    @OptIn(InternalCVLCompilationAPI::class)
    private fun translateBlock(cmds: List<TACCmd.Spec>, symbolTable: TACSymbolTable): CommandWithRequiredDecls<TACCmd.Simple> {
        val toReturn = mutableListOf<TACCmd.Simple>()
        val vars = mutableSetOf<TACSymbol.Var>()
        for(c in cmds) {
            when(c) {
                is TACCmd.Simple -> {
                    if (c is TACCmd.Simple.AnnotationCmd) {
                        val (meta, value) = c.annot

                        if (meta == TACMeta.SNIPPET) {
                            when (val snippetCmd = value as SnippetCmd) {
                                is SnippetCmd.CVLSnippetCmd.CVLArgRetAny -> {
                                    val newSnippet = when (snippetCmd.type) {
                                        is CVLType.PureCVLType.Struct -> {
                                            snippetCmd.toStruct { sym: TACSymbol.Var ->
                                                if (sym.tag is Tag.CVLArray) {
                                                    arrayRepresentationVars(sym, sym.tag as Tag.CVLArray).values.toSet()
                                                } else {
                                                    setOf(sym)
                                                }
                                            }.toAnnotation()
                                        }
                                        is CVLType.PureCVLType.VMInternal.RawArgs,
                                        is CVLType.PureCVLType.CVLArrayType -> {
                                            val repr = when(val t = snippetCmd.sym.tag) {
                                                is Tag.CVLArray.RawArray -> {
                                                    arrayRepresentationVars(snippetCmd.sym, t)
                                                }
                                                is Tag.CVLArray.UserArray -> {
                                                    arrayRepresentationVars(snippetCmd.sym, t)
                                                }
                                                else -> throw IllegalStateException("Unexpected tag representation for $snippetCmd")
                                            }
                                            snippetCmd.toArray((repr - ArrayReprKey.Length).values.toSet(), repr[ArrayReprKey.Length]!!).toAnnotation()
                                        }
                                        is CVLType.PureCVLType.VMInternal.BlockchainState -> {
                                            snippetCmd.toBlockchainState(blockchainFamily(snippetCmd.sym).values.toSet()).toAnnotation()
                                        }
                                        else -> {
                                            snippetCmd.toPrimitive().toAnnotation()
                                        }
                                    }

                                    vars.addAll(newSnippet.mentionedVariables)
                                    toReturn.add(newSnippet)
                                    continue
                                }
                                else -> {}
                            }
                        } else if(meta == CVLCompiler.Companion.TraceMeta.ValueTraversal.META_KEY) {
                            check(value is CVLCompiler.Companion.TraceMeta.ValueTraversal) {
                                "Broken annotation cmd"
                            }
                            /*
                             * No need here or later to gate the creation of these commands with `toAnnotation`: the fact we
                             * are seeing this annotation means they are enabled.
                             */
                            val annotationCmd = TACCmd.Simple.AnnotationCmd(
                                CVLCompiler.Companion.TraceMeta.ValueTraversal.META_KEY,
                                CVLCompiler.Companion.TraceMeta.ValueTraversal(base = value.base.toSimpleRepr(), ap = value.ap, lhs = value.lhs.toSimpleRepr()))

                            toReturn.add(annotationCmd)
                            vars.addAll(annotationCmd.mentionedVariables)
                            continue
                        } else if(meta == CVLCompiler.Companion.TraceMeta.ExternalArg.META_KEY) {
                            check(value is CVLCompiler.Companion.TraceMeta.ExternalArg) {
                                "broken annotation cmd"
                            }
                            val annotationCmd = TACCmd.Simple.AnnotationCmd(
                                CVLCompiler.Companion.TraceMeta.ExternalArg.META_KEY,
                                CVLCompiler.Companion.TraceMeta.ExternalArg(
                                    rootOffset = value.rootOffset,
                                    callId = value.callId,
                                    s = value.s.toSimpleRepr(),
                                    targetType = value.targetType,
                                    fields = toFieldVars(value.s),
                                    sourceType = value.sourceType
                                )
                            )
                            toReturn.add(annotationCmd)
                            vars.addAll(annotationCmd.mentionedVariables)
                            continue
                        } else if(meta == CVLCompiler.Companion.TraceMeta.VariableDeclaration.META_KEY) {
                            check(value is CVLCompiler.Companion.TraceMeta.VariableDeclaration) {
                                "broken annotation cmd"
                            }
                            val annotationCmd = TACCmd.Simple.AnnotationCmd(
                                CVLCompiler.Companion.TraceMeta.VariableDeclaration.META_KEY,
                                CVLCompiler.Companion.TraceMeta.VariableDeclaration(
                                    v = value.v.toSimpleRepr(),
                                    t = value.t,
                                    type = value.type,
                                    fields = toFieldVars(value.v)
                                )
                            )
                            toReturn.add(annotationCmd)
                            vars.addAll(annotationCmd.mentionedVariables)
                            continue
                        }
                    }

                    // TODO: write a CVLCmdMapper so we can write a CVLTACProgram type annotator
                    val exprs = c.exprs()
                        .flatMap { expr -> TACTypeChecker(symbolTable, allowTransientTag = true).typeCheck(expr)
                            .resultOrThrow { errors ->
                                errors.forEach { error -> Logger.alwaysError(error) }
                                IllegalCompilerOutputException("CVL IR received a bad TAC Expression") }
                            .nestedSubexprs()
                        }

                    if (exprs.any { it.tagAssumeChecked is Tag.TransientTag }) {
                        if(c !is TACCmd.Simple.AssigningCmd) {
                            throw IllegalCompilerOutputException("Cannot use complex type outside of expression context $c")
                        }
                        if(c is TACCmd.Simple.AssigningCmd.AssignExpCmd && c.rhs is TACExpr.Sym.Var) {
                            toReturn.add(
                                CVLCompiler.Companion.TraceMeta.CVLMovementCmd(
                                    src = CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar(c.rhs.s.namePrefix),
                                    dst = CVLCompiler.Companion.TraceMeta.ValueIdentity.CVLVar(c.lhs.namePrefix)
                                )
                            )
                        }
                        val lhsTag = (c as? TACCmd.Simple.AssigningCmd)?.lhs?.tag

                        val (newCmds, newDecls) = when (lhsTag) {
                            is Tag.UserDefined.Struct -> {
                                when (c) {
                                    is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                                        @Suppress("NAME_SHADOWING") val cmds = flattenStructs(c)
                                        cmds.cmds to cmds.varDecls
                                    }
                                    is TACCmd.Simple.AssigningCmd.AssignHavocCmd -> {
                                        @Suppress("NAME_SHADOWING") val cmds = flattenStructs(c)
                                        cmds.cmds to cmds.varDecls
                                    }
                                    else -> throw IllegalCompilerOutputException("$c may not assign to a struct")
                                }
                            }
                            else -> {
                                val newDecls = mutableSetOf<TACSymbol.Var>()
                                val structFlattener = TACCmdStructFlattener(newDecls)
                                val newCmds = structFlattener.map(c)
                                listOf(newCmds) to newDecls
                            }
                        }
                        for(cmd in newCmds) {
                            translateDataMovement(cmd, vars, toReturn)
                        }
                        vars.addAll(newDecls)
                    } else {
                        toReturn.add(c)
                    }
                }
                is TACCmd.CVL.ArrayLengthRead -> {
                    toReturn.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = c.lhs,
                        rhs = getArrayLengthVar(c.rhs)
                    ))
                    vars.add(getArrayLengthVar(c.rhs))
                }
                is TACCmd.CVL.StringLastWord -> {
                    val arrayData = getRawArrayDataVar(c.rhs)
                    val arrayLen = getArrayLengthVar(c.rhs)
                    vars.add(arrayData)
                    vars.add(arrayLen)

                    ExprUnfolder.unfoldPlusOneCmd(
                        "lastWordLocation",
                        TACExprFactUntyped {
                            arrayLen.asSym() sub (arrayLen.asSym() mod EVM_WORD_SIZE_INT.asTACExpr)
                        }
                    ) { locationToReadFrom ->
                        TACCmd.Simple.AssigningCmd.ByteLoad(
                            c.lhs,
                            locationToReadFrom.s,
                            arrayData
                        )
                    }.let {
                        toReturn.addAll(it.cmds)
                        vars.addAll(it.varDecls)
                    }
                }
                is TACCmd.CVL.ReadElement -> {
                    val arrayTag = c.base.tag as Tag.CVLArray
                    val tmp = TACKeyword.TMP(Tag.Bit256, "!arrayRead").toUnique("!")
                    val arrayData = arrayDataVar(c.base, c.dataPath)
                    toReturn.add(TACCmd.Simple.AssigningCmd.ByteLoad(
                        lhs = tmp,
                        base = arrayData,
                        loc = c.physicalIndex
                    ))
                    when (arrayTag) {
                        is Tag.CVLArray.RawArray -> {
                            toReturn.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                c.lhs, tmp.asSym()
                            ))
                        }
                        is Tag.CVLArray.UserArray -> {
                            toReturn.add(
                                decodePrimitiveBufferRepresentation(
                                    lhs = c.lhs,
                                    bufferValue = tmp,
                                    enc = c.useEncoding
                                )
                            )
                            when (c.useEncoding) {
                                Tag.CVLArray.UserArray.ElementEncoding.Signed -> {
                                    toReturn.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                        c.lhs, TACBuiltInFunction.TwosComplement.Unwrap(Tag.Bit256).toTACFunctionSym(), listOf(tmp.asSym())
                                    ))
                                }
                                Tag.CVLArray.UserArray.ElementEncoding.Unsigned -> toReturn.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                    lhs = c.lhs,
                                    rhs = tmp.asSym()
                                ))
                                Tag.CVLArray.UserArray.ElementEncoding.Boolean -> toReturn.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                    lhs = c.lhs,
                                    rhs = TACExprFactTypeChecked(symbolTable)() {
                                        TACExpr.TernaryExp.Ite(
                                            TACExpr.BinRel.Eq(tmp.asSym(), TACSymbol.lift(0).asSym()),
                                            False,
                                            True
                                        )
                                    }
                                ))
                            }
                        }
                    }


                    vars.add(arrayData)
                    vars.add(tmp)
                }
                is TACCmd.CVL.SetArrayLength -> {
                    val arrLengthVar = getArrayLengthVar(c.lhs)
                    toReturn.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = arrLengthVar,
                        rhs = c.len
                    ))
                    vars.add(arrLengthVar)
                }
                is TACCmd.CVL.CopyBlockchainState -> {
                    val storageFamily = blockchainFamily(c.lhs)
                    for((k, v) in storageFamily) {
                        toReturn.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = v,
                            rhs = k,
                            meta = c.meta
                        ))
                        vars.add(v)
                        vars.add(k)
                    }
                    toReturn.add(SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageTakeSnapshot(c.lhs).toAnnotation())
                    toReturn.add(TACCmd.Simple.AnnotationCmd(TACMeta.GHOST_SAVE, VariableCheckpoint.ghostCheckpointByVar(c.lhs)))
                    toReturn.add(TACCmd.Simple.AnnotationCmd(TACMeta.CVL_STATE_RESIDUAL, StorageMovement.CopyCurrent(c.lhs.namePrefix)))
                }
                is TACCmd.CVL.SetBlockchainState -> {
                    val storageFamily = blockchainFamily(c.stateVar)
                    for((k, v) in storageFamily) {
                        toReturn.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs =  k,
                            rhs = v,
                            meta = c.meta
                        ))
                        vars.add(v)
                        vars.add(k)
                    }
                    toReturn.add(SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet.StorageRestoreSnapshot(c.stateVar).toAnnotation())
                    toReturn.add(TACCmd.Simple.AnnotationCmd(TACMeta.GHOST_RESTORE, VariableCheckpoint.ghostCheckpointByVar(c.stateVar)))
                    toReturn.add(TACCmd.Simple.AnnotationCmd(TACMeta.CVL_STATE_RESIDUAL, StorageMovement.SetCurrent(c.stateVar.namePrefix)))
                }
                is TACCmd.CVL.WriteElement -> {
                    val arrayTag = c.target.tag as Tag.CVLArray
                    val tmp = TACKeyword.TMP(Tag.Bit256, "arrayWrite").toUnique("!")
                    val arrayData = arrayDataVar(c.target, c.outputPath)
                    when (arrayTag) {
                        is Tag.CVLArray.RawArray -> {
                            toReturn.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                tmp, c.value.asSym()
                            ))
                        }
                        is Tag.CVLArray.UserArray -> {
                            when (c.useEncoding) {
                                Tag.CVLArray.UserArray.ElementEncoding.Signed -> {
                                    toReturn.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                        tmp, TACBuiltInFunction.TwosComplement.Wrap(Tag.Bit256).toTACFunctionSym(), listOf(c.value.asSym())
                                    ))
                                }
                                Tag.CVLArray.UserArray.ElementEncoding.Unsigned -> {
                                    toReturn.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                        tmp,
                                        TACBuiltInFunction.SafeMathNarrow.Implicit(Tag.Bit256).toTACFunctionSym(),
                                        listOf(c.value.asSym())
                                    ))
                                }
                                Tag.CVLArray.UserArray.ElementEncoding.Boolean -> toReturn.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                    tmp,
                                    TACExprFactTypeChecked(symbolTable)() {
                                        TACExpr.TernaryExp.Ite(
                                            c.value.asSym(),
                                            TACSymbol.lift(1).asSym(),
                                            TACSymbol.lift(0).asSym()
                                        )
                                    }
                                ))
                            }
                        }
                    }

                    toReturn.add(TACCmd.Simple.AssigningCmd.ByteStore(
                        base = arrayData,
                        loc = c.physicalIndex,
                        value = tmp
                    ))
                    vars.add(arrayData)
                    vars.add(tmp)
                }
                is TACCmd.CVL.AssignVMParam -> throw CertoraInternalException(CertoraInternalErrorType.CVL_COMPILER,
                    "An assign vm param command was found when code instrumentation expected to be complete.")
                is TACCmd.CVL.AssignBytesBlob -> {
                    val arrayData = getRawArrayDataVar(c.rhs)
                    val arrayLength = getArrayLengthVar(c.rhs)
                    val hashing = TACUnboundedHashingUtils.fromByteMapRange(
                        hashFamily = HashFamily.Keccack,
                        map = arrayData,
                        start = BigInteger.ZERO.asTACExpr,
                        length = arrayLength.asSym(),
                    )
                    toReturn.addAll(hashing.cmdsToAdd)
                    vars.addAll(hashing.declsToAdd)
                    vars.add(arrayData)
                    vars.add(arrayLength)
                    toReturn.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = c.lhs,
                        rhs = hashing.exp
                    ))
                }
                is TACCmd.CVL.CompareStorage -> {
                    val decl : CommandWithRequiredDecls<TACCmd.Simple> = compileStorageComparison(c, symbolTable)
                    vars.addAll(decl.varDecls)
                    toReturn.addAll(decl.cmds)
                }

                is TACCmd.CVL.CompareArray -> {
                    val decl : CommandWithRequiredDecls<TACCmd.Simple> = compileArrayCompare(c)
                    vars.addAll(decl.varDecls)
                    toReturn.addAll(decl.cmds)
                }
                is TACCmd.CVL.ArrayCopy -> {
                    fun TACCmd.CVL.BufferPointer.toBufferVar() = when(this.buffer) {
                        is TACCmd.CVL.Buffer.CVLBuffer -> arrayDataVar(this.buffer.root, this.buffer.dataPath)
                        is TACCmd.CVL.Buffer.EVMBuffer -> this.buffer.t
                    }
                    val copySize = TACKeyword.TMP(Tag.Bit256, "!copyAmount").toUnique("!")
                    safeMul(toReturn, vars, copySize, c.logicalLength, c.elementSize.asTACSymbol())
                    if(c.destinationSource == null) {
                        toReturn.add(
                            TACCmd.Simple.ByteLongCopy(
                                length = copySize,
                                dstBase = c.dst.toBufferVar(),
                                dstOffset = c.dst.offset,
                                srcBase = c.src.toBufferVar(),
                                srcOffset = c.src.offset
                            )
                        )
                    } else {
                        toReturn.add(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                lhs = c.dst.toBufferVar(),
                                rhs = TACExpr.LongStore(
                                    tag = Tag.ByteMap,
                                    srcOffset = c.src.offset.asSym(),
                                    dstOffset = c.dst.offset.asSym(),
                                    length = copySize.asSym(),
                                    dstMap = c.destinationSource.asSym(),
                                    srcMap = c.src.toBufferVar().asSym()
                                )
                            )
                        )
                        vars.add(c.destinationSource)
                    }
                    vars.addAll(setOfNotNull(
                        c.dst.toBufferVar(),
                        c.src.toBufferVar(),
                        c.src.offset as? TACSymbol.Var,
                        c.dst.offset as? TACSymbol.Var,
                        copySize,
                        c.logicalLength as? TACSymbol.Var
                    ))
                }
                is TACCmd.CVL.LocalAlloc -> {
                    val allocVar = c.arr.updateTag(Tag.Bit256, "alloc", sep = "_")
                    val tmp = TACKeyword.TMP(Tag.Bit256, "!fp").toUnique("!")
                    toReturn.add(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = tmp,
                            rhs = allocVar.asSym()
                        )
                    )
                    val next = TACKeyword.TMP(Tag.Bit256, "_newAlloc").toUnique("!")
                    val addResult = safeAdd(next, tmp, c.amount)
                    toReturn.addAll(addResult.cmds)
                    vars.addAll(addResult.varDecls)
                    toReturn.addAll(listOf(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = c.lhs,
                            rhs = tmp.asSym()
                        ),
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = allocVar,
                            rhs = next
                        )
                    ))
                    vars.addAll(listOf(allocVar, tmp, next))
                }
            }
        }
        return CommandWithRequiredDecls(toReturn, vars)
    }

    private fun StorageBasis.Ghost.toVariableTag() = if(this.params.isEmpty()) {
        this.resultType.toTag()
    } else {
        Tag.GhostMap(
            this.params.map { it.toTag() },
            this.resultType.toTag()
        )
    }

    @OptIn(InternalCVLCompilationAPI::class)
    private fun compileStorageComparison(c: TACCmd.CVL.CompareStorage, symbolTable: TACSymbolTable): CommandWithRequiredDecls<TACCmd.Simple> {
        fun balances(op: TACSymbol.Var) = mapOf(
            StorageBasis.Balances as StorageBasis to mapOf(stateVarToFamily(op.namePrefix, TACKeyword.BALANCE.toVar()).mapFirst(TACSymbol.Var::namePrefix))
        )
        fun extcodesizes(op: TACSymbol.Var) = mapOf(
            StorageBasis.ExternalCodesizes as StorageBasis to mapOf(stateVarToFamily(op.namePrefix, TACKeyword.EXTCODESIZE.toVar()).mapFirst(TACSymbol.Var::namePrefix))
        )
        fun forContract(op: TACSymbol.Var, c: SolidityContract) = mapOf(
            StorageBasis.ContractClass(c) as StorageBasis to contractFamily(op.namePrefix, scene.getContract(c)).mapKeys {
                it.key.namePrefix
            }
        )
        fun forGhost(
            op: TACSymbol.Var,
            g: StorageBasis.Ghost
        ) : Map<StorageBasis, Map<String, TACSymbol.Var>> {
            val ufDeclaration = symbolTable.uninterpretedFunctions().singleOrNull {
                it.declarationName == g.name
            } ?: error("CVL compiler failed to record a matching UF for ghost with name ${g.name}")
            return mapOf(
                g to mapOf(g.name to TACSymbol.Var(
                    GhostSaveRestoreInstrumenter.getGhostNameAt(op, ufDeclaration.name),
                    tag = g.toVariableTag()
                ))
            )
        }

        /**
         * For some base variable [op], this function returns a map `m` of StorageBasis -> String -> TACSymbol.Var. The variables
         * in the codomain represent the variable(s) that together represent the StorageBasis in the corresponding key at the
         * storage incarnation given by [op].
         *
         * Note that for any storage basis that is not [spec.cvlast.StorageBasis.ContractClass], the
         * inner map will be singleton, as balances and ghosts are represented by a single map/variable. For the case
         * of [spec.cvlast.StorageBasis.ContractClass] bases, multiple variables (scalar or maps)
         * together form the representation of a contract's state. Thus, the intermediate keys provide a way to correlate
         * variables at different incarnations. For example, for some contract class C and op1 and op2, we may get:
         * C -> "foo" -> "lastStorage!op1!foo" and C -> "foo" -> "lastStorage!op2!foo", where the fact that
         * "lastStorage!op1!foo" and "lastStorage!op2!foo" share the same string key indicates that they both represent
         * the "same" part of storage, but at different incarnations.
         *
         * This function has the following invariants:
         * 1. If called with `op1`, the map returned by this function will have the same domain as when it is called with
         * any other `op2`.
         * 2. Any `String -> Var` associated with some basis for an argument `op1`
         * will have the same set of keys as the map associated the same basis for a different argument `op2`.
         *
         * Ultimately, all the two invariants say is that the keys in the first two levels of maps returned by this function
         * will always be the same, and the [op] argument should only affect the Var symbol names in the codomain. Thus,
         * we can two pointwise comaprisons on the maps returned by this function.
         */
        fun getFamily(op: TACSymbol.Var) : Map<StorageBasis, Map<String, TACSymbol.Var>> = when(c.storageBasis) {
            is StorageBasis.Balances -> balances(op)
            is StorageBasis.ExternalCodesizes -> extcodesizes(op)
            is StorageBasis.ContractClass -> forContract(op, c.storageBasis.canonicalId)
            is ComparisonBasis.All -> {
                val m = balances(op) + scene.getContracts().fold(balances(op)) { acc, it ->
                    acc + forContract(op, c = SolidityContract(it.name))
                }
                c.storageBasis.ghostUniverse.fold(m) { acc, g ->
                    acc + forGhost(op, g)
                }
            }
            is StorageBasis.Ghost -> {
                forGhost(op, c.storageBasis)
            }
        }
        val f1 = getFamily(c.storage1)
        val f2 = getFamily(c.storage2)
        /*
         invariant 1 above
         */
        check(f1.keys == f2.keys) {
            "Family invariant broken, different keys ${f1.keys} vs ${f2.keys}"
        }
        val generator = if(Config.GroundQuantifiers.get() && !c.useSkolemizaton) {
            QuantifierGenerator
        } else {
            SkolemizationGenerator
        }
        val outputVars = mutableSetOf<TACSymbol.Var>()
        val mutRes = MutableCommandWithRequiredDecls<TACCmd.Simple>()
        for((basis, v1) in f1) {
            val v2 = f2[basis]!!
            generateForBasis(
                f1 = v1,
                f2 = v2,
                generator = generator,
                mutRes = mutRes,
                outputVars = outputVars,
                c = c,
                basis = basis
            )
        }
        check(outputVars.isNotEmpty())
        val combinator : (List<TACExpr>, Tag.Bool?) -> TACExpr = if(c.isEquality) {
            TACExpr.BinBoolOp::LAnd
        } else {
            TACExpr.BinBoolOp::LOr
        }
        mutRes.extend(listOf(
            SnippetCmd.CVLSnippetCmd.StorageDisplay(c.storage1).toAnnotation(),
            SnippetCmd.CVLSnippetCmd.StorageDisplay(c.storage2).toAnnotation(),
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = c.lhsVar,
                rhs =  combinator(outputVars.map { it.asSym() }, null)
            )
        ), outputVars + c.lhsVar)
        return mutRes.toCommandWithRequiredDecls()
    }

    /**
     * Compiles array comparison by:
     * 1. Comparing lengths
     * 2. For each data path in the array's element type, computing and comparing hashes
     *
     * Result = (length1 == length2) AND (hash1_path1 == hash2_path1) AND ... AND (hash1_pathN == hash2_pathN)
     *
     * Supports:
     * - Bytes1Array (bytes/string) - simple length + data hash comparison
     * - Arrays of primitives (uint256[], int256[], etc.)
     * - Structs containing primitive arrays (struct S { uint256[] arr; })
     *
     * Does NOT support:
     * - Arrays of structs (S[] where S is a struct type)
     */
    private fun compileArrayCompare(c: TACCmd.CVL.CompareArray): CommandWithRequiredDecls<TACCmd.Simple> {
        val leftSym = c.left
        val rightSym = c.right
        val leftTag = leftSym.tag as Tag.CVLArray
        val rightTag = rightSym.tag as Tag.CVLArray

        // Get all representation variables for both arrays
        val leftRepr = arrayRepresentationVars(leftSym, leftTag)
        val rightRepr = arrayRepresentationVars(rightSym, rightTag)

        val commands = mutableListOf<TACCmd.Simple>()
        val decls = mutableSetOf<TACSymbol.Var>()
        val comparisonResults = mutableListOf<TACExpr>()

        // 1. Compare lengths
        val leftLength = leftRepr[ArrayReprKey.Length]!!
        val rightLength = rightRepr[ArrayReprKey.Length]!!
        decls.add(leftLength)
        decls.add(rightLength)
        comparisonResults.add(TACExpr.BinRel.Eq(leftLength.asSym(), rightLength.asSym()))

        // Determine if this is a packed array (bytes/string) or word-aligned
        // For packed arrays: byte length = logical length (each element is 1 byte)
        // For word-aligned arrays: byte length = logical length * 32 (each element is 32 bytes)
        val isPacked = when (leftTag) {
            is Tag.CVLArray.UserArray -> leftTag.isPacked
            Tag.CVLArray.RawArray -> true // RawArray is byte-level
        }

        // Compute the byte length to hash
        val leftByteLength: TACSymbol
        val rightByteLength: TACSymbol
        if (isPacked) {
            // Packed: byte length = logical length
            leftByteLength = leftLength
            rightByteLength = rightLength
        } else {
            // Word-aligned: byte length = logical length * EVM_WORD_SIZE
            val leftByteLenVar = TACKeyword.TMP(Tag.Bit256, "!byteLen").toUnique("!")
            val rightByteLenVar = TACKeyword.TMP(Tag.Bit256, "!byteLen").toUnique("!")
            decls.add(leftByteLenVar)
            decls.add(rightByteLenVar)

            // Safe multiplication by word size
            safeMul(commands, decls, leftByteLenVar, leftLength, EVM_WORD_SIZE_INT.asTACSymbol())
            safeMul(commands, decls, rightByteLenVar, rightLength, EVM_WORD_SIZE_INT.asTACSymbol())

            leftByteLength = leftByteLenVar
            rightByteLength = rightByteLenVar
        }

        // 2. For each data path, compare hashes
        val dataPaths = leftRepr.keys.filterIsInstance<ArrayReprKey.DataPath>()

        for (pathKey in dataPaths) {
            val leftDataVar = leftRepr[pathKey]!!
            val rightDataVar = rightRepr[pathKey]!!
            decls.add(leftDataVar)
            decls.add(rightDataVar)

            val pathSuffix = pathKey.l.joinToString("_") { field ->
                field.suffix
            }

            val leftHash = TACKeyword.TMP(Tag.Bit256, "!compL_$pathSuffix").toUnique("!")
            val rightHash = TACKeyword.TMP(Tag.Bit256, "!compR_$pathSuffix").toUnique("!")
            decls.add(leftHash)
            decls.add(rightHash)

            // Hash left data map (using byte length, not logical length)
            commands.add(TACCmd.Simple.AssigningCmd.AssignSha3Cmd(
                lhs = leftHash,
                op1 = TACSymbol.Zero,
                op2 = leftByteLength,
                memBaseMap = leftDataVar
            ))

            // Hash right data map (using byte length, not logical length)
            commands.add(TACCmd.Simple.AssigningCmd.AssignSha3Cmd(
                lhs = rightHash,
                op1 = TACSymbol.Zero,
                op2 = rightByteLength,
                memBaseMap = rightDataVar
            ))

            comparisonResults.add(TACExpr.BinRel.Eq(leftHash.asSym(), rightHash.asSym()))
        }

        // 3. Combine all comparisons with AND
        val resultExpr = comparisonResults.reduce { acc, expr ->
            TACExpr.BinBoolOp.LAnd(acc, expr)
        }

        commands.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(
            lhs = c.lhsVar,
            rhs = resultExpr
        ))

        val mutRes = MutableCommandWithRequiredDecls<TACCmd.Simple>()
        mutRes.extend(commands, decls)
        return mutRes.toCommandWithRequiredDecls()
    }


    /**
     * Generate the comparisons for a single basis. [f1] and [f2] hold the map from "canonical" names to the
     * variables that together represent the storage at some incarnation.
     */
    @OptIn(InternalCVLCompilationAPI::class)
    private fun generateForBasis(
        f1: Map<String, TACSymbol.Var>,
        f2: Map<String, TACSymbol.Var>,
        generator: StorageComparisonDispatcher,
        mutRes: MutableCommandWithRequiredDecls<TACCmd.Simple>,
        outputVars: MutableSet<TACSymbol.Var>,
        c: TACCmd.CVL.CompareStorage,
        basis: StorageBasis
    ) {
        /**
         * These codomains must be equal by invariant 2 of getFamily above.
         */
        check(f1.keys == f2.keys)
        for((k, v1) in f1) {
            val v2 = f2[k]!!
            val comparisonOutput = TACKeyword.TMP(Tag.Bool, "!$k!cmp").toUnique("!")
            outputVars.add(comparisonOutput)
            mutRes.extend(generator.generateComparison(
                isEq = c.isEquality,
                output = comparisonOutput,
                fv1 = (c.storage1.meta.find(TACMeta.CVL_DISPLAY_NAME) ?: "???") to v1,
                fv2 = (c.storage2.meta.find(TACMeta.CVL_DISPLAY_NAME) ?: "???") to v2,
                storageBasis = basis
            ))
        }
    }


    class IllegalCompilerOutputException(s: String) : RuntimeException(s)

}
