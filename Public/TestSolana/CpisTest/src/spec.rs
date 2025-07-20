//! This module contains the specification for an example appication which uses
//! CPI calls.

mod processor;

use crate::processor::*;
use cvlr::prelude::*;
use cvlr_solana::{
    cvlr_deserialize_nondet_accounts,
    token::{spl_mint_get_supply, spl_token_account_get_amount},
};
use solana_program::account_info::{next_account_info, AccountInfo};

cvlr_solana::cvlr_solana_init!();

#[rule]
pub fn rule_transfer_token_transfers_same_wallet() {
    let account_infos = cvlr_deserialize_nondet_accounts();
    let account_info_iter = &mut account_infos.iter();
    let token_program: &AccountInfo = next_account_info(account_info_iter).unwrap();
    let from: &AccountInfo = next_account_info(account_info_iter).unwrap();
    let to: &AccountInfo = next_account_info(account_info_iter).unwrap();
    let _authority: &AccountInfo = next_account_info(account_info_iter).unwrap();

    let amount: u64 = nondet();
    let mut token_instruction_data = Vec::new();
    token_instruction_data.extend_from_slice(&amount.to_le_bytes());

    let from_wallet_amount_pre = spl_token_account_get_amount(from);
    let to_wallet_amount_pre = spl_token_account_get_amount(to);

    // Assume from and to are the same account.
    cvlr_assume!(from.key == to.key);
    cvlr_assume!(from_wallet_amount_pre == to_wallet_amount_pre);

    process_transfer_token(&account_infos, &token_instruction_data).unwrap();

    let from_wallet_amount_post = spl_token_account_get_amount(from);
    let to_wallet_amount_post = spl_token_account_get_amount(to);

    cvlr_assert!(*token_program.key == spl_token::id());
    cvlr_assert!(from_wallet_amount_post == from_wallet_amount_pre);
    cvlr_assert!(to_wallet_amount_post == to_wallet_amount_pre);
}

#[rule]
pub fn rule_transfer_token_transfers_different_wallets() {
    let account_infos = cvlr_deserialize_nondet_accounts();
    let account_info_iter = &mut account_infos.iter();
    let token_program: &AccountInfo = next_account_info(account_info_iter).unwrap();
    let from: &AccountInfo = next_account_info(account_info_iter).unwrap();
    let to: &AccountInfo = next_account_info(account_info_iter).unwrap();
    let _authority: &AccountInfo = next_account_info(account_info_iter).unwrap();

    let amount: u64 = nondet();
    let mut token_instruction_data = Vec::new();
    token_instruction_data.extend_from_slice(&amount.to_le_bytes());

    cvlr_assume!(from.key != to.key);

    let from_wallet_amount_pre = spl_token_account_get_amount(from);
    let to_wallet_amount_pre = spl_token_account_get_amount(to);

    process_transfer_token(&account_infos, &token_instruction_data).unwrap();

    let from_wallet_amount_post = spl_token_account_get_amount(from);
    let to_wallet_amount_post = spl_token_account_get_amount(to);

    cvlr_assert!(*token_program.key == spl_token::id());
    cvlr_assert!(from_wallet_amount_post == from_wallet_amount_pre - amount);
    cvlr_assert!(to_wallet_amount_post == to_wallet_amount_pre + amount);
}

#[rule]
pub fn rule_transfer_token_transfers_same_wallet_failing() {
    let account_infos = cvlr_deserialize_nondet_accounts();
    let account_info_iter = &mut account_infos.iter();
    let token_program: &AccountInfo = next_account_info(account_info_iter).unwrap();
    let from: &AccountInfo = next_account_info(account_info_iter).unwrap();
    let to: &AccountInfo = next_account_info(account_info_iter).unwrap();
    let _authority: &AccountInfo = next_account_info(account_info_iter).unwrap();

    let amount: u64 = nondet();
    let mut token_instruction_data = Vec::new();
    token_instruction_data.extend_from_slice(&amount.to_le_bytes());

    let from_wallet_amount_pre = spl_token_account_get_amount(from);
    let to_wallet_amount_pre = spl_token_account_get_amount(to);

    // Assume from and to are the same account.
    cvlr_assume!(from.key == to.key);
    cvlr_assume!(from_wallet_amount_pre == to_wallet_amount_pre);

    process_transfer_token(&account_infos, &token_instruction_data).unwrap();

    let from_wallet_amount_post = spl_token_account_get_amount(from);
    let to_wallet_amount_post = spl_token_account_get_amount(to);

    cvlr_assert!(*token_program.key == spl_token::id());
    // Assert that the amount has changed: this assertion is supposed to fail.
    cvlr_assert!(from_wallet_amount_post == from_wallet_amount_pre - amount);
    cvlr_assert!(to_wallet_amount_post == to_wallet_amount_pre + amount);
}

