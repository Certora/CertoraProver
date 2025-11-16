//! Rule for correctness of some ceil_div implementation

use cvlr::{mathint::NativeInt, prelude::*};

#[inline(never)]
fn new_ceil_div(lhs: u128, rhs: u128) -> Option<u128> {
     let quotient = lhs.checked_div(rhs)?;
     let remainder = lhs.checked_rem(rhs)?;

     // Ceiling division: adjust quotient upward if remainder exists and has same sign as divisor
     if remainder != 0 && (remainder > 0) == (rhs > 0) {
          quotient.checked_add(1)
     } else {
          Some(quotient)
     }
}

#[inline(never)]
fn native_ceil_div(lhs: u128, rhs: u128) -> Option<u128> {
    let x = NativeInt::from(lhs);
    cvlr_assume!(rhs > 0);
    let y = NativeInt::from(rhs);
    Some(x.div_ceil(y).into())
}

/// Prove equivalence between new_ceil_div and NativeInt ceil_div
/// The proof requires that the Solana frontend can recognize this pattern
///
/// ```
///  r6 = r6 + 1
///  r1 = select(r6 == 0, 1, 0)
///  r7 = r7 + r1
/// ```
///  which adds 1 to r7 if the computation r6+1 overflows
///
/// Expected to be verified
#[rule]
pub fn ceil_div_equivalence() {
    let a: u128 = nondet();
    let b: u128 = nondet();

    clog!(a, b);
    let c = new_ceil_div(a, b).unwrap();
    clog!(c);
    let d =  native_ceil_div(a, b).unwrap();

    clog!(a, b, c, d);
    cvlr_assert_eq!(c, d);
}
