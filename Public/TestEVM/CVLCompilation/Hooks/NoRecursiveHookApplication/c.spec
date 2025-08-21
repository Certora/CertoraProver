methods {
  function myInternalFoo() internal => callBar();
  function x() external returns (uint256) envfree;
}

function callBar() {
  env e;
  bar(e);
}

ghost uint xGhost;
hook Sstore x uint v {
  xGhost = v;
  env e;
  foo(e);
}

rule badCheck() {
  env e;
  main(e);
  assert xGhost == x(); // will fail - bar()'s hooks are not instrumented. xGhost == 3 while x == 5
}

rule goodCheck() {
  env e;
  main(e);
  assert xGhost == 3;
  assert x() == 5;
}
