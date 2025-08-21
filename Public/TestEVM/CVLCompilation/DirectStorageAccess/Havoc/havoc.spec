using Test as t;

rule top_levels {
    uint24 before1 = t.topLevel1;
    uint48 before2 = t.topLevel2;
    bool   before3 = t.topLevel3;

    havoc t.topLevel1, t.topLevel2;

    uint24 after1 = t.topLevel1;
    uint48 after2 = t.topLevel2;
    bool   after3 = t.topLevel3;

    satisfy before1 != after1, "topLevel1 has been havoced and may change";
    satisfy before2 != after2, "topLevel2 has been havoced and may change";
    assert  before3 == after3, "topLevel3 has not been havoced and must not change";
}

rule top_levels_consistency {
    env e;
    uint24 getter1;
    uint48 getter2;
    bool   getter3;

    havoc t.topLevel1, t.topLevel2;

    uint24 after1 = t.topLevel1;
    uint48 after2 = t.topLevel2;
    bool   after3 = t.topLevel3;

    getter1, getter2, getter3 = t.getTopLevels(e);

    assert after1 == getter1
        && after2 == getter2
        && after3 == getter3,
        "storage consistency";
}

rule array_deref {
    address key;
    uint idx;
    uint i;
    uint j;

    bool before = currentContract.a[key][idx].bar[j];
    havoc t.a[key][idx].bar[i];
    bool after = currentContract.a[key][idx].bar[j];

    assert before != after => j == i, "the element at index i has been havoced and may change";
    satisfy before != after && j == i, "ensure the above isn't vacuous";

    // note that (from contraposition) the above assert is equivalent to j != i => before == after
    // therefore we also assert that an element at index j != i must not change.
}

rule array_deref_consistency {
    env e;
    address key;
    uint i;

    havoc t.a[key][i].bar[i];

    assert currentContract.a[key][i].bar[i] == getter2(e, key, i), "storage consistency";
}

rule array_length {
    env e;
    address k;
    uint i;

    uint before = t.a[k][i].bar.length;
    havoc t.a[k][i].bar.length;
    uint after = t.a[k][i].bar.length;

    assert after == getter3(e, k, i), "storage consistency";

    satisfy before != after, "array length has been havoced and may change";
}

/**
 * havocing array length only changes how "far" into memory we're reading.
 * so for example, let arr := [A, B, C, D], arr.length = 4 and we havoc arr.length
 * if new_length = 2 (new_length < old_length): [A, B] because the underlying "memory" did not change
 * if new_length = 6 (new_length > old_length): [A, B, C, D, E, F] for some E, F which happened to be written past the last index
 */
rule array_length_elements {
    address k;
    uint i;

    mathint length_before = t.a[k][i].bar.length;
    uint last_idx_before = require_uint256(length_before - 1);
    bool last_elem_before = t.a[k][i].bar[last_idx_before];

    havoc t.a[k][i].bar.length;

    mathint length_after = t.a[k][i].bar.length;
    uint last_idx_after = require_uint256(length_after - 1);
    bool last_elem_after = t.a[k][i].bar[last_idx_after];
    bool elem_at_prev_last_idx_after = t.a[k][i].bar[last_idx_before];

    satisfy last_elem_before != last_elem_after, "array length may have changed, therefore last element may have changed";
    assert last_elem_before == elem_at_prev_last_idx_after, "only the array length may have changed, the underlying data did not";
}

rule array_length_and_reverts {
    env e;
    address k;
    uint i;

    uint length_before = t.a[k][i].bar.length;

    // this assumes no revert, which implies i is valid (i < length_before)
    // thus from here on, 0 <= i < length_before
    getter2(e, k, i);

    havoc t.a[k][i].bar.length;

    uint length_after = t.a[k][i].bar.length;

    getter2@withrevert(e, k, i);

    assert  i >= length_after => lastReverted, "may revert for other reasons, but will surely revert for invalid values of i";
    satisfy i >= length_after, "ensure the above isn't vacuous";
}

rule top_level_mapping {
    address fizz;
    address buzz;

    uint128 before = t.friday[fizz];
    havoc t.friday[buzz];
    uint128 after = t.friday[fizz];

    satisfy fizz == buzz && before != after, "value at buzz has been havoced and may have changed";
    assert  fizz != buzz => before == after, "values at other keys have not been havoced";
}

hook Sstore friday[KEY address addr] uint128 new_value {
    address other_addr;
    require (new_value % 2 == 0 && other_addr == addr) || other_addr != addr;

    havoc t.friday[other_addr];
}

rule havoc_in_store_hook {
    env e;
    address addr;

    setFriday(e, addr, 13);
    assert t.friday[addr] == 13, "havoced at a different address";

    setFriday(e, addr, 666);
    satisfy t.friday[addr] != 666, "value havoced inside hook";
}

// the type constraints placed on a havoced variable during storage havoc compilation,
// are only valid for the CVL representation of types (tl;dr - mathints).
// thus the constraints must be placed on a CVL variable, and placing them on a
// VM variable may restrict the possible values to only positive values.
// that would obviously be incorrect for signed types, so this test ensures it didn't happen.
rule havoc_constraints_for_signed_primitive {
    env e;

    satisfy t.topLevelSigned < 0, "sanity (signed variables can be negative)";
    satisfy getSigned(e) < 0,     "sanity (signed variables can be negative)";

    havoc t.topLevelSigned;
    satisfy t.topLevelSigned < 0, "proper type constraints on havoced value";
}
