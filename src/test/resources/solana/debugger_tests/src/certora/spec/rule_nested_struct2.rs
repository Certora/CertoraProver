use cvlr::prelude::*;

use crate::certora::spec::structs::Foo;

#[rule]
pub fn rule_nested_struct_test2() {
    let mut struct_a2: Foo = nondet();
    let struct_b2: Foo = nondet();
    let which_branch = if struct_b2.bar.owner > 10 {
        struct_a2.key = 2;
        1
    } else {
        struct_a2.bar.owner = 10;
        2
    };
    cvlr_assert!(which_branch >= 1 && struct_a2.bar.owner == struct_b2.key);
}
