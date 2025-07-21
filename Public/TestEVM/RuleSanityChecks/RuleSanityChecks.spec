methods {
  function require_i_lowerthan_10() external envfree;
  function require_nothing() external envfree;
  function always_revert() external envfree;
  function i() external returns uint envfree;
}

rule check_assert_unreachable_01() {
  require i() == 10;
  require_i_lowerthan_10();
  // this line is unreachable
  assert i() == 123; // this assertion will succeed because it is unreachable
}

rule check_assert_unreachable_03() {
  uint i = 1;
  always_revert();
  // this line is unreachable
  assert i == 123; // this assertion will succeed, but only because it is unreachable
}

rule check_assert_reachable_dependent_on_method_parameter_01(method f, calldataarg clldt) {
  require i() == 10;
  env e;
  f(e, clldt);
  // this line is unreachable when f == require_i_lowerthan_10, and f == always_revert
  // otherwise it is reachable
  assert i() == 123; // this assertion will fail iff it is reachable
}

rule check_assert_reachable_01() {
  require i() == 10;
  assert i() == 10; // this assertion is reachable and will succeed
}

rule check_assert_reachable_02() {
  require i() == 10;
  assert i() == 11; // this assertion is reachable and will fail
}

rule check_assert_reachable_03() {
  require i() == 9;
  require_i_lowerthan_10();
  assert i() == 9; // this assertion is reachable and will succeed
}
