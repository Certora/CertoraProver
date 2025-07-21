contract RuleSanityChecks {

  uint public i;

  function require_i_lowerthan_10() public {
    require(i < 10);
  }

  function require_nothing() public {
    require(true);
  }


  function always_revert() public {
    revert("reverting");
  }

}
