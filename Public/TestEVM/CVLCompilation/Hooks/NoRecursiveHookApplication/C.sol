contract C {
  uint public x;
  function main() external {
    x = 3;
  }

  function foo() external {
    myInternalFoo();
  }

  function myInternalFoo() internal {
    // ...
  }

  function bar() external {
    x = 5;
  }

}
