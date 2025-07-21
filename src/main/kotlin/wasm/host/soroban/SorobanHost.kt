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

package wasm.host.soroban

import analysis.*
import analysis.CommandWithRequiredDecls.Companion.mergeMany
import analysis.CommandWithRequiredDecls.Companion.withDecls
import analysis.opt.*
import config.ReportTypes
import datastructures.stdcollections.*
import tac.Tag
import tac.generation.*
import utils.*
import vc.data.*
import verifier.CoreToCoreTransformer
import wasm.analysis.intervals.IntervalBasedExprSimplifier
import wasm.host.soroban.modules.ContextModuleImpl
import wasm.host.soroban.opt.*
import wasm.host.soroban.types.*
import wasm.host.WasmHost
import wasm.impCfg.StraightLine
import wasm.impCfg.WASM_HOST_FUNC_SUMMARY_END
import wasm.impCfg.WASM_HOST_FUNC_SUMMARY_START
import wasm.ir.ImportFunc
import wasm.ir.WasmImport
import wasm.ir.WasmName
import wasm.ir.WasmProgram
import wasm.transform.PropagateRevertConditions

/** Provide implementation details of the Soroban host environment */
object SorobanHost : WasmHost {
    override fun init() = mergeMany(
        label("--> Soroban initialization"),
        Val.init(),
        BytesType.init(),
        StringType.init(),
        SymbolType.init(),
        VecType.init(),
        MapType.init(),
        AddressType.init(),
        IntType.init(),
        ContextModuleImpl.init(),
        Contract.init(),
        label("<-- Soroban initialization"),
    )

    override fun applyPreUnrollTransforms(tac: CoreTACProgram.Linear, wasm: WasmProgram) =
        tac
        .map(CoreToCoreTransformer(ReportTypes.MATERIALIZE_CONTROL_FLOW, ITESummary::materialize))

