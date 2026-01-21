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

package debugger

import annotations.PollutesGlobalState
import cli.SanityValues
import config.*
import datastructures.stdcollections.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import report.calltrace.CallTrace
import report.calltrace.printer.DebugAdapterProtocolStackMachine
import rules.RuleCheckResult
import solver.SolanaFlowTest
import utils.*
import java.nio.file.Path
import java.util.stream.Stream
import kotlin.io.path.Path

class SolanaDebugAdapterCallTraceTests {


    /**
     * A simple test that a variable is present _at one_ stack frame along the entire trace.
     */
    @ParameterizedTest
    @MethodSource("displaysVariableSomewhere")
    fun displaysVariableSomewhere(input: Pair<SolanaConfig, Set<String>>) {
        val debugAdapter = configToResult[input.first]!!;
        val unmatchedVariables = debugAdapter.unmatchedVariables(input.second)
        Assertions.assertTrue(unmatchedVariables.isEmpty()) { "Did not find variable with names $unmatchedVariables in test, all variables are: \n ${debugAdapter.getAllVariables().joinToString("\n")} " }
    }


    /**
     * A test that a variable is present at a specific stack location matched by the line numbers of the call stack at this location
     */
    @ParameterizedTest
    @MethodSource("displaysVariableAtStack")
    fun displaysVariableAtStackTest(input: Pair<SolanaConfig, ICallStackMatcher>) {
        val debugAdapter = configToResult[input.first]!!;
        debugAdapter.applyCallStackMatcher(input.second)
    }

    /**
     * Tests that the call stack has a specific list of line numbers on top of the stack
     * at some point in time. I.e. given a list of
     */
    @ParameterizedTest
    @MethodSource("topOfStackTestInputs")
    fun topOfStackTests(input: Pair<SolanaConfig, List<List<StackSel>>>) {
        val config = input.first
        val topOfStacks = input.second
        val debugAdapter = configToResult[config]!!
        assert(debugAdapter.sequenceOfCallStackMatching(topOfStacks)) {
            "Did not find list of stack elements: ${topOfStacks}\n" +
                debugAdapter.getCallTrace().toLineNumberRepresentation()
        }
    }

