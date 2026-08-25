/*
 * EIP-7843: the SLOTNUM opcode, which pushes the current block's slot number
 * as a uint64.
 */

ghost mathint lastSlotnumSeen;

hook SLOTNUM uint v {
    lastSlotnumSeen = to_mathint(v);
}

rule slotNumFitsInUint64() {
    env e;
    uint256 slot = slotnum@withrevert(e);
    assert !lastReverted, "SLOTNUM must not halt";
    assert slot <= max_uint64, "SLOTNUM pushes a uint64";
}

rule slotNumIsStableWithinATransaction() {
    env e;
    uint256 delta = slotnumDelta@withrevert(e);
    assert !lastReverted, "SLOTNUM must not halt";
    assert delta == 0, "two SLOTNUM executions in one transaction must agree";
}

rule slotNumIsStableAcrossCallsInTheSameBlock() {
    env e;
    uint256 first = slotnum(e);
    uint256 second = slotnum(e);
    assert first == second, "SLOTNUM must be a property of the block, not havocked per call";
}

rule differentEnvsCanHaveDifferentSlotNums() {
    env e1;
    env e2;
    uint256 first = slotnum(e1);
    uint256 second = slotnum(e2);
    satisfy first != second, "the slot number must not be fixed across blocks";
}

rule slotNumMatchesEnv() {
    env e;
    assert to_mathint(slotnum(e)) == to_mathint(e.block.slotnum),
        "SLOTNUM must return the slot number the env was set up with";
}

rule slotNumHookObservesTheValue() {
    env e;
    uint256 slot = slotnum(e);
    assert lastSlotnumSeen == to_mathint(slot), "the SLOTNUM hook must see the value SLOTNUM returned";
}
