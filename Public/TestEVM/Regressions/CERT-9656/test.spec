methods {
    function Test.pleaseSummarize(uint) external returns (uint) => 4;
}

rule basic_rule {
    env e;
    assert entry(e) == 4;
}