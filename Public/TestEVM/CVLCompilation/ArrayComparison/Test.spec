using Test as test;

methods {
    function createSimpleStruct(uint256 x, int128 y) external returns (Test.SimpleStruct memory) envfree;
    function createIntArray(uint256 a, uint256 b, uint256 c) external returns (uint256[] memory) envfree;
    function createStructWithArray(uint256 id, uint256 a, uint256 b) external returns (Test.StructWithArray memory) envfree;
}

// Test 1: Compare primitive arrays (uint256[])
rule comparePrimitiveArrays {
    uint256[] arr1; uint256[] arr2;

    // Can't use `<=>` here because that causes double polarity of the quantifier and breaks grounding
    assert arr1 == arr2 => (arr1.length == arr2.length && (forall uint256 i. i < arr1.length => arr1[i] == arr2[i]));
    assert (arr1.length == arr2.length && (forall uint256 i. i < arr1.length => arr1[i] == arr2[i])) => arr1 == arr2;
}

// Test 2: Reflexivity of array comparison
rule arrayComparisonReflexive {
    uint256[] arr;
    assert arr == arr;
}

// Test 3: Symmetry of array comparison
rule arrayComparisonSymmetric {
    uint256[] arr1; uint256[] arr2;
    assert (arr1 == arr2) == (arr2 == arr1);
}

// Test 4: Compare function return array
rule compareFunctionReturnArray {
    uint256 a; uint256 b; uint256 c;
    uint256[] arr;
    require arr.length == 3;
    require arr[0] == a && arr[1] == b && arr[2] == c;
    assert createIntArray(a, b, c) == arr;
}

// Test 5: Compare structs containing arrays
rule compareStructsWithArrays {
    Test.StructWithArray s1; Test.StructWithArray s2;
    assert s1 == s2 <=> (s1.id == s2.id && s1.values == s2.values);
}

// Test 6: Compare structs with array - reflexive
rule structWithArrayReflexive {
    Test.StructWithArray s;
    assert s == s;
}

// Test 7: Function returning struct with array
rule compareFunctionReturnStructWithArray {
    uint256 id; uint256 a; uint256 b;
    Test.StructWithArray s;
    require s.id == id;
    require s.values.length == 2;
    require s.values[0] == a && s.values[1] == b;
    assert createStructWithArray(id, a, b) == s;
}

// Test 8: Different lengths means not equal
rule differentLengthsNotEqual {
    uint256[] arr1; uint256[] arr2;
    require arr1.length != arr2.length;
    assert arr1 != arr2;
}

// Test 9: Empty arrays are equal
rule emptyArraysEqual {
    uint256[] arr1; uint256[] arr2;
    require arr1.length == 0;
    require arr2.length == 0;
    assert arr1 == arr2;
}

// Test 10: Compare int256 arrays
rule compareIntArrays {
    int256[] arr1; int256[] arr2;

    // Can't use `<=>` here because that causes double polarity of the quantifier and breaks grounding
    assert arr1 == arr2 => (arr1.length == arr2.length && (forall uint256 i. i < arr1.length => arr1[i] == arr2[i]));
    assert (arr1.length == arr2.length && (forall uint256 i. i < arr1.length => arr1[i] == arr2[i])) => arr1 == arr2;
}

// Test 11: Compare struct with nested struct and array field
rule compareStructWithNestedAndArray {
    Test.StructWithNestedAndArray s1; Test.StructWithNestedAndArray s2;
    assert s1 == s2 <=> (s1.inner.x == s2.inner.x && s1.inner.y == s2.inner.y && s1.arr == s2.arr);
}
