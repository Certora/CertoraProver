// this is compilation test (succeeds if compilation succeeds), which attempts to trigger the conditions of CERT-4450
// previously this caused a "redeclared variable" error due to `inner/I.sol` getting imported twice
use builtin rule sanity;
