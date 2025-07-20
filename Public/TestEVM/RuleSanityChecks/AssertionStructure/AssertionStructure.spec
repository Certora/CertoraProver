methods {
    function counter() external returns (uint256) envfree;
    function incrementAndGet() external returns (uint256) envfree;
}

/*
 * Test that the assertion structure checks can identify an implication. For an
 * assert p => q, the assertion structure check generator should create a rule with
 * assert !p. If p is always false then the implication is always true.
 */
rule p_implies_q_false_hypothesis() {
    uint256 a;
    assert a < 0 => counter() != 0, "p does not imply q";
}

/*
 * Test that the assertion structure checks can identify an implication. For an
 * assert p => q, the assertion structure check generator should create a rule with
 * assert q to check that the conclusion of the hypothesis is not always true
 * regardless of the hypothesis.
 */
rule p_implies_q_true_conclusion() {
    uint256 a;
    assert counter() > 0 => a >= 0, "p does not imply q";
}

/*
 * p => !p is a tautology due to the if condition.
 */
rule vacuous_implication(bool p) {
    if (!p) {
        assert p => !p;
    }
    require p;
    assert p;
}

/*
 * After proving the first assert, the second becomes a tautology.
 */
rule first_tautologize_second() {
    require counter() < 10;
    assert incrementAndGet() > 0;
    assert (counter() == 0) => false;
}

/*
 * Test that the assertion structure checks can identify a double implication. For
 * a double implication, assert p <=> q, the assertion structure check generator should
 * create a rule with assert !p && !q.
 */
rule double_implication_both_false() {
    uint256 a;
    assert a < 0 <=> counter() < 0, "p and q always false";
}

/*
 * Test that the assertion structure checks can identify a double implication. For
 * a double implication, assert p <=> q, the assertion structure check generator should
 * create a rule with assert p && q.
 */
rule double_implication_both_true() {
    uint256 a;
    assert a >= 0 <=> counter() >= 0, "p and q always true";
}

rule double_implication_pass(bool a, bool b) {
    require true;
    require a == b;
    assert a <=> b;
}

/*
 * Test that the assertion structure checks can identify a disjunction. For a disjunction
 * assert p || q, the assertion structure check generator should create a rule
 * to test assert p.
 */
rule disjunction_left() {
    uint256 a = 10;
    assert a >= 10 || counter() < 10, "disjunction left always true";
}

/*
 * Test that the assertion structure checks can identify a disjunction. For a disjunction
 * assert p || q, the assertion structure check generator should create a rule
 * to test assert q.
 */
rule disjunction_right() {
    uint256 b = 10;
    assert counter() < 10 || b >= 10, "disjunction right always true";
}
