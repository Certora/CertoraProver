module vacuity::vacuity;

use cvlm::manifest::{ rule, target, target_sanity };
use cvlm::asserts::{ cvlm_assert, cvlm_satisfy };
use cvlm::internal_asserts::cvlm_internal_assume;

public fun cvlm_manifest() {
    rule(b"assert_not_vacuous");
    rule(b"assert_vacuous");
    rule(b"satisfy_not_vacuous");
    rule(b"satisfy_vacuous");

    target(@vacuity, b"vacuity", b"target_not_vacuous");
    target(@vacuity, b"vacuity", b"target_vacuous");
    target_sanity();
}

public fun assert_not_vacuous(n: u64, m: u64) {
    cvlm_internal_assume(n <= m);
    cvlm_internal_assume(n >= m);
    cvlm_assert(n == m);
    cvlm_assert(m == n);
}

public fun assert_vacuous(n: u64, m: u64) {
    cvlm_internal_assume(n < m);
    cvlm_internal_assume(n > m);
    cvlm_assert(n == m);
    cvlm_assert(m == n);
}

public fun satisfy_not_vacuous(n: u64, m: u64) {
    cvlm_internal_assume(n <= m);
    cvlm_internal_assume(n >= m);
    cvlm_satisfy(n != m);
}

public fun satisfy_vacuous(n: u64, m: u64) {
    cvlm_internal_assume(n < m);
    cvlm_internal_assume(n > m);
    cvlm_assert(n != m);
}

public fun target_not_vacuous(n: u64, m: u64) {
    cvlm_internal_assume(n <= m);
    cvlm_internal_assume(n >= m);
}

public fun target_vacuous(n: u64, m: u64) {
    cvlm_internal_assume(n < m);
    cvlm_internal_assume(n > m);
}
