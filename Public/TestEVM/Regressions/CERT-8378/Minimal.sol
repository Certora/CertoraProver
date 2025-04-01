pragma solidity >=0.8.7 <0.9.0;
contract Minimal {
    mapping(bytes28 => uint64[5]) data;
    uint64[5][] adata;

    function test1(bytes28 key) public returns (uint64[5] memory ad) {
        ad = data[key];
    }

    function test2(uint64 key) public returns (uint64[5] memory ad) {
        ad = adata[key];
    }

}
