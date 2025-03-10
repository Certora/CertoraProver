#![no_std]

#[no_mangle]
pub fn test_overflow_add(x: i128, y: i128) {
    cvlr::cvlr_assume!(x >= 0 && y >= 0);
    cvlr::cvlr_assert!(x <= x + y);
}

#[no_mangle]
pub fn test_underflow_add(x: i128, y: i128) {
    cvlr::cvlr_assume!(x <= 0 && y <= 0);
    cvlr::cvlr_assert!(x >= x + y);
}

#[no_mangle]
pub fn test_overflow_add_sanity(x: i128, y: i128) {
    cvlr::cvlr_assume!(x >= 0 && y >= 0);
    cvlr::cvlr_satisfy!(x <= x + y);
}

#[no_mangle]
pub fn test_underflow_add_sanity(x: i128, y: i128) {
    cvlr::cvlr_assume!(x <= 0 && y <= 0);
    cvlr::cvlr_satisfy!(x >= x + y);
}

#[cfg(all(not(feature = "alloc"), target_family = "wasm"))]
#[panic_handler]
fn handle_panic(_: &core::panic::PanicInfo) -> ! {
    core::arch::wasm32::unreachable()
}
