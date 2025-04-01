#![no_std]
use soroban_sdk::*;

mod certora;

fn check_nonnegative_amount(amount: i64) {
    if amount < 0 {
        panic!("negative amount is not allowed: {}", amount)
    }
}

pub fn read_balance(e: &Env, addr: &Address) -> i64 {
    e.storage().persistent().get(&addr).unwrap_or(0)
}

fn write_balance(e: &Env, addr: Address, amount: i64) {
    e.storage().persistent().set(&addr, &amount);
}

pub fn receive_balance(e: &Env, addr: Address, amount: i64) {
    let balance = read_balance(e, &addr);
    write_balance(e, addr, balance + amount);
}

pub fn spend_balance(e: &Env, addr: Address, amount: i64) {
    let balance = read_balance(e, &addr);
    if balance < amount {
        panic!("insufficient balance");
    }
    write_balance(e, addr, balance - amount);
}

#[contract]
pub struct Token;

#[contractimpl]
impl Token {
    pub fn initialize(e: Env, admin: Address) {
        e.storage().persistent().set(&"ADMIN", &admin);
    }

    pub fn transfer(e: &Env, from: Address, to: Address, amount: i64) {
        from.require_auth();
        check_nonnegative_amount(amount);
        spend_balance(&e, from.clone(), amount + 1);
        receive_balance(&e, to.clone(), amount);
    }

    pub fn mint(e: &Env, to: Address, amount: i64) {
        check_nonnegative_amount(amount);
        let admin = e
            .storage()
            .persistent()
            .get::<_, Address>(&"ADMIN")
            .unwrap();
        admin.require_auth();
        receive_balance(&e, to.clone(), amount);
    }

    pub fn burn(e: Env, from: Address, amount: i64) {
        from.require_auth();
        check_nonnegative_amount(amount);
        spend_balance(&e, from.clone(), amount);
    }

    pub fn balance(env: &Env, addr: Address) -> i64 {
        read_balance(env, &addr)
    }
}