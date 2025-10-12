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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package prefixgen

import bridge.types.PrimitiveType
import bridge.types.SolidityTypeDescription
import datastructures.stdcollections.*
import prefixgen.CodeGen.pp
import prefixgen.CodeGen.storagePP
import prefixgen.data.*
import utils.*
import kotlin.collections.single

/**
 * Class for pretty printing [SimpleSolidityAst] representing fuzz tests
 * to Solidity code.
 *
 * [CompilationInput] defines the minimum information for the pretty printing;
 * the generation can also be tweaked by overriding [finalHook], see
 * the documentation for [compileFuzzContract] for details.
 */
open class Compiler {
    /**
     * The inputs for a compilation. In addition to the [ast] of the fuzz test, this includes
     * the imports necessary (represented by [importManager]) and any prefix binding to be done in the special `bind`
     * function in [fixedPrefix].
     */
    interface CompilationInput {
        val ast: List<SimpleSolidityAst>
        val importManager: ImportManager
        val fixedPrefix: FixedPrefix
    }

    /**
     * Override to add arbitrary code before the final `vm.assertTrue(false);`. This can be used to fine tune the kind
     * of counterexamples searched for, see [Mutator] for an example.
     */
    open fun finalHook() : String {
        return ""
    }

    /**
     * The result of the compilation. [soliditySource] is the self contained, pretty printed fuzz test.
     * [structFields] are the fields of the `InputState` struct parameter; [otherParams] are the other
     * top-level parameters (usually corresponding to "bound" variables, see [BoundVariableManager]).
     */
    data class CompilationResult(
        val soliditySource: String,
        val structFields: List<VarDef>,
        val otherParams: List<VarDef>
    )

    companion object {
        const val fuzzFunctionName: String = "test_fuzzInterestingInputs"

        const val fuzzContractName: String = "InputSequenceGenerator"

        fun compileFuzzContract(
            s: CompilationInput,
            setupCode: String,
            initialBindings: Map<String, InitialBinding>
        ): CompilationResult {
            return Compiler().compileFuzzContract(
                s, setupCode, initialBindings
            )
        }
    }

    /**
     * Private class for fresh temp variables
     */
    private class TempVarManager {
        var idCounter = 0

        fun fresh(prefix: String) = "${prefix}_tmp${idCounter++}"
    }

    /**
     * Records the field names and types of values in the `InputState`.
     */
    private class StructFieldManager {
        val fields = mutableListOf<VarDef>()

        /**
         * Register a new input field [rawId] with type [ty] in the input state struct,
         * and return the syntax for accessing said field.
         *
         * It is expected (but not checked) that this function is never called
         * with the same [rawId] multiple times; the behavior of the compilation
         * is undefined if you do so.
         */
        fun readInputValue(rawId: String, ty: SolidityTypeDescription) : String {
            fields.add(
                VarDef(
                    ty, rawId
                )
            )
            return "${SequenceGenerator.inputParamName}.$rawId"
        }
    }

