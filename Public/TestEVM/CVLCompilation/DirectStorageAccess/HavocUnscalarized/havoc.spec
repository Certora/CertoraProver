/*
   Havocing a field of a non-scalarized packed slot must leave the field unconstrained (and must not
   disturb its neighbours in the slot), regardless of the field's byte offset within the slot.
 */

rule havoc_hi_unconstrained {
    havoc currentContract.s.hi;
    satisfy currentContract.s.hi != 0, "havoced field may be non-zero";
}

rule havoc_hi_max {
    havoc currentContract.s.hi;
    satisfy currentContract.s.hi == max_uint128, "havoced field may take any value of its type";
}

rule havoc_hi_mapping_unconstrained(uint256 key) {
    havoc currentContract.s_mapping[key].hi;
    satisfy currentContract.s_mapping[key].hi != 0, "havoced field of a mapping value may be non-zero";
}

rule havoc_hi_unconstrained_signed {
    havoc currentContract.t.hi;
    satisfy currentContract.t.hi < 0, "havoced signed field may be negative";
}

rule havoc_middle_field_exact {
    havoc currentContract.u.b;
    satisfy currentContract.u.b == 0x112233445566778899aabbccddeeff00,
        "havoced field in the middle of the slot may take any value of its type";
}

rule havoc_hi_preserves_lo {
    uint128 old_lo = currentContract.s.lo;
    havoc currentContract.s.hi;
    assert currentContract.s.lo == old_lo, "the neighbouring field must not change";
}

rule havoc_lo_preserves_hi {
    uint128 old_hi = currentContract.s.hi;
    havoc currentContract.s.lo;
    assert currentContract.s.hi == old_hi, "the neighbouring field must not change";
}

rule havoc_middle_field_preserves_neighbors {
    uint64 old_a = currentContract.u.a;
    uint64 old_c = currentContract.u.c;
    havoc currentContract.u.b;
    assert currentContract.u.a == old_a && currentContract.u.c == old_c,
        "the neighbouring fields must not change";
}
