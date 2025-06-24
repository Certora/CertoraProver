/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY, without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

using C as c;

methods {
    function getN() external returns (int8) envfree;
}

invariant nNonNegative() getN() >= 0;

rule nNonNegativeRule {
    assert c.n >= 0;
}

invariant mCurrentContractNonNegative() c.m[currentContract] >= 0 {
    preserved setM1(address a) with (env e) {
        require a != currentContract;
    }
}

invariant mInvariantParamInPreserved(address b) c.m[b] >= 0 {
    preserved addM1(address a) with (env e) {
        require getM(e, b) >= 0;
    }
}

function setup() {}
