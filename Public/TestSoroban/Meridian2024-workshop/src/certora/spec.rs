#![no_std]

use cvlr_soroban_derive::rule;
use cvlr::{cvlr_assert, cvlr_assume};
use soroban_sdk::{Address, Env};

use crate::Token;
use crate::check_nonnegative_amount;

// Sunbeam specs
#[rule]
fn sanity(e: Env, amount: i64) {
    cvlr_assume!(amount < 0, "amount < 0");
    check_nonnegative_amount(amount);
    cvlr_assert!(false);
}
