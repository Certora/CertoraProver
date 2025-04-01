function trivial(uint y) returns uint{
    return y;
}

strong invariant trivialInvariant_uint(env e, uint y) (trivialReturn(e,y) == y || trivialReturn(e,y) == currentContract.storageValue) && trivial(y) == y;

strong invariant trivialInvariant_singleArray(env e, uint y, uint[] z) (trivialReturn(e,y) == y || trivialReturn(e,y) == currentContract.storageValue) && trivialUse(e, z).length == z.length;
strong invariant trivialInvariant_multiArray(env e, uint y, uint[][] z)  (trivialReturn(e,y) == y || trivialReturn(e,y) == currentContract.storageValue) && trivialUse(e, z).length == z.length;

strong invariant trivialInvariant_struct(env e, uint y, StrongInvariantsParam.Foo z)  (trivialReturn(e,y) == y || trivialReturn(e,y) == currentContract.storageValue) && trivialUse(e, z) == z;

strong invariant trivialInvariant_bytes(env e, uint y, bytes z)  (trivialReturn(e,y) == y || trivialReturn(e,y) == currentContract.storageValue) && trivialUse(e, z) == z;