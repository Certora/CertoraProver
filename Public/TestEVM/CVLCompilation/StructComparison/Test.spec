using Test as test;

methods {
    function getOuterStruct(uint24 a, bytes24 b, int32 d) external returns (Test.OuterStruct memory) envfree;
}

rule compareCVLStructs {
    Test.OuterStruct s1; Test.OuterStruct s2;
    assert s1 == s2 <=> (s1.c.a == s2.c.a && s1.c.b == s2.c.b && s1.d == s2.d);
}

rule compareFunctionReturnStruct {
    Test.OuterStruct s2;
    uint24 a; bytes24 b; int32 d;
    assert getOuterStruct(a, b, d) == s2 <=> (a == s2.c.a && b == s2.c.b && d == s2.d);
}

/*
 * This rule doesn't pass typechecking. It fails with
 * ```
 * `test.s` has type `Test.OuterStruct` (from the VM), which cannot be converted to the expected CVL type `Test.OuterStruct`.
 * Reason(s):
 *     Cannot convert Test.OuterStruct to Test.OuterStruct in context of storage values
 * ```
 *
 * To support this we'd need to implement `storageValueContext` in the `converterTo` function of `EVMStructDescriptor`
 *
 * rule compareDirectStorageAccessStruct {
 *     Test.OuterStruct s1 = test.s; Test.OuterStruct s2;
 *     assert s1 == s2 <=> (test.s.c.a == s2.c.a && test.s.c.b == s2.c.b && test.s.d == s2.d);
 * }
 */
