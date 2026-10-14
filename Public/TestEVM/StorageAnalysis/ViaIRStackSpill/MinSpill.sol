// SPDX-License-Identifier: MIT
pragma solidity ^0.8.4;

/// Minimal repro: via-IR spills a computed mapping slot ("stack too deep" mover),
/// and the prover's storage analysis loses the slot value through the memory round-trip.
contract MinSpill {
  struct PackedState {
    uint104 balA;
    uint104 balB;
    uint16 aux0;
    uint16 aux1;
    uint16 aux2;
  }

  struct DeltaPair {
    int256 dA;
    int256 dB;
  }

  mapping(int256 => PackedState) internal _states;
  mapping(int256 => uint256) internal _totals;
  mapping(bytes32 => uint256) internal _userShares;

  function remove(address owner, uint80 salt, int256[] calldata keys, uint256[] calldata shares)
    external
    returns (uint256 total0, uint256 total1)
  {
    unchecked {
      DeltaPair[] memory deltas = new DeltaPair[](keys.length);

      for (uint256 i = 0; i < keys.length; i++) {
        int256 key = keys[i];
        uint256 toRemove = shares[i];
        if (toRemove == 0) continue;

        bytes32 userKey = keccak256(abi.encode(owner, salt, int8(key)));
        uint256 totalVal = _totals[key];
        uint256 userVal = _userShares[userKey];
        require(userVal >= toRemove);

        PackedState storage state = _states[key];
        uint256 amount0 = (state.balA * toRemove) / totalVal;
        uint256 amount1 = (state.balB * toRemove) / totalVal;

        state.balA -= uint104(amount0);
        state.balB -= uint104(amount1);
        _totals[key] = totalVal - toRemove;
        _userShares[userKey] = userVal - toRemove;

        total0 += amount0;
        total1 += amount1;

        deltas[i] = DeltaPair({dA: -int256(amount0), dB: -int256(amount1)});
      }

    }
  }

  function getBalA(int256 k) external view returns (uint104) {
    return _states[k].balA;
  }
}
