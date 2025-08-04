methods {
   unresolved external in _.test(uint256[], uint256[]) => DISPATCH [
     LHarness.foo(uint256[] memory),
   ] default HAVOC_ECF;
}

rule foo {
  env e;
  calldataarg a;
  test@withrevert(e,a);
  satisfy (true);
}