    /**
     * Manage variables which have some a priori (smallish) bound.
     * For a fixed number of these "bound variables" (16), we use foundry's fixture mechanism to significantly
     * reduce the search space by seeding the fuzzing process with only the values within that bound.
     *
     * Any other "bound variables" beyond these 16 are added to the [StructFieldManager].
     *
     * As to why: it *extremely* easy to blow the stack in solidity, and this is exacerbated by excessive numbers
     * of parameters. Thus, we choose a bounded number (16) (the maximum depth of stack variable that can be accessed)
     * in hopes of avoiding blowing the stack.
     *
     * As an additional optimization, we immediately move the values of these top level
     * parameters into a special struct we call the `DiscriminatorStruct`. This prevents
     * the discriminator variables from clogging up the stack and increasing the likelihood of stack blowing.
     *
     * [mainBody] is the same mutable list found in [CompilationState].
     * Obvious care must be taken when using it, but [mainBody] is "append only", and unrelated statements can
     * be safely interleaved, so less care is required than you might think...
     */
    private class BoundVariableManager(
        val structField: StructFieldManager,
        val mainBody: MutableList<String>,
        val tempVarManager: TempVarManager
    ) {
        /**
         * A value [rawId] which ranges from 0 - [maxValueIncl].
         * If [exclude] is non-zero, that value is specifically exlucded from this range (usually
         * 0).
         */
        data class BoundedValue(
            @SoliditySourceIdentifier
            val rawId: String,
            val maxValueIncl: Int,
            val exclude: Int?
        )

        /**
         * The bounded values registered so far (capped at 16).
         */
        val boundedVarDefs = mutableListOf<BoundedValue>()

        companion object {
            const val discStructName = "disc"
            const val discStructType = "DiscriminatorStruct"
            const val maxBoundedValues = 16
        }

        fun readBoundedValue(s: String, maxValueIncl: Int, exclude: Int?) : String {
            val boundedValueName = tempVarManager.fresh("${s}_Bounded")

            /**
             * readExpr is the syntax for accessing the (raw) bounded value
             */
            val readExpr = if(boundedVarDefs.size < maxBoundedValues) {
                /**
                 * Add a new bounded value.
                 */
                boundedVarDefs.add(BoundedValue(
                    rawId = s,
                    maxValueIncl = maxValueIncl,
                    exclude = exclude
                ))
                /**
                 * Read from the *discriminator* struct, not from the parameter name (see discussion of stack management
                 * above).
                 */
                "$discStructName.$s"
            } else {
                /**
                 * Just read the value from the struct
                 */
                structField.readInputValue(rawId = s, SolidityTypeDescription.Primitive(PrimitiveType.uint8))
            }
            /**
             * Bound the value explicitly using [CodeGen.bound].
             *
             * Q: Why do you need to explicitly bound a value that has a fixture? In other words, why do we need
             * this for both sources of bound values.
             * A: GREAT QUESTION. Even with fixtures, Foundry will, with small probability,
             * ignore the fixture and generate a random value in the range allowed by the type. So we still
             * need this defensive code to account for that scenario.
             */
            mainBody.add("""
                uint $boundedValueName = ${CodeGen.bound(readExpr, maxValueIncl)};
            """.trimIndent())
            return boundedValueName
        }

        /**
         * Generate the source code for managing bindings.
         * This includes:
         * [stateBinding]: the code that moves the bound parameters into the discriminator struct
         * [stateDefn]: the code that defines the discriminator struct
         * [fixtureDef]: the definitions of the fixtures for the bound parameters
         * [extraParams]: the names and types of the bound parameters
         */
        data class BoundData(
            val stateBinding: String,
            val stateDefn: String,
            val fixtureDef: String,
            val extraParams: List<VarDef>
        )

        fun compile() : BoundData {
            if(boundedVarDefs.isEmpty()) {
                return BoundData(
                    "", "", "", listOf()
                )
            }
            val fixtureDef = mutableListOf<String>()
            val topLevelParams = mutableListOf<VarDef>()
            val bindingCode = mutableListOf<String>(
                "$discStructType memory $discStructName;"
            )
            val stateDef = mutableListOf<String>(
                """
                    struct $discStructType {
                """.trimIndent()
            )
            for((rawId, max, excl) in boundedVarDefs) {
                val literalOptions = (0 .. max).filter {
                    it != excl
                }.map { ind ->
                    "uint8($ind)"
                }

                /**
                 * So, funnily enough, the documentation for foundry *insists* that the syntax for fixtures
                 * is: `function fixtureParamName() ...`
                 *
                 * However, looking at the source code, it appears the name format is *actually* `fixture_paramName`.
                 * (see here: https://github.com/foundry-rs/foundry/blob/05d4a02f95ca0d3c5b2017ec925dfd0380bc3ab7/crates/evm/fuzz/src/strategies/uint.rs#L77)
                 * Go figure!
                 */
                val numOptions = literalOptions.size
                /**
                 * This simply generates a function with the expected name that returns a list of the potential
                 * bound values.
                 */
                fixtureDef.add("""
                    function fixture_${rawId}() public returns (uint8[$numOptions] memory) {
                       return ${literalOptions.joinToString(", ", prefix="[", postfix = "]")};
                    }
                """.trimIndent())

                /**
                 * Add a definition for the param
                 */
                topLevelParams.add(VarDef(
                    ty = SolidityTypeDescription.builtinPrimitive("uint8")!!,
                    rawId = rawId
                ))

                /**
                 * move the param into the struct
                 */
                bindingCode.add(
                    "$discStructName.$rawId = $rawId;"
                )
                /**
                 * Add the relevant field to the struct declaration
                 */
                stateDef.add("uint8 $rawId;")
            }
            stateDef.add("}")
            return BoundData(
                extraParams = topLevelParams,
                fixtureDef = fixtureDef.joinToString("\n"),
                stateBinding = bindingCode.joinToString("\n"),
                stateDefn = stateDef.joinToString("\n")
            )
        }

    }

