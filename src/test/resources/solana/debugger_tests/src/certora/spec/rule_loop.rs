use cvlr::prelude::*;
use std::hint::black_box;

#[rule]
fn rule_loop() {
    // Local variables that can be inspected in a debugger
    let mut sum = 0;
    let mut product = 1;
    let limit = 10;
    let multiplier = 3;

    // Loop that performs work that LLVM cannot easily inline or optimize away
    for i in 0..limit {
        // Use black_box to prevent the compiler from optimizing away the loop
        let iteration = black_box(i);

        // Perform computation using the non-inlinable function
        let result = complex_computation(iteration, multiplier);

        // Update local variables
        sum = black_box(sum + result);
        product = black_box(product * (iteration + 1));

        // Print to ensure side effects (prevents dead code elimination)
        println!("Iteration {}: result={}, sum={}, product={}",
                 iteration, result, sum, product);
    }

    // Final values that can be inspected
    let final_sum = sum;
    let final_product = product;

    println!("\nFinal values:");
    println!("Sum: {}", final_sum);
    println!("Product: {}", final_product);
    cvlr_assert!(final_product == 0);
}

// Mark this function as never inline to prevent LLVM from inlining it
#[inline(never)]
fn complex_computation(x: i32, y: i32) -> i32 {
    // Use black_box to prevent optimizations
    black_box(x * y + x - y)
}