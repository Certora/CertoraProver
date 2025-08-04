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

package spec.cvlast.typechecker

import bridge.EVMExternalMethodInfo
import bridge.types.SolidityTypeDescription.Companion.builtin
import com.certora.certoraprover.cvl.CallSummary
import com.certora.certoraprover.cvl.LocatedToken
import com.certora.certoraprover.cvl.MethodReferenceExp
import com.certora.certoraprover.cvl.MethodSig
import config.Config
import datastructures.stdcollections.*
import java_cup.runtime.ComplexSymbolFactory
import spec.CVLKeywords
import spec.cvlast.*
import spec.cvlast.typedescriptors.PrintingContext
import spec.cvlast.typedescriptors.VMTypeDescriptor
import spec.genericrulegenerators.BuiltInRuleId
import utils.*

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// CONCRETE ERROR CLASSES //////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

// ArraySizeMustBeLiteral //////////////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.SYNTAX,
    description = "Static array sizes must be number literals, such as `0` or `10`.  More complex expressions are not allowed."
)
// TODO: CERT-2108, needs Exp.toString implementation
// @CVLErrorExample(
//     exampleCVLWithRange = """
//         function f(uint[#3+5#] a) {
//         }
//         """,
//     exampleMessage = "Expected a number literal for a fixed-size array type, not `3+5`",
// )
class ArraySizeMustBeLiteral private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(index: com.certora.certoraprover.cvl.Exp) : this(index.range, "Expected a number literal for a fixed-size array type, not `$index`")
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "Failure to infer type of array literal"
)
@CVLErrorExample(
    exampleCVLWithRange = """
        function f() {
            address[] arr = #[0, -1, max_address+1]#;
        }
    """,
    exampleMessage = "The array literal's type is address, but some elements (with types int8, uint168) do not match."
)
@CVLErrorExample(
    exampleCVLWithRange = """
        function acceptsArray(int8[] arr) {}
        function f() {
            address a = 0;
            acceptsArray(#[a, -1, 3]#);
        }
    """
)
class WrongArrayLiteralElementTypes private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(location: Range, badElements: List<CVLType.PureCVLType>, hintType: CVLType.PureCVLType) : this(
        location = location,
        message = "The array literal's type is $hintType, but some elements (with types ${badElements.joinToString()}) do not match."
    )

    constructor(location: Range) : this(
        location = location,
        message = "Failed to infer a type for the array literal."
    )

}
// LhsIsDynamicArray ///////////////////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.SYNTAX,
    description = "CVL does not support assigning to an entire array; you can only assign to an entry with a specific index.",
)
@CVLErrorExample(
    exampleCVLWithRange = """
            rule example {
                #x[]# = 2;
            }
            """,
    exampleMessage = "Assigning to an array element requires an index",
)
class LhsIsDynamicArray private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(location : Range) : this(location, "Assigning to an array element requires an index")
}

// LhsIsMapping ////////////////////////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.SYNTAX,
    description =
        """
        CVL does not support assigning to an entire mapping; you must give individual assignments for the needed elements.
        """,
)
// TODO: CERT-2108, needs Type toString
// @CVLErrorExample(
//     exampleCVLWithRange = """
//             rule example {
//                 #mapping(uint => uint)# = 3;
//             }
//             """,
//     exampleMessage = "`mapping(uint => uint)` is not a valid left-hand side.",
// )
class LhsIsMapping private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(e: com.certora.certoraprover.cvl.MappingType) : this(e.range, "`$e` is not a valid left-hand side.")
}

// InvalidIdentifier ///////////////////////////////////////////////////////////////////////////////////////////////////

// Unfortunately, escaping dollars in kotlin is not very elegant,
// and we need to double escape them in the examples so that they're still escaped in the generated test code
private const val dollar = "\${'\$'}"

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.SYNTAX,
    description =
        """
        Invalid name given for a CVL declaration.
        """,
)
@CVLErrorExample(
    exampleCVLWithRange = """
             rule example {
                 #int ${dollar}x# = 3;
             }
             """,
    exampleMessage = "${dollar}x is not a valid name for a variable. " +
        "Names must start with a letter or underscore and may only contain letters, numbers, underscores and dollar signs.",
)
@CVLErrorExample(
    exampleCVLWithRange = """
             rule example {
                 #int ${dollar}x;#
             }
             """,
    exampleMessage = "${dollar}x is not a valid name for a variable. " +
        "Names must start with a letter or underscore and may only contain letters, numbers, underscores and dollar signs.",
)
@CVLErrorExample(
    exampleCVLWithRange = """
                 #ghost int ${dollar}x;#
             """,
    exampleMessage = "${dollar}x is not a valid name for a ghost. " +
        "Names must start with a letter or underscore and may only contain letters, numbers, underscores and dollar signs.",
)
@CVLErrorExample(
    exampleCVLWithRange = """
                 #ghost ${dollar}x() returns bool;#
             """,
    exampleMessage = "${dollar}x is not a valid name for a ghost function. " +
        "Names must start with a letter or underscore and may only contain letters, numbers, underscores and dollar signs.",
)
@CVLErrorExample(
    exampleCVLWithRange = """
                 #using PrimaryContract as ${dollar}c;#
             """,
    exampleMessage = "${dollar}c is not a valid name for a contract alias. " +
        "Names must start with a letter or underscore and may only contain letters, numbers, underscores and dollar signs.",
)
@CVLErrorExample(
    exampleCVLWithRange = """
                 invariant foo(#int ${dollar}x#) true;
             """,
    exampleMessage = "${dollar}x is not a valid name for a CVL parameter. " +
        "Names must start with a letter or underscore and may only contain letters, numbers, underscores and dollar signs.",
)
class InvalidIdentifier private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(location: Range, id: String, construct: String) : this(
        location, "$id is not a valid name for a $construct. " +
            "Names must start with a letter or underscore and may only contain letters, numbers, underscores and dollar signs."
    )
}

// SyntaxError /////////////////////////////////////////////////////////////////////////////////////////////////////////


/** Syntax error (from the parser) */
@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.SYNTAX,
    description =
        """
        A syntax error indicates a misplaced symbol that prevents the CVL compiler from interpreting a spec file.
        """,
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        rule example #oopsie# { }
        """,
    exampleMessage = "Syntax error: unexpected token near `oopsie`",
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        rule #max_address# { }
        """,
    exampleMessage = "Syntax error: unexpected token near `max_address`",
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        rule r {
            if (true)
                assert true#)#
            assert false;
        }
        """,
    exampleMessage = "Syntax error: unexpected token near `)`",
)
class SyntaxError private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(location: Range, sym: ComplexSymbolFactory.ComplexSymbol)
        : this(location, "Syntax error: unexpected token near `${sym.value?.toString() ?: sym.name}`")
}

// CVL2MissingSemicolon ////////////////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.CVL2,
    description = "Indicates one of the places that CVL became more strict about the placement of semicolons.  See {ref}`cvl2-semicolons`.",
)
@CVLErrorExample(
    exampleCVLWithRange =
        """
        methods {
            function balanceOf(address)##
        }
        """,
    exampleMessage = "Since CVL 2, methods block entries must end with `;`",
)
@CVLErrorExample("""import "example.spec"##""")
@CVLErrorExample("using test as example##")
@CVLErrorExample("invariant i() true##")
@CVLErrorExample("use rule example##")
@CVLErrorExample("methods { function example() internal returns(uint) => NONDET## }")
class CVL2MissingSemicolon private constructor(override val location: Range, override val message: String) : CVLError() {
    enum class ExpectedLocation(val description: String) {
        IMPORT_STATEMENTS("`import` statements"),
        USING_STATEMENTS("`using` statements"),
        METHODS_ENTRIES("methods block entries"),
        INVARIANTS("invariants"),
        USE_STATEMENTS("`use` statements"),
    }
    constructor(location: Range, typeDescription: ExpectedLocation) : this(location, "Since CVL 2, ${typeDescription.description} must end with `;`")
}

// CVL2IncorrectSemiColonPlacement /////////////////////////////////////////////////////////////////////////////////////
@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.CVL2,
    description = "Indicates a location where a semicolon should not appear.  See {ref}`cvl2-semicolons`.",
)
@CVLErrorExample(
    exampleCVLWithRange =
        """
        invariant i() true filtered {
            x -> x == 0
        }#;#
        """,
    exampleMessage = "Invariants with filtered blocks must not end with `;`",
)
class CVL2IncorrectSemiColonPlacement private constructor(override val location: Range, override val message: String) : CVLError() {
    enum class ExpectedLocation(val description: String) {
        INVARIANT_FILTERED("Invariants with filtered blocks"),
    }

    constructor(location: Range, typeDescription: ExpectedLocation): this(location, "${typeDescription.description} must not end with `;`")
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.SYNTAX,
    description = "an invalid cvl type is specified"
)
@CVLErrorExample(
    exampleCVLWithRange = """
        ghost #uint25# g;
    """
)
@CVLErrorExample(
    exampleCVLWithRange = """
        function f returns #int3# {}
    """
)
@CVLErrorExample(
    exampleCVLWithRange = """
        rule r { #bytes33# b; }
    """
)
class ToCVLTypeError(override val location: Range, override val message: String) : CVLError()

