module test::multi;

use cvlm::manifest::rule;
use cvlm::asserts::cvlm_assert;

public fun cvlm_manifest() {
    rule(b"multi");
}

public fun multi(a: u64, b: u64) {
    cvlm_assert(a == 5);
    cvlm_assert(b == 5);
    cvlm_assert(a == b);
}
