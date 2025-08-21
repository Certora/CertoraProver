pragma solidity ^0.8.21;

contract Test
{
    MyInterface a;
    function foo()
        external
        returns (uint256 ratio)
    {
        return baz(a);
    }

    function baz(MyInterface a)
        internal
        view
        returns (uint256 state)
    {
        (
            uint256 x,
            uint256 y,
            ,
            uint256 z,
            ,
        ) = a.bar(address(this));

        return x;
    }
}

interface MyInterface {
  function bar(
    address user
  )
    external
    view
    returns (
      uint256 a,
      uint256 b,
      uint256 c,
      uint256 d,
      uint256 e,
      uint256 f
    );
}