    companion object {

        private const val DEBUG_MODE: Boolean = false
        private val allRegisteredConfigs = mutableSetOf<SolanaConfig>()

        /**
         * A filter operation, use this to filter out config for debugging.
         * To only execute the test case "storageReset", you can use { c: Config -> c.ruleName == "storageReset"}
         */
        private val filterByConfig = { _: SolanaConfig -> true }

        /**
         * After [beforeAll] has been executed, this maps the config to the complete call traces for look up by the tests above
         */
        private val configToResult = mutableMapOf<SolanaConfig, DebugAdapterProtocolStackMachine>()

        /**
         * List of Solana Configs
         */
        private val rule_add_with_function: SolanaConfig = SolanaConfig(ruleName = "rule_add_with_function")
        private val rule_add_with_function_at_level2: SolanaConfig = SolanaConfig(ruleName = "rule_add_with_function_at_level2")
        private val rule_array_test2: SolanaConfig = SolanaConfig(ruleName = "rule_array_test2")
        private val rule_array_test3: SolanaConfig = SolanaConfig(ruleName = "rule_array_test3")
        private val rule_array_test4: SolanaConfig = SolanaConfig(ruleName = "rule_array_test4")
        private val rule_array_test5: SolanaConfig = SolanaConfig(ruleName = "rule_array_test5")
        private val rule_array_test: SolanaConfig = SolanaConfig(ruleName = "rule_array_test")
        private val rule_basic_add_always_inline: SolanaConfig = SolanaConfig(ruleName = "rule_basic_add_always_inline")
        private val rule_basic_add_inline_never: SolanaConfig = SolanaConfig(ruleName = "rule_basic_add_inline_never")
        private val rule_enums: SolanaConfig = SolanaConfig(ruleName = "rule_enums")
        private val rule_large_struct: SolanaConfig = SolanaConfig(ruleName = "rule_large_struct")
        private val rule_middle_struct: SolanaConfig = SolanaConfig(ruleName = "rule_middle_struct")
        private val rule_mutability: SolanaConfig = SolanaConfig(ruleName = "rule_mutability")
        private val rule_nested_struct_test2: SolanaConfig = SolanaConfig(ruleName = "rule_nested_struct_test2")
        private val rule_nested_struct_test: SolanaConfig = SolanaConfig(ruleName = "rule_nested_struct_test")
        private val rule_nondet_account_array: SolanaConfig = SolanaConfig(ruleName = "rule_nondet_account_array")
        private val rule_option_type_none: SolanaConfig = SolanaConfig(ruleName = "rule_option_type_none")
        private val rule_option_type_some: SolanaConfig = SolanaConfig(ruleName = "rule_option_type_some")
        private val rule_result_type: SolanaConfig = SolanaConfig(ruleName = "rule_result_type")
        private val rule_reference_type: SolanaConfig = SolanaConfig(ruleName = "rule_reference_type")
        private val rule_simple_add: SolanaConfig = SolanaConfig(ruleName = "rule_simple_add")
        private val rule_simple_recursion: SolanaConfig = SolanaConfig(ruleName = "rule_simple_recursion")
        private val rule_single_account: SolanaConfig = SolanaConfig(ruleName = "rule_single_account")
        private val rule_single_account_as_heap: SolanaConfig = SolanaConfig(ruleName = "rule_single_account_as_heap")
        private val rule_struct_packed: SolanaConfig = SolanaConfig(ruleName = "rule_struct_packed")
        private val rule_struct_passing2: SolanaConfig = SolanaConfig(ruleName = "rule_struct_passing2")
        private val rule_struct_passing: SolanaConfig = SolanaConfig(ruleName = "rule_struct_passing")
        private val rule_macro_property: SolanaConfig = SolanaConfig(ruleName = "rule_macro_property")

        data class SolanaConfig(val confPath: Path = Path("./src/test/resources/solana/debugger_tests/run.conf"),
                                val elfFilePath: Path = Path("./src/test/resources/solana/debugger_tests/debugger_tests.so"),
                                val ruleName: String) {
            init {
                if (filterByConfig(this)) {
                    allRegisteredConfigs.add(this)
                }
            }

            override fun toString(): String {
                return "Rule: ${ruleName}"
            }
        }

        @JvmStatic
        fun displaysVariableSomewhere(): Stream<Pair<SolanaConfig, Set<String>>> {
            return Stream.of(
                rule_add_with_function to setOf("faulty_add_result", "input_a", "input_b", "faulty_add_param1", "faulty_add_param2"/*,"res" is missing due to as we don't evaluation Location Expression on the stack (e.g., DW_OP_breg0+0 DW_OP_breg3+0 DW_OP_plus DW_OP_stack_value)*/),
                rule_array_test to setOf("remaining_accounts", "element_zero", "element_one"),
                rule_array_test2 to setOf("remaining_accounts.[0x0].key", "element_zero.key", "element_one"),
                rule_basic_add_always_inline to setOf("input_a", "input_b", "input_c", "basic_add_always_inline_param1", "basic_add_always_inline_param2", "basic_add_always_inline_param3"),
                rule_basic_add_inline_never to setOf("input_a", "input_b", "input_c", "basic_add_param1", "basic_add_param2", "basic_add_param3"),
                rule_enums to setOf("nondet_enum"),
                rule_nested_struct_test to setOf("struct_b.key" /* "struct_a.bar.owner" missing as of DWARF info starting on DW_OP_piece: <offset-pair 0x40, 0xb8> [0x1ae0, 0x1b58]DW_OP_piece 8 DW_OP_reg6 DW_OP_piece 8 */),
                rule_nested_struct_test2 to setOf("struct_b2.key" /* "struct_a2.bar.owner" missing as of DWARF info starting on DW_OP_piece: <offset-pair 0x98, 0xd0> [0x1bf0, 0x1c28]DW_OP_piece 8 DW_OP_reg6 DW_OP_piece 8 */),
                rule_simple_add to setOf("faulty_add_result", "input_a", "input_b"),
                rule_simple_recursion to setOf(/*"faulty_add_result", missing as the test fails in a loop iter issue*/ "input_a", "input_b", "basic_add_recursion_param1", "basic_add_recursion_param2"),
                rule_single_account to setOf("my_account.key", "my_account.owner", "key.__0.[0x0]", "owner.__0.[0x0]"),
                rule_struct_passing to setOf("my_foo.bar.owner", "my_foo.key", "derived_foo.bar.owner", "derived_foo.key"),
                rule_macro_property to setOf("post_condition_method", "pre_condition_method"),

                ).filter { filterByConfig(it.first) }
        }


        @JvmStatic
        fun topOfStackTestInputs(): Stream<Pair<SolanaConfig, List<List<StackSel>>>> {
            return listOf(
                rule_simple_add to listOf(
                    listOf(
                        SolanaFunction(5U),
                        Rule(4U)
                    ),
                    listOf(
                        SolanaFunction(6U),
                        Rule(4U)
                    ),
                    listOf(
                        SolanaFunction(7U),
                        Rule(4U)
                    ),
                    listOf(
                        SolanaFunction(8U),
                        Rule(4U)
                    ),
                ),
                rule_add_with_function to listOf(
                    listOf(
                        SolanaFunction(5U),
                        Rule(4U)
                    ),
                    listOf(
                        SolanaFunction(6U),
                        Rule(4U)
                    ),
                    listOf(
                        SolanaFunction(7U),
                        Rule(4U)
                    ),
                    listOf(
                        SolanaFunction(11U),
                        SolanaFunction(7U),
                        Rule(4U)
                    )
                ),
                rule_simple_recursion to listOf(
                    listOf(
                        SolanaFunction(11U),
                        SolanaFunction(7U),
                        Rule(4U)
                    )
                ),
            ).filter { filterByConfig(it.first) }.stream()
        }

        @JvmStatic
        fun displaysVariableAtStack(): Stream<Pair<SolanaConfig, ICallStackMatcher>> {
            /**
             * We have too many commands that push onto the call stack. This needs cleanup elsewhere.
             * The stack elements that are pushed duplicated by "*duplicated*".
             */
            return listOf(
                rule_simple_add to CallStackMatcher(
                    SolanaFunction(8U),
                    Rule(4U))
                { matchedCallStack ->
                    matchedCallStack.assertContainsVariable("input_b")
                    matchedCallStack.assertContainsVariable("input_a")
                    matchedCallStack.assertNumberOfVariables(4)
                },

                rule_add_with_function to CallStackMatcher(
                    SolanaFunction(8U),
                    Rule(4U))
                { matchedCallStack ->
                    matchedCallStack.assertContainsVariable("faulty_add_result")
                    matchedCallStack.assertContainsVariable("input_a")
                    matchedCallStack.assertContainsVariable("input_b")
                    matchedCallStack.assertNotContainsVariable("faulty_add_param1")
                    matchedCallStack.assertNotContainsVariable("faulty_add_param2")
                    matchedCallStack.assertNumberOfVariables(4)
                },
                rule_add_with_function to CallStackMatcher(
                    SolanaFunction(12U),
                    SolanaFunction(7U),
                    Rule(4U))
                { matchedCallStack ->
                    matchedCallStack.assertContainsVariable("faulty_add_param1")
                    matchedCallStack.assertContainsVariable("faulty_add_param2")
                    matchedCallStack.assertNotContainsVariable("faulty_add_result")
                    matchedCallStack.assertNotContainsVariable("input_a")
                    matchedCallStack.assertNotContainsVariable("input_b")
                    matchedCallStack.assertNumberOfVariables(3)
                },
                rule_add_with_function_at_level2 to CallStackMatcher(
                    SolanaFunction(13U),
                    SolanaFunction(7U),
                    Rule(4U))
                { matchedCallStack ->
                    matchedCallStack.assertContainsVariable("faulty_add_param1")
                    matchedCallStack.assertContainsVariable("faulty_add_param2")
                    matchedCallStack.assertNumberOfVariables(2)
                },
                rule_nested_struct_test to CallStackMatcher(
                    SolanaFunction(9U),
                    Rule(6U)
                ) { matchedCallStack ->
                    matchedCallStack.assertContainsVariable("struct_b.key")

                    // Missing as of optimizations? DWARF location: <offset-pair 0x50, 0xb8> [0x1af0, 0x1b58]DW_OP_reg7 DW_OP_piece 8
                    //assertContainsVariable("struct_b.bar.owner")

                    // Missing as of incorrect DWARF location: (failure evaluating DWARF operation(s)): DW_OP_Piece, but stack empty. [DW_OP_piece null 64, DW_OP_reg r6, DW_OP_piece null 64]
                    //assertContainsVariable("struct_a.bar.owner")
                },
                rule_nested_struct_test2 to CallStackMatcher(
                    SolanaFunction(16U),
                    Rule(6U)
                ) { matchedCallStack ->
                    matchedCallStack.assertContainsVariable("struct_b2.key")

                    matchedCallStack.assertContainsVariable("struct_a2.bar.owner")
                    matchedCallStack.assertContainsVariable("which_branch")
                },
                rule_struct_passing to CallStackMatcher(
                    SolanaFunction(10U),
                    Rule(5U)
                ) { matchedCallStack ->
                    matchedCallStack.assertContainsVariableWithSameValue("derived_foo_key", "derived_foo_owner")
                    matchedCallStack.assertContainsVariableWithSameValue("derived_foo_key", "my_foo.bar.owner")
                    matchedCallStack.assertContainsVariableWithSameValue("derived_foo_owner", "my_foo.key")
                },
                rule_struct_passing2 to CallStackMatcher(
                    SolanaFunction(11U),
                    Rule(5U)
                ) { matchedCallStack ->
                    matchedCallStack.assertContainsVariableWithSameValue("derived_foo_key", "derived_foo_owner")
                    matchedCallStack.assertContainsVariableWithSameValue("derived_foo_key", "derived_foo.key")
                    matchedCallStack.assertContainsVariableWithSameValue("derived_foo_owner", "derived_foo.bar.owner")
                },
                rule_single_account to LastStatementMatcher { matchedCallStack ->
                    matchedCallStack.assertAllPrefixesContainsVariableWithSameValue("key", "owner")
                    // Values are of reference type, so they do _not_ contain the same value.
                    // assertContainsVariableWithSameValue("my_account.key", "my_account.owner")
                    matchedCallStack.assertContainsVariableWithSameValue("key", "my_account.key")
                },
                rule_large_struct to CallStackMatcher(
                    SolanaFunction(15U),
                    Rule(5U)
                ) { matchedCallStack ->
                    matchedCallStack.assertContainsVariableWithSameValue("input_a.h", "input_b.h")

                    //These values are hard-code in the struct so we test we get the same results back in the debugger
                    matchedCallStack.assertContainsVariableWithConcreteValue("input_a.a", 9)
                    matchedCallStack.assertContainsVariableWithConcreteValue("input_b.a", 9)

                    matchedCallStack.assertContainsVariableWithConcreteValue("input_a.g", 7)
                    matchedCallStack.assertContainsVariableWithConcreteValue("input_b.g", 7)

                    matchedCallStack.assertContainsVariableWithConcreteValue("input_a.e", 8)
                    matchedCallStack.assertContainsVariableWithConcreteValue("input_b.e", 8)
                },
                rule_middle_struct to LastStatementMatcher(
                ) { matchedCallStack ->
                    matchedCallStack.assertContainsVariableWithSameValue("input_a.field3", "input_b.field3")
                    matchedCallStack.assertContainsVariableWithConcreteValue("input_a.field1", 9)
                    matchedCallStack.assertContainsVariableWithConcreteValue("input_b.field1", 9)
                    matchedCallStack.assertContainsVariableWithConcreteValue("input_a.field2", 7)
                    matchedCallStack.assertContainsVariableWithConcreteValue("input_b.field2", 7)
                },
                rule_mutability to CallStackMatcher(
                    SolanaFunction(11U),
                    Rule(7U)
                ) { matchedCallStack ->
                    matchedCallStack.assertContainsVariableWithConcreteValue("parent_struct.fixed_value_2", 2)
                },
                rule_mutability to CallStackMatcher(
                    SolanaFunction(27U),
                    SolanaFunction(19U),
                    Rule(7U)
                ) { matchedCallStack ->
                    matchedCallStack.assertContainsVariableWithConcreteValue("parent_struct.fixed_value_2", 3)
                },
                rule_struct_packed to CallStackMatcher(
                    SolanaFunction(7U),
                    Rule(4U)
                ) { matchedCallStack ->
                    matchedCallStack.assertContainsVariableWithConcreteValue("input_a.field1", 9)
                    matchedCallStack.assertContainsVariableWithConcreteValue("input_b.field1", 9)

                    matchedCallStack.assertContainsVariableWithConcreteValue("input_a.field2", 7)
                    matchedCallStack.assertContainsVariableWithConcreteValue("input_b.field2", 7)


                    // Optimized information - the debug information only provides 12 bytes - but the struct actually has 16 bytes: [ 1]<offset-pair 0x38, 0x100> [0x2538, 0x2600]DW_OP_lit7 DW_OP_stack_value DW_OP_piece 8 DW_OP_lit9 DW_OP_stack_value DW_OP_piece 4
                    //assertContainsVariableWithSameValue("input_a.field3", "input_b.field3")
                },
                rule_nondet_account_array to CallStackMatcher(
                    SolanaFunction(11U),
                    Rule(6U)
                ) { matchedCallStack ->
                    matchedCallStack.assertAllPrefixesContainsVariableWithSameValue("key1", "key2")

                },
                rule_single_account_as_heap to LastStatementMatcher { matchedCallStack ->
                    matchedCallStack.assertContainsVariable("my_account.key")
                    matchedCallStack.assertContainsVariable("my_account.owner")
                    matchedCallStack.assertAllPrefixesContainsVariableWithSameValue("key", "owner")
                },
                rule_array_test3 to CallStackMatcher(
                    SolanaFunction(27U),
                    Rule(20U)
                ) { matchedCallStack ->
                    matchedCallStack.assertContainsVariableWithConcreteValue("foo_zero.el3", 7)
                    matchedCallStack.assertContainsVariableWithConcreteValue("foo_zero.el2", 8)
                    matchedCallStack.assertContainsVariableWithConcreteValue("foo_zero.el1", 10)

                    matchedCallStack.assertAllPrefixesContainsVariableWithSameValue("foo_zero", "foo_one")
                    matchedCallStack.assertContainsVariableWithConcreteValue("foo_one.el3", 7)
                    matchedCallStack.assertContainsVariableWithConcreteValue("foo_one.el2", 8)
                    matchedCallStack.assertContainsVariableWithConcreteValue("foo_one.el1", 10)
                },
                rule_array_test4 to LastStatementMatcher() { matchedCallStack ->
                    matchedCallStack.assertContainsVariableWithConcreteValue("foo_zero.el2", 8)
                    matchedCallStack.assertContainsVariableWithConcreteValue("foo_zero.el1", 10)

                    matchedCallStack.assertAllPrefixesContainsVariableWithSameValue("foo_zero", "foo_one")
                    matchedCallStack.assertContainsVariableWithConcreteValue("foo_one.el2", 8)
                    matchedCallStack.assertContainsVariableWithConcreteValue("foo_one.el1", 10)
                },
                rule_array_test5 to LastStatementMatcher { matchedCallStack ->
                    matchedCallStack.assertContainsVariableWithConcreteValue("foo_zero.el1", 10)

                    matchedCallStack.assertAllPrefixesContainsVariableWithSameValue("foo_zero", "foo_one")
                    matchedCallStack.assertContainsVariableWithConcreteValue("foo_one.el1", 10)
                },
                rule_enums to LastStatementMatcher { matchedCallStack ->
                    matchedCallStack.assertContainsVariableWithConcreteValue("nondet_enum.SomeEnum.Foo", 0)
                },


                rule_result_type to LastStatementMatcher { matchedCallStack ->
                    matchedCallStack.assertContainsVariableWithConcreteValue("result.Err.__0", 42)
                },

                // none has a 0x0 value and thus we don't display anything. This is a formatting issue that needs to be fixed
                rule_option_type_none to LastStatementMatcher { matchedCallStack ->
                    matchedCallStack.assertContainsVariableWithConcreteStringValue("option_type_input", "(variable optimized out)")
                },
                rule_option_type_some to LastStatementMatcher { matchedCallStack ->
                    matchedCallStack.assertContainsVariableWithConcreteValue("option_type_input.Some.__0", 2)
                },
                rule_reference_type to CallStackMatcher(
                    SolanaFunction(17U),
                    Rule(8U)
                ) { matchedCallStack ->
                    matchedCallStack.assertContainsVariableWithConcreteValue("substruct.fixed_value_1", 1)
                    matchedCallStack.assertContainsVariableWithConcreteValue("struct1.fixed_value_2", 2)
                    matchedCallStack.assertContainsVariableWithConcreteValue("create_same_clone.fixed_value_2", 2)
                    matchedCallStack.assertContainsVariableWithConcreteValue("create_same_clone.substruct.fixed_value_1", 1)
                    //matchedCallStack.assertContainsVariable("substruct.nondet_value")
                    matchedCallStack.assertContainsVariableWithSameValue("create_same_clone.substruct", "struct1.substruct")
                },
            ).filter { filterByConfig(it.first) }.stream()
        }


        @OptIn(PollutesGlobalState::class)
        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            ConfigScope(Config.CallTraceDebugAdapterProtocol, DebugAdapterProtocolMode.VARIABLES)
                .extend(Config.CallTraceHardFail, HardFailMode.ON)
                .extend(Config.DoSanityChecksForRules, SanityValues.NONE)
                .letIf(DEBUG_MODE) {
                    CommandLineParser.setExecNameAndDirectory()
                    ConfigType.WithArtifacts.set(log.ArtifactManagerFactory.WithArtifactMode.WithArtifacts)
                    it.extend(sbf.SolanaConfig.DumpDwarfDebugInfoInReports, true).extend(sbf.SolanaConfig.PrintAnalyzedToDot, true)
                }
                .use {
                    allRegisteredConfigs.groupBy { it.confPath to it.elfFilePath }.map { grouped ->
                        // In the case of Solana we group the configs by path + elffile so that we run the rules in the same elffile only once
                        val confPath = grouped.key.first
                        val elfFilePath = grouped.key.second
                        val rules = grouped.value.mapToSet { it.ruleName }.toCollection(HashSet(grouped.value.size))
                        val callTraces = SolanaFlowTest.runSolanaFlowOnProjectForTests(confPath, elfFilePath, rules)
                        callTraces.second.forEach { ruleCheckResult ->
                            val ruleCheckInfo = (ruleCheckResult as? RuleCheckResult.Single)?.ruleCheckInfo
                                ?: error("Expected some rule check result ")
                            (ruleCheckInfo as? RuleCheckResult.Single.RuleCheckInfo.WithExamplesData)?.let { exampleData ->
                                val callTrace = exampleData.examples.first().callTrace
                                check(callTrace is CallTrace.ViolationFound || callTrace is CallTrace.Failure) { "No violation found for config ${grouped.key}" }
                                val debugAdapter = callTrace?.debugAdapterCallTrace
                                check(debugAdapter != null)
                                val el = grouped.value.find { it.ruleName == ruleCheckResult.rule.ruleIdentifier.parentIdentifier?.displayName }
                                if (el != null) {
                                    configToResult[el] = debugAdapter
                                }
                            }
                        }
                    }
                }
        }
    }
}