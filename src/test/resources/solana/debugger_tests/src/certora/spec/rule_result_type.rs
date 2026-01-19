use std::{error::{self, Error}};

use cvlr::prelude::*;

#[rule]
pub fn rule_result_type() {
    let input = nondet();
    let result = method_returning_result_type(input);
    cvlr_assert!(result.is_ok());
}

#[inline(never)]
fn method_returning_result_type(input: i32) -> Result<i32, u64> {
    if input < 10 {
        Ok(input * 10)
    } else {
        Err(42)
    }
}