rule r(env e) {
    C.S s;
    _storageInput(e, s);
    _storageOutput(e);
    freeFunc(e);
    _privateFunc(e); // without this call there is no internal function that is harnessed and we get the hint to add the flag on the unresolved call to freeFunc
    satisfy true;
}
