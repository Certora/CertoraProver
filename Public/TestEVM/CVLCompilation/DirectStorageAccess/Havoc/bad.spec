using Test as t;

// `t.a[k][i].bar` is a `bool[]`
rule entire_dynamic_array {
    address k;
    uint i;
    havoc t.a[k][i].bar;

    assert true; // this should be unreachable
}

// `t.a[k][i].gorp` is a `uint48[10]`
rule entire_static_array {
    address k;
    uint i;
    havoc t.a[k][i].gorp;

    assert true; // this should be unreachable
}

// `t.friday` is a `mapping (address => uint128)`
rule entire_mapping {
    havoc t.friday;

    assert true; // this should be unreachable
}

// `t.nestedMap[b]` is a `mapping(string => OuterStruct)`
rule entire_nested_mapping {
    bytes b;
    havoc t.nestedMap[b];

    assert true; // this should be unreachable
}

// `t.nestedMap[b][str]` is a `struct OuterStruct`
rule outer_struct {
    bytes b;
    string str;
    havoc t.nestedMap[b][str];

    assert true; // this should be unreachable
}

// `t.nestedMap[b][str].foo` is a `struct InnerStruct`
rule inner_struct {
    bytes b;
    string str;
    havoc t.nestedMap[b][str].foo;

    assert true; // this should be unreachable
}

// `t.smells` is an `enum Smells`
rule enum {
    havoc t.smells;

    assert true; // this should be unreachable
}