    override fun applyOptimizations(tac: CoreTACProgram.Linear, wasm: WasmProgram) =
        tac
        .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE) { ConstantPropagator.propagateConstants(it, setOf()) })
        .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE_SOROBAN_MEMORY) { optimizeSorobanMemory(it) })
        .mapIfAllowed(CoreToCoreTransformer(ReportTypes.INTERVALS_OPTIMIZE, IntervalBasedExprSimplifier::analyze))
        .mapIfAllowed(CoreToCoreTransformer(ReportTypes.WASM_PROPAGATE_REVERT_CONDITIONS, PropagateRevertConditions::transform))
        .mapIfAllowed(CoreToCoreTransformer(ReportTypes.CANONICALIZE_SCALARSET, CanonicalizeObjectValAllocations::canonicalize))

    private data class SorobanImport(
        val import: WasmImport<ImportFunc>,
        val module: SorobanModule,
        val function: SorobanFunction
    )

    override fun importer(program: WasmProgram) = object : WasmHost.Importer {

        fun tryResolve(id: WasmName): Either<String, SorobanImport> {
            val import = program.importedFuncs[id]
                ?: return "Wasm import ID not found: $id".toLeft()
            val module = sorobanEnv.moduleExports[import.from]
                ?: return "Wasm module not exported: ${import.from}".toLeft()
            val func = module.functionExports[import.what]
                ?: return "Wasm function not exported: ${module.name}/${import.what}".toLeft()

            return SorobanImport(import, module, func).toRight()
        }

        override fun resolve(id: WasmName): Boolean =
            tryResolve(id) is Either.Right

        override fun importFunc(
            id: WasmName,
            args: List<TACSymbol>,
            retVar: TACSymbol.Var?
        ): CommandWithRequiredDecls<TACCmd.Simple>? {

            fun assertFalse(message: String) = assert(message) { false.asTACExpr }

            // Resolve the ID to a Soroban host function
            val (import, module, func) = when (val res = tryResolve(id)) {
                is Either.Left -> return assertFalse(res.d)
                is Either.Right -> res.d
            }

            // Make sure we have the right number of args
            if (args.size != func.args.size) {
                return assertFalse("${module.name}/${import.what} expects ${func.args.size} arguments, but got ${args.size}")
            }

            // Currently all Soroban functions return something (even if only a void Var)
            if (retVar == null) {
                return assertFalse("${module.name}/${import.what} expects a return variable")
            }

            // Some useful labels to make the TAC dumps easier to read
            val name = "${module.name}/${func.name}"
            val enter = listOf(TACCmd.Simple.AnnotationCmd(
                WASM_HOST_FUNC_SUMMARY_START,
                StraightLine.InlinedFuncStartAnnotation.TAC(name, args, null)
            )).withDecls()

            val exit = listOf(TACCmd.Simple.AnnotationCmd(
                WASM_HOST_FUNC_SUMMARY_END,
                StraightLine.InlinedFuncEndAnnotation.TAC(name)
            )).withDecls()

            // Validate and decode the tagged arguments
            val (decodedArgs, decodeArgs) = func.args.zip(args).map { (arg, sym) ->
                decodeArg(sym, arg.name, arg.type, module.name, func.name)
            }.unzip()

            // We may need a temp var for the return value, so we can encode it below.
            val unencodedRetVar = when (func.ret) {
                SorobanType.Void -> null
                SorobanType.Bool -> TACSymbol.Var("result", Tag.Bool).toUnique("!")
                else -> TACSymbol.Var("result", Tag.Bit256).toUnique("!")
            }

            val impl = module.impl.getFuncImpl(func.name, decodedArgs, unencodedRetVar)
                ?: mergeMany(listOfNotNull(
                    label("$name not implemented"),
                    unencodedRetVar?.let { assignHavoc(it) }
                ))

            val encodeResult = encodeResult(retVar, unencodedRetVar, func.ret)

            return CommandWithRequiredDecls.mergeMany(listOf(enter) + decodeArgs + impl + encodeResult + exit)
        }
    }

    private fun encodeResult(
        encodedResult: TACSymbol.Var,
        unencodedResult: TACSymbol?,
        type: SorobanType,
    ): CommandWithRequiredDecls<TACCmd.Simple> {

        fun encode(encoder: TACExprFact.() -> TACExpr) =
            assign(encodedResult, exp = encoder)

        if (unencodedResult == null) {
            check(type == SorobanType.Void)
            return encode { Val.VOID }
        }

        fun passthrough() =
            assign(encodedResult) { unencodedResult.asSym() }

        fun passthroughVal(vararg assumeTags: Val.Tag) = mergeMany(
            passthrough(),
            Val.assumeValid(encodedResult, *assumeTags)
        )

        fun passthroughStorageType() = mergeMany(
            passthrough(),
            Contract.assumeValidStorageType(encodedResult)
        )

        return when (type) {
            SorobanType.i64 -> passthrough()
            SorobanType.u64 -> passthrough()

            SorobanType.Bool -> encode { Val.encodeBool(unencodedResult.asSym()) }

            SorobanType.StorageType -> passthroughStorageType()

            SorobanType.AnyVal -> passthroughVal()

            SorobanType.Void -> `impossible!` // Covered by the null check above

            SorobanType.Error -> passthrough() // Nothing does this yet

            SorobanType.U32Val -> encode { Val.encodeSmallInt(unencodedResult.asSym(), Val.Tag.U32Val) }
            SorobanType.I32Val -> encode { Val.encodeSmallInt(unencodedResult.asSym(), Val.Tag.I32Val) }

            SorobanType.U64Val -> IntType.U64.encodeToVal(encodedResult, unencodedResult)
            SorobanType.I64Val -> IntType.I64.encodeToVal(encodedResult, unencodedResult)
            SorobanType.U128Val -> IntType.U128.encodeToVal(encodedResult, unencodedResult)
            SorobanType.I128Val -> IntType.I128.encodeToVal(encodedResult, unencodedResult)
            SorobanType.U256Val -> IntType.U256.encodeToVal(encodedResult, unencodedResult)
            SorobanType.I256Val -> IntType.I256.encodeToVal(encodedResult, unencodedResult)
            SorobanType.I64Object -> passthroughVal(Val.Tag.I64Object)
            SorobanType.U64Object -> passthroughVal(Val.Tag.U64Object)
            SorobanType.U128Object -> passthroughVal(Val.Tag.U128Object)
            SorobanType.I128Object -> passthroughVal(Val.Tag.I128Object)
            SorobanType.U256Object -> passthroughVal(Val.Tag.U256Object)
            SorobanType.I256Object -> passthroughVal(Val.Tag.I256Object)
            SorobanType.TimepointObject -> passthroughVal(Val.Tag.TimepointObject)
            SorobanType.DurationObject -> passthroughVal(Val.Tag.DurationObject)

            SorobanType.BytesObject -> passthroughVal(Val.Tag.BytesObject)
            SorobanType.StringObject -> passthroughVal(Val.Tag.StringObject)
            SorobanType.Symbol -> passthroughVal(Val.Tag.SymbolSmall, Val.Tag.SymbolObject)
            SorobanType.SymbolObject -> passthroughVal(Val.Tag.SymbolObject)
            SorobanType.VecObject -> passthroughVal(Val.Tag.VecObject)
            SorobanType.MapObject -> passthroughVal(Val.Tag.MapObject)
            SorobanType.AddressObject -> passthroughVal(Val.Tag.AddressObject)
        }
    }

    private fun decodeArg(
        arg: TACSymbol,
        argName: String,
        type: SorobanType,
        module: String,
        func: String
    ): Pair<TACSymbol, CommandWithRequiredDecls<TACCmd.Simple>> {
        fun passthrough() = arg to listOf<TACCmd.Simple>().withDecls()

        fun hasTag(vararg tags: Val.Tag) = Val.assertValid(arg, module, func, *tags)

        fun isBool() = Val.assertIsBool(arg, module, func)

        fun decode(vararg tags: Val.Tag, decoder: TACExprFact.() -> TACExpr) =
            TACSymbol.Var(argName, Tag.Bit256).toUnique("!").let { decoded ->
                decoded to mergeMany(
                    hasTag(*tags),
                    assign(decoded, exp = decoder)
                )
            }

        fun decode(intType: IntType) =
            TACSymbol.Var(argName, Tag.Bit256).toUnique("!").let { decoded ->
                decoded to mergeMany(
                    hasTag(intType.tag, intType.smallTag),
                    intType.decodeVal(decoded, arg.asSym())
                )
            }

        return when (type) {
            SorobanType.i64 -> passthrough()
            SorobanType.u64 -> passthrough()

            SorobanType.Bool -> arg to Val.assertIsBool(arg, module, func)

            SorobanType.StorageType -> arg to Contract.assertValidStorageType(arg, module, func)

            SorobanType.AnyVal -> arg to hasTag()

            SorobanType.Void -> arg to hasTag(Val.Tag.Void)
            SorobanType.Error -> arg to hasTag(Val.Tag.Error)

            SorobanType.U32Val -> decode(Val.Tag.U32Val) { Val.decodeSmallInt(arg.asSym(), Val.Tag.U32Val) }
            SorobanType.I32Val -> decode(Val.Tag.I32Val) { Val.decodeSmallInt(arg.asSym(), Val.Tag.I32Val) }

            SorobanType.U64Val -> decode(IntType.U64)
            SorobanType.I64Val -> decode(IntType.I64)
            SorobanType.U128Val -> decode(IntType.U128)
            SorobanType.I128Val -> decode(IntType.I128)
            SorobanType.U256Val -> decode(IntType.U256)
            SorobanType.I256Val -> decode(IntType.I256)
            SorobanType.I64Object -> arg to hasTag(Val.Tag.I64Object)
            SorobanType.U64Object -> arg to hasTag(Val.Tag.U64Object)
            SorobanType.U128Object -> arg to hasTag(Val.Tag.U128Object)
            SorobanType.I128Object -> arg to hasTag(Val.Tag.I128Object)
            SorobanType.U256Object -> arg to hasTag(Val.Tag.U256Object)
            SorobanType.I256Object -> arg to hasTag(Val.Tag.I256Object)
            SorobanType.TimepointObject -> arg to hasTag(Val.Tag.TimepointObject)
            SorobanType.DurationObject -> arg to hasTag(Val.Tag.DurationObject)

            SorobanType.BytesObject -> arg to hasTag(Val.Tag.BytesObject)
            SorobanType.StringObject -> arg to hasTag(Val.Tag.StringObject)
            SorobanType.Symbol -> arg to hasTag(Val.Tag.SymbolSmall, Val.Tag.SymbolObject)
            SorobanType.SymbolObject -> arg to hasTag(Val.Tag.SymbolObject)
            SorobanType.VecObject -> arg to hasTag(Val.Tag.VecObject)
            SorobanType.MapObject -> arg to hasTag(Val.Tag.MapObject)
            SorobanType.AddressObject -> arg to hasTag(Val.Tag.AddressObject)
        }
    }
}
