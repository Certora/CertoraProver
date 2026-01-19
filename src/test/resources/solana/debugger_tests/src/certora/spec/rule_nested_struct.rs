use cvlr::prelude::*;

use crate::certora::spec::structs::Foo;

#[rule]
pub fn rule_nested_struct_test() {
    let struct_a: Foo = nondet();
    let struct_b: Foo = nondet();
    cvlr_assert!(struct_a.bar.owner == struct_b.key);
}