    /**
     * The compilation state, consisting of the [ArrayLiteralManager], [TempVarManager], [StructFieldManager],
     * and [BoundVariableManager].
     *
     * In addition, we have [mainBody], which is the list of statements for the fuzz test.
     * Obvious care must be taken when appending to this as it is mutable and "global" to the compilation process,
     * but this is private so the danger of screw ups is minimized.
     *
     * In addition, the [Discriminator] used for binding some `sourceId` is incrementally recorded in [aliasData].
     * This is crucial to the [compileAliasIndexSelection] code.
     */
    private class CompilationState(
        val mainBody: MutableList<String>,
        val bvm: BoundVariableManager,
        val fieldManager: StructFieldManager,
        val tvm: TempVarManager,
        val arrayManager: ArrayLiteralManager
    ) {
        val aliasData = mutableMapOf<String, Discriminator>()
    }

    /**
     * Record the [ArrayPrototype] that are needed to compile the [SimpleSolidityAst.BindArray] statements.
     */
    private class ArrayLiteralManager {
        val literals = mutableSetOf<ArrayPrototype>()

        fun getArrayLiteral(
            ty: SolidityTypeDescription.Primitive,
            arity: Int
        ) : String {
            val element = ArrayPrototype(
                ty = ty.primitiveName,
                arity = arity
            )
            literals.add(element)
            return element.funcIdent
        }
    }

    /**
     * One of the variants of alias selection.
     */
    context(CompilationState)
    private fun compileChoice(
        choice: SimpleSolidityAst.BindInputChoice
    ) {
        /**
         * Bind the input value (the "base case" for alias selection).
         */
        val inputValue = fieldManager.readInputValue(
            choice.inputId, choice.ty
        )
        if(choice.aliases == null) {
            /**
             * No aliases? just bind the inpu9t field value then
             */
            mainBody.add("""
                ${choice.ty.pp()} ${choice.boundId} = $inputValue;
            """.trimIndent())
            return
        }
        /**
         * Record our alias data for later (see [compileAliasIndexSelection])
         */
        aliasData[choice.boundId] = choice.aliases
        /**
         * Bind the [Discriminator.selectedId] for our [prefixgen.data.SimpleSolidityAst.BindInputChoice.aliases]
         * field.
         */
        choice.aliases.compileAliasBoundRead()
        /**
         * Case switch over the selected id: if it is `i + 1`, use alias `i` (using the conversion syntax of
         * [Alias.syntaxFormat]), otherwise in the base case (0) use the fresh inputValue.
         */
        val bindingExpr = choice.aliases.aliases.foldIndexed(inputValue) { index: Int, acc: String, alias: Alias ->
            "(${choice.aliases.selectedId} == ${index + 1} ? ${alias.apply()} : $acc)"
        }
        mainBody.add(
            """
                ${choice.ty.pp()} ${choice.boundId} = $bindingExpr;
            """.trimIndent()
        )
    }

