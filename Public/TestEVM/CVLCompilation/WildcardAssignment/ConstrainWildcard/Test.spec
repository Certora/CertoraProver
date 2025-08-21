methods {
    function _.bar(address user) external   => bar_nondet(user) expect (uint256,uint256,uint256,uint256,uint256,uint256);
}



 function bar_nondet(address user) returns (uint256,uint256,uint256,uint256,uint256,uint256)
{
        return (_, _, _, _, _, _);
}


rule test
{
    env e1; env e2;
    foo(e1);
    assert true;
}
