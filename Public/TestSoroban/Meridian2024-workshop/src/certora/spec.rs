#![no_std]

use certora_soroban_macros::{declare_rules, rule};
use soroban_sdk::{Address, Env};

use crate::Token;
use certora::*;
use certora_soroban::{certora_print_i64, CERTORA_calltrace_print_c_i64, is_auth};
use crate::check_nonnegative_amount;

// Sunbeam specs
#[rule]
fn sanity(e: Env, amount: i64) {
    certora::require!(amount < 0, "amount < 0");
    check_nonnegative_amount(amount);
    certora::assert!(false);
}
