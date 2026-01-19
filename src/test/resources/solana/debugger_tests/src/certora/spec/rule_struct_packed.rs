
use cvlr::{nondet::Nondet, prelude::*};
#[rule]
pub fn rule_struct_packed() {
    let input_a = nondet();
    let input_b = derive_middle_struct(&input_a);
    cvlr_assert!(input_a.field1 == input_b.field1 &&
        input_a.field2 == input_b.field2 &&
        input_a.field3 != input_b.field3 // fails only here!
    );
}

#[inline(never)]
fn derive_middle_struct(parameter_variable: &PackedStruct) -> PackedStruct{
    PackedStruct{
        field3: nondet(),
        ..parameter_variable.clone()
    }
}


#[derive(Debug, Clone)]
pub struct PackedStruct{
    pub field1: u32,
    pub field2: u64,
    pub field3: u32,
}

impl Nondet for PackedStruct{
    fn nondet() -> Self {
        PackedStruct {
            field1: 9, field2: 7, field3: nondet()}
    }
}
