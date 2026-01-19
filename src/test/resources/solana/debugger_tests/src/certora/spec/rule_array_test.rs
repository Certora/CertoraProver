use cvlr::prelude::*;
use cvlr_solana::cvlr_nondet_account_info;

#[rule]
pub fn rule_array_test() {
    use solana_program::account_info::AccountInfo;
    let remaining_accounts: &[AccountInfo<'static>; 2] =
        &[cvlr_nondet_account_info(), cvlr_nondet_account_info()];
    let element_zero = &remaining_accounts[0];
    let element_one = &remaining_accounts[1];
    cvlr_assert!(element_zero.key != element_one.key);
}