    /**
     * Register a new bounded value for this [Discriminator], and then bind the
     * [Discriminator.selectedId] via [compileAliasIndexSelection].
     */
    context(CompilationState)
    private fun Discriminator.compileAliasBoundRead() {
        val boundedValue = bvm.readBoundedValue(discriminator, aliases.size, if(must) { 0 } else { null })
        compileAliasIndexSelection(this, boundedValue)

    }

    /**
     * The must version of binding.
     */
    context(CompilationState)
    private fun compileMustAliasBinding(
        must: SimpleSolidityAst.BindMust
    ) {
        /**
         * This is *I believe* redundant because nothing should try to alias the value bound here.
         * If they do, it will always fail.
         */
        aliasData[must.boundId] = must.discriminator
        must.discriminator.compileAliasBoundRead()
        /**
         * For must bindings, our "fallthrough" base case is the case when selectedId == 1.
         * We know selectedId must be in the range 1 .. aliases.size, so if selectedId
         * is any of 2 .. aliases.size, we know it must be 1.
         */
        val remainder = must.discriminator.aliases.asSequence().withIndex().drop(1)
        val baseCaseExpr = must.discriminator.aliases.first().let { alias ->
            alias.syntaxFormat.format(alias.id)
        }
        val bindingExpr = remainder.fold(baseCaseExpr) { acc, (ind, aliasExpr) ->
            "(${must.discriminator.selectedId} == ${ind + 1} ? ${aliasExpr.apply()} : $acc)"
        }
        mainBody.add(
            "${must.ty.pp()} ${must.boundId} = $bindingExpr;"
        )
    }

    /**
     * Compile the dynamic array "literal" syntax.
     *
     * In fact, we generate a bound on the length of the array,
     * and then generate multiple constructor functions for dynamic arrays with known size k (see [ArrayLiteralManager]
     * and [generateArrayLitFunc]).
     */
    context(CompilationState)
    private fun compileArrayLiteral(
        arrayLit: SimpleSolidityAst.BindArray
    ) {
        /**
         * The maximum length of the array is the number of (potential) elements.
         */
        val boundLength = bvm.readBoundedValue(
            arrayLit.lengthSym, arrayLit.elems.size, null
        )

        /**
         * Incrementally built list of arguments to the array literal function.
         * It is an invariant of the following loop that at the start
         * of iteration `i`, this list will have `i` elements.
         */
        val argList = mutableListOf<String>()

        /**
         * Incrementally built list of array constructions via the array literal functions.
         * It is an invariant that the construction expression at index `i` will be for an
         * array with length `i`. Further, in the following loop, at the start
         * of iteration `i` this list will have length `i`.
         */
        val invocations = mutableListOf<String>()
        var i = 0
        while(true) {
            /**
             * By our invariant; `argList` has length `i`. Thus we are constructing an array
             * of size `i`. Further, `invocations` must have length `i`, the element we append onto it will
             * be at index `i`; satisfying our invariant.
             */
            val arrayVar = arrayManager.getArrayLiteral(
                arrayLit.elemType,
                argList.size
            )
            invocations.add(
                "$arrayVar${argList.joinToString(", ", prefix = "(", postfix = ")")}"
            )
            if(i == arrayLit.elems.size) {
                break
            }
            /**
             * i < arrayList.elems.size, and we are now pushing the next element onto the list.
             * By the same reasoning of `invocations` above, this will be at index `i`, meaning the length
             * of the list in the next iteration will be `i + 1`.
             */
            argList.add(arrayLit.elems[i].rawId)
            i++
        }
        /**
         * We now have in `invocations` a list of
         * ```
         * [ make_array_length_0, make_array_length_1, ... make_array_length_k ]
         * ```
         * where `make_array_length_i` is a solidity expression that makes an array of length `i`.
         * Switch over the chosen length `boundLength`, using the syntax at index `i`.
         */
        val rem = invocations.asSequence().withIndex().drop(1)
        val fst = invocations.first()
        val bindingExp = rem.fold(fst) { elseCase: String, (len, constr) ->
            "($boundLength == $len ? $constr : $elseCase)"
        }
        mainBody.add(
            """
                ${arrayLit.elemType.pp()}[] memory ${arrayLit.rawId} = $bindingExp;
            """.trimIndent()
        )
    }

