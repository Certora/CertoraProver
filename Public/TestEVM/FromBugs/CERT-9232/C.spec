// The proxy forwards its whole calldata buffer with a raw staticcall, so the callee carries no
// resolved selector and the dispatch list is what routes it to the implementation in the scene.
// The list is optimistic: without that, the default case havocs the return value and the rules
// below would be checked against an arbitrary result rather than against what the callee echoed.
methods {
    unresolved external in ViewProxy._ =>
        DISPATCH(optimistic=true) [ Impl.echoOne(address), Impl.echoTwo(address, uint256) ];
}

// Setting up the forwarded call binds the word aligned ranges of the proxy's calldata layout. The
// implementation echoes what it received, so these rules pin the exact bytes that arrived: an
// argument that is blanked, shifted, or unconstrained cannot echo back what the caller sent.

// 36 bytes of calldata: the word at offset 32 covers the low 4 bytes of the address, then runs past
// the end of the buffer.
rule oneArgumentArrivesIntact(env e, address key) {
    address echoed = callEchoOne(e, key);
    assert echoed == key;
}

// 68 bytes of calldata: the word at offset 32 straddles the tail of the first argument and the head
// of the second, so an off-by-four splice shows up here even though it would look plausible with a
// single argument. The word at offset 64 is live too.
rule twoArgumentsArriveIntact(env e, address key, uint256 tag) {
    address echoedKey;
    uint256 echoedTag;
    echoedKey, echoedTag = callEchoTwo(e, key, tag);
    assert echoedKey == key;
    assert echoedTag == tag;
}
