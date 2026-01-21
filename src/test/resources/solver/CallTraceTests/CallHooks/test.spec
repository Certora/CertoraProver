methods{
    function callSetValue(address, uint) external returns uint envfree;
}

hook CALL(uint g, address addr, uint value, uint argsOffs, uint argLength, uint retOffset, uint retLength) uint rc {
  assert false,  "intentionally fail to test call trace";
}

rule trigger_call_opcode(address target, uint val) {
    assert callSetValue(target, val) == val;
}
