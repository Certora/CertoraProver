using First as first;
using Second as second;

rule separateLinks(env e) {
    assert first == 0x100 && second == 0x200;
    assert first.getTokenValue(e) == 42;
    assert second.getTokenValue(e) == 100;
    assert first.getImmutableTokenValue(e) == 42;
    assert second.getImmutableTokenValue(e) == 100;
}
