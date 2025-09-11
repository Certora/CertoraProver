//! Some rules for rent computation

use cvlr::{is_u64, prelude::*};
use cvlr_solana::cvlr_deserialize_nondet_accounts;
use solana_program::sysvar::rent::Rent;
use solana_program::sysvar::Sysvar;

/// minimum_balance on non-zero data_len must be non-zero
/// Expected to be verified
#[rule]
pub fn rent_minimum_balance_nonzero() {
    let accounts = cvlr_deserialize_nondet_accounts();
    let rent_info = &accounts[0];

    let rent = Rent::from_account_info(rent_info).unwrap();
    cvlr_assume!(is_u64(rent.lamports_per_byte_year));
    cvlr_assume!(rent.lamports_per_byte_year > 0);
    cvlr_assume!(rent.exemption_threshold == 2f64);

    let data_len: u64 = nondet();
    cvlr_assume!(data_len > 0);
    let result = rent.minimum_balance(data_len as usize);
    cvlr_assert!(result > 0);
}

/// Similar to `rent_minimum_balance_nonzero` but without adding assumptions about `Rent` fields.
/// Expected to be verified.
#[rule]
pub fn rent_minimum_balance_nonzero2() {
    let accounts = cvlr_deserialize_nondet_accounts();
    let rent_info = &accounts[0];

    let rent = Rent::from_account_info(rent_info).unwrap();
    cvlr_assume!(rent == Rent::get().unwrap());

    let data_len: u64 = nondet();
    cvlr_assume!(data_len > 0);
    let result = rent.minimum_balance(data_len as usize);
    cvlr_assert!(result > 0);
}

/// Similar to `rent_minimum_balance_nonzero` but missing assumption about `exemption_threshold`
/// Expected to be violated
#[rule]
pub fn rent_minimum_balance_nonzero3() {
    let accounts = cvlr_deserialize_nondet_accounts();
    let rent_info = &accounts[0];

    let rent = Rent::from_account_info(rent_info).unwrap();
    cvlr_assume!(is_u64(rent.lamports_per_byte_year));
    cvlr_assume!(rent.lamports_per_byte_year > 0);
    //cvlr_assume!(rent.exemption_threshold == 2f64);

    let data_len: u64 = nondet();
    cvlr_assume!(data_len > 0);
    let result = rent.minimum_balance(data_len as usize);
    cvlr_assert!(result > 0);
}