// CVL2RedundantStoragePlacement /////////////////////////////////////////////////////////////////////////////////////
@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.SYNTAX,
    description = "Indicates a location where a `STORAGE` should not appear.",
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        hook Sstore currentContract.myUint uint256 newValue #STORAGE# { }
    """,
    exampleMessage = "`STORAGE` keyword is redundant in hooks",
)
class CVL2RedundantStoragePlacement private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(location: Range): this(location, "`STORAGE` keyword is redundant in hooks")
}

// CVL2MissingFunctionKW ///////////////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.CVL2,
    description = "Since CVL 2, methods block entries must begin with `function`.  See {ref}`cvl2-function-keyword`.",
)
@CVLErrorExample(
    exampleCVLWithRange = """
            methods {
                #balanceOf(address)#;
            }
        """,
    exampleMessage = "Since CVL 2, methods block entries must begin with `function`",
)
class CVL2MissingFunctionKW private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(location: Range) : this(location, "Since CVL 2, methods block entries must begin with `function`")
}

// TwoStateOnNonGhostFunction //////////////////////////////////////////////////////////////////////////////////////////

// TODO: CERT-2108
@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.SYNTAX,
    description =
        "You can only use `f@old` or `f@new` in the context of a `havoc...assuming` statement, and it can only be " +
        "applied to a ghost.  See {ref}`havoc-stmt` for more information.",
)
@CVLErrorExample(
    exampleCVLWithRange =
        """
        function f(uint x) returns uint {
            return x;
        }
        rule example {
            uint y = #f@old(0)#;
        }
        """,
    exampleMessage = "Cannot use `@old` with non-ghost function `f`",
)
class TwoStateOnNonGhostFunction private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(exp: CVLExp.UnresolvedApplyExp, twoState: TwoStateIndex)
        : this(exp.tag.range, "Cannot use `$twoState` with non-ghost function `${exp.methodId}`")
}

// ReservedKeywordAsFunction ///////////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.SYNTAX,
    description =
        """
        This error indicates that you tried to use a cast where a function call was expected.  If you want to call a
        contract function with the same name as a keyword, you will need to add a harness function to your contract with
        a different name.
        """
)
@CVLErrorExample(
    exampleCVLWithRange = """
        rule example {
            uint result = #assert_uint256()#;
        }
    """,
    exampleMessage =
        "`assert_uint256` is a reserved CVL keyword. Usage: `assert_uint256(valToConvert)`",
)
@CVLErrorExample("methods { function #to_bytes3#(uint) external returns (uint) envfree; }")

class ReservedKeywordAsFunction private constructor(override val location: Range, override val message : String) : CVLError() {
    constructor(keyword : String, location: Range) : this(location, "`$keyword` is a reserved CVL keyword. Usage: `$keyword(valToConvert)`")
}

// InvalidCatchAllFlag /////////////////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.METHODS_BLOCK,
    description =
        """
        The annotations that follow the `returns` clause in a methods-block entry (such as {ref}`optional` and
        {ref}`envfree`) only make sense for specific contract functions.  Therefore, their use in catch-all
        summaries of the form `function C._` are disallowed.  If you really want to declare every function `envfree`,
        you will need to add a separate entry for each function.
        """
)
@CVLErrorExample(
    exampleCVLWithRange =
        """
        methods {
            function PrimaryContract._ external #envfree# => NONDET;
        }
        """,
    exampleMessage = "Catch-all methods entries of the form `function f._` cannot be marked `envfree`",
)
class InvalidCatchAllFlag private constructor(override val location: Range, override val message : String) : CVLError() {
    constructor(token : LocatedToken) : this(token.range, "Catch-all methods entries of the form `function f._` cannot be marked `${token.value}`")
}

// WithWrongNumberArguments ////////////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.SYNTAX,
    description =
        """
        A `with` must have the form `with(env <variable-name>)`.  See {ref}`with-env` or {ref}`preserved` for more information.
        """
)
@CVLErrorExample(
    exampleCVLWithRange =
        """
        methods {
            function externalFunction() external #with()# => summary(e1, e2);
        }
        """,
    exampleMessage = "`with` clause has 0 arguments; `with` clauses must define a single argument of type `env` (e.g. `with(env e)`)",
)
@CVLErrorExample("methods { function externalFunction() external #with(env e1, env e2)# => summary(e1, e2); }")
// TODO CERT-3315: "invariant i() true { preserved #with()# {} }",
// TODO CERT-3315: "invariant i() true { preserved #with(env e1, env e2)# {} }",

class WithWrongNumberArguments(override val location: Range, val number : Int) : CVLError() {
    override val message = "`with` clause has $number arguments; `with` clauses must define a single argument of type `env` (e.g. `with(env e)`)"
}

// WithWrongType ///////////////////////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.SYNTAX,
    description =
        """
        A `with` must have the form `with(env <variable-name>)`.  See {ref}`with-env` or {ref}`preserved` for more information.
        """
)
@CVLErrorExample(
    exampleCVLWithRange =
        """
        methods {
            function externalFunction() external with(#uint256 x#) => summary(x);
        }

        function summary(uint256 x) {}
        """,
    exampleMessage = "`with` clause argument has type `uint256`; `with` clauses must define a single argument of type `env` (e.g. `with(env e)`)",
)
// CERT-3315: @CVLErrorExample("invariant i() true { preserved #with(address x)# {} }")

class WithWrongType private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(param : CVLParam)
        : this(param.range, "`with` clause argument has type `${param.type}`; `with` clauses must define a single argument of type `env` (e.g. `with(env e)`)")
}

// WithWildcard ///////////////////////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.SYNTAX,
    description = "`with` clauses must name their variables - wildcards are not allowed"
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        methods {
          function externalTakesUint(uint256 x) external with(#env _#) => summary(x);
        }

        function summary(uint256 x) { }
    """,
    exampleMessage = "`with` clause argument must be a named variable, not a wildcard. If you don't need this variable, remove the entire `with` clause."
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        invariant tautology() true {
            preserved with(#env _#) {

            }
        }
    """,
    exampleMessage = "`with` clause argument must be a named variable, not a wildcard. If you don't need this variable, remove the entire `with` clause."
)
class WithWildcard(override val location: Range) : CVLError() {
    override val message =
        "`with` clause argument must be a named variable, not a wildcard. " +
            "If you don't need this variable, remove the entire `with` clause."
}

// EnvfreeAndWith //////////////////////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.METHODS_BLOCK,
    description =
        """
        {ref}`envfree` indicates that a method does not depend on its environment, while a {ref}`with-env`
        allows a function summary to depend on its environment.  Therefore, we disallow including both annotations on
        the same method.
        """
)
@CVLErrorExample(
    exampleCVLWithRange =
        """
        methods {
            function externalFunction() external envfree #with(env e)# => summary(e);
        }

        function summary(env e) {}
        """,
    exampleMessage = "`envfree` methods-block entries must not declare a `with(env ...)` clause"
)
class EnvfreeAndWith(override val location: Range) : CVLError() {
    override val message = "`envfree` methods-block entries must not declare a `with(env ...)` clause"
}

// UnresolvedSummaryModeOnInternalMethod ///////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.METHODS_BLOCK,
    description =
        """
        Summaries for internal methods cannot be marked as `UNRESOLVED`, since internal methods are always resolved.
        Remove the `UNRESOLVED` keyword from the summary.
        """
)
@CVLErrorExample(
    exampleCVLWithRange =
        """
        methods {
            #function internalFunction() internal => NONDET UNRESOLVED;#
        }
        """,
    exampleMessage = "Summaries for internal methods cannot be marked as `UNRESOLVED`, since internal methods are always resolved. " +
        "Remove the `UNRESOLVED` keyword from the summary."
)
class UnresolvedSummaryModeOnInternalMethod(override val location: Range) : CVLError() {
    override val message: String = "Summaries for internal methods cannot be marked as `UNRESOLVED`, since internal methods are always resolved. " +
        "Remove the `UNRESOLVED` keyword from the summary."
}

// DeleteSummaryModeOnInternalMethod ///////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.METHODS_BLOCK,
    description =
    """
        Summaries for internal methods cannot be marked as `DELETE`, since internal methods can not be removed.
        Remove the `DELETE` keyword from the summary.
        """
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        methods {
            #function internalFunction() internal => NONDET DELETE;#
        }
        """,
    exampleMessage = "Summaries for internal methods cannot be marked as `DELETE`, since internal methods can not be removed. " +
        "Remove the `DELETE` keyword from the summary."
)
class DeleteSummaryModeOnInternalMethod(override val location: Range) : CVLError() {
    override val message: String = "Summaries for internal methods cannot be marked as `DELETE`, since internal methods can not be removed. " +
        "Remove the `DELETE` keyword from the summary."
}

// DispatcherSummaryOnInternalMethod ///////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.METHODS_BLOCK,
    description =
    """
    Cannot apply dispatcher summaries for internal methods.
    """
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        methods {
            #function internalFunction() internal => DISPATCHER;#
        }
        """,
    exampleMessage = "Cannot apply dispatcher summaries for internal methods."
)
class DispatcherSummaryOnInternalMethod(override val location: Range) : CVLError() {
    override val message: String = "Cannot apply dispatcher summaries for internal methods."
}

// DispatcherSummaryNoImplementation ///////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.METHODS_BLOCK,
    description =
    """
    An optimistic Dispatcher must have at least one matching contract method.
    """
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        methods {
            #function _.nonExistentMethod() external => DISPATCHER(true);#
        }
        """,
    exampleMessage = "Optimistic dispatcher summaries must have at least one matching contract method."
)
class DispatcherSummaryNoImplementation(override val location: Range) : CVLError() {
    override val message: String = "Optimistic dispatcher summaries must have at least one matching contract method."
}

// DuplicateDeclaration ////////////////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.UNSUPPORTED,
    description =
        """
        If a CVL variable is defined in a scope, it cannot be redefined.
        """
)
@CVLErrorExample(
    exampleCVLWithRange =
        """
        function f(mathint x) {
            #uint x = 5;#
        }
        """,
    exampleMessage = "Redeclaring variables is not supported; `x` was previously declared on line 2",
)
@CVLErrorExample("function f() { mathint x; #mathint x;# }")                     // Straight-up redeclaration
@CVLErrorExample("ghost uint g; rule shadowing { #bool g;# assert g; }")         // Check that ghosts cannot be shadowed by declarations
@CVLErrorExample("ghost uint g; rule shadowing { #bool g = true;# assert g; }")  // Check that ghosts cannot be shadowed by definitions
@CVLErrorExample("ghost uint g; function f(#uint g#) {}")                        // Shadowing by a function argument
@CVLErrorExample("ghost uint g; invariant i(#uint g#) true;")                    // Shadowing by an invariant argument
@CVLErrorExample("ghost uint g; #ghost uint g;#")                                // Duplicate ghost variables
@CVLErrorExample("ghost uint g; #ghost g() returns uint;#")                      // Ghost function and variable with same name [CERT-3312]
@CVLErrorExample("ghost uint g; hook Sstore uint_field #uint g# { uint ignore; }")     // Simple hook param shadows ghost
@CVLErrorExample("ghost uint g; hook Sstore array_field[INDEX #uint g#] uint v { uint ignore; }") // shadowing in hook pattern
@CVLErrorExample("ghost uint g; definition f(#uint g#) returns bool = false;")   // shadowing with definition argument
@CVLErrorExample("ghost g() returns uint; #ghost uint g;#")                      // Ghost function and variable with same name [CERT-3312]
@CVLErrorExample("function f() { mathint x; { #mathint x = 0;# } }")             // Shadowing inside a block
@CVLErrorExample("function f() { mathint x; if (_) { #mathint x = 0;# } }")      // Shadowing inside an `if` block
@CVLErrorExample("invariant i(env e) true { preserved with(#env e#) {} }")       // Shadowing by preserved argument over invariant argument
@CVLErrorExample("invariant i() true { preserved with(env e) { #env e;# } }")    // Shadowing an argument defined in a with clause
@CVLErrorExample("invariant i(env e) true { preserved { #env e;# } }")           // Shadowing invariant argument in a preserved block
@CVLErrorExample("definition d(uint x, #uint x#) returns uint = x + x;")         // Duplicate arguments
@CVLErrorExample("rule r { assert forall uint x . forall #uint x# . x == x; }")  // Shadowing in nested quantifier
@CVLErrorExample("rule r { mathint x; assert forall #uint x# . x == x; }")       // Shadowing in quantifier
@CVLErrorExample("function f() { uint[] x; #uint x;# }")                         // Complex / primitive
@CVLErrorExample("function f() { uint x; #uint[] x;# }")                         // Primitive / complex
@CVLErrorExample("methods { function externalTakesUint(uint x) external with(#env x#) => summary(x) expect void; } function summary(uint x) {}") // with clause collides with argument

class DuplicateDeclaration private constructor(override val location: Range, override val message : String) : CVLError() {
    constructor(id : String, location : Range, previous : Range) : this(location, "Redeclaring variables is not supported; `$id` was previously declared ${previous.relativeDescription(location)}")
}

