//! Some rules for i128

use cvlr::nondet::nondet_with;
use cvlr::prelude::*;

// Expected to pass
#[rule]
pub fn test1() {
    let x: i128 = nondet_with(|x| *x == 1);
    cvlr_assert_eq!(x, 1);
    //cvlr_assert!(false);
}

// Expected to pass
#[rule]
pub fn test2() {
    let x: i128 = nondet_with(|x| *x > 5);
    cvlr_satisfy!(x == 100);
}

// Expected to pass
#[rule]
pub fn test3() {
    let x: i128 = nondet_with(|x| *x == -2);
    cvlr_assert_eq!(x, -2);
}

// Expected to pass
#[rule]
pub fn test4() {
    let x: i128 = nondet_with(|x| *x < 0);
    cvlr_satisfy!(x == -2);
}

// Expected to fail
#[rule]
pub fn test5() {
    let x: i128 = nondet_with(|x| *x < 0);
    cvlr_satisfy!(x == 2);
}

// Expected to fail
#[rule]
pub fn test6() {
    let x: i128 = nondet_with(|x| *x > 100i128);
    cvlr_satisfy!(x == -1);
}

// Expected to pass
#[rule]
pub fn test7() {
    let x: i128 = nondet();
    cvlr_satisfy!(x == -1);
}

// Expected to pass
#[rule]
pub fn test8() {
    let x: i128 = nondet();
    cvlr_satisfy!(x == 100);
}
