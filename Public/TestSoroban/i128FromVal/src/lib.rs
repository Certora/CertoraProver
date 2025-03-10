#![no_std]
use soroban_env_guest::{num, Tag};
use cvlr_soroban_derive::rule;
use soroban_sdk::{Env, Val, TryFromVal};

#[rule]
pub fn test_to_from_i128_conditions(e: &Env, v: Val) {
    match i128::try_from_val(e, &v) {
        Result::Err(_) => {
            cvlr::cvlr_assert!(v.get_tag() != Tag::I128Small && v.get_tag() != Tag::I128Object);
        }

        Result::Ok(_) => {
            cvlr::cvlr_assert!(v.get_tag() == Tag::I128Small || v.get_tag() == Tag::I128Object);
        }
    }
}

#[rule]
pub fn test_to_from_i128_reach_error(e: &Env, v: Val) {
    match i128::try_from_val(e, &v) {
        Result::Err(_) => {
            cvlr::cvlr_satisfy!(true);
        }

        _ => {}
    }
}

#[rule]
pub fn test_to_from_i128_reach_small(e: &Env, v: Val) {
    match i128::try_from_val(e, &v) {
        Result::Ok(_) => {
            cvlr::cvlr_satisfy!(v.get_tag() == Tag::I128Small);
        }

        _ => {}
    }
}

#[rule]
pub fn test_to_from_i128_small_range(e: &Env, v: Val) {
    match i128::try_from_val(e, &v) {
        Result::Ok(i) => {
            cvlr::cvlr_assert!(v.get_tag() != Tag::I128Small || num::is_small_i128(i));
        }

        _ => {}
    }
}

#[rule]
pub fn test_i128_to_val_tags(e: &Env, i: i128) {
    let v = Val::try_from_val(e, &i);
    cvlr::cvlr_assert!(v.is_ok());
    if num::is_small_i128(i) {
        cvlr::cvlr_assert!(v.unwrap().get_tag() == Tag::I128Small);
    } else {
        cvlr::cvlr_assert!(v.unwrap().get_tag() == Tag::I128Object);
    }
}

#[rule]
pub fn test_i128_to_val_roundtrip(e: &Env, i: i128) {
    let v = Val::try_from_val(e, &i);
    cvlr::cvlr_assert!(v.is_ok());
    let j = i128::try_from_val(e, &v.unwrap());
    cvlr::cvlr_assert!(j.is_ok());
    cvlr::cvlr_assert!(i == j.unwrap());
}
#[rule]
pub fn reach_val_zero(e: &Env, v: Val) {
    let i1 = i128::try_from_val(e, &v).unwrap();
    cvlr::cvlr_satisfy!(i1 == 0);
}
#[rule]
pub fn reach_val_small_negative(e: &Env, v: Val) {
    let i1 = i128::try_from_val(e, &v).unwrap();
    cvlr::cvlr_satisfy!(i1 < 0 && num::is_small_i128(i1));
}
#[rule]
pub fn reach_val_negative(e: &Env, v: Val) {
    let i1 = i128::try_from_val(e, &v).unwrap();
    cvlr::cvlr_satisfy!(i1 < 0 && !num::is_small_i128(i1));
}
#[rule]
pub fn reach_val_small_positive(e: &Env, v: Val) {
    let i1 = i128::try_from_val(e, &v).unwrap();
    cvlr::cvlr_satisfy!(i1 > 0 && num::is_small_i128(i1));
}
#[rule]
pub fn reach_val_positive(e: &Env, v: Val) {
    let i1 = i128::try_from_val(e, &v).unwrap();
    cvlr::cvlr_satisfy!(i1 > 0 && !num::is_small_i128(i1));
}
#[rule]
pub fn reach_vals_equal(e: &Env, v1: Val, v2: Val) {
    let i1 = i128::try_from_val(e, &v1).unwrap();
    let i2 = i128::try_from_val(e, &v2).unwrap();
    cvlr::cvlr_satisfy!(i1 == i2);
}
#[rule]
pub fn reach_vals_not_equal(e: &Env, v1: Val, v2: Val) {
    let i1 = i128::try_from_val(e, &v1).unwrap();
    let i2 = i128::try_from_val(e, &v2).unwrap();
    cvlr::cvlr_satisfy!(i1 != i2);
}