// DeclaredKeyword /////////////////////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "CVL does not allow declaring variables with the same names as keywords."
)
@CVLErrorExample(
    exampleCVLWithRange =
        """
        function f() {
            #mathint nativeBalances = 0;#
        }
        """,
    exampleMessage = "`nativeBalances` is a CVL keyword and cannot be redeclared here.",
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        function f() {
            #mathint nativeCodesize = 0;#
        }
        """,
    exampleMessage = "`nativeCodesize` is a CVL keyword and cannot be redeclared here.",
)
@CVLErrorExample("methods { function externalFunction(#address calledContract#) external; }") // calledContract is illegal in the methods block
@CVLErrorExample("methods { function externalFunction() external with(#env calledContract#) => summary(); }")
@CVLErrorExample("function f() { #mathint calledContract = 0;# }")                            // calledContract is disallowed, even outside the methods block
@CVLErrorExample("hook Sstore (slot 0)[INDEX #uint executingContract#] uint i { uint ignore; }")    // `executingContract` is a keyword inside of hooks
@CVLErrorExample("hook Sstore (slot 0) uint i { #uint executingContract;# }")         // `executingContract` is a keyword inside of hooks

class DeclaredKeyword private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(id : String, location : Range) : this(location, "`$id` is a CVL keyword and cannot be redeclared here.")
}

// AssignedToKeyword ///////////////////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category    = CVLErrorCategory.TYPECHECKING,
    description = "CVL keywords cannot be assigned to."
)
@CVLErrorExample(
    exampleCVLWithRange =
        """
        function f() { #nativeBalances# = 0; }
        """,
    exampleMessage = "The special CVL variable `nativeBalances` cannot be assigned to."
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        function f() { #nativeCodesize# = 0; }
        """,
    exampleMessage = "The special CVL variable `nativeCodesize` cannot be assigned to."
)
class AssignedToKeyword private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(id : String, location : Range) : this(location, "The special CVL variable `$id` cannot be assigned to.")
}

// AssignedAfterUse ////////////////////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.UNSUPPORTED,
    description =
        """
        In CVL, non-ghost variables cannot be given new values once they have been read.
        """
)
@CVLErrorExample(
    exampleCVLWithRange =
        """
        function f() {
            mathint x;
            mathint y = x * x;
            #x# = 3;
        }
        """,
    exampleMessage = "Assigning to a CVL variable after it is accessed is disallowed; `x` was previously used on line 4.",
)
@CVLErrorExample("function f() { mathint x; if(_) { mathint y = x; } #x# = 3; }")           // havoced in the `then` block; can't be redefined
@CVLErrorExample("function f() { mathint x; if(_) {} else { mathint y = x; } #x# = 3; }")   // havoced in the `else` block; can't be redefined
@CVLErrorExample("function f() { mathint x; #x# = x + x; }")                                // circular definition [CERT-3230]
@CVLErrorExample("function f() { mathint x; assert forall mathint y . x == y; #x# = 3; }")  // havoced in quantified statement
@CVLErrorExample("function f() { uint[] x; uint[] y; assert y[0] == 3; #y# = x; }")          // complex type

class AssignedAfterUse private constructor(override val location : Range, override val message: String) : CVLError() {
    constructor(id : String, location: Range, previousUse : Range) : this(location, "Assigning to a CVL variable after it is accessed is disallowed; `$id` was previously used ${previousUse.relativeDescription(location)}.")
}

// AssignFromVoid //////////////////////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "Expressions of type `void`, such as void functions, cannot be the RHS of an assignment."
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        using PrimaryContract as pc;
        rule r(env e) {
            #uint256 n = pc.voidFunction(e);#
            assert false;
        }
    """,
    exampleMessage = "EVM function `PrimaryContract.voidFunction()` has type `void`. It cannot be assigned to a variable."
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        function returnsVoid() {
            return;
        }
        rule r {
            #_ = returnsVoid();#
            assert false;
        }
    """,
    exampleMessage = "CVL function `returnsVoid` has type `void`. It cannot be assigned to a variable."
)
class AssignFromVoid(val rhsKind: RhsKind, val rhs: String, override val location: Range) : CVLError() {
    enum class RhsKind(val description: String) {
        CVL_FUNC("CVL function"),
        EVM_FUNC("EVM function"),
        ADDRESS_FUNC("Address function");
    }

    override val message: String get() = "${rhsKind.description} `$rhs` has type `void`. It cannot be assigned to a variable."
}

// UndeclaredVariable //////////////////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
        category = CVLErrorCategory.TYPECHECKING,
        description = "All CVL variables must be declared before they are used"
)
@CVLErrorExample(
    """
    function f() {
        mathint y = 2 * #x#;
    }
    """,
    "Variable `x` has not been declared."
)

// we forgot `sig:`.
// TODO CERT-3736: This is a stopgap; it would be nice if we made a separate error class and detected more instances of
//  missing `sig:` (e.g. f(uint[]).selector)  This probably requires some serious thinking about the grammar; I think
//  it will require allowing types in the ambiguous apply, and then handling the method fields directly when
//  disambiguating the field access.
//  We can't do perfect disambiguation; for example if there is a contract call `f()` that returns a struct with a `signature`
//  field, then `f().selector` will always be ambiguous.
@CVLErrorExample(
    "function f() { method f; assert f.selector == externalTakesUint(#uint#).selector; }",
    "Variable `uint` has not been declared.  Did you forget to use `sig:` for a method selector?"
)
@CVLErrorExample("function no_sig(method f) { require f.selector == contract_method(#address#); }")

// `calledContract` is only defined in the methods block
@CVLErrorExample(
    "function f() { address cc = #calledContract#; }",
    "The special CVL variable `calledContract` is not defined in this context."
)

// `msg.sender` is not available
@CVLErrorExample(
    "function f() { address x = #msg#.sender; }",
    "Variable `msg` has not been declared.  The Solidity variable `msg` is available as a field of an `env` struct (e.g. `env e; e.msg`)."
)

@CVLErrorExample("function f() { #x# = 3; }")
@CVLErrorExample("function f() { #x#[3] = 0; }")
@CVLErrorExample("function f() { uint[] x; x[#y#] = 0; }")
@CVLErrorExample("function f() { havoc #x#; }")
@CVLErrorExample("methods { function externalFunction() external => summary(#x#); } function summary() {}")

// x falls out of scope
@CVLErrorExample("rule r { if(_) { mathint x = 0; } else { mathint x = 1; } assert #x# == 2; }")
@CVLErrorExample("rule r { if(_) { mathint x = 0; } else { mathint x = 1; } #x# = 2; assert false; }")

// only one error expected for duplicate problems with same variable
@CVLErrorExample("rule bug { mathint y = #x# + x; mathint x = y; mathint z = x; assert true; }")

// rule filters on an undefined variable
@CVLErrorExample("rule r(method f) filtered { f -> f.isPure, g -> #g#.isPure } { assert false; }")

// undefined variables in an invariant filter
@CVLErrorExample("invariant i() true filtered { x -> #g#.selector != 0 }")


class UndeclaredVariable private constructor(override val location: Range, override val message : String) : CVLError() {
    constructor(id : String, location : Range) : this(location, specialize(id))

    companion object {
        /** Catch some special cases to give nicer error messages */
        fun specialize(id : String) : String = when {
            EVMBuiltinTypes.isEVMKeyword(id) -> "Variable `$id` has not been declared.  The Solidity variable `$id` is available as a field of an `env` struct (e.g. `env e; e.$id`)."
            CVLKeywords.find(id) != null     -> "The special CVL variable `$id` is not defined in this context."
            builtin(id) != null              -> "Variable `$id` has not been declared.  Did you forget to use `sig:` for a method selector?" // TODO CERT-3736
            else                             -> "Variable `$id` has not been declared."
        }
    }
}

// InconsistentVariableDefinition //////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description =
        """
        You are allowed to declare a variable and then define it inside the branches of an `if` block.  If you define it
        in one branch, however, you must also define it in the other branch.
        """
)
@CVLErrorExample(
    exampleCVLWithRange =
        """
        function f(bool flag) {
            mathint x;
            #if (flag) {
                x = 5;
            } else {
                assert true;
            }#
        }
        """,
    exampleMessage = "`x` is defined in the `if` branch (on line 5) but not in the `else` branch. Consider either assigning a value in both branches or using `require` instead of assignments.",
)
@CVLErrorExample(
    "function f() { mathint x; #if(_) {} else { x = 5; }# }", // defined in the else branch
    "`x` is defined in the `else` branch (at position 1:43) but not in the `if` branch. Consider either assigning a value in both branches or using `require` instead of assignments."
)

@CVLErrorExample("function f() { mathint x; #if(_) { x = 3; } else { mathint z = x; }# }")  // defined in if, used in else
@CVLErrorExample("function f() { mathint x; #if(_) { x = 4; }# }")                          // no else branch
@CVLErrorExample("function f() { uint x; #if (_) { if (_) { x = 0; } else { x = 1; } }# }") // nested if statements
@CVLErrorExample("function f() { uint x; #if (_) { } else { if (_) { x = 0; } else { x = 1; } }# }") // nested in else

class InconsistentVariableDefinition private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(id : String, ifLocation : Range, definitionLocation : Range, definedInThenBlock : Boolean)
        : this(
            ifLocation,
            "`$id` is defined in the `${ if (definedInThenBlock) { "if" } else { "else" } }` branch (${definitionLocation.relativeDescription(ifLocation)})" +
            " but not in the `${         if (definedInThenBlock) { "else" } else { "if" } }` branch." +
                " Consider either assigning a value in both branches or using `require` instead of assignments."
        )
}

// InvalidSummaryForWithClause /////////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.METHODS_BLOCK,
    description =
        """
        {ref}`with-env` bind a variable name for use in a function summary; without a function summary the clause
        serves no purpose.
        """
)
@CVLErrorExample(
    exampleCVLWithRange =
        """
        methods {
            function externalFunction() external #with(env e)# => NONDET;
        }
        """,
    exampleMessage = "`with(...)` clauses are only allowed for methods-block entries with function summaries.",
)
@CVLErrorExample("methods { function externalFunction() external #with(env e)#; }")
class InvalidSummaryForWithClause(override val location: Range) : CVLError() {
    override val message = "`with(...)` clauses are only allowed for methods-block entries with function summaries."
}

// DuplicatePreserved //////////////////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.SYNTAX,
    description =
        """
        You may have at most one {ref}`preserved` for each method, at most one generic `preserved` block, and at
        most one `fallback` preserved block.
        """
)
@CVLErrorExample(
    exampleCVLWithRange =
        """
        invariant i() true {
            #preserved { }#
            preserved  { }
        }
        """,
    exampleMessage = "Invariant `i` has multiple generic preserved blocks (additional block on line 4)."
)
@CVLErrorExample(
    "invariant i() true { #preserved externalFunction() {}# preserved externalFunction() {} }",
    "Invariant `i` has multiple preserved blocks for `PrimaryContract.externalFunction()` (additional block at position 1:54)."
)
class DuplicatePreserved private constructor(override val location: Range, override val message: String) : CVLError() {
    sealed class Target(val description : String)
    class Generic  : Target("generic preserved blocks")
    class Fallback : Target("fallback preserved blocks")
    class Specific(method : CVLPreserved.ExplicitMethod) : Target("preserved blocks for `${method.methodSignature}`")

