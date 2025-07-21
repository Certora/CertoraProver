methods {
  function setB() external;
}

// there should be no collision with sanity, because each rule computes sanity independently of other rules
rule r() {
	assert true;
}

// this name will not collide with the name of the sanity rule for `r` or any other rule
rule r_reachability() {
	assert false;
}

rule checkAssertWithSinvoke() {
  env e;
  assert (setB(e) && setB(e));
}
