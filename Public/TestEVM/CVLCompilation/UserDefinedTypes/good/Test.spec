using Test as Testest;
using SuperTest as superduper;
using TestLibrary as testlib;
using SuperTestLibrary as superlib;

/**
 *  Tests the usage of the following user defined solidity types:
 *   - Enums
 *   - Structs (excluding array types for now)
 *   - User Defined Value Types (the Solidity name for type aliases, only used for value types)
 *
 *  It tests their usage in:
 *   - rules
 *   - summaries
 *   - cvl functions
 *   - ghost functions
 *   - abi function calls (excluding structs)
 */


methods {
    // external interface
    function my_storage_value_is(Test.EnumClaw) external returns (bool) envfree;
    function my_storage_value() external returns (Test.EnumClaw) envfree;
    function whats_my_alias() external returns (Test.Alias) envfree;
    function guess_my_alias(Test.Alias) external returns (bool) envfree;
    function is_worst_case(Test.StructuralDamage damage) external returns (bool) envfree;
    function is_too_late(Test.SelfDeStruct info) external returns (bool) envfree;
    function call_enum_and_value_function(
        SuperTestLibrary.EnumInALibraryInASuperContract,
        SuperTestLibrary.SupremeType) external returns (bool) envfree;
    function internal_call_choose_an_enum(Test.Alias switcher) external returns (Test.EnumClaw) envfree;
    function external_call_choose_an_enum(Test.Alias switcher) external returns (Test.EnumClaw) envfree;
    function internal_call_choose_an_alias(SuperTest.EnumInASuperContract) external returns (SuperTestLibrary.SupremeType) envfree;
    function external_call_choose_an_alias(SuperTest.EnumInASuperContract) external returns (SuperTestLibrary.SupremeType) envfree;
    function internal_call_guess_my_struct() external returns (bool) envfree;
    function external_call_guess_my_struct() external returns (bool) envfree;
    function superduper.i_return_a_tuple_with_an_enum_and_a_value_type(bool,uint64) external returns (SuperTestLibrary.EnumInALibraryInASuperContract, SuperTestLibrary.SupremeType) envfree;
    function set_my_enum(Test.EnumClaw e) external envfree;

    // summaries
    function SuperTestLibrary.internal_enum_and_value_function_to_summarize(
        SuperTestLibrary.EnumInALibraryInASuperContract x,
        SuperTestLibrary.SupremeType y) internal returns (bool) => summary_takes_an_enum_and_alias(x, y);
    function choose_an_enum(Test.Alias switcher) internal returns (Test.EnumClaw) => cvl_function_summary_takes_an_alias_and_returns_enum(switcher);
    function SuperTest.choose_an_alias(SuperTest.EnumInASuperContract x) internal returns (SuperTestLibrary.SupremeType) => cvl_function_summary_takes_an_enum_and_returns_alias(x);
    function guess_my_struct(Test.SelfDeStruct memory) internal returns (bool) => ALWAYS(false);
}

ghost summary_takes_an_enum_and_alias(SuperTestLibrary.EnumInALibraryInASuperContract, SuperTestLibrary.SupremeType) returns bool {
    axiom   forall SuperTestLibrary.EnumInALibraryInASuperContract x.
                    forall SuperTestLibrary.SupremeType y.
                summary_takes_an_enum_and_alias(x, y) == ((x == SuperTestLibrary.EnumInALibraryInASuperContract.B) &&
                        y > 5);
}

function cvl_function_summary_takes_an_alias_and_returns_enum(Test.Alias x) returns Test.EnumClaw {
    if (x < 2) {
        return Test.EnumClaw.MountRainier;
    } else if (x < 22) {
        return Test.EnumClaw.Auburn;
    } else if (x < 222) {
        return Test.EnumClaw.WhiteRiver;
    } else if (x <= max_uint128) {
        return Test.EnumClaw.TheClaw;
    } else {
        // this branch should be unreachable since Test.Alias wraps uint128
        return Test.EnumClaw.Safeway;
    }
}

// At the time of writing it was impossible to directly return a uint8 literal (there is no casting expression to uint8)
// which is why in each branch we constrain a symbolic value ret, rather than returning in each branch.
function cvl_function_summary_takes_an_enum_and_returns_alias(SuperTest.EnumInASuperContract x) returns SuperTestLibrary.SupremeType {
    SuperTestLibrary.SupremeType ret;
    if (x == SuperTest.EnumInASuperContract.Yeet) {
        ret = 5;
    } else if (x == SuperTest.EnumInASuperContract.McGeet) {
        ret = 10;
    } else {
        ret = 20;
    }
    return ret;
}

invariant we_love_auburn() my_storage_value() == Test.EnumClaw.Auburn {
    // test that a preserved with enum arg compiles
    // this will fail because set_my_enum is required to change the storage value
    preserved set_my_enum(Test.EnumClaw e) {
        require e == Test.EnumClaw.Auburn;
    }
}

invariant we_are_aliases() whats_my_alias() == 25 {
    // test that a preserved with a user defined value type arg compiles
    preserved guess_my_alias(Test.Alias x) {
        require x == 25;
    }
}


