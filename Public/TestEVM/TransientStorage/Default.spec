methods {
    function TransientTest.lock() external returns (bool) envfree;
    function TransientTest.unlock() external returns (bool) envfree;
    function TransientTest.isLocked() external returns (bool) envfree;
}

use builtin rule sanity;

ghost bool ghostLocked;


// tload/tstore hooks for locked field.
hook Tload bool value (slot 0) {
    require ghostLocked == value;
}

hook Tstore currentContract.locked bool value (bool oldValue) {
    require ghostLocked == oldValue;
    ghostLocked = value;
}


rule lockLocks() {
    require !currentContract.locked;
    lock();
    assert currentContract.isLocked();
    assert ghostLocked;
}

rule unlockUnlocks() {
    require currentContract.locked;
    unlock();
    assert !currentContract.locked;
    assert !ghostLocked;
}

rule TloadHookWorks() {
    bool b = currentContract.isLocked();
    assert ghostLocked <=> b;
}
