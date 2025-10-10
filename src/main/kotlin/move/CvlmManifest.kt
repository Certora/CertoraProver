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

package move

import analysis.CommandWithRequiredDecls.Companion.mergeMany
import datastructures.stdcollections.*
import com.certora.collect.*
import config.*
import datastructures.*
import java.math.BigInteger
import java.nio.ByteBuffer
import move.MoveModule.*
import tac.*
import tac.generation.*
import utils.*
import vc.data.*

/** The name of the function that defines the manifest for its defining module. */
private const val moduleManifestName = "cvlm_manifest"

/**
    Gets rules, summaries, etc. from the CVLM module manifests.

    Module manifests are special functions in Move modules that identify the rules, summaries, and other
    "interesting" functions in the module.  A module's manifest must have the following form:

    ```
        fun cvlm_manifest() {
            // call functions from cvlr::manifest here
        }
    ```

    `cvlr::manifest` is a special module that defines functions for describing rules, summaries, etc.
 */
class CvlmManifest(val scene: MoveScene) {
    context(SummarizationContext)
    fun summarize(call: MoveCall) = summarizers[call.callee.name]?.invoke(this@SummarizationContext, call)

    fun maybeShadowType(type: MoveType.Struct): MoveType.Value? = shadowedTypes[type.name]?.invoke(type)

    enum class RuleType { USER_RULE, SANITY }

    private val rulesBuilder = mutableMapOf<MoveFunctionName, RuleType>()
    private val shadowedTypes = mutableMapOf<MoveDatatypeName, (MoveType.Struct) -> MoveType.Value>()
    private val summarizers = mutableMapOf<MoveFunctionName, context(SummarizationContext) (MoveCall) -> MoveBlocks>()
    private val targetFunctionsBuilder = mutableMapOf<MoveModuleName, MutableSet<MoveFunctionName>>()
    private val targetSanityModules = mutableSetOf<MoveModuleName>()

    val rules: Map<MoveFunctionName, RuleType> by lazy {
        rulesBuilder + targetSanityModules.flatMap { module ->
            targetFunctions(module).map {
                if (it in rulesBuilder) {
                    throw CertoraException(
                        CertoraErrorType.CVL,
                        "Target function $it appears in both user and sanity rules"
                    )
                }
                it to RuleType.SANITY
            }
        }
    }

    fun targetFunctions(module: MoveModuleName): Set<MoveFunctionName> =
        targetFunctionsBuilder[module].orEmpty()

