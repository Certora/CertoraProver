rule r {
    env e;
    address boolPointer = getBoolPointer(e); // should always pint to `True` due to the struct_link
    assert boolPointer.returnsBool(e);
}
