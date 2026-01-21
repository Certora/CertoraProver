use cvlr::prelude::*;
use cvlr_solana::cvlr_nondet_account_info;

#[rule]
pub fn rule_single_account_as_heap() {
    let my_account  = Box::new(cvlr_nondet_account_info());
    let key = my_account.key;
    let owner = my_account.owner;
    cvlr_assert!(key != owner);
}
