# EIP-7843: SLOTNUM

No released Solidity or Vyper compiler emits `SLOTNUM` (0x4b), so it reaches
the bytecode through Yul's `verbatim`, which the Prover already supports via
`yul_abi` (see `Test/TestEVM/Yul`). Unlike the EIP-8024 instructions this one
takes no operands, so `verbatim_0i_1o(hex"4b")` is the whole story and the
contract needs no generator.

`slotnumDelta` executes `SLOTNUM` twice in one transaction and returns the
difference. `Default.conf` leaves `solc_optimize` off so solc keeps both
executions instead of folding them into one.

## Coverage

The slot number is modelled as a property of the block rather than of the
call: `SLOTNUM` reads the `tacSlotnum` keyword, which the invocation compiler
assigns from `e.block.slotnum`. The rules divide along that seam.

| rule | covers |
|---|---|
| `slotNumFitsInUint64` | the `uint64` bound, which comes from the field's type via `ensureBitWidth` rather than from any assumption in this spec |
| `slotNumIsStableWithinATransaction` | two executions in one frame reading the same keyword |
| `slotNumIsStableAcrossCallsInTheSameBlock` | two invocations with one `env` agreeing, which is what the field buys over a per-frame havoc |
| `differentEnvsCanHaveDifferentSlotNums` | the dual of the above: two `env`s are free to disagree, so the field is not pinned to a constant |
| `slotNumMatchesEnv` | the keyword actually carrying what the `env` was set up with |
| `slotNumHookObservesTheValue` | the opcode hook, which is independent of the field |

`slotNumMatchesEnv` is the `Test/TestEVM/EnvChecks` property -
`currentContract._number == e.block.number` - written the only way it can be
for an opcode no compiler emits. Deleting the `slotnum` case from
`CVLInvocationCompiler.environmentSetup` fails it, along with
`slotNumFitsInUint64` and `slotNumIsStableAcrossCallsInTheSameBlock`, and
leaves the other two green.

`differentEnvsCanHaveDifferentSlotNums` is the only rule here that would fail
if the keyword were pinned to a constant - both stability rules would still
pass - so it is what keeps them from being satisfied by an over-constrained
keyword.

`slotNumHookObservesTheValue` covers what `Test/TestEVM/OpcodeHooks` cannot:
every rule there calls a Solidity function that emits the opcode under test.
It uses a ghost rather than contract storage so it stays inside the
hand-written Yul contract, and it does not depend on the `env` field at all.

## Reading a failure

`expectedDefault.json` expects every rule to pass. Which one fails narrows
down what broke.

All six failing at once means the opcode is not decoding:
`Disassembler.bytelistToEVMAssembly` maps an unrecognised opcode to `REVERT`,
so every call reverts and the rules that assume a successful call are vacuous
rather than violated. Watch for `SANITY_FAIL` alongside the failures.

`slotNumHookObservesTheValue` alone means the opcode decodes and reaches TAC
but the hook does not fire - the `@HookableOpcode` annotation, the `HookType`
entry, or the `TACHook` arm.

Any of the other four alone points at `block.slotnum` rather than at the
opcode. `slotNumMatchesEnv` is the most specific of them: it fails only if the
keyword and the field have come apart, while
`differentEnvsCanHaveDifferentSlotNums` is the one that catches the keyword
being tied to a single value across `env`s.