    /**
     * Compute the value for [Discriminator.selectedId].
     *
     * This value is this switched over to actually use the selected value.
     * [boundedValue] is the syntax giving the value of [discr]'s [Discriminator.discriminator];
     * as noted in [BoundVariableManager] this actually requires multiple statements and extra logic.
     *
     * However, why is [Discriminator.selectedId] not just the value of [boundedValue]?
     * Well, recall that the aliases are selected from all known bindings with compatible types
     * (see [SequenceGenerator.StateAccumulator.getAliases]). However, some of those bindings
     * may themselves be aliases. If that is the case, i.e., one of *our* potential aliases A
     * was ITSELF chosen to be an alias, then we shouldn't alias with A. While it would *syntactically*
     * valid to do so, it would *falsely* imply that the value we are binding aliases with A,
     * when really it aliases what A is aliasing. This both helps cut down on the search space (if A
     * is itself an alias of another identifier B, then we don't have to consider aliasing A)
     * and helps illuminate the "true" must aliasing relationships (see [Reducer]).
     *
     * This is the role of [CompilationState.aliasData]. When computing [discr]'s [Discriminator.selectedId],
     * we check the value chosen for [boundedValue]. If that value corresponds to an id that itself
     * has some [Discriminator] d, we also check to see whether d.[Discriminator.selectedId] == 0.
     * If it doesn't, then we shouldn't try to alias with `d`.
     *
     * Thus, the expression we bind to [Discriminator.selectedId] is:
     * ```
     * selectedId = if(boundedValue == 1 && (alias1 !in aliasData || aliasData[alias1].selectedId == 0)) {
     *    1
     * } else if(boundedValue == 2 && (alias2 !in aliasData || aliasData[alias2].selectedId == 0)) {
     *    2
     * } else ... {
     *    0
     * }
     * ```
     * The above mixes solidity code and "metacode" (the alias2 !in ...) but hopefully the meaning here is clear.
     *
     * Finally, if [discr] is [Discriminator.must], then this will also emit and assumption that the selectedId cannot be
     * 0.
     */
    context(CompilationState)
    private fun compileAliasIndexSelection(
        discr: Discriminator,
        boundedValue: String
    ) {
        val baseCase = "0"
        val selectionExpr = discr.aliases.foldIndexed(baseCase) { index: Int, acc: String, alias: Alias ->
            val selectedGuard = "$boundedValue == ${index + 1}".letIf(alias.id in aliasData) { partial ->
                "($partial && ${aliasData[alias.id]!!.selectedId} == 0)"
                "($partial && ${aliasData[alias.id]!!.selectedId} == 0)"
            }
            "($selectedGuard ? ${index + 1} : $acc)"
        }
        mainBody.add("uint ${discr.selectedId} = $selectionExpr;")
        if(discr.must) {
            mainBody.add("vm.assume(${discr.selectedId} != 0);")
        }
    }

