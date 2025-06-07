# Internal Function Return Type Merging Test

This test verifies that internal function summaries work correctly when the bytecode lacks return type information.

## Problem
Internal functions parsed from bytecode often don't have return type information (empty `returns` field), while CVL summaries specify return types. This caused an arity mismatch error when trying to merge the signatures.

## Solution
The fix allows CVL to specify return types for internal functions even when the bytecode doesn't provide this information.

## Test Components
- `Context.sol` - Minimal implementation of OpenZeppelin's Context pattern with `_msgSender()` internal function
- `TestContract.sol` - Contract using `_msgSender()` for access control
- `test.spec` - CVL specification that summarizes `_msgSender()` to return `e.msg.sender`

## Test Cases
1. **nonOwnerCannotCallDoSomething** - Verifies that non-owners cannot call protected functions
2. **ownerCanCallDoSomething** - Verifies that owners can call protected functions (using the internal function summary)
