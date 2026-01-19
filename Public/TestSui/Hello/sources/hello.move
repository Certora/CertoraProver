module hello::hello;

use cvlm::manifest::rule;
use cvlm::asserts::{ cvlm_assert, cvlm_satisfy };

public fun cvlm_manifest() {
    rule(b"hello_world");
    rule(b"hello_world2");
}

public fun hello_world() {
    cvlm_satisfy(true);
}

public fun hello_world2() {
    cvlm_assert(false);
}
