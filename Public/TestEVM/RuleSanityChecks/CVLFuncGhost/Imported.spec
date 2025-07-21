function my_function(uint a, uint b) {
  require(a < 1000 || b < 1000);
}

function easier_multiplication(uint256 x, uint256 y) returns uint256 {
  my_function(x, y);
  return assert_uint256(x * y);
}