    constructor(invariant: CVLInvariant, blocks : List<CVLPreserved>, type : Target)
        : this(blocks.first().range, "Invariant `${invariant.id}` has multiple ${type.description} (additional block ${blocks[1].range.relativeDescription(blocks.first().range)}).")
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.SYNTAX,
    description =
    """
        One cannot specify a preserved block for a method of an extension function using the extension function as the receiver.
        Instead, use the extended contract as the receiver.
        """
)
@CVLErrorExample(
    exampleCVLWithRange = """
        invariant i() true {
            #preserved Extension.externalExtensionFunction() {
                require true;
            }#
        }
    """,
    exampleMessage = "`Extension` extends [PrimaryContract], so one cannot " +
        "specify a preserved block for its methods in this way. Specify them using [PrimaryContract].\n" +
        "For example: `preserved PrimaryContract.externalExtensionFunction(...`"
)
class ExplicitPreservedOnExtensionContract private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(preserved: CVLPreserved. ExplicitMethod, extendedContractName: List<String>) : this(
        preserved.range,
        "`${preserved.methodSignature.qualifiedMethodName.host}` extends $extendedContractName, so one cannot " +
            "specify a preserved block for its methods in this way. Specify them using $extendedContractName.\n" +
            "For example: `preserved ${extendedContractName.first()}.${preserved.methodSignature.qualifiedMethodName.methodId}(...`"
    )
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.SYNTAX,
    description =
    """
        A {ref}`preserved` block's `with` clause, must have a single variable, of type `env`.
        """
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        invariant i() true {
            #preserved with(bool b) { }#
        }
        """,
    exampleMessage = "The generic preserved block can only get parameters of type `env`, got type `bool`"
)
class WithParamWrongType private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(preservedType: String, cvlType: CVLType.PureCVLType, location: Range) :
        this(location, "The $preservedType preserved block can only get parameters of type `env`, got type `$cvlType`")
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.SYNTAX,
    description =
    """
        A {ref}`preserved` block's `with` clause, must have a single variable, of type `env`.
        """
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        invariant i() true {
            #preserved with(env e, bool b) { }#
        }
        """,
    exampleMessage = "The generic preserved block can have at most one variable inside the `with()` clause and its type should be `env`"
)
class MultipleWithParams private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(preservedType: String, location: Range) :
        this(location, "The $preservedType preserved block can have at most one variable inside the `with()` clause and its type should be `env`")
}
// AtOnNonContractCall /////////////////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category    = CVLErrorCategory.SYNTAX,
    description =
        """
        You may only use the `at storage` syntax for calls to contract functions.  See {ref}`call-expr` for more
        information.
        """
)
@CVLErrorExample(
    exampleCVLWithRange =
        """
        function f() {
        }

        rule r {
            storage s = lastStorage;
            #f() at s#;
            assert false;
        }
        """,
    exampleMessage = "`at s` may only be used for calls to contract functions; `f` is a CVL function.",
)
class AtOnNonContractCall private constructor(override val location: Range, override val message : String) : CVLError() {
    constructor(app: CVLExp.UnresolvedApplyExp, func: SpecFunction)
        : this(app.getRangeOrEmpty(), "`at ${app.invokeStorage}` may only be used for calls to contract functions; `${app.callableName}` is a ${func.typeDescription}.")
}

// LastRevertWithNonContractCall ///////////////////////////////////////////////////////////////////////////////////////

// TODO(CERT-7707): Remove this once cvlFunctionRevert config is removed
@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.SYNTAX,
    description = "Only contract functions can be called `@withrevert`; see {ref}`call-expr`."
)
// Leaving the example just for documentation purposes - it would need -cvlFunctionRevert false to throw the error now
//@CVLErrorExample(
//    exampleCVLWithRange =
//        """
//        function f() {}
//        function g() { #f@withrevert()#; }
//        """,
//    exampleMessage = "`@withrevert` may only be used for calls to contract functions; `f` is a CVL function.",
//)
class WithrevertOnNonContractCall private constructor(override val location: Range, override val message : String) : CVLError() {
    constructor(app : CVLExp.UnresolvedApplyExp, func : SpecFunction)
        : this(app.getRangeOrEmpty(), "`@withrevert` may only be used for calls to contract functions; `${app.callableName}` is a ${func.typeDescription}.")
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

// both of the following errors can't be triggered with concrete syntax.

@KSerializable
@Suppress("CVLErrorsNeedDocs")
class TupleTypesAreCVLOnly private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(e: com.certora.certoraprover.cvl.TupleType) : this(e.range, "`$e` cannot be used for a VM type")
}

@KSerializable
@Suppress("CVLErrorsNeedDocs")
class LhsIsTuple private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(e: com.certora.certoraprover.cvl.TupleType) : this(e.range, "`$e` is not a valid left-hand side for an assignment; for parallel assignments, omit the parentheses.")
}


// NoValidInstantiationsLeft ///////////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "Direct storage access must have restricted forms."
)
class StorageAccessMismatch(override val location: Range, override val message: String) : CVLError()

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "Direct immutable access must have restricted forms."
)
class ImmutableAccessMismatch(override val location: Range, override val message: String): CVLError()

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description =
        """
        A filter for some method parameter filters out all candidates.
        """
)
class NoValidInstantiationsLeft private constructor(override val location: Range, override val message : String) : CVLError() {
    constructor(location: Range, methodArg: String, ruleName: String) : this(location, "After filtering, method argument $methodArg for rule $ruleName remains with no valid instantiations")
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description =
        """
        A filter was used on a builtin rule that does not support filtering.
        """
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
    #use builtin rule msgValueInLoopRule filtered { f -> f.isPayable }#
    """,
    exampleMessage = "May not use method parameter filters on builtin rule msgValueInLoopRule.",
)
data class InvalidMethodParamFiltersOnBuiltinRule(override val location: Range, private val ruleName: BuiltInRuleId) : CVLError() {
    override val message = "May not use method parameter filters on builtin rule $ruleName."
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "One cannot use the foundry fuzz test builtin rules without setting the --foundry flag"
)
@CVLErrorExample(
    """
        #use builtin rule verifyFoundryFuzzTests;#
    """
)
class FuzzTestBirWithoutFoundryFlag private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(location: Range, ruleName: BuiltInRuleId) : this(
        location,
        "Cannot use the builtin rule `$ruleName` without setting the `${Config.Foundry.userFacingName()}` flag"
    )
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description =
    """
        Variables of {ref}`method type <method-type>` may only be declared in the top-level of a rule, or as parameters/arguments to a
        rule, CVL function, or definition.
        """
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        invariant inv() true {
            preserved externalFunction() with (env e) {
                #method f;#
                require true;
            }
        }
        """,
    exampleMessage = "`method` variables can only be declared in rules, at their out-most scope.",
)
@CVLErrorExample("function foo() { #method f;# }")
@CVLErrorExample("rule r { if (true) #method f;# assert true; }")
@CVLErrorExample("function f() { if(true) { #method f;# } }")
@CVLErrorExample("hook Sload uint256 u privateUintStorage { #method f;# }")

data class MethodVariableNotInRule(override val location: Range) : CVLError() {
    override val message = "`method` variables can only be declared in rules, at their out-most scope."
}

// MultipleFiltersSameMethodParam //////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.SYNTAX,
    description =
    """
        There should be only a single filter for each `method`-type parameter
        """
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        rule r(method f) filtered { #f -> true, f -> true# } { assert false; }
        """,
    exampleMessage = "Cannot define multiple filters for the same method parameter `f`.",
)
class MultipleFiltersSameMethodParam(override val location: Range, private val methodParam: String) : CVLError() {
    override val message get() = "Cannot define multiple filters for the same method parameter `$methodParam`."
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.SYNTAX,
    description =
    """
        One cannot use the `@old` or `@new` notation on a contract instance.
        """
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        rule r {
            mathint x = #currentContract@new.foo()#;
            assert false;
        }
        """,
    exampleMessage = "@old or @new does not make sense here.",
)
@CVLErrorExample("rule r { mathint x = #currentContract@new.foo()#; assert false; }")

class ApplyExpWithAtNewOrAtOldBase(override val location: Range) : CVLError() {
    override val message get() = "@old or @new does not make sense here."
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.SYNTAX,
    description =
    """
        Solidity functions can only be applied from contract instances
        """
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        rule r {
            uint a;
            mathint x = #a.foo()#;
            assert false;
        }
        """,
    exampleMessage = "Can only apply a method to a contract name or address, but got 'a'",
)
class ApplyMethodOnNonContract(private val exp: CVLExp.UnresolvedApplyExp) : CVLError() {
    override val message get() = "Can only apply a method to a contract name or address, but got '${exp.base}'"
    override val location get() = exp.getRangeOrEmpty()
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.SYNTAX,
    description = "Cannot use CVL keywords as variable names"
)
@CVLErrorExample(
    exampleCVLWithRange = """
        function f {
            uint #sum# = 1+1;
        }
    """,
    exampleMessage = "`sum` is a reserved CVL keyword and cannot be used here." +
        " Starting with Prover version 7.23 this is a reserved word in CVL. See " +
        "https://docs.certora.com/en/latest/docs/prover/changelog/prover_changelog.html for more details"
)
@CVLErrorExample(
    exampleCVLWithRange = """
        function f {
            uint #forall#;
        }
    """
)
@CVLErrorExample(
    exampleCVLWithRange = """
        function #exists# {}
    """
)
class InvalidUsageOfCVLKeyword(override val location: Range, private val token: String) : CVLError() {
    override val message: String
        get() = "`$token` is a reserved CVL keyword and cannot be used here." + if(token == "sum") {
            " Starting with Prover version 7.23 this is a reserved word in CVL. See " +
                "https://docs.certora.com/en/latest/docs/prover/changelog/prover_changelog.html for more details"
        } else {
            ""
        }
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.SYNTAX,
    description =
    """
        A syntax error. A list of expected tokens is presented
        """
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        ghost f() returns uint {
            #whatever# axiom true;
        }
        """,
    exampleMessage = "Syntax error: unexpected token `whatever`; expected `init_state`.",
)
@CVLErrorExample(
    "hook Sload address n myArray[#WHATEVER# uint index] {}",
    "Syntax error: unexpected token `WHATEVER`; expected one of `KEY`, `INDEX`."
)
@CVLErrorExample("hook Sload address n myMapping[KEY uint k1][#WHATEVER# uint index] {}")
@CVLErrorExample("hook Sload uint index m[KEY address k].(offset 0).(#WHATEVER# 32) {}")

class UnexpectedToken(override val location: Range, private val token: String, private val expected: List<String>) : CVLError() {
    override val message get() = run {
        val expectedString = expected.singleOrNull()?.let { "`$it`" } ?: "one of ${expected.joinToString { "`$it`" }}"
        "Syntax error: unexpected token `$token`; expected $expectedString."
    }
}
@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description =
    """
        A syntax error. A list of expected tokens is presented
        """
)
@CVLErrorExample("function f() { bytes32[] a; require #a.someField# == 1; }")
class ExpectedArrayLengthAccess(private val exp: CVLExp.FieldSelectExp) : CVLError() {
    override val location: Range
        get() = exp.getRangeOrEmpty()
    override val message: String
        get() = "Unexpected field access .${exp.fieldName} on an expression of type array. Did you mean `.${CVLType.PureCVLType.CVLArrayType.lengthFieldName}` instead?"
}

// ParametricReturn ////////////////////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description =
        """
        You are not allowed to access the return value of a {term}`parametric method` call, since the parametric method
        may be resolved to any of several contract methods, and those methods may have different return types.

        Note that this is also true when you call an overloaded contract method with a {ref}`` `calldataarg` `` argument,
        since this also acts as a parametric method call that calls each of the different overloadings.
        """
)
@CVLErrorExample(
    exampleCVLWithRange =
        """
        rule example {
            method f; env e; calldataarg args;
            uint x = #f(e,args)#;
            assert true;
        }
        """,
    exampleMessage = "You may not access the return value of a call to a `method` variable.",
)
@CVLErrorExample("function example() { env e; calldataarg args; method f; assert #f(e,args)# == 0; }")          // in an assert context
@CVLErrorExample("function example() { env e; calldataarg args; method f; uint x = 3 + #f(e,args)#; }")         // in an expression context

