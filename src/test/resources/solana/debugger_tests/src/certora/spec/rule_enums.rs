use cvlr::{nondet::Nondet, prelude::*};

#[derive(PartialEq)]
pub enum SomeEnum{
    Foo,
    Bar
}

#[rule]
pub fn rule_enums() {
    let nondet_enum: SomeEnum = nondet();
    cvlr_assert!(nondet_enum == SomeEnum::Bar);
}

impl Nondet for SomeEnum{
    fn nondet() -> Self {
        match nondet::<u8>(){
            0 => SomeEnum::Bar,
            _ => SomeEnum::Foo
        }
    }
}