methods {
    function currentContract._ external => HAVOC_ALL;
    function _.bar(address, address) external => DISPATCHER(true);
}

rule recurse(method f) {
    address x;
    address y;
    env e;
    require currentContract.fooCalled == 0 && currentContract.barCalled == 0, "Some initial values";
    foo(e, x, y);
    satisfy currentContract.fooCalled == 999 && currentContract.barCalled == 42, "there should be a HAVOC_ALL that will allow these values";
}