@CVLErrorExample( // CERT-3436 parametricity comes from overloading
    "function example() { env e; calldataarg args; uint x = #overloadedFunction(e,args)#; }",
    "`overloadedFunction` has more than one implementation in `PrimaryContract`; calling it with a `calldataarg` is ambiguous. The prover will generate a parametric rule in this case, but you may not use the return value of the call."
)
@CVLErrorExample("function example() { env e; calldataarg args; uint x = 3 + #overloadedFunction(e, args)#; }") // CERT-3436 parametricity comes from overloading

class ParametricReturn private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(cmd : CVLExp.ApplicationExp, callType: SymbolicContractMethod) : this(cmd.getRangeOrEmpty(), specialize(callType))

    companion object {
        fun specialize(callType : SymbolicContractMethod) : String = when(callType) {
            is Overloading      ->
                "`${callType.methodId}` has more than one implementation in `${callType.host}`; " +
                "calling it with a `calldataarg` is ambiguous. " +
                "The prover will generate a parametric rule in this case, but you may not use the return value of the call."
            is ParametricMethod ->
                "You may not access the return value of a call to a `method` variable."
        }
    }
}

// NotConvertible //////////////////////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description =
        """
        This error message indicates that you tried to use a value that cannot be automatically converted to the
        expected type.  You may need to insert an explicit cast.  See {ref}`subtyping` for more information on types.
        """,
)
@CVLErrorExample(
    exampleCVLWithRange =
        """
        function example(env e) {
            uint256 x = #e#;
        }
        """,
    exampleMessage = "`e` has type `env`, which cannot be converted to the expected type `uint256`.",
)

@CVLErrorExample("sort s; function f(s x) { mathint y = #x# + 5; }")                      // sorts aren't integral types
@CVLErrorExample("function f() { env e; uint x = #returnsStruct(e)#; }")                  // can't convert struct to uint
@CVLErrorExample("function f() { bool b = #13#; }")                                       // 13 as bool
@CVLErrorExample("function f() { bool[] b; bool bb = #b#; }")                             // bool[] to bool
@CVLErrorExample("function f() { address[] a; address x; a = #x#; }")                     // address to address[]

@CVLErrorExample("function f() { PrimaryContract.ExampleEnum x = #10#; }")                // enum out of bounds
@CVLErrorExample(
    "function f() { PrimaryContract.ExampleEnum x = #1#; }",                              // enum in bounds
    "Invalid enum literal.  Did you mean `PrimaryContract.ExampleEnum.MEMBER2`?"
)

// TODO: CERT-3656 out of bounds examples; should probably be their own errors
//       what's worse: if the overflow is for an `int` or a `uint`, you get a different error (see CVLSyntax/CastExp/badTests/bad6)
@CVLErrorExample("function f() { bytes32 u1 = #0x10000000000000000000000000000000000000000000000000000000000000000#; }") // 2^256 as bytes32
@CVLErrorExample("function f() { address a1 = #0x10000000000000000000000000000000000000001#; }")                         // 2^260 + 1 as address

// there should be lots more tests!

class NotConvertible private constructor(override val location: Range, override val message : String) : CVLError() {
    constructor(expression : CVLExp, expectedType : CVLType)
        : this(expression.getRangeOrEmpty(), specialize(expression, expectedType))

    companion object {
        fun specialize(expression: CVLExp, expectedType: CVLType) : String {
            return when (expression) {
                is CVLExp.Constant ->
                    // Special message for enum literals
                    (expectedType as? CVLType.PureCVLType.Enum)?.let {
                        expression.eval()?.n?.takeIf { it.isInt() }?.toInt()?.let {
                            expectedType.elements.getOrNull(it)?.let { constantName ->
                                "Invalid enum literal.  Did you mean `${expectedType.name}.$constantName`?"
                            }
                        }
                    } ?: "Literal value `$expression` cannot be assigned to type `$expectedType`."

                else -> {
                    val expressionType = expression.getCVLType()
                    if(expressionType is CVLType.VM) {
                        val reason = expressionType.descriptor.converterTo(expectedType as CVLType.PureCVLType, expressionType.context.getVisitor())
                        check(reason is CollectingResult.Error) { "we should only reach here if the conversion isn't possible" }
                        "`$expression` has type `${expression.getCVLType()}` (from the VM), which cannot be converted to the expected CVL type `$expectedType`.\n" +
                            "Reason(s):\n    ${reason.messages.joinToString("\n\t")}"
                    } else {
                        "`$expression` has type `${expression.getCVLType()}`, which cannot be converted to the expected type `$expectedType`."
                    }
                }
            }
        }
    }
}

// NonBoolExpression ///////////////////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "Certain expressions must have a boolean type"
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
    function returnsVoid() {
        return;
    }
    function f {
        assert #returnsVoid()#;
    }
    """,
    exampleMessage = "Assert command must have type `bool`, got `void`"
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
    function f {
        bool b = forall uint8 x. #3#;
    }
    """,
    exampleMessage = "The body of quantified expression must have type `bool`, got `3`"
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
    ghost uint8 byteGhost;
    function f {
        havoc byteGhost assuming #"not boolean"#;
    }
    """,
    exampleMessage = "Assuming expression of Havoc command must have type `bool`, got `string`"
)
@CVLErrorExample(
    exampleCVLWithRange = """
        function f {
            uint x;
            if (#x#) {
                return;
            }
        }
    """,
    exampleMessage = "If condition must have type `bool`, got `uint256`"
)
/** this can arguably be merged with [NotConvertible] but it's nice to have a specialized error for this common case */
class NonBoolExpression(val exp: CVLExp, val kind: Kind): CVLError() {
    private val actualType = exp.getCVLType()
    override val location: Range get() = exp.getRangeOrEmpty()
    override val message: String get() = "${kind.description} must have type `bool`, got `$actualType`"

    enum class Kind(val description: String) {
        ASSUME_CMD("Require command"), // the user-facing name of AssumeCmd is `require`
        ASSERT_CMD("Assert command"),
        SATISFY_CMD("Satisfy command"),
        QUANTIFIER_BODY("The body of quantified expression"),
        ASSUMING_EXPR_OF_HAVOC("Assuming expression of Havoc command"),
        IF_COND("If condition")
        ;
    }
}

// NoEnumMember ////////////////////////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description =
    """
        CVL takes the definitions of enum members from the Solidity source.  This error indicates that you tried to use
        a constant that wasn't declared.
        """
)
@CVLErrorExample(
    exampleCVLWithRange =
        """
        function f() {
            PrimaryContract.ExampleEnum x = #PrimaryContract.ExampleEnum.MISSING#;
        }
        """,
    exampleMessage = "enum `PrimaryContract.ExampleEnum` does not have a member `MISSING`."
)
class NoEnumMember private constructor(override val location : Range, override val message : String) : CVLError() {
    constructor(range : Range, type : CVLType.PureCVLType, member: String) : this(range, "enum `$type` does not have a member `$member`.")
}

// MethodVariableTooManyContracts //////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description =
    """
        `method` type variables could only be used under at most a single contract within a given rule.
        """
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        using SecondaryContract as secondary;

        #rule r(method f) {
            env e; calldataarg args;
            currentContract.f(e, args);
            secondary.f(e, args);
            assert true;
        }#
        """,
    exampleMessage = "In rule r there are conflicting usages of `f` - it is marked as being parametric in more than a single contract: [PrimaryContract, SecondaryContract]",
)
@CVLErrorExample( // one of the uses is in a function
    """
    using SecondaryContract as secondary;

    function foo(method f, env e, calldataarg args) {
        secondary.f(e, args);
    }

    #rule r(method f) {
        env e; calldataarg args;
        currentContract.f(e, args);
        foo(f, e, args);
        assert true;
    }#
    """
)
class MethodVariableTooManyContracts private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(location: Range, ruleName: String, methodArg: String, contractNames: List<String>) :
        this(location, "In rule $ruleName there are conflicting usages of `$methodArg` - it is marked as being parametric in more than a single contract: $contractNames")
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description =
    """
        When calling a method on a specific contract, the contract must be either `currentContract` or a method-typed
        variable introduced with a `using` statement. See {ref}`call-expr` for more details.
        """
)
@CVLErrorExample(
    exampleCVLWithRange = "rule r { method f; d.#f()#; assert true; }",
    exampleMessage = "contract variable `d` not found. Contract variables must be introduced with a `using` statement."
)
class NoSuchContractInstance private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(exp: CVLExp.UnresolvedApplyExp) : this(
        location = exp.getRangeOrEmpty(),
        message = "contract variable `${exp.base}` not found. Contract variables must be introduced with a `using` statement."
    )
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "parametric methods can only be called on aliased contracts"
)
@CVLErrorExample("rule r { method f; address d; d.#f()#; assert true; }")
class NotAContractInstance private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(exp: CVLExp.UnresolvedApplyExp) : this(
        exp.getRangeOrEmpty(),
        "cannot perform parametric function calls on general address-typed variables - only contract aliases are supported for this."
    )
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.CLI,
    description =
    """
        All contracts listed in the {ref}`--contract` option must be in the {term}`scene`.
        """
)
class ContractChoiceNoSuchContract(val contractName: String) : CVLError() {
    override val location = Range.Empty("invalid command line argument")
    override val message = "Invalid argument to `-contract`: No contract named $contractName in the scene"
}

// DisallowedTypeUsedInQuantifier //////////////////////////////////////////////////////////////////////////////////////

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description =
        """
        CVL quantifier bodies may only contain CVL definitions or ghost function calls."
        """
)
@CVLErrorExample(
    exampleCVLWithRange =
        """
        methods {
            function returnsTwiceUint(uint x) external returns uint envfree;
        }

        rule checkQuantifier() {
            require (exists uint x . #returnsTwiceUint(x)# > 4);
            assert true;
        }
        """,
    exampleMessage = "Contract function calls such as returnsTwiceUint(x) are disallowed inside quantified formulas."
)
@CVLErrorExample(
    exampleCVLWithRange =
        """
        function foo(uint x) returns uint {
            return require_uint256(2 * x);
        }

        rule checkQuantifier() {
            require (exists uint x . #foo(x)# > 4);
            assert true;
        }
        """,
    exampleMessage = "CVL function calls such as foo(x) are disallowed inside quantified formulas."
)
class DisallowedTypeUsedInQuantifier(override val location: Range, override val message: String) : CVLError() {

    enum class DisallowedType(val description: String) {
        CVL_FUNCTION_CALL("CVL function calls"),
        SOLIDITY_FUNCTION_CALL("Contract function calls"),
        BUILT_IN_KECCAK256("CVL built-in `keccak256` function calls")
    }

    constructor(location: Range, exp: String, disallowedType: DisallowedType) :
        this(location, "${disallowedType.description} such as $exp are disallowed inside quantified formulas.")
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description =
    """
        Satisfy statements are only allowed in rules, and functions that are called only in rules.
        """
)
class BadSatisfy : CVLError {
    override val location: Range
    private val errorPlace: String

    constructor(location: Range, errorPlace: String) : super() {
        this.location = location
        this.errorPlace = errorPlace
        this.message = "Satisfy statements are not allowed in $errorPlace."
    }

