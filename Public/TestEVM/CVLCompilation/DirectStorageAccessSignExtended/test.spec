/*
  StorageTypeBounder stores small signed split storage fields sign-extended and
  strips the SIGNEXTEND decode from the contract's own loads. These rules pin
  down the CVL direct-storage-access side of that convention (marked via the
  SIGN_EXTENDED_STORAGE meta): direct reads must agree with the contract's view
  of the field, and havocs must store values that the contract's
  sign-extended-range assumptions accept — in particular, negative values must
  remain reachable after a havoc.
*/

methods {
    function getOff(uint256) external returns (int200) envfree;
    function setOff(uint256, int200) external envfree;
    function getTail(uint256) external returns (uint56) envfree;
    function getOff2(uint256) external returns (int200) envfree;
    function setOff2(uint256, int200) external envfree;
    function getHead2(uint256) external returns (uint56) envfree;
}

rule read_matches_getter(uint256 k) {
    assert currentContract.m[k].off == getOff(k),
        "direct read agrees with the contract's own load";
}

rule setter_visible_to_direct_read(uint256 k, int200 v) {
    setOff(k, v);
    assert currentContract.m[k].off == v,
        "value stored by the contract is read back exactly";
}

rule setter_negative_visible(uint256 k) {
    setOff(k, -1);
    assert currentContract.m[k].off == -1,
        "negative value stored by the contract is read back exactly";
}

rule havoc_negative_reachable(uint256 k) {
    havoc currentContract.m[k].off;
    satisfy currentContract.m[k].off < 0,
        "havoced signed field may be negative";
}

rule havoc_agrees_with_contract(uint256 k) {
    havoc currentContract.m[k].off;
    assert currentContract.m[k].off == getOff(k),
        "havoced value is stored in the convention the contract's loads expect";
}

rule havoc_preserves_neighbor(uint256 k) {
    uint56 before = getTail(k);
    havoc currentContract.m[k].off;
    assert getTail(k) == before,
        "havocing one packed field does not disturb its slot neighbor";
}

// The same rules for `S2.off`, whose slot offset is non-zero: the convention is
// a property of the (bottom-aligned) split variable, not of the slot layout.

rule packed_read_matches_getter(uint256 k) {
    assert currentContract.m2[k].off == getOff2(k),
        "direct read of a non-zero-offset field agrees with the contract's own load";
}

rule packed_setter_negative_visible(uint256 k) {
    setOff2(k, -1);
    assert currentContract.m2[k].off == -1,
        "negative value stored by the contract is read back exactly";
}

rule packed_havoc_negative_reachable(uint256 k) {
    havoc currentContract.m2[k].off;
    satisfy currentContract.m2[k].off < 0,
        "havoced signed field may be negative";
}

rule packed_havoc_agrees_with_contract(uint256 k) {
    havoc currentContract.m2[k].off;
    assert currentContract.m2[k].off == getOff2(k),
        "havoced value is stored in the convention the contract's loads expect";
}

rule packed_havoc_preserves_neighbor(uint256 k) {
    uint56 before = getHead2(k);
    havoc currentContract.m2[k].off;
    assert getHead2(k) == before,
        "havocing one packed field does not disturb its slot neighbor";
}
