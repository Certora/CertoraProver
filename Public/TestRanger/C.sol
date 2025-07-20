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

 contract C {
    int8 n;
    mapping (address => int8) m;

    function setN1() public {
        n = 1;
    }

    function addN1() public {
        n += 1;
    }

    function subN2() public {
        if (n >= 1) {
            n -= 2;
        } else {
            revert();
        }
    }

    function setM1(address a) public {
        m[a] = 1;
    }

    function addM1(address a) public {
        m[a] += 1;
    }

    function subM2(address a) public {
        if (m[a] >= 1) {
            m[a] -= 2;
        } else {
            revert();
        }
    }

    function setNandM1(address a) public {
        setN1(); setM1(a);
    }

    function addNandM1(address a) public {
        addN1(); addM1(a);
    }

    function getN() public returns (int8) {
        return n;
    }

    function getM(address a) public returns (int8) {
        return m[a];
    }

    function alwaysReverts() public { revert(); }
}