#[rule]
pub fn rule_mint_token_mints() {
    let account_infos = cvlr_deserialize_nondet_accounts();
    let account_info_iter = &mut account_infos.iter();
    let token_program: &AccountInfo = next_account_info(account_info_iter).unwrap();
    let mint: &AccountInfo = next_account_info(account_info_iter).unwrap();
    let destination: &AccountInfo = next_account_info(account_info_iter).unwrap();
    let _mint_authority: &AccountInfo = next_account_info(account_info_iter).unwrap();

    let amount: u64 = nondet();
    let decimals: u8 = nondet();
    let mut token_instruction_data = Vec::new();
    token_instruction_data.extend_from_slice(&amount.to_le_bytes());
    token_instruction_data.extend_from_slice(&decimals.to_le_bytes());

    let destination_wallet_amount_pre = spl_token_account_get_amount(destination);
    let mint_supply_pre = spl_mint_get_supply(mint);

    process_mint_token(&account_infos, &token_instruction_data).unwrap();

    let destination_wallet_amount_post = spl_token_account_get_amount(destination);
    let mint_supply_post = spl_mint_get_supply(mint);

    cvlr_assert!(*token_program.key == spl_token::id());
    cvlr_assert!(destination_wallet_amount_post == destination_wallet_amount_pre + amount);
    cvlr_assert!(mint_supply_post == mint_supply_pre + amount);
}

#[rule]
pub fn rule_burn_token_burns() {
    let account_infos = cvlr_deserialize_nondet_accounts();
    let account_info_iter = &mut account_infos.iter();
    let token_program: &AccountInfo = next_account_info(account_info_iter).unwrap();
    let source: &AccountInfo = next_account_info(account_info_iter).unwrap();
    let mint: &AccountInfo = next_account_info(account_info_iter).unwrap();
    let _mint_authority: &AccountInfo = next_account_info(account_info_iter).unwrap();

    let amount: u64 = nondet();
    let mut token_instruction_data = Vec::new();
    token_instruction_data.extend_from_slice(&amount.to_le_bytes());

    let source_wallet_amount_pre = spl_token_account_get_amount(source);
    let mint_supply_pre = spl_mint_get_supply(mint);

    process_burn_token(&account_infos, &token_instruction_data).unwrap();

    let source_wallet_amount_post = spl_token_account_get_amount(source);
    let mint_supply_post = spl_mint_get_supply(mint);

    cvlr_assert!(*token_program.key == spl_token::id());
    cvlr_assert!(source_wallet_amount_post == source_wallet_amount_pre - amount);
    cvlr_assert!(mint_supply_post == mint_supply_pre - amount);
}

#[rule]
pub fn rule_close_account_balance_is_zero_after_success() {
    let account_infos = cvlr_deserialize_nondet_accounts();
    let account_info_iter = &mut account_infos.iter();
    let token_program: &AccountInfo = next_account_info(account_info_iter).unwrap();
    let account: &AccountInfo = next_account_info(account_info_iter).unwrap();
    let _dest: &AccountInfo = next_account_info(account_info_iter).unwrap();
    let _owner: &AccountInfo = next_account_info(account_info_iter).unwrap();

    let token_instruction_data = Vec::new();

    process_close_account(&account_infos, &token_instruction_data).unwrap();

    let account_wallet_amount_post = spl_token_account_get_amount(account);

    cvlr_assert!(*token_program.key == spl_token::id());
    cvlr_assert!(account_wallet_amount_post == 0);
}