    override val message: String
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "Only certain types of expressions are allowed as havoc targets."
)
class InvalidHavocTargetType(val exp: CVLExp) : CVLError() {
    override val location get() = exp.tag.range
    override val message get() = "`$exp` cannot be havoced"
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "Havoc of CVL variables is currently limited to ghosts."
)
@CVLErrorExample(
    exampleCVLWithRange =
        """
        function mutateThis(uint256 x) {
            havoc #x# assuming true;
        }
        """,
    exampleMessage = "Cannot havoc `x`. Local CVL parameters are not ghosts and cannot be havoced."
)
@CVLErrorExample(
    exampleCVLWithRange =
        """
        rule check_havoc {
            calldataarg args;
            env e;
            method f;
            f(e, args);
            havoc #args#;
            method g;
            g(e, args);
        }
        """,
    exampleMessage = "Cannot havoc `args`. Havoc of calldataarg has been deprecated. Try defining a new variable instead."
)
class NonGhostVariableAsHavocTarget private constructor(override val location: Range, override val message: String) : CVLError() {
    companion object {
        fun fromSymbolValue(variable: CVLExp.VariableExp, sv: Any?): NonGhostVariableAsHavocTarget {
            val location = variable.getRangeOrEmpty()

            val reason = when {
                sv is CVLParam -> "Local CVL parameters are not ghosts and cannot be havoced."
                sv is CVLCmd.Simple.Declaration && sv.cvlType is CVLType.PureCVLType.VMInternal.RawArgs -> {
                    "Havoc of calldataarg has been deprecated. Try defining a new variable instead."
                }
                else -> "Havoc of non-ghost variables is not allowed."
            }
            val message = "Cannot havoc `${variable.id}`. $reason"

            return NonGhostVariableAsHavocTarget(location, message)
        }
    }
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "Storage accesses are not allowed in assuming expressions of havoc commands."
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
    function f {
        havoc currentContract.uint_field assuming #currentContract.uint_field# == 12345;
    }
    """,
    exampleMessage = "Storage access literal `currentContract.uint_field` is not allowed in assuming expressions. Instead of using an assuming expression, try adding a require after the havoc."
)
class StorageAccessInAssumingExpression(private val storageAccessExp: CVLExp) : CVLError() {
    override val location: Range get() = storageAccessExp.getRangeOrEmpty()
    override val message: String get() =
        "Storage access literal `$storageAccessExp` is not allowed in assuming expressions." +
        " Instead of using an assuming expression, try adding a require after the havoc."
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "Certain types may only havoced if they originated from direct access of storage."
)
@CVLErrorExample(
    exampleCVLWithRange =
        """
        rule r {
            uint[] arrgh = [1, 2, 3];
            havoc #arrgh[0]#;
        }
        """,
    exampleMessage = "`arrgh[0]` cannot be havoced since it did not originate from direct storage access. Try using a local CVL variable instead of a havoc command."
)
class HavocTargetLiteralMustBeStorageAccess(val target: CVLExp.HavocTarget) : CVLError() {
    override val location: Range get() = target.asExp.tag.range
    override val message: String get() = "`$target` cannot be havoced since it did not originate from direct storage access. Try using a local CVL variable instead of a havoc command."
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "Direct storage access havoc is currently limited to primitives, arrays of primitives at a specific index, and array lengths."
)
@CVLErrorExample(
    exampleCVLWithRange =
        """
        function f {
            havoc #currentContract.array_field#;
        }
        """,
    exampleMessage = "cannot havoc `currentContract.array_field`. havoc of entire arrays is currently unsupported."
)
class UnsupportedStorageTarget(val target: CVLExp.HavocTarget, val input: Input) : CVLError() {
    override val location: Range get() = target.asExp.tag.range

    enum class Input(val description: String) {
        EntireMapping("entire mappings"),
        EntireArray("entire arrays"),
        Struct("contract-defined structs"),
        Enum("contract-defined enums"),
    }

    override val message: String get() = "cannot havoc `$target`. havoc of ${input.description} is currently unsupported."
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "Calling library functions from spec code is not supported."
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        using Library as l;
        function f {
            env e;
            l.#externalFunction(e)#;
        }
        """,
    exampleMessage = "Calling Library functions from spec is not supported. Use a harness function that calls the library one if needed"
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        using Library as l;
        function f {
            env e; calldataarg args;
            l.#overloadedFunction(e, args)#;
        }
        """,
)
class LibraryFunctionCallNotSupported private constructor(override val location: Range, override val message: String): CVLError() {
    constructor(exp: CVLExp.ApplicationExp) : this(exp.getRangeOrEmpty(), "Calling Library functions from spec is not supported. Use a harness function that calls the library one if needed")
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "Cannot hook on the length field of a variable of type `string` or `bytes`"
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        #hook Sload uint len currentContract.bytes_field.length {
            require false;
        }#
        """,
    exampleMessage = "Cannot hook on the length of `string` or `bytes` in `PrimaryContract.bytes_field.length`"
)
@CVLErrorExample("#hook Sload uint len currentContract.string_field.length { require false; }#")
class PackedBytes1ArrayLengthHook private constructor(override val location: Range, override val message: String): CVLError() {
    constructor(hook: CVLHook, pattern: CVLSlotPattern) : this(hook.range, "Cannot hook on the length of `string` or `bytes` in `$pattern`")
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "Cannot use `var.fieldname` if `var` is not a struct type"
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        rule r {
            calldataarg args;
            assert #args.non_existent_field#;
        }
        """,
    exampleMessage = "Expression `args` is not a struct but a `calldataarg` type. Cannot use `.non_existent_field` on it."
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        methods { function externalFunction() external envfree; }
        rule r(method f) {
            assert f.selector == #externalFunction().selector#;
        }
        """,
    exampleMessage = "Expression `PrimaryContract.externalFunction()` is not a struct but a `void` type. Cannot use `.selector` on it. Did you forget to use `sig:` for a method selector?"
)
class FieldSelectOnNonStruct private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(exp: CVLExp.FieldSelectExp, ty: CVLType.PureCVLType) :
        this(
            exp.getRangeOrEmpty(),
            "Expression `${exp.structExp}` is not a struct but a `$ty` type. Cannot use `.${exp.fieldName}` on it." +
                if (exp.fieldName == EVMExternalMethodInfo.selectorField) { " Did you forget to use `sig:` for a method selector?" } else { "" }
        )
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "Cannot compare structs that have a field with an array type."
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        rule r {
            PrimaryContract.StructOfArrayOfUints s; env e;
            assert #returnsStructOfArrayOfUints(e) == s#;
        }
        """,
    exampleMessage = "Cannot directly compare `PrimaryContract.returnsStructOfArrayOfUints(e)` to `s` because these structs contain an array (in field `PrimaryContract.StructOfArrayOfUints.uArr`). Comparison can only be done field by field"
)
class StructComparisonContainsArrays private constructor(override val location: Range, override val message: String) : CVLError() {
    companion object {
        /** Some logic to make sure we get `StructName.fieldName` instead of `varName.fieldName` */
        private tailrec fun getStructAndFields(base: CVLExp, rest: String): String {
            if (base !is CVLExp.FieldSelectExp) {
                return "${base.getCVLType()}$rest"
            }
            return getStructAndFields(base.structExp, "." + base.fieldName + rest)
        }
    }

    constructor(exp: CVLExp.RelopExp, base: CVLExp, fieldName: String) :
        this(
            exp.getRangeOrEmpty(),
            "Cannot directly compare `${exp.l}` to `${exp.r}` because these structs contain an array (in field `${getStructAndFields(base, ".$fieldName")}`)." +
                " Comparison can only be done field by field"
        )
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.METHODS_BLOCK,
    description = "Catch unresolved summaries are for external functions only."
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        methods {
            function _._ #internal# => DISPATCH [ _.bar(uint) ] default HAVOC_ALL;
        }
        """,
    exampleMessage = "Catch unresolved summary can only be marked as external, got 'internal'."
)
class OnlyExternalSummaryAllowed constructor(override val location: Range, override val message: String): CVLError() {
    constructor(token: LocatedToken) : this(token.range, "Catch unresolved summary can only be marked as external, got '${token.value}'.")
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.METHODS_BLOCK,
    description = "Catch unresolved summaries are for '_._' patterns only."
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        methods {
            function #C._# external => DISPATCH [ _.foo(uint) ] default HAVOC_ALL;
        }
        """,
    exampleMessage = "Summarizing with a dispatch list is only allowed with '_._' pattern, got 'C._'."
)
class DispatchListWithSpecificId private constructor(override val location: Range, override val message: String): CVLError() {
    constructor(exp: MethodReferenceExp) : this(exp.range, "Summarizing with a dispatch list is only allowed with '_._' pattern, got '${exp}'.")
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.METHODS_BLOCK,
    description = "The wildcard '_._' patterns is not allowed inside the dispatch list."
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        methods {
            function _._ external => DISPATCH [ #_._# ] default HAVOC_ALL;
        }
        """,
    exampleMessage = "A dispatch list may not contain the '_._' pattern."
)
class FullWildcardInDispatchList private constructor(override val location: Range, override val message: String): CVLError() {
    constructor(exp: MethodSig) : this(exp.id.range, "A dispatch list may not contain the '_._' pattern.")
}
@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.METHODS_BLOCK,
    description = "Using an optimistic unresolved external summary with a default case is not allowed."
)
@CVLErrorExample(
   exampleCVLWithRange =
    """
        methods {
            function _._ external => #DISPATCH(optimistic=true) [ C._ ] default NONDET#;
        }
        """,
    exampleMessage = "Using an optimistic unresolved external summary with a default case is not allowed, remove the 'default ...' or use optimistic=false, in the method summary."
)
class OptimisticDispatchListHasNoDefault private constructor(override val location: Range, override val message: String): CVLError() {
    constructor(dispatchList: CallSummary.DispatchList) : this(dispatchList.range, "Using an optimistic unresolved external summary with a default case is not allowed, remove the 'default ...' or use optimistic=false, in the method summary.")
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.METHODS_BLOCK,
    description = "Received a non havocing summary."
)
// No example because the syntax only allowes havocing summaries at the moment
//@CVLErrorExample(
//    exampleCVLWithRange =
//    """
//        methods {
//            function _._ external => DISPATCH [ C._ ] default #ALWAYS(5)#;
//        }
//        """,
//    exampleMessage = "Expecting a havocing summary, but got ALWAYS(5)"
//)
class NonHavocingSummary private constructor(override val location: Range, override val message: String): CVLError() {
    constructor(summary: CallSummary) : this(summary.range, "Expecting a havocing summary, but got $summary")
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.METHODS_BLOCK,
    description = "Received a non wildcard method, but it did not have a param list."
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        methods {
            function _._ external => DISPATCH [ #C.a# ] default NONDET;
        }
        """,
    exampleMessage = "Expecting method parameters for C.a or a wildcard pattern C._"
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        methods {
            function _._ external => DISPATCH [ #_.a# ] default NONDET;
        }
        """,
    exampleMessage = "Expecting method parameters for _.a or a wildcard pattern <Some Contract>._"
)
class NonWildcardNoParams private constructor(override val location: Range, override val message: String): CVLError() {
    constructor(exp: MethodReferenceExp) : this(exp.range, "Expecting method parameters for $exp or a wildcard pattern " +
        "${exp.contract.toString().letIf(exp.contract.toString() == CVLKeywords.wildCardExp.keyword) {"<Some Contract>"}}._")
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.METHODS_BLOCK,
    description = "Received a wildcard method pattern with an argument list which is not supported."
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        methods {
            function _._ external => DISPATCH [#C._()#] default NONDET;
        }
        """,
    exampleMessage = "Not expecting method parameters for wildcard method in C._()"
)
class WildCardMethodWithParams private constructor(override val location: Range, override val message: String): CVLError() {
    constructor(sig: MethodSig) : this(sig.range,
        "Not expecting method parameters for wildcard method in ${sig.baseContract()}._(${sig.params.joinToString(",") { it.toString() }})")
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.METHODS_BLOCK,
    description = "Received more than one catch all unresolved summary (`_._` pattern)."
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        methods {
            function _._ external => DISPATCH [] default NONDET;
            #function _._ external => DISPATCH [] default HAVOC_ALL;#
        }
        """,
    exampleMessage = "Duplicate catch-unresolved summarization for all contracts"
)
class MultipleCatchUnresolvedSummaries private constructor(override val location: Range, override val message: String): CVLError() {
    constructor(entry: CatchUnresolvedSummaryAnnotation) : this(entry.range,
        "Duplicate catch-unresolved summarization for all contracts")
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "Contracts from patterns with specific contracts from dispatch " +
        "list need to exist in the solidity source."
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        methods {
            function _._ external => DISPATCH [ #D.whoami(uint)# ] default NONDET;
        }
        """,
    exampleMessage = "Contract D was not found in the solidity sources."
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        methods {
            function _._ external => DISPATCH [ #D._# ] default NONDET;
        }
        """,
    exampleMessage = "Contract D was not found in the solidity sources."
)
class DispatchListContractNotFound private constructor(override val location: Range, override val message: String): CVLError() {
    constructor(entry: SpecCallSummary.DispatchList.Pattern.WildcardMethod) : this(entry.range,
        "Contract ${entry.contract.contract.name} was not found in the solidity sources.")