// Tests both passing an enum type arg to ABI function call and
// receiving an enum type as a return value
rule pass_an_enum_arg(Test.EnumClaw x) {
    require my_storage_value() == x;
    Test.EnumClaw y = my_storage_value();
    assert x == y;
    assert my_storage_value_is(x);
}

rule pass_a_struct_arg(Test.StructuralDamage x) {
    require x.assessment > 9000 && x.year == 2020;
    assert is_worst_case(x);
}

rule pass_a_struct_arg_should_fail(Test.StructuralDamage x) {
    assert is_worst_case(x);
}

rule pass_a_nested_struct_arg(Test.SelfDeStruct info) {
    require info.time_remaining < 5 && info.the_method > 4337 && info.where_to_destroy == Test.EnumClaw.Safeway && is_worst_case(info.damage);
    assert is_too_late(info);
}


rule pass_a_nested_struct_arg_should_fail(Test.SelfDeStruct info) {
    require info.time_remaining < 5 && info.the_method > 4337 && info.where_to_destroy == Test.EnumClaw.Auburn;
    assert is_too_late(info);
}

rule use_method_selector(method f) {
    env e; calldataarg args;
    Test.EnumClaw before = my_storage_value();
    f(e, args);
    Test.EnumClaw after = my_storage_value();
    assert f.selector == sig:set_my_enum(Test.EnumClaw).selector => before != after;
}

rule assign_a_struct(Test.SelfDeStruct x) {
    Test.SelfDeStruct y;
    y = x;
    Test.StructuralDamage z = y.damage;
    assert y.time_remaining == x.time_remaining;
    assert z.assessment == x.damage.assessment;
}

// Tests both passing a user defined value type arg to ABI function call and
// receiving a user defined value type as a return value
rule pass_a_user_defined_arg(Test.Alias x) {
    require whats_my_alias() == x;
    Test.Alias y = whats_my_alias();
    assert x == y;
    assert guess_my_alias(x);
}

rule read_a_tuple_return(bool b, uint64 n) {
    SuperTestLibrary.EnumInALibraryInASuperContract e;
    SuperTestLibrary.SupremeType s;
    e, s = superduper.i_return_a_tuple_with_an_enum_and_a_value_type(b, n);
    assert s == n && b => e == SuperTestLibrary.EnumInALibraryInASuperContract.A;
}

rule read_a_tuple_return_sanity_should_fail(bool b, uint64 n) {
    SuperTestLibrary.EnumInALibraryInASuperContract e;
    SuperTestLibrary.SupremeType s;
    e, s = superduper.i_return_a_tuple_with_an_enum_and_a_value_type(b, n);
    assert !b => e == SuperTestLibrary.EnumInALibraryInASuperContract.A;
}

rule summarize_enum_and_value_arg_with_ghost {
    SuperTestLibrary.EnumInALibraryInASuperContract x = SuperTestLibrary.EnumInALibraryInASuperContract.B;
    SuperTestLibrary.SupremeType y = 300;
    // require summary_takes_an_enum_and_alias(x, y);
    assert call_enum_and_value_function(x, y);
}

rule summarize_enum_and_value_arg_with_ghost_should_fail {
    // check vacuity bug in summary (uses axioms, easy to bork)
    SuperTestLibrary.EnumInALibraryInASuperContract x = SuperTestLibrary.EnumInALibraryInASuperContract.B;
    SuperTestLibrary.SupremeType y = 300;
    // require summary_takes_an_enum_and_alias(x, y);
    assert call_enum_and_value_function(x, y);
    assert false;
}

rule summarize_internal_call_with_cvl_function(Test.Alias switcher) {
    // also testing that Test.Alias doesn't go over max_uint128
    assert internal_call_choose_an_enum(switcher) != Test.EnumClaw.Safeway;
}

rule summarize_external_call_with_cvl_function {
    uint8 x;
    require x == 21;
    assert external_call_choose_an_enum(x) == Test.EnumClaw.Auburn;
}

rule summarize_internal_call_with_cvl_function_takes_enum(SuperTest.EnumInASuperContract x) {
    assert internal_call_choose_an_alias(x) != 20;
}

rule summarize_external_call_with_cvl_function_takes_enum(SuperTest.EnumInASuperContract x) {
    assert external_call_choose_an_alias(x) == 5 || external_call_choose_an_alias(x) == 10;
}

rule summarize_external_call_struct_arg {
    assert !external_call_guess_my_struct();
}

rule summarize_internal_call_struct_arg {
    assert !internal_call_guess_my_struct();
}

rule check_bidwidths(Test.SelfDeStruct x) {
    Test.SelfDeStruct y;

    assert x.time_remaining <= max_uint64;
    assert x.the_method <= max_uint64;
    assert x.damage.assessment <= max_uint128;
    assert x.damage.year <= max_uint256;

    assert y.time_remaining <= max_uint64;
    assert y.the_method <= max_uint64;
    assert y.damage.assessment <= max_uint128;
    assert y.damage.year <= max_uint256;
}
