//! Some rules for Rust u128

use cvlr::prelude::*;

#[rule]
pub fn rule_shift_left() {
    let x: u128 = nondet();
    let s: u64 = nondet();
    cvlr_assume!(x <= 1000);
    cvlr_assume!(s <= 93);
    let res = x << s;
    cvlr::clog!(x, s, res);
    cvlr_assert!(res <= 99_035_203_142_830_420_000_000_000_000_000u128);
}

#[rule]
pub fn rule_shift_right() {
    let x: u128 = nondet();
    let s: u64 = nondet();
    cvlr_assume!(x <= 99_035_203_142_830_420_000_000_000_000_000u128);
    cvlr_assume!(s >= 30 && s < 128);
    let res = x >> s;
    cvlr::clog!(x, s, res);
    cvlr_assert!(res <= 92_233_720_368_547_760_000_000u128);
}
