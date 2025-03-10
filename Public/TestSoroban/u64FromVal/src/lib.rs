#![no_std]
use soroban_env_guest::{num, Tag};
use cvlr_soroban_derive::rule;
use soroban_sdk::{Env, Val, TryFromVal};

#[rule]
pub fn test_to_from_u64_conditions(e: &Env, v: Val) {
    match u64::try_from_val(e, &v) {
        Result::Err(_) => {
            cvlr::cvlr_assert!(v.get_tag() != Tag::U64Small && v.get_tag() != Tag::U64Object);
        }

        Result::Ok(_) => {
            cvlr::cvlr_assert!(v.get_tag() == Tag::U64Small || v.get_tag() == Tag::U64Object);
        }
    }
}

#[rule]
pub fn test_to_from_u64_reach_error(e: &Env, v: Val) {
    match u64::try_from_val(e, &v) {
        Result::Err(_) => {
            cvlr::cvlr_satisfy!(true);
        }

        _ => {}
    }
}

#[rule]
pub fn test_to_from_u64_reach_small(e: &Env, v: Val) {
    match u64::try_from_val(e, &v) {
        Result::Ok(_) => {
            cvlr::cvlr_satisfy!(v.get_tag() == Tag::U64Small);
        }

        _ => {}
    }
}

#[rule]
pub fn test_to_from_u64_small_range(e: &Env, v: Val) {
    match u64::try_from_val(e, &v) {
        Result::Ok(i) => {
            cvlr::cvlr_assert!(v.get_tag() != Tag::U64Small || num::is_small_u64(i));
        }

        _ => {}
    }
}

#[rule]
pub fn test_u64_to_val_tags(e: &Env, i: u64) {
    let v = Val::try_from_val(e, &i);
    cvlr::cvlr_assert!(v.is_ok());
    if num::is_small_u64(i) {
        cvlr::cvlr_assert!(v.unwrap().get_tag() == Tag::U64Small);
    } else {
        cvlr::cvlr_assert!(v.unwrap().get_tag() == Tag::U64Object);
    }
}

#[rule]
pub fn test_u64_to_val_roundtrip(e: &Env, i: u64) {
    let v = Val::try_from_val(e, &i);
    cvlr::cvlr_assert!(v.is_ok());
    let j = u64::try_from_val(e, &v.unwrap());
    cvlr::cvlr_assert!(j.is_ok());
    cvlr::cvlr_assert!(i == j.unwrap());
}
#[rule]
pub fn reach_val_zero(e: &Env, v: Val) {
    let i1 = u64::try_from_val(e, &v).unwrap();
    cvlr::cvlr_satisfy!(i1 == 0);
}
#[rule]
pub fn reach_val_small_positive(e: &Env, v: Val) {
    let i1 = u64::try_from_val(e, &v).unwrap();
    cvlr::cvlr_satisfy!(i1 > 0 && num::is_small_u64(i1));
}
#[rule]
pub fn reach_val_positive(e: &Env, v: Val) {
    let i1 = u64::try_from_val(e, &v).unwrap();
    cvlr::cvlr_satisfy!(i1 > 0 && !num::is_small_u64(i1));
}
#[rule]
pub fn reach_vals_equal(e: &Env, v1: Val, v2: Val) {
    let i1 = u64::try_from_val(e, &v1).unwrap();
    let i2 = u64::try_from_val(e, &v2).unwrap();
    cvlr::cvlr_satisfy!(i1 == i2);
}
#[rule]
pub fn reach_vals_not_equal(e: &Env, v1: Val, v2: Val) {
    let i1 = u64::try_from_val(e, &v1).unwrap();
    let i2 = u64::try_from_val(e, &v2).unwrap();
    cvlr::cvlr_satisfy!(i1 != i2);
}
