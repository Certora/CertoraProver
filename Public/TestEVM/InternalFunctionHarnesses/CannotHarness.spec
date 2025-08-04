rule r(env e) {
    C.S s;
    _storageInput(e, s);
    _storageOutput(e);
    freeFunc(e);
    satisfy true;
}
