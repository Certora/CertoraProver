use crate::certora::spec::structs::Bar;
use crate::certora::spec::structs::Foo;
use cvlr::{nondet::Nondet, prelude::*};
#[rule]
pub fn rule_struct_passing() {
    let my_foo = returns_foo();
    let derived_foo = swap_fields(my_foo);
    let derived_foo_key = derived_foo.key;
    let derived_foo_owner = derived_foo.bar.owner;
    cvlr_assert!(derived_foo_key != derived_foo_owner);
}

fn returns_foo() -> Foo {
    return Foo::nondet();
}

fn swap_fields(foo: Foo) -> Foo {
    return Foo {
        key: foo.bar.owner,
        bar: Bar { owner: foo.key },
    };
}