    /**
     * Compile the "main" body of the contract. NB that this will come after the bound value binding code.
     */
    context(CompilationState)
    private fun compileBody(
        body: List<SimpleSolidityAst>
    ) {
        for(ast in body) {
            when(ast) {
                is SimpleSolidityAst.BindArray -> {
                    compileArrayLiteral(ast)
                }
                is SimpleSolidityAst.BindInputChoice -> {
                    compileChoice(ast)
                }
                is SimpleSolidityAst.BindMust -> {
                    compileMustAliasBinding(ast)
                }
                is SimpleSolidityAst.BindEnum -> {
                    compileEnumLiteral(ast)
                }

                is SimpleSolidityAst.FunctionCall -> {
                    val lhs = if (ast.binds == null) {
                        ""
                    } else if (ast.binds.size == 1) {
                        val single = ast.binds.single()
                        if (single == null) {
                            ""
                        } else {
                            single.asDecl + " = "
                        }
                    } else {
                        ast.binds.joinToString(", ", prefix = "(", postfix = ") = ") { vd ->
                            vd?.asDecl ?: "/* not bound */"
                        }
                    }
                    val args = ast.args.joinToString(", ") { s ->
                        compile(s)
                    }
                    val functionCall = """
                        /**
                            CALL: ${ast.contract}.${ast.functionName}
                         */
                        vm.prank(${ast.asSender.rawId});
                        vm.assumeNoRevert();
                        $lhs ${ast.contract}.${ast.functionName}($args);
                    """.trimIndent()
                    mainBody.add(functionCall)
                }

                is SimpleSolidityAst.Literal -> {
                    mainBody.addAll(ast.lines)
                }

                is SimpleSolidityAst.SemanticLine -> mainBody.add(ast.line)
            }
        }
    }

    /**
     * Compile an enum literal, using the [BoundVariableManager] to compute the ordinal of the enum literal to use.
     *
     * Q: Why jump through this hoop, why not declare an input field parameter of type `Enum`?
     * A: HAHAHA because dear reader, at the abi level, an `Enum` is a `uint8`. Foundry isn't smart enough to look
     * at the "real" ABI information from the compiler and realize this uint8 represents an enum, and instead
     * generates values all over the uint8 range, most of which are not valid enum ordinals. This leads to the
     * decoding logic generated by the Solidity compiler to (correctly) revert, but these reverts are not actually interesting
     * to us.
     */
    context(CompilationState)
    private fun compileEnumLiteral(ast: SimpleSolidityAst.BindEnum) {
        val boundedValue = bvm.readBoundedValue(ast.inputDiscriminator, ast.ty.enumMembers.lastIndex, null)
        mainBody.add("""
            ${ast.ty.qualifiedName} ${ast.rawId} = ${ast.ty.qualifiedName}($boundedValue);
        """.trimIndent())
    }

