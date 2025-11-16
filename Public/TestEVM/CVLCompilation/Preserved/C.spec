using D as d;

methods {
    function d.initialized_at() external returns (uint) envfree;
}

invariant holdsWithParamRestrictions()
    currentContract.x != 42
    {
        // TODO(CERT-9755): Currently not possible to capture constructor parameters in preserved
        //preserved constructor(uint i) with (env e) {
        //   require i != 42;
        //}
        preserved foo(uint i) with (env e) {
            require i != 42;
        }
    }

invariant holdsWithEnvRestrictions()
    currentContract.timestamp > 10
    {
        preserved constructor() with (env e) {
            require e.block.timestamp > 10;
        }
        preserved foo(uint i) with (env e) {
            require e.block.timestamp > 10;
        }
    }

invariant DInitializedBeforeC()
    currentContract.timestamp > 0 => d.initialized_at() > 0
    {
        preserved constructor() {
            // TODO(CERT-9671) This is a SANITY_FAIL for now because D's storage is zeroed. It will become a success.
            require d.initialized_at() != 0, "assume D was initialized already when calling Cs constructor";
        }
        preserved foo(uint i) with (env e) {
            require currentContract.timestamp > 0;
        }
    }
