contract Test {
	struct Struct {
		uint96 baz;
		bool[] bar;
		uint48[10] gorp;
	}

	mapping (address => Struct[6]) public a;

	uint24 public topLevel1;
	uint48 public topLevel2;
	bool public topLevel3;

	mapping (bytes => uint) public topLevelMap;

	int32 public topLevelSigned;

	struct InnerStruct {
		uint96 x;
		uint96 y;
		bool z;
	}

	struct OuterStruct {
		uint8 a;
		InnerStruct foo;
	}

	mapping (bytes => mapping(string => OuterStruct)) nestedMap;

    struct StructWithMapping {
        uint8 a;
        mapping (uint256 => address) m;
    }

    StructWithMapping[] public structWithMapping;

	function getter1(address k, uint i) external returns (uint96) {
		return a[k][i].gorp[i];
	}

	function getter2(address k, uint i) external returns (bool) {
		return a[k][i].bar[i];
	}

	function getter3(address k, uint i) external returns (uint) {
		return a[k][i].bar.length;
	}

	function getter4(bytes memory k1, string memory k2) external returns (uint96) {
		return nestedMap[k1][k2].foo.y;
	}

	function getter5(address k, uint i) external returns (uint96) {
		return a[k][i].baz;
	}

    function getter6(uint k, uint i) external returns (address) {
        return structWithMapping[i].m[k];
    }

	function readMap(bytes memory k) external returns (uint) {
		return topLevelMap[k];
	}

	function getStoppedState() external returns (bool) {
		return topLevel3;
	}

	function getTokenPrice(address k) external returns (uint96) {
		return a[k][0].baz;
	}

	function getPriceOracle(bytes memory k) external returns (uint) {
		return topLevelMap[k];
	}

	function getTopLevels() external returns (uint24, uint48, bool) {
		return (topLevel1, topLevel2, topLevel3);
	}

	function getSigned() external view returns (int32) {
		return topLevelSigned;
	}

	bytes public theBytes;

	function typeConstraintsPreserved() external view returns (bool) {
		return 0 <= topLevel1 && topLevel1 <= 2**24 - 1;
	}

	mapping (address => uint128) public friday;

	function setFriday(address addr, uint128 value) external {
		friday[addr] = value;
	}

	Smells public smells;
}

enum Smells { Rose, Chocolate, Cheese, Lilac }
