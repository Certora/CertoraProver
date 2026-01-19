use cvlr::prelude::*;
use cvlr_solana::{cvlr_nondet_acc_infos};
use solana_program::pubkey::Pubkey;

#[rule]
pub fn rule_nondet_account_array() {
    let account_infos = cvlr_nondet_acc_infos();
    let key1 = account_infos[0].key;
    let key2 = account_infos[1].key;
    foo(&key1, &key2);
    cvlr_assert!(key1 != key2);
}

#[inline(never)]
fn foo(ref_key1: &Pubkey, ref_key2: &Pubkey) -> usize{
    return ref_key1.to_string().len() + ref_key2.to_string().len()
}