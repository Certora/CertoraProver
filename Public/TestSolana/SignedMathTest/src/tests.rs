//! Some rules for signed math

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

// checked_mul is translated to __muloti4
#[rule]
pub fn test9(){
    let supply_amount: i128 = nondet_with(|x| *x == 1);
    let multiplier: i128 = 10;

    let result = supply_amount.checked_mul(multiplier).unwrap();
    cvlr_assert_eq!(supply_amount,1);
    cvlr_assert_eq!(result,10);
}

// Expected to pass
#[rule]
pub fn test10(){
    let x: i128 = nondet_with(|x| *x >= 0);
    let y = x.abs();
    cvlr_assert_eq!(x, y);
}

// Expected to pass
#[rule]
pub fn test11(){
    let x: i128 = nondet_with(|x| *x < 0 && *x > i128::MIN);
    let y = x.abs();
    let z = y.checked_mul(-1).unwrap();
    cvlr_assert!(x == z);
}

// Expected to pass
#[rule]
pub fn test12(){
    let x: i128 = nondet_with(|x| *x == -100i128);
    let y = x.abs();
    cvlr_assert!(y == 100i128);
}

// Expected to pass
#[rule]
pub fn test13(){
    let x: i128 = nondet_with(|x| *x == 100i128);
    let y = x.abs();
    cvlr_assert!(y == 100i128);
}


// Expected to pass
// modular negation is removed by the compiler
/*#[rule]
pub fn test15(){
    let x: i128 = nondet();
    cvlr_assert!(x.abs() >= 0);
}*/
