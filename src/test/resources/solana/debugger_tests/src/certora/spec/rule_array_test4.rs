use cvlr::nondet::Nondet;
use cvlr::prelude::*;
use cvlr_solana::cvlr_nondet_account_info;
use solana_program::account_info::AccountInfo;
use solana_program::pubkey::Pubkey;

struct Foo{
    el1: u64,
    el2: u64
}

impl Nondet for Foo{
    fn nondet() -> Self {
        Self { el1: nondet(), el2: nondet() }
    }
}

#[rule]
pub fn rule_array_test4() {
    let two_foos: &[Foo; 2] =
        &Box::new([Foo{el1: 10, el2: 8}, Foo{el1: 10, el2: nondet()}]);
    let foo_zero = &two_foos[0];
    let foo_one = &two_foos[1];
    cvlr_assert!(foo_zero.el1 == foo_one.el1
                    && foo_zero.el2 != foo_one.el2);
}
