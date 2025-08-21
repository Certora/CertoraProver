methods {
  function x() external returns (uint256) envfree;
}

hook Sstore x uint v {
    // Oops I am empty
}

rule parametric_sanity() {
  env e;
  method m;
  calldataarg args;
  m(e, args);
  satisfy true, "sanity";
}
