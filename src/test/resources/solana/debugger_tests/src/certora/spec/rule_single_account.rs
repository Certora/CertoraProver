use cvlr::prelude::*;
use cvlr_solana::cvlr_nondet_account_info;

#[rule]
pub fn rule_single_account() {
    let my_account  = cvlr_nondet_account_info();
    let key = my_account.key;
    let owner = my_account.owner;
    cvlr_assert!(key != owner);
}
