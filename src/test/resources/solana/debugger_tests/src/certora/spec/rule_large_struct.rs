
use cvlr::prelude::*;
use crate::certora::spec::structs::LargeStruct;
#[rule]
pub fn rule_large_struct() {
    let input_a: LargeStruct = nondet();
    let input_b: LargeStruct = derive_large_struct(&input_a);
    cvlr_assert!(input_a.a == input_b.a &&
        input_a.b == input_b.b &&
        input_a.c == input_b.c &&
        input_a.d == input_b.d &&
        input_a.e == input_b.e &&
        input_a.f == input_b.f &&
        input_a.g == input_b.g &&
        input_a.h != input_b.h // fails only here!
    );
}

#[inline(never)]
fn derive_large_struct(input_a: &LargeStruct) -> LargeStruct{
    LargeStruct{
        h: nondet(),
        ..input_a.clone()
    }
}