# Separate contract instances

Run `certoraRun instances.conf` to check two instances of `Counter`.
The rules check that their addresses differ and that a write to `Counter0`
does not change the storage of `Counter1`.

Use `--contract_instance Counter:Counter0 Counter:Counter1` on the command line,
or the `contract_instance` list in a configuration file. The source declaration
must be selected in `files`. The original `Counter` remains in the scene.
Each new instance uses the same compiled code and source types, with its own
address, storage, and immutable values. Source type names retain their original
declaration names. Imported libraries retain their original addresses.

Instance names work with `verify`, `using`, `link`, `struct_link`, and `address`.
For example, `--link Pool:token0=Counter0 Pool:token1=Counter1` selects separate
targets. Links apply only to the named instance; they are not copied from the
original contract. `--address Counter0:0x100 Counter1:0x200` sets fixed addresses.
Instance addresses must be distinct, nonzero, and fit in 160 bits.

This option supports Solidity source contracts. Duplicate names and instances
of other instances are rejected. It cannot be combined with `contract_extensions`,
`storage_extension_harnesses`, or `storage_extension_annotation`.
