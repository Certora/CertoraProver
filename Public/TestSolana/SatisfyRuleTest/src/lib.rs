//! Rules that mix an assertion of the user with a satisfy, and rules that exercise the vacuity check.

use cvlr::prelude::*;

/// A satisfy rule may contain an assertion of the user.  The assertion is assumed, so what the rule checks is
/// whether the satisfy is reachable in a run that does not violate it.
#[rule]
pub fn rule_satisfy_with_assert() {
    let x: u64 = nondet();
    cvlr_assume!(x > 5);
    cvlr_assert!(x < 100);
    cvlr_satisfy!(x == 50);
}

/// The assertion of a satisfy rule is assumed, so it can make the satisfy unreachable.
#[rule]
pub fn rule_satisfy_unreachable_by_assert() {
    let x: u64 = nondet();
    cvlr_assert!(x != 50);
    cvlr_satisfy!(x == 50);
}

/// A rule that asserts and is reachable: both the rule and its vacuity check pass.
#[rule]
pub fn rule_assert_passes_vacuity() {
    let x: u64 = nondet();
    cvlr_assume!(x > 5);
    cvlr_assert!(x > 0);
}

/// A rule whose assumptions cannot hold: the vacuity check fails.
#[rule]
pub fn rule_assert_fails_vacuity() {
    let x: u64 = nondet();
    cvlr_assume!(x > 5);
    cvlr_assume!(x < 5);
    cvlr_assert!(x > 0);
}