    constructor(entry: SpecCallSummary.DispatchList.Pattern.QualifiedMethod) : this(entry.range,
        "Contract ${entry.sig.qualifiedMethodName.host} was not found in the solidity sources.")
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "Dispatch list patterns should at least match one method in solidity sources. " +
        "No matching method was found."
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        methods {
            function _._ external => DISPATCH [ #PrimaryContract.whoami(uint)# ] default NONDET;
        }
        """,
    exampleMessage = "A method with the signature `whoami(uint256)` from the dispatch list was not found in contract `PrimaryContract`."
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        methods {
            function _._ external => DISPATCH [ #_.whoami(uint)# ] default NONDET;
        }
        """,
    exampleMessage = "A method with the signature `whoami(uint256)` from the dispatch list was not found in any contract."
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
        methods {
            function _._ external => DISPATCH [
            _.overloadedFunction(),
            _.overloadedFunction(uint),
            #_.overloadedFunction(uint, uint)#,
             ] default NONDET;
        }
        """,
    exampleMessage = "A method with the signature `overloadedFunction(uint256, uint256)` from the dispatch list was not found in any contract."
)
class DispatchListNoMatchingMethodFound private constructor(override val location: Range, override val message: String): CVLError() {
    constructor(entry: SpecCallSummary.DispatchList.Pattern.WildcardContract) : this(entry.range,
        "A method with the signature `${entry.sig.printMethodParameterSignature()}` from the dispatch list " +
            "was not found in any contract.")

    constructor(entry: SpecCallSummary.DispatchList.Pattern.QualifiedMethod) : this(entry.range,
        "A method with the signature `${entry.sig.printMethodParameterSignature()}` from the dispatch list " +
            "was not found in contract `${entry.sig.qualifiedMethodName.host}`.")
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "There's a path through a CVL function that doesn't end with a return"
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
            #function cvlFun() returns bool {}#
        """,
    exampleMessage = "CVL Function cvlFun does not end with a return statement"
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
            #function cvlFun() returns bool {
                assert true;
            }#
        """,
    exampleMessage = "CVL Function cvlFun does not end with a return statement"
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
            #function cvlFun() returns bool {
                return true;
                assert true;
            }#
        """,
    exampleMessage = "CVL Function cvlFun does not end with a return statement"
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
            #function cvlFun(bool b) returns bool {
                if (b) {
                    return true;
                }
            }#
        """,
    exampleMessage = "CVL Function cvlFun does not end with a return statement"
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
            #function cvlFun(bool b) returns bool {
                if (b) {} else {
                    return true;
                }
            }#
        """,
    exampleMessage = "CVL Function cvlFun does not end with a return statement"
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
            #function cvlFun(bool b) returns bool {
                {
                    return true;
                }
                assert true;
            }#
        """,
    exampleMessage = "CVL Function cvlFun does not end with a return statement"
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
            #function doesNotReturn3(uint256 x, uint256 y) returns uint256 {
                mathint z = x - y;
                if (x > y) {
                    if (z == 500) {
                    return x;
                    }
                } else {
                    return y;
                }
            }#
        """,
    exampleMessage = "CVL Function doesNotReturn3 does not end with a return statement"
)
class DoesNotEndWithReturn private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(sub: CVLFunction) : this(
        sub.range, "CVL Function ${sub.declarationId} does not end with a return statement"
    )
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "There's an unreachable statement after a `return` or `revert`."
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
            function cvlFun(bool b) returns bool {
                if (b) {
                    return true;
                    #assert true;#
                }
                return true;
            }
        """,
    exampleMessage = "Unreachable statement after `return`: `assert true`"
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
            function cvlFun(bool b) returns bool {
                if (b) {} else {
                    return true;
                    #assert true;#
                }
                return true;
            }
        """,
    exampleMessage = "Unreachable statement after `return`: `assert true`"
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
            function cvlFun(bool b) returns bool {
                {
                    return true;
                    #assert true;#
                }
                return true;
            }
        """,
    exampleMessage = "Unreachable statement after `return`: `assert true`"
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
            function cvlFun(bool b) returns bool {
                {
                    revert();
                    #assert true;#
                }
                return true;
            }
        """,
    exampleMessage = "Unreachable statement after `revert`: `assert true`"
)
class UnreachableStatement private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(cmd: CVLCmd, reason: CVLCmd.Simple.HaltingCVLCmd) : this(
        cmd.range, "Unreachable statement after `${reason.cmdName}`: `${cmd.toPrintString()}`"
    )
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "There's a usage of `lastReverted` after a call without the `@withrevert` designation - `lastReverted` is always false in this case"
)
@CVLErrorExample(
    exampleCVLWithRange = """
        rule r(env e) {
            externalFunction(e);
            assert #lastReverted#;
        }
    """,
    exampleMessage = "‘lastReverted’ refers to the previous call from spec and is always false " +
        "after non-reverting call(s) [PrimaryContract.externalFunction(e)]. Did you mean to add `@withrevert`?"
)
@CVLErrorExample(
    exampleCVLWithRange = """
        function f(env e) {
            externalFunction(e);
            assert #lastReverted#;
        }
    """,
)
@CVLErrorExample(
    exampleCVLWithRange = """
        rule r(env e) {
            if (true) {
                externalFunction(e);
            } else {
                externalFunction(e);
            }
            assert #lastReverted#;
        }
    """
)
@CVLErrorExample(
    exampleCVLWithRange = """
        rule r(env e) {
            externalFunction@withrevert(e);
            if (true) {
                assert lastReverted;
            } else {
                externalFunction(e);
                assert #lastReverted#;
            }
            assert true;
        }
    """
)
@CVLErrorExample(
    exampleCVLWithRange = """
        function cvlFunc(env e) {
            externalFunction(e);
        }

        rule r(env e) {
            externalFunction@withrevert(e);
            cvlFunc(e);
            assert #lastReverted#;
        }
    """
)
class LastRevertedAfterNonRevertingCall private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(lastCalls: Set<CVLExp.ApplyExp.RevertAnnotatable>, location: Range) : this(
        location,
        "‘lastReverted’ refers to the previous call from spec and is always false " +
            "after non-reverting call(s) $lastCalls. Did you mean to add `@withrevert`?"
    )
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.SYNTAX,
    description = "The same key is declared multiple times in a flag list"
)
@CVLErrorExample(
    exampleCVLWithRange = """
        methods {
            function _.externalFunction() external => DISPATCHER(optimistic=true, #optimistic#=true);
        }
    """,
    exampleMessage = "The flag 'optimistic' is declared multiple times"
)
class DuplicateKey(override val location: Range, private val flag: String) : CVLError() {
    override val message get() = "The flag '$flag' is declared multiple times"
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.SYNTAX,
    description = "The flag 'optimistic' is required when declaring a `DISPATCHER` summary with keyword arguments"
)
@CVLErrorExample(
    exampleCVLWithRange = """
        methods {
            function _.externalFunction() external => DISPATCHER(#use_fallback=true#);
        }
    """,
    exampleMessage = "The flag 'optimistic' is required when declaring a `DISPATCHER` summary with keyword arguments"
)
class OptimisticFlagRequired(override val location: Range) : CVLError() {
    override val message get() = "The flag 'optimistic' is required when declaring a `DISPATCHER` summary with keyword arguments"
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.SYNTAX,
    description = "An unsupported flag was specified for a `DISPATCHER` summary"
)
@CVLErrorExample(
    exampleCVLWithRange = """
        methods {
            function _.externalFunction() external => DISPATCHER(#optimistic=false, non_existent=true#);
        }
    """,
    exampleMessage = "The flags [non_existent] are unsupported for `DISPATCHER` summaries"
)
@CVLErrorExample(
    exampleCVLWithRange = """
        methods {
            unresolved external in _.externalFunction() => DISPATCH(#non_existent=true#) [] default NONDET;
        }
    """,
    exampleMessage = "The flags [non_existent] are unsupported for `DISPATCH` summaries"
)
class UnexpectedSummaryFlags private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(location: Range, flags: Map<String, Boolean>, summaryType: String) : this(
        location, "The flags ${flags.keys} are unsupported for `$summaryType` summaries"
    )
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = ""
)
@CVLErrorExample(
    exampleCVLWithRange = """
        function f() {
            env e; calldataarg args; address a;
            a.#externalFunction@withrevert(e, args)#;
        }
    """
)
class AddressFuncCallWithRevert private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(exp: CVLExp.UnresolvedApplyExp) : this(exp.getRangeOrEmpty(), "Cannot use `@withrevert` for address function calls")
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = ""
)
@CVLErrorExample(
    exampleCVLWithRange = """
        function f() {
            address a;
            a.#externalFunction()#;
        }
    """
)
class AddressFuncCallNoEnv private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(exp: CVLExp.UnresolvedApplyExp) : this(exp.getRangeOrEmpty(), "Address function calls require to use an `env` variable in the call")
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = ""
)
@CVLErrorExample(
    exampleCVLWithRange = """
        function f() {
            env e; address a;
            a.#non_existent(e)#;
        }
    """
)
@CVLErrorExample(
    exampleCVLWithRange = """
        function f() {
            env e; address a;
            a.#externalFunction(e, a)#;
        }
    """
)
class AddressFuncCallNoFuncs private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(exp: CVLExp.UnresolvedApplyExp) : this(exp.getRangeOrEmpty(), "Did not find any contract functions that match the given name and arguments")
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = ""
)
@CVLErrorExample(
    exampleCVLWithRange = """
        function f() {
            env e; address a;
            a.#differentReturnTypes(e)#;
        }
    """,
    exampleMessage = "The function `differentReturnTypes` has several implementation with different return values [uint256, int256]. Cannot perform an address function call on them"
)
class AddressFuncCallMultipleReturnTypes private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(exp: CVLExp.UnresolvedApplyExp, returnTypes: Set<CVLType>) : this(
        exp.getRangeOrEmpty(),
        "The function `${exp.methodId}` has several implementation with different return values $returnTypes. Cannot perform an address function call on them"
    )
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "attempting to `reset_storage` on something other than a contract alias"
)
@CVLErrorExample(
    exampleCVLWithRange = """
        function f() {
            address a;
            reset_storage #a#;
        }
    """,
    exampleMessage = "can only `reset_storage` of `currentContract`, `allContracts`, or contracts declared via a `using` statement. Got `a`"
)
class ResetStorageOnNonContract private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(exp: CVLExp) : this(
        exp.getRangeOrEmpty(),
        "can only `reset_storage` of `currentContract`, `allContracts`, or contracts declared via a `using` statement. Got `$exp`"
    )
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "filtered out all possible foundry fuzz tests"
)
class NoFoundryTestsLeft private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(range: Range, ruleName: String, mainContract: String) : this(
        range,
        if (!Config.methodsAreFiltered) {
            "Rule `$ruleName` found no fuzz tests in contract `$mainContract`!\""
        } else {
            "After filtering to methods specified by ${Config.methodChoiceFlagsUserFacingNames}, no fuzz test methods were left."
        }
    )
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "One cannot alias contracts that are marked as extensions of other contracts. In order to call functions" +
        "from that contract, use an alias to the extended contract instead."
)
@CVLErrorExample(
    exampleCVLWithRange = """
        #using Extension as extension;#
    """
)
class AliasingExtensionContract private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(impContract: CVLImportedContract, extendedContractName: List<String>) : this(
        impContract.range,
        "${impContract.contractName} is an extension contract of $extendedContractName " +
            "so can't be referenced directly in CVL. Its methods should be called " +
            "directly from the extended contract."
    )
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.CLI,
    description = "a non-existent method was specified via `--method`"
)
class NoSuchMethodChoice private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(methodChoice: String, suggestions: Set<String>) : this(
        Range.Empty(),
        "Could not find a method named $methodChoice in the scene." + if (suggestions.isEmpty()) {
            ""
        } else {
            suggestions.joinToString(separator = " or ", prefix = " Did you mean ", postfix = "?")
        }
    )
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "summing is only supported on ghost mappings"
)
@CVLErrorExample(
    exampleCVLWithRange = """
        rule r {
            mathint m = sum uint n. #n#;
            assert true;
        }
    """
)
@CVLErrorExample(
    exampleCVLWithRange = """
        rule r {
            uint u;
            mathint m = sum uint n. #u#;
            assert true;
        }
    """
)
@CVLErrorExample(
    exampleCVLWithRange = """
        rule r {
            uint[] arr;
            mathint m = sum uint n. #arr[n]#;
            assert true;
        }
    """
)
@CVLErrorExample(
    exampleCVLWithRange = """
        rule r {
            mathint m = sum uint n. #currentContract.array_field[n]#;
            assert true;
        }
    """
)
class SumOnNonGhostVariable private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(body: CVLExp) : this(
        body.getRangeOrEmpty(),
        "Summing is only supported on ghost mappings, got `$body`"
    )
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "sum variables must have a value type"
)
@CVLErrorExample(
    exampleCVLWithRange = """
        ghost mapping(bytes => uint) notSummable;
        rule r {
            mathint m = #sum bytes b. notSummable[b]#;
            assert true;
        }
    """
)
class SumVariableNotValueType private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(exp: CVLExp.SumExp, param: CVLParam) : this(
        exp.getRangeOrEmpty(),
        "Sum param $param must have a value type"
    )
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "the body of the sum does not have a summable type"
)
@CVLErrorExample(
    exampleCVLWithRange = """
        ghost mapping(bytes32 => mapping(int => uint)) aGhost;
        rule r {
            mathint m = sum bytes32 b. #aGhost[b]#;
            assert true;
        }
    """
)
@CVLErrorExample(
    exampleCVLWithRange = """
        ghost mapping(int => PrimaryContract.ExampleEnum) aGhost;
        rule r {
            mathint m = sum int n. #aGhost[n]#;
            assert true;
        }
    """
)
class SumBodyNotSummable private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(body: CVLExp.ArrayDerefExp) : this(
        body.getRangeOrEmpty(),
        "The body of a sum must have a type that is summable (for instance int, uint, address), got ${body.getCVLType()}"
    )
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "all sum arguments must be used within the sum's body"
)
@CVLErrorExample(
    exampleCVLWithRange = """
        ghost mapping(address => uint) summable;
        rule r {
            mathint m = #sum address a, address b. summable[a]#;
            assert true;
        }
    """
)
class SumUnusedArguments private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(exp: CVLExp.SumExp, unusedArgs: List<CVLParam>) : this(
        exp.getRangeOrEmpty(),
        "All sum arguments must be used in the sum body. The following are unused: ${unusedArgs.joinToString { it.id }}"
    )
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "Cannot nest sums"
)
@CVLErrorExample(
    exampleCVLWithRange = """
        ghost mapping(address => mapping(mathint => uint)) summable;
        ghost mapping(int => int8) anotherGhost;
        rule r {
            mathint m = #sum address a. summable[a][sum int m. anotherGhost[m]]#;
            assert true;
        }
    """,
    exampleMessage = "Cannot use nested sums. Use multiple arguments in the outermost sum instead. e.g. `sum address a, int256 m. ...`"
)
class NestedSumsExpressions private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(exp: CVLExp.SumExp, subSums: List<CVLExp.SumExp>) : this(
        exp.getRangeOrEmpty(),
        "Cannot use nested sums. Use multiple arguments in the outermost sum instead. e.g. " +
        "`sum ${exp.params.joinToString()}, ${subSums.joinToString { it.params.joinToString() }}. ...`"
    )
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "Cannot use sum parameters in complex expressions"
)
@CVLErrorExample(
    exampleCVLWithRange = """
        ghost mapping(mathint => uint) summable;
        rule r {
            mathint m = sum mathint a. summable[#2 * a#];
            assert true;
        }
    """
)
class SumNonBasicParamExpression private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(nonVarExp: CVLExp) : this(
        nonVarExp.getRangeOrEmpty(),
        "Sum parameters can only be used as index values, not $nonVarExp"
    )
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "Cannot sum the same ghost both with sum and usum"
)
@CVLErrorExample(
    exampleCVLWithRange = """
        #ghost mapping(mathint => uint) summable;#
        function f {
            mathint m1 = sum mathint a. summable[a];
            mathint m2 = usum mathint a. summable[a];
        }
    """
)
@CVLErrorExample(
    exampleCVLWithRange = """
        #ghost mapping(mathint => mapping(mathint => uint)) summable;#
        function f {
            mathint m;
            mathint m1 = sum mathint a. summable[m][a];
            mathint m2 = usum mathint a. summable[a][m];
        }
    """
)
class SumSignedAndUnsignedNotSupported private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(origGhostName: String, location: Range) : this(
        location,
        "Cannot use both `sum` and `usum` on the same ghost $origGhostName"
    )
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "usum is only supported on ghosts with unsigned target types"
)
@CVLErrorExample(
    exampleCVLWithRange = """
        ghost mapping(uint => int) g;
        function f() {
            assert #(usum uint u. g[u])# >= 0;
        }
    """
)
class UnsignedSumOnSignedGhostType private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(location: Range, body: CVLExp.ArrayDerefExp) : this(
        location,
        "Cannot use `usum` on ${body.baseArray}, since its target type is not unsigned."
    )
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "revert statements are only allowed inside function definitions"
)
@CVLErrorExample(
    exampleCVLWithRange =
    """
    rule cannotUseRevertOutsideOfFunction {
        bool b;
        if (!b) {
            #revert();# // not allowed
        }
        assert b;
    }
    """,
    exampleMessage = "Revert statement is not allowed outside a CVL function."
)
class RevertCmdOutsideOfFunction private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(range: Range) : this(
        range,
        "Revert statement is not allowed outside a CVL function."
    )
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.TYPECHECKING,
    description = "require statements should have a reason"
)
class RequireWithoutReason private constructor(override val location: Range, override val message: String) : CVLError() {
    constructor(range: Range, exp: CVLExp) : this(
        range,
        "No reason provided for assumption of $exp."
    )
}

