use cvlr::prelude::*;
use cvlr_solana::cvlr_nondet_account_info;
use solana_program::account_info::AccountInfo;
use solana_program::pubkey::Pubkey;

#[rule]
pub fn rule_array_test2() {
    let remaining_accounts: &[AccountInfo<'static>; 2] =
        &[cvlr_nondet_account_info(), cvlr_nondet_account_info()];
    let element_zero = &remaining_accounts[0];
    let element_one = &remaining_accounts[1];
    let key = get_key(element_one);
    let owner = get_owner(element_zero);
    cvlr_assert!(element_zero.key != key && element_one.owner != owner);
}

#[inline(never)]
pub fn get_key<'a>(acc: &AccountInfo<'a>) -> &'a Pubkey {
    acc.key
}

#[inline(never)]
pub fn get_owner<'a>(acc: &AccountInfo<'a>) -> &'a Pubkey {
    acc.owner
}
