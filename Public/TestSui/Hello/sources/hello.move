module hello::hello;

use cvlm::manifest::rule;
use cvlm::asserts::cvlm_satisfy;

public fun cvlm_manifest() {
    rule(b"hello_world");
}

public fun hello_world() {
    cvlm_satisfy(true);
}