@KSerializable
@CVLErrorType(
    category = CVLErrorCategory.METHODS_BLOCK,
    description = "Reroute target must be compatible with summarized function"
)
class IllegalRerouteSummary(
    override val location: Range,
    private val errorType: ErrorSort
) : CVLError() {
    @KSerializable
    sealed class ErrorSort : AmbiSerializable {
        @KSerializable
        data class ParameterTypeMismatch(val tgt: QualifiedFunction, val params: List<VMTypeDescriptor>) : ErrorSort()

        @KSerializable
        data class NotALibrary(val tgt: QualifiedFunction) : ErrorSort()

        @KSerializable
        data class ReturnTypeMismatch(val tgt: QualifiedMethodSignature, val expected: List<VMTypeDescriptor>) : ErrorSort()

        @KSerializable
        data class NoWithClause(val withRange: Range) : ErrorSort()

        @KSerializable
        data class IllegalArgument(val exp: CVLExp) : ErrorSort()
        @KSerializable
        data class NoMatchingFunction(val missingFunctionName: QualifiedFunction) : ErrorSort()
    }

    private fun List<VMTypeDescriptor>.formatAsLibrary() = this.joinToString(", ", prefix = "(", postfix = ")") {
        it.canonicalString(PrintingContext(isLibrary = true))
    }

    private fun List<VMTypeDescriptor>.returnDescription() = when(this.size) {
        0 -> "returns no values"
        else -> "returns ${
            this.joinToString(", ", prefix = "(", postfix = ")") {
                it.canonicalString(PrintingContext(isLibrary = true))
            }
        }"
    }

    private val caveat get() = "(If you are not attempting to use a reroute summary, remember that you cannot invoke external library functions or bind storage parameters outside of rerouting summaries.)"

    override val message: String get() = when(errorType) {
        is ErrorSort.ReturnTypeMismatch -> "Found matching declaration for ${errorType.tgt.qualifiedMethodName}${errorType.tgt.paramTypes.formatAsLibrary()}," +
            " but it ${errorType.tgt.resType.returnDescription()} where the summarized function ${errorType.expected.returnDescription()}"

        is ErrorSort.IllegalArgument -> {
            "Illegal argument ${errorType.exp} for reroute summary; only parameters bound by the method block declaration can be used as reroute arguments. $caveat"
        }
        is ErrorSort.NoWithClause -> "Reroute summaries cannot have `with` clauses: found a `with` clause at ${errorType.withRange}. $caveat"
        is ErrorSort.NotALibrary -> "Selected reroute target function ${errorType.tgt} is not an external library function. $caveat"
        is ErrorSort.ParameterTypeMismatch -> "No external library function ${errorType.tgt.methodId}${errorType.params.joinToString(prefix = "(", postfix = ")", separator = ", ") {
            it.prettyPrint()
        }} exists in reroute host contract ${errorType.tgt.host.name}. $caveat"

        is ErrorSort.NoMatchingFunction -> "No external functions with name ${errorType.missingFunctionName.methodId} in reroute host contract ${errorType.missingFunctionName.host.name}. $caveat"
    }
}