    /**
     * Compile the fuzz contract based off of the [CompilationInput].
     * [setupCode] is the `setUp()` function provided by the user; [initialBindings]
     * come from the initial bindings file. Because these come from two existing file
     * artifacts separate from the stuff generated as part of sequence enumeration,
     * they are kept separate from [CompilationInput].
     *
     */
    fun compileFuzzContract(
        s: CompilationInput,
        setupCode: String,
        initialBindings: Map<String, InitialBinding>
    ): CompilationResult {
        /**
         * Setup the imports
         */
        val imports = s.importManager.compile()
        val manualImports = initialBindings.values.mapNotNullToSet { initialBinding: InitialBinding ->
            initialBinding.path
        }.joinToString("\n") {
            "import \"$it\";"
        }

        /**
         * Declare storage variables; both those bound in `setUp` (via [initialBindings])
         * and in the [CompilationInput.fixedPrefix].
         */
        val storageDeclarations = (initialBindings.map { (v, bind) ->
            bind.ty.storagePP() + " $v;"
        } + s.fixedPrefix.storageVars.map { vdef ->
            vdef.ty.storagePP() + " ${vdef.rawId};"
        }).joinToString("\n")

        /**
         * "pretty print" the literal text for the `bind()` function.
         */
        val literalBindings = s.fixedPrefix.setup.joinToString("\n")

        val mainBody = mutableListOf<String>()
        val tvm = TempVarManager()
        val fieldManager = StructFieldManager()
        val bvm = BoundVariableManager(
            mainBody = mainBody,
            structField = fieldManager,
            tempVarManager = tvm
        )
        val arrayManager = ArrayLiteralManager()
        val compilationState = CompilationState(
            mainBody, bvm, fieldManager, tvm, arrayManager
        )
        compilationState.run {
            compileBody(s.ast)
        }
        /**
         * Code gen complete, all input field names, bound variables, and array literals are "stable"
         */

        /**
         * Declare the input fields from the [StructFieldManager]
         */
        val structFields = fieldManager.fields.joinToString("\n") { (ty, id) ->
            "${ty.storagePP()} $id;"
        }

        val bounder = bvm.compile()

        val inputStateName = "InputState"

        /**
         * At least include the input state struct.
         */
        val topLevelParams = mutableListOf(
            "$inputStateName memory ${SequenceGenerator.inputParamName}"
        )

        /**
         * add the bound params too
         */
        topLevelParams.addAll(bounder.extraParams.map { (ty, id) ->
            "${ty.pp()} $id"
        })

        /**
         * Generate all of the array literals required by any [prefixgen.data.SimpleSolidityAst.BindArray]
         * statements.
         */
        val arrayLitImpl = arrayManager.literals.joinToString("\n") {
            generateArrayLitFunc(it)
        }

        /**
         * Splat it all together
         */
        val sourceCode = """
            $imports
            $manualImports
            import {Test} from "forge-std/Test.sol";

            contract $fuzzContractName is Test {
                $storageDeclarations

                struct $inputStateName {
                   $structFields
                }

                $setupCode

                function bind() internal {
                   $literalBindings
                }

                function $fuzzFunctionName(
                   ${topLevelParams.joinToString(",")}
                ) public {
                   ${bounder.stateBinding}
                   ${mainBody.joinToString("\n")}
                   ${finalHook()}
                   vm.assertTrue(false);
                }

                ${bounder.stateDefn}

                ${bounder.fixtureDef}

                $arrayLitImpl
            }
        """.trimIndent()
        return CompilationResult(
            soliditySource = sourceCode,
            structFields = fieldManager.fields,
            otherParams = bounder.extraParams
        )
    }

    /**
     * Pretty printer of expressions, straightforward
     */
    private fun compile(s: SymExp) : String {
        return when(s) {
            is SymExp.StructLiteral -> {
                s.fields.joinToString(separator = ",\n", prefix = s.structName + "(", postfix = ")") {
                    compile(it)
                }
            }
            is SymExp.Symbol -> {
                s.rawId
            }
            is SymExp.StaticArrayLiteral -> {
                s.elems.joinToString(",", prefix = "[", postfix = "]") { elem ->
                    "${s.elemTy}(${compile(elem)})"
                }
            }
        }
    }

    /**
     * Generate a function for [s]. For `ArrayPrototype(arity=2, elems=uint)` will generate:
     * ```
     * function array_lit_uint_2(uint e1, uint e2) internal returns (uint[] memory) {
     *    uint[] memory toRet = new uint[](2);
     *    toRet[0] = e1;
     *    toRet[1] = e2;
     *    return toRet;
     * }
     * ```
     */
    private fun generateArrayLitFunc(s: ArrayPrototype): String {
        val params = (0 ..< s.arity).map {
            "elem$it"
        }
        val elemWrites = params.mapIndexed { ind, id ->
            "toRet[$ind] = $id;"
        }.joinToString("\n")
        val paramList = params.joinToString(", ") { pName ->
            "${s.ty.name} $pName"
        }
        return """
            function ${s.funcIdent}($paramList) internal returns (${s.ty.name}[] memory) {
                ${s.ty.name}[] memory toRet = new ${s.ty.name}[](${s.arity});
                $elemWrites
                return toRet;
            }
        """.trimIndent()
    }
}
