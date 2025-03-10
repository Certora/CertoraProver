#![no_std]
use soroban_sdk::*;
use cvlr::*;
use cvlr_soroban::*;
use cvlr::nondet::*;
use cvlr_soroban_derive::rule;
use paste::paste;

#[contracttype]
#[derive(PartialEq, Eq)]
struct T1 {
    x: u64
}

#[contracttype]
#[derive(PartialEq, Eq)]
struct T2 {
    x: u64,
    y: u64
}

#[contracttype]
#[derive(PartialEq, Eq)]
struct T3 {
    x: u64,
    y: u64,
    z: Address,
}

#[contracttype]
#[derive(PartialEq, Eq)]
struct T4 {
    x: T1,
    y: T2,
}

#[contracttype]
#[derive(PartialEq, Eq)]
enum E1 {
    A, B, C
}

#[contracttype]
#[derive(PartialEq, Eq)]
enum E2 {
    A(Address), B, C
}

#[contracttype]
#[derive(PartialEq, Eq)]
enum E3 {
    A(Address), B(u64, u64), C(i128)
}

#[contracttype]
#[derive(PartialEq, Eq)]
enum E4 {
    A(Address), B(T1), C(T4)
}

impl Nondet for T1 {
    fn nondet() -> Self {
        Self { x: nondet() }
    }
}

impl Nondet for T2 {
    fn nondet() -> Self {
        Self { x: nondet(), y: nondet() }
    }
}

impl Nondet for T3 {
    fn nondet() -> Self {
        Self { x: nondet(), y: nondet(), z: nondet_address() }
    }
}

impl Nondet for T4 {
    fn nondet() -> Self {
        Self { x: nondet(), y: nondet() }
    }
}

impl Nondet for E1 {
    fn nondet() -> Self {
        match u32::nondet() {
            0 => Self::A,
            1 => Self::C,
            _ => Self::B,
        }
    }
}

impl Nondet for E2 {
    fn nondet() -> Self {
        match u32::nondet() {
            0 => Self::A(nondet_address()),
            1 => Self::B,
            _ => Self::C,
        }
    }
}

impl Nondet for E3 {
    fn nondet() -> Self {
        match u32::nondet() {
            0 => Self::A(nondet_address()),
            1 => Self::B(nondet(), nondet()),
            _ => Self::C(nondet()),
        }
    }
}

impl Nondet for E4 {
    fn nondet() -> Self {
        match u32::nondet() {
            0 => Self::A(nondet_address()),
            1 => Self::B(nondet()),
            _ => Self::C(nondet()),
        }
    }
}

fn test_key_simple<T>(e: &Env, key1: &T, key2: &T)
where
    T: Eq + IntoVal<Env, Val>
{
    cvlr_assert!(e.storage().persistent().has(key1) == e.storage().persistent().has(key1));

    cvlr_assert!(key1 != key2 || e.storage().persistent().has(key1) == e.storage().persistent().has(key2));
}

fn test_key_simple_sanity<T>(e: &Env, key1: &T, key2: &T)
where
    T: Eq + IntoVal<Env, Val>
{
    let _ = e.storage().persistent().has(key1) == e.storage().persistent().has(key1);

    cvlr_satisfy!(true);
}

fn test_set_simple<K, V>(e: &Env, key: &K, val: V)
where
    K: Eq + IntoVal<Env, Val>,
    V: Eq + TryFromVal<Env, Val>,
    Val: TryFromVal<Env, V>
{
    e.storage().persistent().set(key, &val);
    let v = e.storage().persistent().get::<_,V>(key);
    cvlr_assert!(v.is_some());
    cvlr_assert!(v.unwrap() == val);
}


fn test_set_simple_sanity<K, V>(e: &Env, key: &K, val: V)
where
    K: Eq + IntoVal<Env, Val>,
    V: Eq + TryFromVal<Env, Val>,
    Val: TryFromVal<Env, V>
{
    e.storage().persistent().set(key, &val);
    let v = e.storage().persistent().get::<_,V>(key);
    cvlr_satisfy!(true);
}

fn test_nondet<T>(e: &Env)
    where T: Eq + Nondet + IntoVal<Env, Val>
{
    test_key_simple(e, &T::nondet(), &T::nondet());
}

fn test_nondet_sanity<T>(e: &Env)
    where T: Eq + Nondet + IntoVal<Env, Val>
{
    test_key_simple_sanity(e, &T::nondet(), &T::nondet());
}

fn test_nondet_set<K,V>(e: &Env)
    where K: Eq + Nondet + IntoVal<Env, Val>,
          V: Eq + Nondet + TryFromVal<Env, Val>,
          Val: TryFromVal<Env, V>
{
    test_set_simple(e, &K::nondet(), V::nondet());
}

fn test_nondet_set_sanity<K,V>(e: &Env)
    where K: Eq + Nondet + IntoVal<Env, Val>,
          V: Eq + Nondet + TryFromVal<Env, Val>,
          Val: TryFromVal<Env, V>
{
    test_set_simple_sanity(e, &K::nondet(), V::nondet());
}

macro_rules! key_test {
    ($t:ty) => {
        paste! {
          #[rule]
          pub fn [<test_ $t>](e: &Env) { test_nondet::<$t>(e); }

          #[rule]
          pub fn [<test_ $t _sanity>](e: &Env) { test_nondet_sanity::<$t>(e); }

          #[rule]
          pub fn [<test_ $t _set>](e: &Env) { test_nondet_set::<$t, $t>(e); }

          #[rule]
          pub fn [<test_ $t _set_sanity>](e: &Env) { test_nondet_set_sanity::<$t, $t>(e); }
        }
    }
}

key_test!(u32);
key_test!(u64);
key_test!(u128);
key_test!(T1);
key_test!(T2);
key_test!(T3);
key_test!(T4);
key_test!(E1);
key_test!(E2);
key_test!(E3);
key_test!(E4);

#[rule]
pub fn test_address(e: &Env) { test_key_simple(e, &nondet_address(), &nondet_address()); }

#[rule]
pub fn test_address_set(e: &Env) { test_set_simple(e, &nondet_address(), nondet_address()); }

#[rule]
pub fn test_address_sanity(e: &Env) { test_key_simple_sanity(e, &nondet_address(), &nondet_address()); }

#[rule]
pub fn test_address_set_sanity(e: &Env) { test_set_simple_sanity(e, &nondet_address(), nondet_address()); }
