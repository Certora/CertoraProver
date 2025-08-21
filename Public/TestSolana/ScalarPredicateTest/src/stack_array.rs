use cvlr::prelude::*;

/// Examples that allocate arrays on the stack and iterate over them.

#[derive(Debug, Clone, Copy)]
struct Foo {
    a: u64,
    b: u64,
    c: u64,
}

#[inline(never)]
fn init(arr: &mut [Foo], x: u64) {
    for a in arr {
        let s = Foo{ a: x, b: x+1, c: x+2};
        *a = s;
    }

}

#[inline(never)]
fn post(arr: &[Foo], v: u64) {
    for a in arr {
        cvlr_assert_eq!(a.a, v);
        cvlr_assert_eq!(a.b, v+1);
        cvlr_assert_eq!(a.c, v+2);
    }
}

/// We need -solanaScalarMaxVals N for N > 1 to avoid a PTA error
/// during the memory analysis.
#[rule]
pub fn rule_stack_array_1() {

    let mut arr = [Foo { a: 0, b: 0, c: 0 }; 5]; // stack-allocated array
    let x = nondet();
    init(&mut arr, x);
    post(&arr, x);

}

/// This example produces a PTA error
///
/// Too bad for the scalar (with or without predicatse) analysis
/// because even if we keep track of multiple values for registers and
/// stack offsets, the transfer function for `assume(i<5)` won't
/// generate the state `i=0 || i=1 || ... || i=4
#[rule]
pub fn rule_stack_array_2() {
    let mut arr = [Foo { a: 0, b: 0, c: 0 }; 5]; // stack-allocated array
    let i:usize = nondet();
    cvlr_assume!(i < 5);
    arr[i] = Foo{a:1, b:0, c:0};
    for a in arr {
        cvlr_assert_ge!(a.a, 0);
        cvlr_assert_le!(a.a, 1);
    }
}

/// We need -solanaScalarMaxVals N for N > 1 and
/// -solanaUseScalarPredicateDomain true to avoid a PTA error during
/// the memory analysis.
#[rule]
pub fn rule_stack_array_3() {
    let mut arr = [Foo { a: 0, b: 0, c: 0 }; 5]; // stack-allocated array
    let i:usize = match nondet::<u8>() {
        0 => 0,
        1 => 1,
        2 => 2,
        3 => 3,
        _ => 4
    };
    arr[i] = Foo{a:1, b:0, c:0};

    // The Rust compiler doesn't unroll this loop even if `arr` is small.
    // But it creates a pattern that the scalar domain does not handle precisely but the scalar+predicate does.
    // ```
    // r9 := 0
    // r8 := r10 - x
    // while (r9 != 5) {
    //  r8 = r8 + 24
    //  r9 = r9 + 1
    // }
    // ```
    for a in arr {
        cvlr_assert!(a.a <= 1);
    }
}

/// As rule_stack_array_3 but it traverses the stack-allocated array in reversed order.
#[rule]
pub fn rule_stack_array_4() {
    let mut arr = [Foo { a: 0, b: 0, c: 0 }; 5]; // stack-allocated array
    let i:usize = match nondet::<u8>() {
        0 => 0,
        1 => 1,
        2 => 2,
        3 => 3,
        _ => 4
    };
    arr[i] = Foo{a:1, b:0, c:0};

    // The Rust compiler doesn't unroll this loop even if `arr` is small.
    // But it creates a pattern that the scalar domain does not handle precisely but the scalar+predicate does.
    // ```
    // r9 := 5
    // r8 := r10 - x
    // while (r9 != 0) {
    //  r8 = r8 - 24
    //  r9 = r9 - 1
    // }
    // ```
    //
    // If we use `iter` then the loop is unrolled.
    for a in arr.into_iter().rev() {
        cvlr_assert!(a.a <= 1);
    }
}
