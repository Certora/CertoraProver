# Test Information

The wasm files correspond to building the target multiple times with different opt levels:

```
RUSTFLAGS="-C opt-level=<level>" cargo build --target=wasm32-unknown-unknown --release
```

and copying target/wasm32-unknown-unknown/release/i128_overflow_tests.wasm to the root dir