    private fun addRule(rule: MoveFunctionName, ruleType: RuleType) {
        if (rulesBuilder.put(rule, ruleType) != null) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Rule $rule appears multiple times in the CVLM module manifests"
            )
        }
    }

    private fun addShadowedType(
        shadowedType: MoveDatatypeName,
        shadow: (MoveType.Struct) -> MoveType.Value
    ) {
        if (shadowedTypes.put(shadowedType, shadow) != null) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Type $shadowedType is shadowed multiple times in the CVLM module manifests"
            )
        }
    }

    private fun addSummarizer(
        summarized: MoveFunctionName,
        summarizer: context(SummarizationContext) (MoveCall) -> MoveBlocks
    ) {
        if (summarizers.put(summarized, summarizer) != null) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Function $summarized appears in multiple incompatible roles in the CVLM module manifests"
            )
        }
    }

    private fun addTargetFunction(
        manifestModule: MoveModuleName,
        func: MoveFunctionName
    ) {
        val moduleFunctions = targetFunctionsBuilder.getOrPut(manifestModule) { mutableSetOf() }
        if (!moduleFunctions.add(func)) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Target function $func appears multiple times in the $manifestModule module manifest"
            )
        }
    }

    private val functionStructName = MoveDatatypeName(
        MoveModuleName(Config.CvlmAddress.get(), "function"),
        "Function"
    )

    init {
        // Ensure cvlm::function::Function is represented as MoveType.Function, and no user code can override this.
        addShadowedType(functionStructName) { MoveType.Function }

        // Load all module manifest functions in the scene.
        scene.modules.forEach { moduleName ->
            scene.module(moduleName).functionDefinitions?.forEach { funcDef ->
                if (funcDef.function.name.simpleName == moduleManifestName) {
                    loadManifest(funcDef)
                }
            }
        }
    }

    private sealed class StackValue {
        data class String(val value: kotlin.String) : StackValue()
        data class Address(val value: BigInteger) : StackValue()
    }

    private fun loadManifest(funcDef: FunctionDefinition) {
        val manifestName = funcDef.function.name

        if (funcDef.function.typeParameters.isNotEmpty()) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Module manifest function $manifestName cannot have type parameters"
            )
        }
        if (funcDef.function.params.isNotEmpty()) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Module manifest function $manifestName cannot have parameters"
            )
        }
        if (funcDef.function.returns.isNotEmpty()) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Module manifest function $manifestName cannot return values"
            )
        }
        val manifestCode = funcDef.code ?: throw CertoraException(
            CertoraErrorType.CVL,
            "Module manifest function $manifestName does not have a code block"
        )

        // Interpret the manifest code.  We only allow constants, and calls to the manifest functions.
        val manifestModule = MoveModuleName(Config.CvlmAddress.get(), "manifest")
        val stack = ArrayDeque<StackValue>()
         manifestCode.instructions.forEach { inst ->
            when(inst) {
                is Instruction.LdConst -> {
                    val c = inst.index.deref()
                    when (c.type) {
                        SignatureToken.Vector(SignatureToken.U8) -> {
                            stack.addLast(
                                StackValue.String(
                                    ByteBuffer.wrap(c.data.toByteArray()).getUtf8String()
                                )
                            )
                        }
                        SignatureToken.Address -> {
                            stack.addLast(
                                StackValue.Address(
                                    ByteBuffer.wrap(c.data.toByteArray()).getAddress()
                                )
                            )
                        }
                        else -> {
                            throw CertoraException(
                                CertoraErrorType.CVL,
                                "Unsupported data type in module manifest function ${funcDef.qualifiedName}: ${c.type::class.simpleName}"
                            )
                        }
                    }
                }
                is Instruction.Call -> {
                    when (inst.index.deref().name) {
                        MoveFunctionName(manifestModule, "rule") -> rule(manifestName, stack)
                        MoveFunctionName(manifestModule, "module_sanity") -> moduleSanity(manifestName, stack)
                        MoveFunctionName(manifestModule, "summary") -> summary(manifestName, stack)
                        MoveFunctionName(manifestModule, "ghost") -> ghost(manifestName, stack)
                        MoveFunctionName(manifestModule, "hash") -> hash(manifestName, stack)
                        MoveFunctionName(manifestModule, "shadow") -> shadow(manifestName, stack)
                        MoveFunctionName(manifestModule, "field_access") -> fieldAccessor(manifestName, stack)
                        MoveFunctionName(manifestModule, "target") -> target(manifestName, stack)
                        MoveFunctionName(manifestModule, "target_sanity") -> targetSanity(manifestName, stack)
                        MoveFunctionName(manifestModule, "invoker") -> invoker(manifestName, stack)
                        else -> {
                            throw CertoraException(
                                CertoraErrorType.CVL,
                                "Unexpected call to ${inst.index.deref().name} in module manifest function ${funcDef.qualifiedName}"
                            )
                        }
                    }
                }
                is Instruction.Ret -> {
                    if (stack.isNotEmpty()) {
                        throw CertoraException(
                            CertoraErrorType.CVL,
                            "Expected no return values in module manifest function ${funcDef.qualifiedName}, but got ${stack.size} values"
                        )
                    }
                }
                else -> {
                    throw CertoraException(
                        CertoraErrorType.CVL,
                        "Unexpected instruction in module manifest function ${funcDef.qualifiedName}: ${inst::class.simpleName}"
                    )
                }
            }
        }
    }

    private fun rule(manifestName: MoveFunctionName, stack: ArrayDeque<StackValue>) {
        /*
            ```
            public native fun rule(ruleFunName: vector<u8>);
            ```

            Marks the named function in the current module as a rule.
         */

        val ruleNameValue = stack.removeLast() as StackValue.String
        val ruleName = MoveFunctionName(manifestName.module, ruleNameValue.value)
        val ruleDef = scene.maybeDefinition(ruleName)
        if (ruleDef == null) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Rule function $ruleName is not defined in the scene, but is referenced in module manifest function $manifestName"
            )
        }

        addRule(ruleName, RuleType.USER_RULE)
    }

    private fun moduleSanity(manifestName: MoveFunctionName, stack: ArrayDeque<StackValue>) {
        /*
            ```
            public native fun module_sanity(addr: address, mod: vector<u8>);
            ```

            Generates sanity rules for the public functions in `addr`::`mod`.
         */

        val moduleNameValue = stack.removeLast() as StackValue.String
        val moduleAddressValue = stack.removeLast() as StackValue.Address
        val moduleName = MoveModuleName(
            moduleAddressValue.value,
            moduleNameValue.value
        )

        val module = scene.module(moduleName)

        val functionNames = module.publicFunctionDefinitions.map { it.function.name }
        if (functionNames.isEmpty()) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Module $moduleName has no public functions to satisfy the module_sanity entry in $manifestName"
            )
        }

        functionNames.forEach {
            addRule(it, RuleType.SANITY)
        }
    }

    private fun targetSanity(manifestName: MoveFunctionName, stack: ArrayDeque<StackValue>) {
        /*
            ```
            public native fun target_sanity();
            ```

            Generates sanity rules for this module's target functions
         */
        unused(stack)
        if (!targetSanityModules.add(manifestName.module)) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Target function sanity for module ${manifestName.module} defined more than once"
            )
        }
    }

    private fun summary(manifestName: MoveFunctionName, stack: ArrayDeque<StackValue>) {
        /*
            ```
            public native fun summary(
                summaryFunName: vector<u8>,
                summarizedFunAddr: address,
                summarizedFunModule: vector<u8>,
                summarizedFunName: vector<u8>
            );
            ```

            Marks `summaryFunName` (in the current module) as a summary for
            `summarizedFunAddr::summarizedFunModule::summarizedFunName`
         */

        val summarizedFuncNameValue = stack.removeLast() as StackValue.String
        val summarizedFuncModuleValue = stack.removeLast() as StackValue.String
        val summarizedFuncAddressValue = stack.removeLast() as StackValue.Address
        val summaryFuncNameValue = stack.removeLast() as StackValue.String

        val summarizedName = MoveFunctionName(
            MoveModuleName(
                summarizedFuncAddressValue.value,
                summarizedFuncModuleValue.value
            ),
            summarizedFuncNameValue.value
        )
        val summaryName = MoveFunctionName(manifestName.module, summaryFuncNameValue.value)

        val summaryDef = scene.maybeDefinition(summaryName)
        if (summaryDef == null) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Summary function $summaryName is not defined in the scene, but is referenced in module manifest function $manifestName"
            )
        }
        val summarizedDef = scene.maybeDefinition(summarizedName)
        if (summarizedDef == null) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Function $summarizedName is not defined in the scene, but is referenced in module manifest function $manifestName"
            )
        } else {
            if (summarizedDef.function.typeParameters != summaryDef.function.typeParameters) {
                throw CertoraException(
                    CertoraErrorType.CVL,
                    "Summary function $summaryName has different type parameters than the summarized function $summarizedName in module manifest function $manifestName"
                )
            }
            if (summarizedDef.function.params != summaryDef.function.params) {
                throw CertoraException(
                    CertoraErrorType.CVL,
                    "Summary function $summaryName has different parameters than the summarized function $summarizedName in module manifest function $manifestName"
                )
            }
            if (summarizedDef.function.returns != summaryDef.function.returns) {
                throw CertoraException(
                    CertoraErrorType.CVL,
                    "Summary function $summaryName has different return types than the summarized function $summarizedName in module manifest function $manifestName"
                )
            }
        }

        addSummarizer(summarizedName) { call ->
            with(scene) {
                compileFunctionCall(
                    call.copy(
                        callee = MoveFunction(summaryDef.function, call.callee.typeArguments)
                    )
                )
            }
        }
    }

    private fun ghost(manifestName: MoveFunctionName, stack: ArrayDeque<StackValue>) {
        /*
            ```
            public native fun ghost(ghostFunName: vector<u8>);
            ```

            Marks `ghostFunName` (in the current module) as a ghost variable/mapping.  The function may have parameters,
            and may have type parameters.  If the function returns a reference, it will return a reference to a unique
            location for the given function/arguments/type arguments.  If the function returns a value, it will return
            the contents of unique location for the given function/arguments/type arguments.
         */

        val ghostNameValue = stack.removeLast() as StackValue.String
        val ghostName = MoveFunctionName(manifestName.module, ghostNameValue.value)
        val ghostDef = scene.maybeDefinition(ghostName)
        if (ghostDef == null) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Ghost function $ghostName is not defined in the scene, but is referenced in module manifest function $manifestName"
            )
        }
        if (ghostDef.code != null) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Ghost function $ghostName must be declared as a `native fun`."
            )
        }
        if (ghostDef.function.returns.size != 1) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Ghost function $ghostName must return exactly one value."
            )
        }

        addSummarizer(ghostName) { call ->
            singleBlockSummary(call) {
                /*
                    - For a nongeneric ghost function with no parameters `native fun ghost(): &R`, we generate a single
                      TAC variable of type `R.toTag()`.
                    - For a nongeneric ghost function with a single numeric parameter, we generate a single TAC variable
                      of type `MoveTag.GhostArray(R)`, and use the parameter as an index into the array.
                    - For a nongeneric ghost function with multiple parameters, we generate a single TAC variable of
                      type `MoveTag.GhostArray(R)`, and hash the parameters to get the index.
                    - Generic ghost functions work exactly as above, except we generate a separate variable for each
                      instantiation of the ghost function.
                */

                val ghostFunc = call.callee

                fun ghostVar(tag: Tag): TACSymbol.Var {
                    val name = ghostFunc.name.toVarName()
                    return TACSymbol.Var(
                        name,
                        tag,
                        // treat ghost variables as keywords, to preserve their names in TAC dumps
                        meta = MetaMap(TACSymbol.Var.KEYWORD_ENTRY to TACSymbol.Var.KeywordEntry(name))
                    ).letIf(ghostFunc.typeArguments.isNotEmpty()) {
                            it.withSuffix(
                                ghostFunc.typeArguments.joinToString("!") { it.symNameExt() },
                                "!"
                            )
                        }
                }

                val (isRefReturn, resultValType) = when (val resultType = ghostFunc.returns[0]) {
                    is MoveType.Reference -> true to resultType.refType
                    is MoveType.Value -> false to resultType
                }

                val refVar = if (isRefReturn) {
                    call.returns[0]
                } else {
                    TACKeyword.TMP(MoveTag.Ref(resultValType))
                }

                val makeRef = when {
                    ghostFunc.params.isEmpty() -> {
                        // No parameters: just use a simple TAC variable
                        TACCmd.Move.BorrowLocCmd(
                            ref = refVar,
                            loc = ghostVar(resultValType.toTag()).ensureHavocInit(resultValType)
                        ).withDecls(refVar)
                    }
                    ghostFunc.params.size == 1 && ghostFunc.params[0] is MoveType.Bits -> {
                        // A single numeric parameter: use it as an index into a ghost array
                        val ghostArrayRef = TACKeyword.TMP(MoveTag.Ref(MoveType.GhostArray(resultValType)))
                        mergeMany(
                            TACCmd.Move.BorrowLocCmd(
                                ref = ghostArrayRef,
                                loc = ghostVar(MoveTag.GhostArray(resultValType)).ensureHavocInit()
                            ).withDecls(ghostArrayRef),
                            TACCmd.Move.GhostArrayBorrowCmd(
                                dstRef = refVar,
                                arrayRef = ghostArrayRef,
                                index = call.args[0]
                            ).withDecls(refVar)
                        )
                    }
                    else -> {
                        // Hash the arguments to get an index into a ghost array
                        val hash = TACKeyword.TMP(Tag.Bit256)
                        val ghostArrayRef = TACKeyword.TMP(MoveTag.Ref(MoveType.GhostArray(resultValType)))
                        mergeMany(
                            CvlmHash.hashArguments(hash, call),
                            TACCmd.Move.BorrowLocCmd(
                                ref = ghostArrayRef,
                                loc = ghostVar(MoveTag.GhostArray(resultValType)).ensureHavocInit()
                            ).withDecls(ghostArrayRef),
                            TACCmd.Move.GhostArrayBorrowCmd(
                                dstRef = refVar,
                                arrayRef = ghostArrayRef,
                                index = hash
                            ).withDecls(refVar)
                        )
                    }
                }

                if (isRefReturn) {
                    makeRef
                } else {
                    mergeMany(
                        makeRef,
                        TACCmd.Move.ReadRefCmd(
                            dst = call.returns[0],
                            ref = refVar
                        ).withDecls(call.returns[0])
                    )
                }
            }
        }
    }

    private fun hash(manifestName: MoveFunctionName, stack: ArrayDeque<StackValue>) {
        /*
            ```
            public native fun hash(hashFunName: vector<u8>);
            ```

            Marks `hashFunName` (in the current module) as a hash function.  The function must return a single u256
            value, and must have at least one parameter and/or type parameter.  When called, the function will hash its
            arguments and/or type arguments, and return a u256 value that is unique for the given
            function/arguments/type arguments.
         */

        val hashNameValue = stack.removeLast() as StackValue.String
        val hashName = MoveFunctionName(manifestName.module, hashNameValue.value)
        val hashDef = scene.maybeDefinition(hashName)
        if (hashDef == null) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Hash function $hashName is not defined in the scene, but is referenced in module manifest function $manifestName"
            )
        }
        if (hashDef.code != null) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Hash function $hashName must be declared as a `native fun`."
            )
        }
        if (hashDef.function.returns.size != 1 || hashDef.function.returns[0] !is SignatureToken.U256) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Hash function $hashName must return u256."
            )
        }
        if (hashDef.function.params.isEmpty() && hashDef.function.typeParameters.isEmpty()) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Hash function $hashName must have at least one parameter or type parameter."
            )
        }

        addSummarizer(hashName) { call ->
            singleBlockSummary(call) {
                CvlmHash.hashArguments(call.returns[0], call)
            }
        }
    }

    private fun shadow(manifestName: MoveFunctionName, stack: ArrayDeque<StackValue>) {
        /*
            ```
            public native fun shadow(shadowFunName: vector<u8>);
            ```

            Marks `shadowFunName` (in the current module) as a shadow mapping.  The function must take a reference to a
            struct as its first argument; this is the shadowed type.  The function may take additional arguments.
            Generic shadow functions must have the same type parameters as the shadowed type.

            The shadowed type is replaced with a "ghost"-style shadow variable wherever it appears.  The shadow mapping
            function is used to get at locations from the shadow variable.
         */

        val shadowFuncNameValue = stack.removeLast() as StackValue.String
        val shadowFuncName = MoveFunctionName(manifestName.module, shadowFuncNameValue.value)
        val shadowFuncDef = scene.maybeDefinition(shadowFuncName)
        if (shadowFuncDef == null) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Shadow mapping $shadowFuncName is not defined in the scene, but is referenced in module manifest function $manifestName"
            )
        }
        if (shadowFuncDef.code != null) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Shadow mapping $shadowFuncName must be declared as a `native fun`."
            )
        }
        val shadowFunc = shadowFuncDef.function
        val shadowedToken =
            (shadowFunc.params.firstOrNull() as? SignatureToken.Reference)
            ?.type as? SignatureToken.DatatypeValue
            ?: throw CertoraException(
                CertoraErrorType.CVL,
                "Shadow mapping $shadowFuncName must have at least one parameter, which is a reference to a struct type."
            )
        val shadowedTypeHandle = shadowedToken.handle
        if (shadowFunc.typeParameters != shadowedTypeHandle.typeParameters.map { it.constraints }) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Shadow mapping $shadowFuncName must have the same type parameters as the shadowed type ${shadowedTypeHandle.name}."
            )
        }
        if (shadowedToken.typeArguments.withIndex().any { (index, type) ->
            type !is SignatureToken.TypeParameter || type.index.index != index
        }) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Shadowed type ${shadowedTypeHandle.name} must have the same type arguments as shadow mapping $shadowFuncName."
            )
        }
        val shadowedTypeDef = scene.definition(shadowedTypeHandle)
        if (shadowFunc.returns.size != 1 || shadowFunc.returns[0] !is SignatureToken.Reference) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Shadow mapping $shadowFuncName must return a reference type."
            )
        }

        addShadowedType(shadowedTypeDef.name) { shadowedType ->
            // Instantiate the shadow type for each instantiation of the shadowed type.
            with(scene) {
                val mappingParams = shadowFunc.params.drop(1).map { it.toMoveType(shadowedType.typeArguments) }
                val mappingReturn = shadowFunc.returns[0].toMoveType(shadowedType.typeArguments)

                val shadowedTypeNames = shadowedTypes.keys.toTreapSet()
                if ((mappingParams + mappingReturn).any { shadowedTypeNames.containsAny(it.consituentStructNames()) }) {
                    throw CertoraException(
                        CertoraErrorType.CVL,
                        "Shadow mapping $shadowFuncName has parameters or return type that reference shadowed types."
                    )
                }

                val resultType = (mappingReturn as MoveType.Reference).refType

                if (resultType is MoveType.Struct && resultType.name in shadowedTypes) {
                    throw CertoraException(
                        CertoraErrorType.CVL,
                        "Shadow mapping $shadowFuncName returns a type that is shadowed: ${resultType.name}."
                    )
                }

                if (mappingParams.isEmpty()) {
                    // No additional parameters: just use a simple shadow variable
                    resultType
                } else {
                    // Use a ghost array to store the shadowed values, indexed by the additional parameters
                    MoveType.GhostArray(resultType)
                }
            }
        }

        // Implement the shadow mapping function itself
        addSummarizer(shadowFuncName) { call ->
            singleBlockSummary(call) {
                when {
                    call.callee.params.size == 1 -> {
                        // No additional arguments - just return the reference to the shadow variable
                        assign(call.returns[0]) { call.args[0].asSym() }
                    }
                    call.callee.params.size == 2 && call.callee.params[1] is MoveType.Bits -> {
                        // A single additional numeric argument - use it as an index into a ghost array
                        TACCmd.Move.GhostArrayBorrowCmd(
                            dstRef = call.returns[0],
                            arrayRef = call.args[0],
                            index = call.args[1]
                        ).withDecls(call.returns[0])
                    }
                    else -> {
                        // Hash the additional arguments to get the index into the ghost array
                        val hash = TACKeyword.TMP(Tag.Bit256)
                        mergeMany(
                            CvlmHash.hashArguments(hash, call, skipFirstArg = true),
                            TACCmd.Move.GhostArrayBorrowCmd(
                                dstRef = call.returns[0],
                                arrayRef = call.args[0],
                                index = hash
                            ).withDecls(call.returns[0])
                        )
                    }
                }
            }
        }
    }

    private fun fieldAccessor(manifestName: MoveFunctionName, stack: ArrayDeque<StackValue>) {
        /*
            ```
            public native fun field_accessor(
                accessorFunName: vector<u8>,
                fieldName: vector<u8>
            );
            ```

            Marks `accessorFunName` (in the current module) as a field accessor for the field named `fieldName`.  This
            function must return a reference type, and must take exactly one parameter of type `&S` where `S` is a
            struct or generic parameter.  When called, the function will return a reference to the field named
            `fieldName` in the struct that is passed as the parameter.
         */

        val fieldNameValue = stack.removeLast() as StackValue.String
        val fieldName = fieldNameValue.value
        val accessorNameValue = stack.removeLast() as StackValue.String
        val accessorName = MoveFunctionName(manifestName.module, accessorNameValue.value)
        val accessorDef = scene.maybeDefinition(accessorName)
        if (accessorDef == null) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Field accessor function $accessorName is not defined in the scene, but is referenced in module manifest function $manifestName"
            )
        }
        if (accessorDef.code != null) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Field accessor function $accessorName must be declared as a `native fun`."
            )
        }
        if (accessorDef.function.params.size != 1 || accessorDef.function.params[0] !is SignatureToken.Reference) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Field accessor function $accessorName must take exactly one reference parameter."
            )
        }
        if (accessorDef.function.returns.size != 1 || accessorDef.function.returns[0] !is SignatureToken.Reference) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Field accessor function $accessorName must return a reference type."
            )
        }

        /*
            Note: we don't validate the field name until we're asked for a summary.  This allows the definition of
            generic field accessors, where we don't know the type of the struct until we see a specific instantiation of
            the accessor function.
         */

        addSummarizer(accessorName) { call ->
            singleBlockSummary(call) {
                val dstRef = call.returns[0]
                val srcRef = call.args[0]

                // Find the field index in the struct type
                val refTag = srcRef.tag as MoveTag.Ref
                val structType = refTag.refType as? MoveType.Struct
                if (structType == null) {
                    throw CertoraException(
                        CertoraErrorType.CVL,
                        "Field accessor ${call.callee.name} is called on a non-struct type ${refTag.refType}."
                    )
                }

                val fields = structType.fields ?: throw CertoraException(
                    CertoraErrorType.CVL,
                    "Illegal field accessor for native struct $structType."
                )
                val fieldIndex = fields.indexOfFirst { it.name == fieldName }

                if (fieldIndex < 0) {
                    throw CertoraException(
                        CertoraErrorType.CVL,
                        "Field '$fieldName' not found in struct type $structType, from field accessor ${call.callee.name}."
                    )
                }
                val fieldType = fields[fieldIndex].type
                val returnType = (call.callee.returns[0] as MoveType.Reference).refType
                if (fieldType != returnType) {
                    throw CertoraException(
                        CertoraErrorType.CVL,
                        "Field '$fieldName' in struct type $structType has type $fieldType, but expected $returnType from field accessor ${call.callee.name}."
                    )
                }

                TACCmd.Move.BorrowFieldCmd(
                    dstRef = dstRef,
                    srcRef = srcRef,
                    fieldIndex = fieldIndex
                ).withDecls(dstRef)
            }
        }
    }

    private fun target(manifestName: MoveFunctionName, stack: ArrayDeque<StackValue>) {
        /*
            public native fun target(module_address: address, module_name: vector<u8>, function_name: vector<u8>);

            Names a target function for the parametric rules in this scene
         */

        val targetNameValue = stack.removeLast() as StackValue.String
        val targetModuleValue = stack.removeLast() as StackValue.String
        val targetAddressValue = stack.removeLast() as StackValue.Address

        val targetName = MoveFunctionName(
            MoveModuleName(
                targetAddressValue.value,
                targetModuleValue.value
            ),
            targetNameValue.value
        )

        val targetDef = scene.maybeDefinition(targetName)
        if (targetDef == null) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Target $targetName is not defined in the scene, but is referenced in module manifest function $manifestName"
            )
        }

        addTargetFunction(manifestName.module, targetName)
    }

    private fun invoker(manifestName: MoveFunctionName, stack: ArrayDeque<StackValue>) {
        /*
            public native fun invoker(function_name: vector<u8>);

            Registers `function_name` in this module as an "invoker" function for the targets of parametric rules.

            The invoker's first parameter must be of type `cvlm::function::Function`.  No other parameters can be
            functions.  Additional parameters will be forwarded to the target function, by matching parameters by type.
            Any target function parameters that do not have corresponding invoker parameters will be havoc'd.
         */
        val invokerNameValue = stack.removeLast() as StackValue.String

        val invokerName = MoveFunctionName(manifestName.module, invokerNameValue.value)
        val invokerDef = scene.maybeDefinition(invokerName)
        if (invokerDef == null) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Invoker function $invokerName is not defined in the scene, but is referenced in module manifest function $manifestName"
            )
        }
        if (invokerDef.code != null) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Invoker function $invokerName must be declared as a `native fun`."
            )
        }
        if (invokerDef.function.returns.isNotEmpty()) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Invoker function $invokerName must not have any return values."
            )
        }
        if (invokerDef.function.typeParameters.isNotEmpty()) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Invoker function $invokerName must not have type parameters."
            )
        }
        if (invokerDef.function.params.size < 1 ||
            (invokerDef.function.params[0] as? SignatureToken.Datatype)?.handle?.name != functionStructName
        ) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Invoker function $invokerName must have an initial parameter of type $functionStructName."
            )
        }
        if (
            invokerDef.function.params.drop(1).any {
                (it as? SignatureToken.Datatype)?.handle?.name == functionStructName
            }
        ) {
            throw CertoraException(
                CertoraErrorType.CVL,
                "Invoker function $invokerName must have only one parameter of type $functionStructName."
            )
        }

        addSummarizer(invokerName) { invokerCall ->
            singleBlockSummary(invokerCall) {
                TACCmd.Simple.SummaryCmd(
                    InvokerCall(
                        invokerName,
                        invokerCall.args
                    )
                ).withDecls()
            }
        }
    }
}
